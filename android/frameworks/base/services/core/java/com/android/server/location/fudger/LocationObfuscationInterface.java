package com.android.server.location.fudger;

import android.location.Location;
import android.location.LocationResult;

/**
 * Interface defining a pluggable location obfuscation strategy.
 * Implementations (e.g., LocationFudger, GeoDPFudger) provide concrete
 * algorithms (grid snapping, geo‑indistinguishability, etc.) while sharing
 * a common API for the location service.
 */
public interface LocationObfuscationInterface {
    /**
     * Obfuscate a single fine-grained Location.
     *
     * @param fine the original Location with full precision
     * @return a new Location instance with reduced precision/privacy protection
     */
    Location createCoarse(Location fine);

    /**
     * Obfuscate a batch of fine-grained locations.
     *
     * @param fineResult the original LocationResult containing multiple Location fixes
     * @return a LocationResult whose contained Location objects have been obfuscated
     */
    LocationResult createCoarse(LocationResult fineResult);
}