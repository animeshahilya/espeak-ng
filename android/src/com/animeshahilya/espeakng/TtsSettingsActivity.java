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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.animeshahilya.espeakng.preference.FavoriteVoicesDialogFragment;
import com.animeshahilya.espeakng.preference.ImportVoicePreference;
import com.animeshahilya.espeakng.preference.SeekBarDialogFragment;
import com.animeshahilya.espeakng.preference.SeekBarPreference;
import com.animeshahilya.espeakng.preference.SpeakPunctuationDialogFragment;
import com.animeshahilya.espeakng.preference.SpeakPunctuationPreference;
import com.animeshahilya.espeakng.preference.SupportedLanguagesDialogFragment;
import com.animeshahilya.espeakng.preference.SupportedLanguagesPreference;
import com.animeshahilya.espeakng.preference.VoiceVariantDialogFragment;
import com.animeshahilya.espeakng.preference.VoiceVariantPreference;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;

public class TtsSettingsActivity extends AppCompatActivity {

    static final String TAG = TtsSettingsActivity.class.getSimpleName();

    /** Single accessor for the device-protected default prefs (see EspeakApp). */
    private static SharedPreferences getPrefs() {
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
        return PreferenceManager.getDefaultSharedPreferences(storage);
    }

    private static final java.util.HashMap<String, LangInfo> sLangInfo = new java.util.HashMap<String, LangInfo>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Migrate old eyes-free settings to the new settings:

