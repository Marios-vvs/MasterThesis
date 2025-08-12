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
    // Distance (in kilometers) to offset the location.
    private volatile int mDistanceKm;

    // Clock for timing updates.
    private final Clock mClock;
    // Random generator for direction and variation.
    private final Random mRandom;

    // Interval for updating direction and distance (1 hour).
    @VisibleForTesting
    static final long UPDATE_INTERVAL_MS =  60 * 1000; //1 min for test

    // Maximum latitude to avoid cos(lat)=0 at poles.
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final double MAX_LATITUDE = 90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    // Jitter parameters
    private static final double DIRECTION_JITTER_DEGREES = 3.0; // max ±3° jitter per update
    private static final double VARIATION_PERCENT = 0.05;        // ±5% distance variation

    // Current offset direction (degrees) and distance (meters).
    @GuardedBy("this")
    private double mDirectionDeg;
    @GuardedBy("this")
    private double mDistanceM;

    // Next time (in elapsed realtime) to update direction/distance.
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    // Caches for fine/coarse locations and location results.
    /*
    @GuardedBy("this") @Nullable private Location mCachedFineLocation;
    @GuardedBy("this") @Nullable private Location mCachedCoarseLocation;
    @GuardedBy("this") @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this") @Nullable private LocationResult mCachedCoarseLocationResult;
    */
    
    /**
     * Constructs a DirectionalDistanceFudger with the given base distance.
     *
     * @param distanceKm distance in kilometers to offset the location.
     */
    public DistanceFudger(int distanceKm) {
        this(distanceKm, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    DistanceFudger(int distanceKm, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        if(distanceKm > 0){
        mDistanceKm = distanceKm;
        }
        else{
            mDistanceKm = 10;
        }
        resetDirectionDistance();
    }

    /**
     * Resets the direction and distance variation to initial random values.
     */
    private synchronized void resetDirectionDistance() {
        // Pick a random initial direction [0, 360).
        mDirectionDeg = mRandom.nextDouble() * 360.0;
        // Reset distance to base (no variation).
        mDistanceM = mDistanceKm * 1000.0;
        // Schedule next update in one hour.
        mNextUpdateRealtimeMs = mClock.millis() + UPDATE_INTERVAL_MS;
        //log reset
        Log.i(TAG, String.format("resetDirectionDistance: directionDeg=%.3f°, distanceM=%.1f, nextUpdateInMs=%d",
                mDirectionDeg, mDistanceM, UPDATE_INTERVAL_MS));
    }

    /**
     * Sets a new base distance (in km) and resets the obfuscation parameters.
     */
    public void setDistanceKm(int distanceKm) {
        synchronized (this) {
            if(distanceKm > 0){
            mDistanceKm = distanceKm;
            }
            else{
            mDistanceKm = 10;
            }
            resetDirectionDistance();
            // Clear caches as the obfuscation parameters changed.
            /*
            mCachedFineLocation = null;
            mCachedCoarseLocation = null;
            mCachedFineLocationResult = null;
            mCachedCoarseLocationResult = null;
            */
        }
    }

    /** Returns the current base distance in kilometers. */
    public int getDistanceKm() {
        return mDistanceKm;
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
        // Apply coarse to each location in the result.
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
        /*
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }
        */
        updateDirectionDistance();

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
            // Bearing convention: 0° means north (increase latitude). Decompose into dy/dx.
            dyMeters = usedDistanceM * Math.cos(angleRad); 
            dxMeters = usedDistanceM * Math.sin(angleRad); 
        }

        double newLatDeg = baseLat + metersToDegreesLatitude(dyMeters);
        newLatDeg = wrapLatitude(newLatDeg);
        
        double newLonDeg = baseLon + metersToDegreesLongitude(dxMeters, newLatDeg);
        newLonDeg = wrapLongitude(newLonDeg);

        coarse.setLatitude(newLatDeg);
        coarse.setLongitude(newLonDeg);

        float originalAccuracy = coarse.getAccuracy();
        float offsetAccuracy   = (float) usedDistanceM;
        coarse.setAccuracy(Math.max(offsetAccuracy, originalAccuracy));
        Log.d(TAG, String.format("createCoarse: input=(%.6f,%.6f) -> obfuscated=(%.6f,%.6f);"
                        + " bearing=%.3f°, dy=%.1fm dx=%.1fm distance=%.1fm",
                baseLat, baseLon, newLatDeg, newLonDeg, usedDirectionDeg, dyMeters, dxMeters, usedDistanceM));

        /*
        synchronized (this) {
            mCachedFineLocation   = fine;
            mCachedCoarseLocation = coarse;
        }
        */
        return coarse;
    }


    /**
     * Updates the direction and distance variation if the update interval has elapsed.
     */
   private synchronized void updateDirectionDistance() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }
        double oldDirection = mDirectionDeg;
        double oldDistanceM = mDistanceM;

        // Vary distance ±VARIATION_PERCENT around base distance.
        double baseDistanceM = mDistanceKm * 1000.0;
        double factor = 1.0 + (mRandom.nextDouble() * 2.0 - 1.0) * VARIATION_PERCENT;
        mDistanceM = baseDistanceM * factor;

        // Jitter direction by a random amount up to ±DIRECTION_JITTER_DEGREES.
        double jitter = (mRandom.nextDouble() * 2.0 - 1.0) * DIRECTION_JITTER_DEGREES;
        mDirectionDeg = (mDirectionDeg + jitter) % 360.0;
        if (mDirectionDeg < 0) {
            mDirectionDeg += 360.0;
        }

        // clear caches when interval elapsed.
       /* 
       mCachedFineLocation = null;
        mCachedCoarseLocation = null;
        mCachedFineLocationResult = null;
        mCachedCoarseLocationResult = null;
        */
        // Schedule next update.
        mNextUpdateRealtimeMs = now + UPDATE_INTERVAL_MS;

        Log.i(TAG, String.format("updateDirectionDistance: oldDir=%.3f° oldDist=%.1fm -> newDir=%.3f° newDist=%.1fm jitter=%.3f° factor=%.4f nextInMs=%d",
                oldDirection, oldDistanceM, mDirectionDeg, mDistanceM, jitter, factor, UPDATE_INTERVAL_MS));
    }

    /**
     * Wraps a latitude value to the valid range [-MAX_LATITUDE, MAX_LATITUDE].
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
     * Wraps a longitude value to the range [-180, 180).
     */
    private static double wrapLongitude(double lon) {
        lon %= 360.0; // wraps into range (-360.0, +360.0)
        if (lon >= 180.0) {
            lon -= 360.0;
        }
        if (lon < -180.0) {
            lon += 360.0;
        }
        return lon;
    }

    /**
     * Converts a distance in meters to degrees of latitude.
     */
    private static double metersToDegreesLatitude(double distance) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    /**
     * Converts a distance in meters to degrees of longitude at the given latitude.
     */
    private static double metersToDegreesLongitude(double distance, double lat) {
        // cosine of latitude to account for convergence of meridians.
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / Math.cos(Math.toRadians(lat));
    }
}
