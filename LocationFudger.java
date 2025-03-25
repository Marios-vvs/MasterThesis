/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.location.fudger;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;

import android.util.Log;

/**
 * Contains the logic to obfuscate (fudge) locations for coarse applications. The goal is just to
 * prevent applications with only the coarse location permission from receiving a fine location.
 *
 * This version replaces the original Gaussian noise mechanism in `createCoarse()` with a
 * two-dimensional Laplace noise mechanism, as described in:
 * "Masking Location Streams in the Presence of Colluding Service Providers".
 *
 * The rest of the original code remains unchanged.
 */
public class LocationFudger {

    private static final String TAG = "LocationFudger";

    // Minimum accuracy a coarsened location can have
    private static final float MIN_ACCURACY_M = 200.0f;

    // How often random offsets are updated
    @VisibleForTesting
    static final long OFFSET_UPDATE_INTERVAL_MS = 60 * 60 * 1000;

    // The percentage that we change the random offset at every interval.
    private static final double CHANGE_PER_INTERVAL = 0.03;  // 3% change

    // Weights used to move the random offset.
    private static final double NEW_WEIGHT = CHANGE_PER_INTERVAL;
    private static final double OLD_WEIGHT = Math.sqrt(1 - NEW_WEIGHT * NEW_WEIGHT);

    // Approximate conversion: 111,000 meters per degree at the equator.
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;

    // Avoid division by zero errors
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final Clock mClock;
    private final Random mRandom;

    @GuardedBy("this")
    private double mLatitudeOffsetM;
    @GuardedBy("this")
    private double mLongitudeOffsetM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    @GuardedBy("this")
    @Nullable private Location mCachedFineLocation;
    @GuardedBy("this")
    @Nullable private Location mCachedCoarseLocation;

    @GuardedBy("this")
    @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this")
    @Nullable private LocationResult mCachedCoarseLocationResult;

    public LocationFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    LocationFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
        resetOffsets();
    }

    public void resetOffsets() {
        mLatitudeOffsetM = nextRandomOffset();
        mLongitudeOffsetM = nextRandomOffset();
        mNextUpdateRealtimeMs = mClock.millis() + OFFSET_UPDATE_INTERVAL_MS;
    }

    public LocationResult createCoarse(LocationResult fineLocationResult) {
        synchronized (this) {
            if (fineLocationResult == mCachedFineLocationResult
                    || fineLocationResult == mCachedCoarseLocationResult) {
                return mCachedCoarseLocationResult;
            }
        }

        LocationResult coarseLocationResult = fineLocationResult.map(this::createCoarse);

        synchronized (this) {
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
        }
        return coarseLocationResult;
    }

    /**
     * Applies Laplace noise to obfuscate the location.
     */
    public Location createCoarse(Location fine) {
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }

        // Log original location.
        Log.d(TAG, "Original location: (" + fine.getLatitude() + ", " + fine.getLongitude() + ")");

        // Create a copy of the fine location and remove fine-grained fields.
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        double latitude = wrapLatitude(coarse.getLatitude());
        double longitude = wrapLongitude(coarse.getLongitude());

        // Apply two-dimensional Laplace noise.
        double[] deltaMeters = nextLaplace2DOffset();
        Log.d(TAG, "Laplace noise added (meters): deltaNorth = " + deltaMeters[0] +
                ", deltaEast = " + deltaMeters[1]);
        double deltaLat = metersToDegreesLatitude(deltaMeters[0]);
        double deltaLon = metersToDegreesLongitude(deltaMeters[1], latitude);
        latitude = wrapLatitude(latitude + deltaLat);
        longitude = wrapLongitude(longitude + deltaLon);
        Log.d(TAG, "Location after Laplace noise (pre-quantization): (" + latitude + ", " + longitude + ")");

        // Snap the location to a grid.
        double latGranularity = metersToDegreesLatitude(mAccuracyM);
        latitude = wrapLatitude(Math.round(latitude / latGranularity) * latGranularity);
        double lonGranularity = metersToDegreesLongitude(mAccuracyM, latitude);
        longitude = wrapLongitude(Math.round(longitude / lonGranularity) * lonGranularity);

        Log.d(TAG, "Final obfuscated location after quantization: (" + latitude + ", " + longitude + ")");

        coarse.setLatitude(latitude);
        coarse.setLongitude(longitude);
        coarse.setAccuracy(Math.max(mAccuracyM, coarse.getAccuracy()));

        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }
        return coarse;
    }

    /**
     * Generates a two-dimensional Laplace noise offset.
     */
    private double[] nextLaplace2DOffset() {
        double b = mAccuracyM / 4.0;
        double angle = 2 * Math.PI * mRandom.nextDouble();  // Uniform angle [0, 2π)
        double u = mRandom.nextDouble();
        double r = -b * Math.log(1 - u);  // Exponential noise magnitude with mean b.
        double deltaNorth = r * Math.cos(angle);
        double deltaEast = r * Math.sin(angle);
        return new double[]{deltaNorth, deltaEast};
    }

    private synchronized void updateOffsets() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }

        mLatitudeOffsetM = (OLD_WEIGHT * mLatitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mLongitudeOffsetM = (OLD_WEIGHT * mLongitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mNextUpdateRealtimeMs = now + OFFSET_UPDATE_INTERVAL_MS;
    }

    private double nextRandomOffset() {
        return mRandom.nextGaussian() * (mAccuracyM / 4.0);
    }

    private static double wrapLatitude(double lat) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
    }

    private static double wrapLongitude(double lon) {
        lon %= 360.0;
        if (lon >= 180.0) {
            lon -= 360.0;
        }
        if (lon < -180.0) {
            lon += 360.0;
        }
        return lon;
    }

    private static double metersToDegreesLatitude(double meters) {
        return meters / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    private static double metersToDegreesLongitude(double meters, double lat) {
        return meters / (APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR * Math.cos(Math.toRadians(lat)));
    }
}
