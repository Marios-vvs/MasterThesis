package com.android.settings.location;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

public class FakeLocationDistancePreferenceController extends LocationBasePreferenceController
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "FakeLocationDistanceCtrl";

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
        Log.d(TAG, "updateState(): current stored value = " + current + " km");

        String valueStr = String.valueOf(current);
        int index = mListPreference.findIndexOfValue(valueStr);

        if (index >= 0) {
            // User picked a predefined option (10, 100, 500)
            mListPreference.setValue(valueStr);
            mListPreference.setSummary(mListPreference.getEntries()[index]);
        } else {
            // Custom value (not in entryValues)
            mListPreference.setValue("custom");
            mListPreference.setSummary(current + " km");
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String strValue = (String) newValue;
        Log.d(TAG, "User selected value: " + strValue);


        if ("custom".equals(strValue)) {
            if (mListPreference != null) mListPreference.setEnabled(false);
            showCustomDistanceDialog();
            return false;
        }

        int newDistance = Integer.parseInt(strValue);
        Log.d(TAG, "Storing standard distance: " + newDistance + " km");
        Settings.Global.putInt(mResolver, Settings.Global.FAKE_LOCATION_DISTANCE, newDistance);
        updateSummary(newDistance);
        return true;
    }

    private void showCustomDistanceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle("Enter custom distance (0–40000 km)");

        final EditText input = new EditText(mContext);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            try {
                String rawInput = input.getText().toString().trim();
                if (rawInput.isEmpty()) {
                    Toast.makeText(mContext, "Input required", Toast.LENGTH_SHORT).show();
                    return;
                }

                float floatValue = Float.parseFloat(rawInput);
                if (floatValue < 0 || floatValue > 40000) {
                    Toast.makeText(mContext, "Distance must be between 0 and 40000", Toast.LENGTH_SHORT).show();
                    return;
                }

                int ceiled = (int) Math.ceil(floatValue);
                Log.d(TAG, "User entered custom float: " + floatValue + ", storing ceiled: " + ceiled + " km");
                Settings.Global.putInt(mResolver, Settings.Global.FAKE_LOCATION_DISTANCE, ceiled);
                updateSummary(ceiled);

            } catch (NumberFormatException e) {
                Toast.makeText(mContext, "Invalid number format", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);

        // Ensure preference is re-enabled even if dialog dismissed without a button
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (mListPreference != null) {
                mListPreference.setEnabled(true);
            }
        });

        dialog.show();
    }

    private void updateSummary(int value) {
        if (mListPreference != null) {
            mListPreference.setSummary(value + " km");
            //mListPreference.setValue(String.valueOf(value));
            mListPreference.setValue("custom");
        }
    }

    @Override
    public void onLocationModeChanged(int mode, boolean restricted) {
        // Optional: dynamically show/hide based on location state
    }
}
