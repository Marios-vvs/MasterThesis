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
 * Contains the logic to obfuscate (fudge) locations for coarse applications by
 * offsetting a fixed distance in a randomly jittering direction. The direction and
 * distance are periodically updated to prevent tracking of the obfuscation pattern.
 */
public class DistanceFudger implements LocationObfuscationInterface {
    private static final String TAG = "DistanceFudger";
    private volatile int mDistanceKm;

    private final Clock mClock;
    private final Random mRandom;

    @VisibleForTesting
    static final long UPDATE_INTERVAL_MS =  60 * 1000; // 1 min for test

    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE = 90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private static final double DIRECTION_JITTER_DEGREES = 3.0;
    private static final double VARIATION_PERCENT = 0.05;

    @GuardedBy("this")
    private double mDirectionDeg;
    @GuardedBy("this")
    private double mDistanceM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

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
        resetDirectionDistance();
    }

    private synchronized void resetDirectionDistance() {
        mDirectionDeg = mRandom.nextDouble() * 360.0;
        mDistanceM = mDistanceKm * 1000.0;
        mNextUpdateRealtimeMs = mClock.millis() + UPDATE_INTERVAL_MS;

        Log.i(TAG, String.format("[%d] resetDirectionDistance: directionDeg=%.3f°, distanceM=%.1f, nextUpdateInMs=%d",
                System.currentTimeMillis(), mDirectionDeg, mDistanceM, UPDATE_INTERVAL_MS));
    }

    public void setDistanceKm(int distanceKm) {
        synchronized (this) {
            if (distanceKm > 0) {
                mDistanceKm = distanceKm;
            } else {
                mDistanceKm = 10;
            }
            resetDirectionDistance();
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
        updateDirectionDistance();

        // Log the incoming fine location
        Log.d(TAG, String.format("[%d] createCoarse: fine=(%.6f, %.6f) acc=%.1f",
                System.currentTimeMillis(), fine.getLatitude(), fine.getLongitude(), fine.getAccuracy()));

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
        synchronized (this) {
            usedDirectionDeg = mDirectionDeg;
            usedDistanceM = mDistanceM;
            angleRad = Math.toRadians(usedDirectionDeg);
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

        // Log the obfuscated result
        Log.d(TAG, String.format("[%d] createCoarse: obfuscated=(%.6f, %.6f) acc=%.1f bearing=%.3f° dy=%.1fm dx=%.1fm distance=%.1fm",
                System.currentTimeMillis(), newLatDeg, newLonDeg, coarse.getAccuracy(),
                usedDirectionDeg, dyMeters, dxMeters, usedDistanceM));

        return coarse;
    }

    private synchronized void updateDirectionDistance() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }
        double oldDirection = mDirectionDeg;
        double oldDistanceM = mDistanceM;

        double baseDistanceM = mDistanceKm * 1000.0;
        double factor = 1.0 + (mRandom.nextDouble() * 2.0 - 1.0) * VARIATION_PERCENT;
        mDistanceM = baseDistanceM * factor;

        double jitter = (mRandom.nextDouble() * 2.0 - 1.0) * DIRECTION_JITTER_DEGREES;
        mDirectionDeg = (mDirectionDeg + jitter) % 360.0;
        if (mDirectionDeg < 0) {
            mDirectionDeg += 360.0;
        }

        mNextUpdateRealtimeMs = now + UPDATE_INTERVAL_MS;

        Log.i(TAG, String.format("[%d] updateDirectionDistance: oldDir=%.3f° oldDist=%.1fm -> newDir=%.3f° newDist=%.1fm jitter=%.3f° factor=%.4f nextInMs=%d",
                System.currentTimeMillis(), oldDirection, oldDistanceM, mDirectionDeg, mDistanceM, jitter, factor, UPDATE_INTERVAL_MS));
    }

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

    private static double metersToDegreesLongitude(double distance, double lat) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / Math.cos(Math.toRadians(lat));
    }
}
