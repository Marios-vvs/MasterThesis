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

    private static final float MIN_ACCURACY_M = 200.0f;
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
        synchroni
