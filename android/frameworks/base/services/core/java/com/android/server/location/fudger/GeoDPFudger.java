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

/**
 * Implements geo-indistinguishability (planar Laplace) based location obfuscation.
 * Uses global noise offsets refreshed once per interval, mirroring LocationFudger control flow.
 */
public class GeoDPFudger implements LocationObfuscationInterface {
    private static final float MIN_ACCURACY_M = 200.0f;
    @VisibleForTesting
    static final long NOISE_UPDATE_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final int APPROX_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROX_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final double mEpsilon;
    private final Clock mClock;
    private final Random mRandom;

    // Global offsets applied to all calls within the same interval
    @GuardedBy("this")
    private double mLatitudeOffsetM;
    @GuardedBy("this")
    private double mLongitudeOffsetM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    // Caches for single Location
    @GuardedBy("this") @Nullable private Location mCachedFineLocation;
    @GuardedBy("this") @Nullable private Location mCachedCoarseLocation;
    // Caches for LocationResult
    @GuardedBy("this") @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this") @Nullable private LocationResult mCachedCoarseLocationResult;

    /** Public constructor initializes clock, random, accuracy, and seeds offsets. */
    public GeoDPFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    /** @VisibleForTesting: inject Clock and Random for deterministic tests. */
    GeoDPFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
        mEpsilon = 2.0 / mAccuracyM;  // calibrate privacy budget
        resetOffsets(); // seed initial noise offsets and timer
    }

    /** Reset the noise offsets and timer (for testing or on demand). */
    public synchronized void resetOffsets() {
        // Sample new planar Laplace offsets for latitude & longitude
        double[] offsets = samplePlanarLaplaceOffsets();
        mLatitudeOffsetM = offsets[0];
        mLongitudeOffsetM = offsets[1];
        // Schedule next refresh
        mNextUpdateRealtimeMs = mClock.millis() + NOISE_UPDATE_INTERVAL_MS;
        // Clear caches so new offsets apply immediately
        mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
    }

    @Override
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

    @Override
    public Location createCoarse(Location fine) {
        updateNoise(); // refresh offsets if interval elapsed
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }
        // Clone and strip metadata
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);
        // Base coordinates clamp
        double lat = wrapLatitude(coarse.getLatitude());
        double lon = wrapLongitude(coarse.getLongitude());
        // Apply global offsets
        synchronized (this) {
            lat += metersToDegreesLatitude(mLatitudeOffsetM);
            lon += metersToDegreesLongitude(mLongitudeOffsetM, lat);
        }
        // Wrap to valid range
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);
        // Set new values & accuracy
        coarse.setLatitude(lat);
        coarse.setLongitude(lon);
        coarse.setAccuracy(Math.max(mAccuracyM, coarse.getAccuracy()));
        // Cache
        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }
        return coarse;
    }

    /** Refresh offsets once per interval, clearing caches on rollover. */
    private synchronized void updateNoise() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return; // not yet time
        }
        // Reseed global offsets
        double[] offsets = samplePlanarLaplaceOffsets();
        mLatitudeOffsetM = offsets[0];
        mLongitudeOffsetM = offsets[1];
        // Clear caches so new offsets take effect
        mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
        // Schedule next refresh
        mNextUpdateRealtimeMs = now + NOISE_UPDATE_INTERVAL_MS;
    }

    /** Sample a pair of planar Laplace offsets (in meters). */
    private double[] samplePlanarLaplaceOffsets() {
        // Draw two uniforms
        double u = mRandom.nextDouble();
        double v = mRandom.nextDouble();
        // Radius from Gamma(shape=2, scale=1/ε)
        double radius = -Math.log(u * v) / mEpsilon;
        double theta = 2 * Math.PI * mRandom.nextDouble();
        double dy = radius * Math.sin(theta);
        double dx = radius * Math.cos(theta);
        return new double[]{dy, dx};
    }

    // Helper methods copied unchanged:
    private static double wrapLatitude(double lat) {
        if (lat > MAX_LATITUDE) lat = MAX_LATITUDE;
        if (lat < -MAX_LATITUDE) lat = -MAX_LATITUDE;
        return lat;
    }
    private static double wrapLongitude(double lon) {
        lon %= 360.0;
        if (lon >= 180.0) lon -= 360.0;
        if (lon < -180.0) lon += 360.0;
        return lon;
    }
    private static double metersToDegreesLatitude(double m) {
        return m / APPROX_METERS_PER_DEGREE_AT_EQUATOR;
    }
    private static double metersToDegreesLongitude(double m, double lat) {
        return m / (APPROX_METERS_PER_DEGREE_AT_EQUATOR * Math.cos(Math.toRadians(lat)));
    }
}
