/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

package com.animeshahilya.espeakng;

import com.animeshahilya.espeakng.ui.SettingsFragment;

import com.animeshahilya.espeakng.ui.SettingsBuilder;

import com.animeshahilya.espeakng.ui.PickedFiles;

import com.animeshahilya.espeakng.ui.SpeechPreview;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.util.BackupRestoreHelper;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.text.UserDictionaryManager;
import com.animeshahilya.espeakng.ui.VoiceProfile;
import com.animeshahilya.espeakng.ui.VoiceSettings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.EspeakApp;

public class TtsSettingsActivity extends AppCompatActivity {

    public static final String TAG = TtsSettingsActivity.class.getSimpleName();

    /** Single accessor for the device-protected default prefs (see EspeakApp). */
    public static SharedPreferences getPrefs() {
        Context storage = EspeakApp.getStorageContext();
        if (storage == null) {
            storage = EspeakApp.requireStorageContext(null);
        }
        if (storage == null) {
            // requireStorageContext(null) returns null before Application.onCreate
            // has run (a static helper reachable from tests/providers). Passing
            // that null into getDefaultSharedPreferences() NPEs with no clue;
            // fail with the actual reason instead.
            throw new IllegalStateException("EspeakApp storage context not initialized");
        }
        return EspeakApp.prefs(storage);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Migrate old eyes-free settings to the new settings:

        final Context storage = EspeakApp.requireStorageContext(getApplicationContext());
        // No CheckVoiceData.ensureVoiceData() here: extracting the ~30MB
        // archive synchronously in onCreate() is the same main-thread ANR
        // class that was already fixed for SettingsBuilder.createPreferences() (#2430). The
        // createPreferences worker below now runs extraction before its
        // engine probe, which is the first thing on this screen that needs
        // the tree on disk.
        final SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor editor = null;

        String pitch = prefs.getString(VoiceSettings.PREF_PITCH, null);
        if (pitch == null) {
            if (editor == null) editor = prefs.edit();
            // Try the old eyes-free setting:
            if (prefs.contains(VoiceSettings.PREF_DEFAULT_PITCH)) {
                pitch = prefs.getString(VoiceSettings.PREF_DEFAULT_PITCH, "100");
                try {
                    int pitchValue = Integer.parseInt(pitch) / 2;
                    editor.putString(VoiceSettings.PREF_PITCH, Integer.toString(pitchValue));
                } catch (NumberFormatException e) {
                    editor.putString(VoiceSettings.PREF_PITCH, Integer.toString(VoiceSettings.DEFAULT_PITCH));
                }
            } else {
                editor.putString(VoiceSettings.PREF_PITCH, Integer.toString(VoiceSettings.DEFAULT_PITCH));
            }
        }

        String pitchRange = prefs.getString(VoiceSettings.PREF_PITCH_RANGE, null);
        if (pitchRange == null) {
            if (editor == null) editor = prefs.edit();
            editor.putString(VoiceSettings.PREF_PITCH_RANGE, Integer.toString(VoiceSettings.DEFAULT_PITCH_RANGE));
        }

        String capitals = prefs.getString(VoiceSettings.PREF_CAPITALS, null);
        if (capitals == null) {
            if (editor == null) editor = prefs.edit();
            editor.putString(VoiceSettings.PREF_CAPITALS, Integer.toString(VoiceSettings.DEFAULT_CAPITALS));
        }

        String rate = prefs.getString(VoiceSettings.PREF_RATE, null);
        // Only a real eyes-free install has PREF_DEFAULT_RATE set; a fresh
        // install has neither pref, and VoiceSettings.getRate() already
        // falls back to the engine default in that case. Constructing
        // SpeechSynthesis here is seconds of JNI/disk work on the main
        // thread (the same ANR risk fixed for SettingsBuilder.createPreferences() below),
        // so skip it unless there is actually something to migrate.
        if (rate == null && prefs.contains(VoiceSettings.PREF_DEFAULT_RATE)) {
            try {
                SpeechSynthesis engine = new SpeechSynthesis(storage, null);
                int defaultValue = engine.Rate.getDefaultValue();
                int maxValue = engine.Rate.getMaxValue();

                rate = prefs.getString(VoiceSettings.PREF_DEFAULT_RATE, "100");
                try {
                    int rateValue = (int) ((Integer.parseInt(rate) / 100.0f) * defaultValue);
                    if (rateValue < defaultValue) rateValue = defaultValue;
                    if (rateValue > maxValue) rateValue = maxValue;
                    if (editor == null) editor = prefs.edit();
                    editor.putString(VoiceSettings.PREF_RATE, Integer.toString(rateValue));
                } catch (NumberFormatException e) {
                    // Malformed legacy value - leave PREF_RATE unset so
                    // VoiceSettings.getRate() falls back to the engine default.
                }
            } catch (Throwable t) {
                // Corrupt/missing voice data (or a JNI failure) must not crash
                // settings startup: leaving PREF_RATE unset just means
                // getRate() falls back to the engine default, same as the
                // NumberFormatException path above.
                Log.w(TAG, "Legacy rate migration skipped", t);
            }
        }

        String variant = prefs.getString(VoiceSettings.PREF_VARIANT, null);
        if (variant == null) {
            if (editor == null) editor = prefs.edit();
            String gender = prefs.getString(VoiceSettings.PREF_DEFAULT_GENDER, null);
            if ("2".equals(gender)) {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceVariant.FEMALE);
            } else if ("1".equals(gender)) {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceVariant.MALE);
            } else {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceSettings.DEFAULT_VARIANT);
            }
        }

        if (editor != null) {
            editor.apply();
        }

        // No applySystemBarAppearance() here: the window has no decor until
        // content is installed, and onResume() below applies it with a valid
        // window on every start (AppCompat getInsetsController() throws on a
        // decor-less window where the framework one happened to survive).
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(!openedFromLauncher(this));
            getSupportActionBar().setTitle(R.string.app_name);
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View contentView = findViewById(android.R.id.content);
        if (contentView != null) {
            contentView.setFitsSystemWindows(false);
        }

        // Only replace on first creation: on recreation the FragmentManager
        // has already restored SettingsFragment (and any dialog fragment
        // targeting it - setTargetFragment's mTargetWho survives in
        // FragmentState). Blindly replacing here destroyed the restored
        // instance and left restored dialogs pointing at a dead fragment,
        // which then NPE'd resolving their Preference against an empty tree.
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    android.R.id.content,
                    new SettingsFragment()).commit();
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
                if (current instanceof SettingsFragment
                        && ((SettingsFragment) current).popToParent()) {
                    return;
                }
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBarAppearance(getWindow(), this);
        // Time-based rows go stale while the screen sits open (a sleep-timer
        // mute expires with no callback to refresh it). Recompute on every
        // return rather than running a live timer for one row.
        try {
            final Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
            if (current instanceof SettingsFragment) {
                final PreferenceScreen screen = ((SettingsFragment) current).getPreferenceScreen();
                final Preference sleep = screen != null
                        ? screen.findPreference(VoiceSettings.PREF_SLEEP_TIMER) : null;
                if (sleep instanceof ListPreference) {
                    SettingsBuilder.updateSleepSummary(this, (ListPreference) sleep, getPrefs());
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PiperSettings.stopPlayback();
    }

    /**
     * Opened from the launcher, the main page is the app's root: an up arrow
     * there only closed the app (TalkBack read "Navigate up" first on every
     * open). From the system's text-to-speech settings it returns there.
     */
    public static boolean openedFromLauncher(Activity activity) {
        return Intent.ACTION_MAIN.equals(activity.getIntent().getAction());
    }

    public static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    public static void applySystemBarAppearance(Window window, Context context) {
        if (window == null || context == null) return;
        boolean isNight = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller =
                new androidx.core.view.WindowInsetsControllerCompat(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(!isNight);
        controller.setAppearanceLightNavigationBars(!isNight);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
            if (current instanceof SettingsFragment
                    && ((SettingsFragment) current).popToParent()) {
                return true;
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        final MenuItem searchItem = menu.findItem(R.id.menu_search_settings);
        if (searchItem != null) {
            final View actionView = searchItem.getActionView();
            if (actionView instanceof androidx.appcompat.widget.SearchView) {
                final androidx.appcompat.widget.SearchView searchView =
                        (androidx.appcompat.widget.SearchView) actionView;
                searchView.setQueryHint(getString(R.string.search_settings_hint));
                searchView.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        return setSearchQuery(query);
                    }

                    @Override
                    public boolean onQueryTextChange(String query) {
                        return setSearchQuery(query);
                    }
                });
                // Collapsing the search field restores the screen it replaced.
                searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item) {
                        return true;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item) {
                        clearSearchQuery();
                        return true;
                    }
                });
            }
        }
        return true;
    }

    private boolean setSearchQuery(String query) {
        Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
        if (current instanceof SettingsFragment) {
            return ((SettingsFragment) current).setSearchQuery(query);
        }
        return false;
    }

    private void clearSearchQuery() {
        Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
        if (current instanceof SettingsFragment) {
            ((SettingsFragment) current).clearSearchQuery();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PiperSettings.stopPlayback();
        SpeechPreview.shutdownPreview();
    }

    public static final int REQUEST_CODE_IMPORT_VOICE = 1001;
    public static final int REQUEST_CODE_IMPORT_DICT = 1002;
    public static final int REQUEST_CODE_EXPORT_DICT = 1003;
    public static final int REQUEST_CODE_EXPORT_BACKUP = 1004;
    public static final int REQUEST_CODE_IMPORT_BACKUP = 1005;
    public static final int REQUEST_CODE_EXPORT_PROFILE = 1006;
    public static final int REQUEST_CODE_IMPORT_PROFILE = 1007;
    public static final int REQUEST_CODE_IMPORT_NATURAL_VOICE = 1008;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_NATURAL_VOICE && resultCode == RESULT_OK && data != null) {
            // One archive, or several files (multiple selection fills clip data).
            final java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) {
                PickedFiles.importNaturalVoice(this, uris);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        switch (requestCode) {
            case REQUEST_CODE_IMPORT_VOICE:
                PickedFiles.importVoiceUri(this, uri);
                break;
            case REQUEST_CODE_IMPORT_DICT:
                Toast.makeText(this, R.string.dict_import_started, Toast.LENGTH_SHORT).show();
                PickedFiles.readPicked(this, uri, "Dictionary", 0,
                        in -> UserDictionaryManager.getInstance(this).importFromStream(in),
                        count -> Toast.makeText(this, count > 0
                                ? getResources().getQuantityString(R.plurals.dict_import_done, count, count)
                                : getString(R.string.dict_import_none), Toast.LENGTH_LONG).show());
                break;
            case REQUEST_CODE_EXPORT_DICT:
                PickedFiles.writePicked(this, uri, "Dictionary",
                        out -> UserDictionaryManager.getInstance(this).exportToStream(out),
                        R.string.dict_export_done, R.string.dict_export_failed);
                break;
            case REQUEST_CODE_IMPORT_BACKUP:
                Toast.makeText(this, R.string.restore_started, Toast.LENGTH_SHORT).show();
                // Rule counts are never negative, so -1 marks failure.
                PickedFiles.readPicked(this, uri, "Backup", -1, in -> BackupRestoreHelper.importFromStream(this, in), count -> {
                    Toast.makeText(this, count >= 0
                            ? getResources().getQuantityString(R.plurals.restore_done, count, count)
                            : getString(R.string.backup_restore_failed), Toast.LENGTH_LONG).show();
                    if (count >= 0) {
                        recreate();
                    }
                });
                break;
            case REQUEST_CODE_EXPORT_BACKUP:
                PickedFiles.writePicked(this, uri, "Backup", out -> BackupRestoreHelper.exportToStream(this, out),
                        R.string.backup_done, R.string.backup_export_failed);
                break;
            case REQUEST_CODE_IMPORT_PROFILE:
                PickedFiles.readPicked(this, uri, "Profile", false, in -> VoiceProfile.loadFromStream(this, in), ok -> {
                    Toast.makeText(this, ok ? R.string.profile_applied : R.string.profile_load_failed,
                            Toast.LENGTH_SHORT).show();
                    if (ok) {
                        recreate();
                    }
                });
                break;
            case REQUEST_CODE_EXPORT_PROFILE:
                PickedFiles.writePicked(this, uri, "Profile", out -> VoiceProfile.saveToStream(this, out),
                        R.string.profile_saved, R.string.profile_save_failed);
                break;
            default:
                break;
        }
    }

    /**
     * True once the hosting activity can no longer accept preference updates.
     * The load outlives a screen the user backed straight out of, and adding
     * preferences to a dead activity's group would leak it.
     */
    public static boolean isGone(Context context) {
        if (!(context instanceof Activity)) {
            return false;
        }
        final Activity activity = (Activity) context;
        return activity.isFinishing() || activity.isDestroyed();
    }

}

