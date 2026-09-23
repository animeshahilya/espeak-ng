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
import android.app.AlertDialog;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

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
import java.util.Comparator;
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
            if (preference instanceof PreferenceScreen) {
                PreferenceScreen current = getPreferenceScreen();
                if (current != null) {
                    mScreenStack.push(current);
                }
                setPreferenceScreen((PreferenceScreen) preference);
                updateTitle();
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
            return true;
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
                }
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
            if (listView instanceof ViewGroup) {
                ((ViewGroup) listView).setClipToPadding(false);
            }
            final Context context = getActivity();
            final View.OnApplyWindowInsetsListener insetsListener = new View.OnApplyWindowInsetsListener() {
                // getSystemWindowInsetBottom() was deprecated in API 30; kept
                // for the pre-R branch, which has no WindowInsets.Type API.
                @Override
                @SuppressWarnings("deprecation")
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int bottomInset = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    } else {
                        bottomInset = insets.getSystemWindowInsetBottom();
                    }
                    if (listView != null && context != null) {
                        listView.setPadding(
                                listView.getPaddingLeft(),
                                listView.getPaddingTop(),
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
            int multiplier = VoiceSettings.RATE_BOOST_MULTIPLIER;
            try {
                multiplier = Integer.parseInt(prefs.getString(
                        VoiceSettings.PREF_RATE_BOOST_MULTIPLIER,
                        Integer.toString(VoiceSettings.RATE_BOOST_MULTIPLIER)));
            } catch (NumberFormatException e) {
                // Malformed value - fall back to the default multiplier
            }
            voiceParam.enableRateBoost(prefs.getBoolean(VoiceSettings.PREF_RATE_BOOST, false), multiplier);
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

    private static String getDisplayName(Voice voice) {
        final String displayName = voice.locale.getDisplayName();
        return (displayName == null || displayName.isEmpty()) ? voice.toString() : displayName;
    }

    /**
     * Localized voice label: the name in the system's own language first
     * (e.g. "हिन्दी" on a Hindi system), followed by the English name from
     * the voice data in parentheses. That is the Android-native answer to
     * upstream #2515's self-designation registry proposal - Locale already
     * localizes every language for free, with no registry to maintain.
     * Falls back to the plain English name when both agree (the common
     * English-system case, where output is byte-identical to before), and to
     * the raw voice name when neither is available. Keeping the English name
     * always present also keeps regional variants that share a localized
     * name ("English (India)" vs "English (Singapore)") distinguishable.
     */
    private static String getVoiceLabel(Voice voice) {
        LangInfo info = lookupLangInfo(voice);
        final String english = info != null ? info.displayName : voice.name;
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

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isGone(context) || !fragment.isAdded()) {
                                return;
                            }
                            fragment.setPreferenceScreen(buildPreferences(context,
                                    fragment.getPreferenceManager(), engine, voices, isWatch));
                        }
                    });
                } catch (Throwable t) {
                    // The engine probe (native lib load, phondata, JNI voice
                    // enumeration) used to run unguarded on this worker: any
                    // failure became an uncaught exception that killed the
                    // whole process with zero UI feedback.
                    Log.e(TAG, "Failed to build settings preferences", t);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isGone(context)) {
                                return;
                            }
                            Toast.makeText(context, R.string.settings_load_failed,
                                    Toast.LENGTH_LONG).show();
                        }
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
        speakPreview(context, context.getString(R.string.test_voice_sample), "sample_utterance");
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

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.about_title)
                .setView(aboutView)
                .setPositiveButton(android.R.string.ok, null)
                .create();

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

    /** Keys of the action rows in res/xml/preferences.xml (no stored value). */
    private static final String KEY_TEST_VOICE = "action_test_voice";

    /**
     * Inflates res/xml/preferences.xml and fills in what needs the engine
     * (voice parameters, the language list). Runs on the main thread once
     * createPreferences() has loaded the engine.
     */
    private static PreferenceScreen buildPreferences(final Context context, PreferenceManager pm,
                                                     SpeechSynthesis engine, List<Voice> voices,
                                                     boolean isWatch) {
        final SharedPreferences prefs = getPrefs();
        final VoiceSettings settings = new VoiceSettings(prefs, engine);
        // These lists show their stored value. Seed it from the legacy
        // booleans VoiceSettings still reads, or attaching the list would
        // persist its default over the mode actually in use.
        final SharedPreferences.Editor seed = prefs.edit();
        if (!prefs.contains(VoiceSettings.PREF_READING_MODE)) {
            seed.putString(VoiceSettings.PREF_READING_MODE, settings.getReadingMode());
        }
        if (!prefs.contains(VoiceSettings.PREF_DIGIT_GROUPING)) {
            seed.putString(VoiceSettings.PREF_DIGIT_GROUPING, settings.getDigitGroupingMode());
        }
        seed.commit();

        final PreferenceScreen screen = pm.inflateFromResource(context, R.xml.preferences, null);

        if (isWatch) {
            for (String key : new String[] {
                    LanguageSettings.PREF_SUPPORTED_LANGUAGES, KEY_TEST_VOICE,
                    VoiceSettings.PREF_RATE_BOOST_MULTIPLIER, VoiceSettings.PREF_RATE_BOOST,
                    VoiceSettings.PREF_USER_DICTIONARY, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                    VoiceSettings.PREF_NATO_SPELLING, VoiceSettings.PREF_SPOKEN_DIACRITICS,
                    VoiceSettings.PREF_EMOJI_PROCESSING, VoiceSettings.PREF_SIMPLIFY_URLS,
                    "category_presets", "category_data"}) {
                screen.removePreferenceRecursively(key);
            }
        } else {
            configureSupportedLanguages(context, screen.findPreference(LanguageSettings.PREF_SUPPORTED_LANGUAGES), voices);

            // Generated from VoiceSettings' own limits, so the list cannot
            // drift from what getRateBoostMultiplier() allows.
            final int min = VoiceSettings.RATE_BOOST_MULTIPLIER_MIN;
            final int max = VoiceSettings.RATE_BOOST_MULTIPLIER_MAX;
            final CharSequence[] boostEntries = new CharSequence[max - min + 1];
            final CharSequence[] boostValues = new CharSequence[max - min + 1];
            for (int m = min; m <= max; m++) {
                boostEntries[m - min] = m + "\u00d7";
                boostValues[m - min] = Integer.toString(m);
            }
            setEntries(screen, VoiceSettings.PREF_RATE_BOOST_MULTIPLIER, boostEntries, boostValues);

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

        // The raw intonation group only applies to the Custom style.
        final Preference intonationGroup = screen.findPreference(VoiceSettings.PREF_INTONATION_GROUP);
        intonationGroup.setEnabled(VoiceSettings.INTONATION_CUSTOM.equals(
                prefs.getString(VoiceSettings.PREF_INTONATION_STYLE, VoiceSettings.INTONATION_NATURAL)));
        screen.findPreference(VoiceSettings.PREF_INTONATION_STYLE).setOnPreferenceChangeListener((p, value) -> {
            intonationGroup.setEnabled(VoiceSettings.INTONATION_CUSTOM.equals(value));
            return true;
        });
        return screen;
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
        final List<Voice> sortedVoices = new ArrayList<Voice>(voices);
        Collections.sort(sortedVoices, new Comparator<Voice>() {
            @Override
            public int compare(Voice lhs, Voice rhs) {
                return getDisplayName(lhs).compareToIgnoreCase(getDisplayName(rhs));
            }
        });

        final CharSequence[] entries = new CharSequence[sortedVoices.size()];
        final CharSequence[] entryValues = new CharSequence[sortedVoices.size()];
        int index = 0;
        for (Voice voice : sortedVoices) {
            entries[index] = getVoiceLabel(voice);
            entryValues[index] = voice.toString();
            ++index;
        }
        pref.setEntries(entries);
        pref.setEntryValues(entryValues);

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
