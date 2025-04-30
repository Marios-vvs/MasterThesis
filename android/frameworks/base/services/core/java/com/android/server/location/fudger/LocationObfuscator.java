package com.android.server.location.fudger;

import android.location.Location;
import android.location.LocationResult;

public interface LocationObfuscator {
    Location obfuscate(Location fine);
    default LocationResult createCoarse(LocationResult fineResult) {
        return fineResult.map(this::obfuscate);
    }
}
