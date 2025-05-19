package com.android.settings.location;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.annotation.VisibleForTesting;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.core.BasePreferenceController;
import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.core.lifecycle.Lifecycle;

/**
 * Controller for the "Fake Location Distance" ListPreference.
 * Shows three options (10 km, 100 km, 500 km) when Fake Location is enabled,
 * and persists the chosen value to Settings.Global.FAKE_LOCATION_DISTANCE.
 */
public class FakeLocationDistancePreferenceController 
        extends BasePreferenceController implements PreferenceControllerMixin,
        Preference.OnPreferenceChangeListener {

    private static final String KEY = "fake_location_distance";
    private ListPreference mListPreference;
    private final ContentResolver mResolver;

    public FakeLocationDistancePreferenceController(Context context, Lifecycle lifecycle) {
        super(context, KEY);
        mResolver = context.getContentResolver();
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public int getAvailabilityStatus() {
        // Only available when Fake Location toggle is ON
        int enabled = Settings.Global.getInt(
                mResolver,
                Settings.Global.FAKE_LOCATION_ENABLED,
                0);
        return (enabled == 1) ? AVAILABLE : CONDITIONALLY_UNAVAILABLE;
    }

    @Override
    public String getPreferenceKey() {
        return KEY;
    }

    @Override
    public void displayPreference(androidx.preference.PreferenceScreen screen) {
        super.displayPreference(screen);
        mListPreference = (ListPreference) screen.findPreference(KEY);
        if (mListPreference != null) {
            mListPreference.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        if (mListPreference == null) return;

        // Read current distance value, default to 10
        int current = Settings.Global.getInt(
                mResolver,
                Settings.Global.FAKE_LOCATION_DISTANCE,
                10);

        String valueStr = String.valueOf(current);
        mListPreference.setValue(valueStr);

        int index = mListPreference.findIndexOfValue(valueStr);
        if (index >= 0) {
            mListPreference.setSummary(
                mListPreference.getEntries()[index]
            );
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String strValue = (String) newValue;
        int newDistance = Integer.parseInt(strValue);

        // Persist the chosen distance
        Settings.Global.putInt(
                mResolver,
                Settings.Global.FAKE_LOCATION_DISTANCE,
                newDistance
        );

        // Update summary to reflect the new selection
        int index = mListPreference.findIndexOfValue(strValue);
        if (index >= 0) {
            mListPreference.setSummary(
                mListPreference.getEntries()[index]
            );
        }
        return true;
    }
}