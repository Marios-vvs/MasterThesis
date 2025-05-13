package com.android.server.location.fudger;

import android.location.Location;
import android.os.Build;
import androidx.annotation.RequiresApi;
import java.util.Random;

/**
 * KalmanObfuscator implements a 4D constant-velocity Kalman filter
 * for location smoothing, with optional planar Laplace noise for geo-indistinguishability.
 *
 * State vector: [lat, lon, vLat, vLon] (degrees and degrees/sec).
 */
public class KalmanObfuscator {
    // State and covariance
    private double[] X;    // [lat, lon, vLat, vLon]
    private double[][] P;  // 4x4 covariance matrix
    // Process and measurement noise parameters
    private double sigmaAccel;   // assumed stddev of acceleration (deg/s^2 approx)
    private double sigmaInitPos; // initial position uncertainty (deg)
    private double sigmaInitVel; // initial velocity uncertainty (deg/s)
    // Privacy (geo-indist) parameter
    private double epsilon;      // planar Laplace parameter (1/meter)
    // Random number generator for noise
    private Random rand;
    // Timestamp of last update
    private long lastTimestamp = -1;

    /**
     * Constructor.
     * @param epsilon Privacy parameter (smaller=more noise, units 1/m).
     * @param sigmaAccel Std.dev. of assumed acceleration (m/s^2).  Approximate scaling: 1 deg ≈111320 m.
     */
    public KalmanObfuscator(double epsilon, double sigmaAccel) {
        this.epsilon = epsilon;
        this.sigmaAccel = sigmaAccel / 111320.0; // convert to degrees/s^2
        // Initialize state/covariance
        X = new double[4];
        P = new double[4][4];
        // Default initial uncertainties
        sigmaInitPos = 1.0;   // degrees (~111km) large by default
        sigmaInitVel = 0.01;  // deg/s (~1 km/s) as rough guess
        rand = new Random();
        reset();
    }

    /** Resets the filter. */
    public void reset() {
        X[0] = X[1] = X[2] = X[3] = 0;
        lastTimestamp = -1;
        // Initialize covariance
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                P[i][j] = 0;
        // Large uncertainty in position, moderate in velocity
        P[0][0] = sigmaInitPos*sigmaInitPos;
        P[1][1] = sigmaInitPos*sigmaInitPos;
        P[2][2] = sigmaInitVel*sigmaInitVel;
        P[3][3] = sigmaInitVel*sigmaInitVel;
    }

    /**
     * Performs one Kalman filter update with the given actual location.
     * Returns a new Location containing the obfuscated (smoothed + noise) coordinates.
     */
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    public Location update(Location actual) {
        // Initialize state on first call
        if (lastTimestamp < 0) {
            X[0] = actual.getLatitude();
            X[1] = actual.getLongitude();
            X[2] = 0;
            X[3] = 0;
            lastTimestamp = actual.getElapsedRealtimeNanos();
            // Return original with noise
            return outputLocation(actual);
        }
        // Compute time delta in seconds
        long now = actual.getElapsedRealtimeNanos();
        double dt = (now - lastTimestamp) * 1e-9;
        if (dt <= 0) dt = 0.0;
        lastTimestamp = now;

        // ----- Predict step -----
        // State transition F
        double[][] F = {
            {1, 0, dt, 0},
            {0, 1, 0, dt},
            {0, 0, 1, 0},
            {0, 0, 0, 1}
        };
        // Process noise covariance Q (constant velocity model)
        double dt2 = dt*dt, dt3 = dt2*dt, dt4 = dt2*dt2;
        double q = sigmaAccel*sigmaAccel;
        double[][] Q = new double[4][4];
        // Position-position
        Q[0][0] = q*dt4/4.0;
        Q[0][2] = q*dt3/2.0;
        Q[1][1] = q*dt4/4.0;
        Q[1][3] = q*dt3/2.0;
        // Velocity-velocity
        Q[2][0] = q*dt3/2.0;
        Q[2][2] = q*dt2;
        Q[3][1] = q*dt3/2.0;
        Q[3][3] = q*dt2;
        // (others are zero or symmetric)
        Q[2][0] = Q[0][2];
        Q[3][1] = Q[1][3];

        // Predict state X = F*X (we update in place)
        double newLat = X[0] + X[2]*dt;
        double newLon = X[1] + X[3]*dt;
        double newVlat = X[2];
        double newVlon = X[3];
        X[0] = newLat; X[1] = newLon; X[2] = newVlat; X[3] = newVlon;
        // Predict covariance P = F*P*F^T + Q
        P = multiply(F, multiply(P, transpose(F)));
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                P[i][j] += Q[i][j];

        // ----- Update step -----
        // Measurement: lat, lon (in degrees)
        double zLat = actual.getLatitude();
        double zLon = actual.getLongitude();
        // Measurement matrix H = [I | 0], so we only use P[0..1][0..1]
        // Compute innovation y = z - H*X
        double y0 = zLat - X[0];
        double y1 = zLon - X[1];
        // Measurement covariance R (from accuracy)
        double acc = actual.hasAccuracy() ? actual.getAccuracy() : 50.0f;
        // Convert m->deg at current lat
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(Math.toRadians(X[0]));
        if (metersPerDegLon < 1e-5) metersPerDegLon = 1e-5;
        double varLat = (acc/ metersPerDegLat) * (acc/ metersPerDegLat);
        double varLon = (acc/ metersPerDegLon) * (acc/ metersPerDegLon);
        // Innovation covariance S = HPHT + R
        double S00 = P[0][0] + varLat;
        double S11 = P[1][1] + varLon;
        // Kalman gain K = P * H^T * S^{-1}
        // Since H selects first two rows, K columns 0-1 are: 
        double k00 = P[0][0] / S00;
        double k10 = P[1][0] / S00;
        double k20 = P[2][0] / S00;
        double k30 = P[3][0] / S00;
        double k01 = P[0][1] / S11;
        double k11 = P[1][1] / S11;
        double k21 = P[2][1] / S11;
        double k31 = P[3][1] / S11;
        // Update state X = X + K*y
        X[0] += k00*y0 + k01*y1;
        X[1] += k10*y0 + k11*y1;
        X[2] += k20*y0 + k21*y1;
        X[3] += k30*y0 + k31*y1;
        // Update covariance P = (I-KH)P
        // (Equivalent to Joseph form; here we skip for brevity. We do a simpler update:)
        // P00 = P00 - K*HP
        // Compute P_new = P - K*H*P
        double[][] K = {{k00, k01},{k10,k11},{k20,k21},{k30,k31}};
        double[][] H = {{1,0,0,0},{0,1,0,0}};
        double[][] KH = multiply(K, H);
        double[][] I = identityMatrix(4);
        double[][] temp = multiply(subtract(I, KH), P);
        P = temp;

        // ----- Construct output Location -----
        return outputLocation(actual);
    }

