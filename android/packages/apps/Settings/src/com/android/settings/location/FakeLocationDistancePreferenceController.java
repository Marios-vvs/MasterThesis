package com.android.settings.location;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

public class FakeLocationDistancePreferenceController extends LocationBasePreferenceController
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY = "fake_location_distance";
    private ListPreference mListPreference;
    private final ContentResolver mResolver;

    public FakeLocationDistancePreferenceController(Context context, String key) {
        super(context, key);
        mResolver = context.getContentResolver();
    }

    @Override
    public int getAvailabilityStatus() {
        int enabled = Settings.Global.getInt(mResolver, Settings.Global.FAKE_LOCATION_ENABLED, 0);
        return (enabled == 1) ? AVAILABLE : CONDITIONALLY_UNAVAILABLE;
    }

    @Override
    public String getPreferenceKey() {
        return KEY;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mListPreference = screen.findPreference(KEY);
        if (mListPreference != null) {
            mListPreference.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        if (mListPreference == null) return;

        int current = Settings.Global.getInt(
                mResolver,
                Settings.Global.FAKE_LOCATION_DISTANCE,
                10); // default value

        String valueStr = String.valueOf(current);
        mListPreference.setValue(valueStr);

        int index = mListPreference.findIndexOfValue(valueStr);
        if (index >= 0) {
            mListPreference.setSummary(mListPreference.getEntries()[index]);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String strValue = (String) newValue;
        int newDistance = Integer.parseInt(strValue);

        Settings.Global.putInt(mResolver, Settings.Global.FAKE_LOCATION_DISTANCE, newDistance);

        int index = mListPreference.findIndexOfValue(strValue);
        if (index >= 0) {
            mListPreference.setSummary(mListPreference.getEntries()[index]);
        }
        return true;
    }

    @Override
    public void onLocationModeChanged(int mode, boolean restricted) {
        // Optional: dynamically show/hide based on location state
    }
}
