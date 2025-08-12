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
 * Implements geo-indistinguishability (planar Laplace) based location obfuscation.
 * Uses global noise offsets refreshed once per interval, mirroring LocationFudger control flow.
 *
 * Added logging: lifecycle (reset/update), and per-location before/after coarsening with timestamps.
 */
public class GeoDPFudger implements LocationObfuscationInterface {
    private static final String TAG = "GeoDPFudger";

    private static final float MIN_ACCURACY_M = 500.0f;
    @VisibleForTesting
    static final long NOISE_UPDATE_INTERVAL_MS = 10 * 1000; // 10s for evaluation
    private static final int APPROX_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROX_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final double mEpsilon;
    private final Clock mClock;
    private final Random mRandom;

    // Global offsets applied to all calls within the same interval (meters).
    @GuardedBy("this")
    private double mLatitudeOffsetM;
    @GuardedBy("this")
    private double mLongitudeOffsetM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    /*
    // Caches for single Location
    @GuardedBy("this") @Nullable private Location mCachedFineLocation;
    @GuardedBy("this") @Nullable private Location mCachedCoarseLocation;
    // Caches for LocationResult
    @GuardedBy("this") @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this") @Nullable private LocationResult mCachedCoarseLocationResult;
    */

    /** Public constructor initializes clock, random, accuracy, and seeds offsets. */
    public GeoDPFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    /** @VisibleForTesting: inject Clock and Random for deterministic tests. */
    GeoDPFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        //mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
        mAccuracyM = MIN_ACCURACY_M;
        mEpsilon = 2.0 / mAccuracyM;  // calibrate privacy budget

        Log.i(TAG, String.format("construct: mAccuracyM=%.1fm mEpsilon=%.6f time(monotonic)=%d wall=%d",
                mAccuracyM, mEpsilon, mClock.millis(), System.currentTimeMillis()));

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
        /*
        mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
        */

        Log.i(TAG, String.format(
                "resetOffsets: latOffsetM=%.3f lonOffsetM=%.3f nextUpdateAt(monotonic)=%d (in %dms) wall=%d",
                mLatitudeOffsetM, mLongitudeOffsetM, mNextUpdateRealtimeMs,
                NOISE_UPDATE_INTERVAL_MS, System.currentTimeMillis()));
    }

    @Override
    public LocationResult createCoarse(LocationResult fineLocationResult) {
        /*
        synchronized (this) {
            if (fineLocationResult == mCachedFineLocationResult
                    || fineLocationResult == mCachedCoarseLocationResult) {
                return mCachedCoarseLocationResult;
            }
        }
        */
        LocationResult coarseLocationResult = fineLocationResult.map(this::createCoarse);
        /*
        synchronized (this) {
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
        }
        */
        return coarseLocationResult;
    }

    @Override
    public Location createCoarse(Location fine) {
        updateNoise(); // refresh offsets if interval elapsed
        /*
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }
        */

        // Capture timestamps for logs
        long timeMonotonicBefore = mClock.millis();
        long timeWallBefore = System.currentTimeMillis();

        // Log the input (before coarsening). include hasAccuracy info
        boolean hadAccuracy = fine.hasAccuracy();
        float inputAccuracy = hadAccuracy ? fine.getAccuracy() : 0.0f;
        Log.d(TAG, String.format(
                "createCoarse: BEFORE time(monotonic)=%d wall=%d lat=%.7f lon=%.7f hasAccuracy=%b accuracy=%.1fm",
                timeMonotonicBefore, timeWallBefore, fine.getLatitude(), fine.getLongitude(),
                hadAccuracy, inputAccuracy));

        // Clone and strip metadata
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);
        // Base coordinates clamp
        double lat = wrapLatitude(coarse.getLatitude());
        double lon = wrapLongitude(coarse.getLongitude());

        // Apply global offsets (read offsets synchronously)
        double usedLatOffsetM;
        double usedLonOffsetM;
        synchronized (this) {
            usedLatOffsetM = mLatitudeOffsetM;
            // apply latitude offset first so longitude conversion uses offset latitude like original code
            lat += metersToDegreesLatitude(usedLatOffsetM);
            usedLonOffsetM = mLongitudeOffsetM;
            lon += metersToDegreesLongitude(usedLonOffsetM, lat);
        }

        // Wrap to valid range
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);

        // Set new values & accuracy
        coarse.setLatitude(lat);
        coarse.setLongitude(lon);
        coarse.setAccuracy(mAccuracyM);

        // timestamps after computation
        long timeMonotonicAfter = mClock.millis();
        long timeWallAfter = System.currentTimeMillis();

        // Log the output (after coarsening) and the offsets used (meters)
        Log.d(TAG, String.format(
                "createCoarse: AFTER time(monotonic)=%d wall=%d lat=%.7f lon=%.7f reportedAccuracy=%.1fm "
                        + "usedLatOffsetM=%.3f usedLonOffsetM=%.3f elapsedMs=%d",
                timeMonotonicAfter, timeWallAfter, coarse.getLatitude(), coarse.getLongitude(),
                mAccuracyM, usedLatOffsetM, usedLonOffsetM, (timeMonotonicAfter - timeMonotonicBefore)));

        // Cache
        /*
        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }
        */
        return coarse;
    }

    /** Refresh offsets once per interval, clearing caches on rollover. */
    private synchronized void updateNoise() {
        long now = mClock.millis();
        long wallNow = System.currentTimeMillis();
        if (now < mNextUpdateRealtimeMs) {
            // Debug log to show we're skipping update (helps when tuning interval)
            Log.d(TAG, String.format("updateNoise: not time yet now=%d nextUpdate=%d wall=%d",
                    now, mNextUpdateRealtimeMs, wallNow));
            return; // not yet time
        }
        // Reseed global offsets
        double[] offsets = samplePlanarLaplaceOffsets();
        double oldLat = mLatitudeOffsetM;
        double oldLon = mLongitudeOffsetM;
        mLatitudeOffsetM = offsets[0];
        mLongitudeOffsetM = offsets[1];
        // Clear caches so new offsets take effect
        /*
        mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
        */
        // Schedule next refresh
        mNextUpdateRealtimeMs = now + NOISE_UPDATE_INTERVAL_MS;

        Log.i(TAG, String.format(
                "updateNoise: refreshed offsets oldLat=%.3f oldLon=%.3f -> newLat=%.3f newLon=%.3f "
                        + "time(monotonic)=%d wall=%d nextUpdateAt=%d (in %dms)",
                oldLat, oldLon, mLatitudeOffsetM, mLongitudeOffsetM, now, wallNow,
                mNextUpdateRealtimeMs, NOISE_UPDATE_INTERVAL_MS));
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

        // Log the sampled noise parameters (radius in meters and angle)
        Log.d(TAG, String.format("samplePlanarLaplaceOffsets: radius=%.3f m theta=%.6f rad -> dy=%.3f dx=%.3f",
                radius, theta, dy, dx));

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
