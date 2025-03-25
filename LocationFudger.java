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
 * Contains the logic to obfuscate (fudge) locations for coarse applications.
 * This updated version uses a two-dimensional Laplace noise mechanism to add noise
 * to the latitude and longitude, following the approach in:
 * "Masking Location Streams in the Presence of Colluding Service Providers" :contentReference[oaicite:0]{index=0}&#8203;:contentReference[oaicite:1]{index=1}.
 */
public class LocationFudger {

    private static final String TAG = "TestObfuscation";

    // minimum accuracy a coarsened location can have (in meters)
    private static final float MIN_ACCURACY_M = 200.0f;

    // how often the cached obfuscation is updated (for caching purposes)
    @VisibleForTesting
    static final long OFFSET_UPDATE_INTERVAL_MS = 60 * 60 * 1000;

    // approximate conversion: 111,000 meters per degree at the equator.
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;

    // Avoid division by zero at extreme latitudes.
    private static final double MAX_LATITUDE = 90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final Clock mClock;
    private final Random mRandom;

    @GuardedBy("this")
    private Location mCachedFineLocation;
    @GuardedBy("this")
    private Location mCachedCoarseLocation;

    @GuardedBy("this")
    @Nullable
    private LocationResult mCachedFineLocationResult;
    @GuardedBy("this")
    @Nullable
    private LocationResult mCachedCoarseLocationResult;

    public LocationFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    LocationFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
    }

    /**
     * Coarsens a LocationResult by applying the two-dimensional Laplace noise mechanism
     * to every location in the result.
     */
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
     * Create a coarse location using a two-dimensional Laplace noise mechanism
     * and snap-to-grid quantization.
     *
     * The algorithm:
     * 1. Removes fine-grained details (bearing, speed, altitude, extras).
     * 2. Adds noise by sampling a uniform angle and a noise magnitude from an exponential distribution,
     *    then converts these offsets (in meters) to degrees.
     * 3. Snaps the resulting coordinates to a grid based on the target accuracy.
     */
    public Location createCoarse(Location fine) {
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }

        // Create a copy of the fine location and remove fields that may leak extra information.
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        // Get original coordinates and ensure they are in valid ranges.
        double latitude = wrapLatitude(coarse.getLatitude());
        double longitude = wrapLongitude(coarse.getLongitude());

        // ADDED: Add two-dimensional Laplace noise to the location.
        // This mechanism samples a random direction and a noise magnitude, following
        // a Laplace (exponential for the absolute value) distribution.
        double[] deltaMeters = nextLaplace2DOffset();
        // Convert meter offsets to degrees.
        double deltaLat = metersToDegreesLatitude(deltaMeters[0]);
        double deltaLon = metersToDegreesLongitude(deltaMeters[1], latitude);
        latitude = wrapLatitude(latitude + deltaLat);
        longitude = wrapLongitude(longitude + deltaLon);

        // Snap-to-grid quantization based on the defined accuracy.
        double latGranularity = metersToDegreesLatitude(mAccuracyM);
        latitude = wrapLatitude(Math.round(latitude / latGranularity) * latGranularity);
        double lonGranularity = metersToDegreesLongitude(mAccuracyM, latitude);
        longitude = wrapLongitude(Math.round(longitude / lonGranularity) * lonGranularity);

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
     * ADDED: Generates a two-dimensional Laplace noise offset.
     * The method samples:
     * - A uniform angle in [0, 2π)
     * - A noise magnitude (radius) using an exponential distribution with mean b, where
     *   b is set relative to the target accuracy.
     *
     * The resulting offset is decomposed into a north-south component and an east-west component, both in meters.
     *
     * @return a two-element array where index 0 is the noise in the north-south direction (meters)
     *         and index 1 is the noise in the east-west direction (meters).
     */
    private double[] nextLaplace2DOffset() {
        // Privacy scale parameter; adjust this value to tune the noise level.
        double b = mAccuracyM / 4.0;
        // Sample a uniform angle between 0 and 2π.
        double angle = 2 * Math.PI * mRandom.nextDouble();
        // Sample noise magnitude from an exponential distribution with mean b.
        double u = mRandom.nextDouble();
        double r = -b * Math.log(1 - u);
        // Decompose the radius into north-south and east-west offsets.
        double deltaNorth = r * Math.cos(angle);
        double deltaEast = r * Math.sin(angle);
        return new double[]{deltaNorth, deltaEast};
    }

    /**
     * Ensures that latitude is within valid bounds.
     */
    private static double wrapLatitude(double lat) {
        if (lat > MAX_LATITUDE) {
            lat = MAX_LATITUDE;
        }
        if (lat < -MAX_LATITUDE) {
            lat = -MAX_LATITUDE;
        }
        return lat;
    }

    /**
     * Ensures that longitude is within valid bounds.
     */
    private static double wrapLongitude(double lon) {
        lon %= 360.0;  // Wrap into range (-360.0, +360.0)
        if (lon >= 180.0) {
            lon -= 360.0;
        }
        if (lon < -180.0) {
            lon += 360.0;
        }
        return lon;
    }

    /**
     * Converts a distance in meters to degrees latitude.
     */
    private static double metersToDegreesLatitude(double meters) {
        return meters / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    /**
     * Converts a distance in meters to degrees longitude.
     * This conversion takes into account the current latitude.
     */
    private static double metersToDegreesLongitude(double meters, double lat) {
        return meters / (APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR * Math.cos(Math.toRadians(lat)));
    }
}
