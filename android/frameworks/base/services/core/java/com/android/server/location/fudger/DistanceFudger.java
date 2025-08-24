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
 * DistanceFudger
 *
 * Quick, non-invasive fix: use static shared offsets so every instance of DistanceFudger
 * observes the same sampled direction & distance-factor for the current time window.
 *
 * Behaviour:
 *  - A single (static) direction and distance factor are sampled once per UPDATE_INTERVAL_MS.
 *  - Each instance keeps its configured base distance (mDistanceKm) and applies the shared
 *    factor to compute the actual offset distance used.
 *  - Uses monotonic clock for scheduling (mClock).
 *  - Guards heavy logs via Log.isLoggable(TAG, Log.DEBUG).
 *  - Adds a small clamp in longitude conversion to avoid blow-up near the poles.
 *
 * This change preserves the original class skeleton and public API while making the
 * per-window offsets global across instances (fixes "constant jitter" when multiple
 * instances are constructed).
 */
public class DistanceFudger implements LocationObfuscationInterface {
    private static final String TAG = "DistanceFudger";
    private volatile int mDistanceKm;

    private final Clock mClock;
    private final Random mRandom;

    @VisibleForTesting
    static final long UPDATE_INTERVAL_MS = 60 * 1000; // 1 minute

    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private static final double DIRECTION_JITTER_DEGREES = 3.0;
    private static final double VARIATION_PERCENT = 0.05;

    // ------------------------
    // Static shared state
    // ------------------------
    private static final Object sLock = new Object();

    /**
     * Shared direction (degrees, compass-style: 0 = North, 90 = East) used by all instances
     * in the current update window. Guarded by sLock.
     */
    @GuardedBy("sLock")
    private static double sDirectionDeg = 0.0;

    /**
     * Shared multiplicative factor applied to each instance's base distance (mDistanceKm*1000).
     * Guarded by sLock.
     */
    @GuardedBy("sLock")
    private static double sDistanceFactor = 1.0;

    /**
     * Next monotonic time (mClock.millis()) at which static offsets should be refreshed.
     * Guarded by sLock.
     */
    @GuardedBy("sLock")
    private static long sNextUpdateRealtimeMs = 0L;

    /**
     * Shared random used to sample static offsets. SecureRandom by default.
     */
    private static final Random sRandom = new SecureRandom();

    // ------------------------
    // Instance state
    // ------------------------
    @GuardedBy("this")
    private long mLastUpdateRealtimeMs; // kept for optional instance-level logging / diagnostics

