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
 * Calibrated for ~200m average distortion (ε ≈ 0.01).
 * Mirrors API and style of LocationFudger (random offset + grid), but replaces with DP noise.
 */
public class GeoDPFudger {
    // Minimum coarse "accuracy" threshold (like LocationFudger)
    private static final float MIN_ACCURACY_M = 200.0f;

    // Update interval (hourly) – present for symmetry, but DP uses independent noise
    @VisibleForTesting
    static final long NOISE_UPDATE_INTERVAL_MS = 60 * 60 * 1000; // 1 hour

    // Earth approximation: 111km per degree at equator, avoid cos(90°)=0
    private static final int APPROX_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROX_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final double mEpsilon;  // DP privacy parameter
    private final Clock mClock;
    private final Random mRandom;
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

    /** 
     * @param accuracyM Target average distortion (meters). Effective ε ≈ 2/accuracyM.
     */
    public GeoDPFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    /** 
     * @VisibleForTesting 
     */
    GeoDPFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        // Enforce minimum "accuracy" (hence maximum ε = 2/MIN_ACCURACY)
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);
        // Calibrate ε so that expected noise distance ≈ 2/ε ≈ mAccuracyM
        mEpsilon = 2.0 / mAccuracyM;
        // Initialize (no-op) noise update time
        mNextUpdateRealtimeMs = mClock.millis() + NOISE_UPDATE_INTERVAL_MS;
    }

    /** Resets the noise timer (for testing or if needed). */
    public void resetNoise() {
        synchronized (this) {
            mNextUpdateRealtimeMs = mClock.millis() + NOISE_UPDATE_INTERVAL_MS;
        }
    }

    /**
     * Obfuscate a batch of locations by applying createCoarse to each.
     */
    public LocationResult createCoarse(LocationResult fineLocationResult) {
        synchronized (this) {
            if (fineLocationResult == mCachedFineLocationResult
                    || fineLocationResult == mCachedCoarseLocationResult) {
                return mCachedCoarseLocationResult;
            }
        }
        LocationResult coarseLocationResult =
                fineLocationResult.map(this::createCoarse);
        synchronized (this) {
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
        }
        return coarseLocationResult;
    }

    /**
     * Obfuscate a single location by adding planar Laplace noise to latitude/longitude.
     */
    public Location createCoarse(Location fine) {
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                // Same object, return cached result for efficiency
                return mCachedCoarseLocation;
            }
        }
        // (Mimic update pattern; no persistent offset is used in DP)
        updateNoise();

        // Copy the location and remove fine-grained metadata
        Location coarse = new Location(fine);
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        // Normalize base coordinates
        double latitude = wrapLatitude(coarse.getLatitude());
        double longitude = wrapLongitude(coarse.getLongitude());

        // Sample planar Laplace noise:
        //   radius ~ Gamma(shape=2, scale=1/ε) = sum of two Exp(ε), angle uniform [0,2π)
        double u = mRandom.nextDouble();
        double v = mRandom.nextDouble();
        // Two independent Exp(ε): -ln(u)/ε and -ln(v)/ε. Sum = -ln(u*v)/ε.
        double radius = -Math.log(u * v) / mEpsilon;
        double theta = 2 * Math.PI * mRandom.nextDouble();
        // Convert polar to Cartesian offsets (meters)
        double dy = radius * Math.sin(theta);
        double dx = radius * Math.cos(theta);

        // Convert meter offsets to latitude/longitude differences
        double dLat = metersToDegreesLatitude(dy);
        double dLon = metersToDegreesLongitude(dx, latitude);

        latitude += dLat;
        longitude += dLon;
        // Wrap coordinates to valid range
        latitude = wrapLatitude(latitude);
        longitude = wrapLongitude(longitude);

        coarse.setLatitude(latitude);
        coarse.setLongitude(longitude);
        // Reported accuracy: at least the target coarse accuracy
        coarse.setAccuracy(Math.max(mAccuracyM, coarse.getAccuracy()));

        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }
        return coarse;
    }

    /** 
     * Periodic update (hourly) – no internal state change for DP noise 
     * (present only to mirror LocationFudger structure).
     */
    private synchronized void updateNoise() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }
        mNextUpdateRealtimeMs = now + NOISE_UPDATE_INTERVAL_MS;
    }

    /** Clamp latitude to avoid cos(±90°)=0 issues. */
    private static double wrapLatitude(double lat) {
        if (lat > MAX_LATITUDE) {
            lat = MAX_LATITUDE;
        }
        if (lat < -MAX_LATITUDE) {
            lat = -MAX_LATITUDE;
        }
        return lat;
    }

    /** Wrap longitude into [-180, +180) range. */
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

    /** Convert northward distance (meters) to degrees latitude. */
    private static double metersToDegreesLatitude(double distance) {
        return distance / APPROX_METERS_PER_DEGREE_AT_EQUATOR;
    }

    /** Convert eastward distance (meters) to degrees longitude at given latitude. */
    private static double metersToDegreesLongitude(double distance, double lat) {
        return distance / APPROX_METERS_PER_DEGREE_AT_EQUATOR / Math.cos(Math.toRadians(lat));
    }
}
