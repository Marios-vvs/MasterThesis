package com.android.settings.location;

import android.content.Context;
import android.provider.Settings;
import com.android.settings.core.TogglePreferenceController;

/**
 * Controller for the "Fake Location" switch in Settings > Location.
 * Reads/writes the global setting FAKE_LOCATION_ENABLED.
 */
public class FakeLocationPreferenceController extends LocationBasePreferenceController {

    private static final String KEY = "fake_location_enabled";

    public FakeLocationPreferenceController(Context context) {
        super(context, KEY);
    }

    @Override
    public int getAvailabilityStatus() {
        // Always available in the Location settings screen
        return AVAILABLE;
    }

    @Override
    public boolean isChecked() {
        // Returns true if the global setting is 1, false otherwise
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.FAKE_LOCATION_ENABLED, 0) == 1;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        // Write 1 (true) or 0 (false) to Settings.Global
        return Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.FAKE_LOCATION_ENABLED,
                isChecked ? 1 : 0);
    }

    @Override
    public void onLocationModeChanged(int mode, boolean restricted) {}
}