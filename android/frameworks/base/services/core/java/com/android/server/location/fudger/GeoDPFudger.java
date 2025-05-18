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
 * Calibrated for ~200 m average distortion (ε ≈ 2/mAccuracy).
 * resembles function names, synchronization, and caching patterns of LocationFudger,
 * but replaces offset+grid with a differential‑privacy noise mechanism.
 */
public class GeoDPFudger implements LocationObfuscationInterface{
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

    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    // Caches for single Location
    @GuardedBy("this")
    @Nullable private Location mCachedFineLocation;
    @GuardedBy("this")
    @Nullable private Location mCachedCoarseLocation;

    // Caches for LocationResult
    @GuardedBy("this")
    @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this")
    @Nullable private LocationResult mCachedCoarseLocationResult;

    /** Public constructor mirrors LocationFudger. */
    public GeoDPFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    /** @VisibleForTesting: inject Clock and Random for deterministic tests. */
    GeoDPFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
        mEpsilon = 2.0 / mAccuracyM;  // calibrate privacy budget
        // initialize next update time immediately
        mNextUpdateRealtimeMs = mClock.millis() + NOISE_UPDATE_INTERVAL_MS;
    }

    /** Reset the noise timer (for testing). */
    public void resetNoise() {
        synchronized (this) {
            mNextUpdateRealtimeMs = mClock.millis() + NOISE_UPDATE_INTERVAL_MS;
        }
    }

    /**
     * Obfuscate a batch of locations, mirroring LocationFudger's API.
     */
    @override
    public LocationResult createCoarse(LocationResult fineLocationResult) {
        synchronized (this) {
            if (fineLocationResult == mCachedFineLocationResult
                    || fineLocationResult == mCachedCoarseLocationResult) {
                return mCachedCoarseLocationResult;
            }
        }
        // Delegate to single-location method via map()
        LocationResult coarseLocationResult = fineLocationResult.map(this::createCoarse);
        synchronized (this) {
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
        }
        return coarseLocationResult;
    }

    /**
     * Obfuscate a single Location by adding planar Laplace noise.
     */
    @override
    public Location createCoarse(Location fine) {
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }

        // Update noise interval and clear caches if needed
        updateNoise();

        // Copy and sanitize
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        // Base coordinates
        double lat = wrapLatitude(coarse.getLatitude());
        double lon = wrapLongitude(coarse.getLongitude());

        // --- Planar Laplace noise sampling ---
        double u = mRandom.nextDouble();
        double v = mRandom.nextDouble();
        // Radius ~ Gamma(shape=2, scale=1/ε): sum of two Exp(ε)
        double radius = -Math.log(u * v) / mEpsilon;
        double theta = 2 * Math.PI * mRandom.nextDouble();
        double dy = radius * Math.sin(theta);
        double dx = radius * Math.cos(theta);

        // Convert meters to degrees
        double dLat = metersToDegreesLatitude(dy);
        double dLon = metersToDegreesLongitude(dx, lat);

        lat += dLat;
        lon += dLon;

        // Wrap after noise
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);

        coarse.setLatitude(lat);
        coarse.setLongitude(lon);
        coarse.setAccuracy(Math.max(mAccuracyM, coarse.getAccuracy()));

        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }
        return coarse;
    }

    /**
     * Update the noise interval once per hour.
     * FIX: also clear caches when new interval begins.
     */
    private synchronized void updateNoise() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }
        mNextUpdateRealtimeMs = now + NOISE_UPDATE_INTERVAL_MS;
        // FIX: invalidate caches to force recompute with fresh noise
        mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
    }

    // --- Helper methods (copied from LocationFudger) ---

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

    private static double metersToDegreesLatitude(double distance) {
        return distance / APPROX_METERS_PER_DEGREE_AT_EQUATOR;
    }

    private static double metersToDegreesLongitude(double distance, double latitudeDegrees) {
        return distance
                / (APPROX_METERS_PER_DEGREE_AT_EQUATOR * Math.cos(Math.toRadians(latitudeDegrees)));
    }
}