    /** Returns a Location object with filtered (and noised) coordinates. */
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private Location outputLocation(Location original) {
        // Use filtered state X[0],X[1] as the (true) base
        double outLat = X[0];
        double outLon = X[1];
        // Add planar Laplace noise for geo-indistinguishability
        if (epsilon > 0) {
            // Sample r by summing two exponentials
            double u1 = rand.nextDouble();
            double u2 = rand.nextDouble();
            double r = -(1.0/epsilon) * (Math.log(u1) + Math.log(u2));
            double theta = 2*Math.PI * rand.nextDouble();
            double dx = r * Math.cos(theta);
            double dy = r * Math.sin(theta);
            // Convert from meters to degrees
            double dLat = dy / 111320.0;
            double dLon = dx / (111320.0 * Math.cos(Math.toRadians(outLat)));
            outLat += dLat;
            outLon += dLon;
        }
        // Create new Location based on original (to preserve extras like provider, time)
        Location locOut = new Location(original);
        locOut.setLatitude(outLat);
        locOut.setLongitude(outLon);
        // Optionally adjust accuracy: here we keep original accuracy
        // Alternatively: we could set locOut.setAccuracy(some function of P);
        return locOut;
    }

    // ---------- Matrix helper functions ----------

    /** Returns the 4x4 identity matrix. */
    private double[][] identityMatrix(int n) {
        double[][] I = new double[n][n];
        for(int i=0;i<n;i++) I[i][i] = 1.0;
        return I;
    }

    /** Matrix multiplication A*B. */
    private double[][] multiply(double[][] A, double[][] B) {
        int m=A.length, n=B[0].length, p=A[0].length;
        double[][] C = new double[m][n];
        for(int i=0;i<m;i++){
            for(int j=0;j<n;j++){
                double sum=0;
                for(int k=0;k<p;k++){
                    sum += A[i][k] * B[k][j];
                }
                C[i][j] = sum;
            }
        }
        return C;
    }

    /** Transpose of matrix A. */
    private double[][] transpose(double[][] A) {
        int m=A.length, n=A[0].length;
        double[][] T = new double[n][m];
        for(int i=0;i<m;i++)
            for(int j=0;j<n;j++)
                T[j][i] = A[i][j];
        return T;
    }

    /** C = A - B (same dimensions). */
    private double[][] subtract(double[][] A, double[][] B) {
        int m=A.length, n=A[0].length;
        double[][] C = new double[m][n];
        for(int i=0;i<m;i++){
            for(int j=0;j<n;j++){
                C[i][j] = A[i][j] - B[i][j];
            }
        }
        return C;
    }

    // ---------- Accessors for testing ----------
    /** Get current filter state [lat, lon, vLat, vLon]. */
    public double[] getState() {
        return X.clone();
    }
    /** Get current state covariance matrix (4x4). */
    public double[][] getCovariance() {
        double[][] C = new double[4][4];
        for(int i=0;i<4;i++) for(int j=0;j<4;j++) C[i][j] = P[i][j];
        return C;
    }
}
