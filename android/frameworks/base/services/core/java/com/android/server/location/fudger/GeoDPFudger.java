package com.android.server.location.fudger;

import android.location.Location;
import android.location.LocationResult;
import android.os.SystemClock;

import java.time.Clock;
import java.util.Random;

import com.android.internal.annotations.GuardedBy;

public class GeoDPFudger implements LocationObfuscator {

    private static final long OFFSET_UPDATE_INTERVAL_MS = 60 * 60 * 1000;
    private static final double DEFAULT_EPSILON = 0.05;  // Tune for privacy/accuracy
    private static final int APPROX_METERS_PER_DEGREE = 111_000;

    private final Clock mClock;
    private final Random mRandom;
    private final double mEpsilon;

    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;
    @GuardedBy("this")
    private double mDxMeters;
    @GuardedBy("this")
    private double mDyMeters;
    @GuardedBy("this")
    private Location mCachedFine;
    @GuardedBy("this")
    private Location mCachedObfuscated;

    @GuardedBy("this")
    private LocationResult mCachedFineResult;
    @GuardedBy("this")
    private LocationResult mCachedObfuscatedResult;

    public GeoDPFudger() {
        this(DEFAULT_EPSILON, SystemClock.elapsedRealtimeClock(), new Random());
    }

    public GeoDPFudger(double epsilon, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mEpsilon = epsilon;
        updateOffset();
    }

    @Override
    public synchronized Location Obfuscate(Location fine) {
        if (fine == mCachedFine) {
            return mCachedObfuscated;
        }

        updateOffset();

        double latOffset = metersToDegreesLatitude(mDyMeters);
        double lonOffset = metersToDegreesLongitude(mDxMeters, fine.getLatitude());

        Location obfuscatedLocation = new Location(fine);
        obfuscatedLocation.setLatitude(wrapLatitude(fine.getLatitude() + latOffset));
        obfuscatedLocation.setLongitude(wrapLongitude(fine.getLongitude() + lonOffset));

        obfuscatedObfuscated.removeBearing();
        obfuscatedObfuscation.removeSpeed();
        obfuscatedObfuscation.removeAltitude();
        obfuscatedObfuscation.setExtras(null);

        obfuscatedLocation.setAccuracy(Math.max(fine.getAccuracy(), 200.0f));

        mCachedFine = fine;
        mCachedObfuscated = obfuscatedLocation;
        return obfuscatedLocation;
    }

    @Override
    public synchronized LocationResult createCoarse(LocationResult fineResult) {
        if (fineResult == mCachedFineResult) {
            return mCachedObfuscatedResult;
        }

        LocationResult result = fineResult.map(this::createCoarse);
        mCachedFineResult = fineResult;
        mCachedObfuscatedResult = result;
        return result;
    }

    private synchronized void updateOffset() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) return;

        double r = sampleRadius(mEpsilon);
        double theta = 2 * Math.PI * mRandom.nextDouble();

        mDxMeters = r * Math.cos(theta);
        mDyMeters = r * Math.sin(theta);

        mNextUpdateRealtimeMs = now + OFFSET_UPDATE_INTERVAL_MS;
    }

    private double sampleRadius(double epsilon) {
        double u = mRandom.nextDouble();
        return -Math.log(1 - u) / epsilon; // Inverse CDF of exponential with lambda=epsilon
    }

    private static double metersToDegreesLatitude(double meters) {
        return meters / APPROX_METERS_PER_DEGREE;
    }

    private static double metersToDegreesLongitude(double meters, double latitude) {
        return meters / (APPROX_METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude)));
    }

    private static double wrapLatitude(double lat) {
        return Math.max(-89.999, Math.min(89.999, lat));
    }

    private static double wrapLongitude(double lon) {
        lon = lon % 360.0;
        if (lon > 180.0) lon -= 360.0;
        if (lon < -180.0) lon += 360.0;
        return lon;
    }
}
