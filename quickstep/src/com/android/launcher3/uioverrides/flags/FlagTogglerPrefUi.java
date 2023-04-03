/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.uioverrides.flags;

import static com.android.launcher3.config.FeatureFlags.FLAGS_PREF_NAME;
import static com.android.launcher3.config.FeatureFlags.FlagState.TEAMFOOD;
import static com.android.launcher3.uioverrides.flags.FlagsFactory.TEAMFOOD_FLAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Process;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreference;

import com.android.launcher3.R;
import com.android.launcher3.config.FeatureFlags;

import java.util.List;
import java.util.Set;

/**
 * Dev-build only UI allowing developers to toggle flag settings. See {@link FeatureFlags}.
 */
public final class FlagTogglerPrefUi {

    private static final String TAG = "FlagTogglerPrefFrag";

    private final PreferenceFragmentCompat mFragment;
    private final Context mContext;
    private final SharedPreferences mSharedPreferences;

    private final PreferenceDataStore mDataStore = new PreferenceDataStore() {

        @Override
        public void putBoolean(String key, boolean value) {
            mSharedPreferences.edit().putBoolean(key, value).apply();
            updateMenu();
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            return mSharedPreferences.getBoolean(key, defaultValue);
        }
    };

    public FlagTogglerPrefUi(PreferenceFragmentCompat fragment) {
        mFragment = fragment;
        mContext = fragment.getActivity();
        mSharedPreferences = mContext.getSharedPreferences(
                FLAGS_PREF_NAME, Context.MODE_PRIVATE);
    }

    public void applyTo(PreferenceGroup parent) {
        Set<String> modifiedPrefs = mSharedPreferences.getAll().keySet();
        List<DebugFlag> flags = FlagsFactory.getDebugFlags();
        flags.sort((f1, f2) -> {
            // Sort first by any prefs that the user has changed, then alphabetically.
            int changeComparison = Boolean.compare(
                    modifiedPrefs.contains(f2.key), modifiedPrefs.contains(f1.key));
            return changeComparison != 0
                    ? changeComparison
                    : f1.key.compareToIgnoreCase(f2.key);
        });

        // Ensure that teamfood flag comes on the top
        if (flags.remove(TEAMFOOD_FLAG)) {
            flags.add(0, (DebugFlag) TEAMFOOD_FLAG);
        }

        // For flag overrides we only want to store when the engineer chose to override the
        // flag with a different value than the default. That way, when we flip flags in
        // future, engineers will pick up the new value immediately. To accomplish this, we use a
        // custom preference data store.
        for (DebugFlag flag : flags) {
            SwitchPreference switchPreference = new SwitchPreference(mContext) {
                @Override
                public void onBindViewHolder(PreferenceViewHolder holder) {
                    super.onBindViewHolder(holder);
                    holder.itemView.setOnLongClickListener(v -> {
                        mSharedPreferences.edit().remove(flag.key).apply();
                        setChecked(getFlagStateFromSharedPrefs(flag));
                        updateSummary(this, flag);
                        updateMenu();
                        return true;
                    });
                }
            };
            switchPreference.setKey(flag.key);
            switchPreference.setDefaultValue(FlagsFactory.getEnabledValue(flag.defaultValue));
            switchPreference.setChecked(getFlagStateFromSharedPrefs(flag));
            switchPreference.setTitle(flag.key);
            updateSummary(switchPreference, flag);
            switchPreference.setPreferenceDataStore(mDataStore);
            switchPreference.setOnPreferenceChangeListener((p, v) -> {
                new Handler().post(() -> updateSummary(switchPreference, flag));
                return true;
            });


            parent.addPreference(switchPreference);
        }
        updateMenu();
    }

    /**
     * Updates the summary to show the description and whether the flag overrides the default value.
     */
    private void updateSummary(SwitchPreference switchPreference, DebugFlag flag) {
        String summary = flag.defaultValue == TEAMFOOD
                ? "<font color='blue'><b>[TEAMFOOD]</b> </font>" : "";
        if (mSharedPreferences.contains(flag.key)) {
            summary += "<font color='red'><b>[OVERRIDDEN]</b> </font>";
        }
        if (!TextUtils.isEmpty(summary)) {
            summary += "<br>";
        }
        switchPreference.setSummary(Html.fromHtml(summary + flag.description));
    }

    private void updateMenu() {
        mFragment.setHasOptionsMenu(anyChanged());
        mFragment.getActivity().invalidateOptionsMenu();
    }

    public void onCreateOptionsMenu(Menu menu) {
        if (anyChanged()) {
            menu.add(0, R.id.menu_apply_flags, 0, "Apply")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
    }

    public void onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_apply_flags) {
            mSharedPreferences.edit().commit();
            Log.e(TAG,
                    "Killing launcher process " + Process.myPid() + " to apply new flag values");
            System.exit(0);
        }
    }

    public void onStop() {
        if (anyChanged()) {
            Toast.makeText(mContext, "Flag won't be applied until you restart launcher",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean getFlagStateFromSharedPrefs(DebugFlag flag) {
        boolean defaultValue = FlagsFactory.getEnabledValue(flag.defaultValue);
        return mDataStore.getBoolean(flag.key, defaultValue);
    }

    private boolean anyChanged() {
        for (DebugFlag flag : FlagsFactory.getDebugFlags()) {
            if (getFlagStateFromSharedPrefs(flag) != flag.get()) {
                return true;
            }
        }
        return false;
    }
}