        final Context storage = EspeakApp.requireStorageContext(getApplicationContext());
        // No CheckVoiceData.ensureVoiceData() here: extracting the ~30MB
        // archive synchronously in onCreate() is the same main-thread ANR
        // class that was already fixed for createPreferences() (#2430). The
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
        // thread (the same ANR risk fixed for createPreferences() below),
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
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.app_name);
        }

        View contentView = findViewById(android.R.id.content);
        if (contentView != null) {
            contentView.setFitsSystemWindows(false);
        }

        // Only replace on first creation: on recreation the FragmentManager
        // has already restored PrefsEspeakFragment (and any dialog fragment
        // targeting it - setTargetFragment's mTargetWho survives in
        // FragmentState). Blindly replacing here destroyed the restored
        // instance and left restored dialogs pointing at a dead fragment,
        // which then NPE'd resolving their Preference against an empty tree.
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    android.R.id.content,
                    new PrefsEspeakFragment()).commit();
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
                if (current instanceof PrefsEspeakFragment
                        && ((PrefsEspeakFragment) current).popToParent()) {
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
    }


    public static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    // System-UI flags were deprecated in API 30, but this whole branch is the
    // pre-R fallback (the R+ branch above uses WindowInsetsController): on
    // API 26-29 these calls are the only way to tint the system bars.
    @SuppressWarnings("deprecation")
    public static void applySystemBarAppearance(Window window, Context context) {
        if (window == null || context == null) return;
        boolean isNight = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = isNight ? 0 : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                controller.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View decorView = window.getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (!isNight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
            if (current instanceof PrefsEspeakFragment
                    && ((PrefsEspeakFragment) current).popToParent()) {
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
        if (current instanceof PrefsEspeakFragment) {
            return ((PrefsEspeakFragment) current).setSearchQuery(query);
        }
        return false;
    }

    private void clearSearchQuery() {
        Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
        if (current instanceof PrefsEspeakFragment) {
            ((PrefsEspeakFragment) current).clearSearchQuery();
        }
    }

    private static TextToSpeech sTts;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sTts != null) {
            try {
                sTts.stop();
                sTts.shutdown();
            } catch (Exception ignored) {
            }
            sTts = null;
        }
    }

    public static final int REQUEST_CODE_IMPORT_VOICE = 1001;
    public static final int REQUEST_CODE_IMPORT_DICT = 1002;
    public static final int REQUEST_CODE_EXPORT_DICT = 1003;
    public static final int REQUEST_CODE_EXPORT_BACKUP = 1004;
    public static final int REQUEST_CODE_IMPORT_BACKUP = 1005;
    public static final int REQUEST_CODE_EXPORT_PROFILE = 1006;
    public static final int REQUEST_CODE_IMPORT_PROFILE = 1007;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_VOICE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importVoiceUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_IMPORT_DICT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importDictionaryUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_EXPORT_DICT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportDictionaryUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_EXPORT_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportBackupUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_IMPORT_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importBackupUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_EXPORT_PROFILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportProfileUri(this, data.getData());
        } else if (requestCode == REQUEST_CODE_IMPORT_PROFILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importProfileUri(this, data.getData());
        }
    }

    /** Unit of background work for {@link #runInBackground}. */
    private interface BackgroundWork<T> {
        T run();
    }

    /** Main-thread continuation for {@link #runInBackground}. */
    private interface BackgroundDone<T> {
        void done(T result);
    }

    /**
     * Runs {@code work} on a named worker thread, then posts {@code done} to
     * the main thread. Unifies the thread + Handler shape every
     * import/export block used to hand-roll.
     */
    private static <T> void runInBackground(String threadName,
            final BackgroundWork<T> work, final BackgroundDone<T> done) {
        new Thread(new Runnable() {
            @Override public void run() {
                final T result = work.run();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        done.done(result);
                    }
                });
            }
        }, threadName).start();
    }

    private static void importDictionaryUri(final Activity activity, final Uri uri) {
        Toast.makeText(activity, R.string.dict_import_started, Toast.LENGTH_SHORT).show();
        runInBackground("dict-import", new BackgroundWork<Integer>() {
            @Override public Integer run() {
                int added = 0;
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        added = UserDictionaryManager.getInstance(activity).importFromStream(is);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Dictionary import failed", e);
                }
                return added;
            }
        }, new BackgroundDone<Integer>() {
            @Override public void done(Integer count) {
                if (isGone(activity)) return;
                Toast.makeText(activity,
                        activity.getResources().getQuantityString(R.plurals.dict_import_done, count, count),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void exportDictionaryUri(final Activity activity, final Uri uri) {
        runInBackground("dict-export", new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                boolean ok = false;
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        UserDictionaryManager.getInstance(activity).exportToStream(os);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Dictionary export failed", e);
                }
                return ok;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean done) {
                if (isGone(activity)) return;
                Toast.makeText(activity,
                        done ? R.string.dict_export_done : R.string.dict_export_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void exportBackupUri(final Activity activity, final Uri uri) {
        runInBackground("backup-export", new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                boolean ok = false;
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        BackupRestoreHelper.exportToStream(activity, os);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Backup export failed", e);
                }
                return ok;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean done) {
                if (isGone(activity)) return;
                Toast.makeText(activity,
                        done ? R.string.backup_done : R.string.backup_export_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void importBackupUri(final Activity activity, final Uri uri) {
        Toast.makeText(activity, R.string.restore_started, Toast.LENGTH_SHORT).show();
        runInBackground("backup-import", new BackgroundWork<Integer>() {
            @Override public Integer run() {
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        // Rule counts are never negative, so -1 marks failure.
                        return BackupRestoreHelper.importFromStream(activity, is);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Backup import failed", e);
                }
                return -1;
            }
        }, new BackgroundDone<Integer>() {
            @Override public void done(Integer count) {
                if (isGone(activity)) return;
                final boolean done = count >= 0;
                Toast.makeText(activity,
                        done ? activity.getResources().getQuantityString(R.plurals.restore_done, count, count)
                                : activity.getString(R.string.backup_restore_failed),
                        Toast.LENGTH_LONG).show();
                if (done) {
                    activity.recreate();
                }
            }
        });
    }

    private static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                // Ignore fallback
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result != null ? result : "imported_data";
    }

    private static void importVoiceUri(final Activity activity, final Uri uri) {
        final Context storage = EspeakApp.requireStorageContext(activity);
        runInBackground("voice-import-thread", new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                boolean success = false;
                String fileName = getFileNameFromUri(activity, uri);
                File targetDir = CheckVoiceData.getDataPath(storage);
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                try (InputStream inputStream = activity.getContentResolver().openInputStream(uri)) {
                    if (inputStream != null) {
                        // Locale.ROOT: Turkish-locale devices would map a
                        // capital I in ".ZIP" to a dotless ı and fail the check.
                        if (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                            FileUtils.extractZip(inputStream, targetDir);
                            success = true;
                        } else {
                            File outFile = new File(targetDir, fileName);
                            // Same containment guarantee FileUtils.extractZip
                            // enforces per entry: a crafted display name (or
                            // one reported by a content provider) must not be
                            // able to write outside the voice data directory.
                            if (outFile.getCanonicalPath()
                                    .startsWith(targetDir.getCanonicalPath() + File.separator)) {
                                try (OutputStream fos = new FileOutputStream(outFile)) {
                                    byte[] buffer = new byte[8192];
                                    int len;
                                    while ((len = inputStream.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                    success = true;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error importing voice data from URI", e);
                    success = false;
                }
                return success;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean finalSuccess) {
                if (isGone(activity)) return;
                if (finalSuccess) {
                    synchronized (TtsSettingsActivity.class) {
                        sLangInfo.clear();
                    }
                    final Intent updated =
                            new Intent(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED);
                    // Same scoping as DownloadVoiceData: only this
                    // app's TtsService should act on it.
                    updated.setPackage(activity.getPackageName());
                    activity.sendBroadcast(updated);
                    Toast.makeText(activity, R.string.import_voice_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, R.string.import_voice_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public static class PrefsEspeakFragment extends PreferenceFragmentCompat {
        private static final String DIALOG_FRAGMENT_TAG =
                "androidx.preference.PreferenceFragment.DIALOG";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // The fragment has its own PreferenceManager, separate from the
            // activity's. Everything on this screen persists through it, so it
            // must use the device-protected file that TtsService reads. A
            // Preference left on the default credential-encrypted storage has
            // no effect on speech, and its stray file is what #2536 copied
            // over the real settings on every screen reader restart.
            getPreferenceManager().setStorageDeviceProtected();
            // Empty until the engine has loaded; see createPreferences().
            setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
            createPreferences(this);
        }

        // setTargetFragment is deprecated in general, but it is the documented
        // mechanism here: PreferenceDialogFragmentCompat.onCreate() requires a
        // target implementing DialogPreference.TargetFragment (which
        // PreferenceFragmentCompat does) and resolves the preference through
        // it - without this every custom dialog crashes on open.
        // Framework auto-showed nested PreferenceScreens in dialogs. AndroidX
        // does neither: base onPreferenceTreeClick only handles the deprecated
        // android:fragment path, and onNavigateToScreen merely delegates to an
        // OnPreferenceStartScreenCallback nobody implements (verified against
        // the 1.2.1 source - calling it is a silent no-op). So this fragment
        // re-roots itself instead: same manager, no tree rebuild. Back pops
        // through the activity's OnBackPressedCallback (registered in
        // TtsSettingsActivity.onCreate), which calls popToParent() before
        // finishing. Rotation does not save this stack (no onSaveInstanceState
        // override), dropping back to the root screen - matching the old
        // framework dialogs, which never survived rotation either.
        private final Deque<PreferenceScreen> mScreenStack = new ArrayDeque<>();

        @Override
        public boolean onPreferenceTreeClick(Preference preference) {
            if (preference != null && preference.getKey() != null
                    && preference.getKey().startsWith(SEARCH_RESULT_PREFIX)) {
                navigateToSearchResult(preference.getKey().substring(SEARCH_RESULT_PREFIX.length()));
                return true;
            }
            if (preference instanceof PreferenceScreen) {
                PreferenceScreen current = getPreferenceScreen();
                if (current != null) {
                    mScreenStack.push(current);
                }
                setPreferenceScreen((PreferenceScreen) preference);
                updateTitle();
                focusFirstRow();
                return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        /**
         * Returns to the parent screen if navigated into a sub-screen.
         *
         * @return true if a sub-screen was showing and the parent restored.
         */
        boolean popToParent() {
            PreferenceScreen parent = mScreenStack.poll();
            if (parent == null) {
                return false;
            }
            setPreferenceScreen(parent);
            updateTitle();
            focusFirstRow();
            return true;
        }

        /**
         * Moves TalkBack focus to the first row after a screen change.
         * Re-rooting swaps the whole list silently: sighted users see a new
         * list from the top, but a screen reader's focus stays where the old
         * list's position was -- potentially mid-list or on nothing. The
         * announcement alone (see updateTitle()) tells the user where they
         * landed without moving them there; this completes it. Sighted users
         * only get the scroll reset, never a focus jump.
         */
        private void focusFirstRow() {
            final View root = getView();
            final Activity activity = getActivity();
            if (root == null || activity == null) {
                return;
            }
            final View list = root.findViewById(androidx.preference.R.id.recycler_view);
            if (!(list instanceof androidx.recyclerview.widget.RecyclerView)) {
                return;
            }
            final androidx.recyclerview.widget.RecyclerView recycler =
                    (androidx.recyclerview.widget.RecyclerView) list;
            recycler.scrollToPosition(0);
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) {
                return;
            }
            recycler.post(new Runnable() {
                @Override public void run() {
                    try {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                                recycler.findViewHolderForAdapterPosition(0);
                        View row = holder != null ? holder.itemView : null;
                        if (row == null && recycler.getChildCount() > 0) {
                            row = recycler.getChildAt(0);
                        }
                        if (row != null) {
                            row.performAccessibilityAction(
                                    android.view.accessibility.AccessibilityNodeInfo
                                            .ACTION_ACCESSIBILITY_FOCUS, null);
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        /**
         * Scrolls the current screen's list to the row for {@code key} and
         * moves TalkBack focus to it. Used after search navigation so the
         * user lands on the match, not the top of its screen.
         */
        private void scrollToKey(final String key) {
            final View root = getView();
            if (root == null || key == null) {
                return;
            }
            final View list = root.findViewById(androidx.preference.R.id.recycler_view);
            if (!(list instanceof androidx.recyclerview.widget.RecyclerView)) {
                return;
            }
            final androidx.recyclerview.widget.RecyclerView recycler =
                    (androidx.recyclerview.widget.RecyclerView) list;
            final androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter = recycler.getAdapter();
            if (!(adapter instanceof androidx.preference.PreferenceGroupAdapter)) {
                return;
            }
            final androidx.preference.PreferenceGroupAdapter group =
                    (androidx.preference.PreferenceGroupAdapter) adapter;
            int position = -1;
            for (int i = 0; i < group.getItemCount(); i++) {
                try {
                    Preference item = group.getItem(i);
                    if (item != null && key.equals(item.getKey())) {
                        position = i;
                        break;
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
            if (position < 0) {
                return;
            }
            recycler.scrollToPosition(position);
            final int target = position;
            recycler.post(new Runnable() {
                @Override public void run() {
                    try {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                                recycler.findViewHolderForAdapterPosition(target);
                        if (holder != null) {
                            holder.itemView.performAccessibilityAction(
                                    android.view.accessibility.AccessibilityNodeInfo
                                            .ACTION_ACCESSIBILITY_FOCUS, null);
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        // Settings search: rows are tagged with this prefix so a tap can be
        // told apart from a real preference click in onPreferenceTreeClick.
        private static final String SEARCH_RESULT_PREFIX = "search_result::";
        private static final int SEARCH_MAX_RESULTS = 50;

        /** The full inflated tree; search always runs against this, not the showing screen. */
        private PreferenceScreen mRootScreen;
        private final List<SearchEntry> mSearchIndex = new ArrayList<SearchEntry>();
        /** Target key -> chain of sub-screen keys from the root. */
        private final Map<String, List<String>> mSearchChains = new HashMap<String, List<String>>();
        /** Non-null while the results screen is showing. */
        private PreferenceScreen mSearchResults;

        private static final class SearchEntry {
            final String key;
            final String title;
            final String detail;
            final List<String> chain;

            SearchEntry(String key, String title, String detail, List<String> chain) {
                this.key = key;
                this.title = title;
                this.detail = detail;
                this.chain = chain;
            }
        }

        /** Called once the engine-backed tree exists; indexes every titled row. */
        void onRootScreenReady(PreferenceScreen root) {
            mRootScreen = root;
            mSearchIndex.clear();
            mSearchChains.clear();
            if (root != null) {
                walkSearchTree(root, new ArrayList<String>(), root.getTitle());
            }
        }

        private void walkSearchTree(androidx.preference.PreferenceGroup group,
                List<String> chain, CharSequence crumb) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                final Preference p = group.getPreference(i);
                if (p == null || !p.isVisible() || p.getTitle() == null) {
                    continue;
                }
                if (p instanceof PreferenceScreen) {
                    final List<String> sub = new ArrayList<String>(chain);
                    sub.add(p.getKey());
                    final String detail = crumb != null ? crumb.toString() : "";
                    mSearchIndex.add(new SearchEntry(p.getKey(), p.getTitle().toString(), detail, sub));
                    mSearchChains.put(p.getKey(), sub);
                    walkSearchTree((PreferenceScreen) p, sub, p.getTitle());
                } else {
                    if (p.getKey() == null || p.getKey().startsWith(SEARCH_RESULT_PREFIX)) {
                        continue;
                    }
                    CharSequence summary = p.getSummary();
                    final String detail = (summary != null ? summary.toString() + " • " : "")
                            + (crumb != null ? crumb.toString() : "");
                    mSearchIndex.add(new SearchEntry(p.getKey(), p.getTitle().toString(), detail, chain));
                    if (!mSearchChains.containsKey(p.getKey())) {
                        mSearchChains.put(p.getKey(), chain);
                    }
                }
            }
        }

        /**
         * Filters the whole tree for {@code query} and shows the matches.
         * Returns true when the query was consumed (including "not ready").
         */
        boolean setSearchQuery(String query) {
            final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                clearSearchQuery();
                return true;
            }
            if (mRootScreen == null || !isAdded()) {
                final Activity activity = getActivity();
                if (activity != null) {
                    Toast.makeText(activity, R.string.search_not_ready, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            // Reindex every query: rows reveal and hide as settings change
            // (e.g. the custom intonation group), and the index is ~60 rows.
            onRootScreenReady(mRootScreen);
            final List<SearchEntry> matches = new ArrayList<SearchEntry>();
            for (SearchEntry e : mSearchIndex) {
                if (matches.size() >= SEARCH_MAX_RESULTS) {
                    break;
                }
                final String haystack = (e.title + "\n" + e.detail).toLowerCase(Locale.ROOT);
                if (haystack.contains(q)) {
                    matches.add(e);
                }
            }
            final PreferenceScreen current = getPreferenceScreen();
            if (mSearchResults == null && current != null) {
                mScreenStack.push(current);
            }
            final PreferenceScreen results =
                    getPreferenceManager().createPreferenceScreen(requireContext());
            results.setTitle(getString(R.string.search_results_title)
                    + " (" + matches.size() + ")");
            if (matches.isEmpty()) {
                final Preference empty = new Preference(requireContext());
                empty.setTitle(R.string.search_no_match);
                empty.setSelectable(false);
                results.addPreference(empty);
            } else {
                for (SearchEntry e : matches) {
                    final Preference row = new Preference(requireContext());
                    row.setKey(SEARCH_RESULT_PREFIX + e.key);
                    row.setTitle(e.title);
                    row.setSummary(e.detail);
                    results.addPreference(row);
                }
            }
            mSearchResults = results;
            setPreferenceScreen(results);
            updateTitle();
            return true;
        }

        /** Leaves the results screen, returning to the screen search replaced. */
        void clearSearchQuery() {
            if (mSearchResults == null) {
                return;
            }
            mSearchResults = null;
            popToParent();
        }

        /** Follows one search result to its row: root, down its chain, scroll. */
        private void navigateToSearchResult(String targetKey) {
            final List<String> chain = mSearchChains.get(targetKey);
            mSearchResults = null;
            // Silent reset, not popToParent(): each pop would announce and
            // refocus, so following one result would chatter through every
            // screen on the way back to the root.
            PreferenceScreen current = getPreferenceScreen();
            PreferenceScreen parent;
            while ((parent = mScreenStack.poll()) != null) {
                current = parent;
            }
            if (current == null || chain == null) {
                return;
            }
            for (String screenKey : chain) {
                final Preference next = current.findPreference(screenKey);
                if (!(next instanceof PreferenceScreen)) {
                    return;
                }
                mScreenStack.push(current);
                current = (PreferenceScreen) next;
            }
            setPreferenceScreen(current);
            updateTitle();
            scrollToKey(targetKey);
        }

        private void updateTitle() {
            Activity activity = getActivity();
            if (activity instanceof AppCompatActivity) {
                androidx.appcompat.app.ActionBar actionBar =
                        ((AppCompatActivity) activity).getSupportActionBar();
                if (actionBar != null) {
                    PreferenceScreen screen = getPreferenceScreen();
                    if (mScreenStack.isEmpty() || screen == null || screen.getTitle() == null) {
                        actionBar.setTitle(R.string.app_name);
                    } else {
                        actionBar.setTitle(screen.getTitle());
                    }
                    // Re-rooting the fragment swaps the whole list silently;
                    // announce the new screen name so TalkBack users hear
                    // where they landed instead of only seeing the title bar
                    // change under them.
                    announceScreenTitle(actionBar.getTitle());
                }
            }
        }

        /**
         * announceForAccessibility was deprecated in Android 16; these one-shot
         * polite announcements for explicit navigation are the non-disruptive
         * case (same rationale as SupportedLanguagesDialogFragment).
         */
        @SuppressWarnings("deprecation")
        private void announceScreenTitle(CharSequence title) {
            announce(title);
        }

        /** One-shot polite announcement through the preference list view. */
        @SuppressWarnings("deprecation")
        void announce(CharSequence text) {
            View list = getView() != null
                    ? getView().findViewById(androidx.preference.R.id.recycler_view)
                    : null;
            if (list != null && text != null) {
                list.announceForAccessibility(text);
            }
        }

        @Override
        public void onDisplayPreferenceDialog(Preference preference) {
            if (preference instanceof VoiceVariantPreference) {
                showDialogTargeted(VoiceVariantDialogFragment.newInstance(preference.getKey()));
                return;
            }
            if (preference instanceof SpeakPunctuationPreference) {
                showDialogTargeted(SpeakPunctuationDialogFragment.newInstance(preference.getKey()));
                return;
            }
            if (preference instanceof SeekBarPreference) {
                showDialogTargeted(SeekBarDialogFragment.newInstance(preference.getKey()));
                return;
            }
            if (preference instanceof SupportedLanguagesPreference) {
                showDialogTargeted(SupportedLanguagesDialogFragment.newInstance(preference.getKey()));
                return;
            }
            super.onDisplayPreferenceDialog(preference);
        }

        // Single choke point for the custom dialogs above. setTargetFragment
        // is deprecated in general (see the comment on this fragment), but it
        // is the documented mechanism for PreferenceDialogFragmentCompat,
        // which resolves its preference through the target.
        @SuppressWarnings("deprecation")
        private void showDialogTargeted(DialogFragment fragment) {
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // AndroidX hosts the list in a RecyclerView, not a ListView.
            final View listView = view.findViewById(androidx.preference.R.id.recycler_view);
            // androidx.preference's preference_recyclerview.xml hardcodes
            // clipToPadding="false", which lets rows scroll through the
            // transparent status bar. Top/bottom padding marks the opaque
            // system/action bars, so content must stop at those edges.
            if (listView instanceof ViewGroup) {
                ((ViewGroup) listView).setClipToPadding(true);
            }
            final Context context = getActivity();
            final View.OnApplyWindowInsetsListener insetsListener = new View.OnApplyWindowInsetsListener() {
                // getSystemWindowInset* was deprecated in API 30; kept for the
                // pre-R branch, which has no WindowInsets.Type API. Content is
                // edge-to-edge (fitsSystemWindows=false on android.R.id.content),
                // and ActionBarOverlayLayout leaves the list at y=0 under the
                // status bar and the overlaid action bar - pad top past both so
                // the first row is reachable, bottom past the nav bar so the
                // last row can scroll clear of it. Action-bar height comes from
                // the laid-out container: resolving android.R.attr.actionBarSize
                // against the M3 theme over-reports here and leaves a dead gap.
                @Override
                @SuppressWarnings("deprecation")
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int topInset = 0;
                    int bottomInset = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
                        bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    } else {
                        topInset = insets.getSystemWindowInsetTop();
                        bottomInset = insets.getSystemWindowInsetBottom();
                    }
                    int actionBarHeight = 0;
                    Activity activity = getActivity();
                    if (activity instanceof AppCompatActivity
                            && ((AppCompatActivity) activity).getSupportActionBar() != null) {
                        View abContainer = activity.findViewById(
                                androidx.appcompat.R.id.action_bar_container);
                        if (abContainer != null && abContainer.getHeight() > 0) {
                            actionBarHeight = abContainer.getHeight();
                        }
                    }
                    if (listView != null && context != null) {
                        listView.setPadding(
                                listView.getPaddingLeft(),
                                topInset + actionBarHeight,
                                listView.getPaddingRight(),
                                bottomInset + dpToPx(context, 16)
                        );
                    }
                    return insets;
                }
            };
            view.setOnApplyWindowInsetsListener(insetsListener);
            if (listView != null) {
                listView.setOnApplyWindowInsetsListener(insetsListener);
            }
            view.requestApplyInsets();
            // The action bar may still be 0-tall on the first insets pass;
            // re-apply once it has measured so top padding clears it.
            final View abContainer = getActivity() != null
                    ? getActivity().findViewById(androidx.appcompat.R.id.action_bar_container)
                    : null;
            if (abContainer != null) {
                abContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b,
                                               int ol, int ot, int or, int ob) {
                        if (b - t > 0) {
                            v.removeOnLayoutChangeListener(this);
                            view.requestApplyInsets();
                        }
                    }
                });
            }
        }
    }

    /**
     * Describes one voice parameter to {@link SeekBarPreference}: where its
     * value lives, what it is called and how it reads.
     */
    private static SeekBarPreference.Parameter voiceParameter(Context context,
                                                              SpeechSynthesis.Parameter parameter,
                                                              String key, int titleRes) {
        final String formatter;
        if (VoiceSettings.PREF_WORD_GAP.equals(key)) {
            formatter = context.getString(R.string.formatter_gap_ms);
        } else {
            switch (parameter.getUnitType())
            {
                case Percentage:
                    formatter = context.getString(R.string.formatter_percentage);
                    break;
                case WordsPerMinute:
                    formatter = context.getString(R.string.formatter_wpm);
                    break;
                default:
                    // A future engine parameter whose UnitType this formatter
                    // doesn't know must not crash the settings screen while
                    // it builds - format it as a percentage and move on.
                    formatter = context.getString(R.string.formatter_percentage);
                    break;
            }
        }

        final SharedPreferences prefs = getPrefs();
        final String value = prefs.getString(key, null);
        int defaultVal = parameter.getDefaultValue();
        if (VoiceSettings.PREF_PITCH.equals(key)) {
            defaultVal = VoiceSettings.DEFAULT_PITCH;
        } else if (VoiceSettings.PREF_PITCH_RANGE.equals(key)) {
            defaultVal = VoiceSettings.DEFAULT_PITCH_RANGE;
        }
        int current = defaultVal;
        if (value != null) {
            try {
                current = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Malformed value - fall back to default
            }
        }

        final SeekBarPreference.Parameter voiceParam = new SeekBarPreference.Parameter(
                key,
                context.getString(titleRes),
                parameter.getMinValue(),
                parameter.getMaxValue(),
                defaultVal,
                current,
                formatter);

        if (VoiceSettings.PREF_WORD_GAP.equals(key)) {
            voiceParam.setValueMultiplier(10);
        }

        if (VoiceSettings.PREF_RATE.equals(key)) {
            final VoiceSettings settings = new VoiceSettings(prefs, null); // boost getters never touch the engine
            voiceParam.enableRateBoost(settings.isRateBoostEnabled(), settings.getRateBoostMultiplier());
        }

        return voiceParam;
    }

    private static String getSupportedLanguagesSummary(Context context, Set<String> selected, int total) {
        int enabled = (selected == null || selected.isEmpty()) ? total : selected.size();
        if (enabled >= total) {
            return context.getString(R.string.espeak_supported_languages_all);
        }
        return context.getResources().getQuantityString(R.plurals.espeak_supported_languages_summary, total, enabled, total);
    }

    /**
     * Localized voice label: the name in the system's own language first
     * (e.g. "हिन्दी" on a Hindi system), followed by the English name from
     * the voice data in parentheses. That is the Android-native answer to
     * upstream #2515's self-designation registry proposal - Locale already
     * localizes every language for free, with no registry to maintain.
     * On an English system the voice data's own name is used alone: the
     * Locale name would only repeat it in other words ("Abkhazian (Abkhaz)",
     * "Armenian (Armenian (East Armenia))"), which TalkBack then reads twice.
     * Also falls back to the English name when both agree, and to the raw
     * voice name when neither is available. Keeping the English name
     * always present also keeps regional variants that share a localized
     * name ("English (India)" vs "English (Singapore)") distinguishable.
     */
    private static String getVoiceLabel(Voice voice) {
        LangInfo info = lookupLangInfo(voice);
        final String english = info != null ? info.displayName : voice.name;
        if ("en".equals(Locale.getDefault().getLanguage())) {
            return english;
        }
        final String localized;
        try {
            localized = voice.locale.getDisplayName(Locale.getDefault());
        } catch (Exception e) {
            return english;
        }
        if (localized == null || localized.isEmpty() || localized.equalsIgnoreCase(english)) {
            return english;
        }
        return localized + " (" + english + ")";
    }

    private static class LangInfo {
        final String displayName;
        LangInfo(String displayName) {
            this.displayName = displayName;
        }
    }

    private static LangInfo lookupLangInfo(Voice voice) {
        ensureLangInfoLoaded();
        String key1 = voice.name;
        String key2 = null;
        if (voice.identifier != null) {
            int slash = voice.identifier.lastIndexOf('/');
            key2 = (slash >= 0 && slash < voice.identifier.length() - 1) ? voice.identifier.substring(slash + 1) : voice.identifier;
        }
        // Same monitor as ensureLangInfoLoaded() and the import-thread clear():
        // HashMap is not thread-safe, so unsynchronized reads could observe
        // a half-updated map.
        synchronized (TtsSettingsActivity.class) {
            LangInfo info = sLangInfo.get(key1);
            if (info == null && key2 != null) {
                info = sLangInfo.get(key2);
            }
            return info;
        }
    }

    // Synchronized because createPreferences() warms this from a worker thread
    // while lookupLangInfo() may reach it from the main thread. Readers always
    // come through here first, so they block until a build in progress
    // finishes rather than observing a half-populated map.
    private static synchronized void ensureLangInfoLoaded() {
        Context storage = EspeakApp.getStorageContext();
        if (!sLangInfo.isEmpty() || storage == null) return;
        File root = new File(CheckVoiceData.getDataPath(storage), "lang");
        if (!root.exists()) return;
        Stack<File> stack = new Stack<File>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory()) {
                    stack.push(f);
                } else {
                    LangInfo info = parseLangFile(f);
                    if (info != null) {
                        sLangInfo.put(f.getName(), info);
                    }
                }
            }
        }
    }

    private static LangInfo parseLangFile(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String name = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("name")) {
                    name = line.substring("name".length()).trim();
                    break;
                }
            }
            if (name == null) return null;
            return new LangInfo(name);
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Failed parsing lang file " + file.getName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Gathers what the preference screen needs from the engine, then builds it.
     *
     * All of the expensive part used to run inline in onCreate(): constructing
     * SpeechSynthesis initialises the native library (nativeCreate loads
     * phondata and the dictionaries), getAvailableVoices() enumerates every
     * voice over JNI, and building the supported-languages list walks the whole
     * lang/ tree opening and parsing one file per voice. That is seconds of
     * disk and JNI work on a cold start with a slow filesystem, on the thread
     * that has to stay responsive -- the ANR risk reported in #2430.
     *
     * So it is gathered on a worker thread, and the screen is inflated from
     * res/xml/preferences.xml on the main thread when it lands.
     */
    private static void createPreferences(final PreferenceFragmentCompat fragment) {
        final Context context = fragment.requireActivity();
        final Context storage = EspeakApp.requireStorageContext(context);
        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final boolean isWatch = context.getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_WATCH);

                    // Extract/refresh voice data off the main thread (moved out
                    // of onCreate; see the comment there). Runs under
                    // CheckVoiceData's extraction lock, so a concurrent
                    // TtsService.onCreate() doing the same work serializes
                    // instead of racing.
                    CheckVoiceData.ensureVoiceData(storage);

                    final SpeechSynthesis engine = new SpeechSynthesis(storage, null);
                    final List<Voice> voices = engine.getAvailableVoices();

                    // Warm the lang/ metadata cache here rather than leaving it to
                    // the first getVoiceLabel() call, which would drag the whole
                    // scan back onto the main thread. Skipped on Wear, where the
                    // supported-languages list is not built at all.
                    if (!isWatch) {
                        ensureLangInfoLoaded();
                    }

                    handler.post(() -> {
                        if (isGone(context) || !fragment.isAdded()) {
                            return;
                        }
                        final PreferenceScreen root = buildPreferences(context,
                                (PrefsEspeakFragment) fragment,
                                fragment.getPreferenceManager(), engine, voices, isWatch);
                        fragment.setPreferenceScreen(root);
                        ((PrefsEspeakFragment) fragment).onRootScreenReady(root);
                        maybeShowWhatsNew(context);
                    });
                } catch (Throwable t) {
                    // The engine probe (native lib load, phondata, JNI voice
                    // enumeration) used to run unguarded on this worker: any
                    // failure became an uncaught exception that killed the
                    // whole process with zero UI feedback.
                    Log.e(TAG, "Failed to build settings preferences", t);
                    handler.post(() -> {
                        if (isGone(context)) {
                            return;
                        }
                        Toast.makeText(context, R.string.settings_load_failed,
                                Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, "espeak-settings-load").start();
    }

    /**
     * True once the hosting activity can no longer accept preference updates.
     * The load outlives a screen the user backed straight out of, and adding
     * preferences to a dead activity's group would leak it.
     */
    static boolean isGone(Context context) {
        if (!(context instanceof Activity)) {
            return false;
        }
        final Activity activity = (Activity) context;
        return activity.isFinishing() || activity.isDestroyed();
    }


    /**
     * Speaks {@code text} through the lazily-initialized preview engine.
     * Unifies the TTS init/speak shape previewText() and playTestVoice()
     * used to duplicate; only the text and utterance id differ.
     */
    static void speakPreview(final Context context, final String text,
            final String utteranceId) {
        if (sTts == null) {
            sTts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS && sTts != null) {
                        sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                    }
                }
            }, context.getPackageName());
        } else {
            sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    private static void playTestVoice(final Context context) {
        // The sample itself is the confirmation once it starts, but there is
        // a beat of TTS-init silence first; the toast tells TalkBack the tap
        // registered instead of leaving the user wondering.
        Toast.makeText(context, R.string.test_voice_playing, Toast.LENGTH_SHORT).show();
        speakPreview(context, context.getString(R.string.test_voice_sample), "sample_utterance");
    }

    /**
     * Speaks {@code text} in {@code locale} through the preview engine, then
     * restores the engine's previous voice. Used by the language picker's
     * touch-and-hold sample: the preview engine otherwise speaks everything
     * in the current settings voice, which would make every sample sound the
     * same. Restoration runs on utterance completion with a timeout fallback,
     * so a dropped callback cannot leave the preview engine (and the next
     * test-voice tap) stuck in the sampled language.
     */
    public static void speakPreviewInLanguage(final Context context, final String text,
            final java.util.Locale locale, final String utteranceId) {
        ensurePreviewEngine(context, new Runnable() {
            @Override public void run() {
                if (sTts == null) return;
                android.speech.tts.Voice previous = null;
                try {
                    previous = sTts.getVoice();
                } catch (Exception ignored) {
                }
                final android.speech.tts.Voice restoreTo = previous;
                try {
                    sTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {
                        }

                        @Override public void onDone(String id) {
                            restorePreviewVoice(restoreTo);
                        }

                        @Override public void onError(String id) {
                            restorePreviewVoice(restoreTo);
                        }

                        @Override public void onError(String id, int errorCode) {
                            restorePreviewVoice(restoreTo);
                        }
                    });
                } catch (Exception ignored) {
                }
                try {
                    sTts.setLanguage(locale);
                } catch (Exception e) {
                    restorePreviewVoice(restoreTo);
                    return;
                }
                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                // Timeout fallback: no callback (or a slow engine init) must
                // not strand the preview voice. Harmless if onDone already ran.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        restorePreviewVoice(restoreTo);
                    }
                }, 8000);
            }
        });
    }

    private static void restorePreviewVoice(android.speech.tts.Voice voice) {
        if (sTts == null) return;
        try {
            if (voice != null) {
                sTts.setVoice(voice);
            } else {
                sTts.setLanguage(java.util.Locale.getDefault());
            }
        } catch (Exception ignored) {
        }
        try {
            sTts.setOnUtteranceProgressListener(null);
        } catch (Exception ignored) {
        }
    }

    /** Runs {@code after} once the lazily-initialized preview engine exists. */
    private static void ensurePreviewEngine(final Context context, final Runnable after) {
        if (sTts != null) {
            after.run();
            return;
        }
        sTts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    after.run();
                }
            }
        }, context.getPackageName());
    }

    /**
     * Marks an AlertDialog's title as a heading (API 28+) so TalkBack users
     * can jump to it with heading navigation, matching the preference
     * categories and the about dialog's own headings. No-op when the dialog
     * has no title view yet (called before show()).
     */
    public static void markAlertTitleHeading(AlertDialog dialog) {
        if (dialog == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            View title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (title != null) {
                title.setAccessibilityHeading(true);
            }
        } catch (Exception ignored) {
        }
    }

    // Voice profiles: named files holding variant, rate, pitch, pitch range,
    // volume and punctuation, so users can share NVDA-style voice setups.
    // Full settings stay in backup/restore; profiles are the small sharable
    // slice. Unknown keys in a profile are ignored, so old profiles load on
    // newer app versions and newer profiles degrade on older ones.

    private static void exportProfileUri(final Activity activity, final Uri uri) {
        runInBackground("profile-export", new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                boolean ok = false;
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        VoiceProfile.saveToStream(activity, os);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Profile export failed", e);
                }
                return ok;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean done) {
                if (isGone(activity)) return;
                Toast.makeText(activity,
                        done ? R.string.profile_saved : R.string.profile_save_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void importProfileUri(final Activity activity, final Uri uri) {
        runInBackground("profile-import", new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        return VoiceProfile.loadFromStream(activity, is);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Profile import failed", e);
                }
                return false;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean done) {
                if (isGone(activity)) return;
                Toast.makeText(activity,
                        done ? R.string.profile_applied : R.string.profile_load_failed,
                        Toast.LENGTH_SHORT).show();
                if (done) {
                    activity.recreate();
                }
            }
        });
    }

    private static void showAboutDialog(final Context context) {
        String versionName;
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = BuildConfig.VERSION_NAME;
        }

        View aboutView = LayoutInflater.from(context).inflate(R.layout.dialog_about, null);
        TextView tvVersion = aboutView.findViewById(R.id.about_version);
        if (tvVersion != null) {
            tvVersion.setText(context.getString(R.string.about_version_format, versionName));
        }

        // One TextView for every highlight: six separate views were six
        // TalkBack stops for what is visually one block. Joined here so each
        // bullet stays an independently translated string resource.
        TextView tvFeatures = aboutView.findViewById(R.id.about_features);
        if (tvFeatures != null) {
            final CharSequence[] lines = {
                    context.getText(R.string.about_feature_offline),
                    context.getText(R.string.about_feature_languages),
                    context.getText(R.string.about_feature_indian),
                    context.getText(R.string.about_feature_dsp),
                    context.getText(R.string.about_feature_dict),
                    context.getText(R.string.about_feature_locks),
            };
            tvFeatures.setText(android.text.TextUtils.join("\n\n", lines));
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.about_title)
                .setView(aboutView)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(d -> markAlertTitleHeading(dialog));

        Button btnGithub = aboutView.findViewById(R.id.btn_about_github);
        if (btnGithub != null) {
btnGithub.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/animeshahilya/espeak-ng"));
                            // resolveActivity() returns null on API 30+ without a <queries>
                            // manifest entry, which made this button silently do nothing.
                            try {
                                context.startActivity(intent);
                            } catch (ActivityNotFoundException e) {
                                // no browser installed; nothing to open
                            }
                        }
                    });
        }

        dialog.show();
    }

    /**
     * Recent reading: re-hear what the engine said. Entries are stored
     * snippets (see {@link ReadingHistory}); tapping one speaks it through
     * the preview engine with the current settings.
     */
    static void showRecentReadingDialog(final Context context) {
        final SharedPreferences prefs = getPrefs();
        final List<ReadingHistory.Entry> items = ReadingHistory.get(prefs);
        if (items.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.setting_recent_reading)
                    .setMessage(R.string.history_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // setItems rows announce their title only; the snippet itself
        // identifies each entry, so voice/time stay out of the tree.
        final CharSequence[] titles = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            titles[i] = items.get(i).text;
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_recent_reading)
                .setItems(titles, (d, which) -> {
                    if (which >= 0 && which < items.size()) {
                        speakPreview(context, items.get(which).text, "history_rehear");
                    }
                })
                .setNeutralButton(R.string.history_clear, (d, which) -> {
                    ReadingHistory.clear(prefs);
                    Toast.makeText(context, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dict_back, null)
                .create();
        dialog.setOnShowListener(d -> markAlertTitleHeading(dialog));
        dialog.show();
    }

    /** Keys of the action rows in res/xml/preferences.xml (no stored value). */
    private static final String KEY_TEST_VOICE = "action_test_voice";

    /**
     * Inflates res/xml/preferences.xml and fills in what needs the engine
     * (voice parameters, the language list). Runs on the main thread once
     * createPreferences() has loaded the engine.
     */
    private static PreferenceScreen buildPreferences(final Context context,
                                                     final PrefsEspeakFragment fragment,
                                                     PreferenceManager pm,
                                                     SpeechSynthesis engine, List<Voice> voices,
                                                     boolean isWatch) {
        final SharedPreferences prefs = getPrefs();
        final VoiceSettings settings = new VoiceSettings(prefs, engine);
        // These lists show their stored value. Seed it from the legacy
        // booleans VoiceSettings still reads, or attaching the list would
        // persist its default over the mode actually in use.
        final SharedPreferences.Editor seed = prefs.edit();
        // Merged settings: seeded from the old toggles they replace. Phonetic
        // letters first, since it reads a stored "phonetic" reading mode.
        if (!prefs.contains(VoiceSettings.PREF_PHONETIC_LETTERS)) {
            seed.putString(VoiceSettings.PREF_PHONETIC_LETTERS, settings.getPhoneticLetters());
        }
        if (!prefs.contains(VoiceSettings.PREF_RATE_BOOST_LEVEL)) {
            seed.putString(VoiceSettings.PREF_RATE_BOOST_LEVEL, settings.getRateBoostLevel());
        }
        if (!prefs.contains(VoiceSettings.PREF_AUDIO_OPTIMIZER_LEVEL)) {
            seed.putString(VoiceSettings.PREF_AUDIO_OPTIMIZER_LEVEL, settings.getAudioOptimizerLevel());
        }
        if (VoiceSettings.READING_PHONETIC.equals(prefs.getString(VoiceSettings.PREF_READING_MODE, null))
                || !prefs.contains(VoiceSettings.PREF_READING_MODE)) {
            seed.putString(VoiceSettings.PREF_READING_MODE, settings.getReadingMode());
        }
        if (!prefs.contains(VoiceSettings.PREF_INDIAN_NUMBERING_VOICES)) {
            seed.putString(VoiceSettings.PREF_INDIAN_NUMBERING_VOICES, settings.getIndianNumberingVoices());
        }
        if (!prefs.contains(VoiceSettings.PREF_DIGIT_GROUPING)) {
            seed.putString(VoiceSettings.PREF_DIGIT_GROUPING, settings.getDigitGroupingMode());
        }
        seed.commit();

        final PreferenceScreen screen = pm.inflateFromResource(context, R.xml.preferences, null);

        if (isWatch) {
            for (String key : new String[] {
                    LanguageSettings.PREF_SUPPORTED_LANGUAGES, KEY_TEST_VOICE,
                    VoiceSettings.PREF_RATE_BOOST_LEVEL,
                    VoiceSettings.PREF_USER_DICTIONARY, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                    VoiceSettings.PREF_PHONETIC_LETTERS, VoiceSettings.PREF_SPOKEN_DIACRITICS,
                    VoiceSettings.PREF_EMOJI_PROCESSING, VoiceSettings.PREF_SIMPLIFY_URLS,
                    "action_favorite_voices", VoiceSettings.PREF_SLEEP_TIMER,
                    "action_save_profile", "action_load_profile",
                    VoiceSettings.PREF_READING_HISTORY, "action_recent_reading",
                    "category_presets", "category_data"}) {
                screen.removePreferenceRecursively(key);
            }
        } else {
            configureSupportedLanguages(context, screen.findPreference(LanguageSettings.PREF_SUPPORTED_LANGUAGES), voices);

            // Generated from VoiceSettings' own limits, so the list cannot
            // drift from what getRateBoostMultiplier() allows.
            final int min = VoiceSettings.RATE_BOOST_MULTIPLIER_MIN;
            final int max = VoiceSettings.RATE_BOOST_MULTIPLIER_MAX;
            final CharSequence[] boostEntries = new CharSequence[max - min + 2];
            final CharSequence[] boostValues = new CharSequence[max - min + 2];
            boostEntries[0] = context.getString(R.string.setting_off);
            boostValues[0] = VoiceSettings.RATE_BOOST_OFF;
            for (int m = min; m <= max; m++) {
                boostEntries[m - min + 1] = m + "\u00d7";
                boostValues[m - min + 1] = Integer.toString(m);
            }
            setEntries(screen, VoiceSettings.PREF_RATE_BOOST_LEVEL, boostEntries, boostValues);

            final CharSequence[] digitEntries = new CharSequence[9];
            final CharSequence[] digitValues = new CharSequence[9];
            for (int i = 0; i < 9; i++) {
                final int n = i + 4;
                digitEntries[i] = context.getResources().getQuantityString(R.plurals.digits_count, n, n);
                digitValues[i] = String.valueOf(n);
            }
            setEntries(screen, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD, digitEntries, digitValues);

            onClick(screen, KEY_TEST_VOICE, () -> playTestVoice(context));
            onClick(screen, VoiceSettings.PREF_USER_DICTIONARY, () -> UserDictionaryScreen.show(context));
            onClick(screen, "action_recommended_defaults", () -> applyRecommendedDefaults(context));
            onClick(screen, "action_backup", () -> {
                final Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "espeak_backup.json");
                startForResult(context, i, REQUEST_CODE_EXPORT_BACKUP);
            });
            onClick(screen, "action_restore", () -> {
                final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startForResult(context, i, REQUEST_CODE_IMPORT_BACKUP);
            });
            onClick(screen, "action_export_log", () -> exportLog(context));
            final ImportVoicePreference importVoice = screen.findPreference("action_import_voice");
            importVoice.setOnPreferenceChangeListener(mOnPreferenceChanged);
            importVoice.setDescription(R.string.import_voice_description);
        }
        onClick(screen, "action_about", () -> showAboutDialog(context));

        // Listener first: these two report their summary through it.
        final VoiceVariantPreference variant = screen.findPreference(VoiceSettings.PREF_VARIANT);
        variant.setOnPreferenceChangeListener(mOnPreferenceChanged);
        variant.setVoiceVariant(settings.getVoiceVariant());
        final SpeakPunctuationPreference punctuation = screen.findPreference(VoiceSettings.PREF_PUNCTUATION_LEVEL);
        punctuation.setOnPreferenceChangeListener(mOnPreferenceChanged);
        punctuation.setVoiceSettings(settings);

        configureSeekBar(context, screen, engine.Rate, VoiceSettings.PREF_RATE, R.string.setting_default_rate)
                .setRateBoostToggleVisible(isWatch);
        configureSeekBar(context, screen, engine.Pitch, VoiceSettings.PREF_PITCH, R.string.setting_default_pitch);
        configureSeekBar(context, screen, engine.PitchRange, VoiceSettings.PREF_PITCH_RANGE, R.string.espeak_pitch_range);
        configureSeekBar(context, screen, engine.Volume, VoiceSettings.PREF_VOLUME, R.string.espeak_volume);
        configureSeekBar(context, screen, engine.WordGap, VoiceSettings.PREF_WORD_GAP, R.string.setting_wordgap);
        configureSeekBar(context, screen, engine.PauseScale, VoiceSettings.PREF_PAUSE_SCALE, R.string.setting_pause_scale);

        // Rows that only matter under another setting are hidden, not
        // greyed out, while that setting is off: a disabled row is still a
        // TalkBack stop that reads "disabled" and does nothing.
        showWhile(screen, VoiceSettings.PREF_INTONATION_STYLE, VoiceSettings.PREF_INTONATION_GROUP,
                value -> VoiceSettings.INTONATION_CUSTOM.equals(value),
                prefs.getString(VoiceSettings.PREF_INTONATION_STYLE, VoiceSettings.INTONATION_NATURAL),
                fragment, context.getString(R.string.setting_intonation_group_shown));

        // Human-phrase summaries where the entry text alone lacks context.
        setThresholdSummary(screen, context, prefs);
        setBoostSummary(screen, context, prefs);

        if (isWatch) {
            // The removals above silently drop phone-only rows; say so once,
            // or watch users hunt for settings that were never there.
            final Preference note = new Preference(context);
            note.setKey("note_wear_limited");
            note.setTitle(R.string.note_wear_limited_title);
            note.setSummary(R.string.note_wear_limited_summary);
            note.setSelectable(false);
            screen.addPreference(note);
        } else {
            configureSleepTimer(context, screen, prefs);
            onClick(screen, "action_favorite_voices", () ->
                    FavoriteVoicesDialogFragment.show(context, voices));
            onClick(screen, "action_save_profile", () -> {
                final Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "espeak_voice_profile.json");
                startForResult(context, i, REQUEST_CODE_EXPORT_PROFILE);
            });
            onClick(screen, "action_load_profile", () -> {
                final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                startForResult(context, i, REQUEST_CODE_IMPORT_PROFILE);
            });
            onClick(screen, "action_recent_reading", () ->
                    showRecentReadingDialog(context));
            refreshFavoritesSummary(screen, prefs);
        }
        return screen;
    }

    /**
     * "Group in threes from N digits": the entry text ("7 digits") names the
     * threshold but not what it thresholds.
     */
    private static void setThresholdSummary(PreferenceScreen screen, Context context,
                                            SharedPreferences prefs) {
        final ListPreference pref = screen.findPreference(VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD);
        if (pref == null) {
            return; // dropped on Wear
        }
        final VoiceSettings settings = new VoiceSettings(prefs, null);
        pref.setSummary(context.getString(R.string.summary_digit_threshold,
                settings.getDigitGroupThreshold()));
        pref.setOnPreferenceChangeListener((p, value) -> {
            try {
                int v = Integer.parseInt(String.valueOf(value));
                if (v < 4) v = 4;
                if (v > 12) v = 12;
                p.setSummary(context.getString(R.string.summary_digit_threshold, v));
            } catch (NumberFormatException ignored) {
            }
            return true;
        });
    }

    /**
     * "3× the normal maximum speed": the entry text ("3×") names the
     * multiplier but not what it multiplies.
     */
    private static void setBoostSummary(PreferenceScreen screen, Context context,
                                        SharedPreferences prefs) {
        final ListPreference pref = screen.findPreference(VoiceSettings.PREF_RATE_BOOST_LEVEL);
        if (pref == null) {
            return; // dropped on Wear
        }
        updateBoostSummary(context, pref, prefs.getString(
                VoiceSettings.PREF_RATE_BOOST_LEVEL, VoiceSettings.RATE_BOOST_OFF));
        pref.setOnPreferenceChangeListener((p, value) -> {
            updateBoostSummary(context, (ListPreference) p, String.valueOf(value));
            return true;
        });
    }

    private static void updateBoostSummary(Context context, ListPreference pref, String value) {
        if (value == null || VoiceSettings.RATE_BOOST_OFF.equals(value)) {
            pref.setSummary(context.getString(R.string.setting_off));
        } else {
            pref.setSummary(context.getString(R.string.summary_rate_boost_on, value));
        }
    }

    /**
     * Sleep timer choices, generated like the rate-boost list so the UI
     * cannot drift from {@link VoiceSettings#SLEEP_MAX_MINUTES}. Arming
     * applies the mute a few seconds later (not instantly): the "pausing"
     * toast must itself be spoken first, and it travels through the very
     * engine about to go silent. Clearing the timer unmutes at once.
     */
    private static void configureSleepTimer(final Context context, PreferenceScreen screen,
                                            final SharedPreferences prefs) {
        final ListPreference pref = screen.findPreference(VoiceSettings.PREF_SLEEP_TIMER);
        if (pref == null) {
            return; // dropped on Wear
        }
        final int[] options = {15, 30, 45, 60};
        final CharSequence[] entries = new CharSequence[options.length + 1];
        final CharSequence[] values = new CharSequence[options.length + 1];
        entries[0] = context.getString(R.string.setting_off);
        values[0] = VoiceSettings.SLEEP_OFF;
        for (int i = 0; i < options.length; i++) {
            entries[i + 1] = context.getResources().getQuantityString(
                    R.plurals.sleep_minutes, options[i], options[i]);
            values[i + 1] = Integer.toString(options[i]);
        }
        pref.setEntries(entries);
        pref.setEntryValues(values);
        updateSleepSummary(context, pref, prefs);
        pref.setOnPreferenceChangeListener((p, value) -> {
            final String v = String.valueOf(value);
            if (VoiceSettings.SLEEP_OFF.equals(v)) {
                VoiceSettings.clearSleepMute(prefs);
                Toast.makeText(context, R.string.sleep_timer_resumed, Toast.LENGTH_SHORT).show();
            } else {
                int minutes;
                try {
                    minutes = Math.min(VoiceSettings.SLEEP_MAX_MINUTES, Math.max(1,
                            Integer.parseInt(v)));
                } catch (NumberFormatException e) {
                    return false;
                }
                Toast.makeText(context, R.string.sleep_timer_armed, Toast.LENGTH_SHORT).show();
                final Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> VoiceSettings.armSleepMute(prefs, minutes), 3000);
            }
            updateSleepSummary(context, (ListPreference) p, prefs);
            // The persisted duration is written by the preference itself;
            // refresh the summary again once a delayed arm lands.
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> updateSleepSummary(context, (ListPreference) p, prefs), 3500);
            return true;
        });
    }

    private static void updateSleepSummary(Context context, ListPreference pref,
                                           SharedPreferences prefs) {
        if (VoiceSettings.isSleepMuted(prefs)) {
            final long until = prefs.getLong(VoiceSettings.PREF_SLEEP_MUTE_UNTIL, 0);
            final String time = android.text.format.DateFormat.getTimeFormat(context)
                    .format(new java.util.Date(until));
            pref.setSummary(context.getString(R.string.sleep_paused_until, time));
        } else {
            pref.setSummary(context.getString(R.string.setting_sleep_timer_summary));
        }
    }

    /**
     * One-time "what's new" after an update: the stored version trails the
     * package version on first launch of a new APK. Shown once the engine
     * tree exists (so it never blocks the loading path), skipped on failure
     * to keep the screen usable.
     */
    private static void maybeShowWhatsNew(final Context context) {
        try {
            final int current;
            try {
                current = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
            } catch (PackageManager.NameNotFoundException e) {
                return;
            }
            final SharedPreferences prefs = getPrefs();
            if (prefs.getInt(VoiceSettings.PREF_WHATS_NEW_SEEN, 0) >= current) {
                return;
            }
            if (isGone(context)) {
                return;
            }
            final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.whats_new_title)
                    .setMessage(R.string.whats_new_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOnDismissListener(d ->
                    prefs.edit().putInt(VoiceSettings.PREF_WHATS_NEW_SEEN, current).apply());
            dialog.show();
            markAlertTitleHeading(dialog);
        } catch (Throwable t) {
            Log.w(TAG, "What's-new skipped", t);
        }
    }

    /** Favorites summary: how many voices are pinned, for sighted scanners. */
    public static void refreshFavoritesSummary(PreferenceScreen screen, SharedPreferences prefs) {
        final Preference pref = screen.findPreference("action_favorite_voices");
        if (pref == null) {
            return;
        }
        final int count = LanguageSettings.getFavoriteVoices(prefs).size();
        if (count == 0) {
            pref.setSummary(pref.getContext().getString(R.string.favorites_summary_none));
        } else {
            pref.setSummary(pref.getContext().getResources().getQuantityString(
                    R.plurals.favorites_summary, count, count));
        }
    }

    /** Shows {@code childKey} only while {@code parentKey}'s value passes {@code shown}. */
    private static void showWhile(PreferenceScreen screen, String parentKey, String childKey,
                                  java.util.function.Predicate<Object> shown, Object current,
                                  PrefsEspeakFragment fragment, String revealAnnouncement) {
        final Preference parent = screen.findPreference(parentKey);
        final Preference child = screen.findPreference(childKey);
        if (parent == null || child == null) {
            return; // dropped on Wear
        }
        child.setVisible(shown.test(current));
        parent.setOnPreferenceChangeListener((p, value) -> {
            final boolean show = shown.test(value);
            child.setVisible(show);
            // The revealed row sits elsewhere on this screen; say so, or a
            // TalkBack user toggling the parent never learns it appeared.
            if (show && revealAnnouncement != null && fragment != null && fragment.isAdded()) {
                fragment.announce(revealAnnouncement);
            }
            return true;
        });
    }

    private static void onClick(PreferenceScreen screen, String key, Runnable action) {
        screen.findPreference(key).setOnPreferenceClickListener(p -> {
            action.run();
            return true;
        });
    }

    private static void setEntries(PreferenceScreen screen, String key,
                                   CharSequence[] entries, CharSequence[] values) {
        final ListPreference pref = screen.findPreference(key);
        pref.setEntries(entries);
        pref.setEntryValues(values);
    }

    private static void startForResult(Context context, Intent intent, int requestCode) {
        if (context instanceof Activity) {
            ((Activity) context).startActivityForResult(intent, requestCode);
        }
    }

    private static void applyRecommendedDefaults(Context context) {
        getPrefs().edit()
                .putString(VoiceSettings.PREF_VARIANT, VoiceSettings.DEFAULT_VARIANT)
                .putString(VoiceSettings.PREF_PITCH, Integer.toString(VoiceSettings.DEFAULT_PITCH))
                .putString(VoiceSettings.PREF_PITCH_RANGE, Integer.toString(VoiceSettings.DEFAULT_PITCH_RANGE))
                .putString(VoiceSettings.PREF_CAPITALS, Integer.toString(VoiceSettings.DEFAULT_CAPITALS))
                .apply();
        Toast.makeText(context, R.string.recommended_defaults_applied, Toast.LENGTH_SHORT).show();
        if (context instanceof Activity) {
            ((Activity) context).recreate();
        }
    }

    private static void exportLog(final Context context) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Toast.makeText(context, R.string.export_log_collecting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String log = LogExporter.collect(context);
            handler.post(() -> {
                if (isGone(context)) return;
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject));
                share.putExtra(Intent.EXTRA_TEXT, log);
                context.startActivity(Intent.createChooser(share,
                        context.getString(R.string.setting_export_log)));
            });
        }, "log-export").start();
    }

    /** A single voice parameter, edited in a dialog of its own. */
    private static SeekBarPreference configureSeekBar(Context context, PreferenceScreen screen,
                                                      SpeechSynthesis.Parameter parameter,
                                                      String key, int titleRes) {
        final SeekBarPreference pref = screen.findPreference(key);
        pref.addParameter(voiceParameter(context, parameter, key, titleRes));
        pref.setSummary(pref.buildSummary());
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static void configureSupportedLanguages(Context context, SupportedLanguagesPreference pref,
                                                    List<Voice> voices) {
        // Sorted by the label that is shown and spoken, so the list reads in
        // alphabetical order.
        final Map<Voice, String> labels = new HashMap<Voice, String>();
        for (Voice voice : voices) {
            labels.put(voice, getVoiceLabel(voice));
        }
        final List<Voice> sortedVoices = new ArrayList<Voice>(voices);
        // A Collator, not compareToIgnoreCase: plain char order puts accented
        // initials ("Čeština", "Íslenska") after Z.
        final java.text.Collator collator = java.text.Collator.getInstance();
        Collections.sort(sortedVoices, (lhs, rhs) -> collator.compare(labels.get(lhs), labels.get(rhs)));

        final CharSequence[] entries = new CharSequence[sortedVoices.size()];
        final CharSequence[] entryValues = new CharSequence[sortedVoices.size()];
        final Map<String, java.util.Locale> locales = new HashMap<String, java.util.Locale>();
        int index = 0;
        for (Voice voice : sortedVoices) {
            entries[index] = labels.get(voice);
            entryValues[index] = voice.toString();
            if (voice.locale != null) {
                locales.put(voice.toString(), voice.locale);
            }
            ++index;
        }
        pref.setEntries(entries);
        pref.setEntryValues(entryValues);
        // Touch-and-hold samples speak through these locales, not the
        // current settings voice (which would make every sample identical).
        pref.setLocales(locales);

        Set<String> selected = LanguageSettings.getSelectedLanguages(getPrefs());
        if (selected == null) {
            selected = new HashSet<String>();
            for (Voice voice : sortedVoices) {
                selected.add(voice.toString());
            }
        }
        pref.setValues(selected);
        pref.setSummary(getSupportedLanguagesSummary(context, selected, pref.getDistinctValueCount()));
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
    }

    /** Summary refresh for the custom dialog preferences and the language list. */
    private static final OnPreferenceChangeListener mOnPreferenceChanged = (preference, newValue) -> {
        if (newValue instanceof String) {
            preference.setSummary((String) newValue);
        } else if (newValue instanceof Set && preference instanceof SupportedLanguagesPreference) {
            @SuppressWarnings("unchecked")
            final Set<String> values = new HashSet<String>((Set<String>) newValue);
            final int total = ((SupportedLanguagesPreference) preference).getDistinctValueCount();
            preference.setSummary(getSupportedLanguagesSummary(preference.getContext(), values, total));
        }
        return true;
    };
}