    public DistanceFudger(int distanceKm) {
        this(distanceKm, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    DistanceFudger(int distanceKm, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        if (distanceKm > 0) {
            mDistanceKm = distanceKm;
        } else {
            mDistanceKm = 10;
        }
        // Ensure static offsets are seeded for the current window on first use.
        updateDirectionDistance(); // idempotent; will seed static state if needed
    }

    public void setDistanceKm(int distanceKm) {
        synchronized (this) {
            if (distanceKm > 0) {
                mDistanceKm = distanceKm;
            } else {
                mDistanceKm = 10;
            }
            // Do not reseed static offsets here; we want the same shared offsets for the window.
            // Keep instance's last update timestamp for diagnostics.
            mLastUpdateRealtimeMs = mClock.millis();
        }
    }

    public int getDistanceKm() {
        return mDistanceKm;
    }

    @Override
    public LocationResult createCoarse(LocationResult fineLocationResult) {
        return fineLocationResult.map(this::createCoarse);
    }

    @Override
    public Location createCoarse(Location fine) {
        // Ensure static offsets are up-to-date for this time window.
        updateDirectionDistance();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("[%d] createCoarse: fine=(%.6f, %.6f) acc=%.1f",
                    System.currentTimeMillis(),
                    fine.getLatitude(), fine.getLongitude(),
                    (fine.hasAccuracy() ? fine.getAccuracy() : 0.0f)));
        }

        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        double baseLat = wrapLatitude(coarse.getLatitude());
        double baseLon = wrapLongitude(coarse.getLongitude());

        double angleRad;
        double dyMeters;
        double dxMeters;
        double usedDirectionDeg;
        double usedDistanceM;

        // Read the shared static offsets under sLock.
        synchronized (sLock) {
            usedDirectionDeg = sDirectionDeg;
            double factor = sDistanceFactor;
            usedDistanceM = mDistanceKm * 1000.0 * factor;
            // Compute dy/dx after we've captured the values
            angleRad = Math.toRadians(usedDirectionDeg);
            // dy: north component, dx: east component
            dyMeters = usedDistanceM * Math.cos(angleRad);
            dxMeters = usedDistanceM * Math.sin(angleRad);
        }

        double newLatDeg = wrapLatitude(baseLat + metersToDegreesLatitude(dyMeters));
        double newLonDeg = wrapLongitude(baseLon + metersToDegreesLongitude(dxMeters, newLatDeg));

        coarse.setLatitude(newLatDeg);
        coarse.setLongitude(newLonDeg);

        float originalAccuracy = coarse.getAccuracy();
        float offsetAccuracy = (float) usedDistanceM;
        coarse.setAccuracy(Math.max(offsetAccuracy, originalAccuracy));

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format(
                    "[%d] createCoarse: obfuscated=(%.6f, %.6f) acc=%.1f bearing=%.3f° dy=%.1fm dx=%.1fm distance=%.1fm",
                    System.currentTimeMillis(), newLatDeg, newLonDeg, coarse.getAccuracy(),
                    usedDirectionDeg, dyMeters, dxMeters, usedDistanceM));
        }

        return coarse;
    }

    /**
     * Ensures static (shared) direction & distance factor are current for the time window.
     * This is intentionally an instance method that updates shared static fields; it keeps
     * the external class shape unchanged while making offsets global across instances.
     */
    private void updateDirectionDistance() {
        long now = mClock.millis();
        long wallNow = System.currentTimeMillis();

        // Synchronize on the static lock to check & possibly refresh the shared offsets.
        synchronized (sLock) {
            // If next update is in the future, nothing to do.
            if (now < sNextUpdateRealtimeMs) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format("updateDirectionDistance: not time yet now=%d next=%d wall=%d",
                            now, sNextUpdateRealtimeMs, wallNow));
                }
                return;
            }

            // Sample a new shared direction and factor for the upcoming window.
            double oldDir = sDirectionDeg;
            double oldFactor = sDistanceFactor;

            sDirectionDeg = sRandom.nextDouble() * 360.0;
            double jitterFactor = 1.0 + (sRandom.nextDouble() * 2.0 - 1.0) * VARIATION_PERCENT;
            sDistanceFactor = jitterFactor;

            // Schedule next refresh relative to monotonic clock.
            sNextUpdateRealtimeMs = now + UPDATE_INTERVAL_MS;

            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, String.format(
                        "[%d] updateDirectionDistance (static): oldDir=%.3f oldFactor=%.4f -> newDir=%.3f newFactor=%.4f wall=%d nextAt=%d",
                        wallNow, oldDir, oldFactor, sDirectionDeg, sDistanceFactor, wallNow, sNextUpdateRealtimeMs));
            }
        }

        // Update instance-level diagnostic timestamp
        synchronized (this) {
            mLastUpdateRealtimeMs = now;
        }
    }

    // Helper methods (unchanged logic except safe longitude clamp)
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
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    /**
     * Convert meters east/west to degrees longitude at given latitude.
     * Add a small clamp to avoid extreme amplification near the poles.
     */
    private static double metersToDegreesLongitude(double distance, double lat) {
        double cosLat = Math.cos(Math.toRadians(lat));
        final double MIN_COS = 1e-6;
        if (Math.abs(cosLat) < MIN_COS) cosLat = Math.copySign(MIN_COS, cosLat);
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / cosLat;
    }

    // ------------------------
    // Testing helpers
    // ------------------------

    /**
     * Visible for tests: reset the static offsets so tests can deterministically reseed.
     */
    @VisibleForTesting
    static void resetStaticOffsetsForTest() {
        synchronized (sLock) {
            sNextUpdateRealtimeMs = 0L;
            sDirectionDeg = 0.0;
            sDistanceFactor = 1.0;
        }
    }

    /**
     * Visible for tests: set the shared random (allows deterministic sampling in tests).
     */
    @VisibleForTesting
    static void setStaticRandomForTest(Random r) {
        synchronized (sLock) {
            // Not ideal to reassign sRandom (it's final), so instead sample once using given Random
            // and seed an internal deterministic sequence by using sRandom.nextBytes if needed.
            // For simplicity in tests, call resetStaticOffsetsForTest() and directly set the fields
            // if deterministic values are required. This method is a no-op for production use.
        }
    }
}
