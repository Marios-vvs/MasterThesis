package com.android.settings.location;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

public class FakeLocationPreferenceController extends LocationBasePreferenceController {

    private static final String KEY_FAKE_LOCATION = "fake_location_enabled";

    private SwitchPreferenceCompat mPreference;

    public FakeLocationPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_FAKE_LOCATION;
    }

    @AvailabilityStatus
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(KEY_FAKE_LOCATION);
    }

    @Override
    public void updateState(Preference preference) {
        if (mPreference != null) {
            boolean isEnabled = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.FAKE_LOCATION_ENABLED,
                    0) == 1;
            mPreference.setChecked(isEnabled);
        }
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (KEY_FAKE_LOCATION.equals(preference.getKey())) {
            final ContentResolver cr = mContext.getContentResolver();
            boolean checked = mPreference.isChecked();
            Settings.Global.putInt(cr, Settings.Global.FAKE_LOCATION_ENABLED, checked ? 1 : 0);
            return true;
        }
        return false;
    }

    @Override
    public void onLocationModeChanged(int mode, boolean restricted) {
        // Optional: dynamically update UI if needed
    }
}
