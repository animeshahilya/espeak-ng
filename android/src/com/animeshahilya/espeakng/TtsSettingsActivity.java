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
import android.content.Context;
import android.content.DialogInterface;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.preference.AccessiblePreferenceCategory;
import com.animeshahilya.espeakng.preference.ImportVoicePreference;
import com.animeshahilya.espeakng.preference.SeekBarDialogFragment;
import com.animeshahilya.espeakng.preference.SeekBarPreference;
import com.animeshahilya.espeakng.preference.SpeakPunctuationDialogFragment;
import com.animeshahilya.espeakng.preference.SpeakPunctuationPreference;
import com.animeshahilya.espeakng.preference.SupportedLanguagesDialogFragment;
import com.animeshahilya.espeakng.preference.SupportedLanguagesPreference;
import com.animeshahilya.espeakng.preference.VoiceVariantDialogFragment;
import com.animeshahilya.espeakng.preference.VoiceVariantPreference;

import java.io.BufferedInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TtsSettingsActivity extends AppCompatActivity {

    private static Context storageContext;
    private static final String TAG = TtsSettingsActivity.class.getSimpleName();

    /** Single accessor for the device-protected default prefs (see EspeakApp). */
    private static SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(storageContext);
    }

    private static final java.util.HashMap<String, LangInfo> sLangInfo = new java.util.HashMap<String, LangInfo>();

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Migrate old eyes-free settings to the new settings:

        storageContext = EspeakApp.requireStorageContext(getApplicationContext());
        CheckVoiceData.ensureVoiceData(storageContext);
        final SharedPreferences prefs = getPrefs();
        final SharedPreferences.Editor editor = prefs.edit();

        String pitch = prefs.getString(VoiceSettings.PREF_PITCH, null);
        if (pitch == null) {
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
            editor.putString(VoiceSettings.PREF_PITCH_RANGE, Integer.toString(VoiceSettings.DEFAULT_PITCH_RANGE));
        }

        String capitals = prefs.getString(VoiceSettings.PREF_CAPITALS, null);
        if (capitals == null) {
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
            SpeechSynthesis engine = new SpeechSynthesis(storageContext, null);
            int defaultValue = engine.Rate.getDefaultValue();
            int maxValue = engine.Rate.getMaxValue();

            rate = prefs.getString(VoiceSettings.PREF_DEFAULT_RATE, "100");
            try {
                int rateValue = (int) ((Integer.parseInt(rate) / 100.0f) * defaultValue);
                if (rateValue < defaultValue) rateValue = defaultValue;
                if (rateValue > maxValue) rateValue = maxValue;
                editor.putString(VoiceSettings.PREF_RATE, Integer.toString(rateValue));
            } catch (NumberFormatException e) {
                // Malformed legacy value - leave PREF_RATE unset so
                // VoiceSettings.getRate() falls back to the engine default.
            }
        }

        String variant = prefs.getString(VoiceSettings.PREF_VARIANT, null);
        if (variant == null) {
            String gender = prefs.getString(VoiceSettings.PREF_DEFAULT_GENDER, null);
            if ("2".equals(gender)) {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceVariant.FEMALE);
            } else if ("1".equals(gender)) {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceVariant.MALE);
            } else {
                editor.putString(VoiceSettings.PREF_VARIANT, VoiceSettings.DEFAULT_VARIANT);
            }
        }

        editor.commit();

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

        getSupportFragmentManager().beginTransaction().replace(
                android.R.id.content,
                new PrefsEspeakFragment()).commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBarAppearance(getWindow(), this);
    }

    // onBackPressed is deprecated in API 33+, but it remains the terminal
    // back-press entry point and is the only mechanism verified to fire here:
    // OnBackPressedDispatcher callbacks (lifecycle-owned and unconditional)
    // never reached the fragment on-device. This pops sub-screens first and
    // otherwise falls through to the normal finish behavior.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        android.util.Log.i("NAVPROBE", "activity onBackPressed");
        Fragment current = getSupportFragmentManager().findFragmentById(android.R.id.content);
        android.util.Log.i("NAVPROBE", "current fragment=" + current);
        if (current instanceof PrefsEspeakFragment
                && ((PrefsEspeakFragment) current).popToParent()) {
            android.util.Log.i("NAVPROBE", "popped to parent");
            return;
        }
        android.util.Log.i("NAVPROBE", "falling through to super");
        super.onBackPressed();
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                Toast.makeText(activity,
                        activity.getString(R.string.dict_import_done, count),
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
                Toast.makeText(activity,
                        done ? R.string.dict_export_done : R.string.import_voice_error,
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
                Toast.makeText(activity,
                        done ? R.string.backup_done : R.string.import_voice_error,
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
                final boolean done = count >= 0;
                Toast.makeText(activity,
                        done ? activity.getString(R.string.restore_done, count)
                                : activity.getString(R.string.import_voice_error),
                        Toast.LENGTH_LONG).show();
                if (done && activity instanceof Activity) {
                    ((Activity) activity).recreate();
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
                            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream))) {
                                ZipEntry entry;
                                byte[] buffer = new byte[8192];
                                final String canonicalTargetDir =
                                        targetDir.getCanonicalPath() + File.separator;
                                while ((entry = zis.getNextEntry()) != null) {
                                    String entryName = entry.getName();
                                    // Prevent zip path traversal (same guarantee as
                                    // FileUtils.extractZip, kept inline so one bad
                                    // entry is skipped instead of failing the
                                    // whole import): reject "..", absolute
                                    // paths, and anything resolving outside
                                    // the voice data dir.
                                    if (entryName.contains("..")) continue;
                                    File outFile = new File(targetDir, entryName);
                                    if (!outFile.getCanonicalPath().startsWith(canonicalTargetDir)) continue;
                                    if (entry.isDirectory()) {
                                        outFile.mkdirs();
                                    } else {
                                        File parent = outFile.getParentFile();
                                        if (parent != null && !parent.exists()) {
                                            parent.mkdirs();
                                        }
                                        try (OutputStream fos = new FileOutputStream(outFile)) {
                                            int len;
                                            while ((len = zis.read(buffer)) > 0) {
                                                fos.write(buffer, 0, len);
                                            }
                                        }
                                    }
                                    zis.closeEntry();
                                }
                                success = true;
                            }
                        } else {
                            File outFile = new File(targetDir, fileName);
                            // Same containment guarantee the zip path below
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getPreferenceManager().setStorageDeviceProtected();
            }
            setPreferencesFromResource(R.xml.preferences, rootKey);
            createPreferences(getActivity(), getPreferenceScreen());
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
        // re-roots itself instead: same manager, no tree rebuild. Back
        // returns via the activity's onBackPressed() below - the
        // OnBackPressedDispatcher callbacks (both lifecycle-owned and
        // unconditional) were verified on-device to never fire here, so the
        // terminal onBackPressed entry point is used instead. Rotation drops
        // back to the root, matching the old dialogs, which never survived
        // rotation either.
        private final Deque<PreferenceScreen> mScreenStack = new ArrayDeque<>();

        @Override
        public boolean onPreferenceTreeClick(Preference preference) {
            if (preference instanceof PreferenceScreen) {
                PreferenceScreen current = getPreferenceScreen();
                if (current != null) {
                    mScreenStack.push(current);
                }
                setPreferenceScreen((PreferenceScreen) preference);
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
            return true;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onDisplayPreferenceDialog(Preference preference) {
            if (preference instanceof VoiceVariantPreference) {
                VoiceVariantDialogFragment fragment =
                        VoiceVariantDialogFragment.newInstance(preference.getKey());
                fragment.setTargetFragment(this, 0);
                fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
                return;
            }
            if (preference instanceof SpeakPunctuationPreference) {
                SpeakPunctuationDialogFragment fragment =
                        SpeakPunctuationDialogFragment.newInstance(preference.getKey());
                fragment.setTargetFragment(this, 0);
                fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
                return;
            }
            if (preference instanceof SeekBarPreference) {
                SeekBarDialogFragment fragment =
                        SeekBarDialogFragment.newInstance(preference.getKey());
                fragment.setTargetFragment(this, 0);
                fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
                return;
            }
            if (preference instanceof SupportedLanguagesPreference) {
                SupportedLanguagesDialogFragment fragment =
                        SupportedLanguagesDialogFragment.newInstance(preference.getKey());
                fragment.setTargetFragment(this, 0);
                fragment.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
                return;
            }
            super.onDisplayPreferenceDialog(preference);
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

    private static Preference createImportVoicePreference(Context context) {
        final String title = context.getString(R.string.import_voice_title);

        final ImportVoicePreference pref = new ImportVoicePreference(context);
        pref.setTitle(title);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        pref.setDescription(R.string.import_voice_description);
        return pref;
    }

    private static Preference createVoiceVariantPreference(Context context, VoiceSettings settings, int titleRes) {
        final String title = context.getString(titleRes);

        final VoiceVariantPreference pref = new VoiceVariantPreference(context);
        pref.setTitle(title);
        pref.setDialogTitle(title);
        // Key required: AndroidX resolves dialog preferences by key, and the
        // custom persist path already writes this same key - no new storage.
        pref.setKey(VoiceSettings.PREF_VARIANT);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        pref.setPersistent(true);
        pref.setVoiceVariant(settings.getVoiceVariant());
        return pref;
    }

    private static Preference createSpeakPunctuationPreference(Context context, VoiceSettings settings, int titleRes) {
        final String title = context.getString(titleRes);

        final SpeakPunctuationPreference pref = new SpeakPunctuationPreference(context);
        pref.setTitle(title);
        pref.setDialogTitle(title);
        // Key required: AndroidX resolves dialog preferences by key, and the
        // custom persist path already writes this same key - no new storage.
        pref.setKey(VoiceSettings.PREF_PUNCTUATION_LEVEL);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        pref.setPersistent(true);
        pref.setVoiceSettings(settings);
        return pref;
    }

    private static Preference createUnicodeNormalizationPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_unicode_normalization);
        pref.setSummary(R.string.setting_unicode_normalization_summary);
        pref.setKey(VoiceSettings.PREF_UNICODE_NORMALIZATION);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
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
                    throw new IllegalStateException("Unsupported unit type for the parameter.");
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

    private static SeekBarPreference newSeekBarPreference(Context context, String key, String title) {
        final SeekBarPreference pref = new SeekBarPreference(context);
        pref.setTitle(title);
        pref.setDialogTitle(title);
        // Without a key, Preference.dispatchSaveInstanceState() skips the
        // preference and an open dialog does not survive a rotation.
        pref.setKey(key);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        pref.setPersistent(true);
        return pref;
    }

    /** A single voice parameter, edited in a dialog of its own. */
    private static Preference createSeekBarPreference(Context context,
                                                      SpeechSynthesis.Parameter parameter,
                                                      String key, int titleRes) {
        final SeekBarPreference pref = newSeekBarPreference(context, key, context.getString(titleRes));
        pref.addParameter(voiceParameter(context, parameter, key, titleRes));
        pref.setSummary(pref.buildSummary());
        return pref;
    }

    private static Preference createSupportedLanguagesPreference(Context context, List<Voice> voices) {
        final List<Voice> sortedVoices = new ArrayList<Voice>(voices);
        Collections.sort(sortedVoices, new Comparator<Voice>() {
            @Override
            public int compare(Voice lhs, Voice rhs) {
                return getDisplayName(lhs).compareToIgnoreCase(getDisplayName(rhs));
            }
        });

        final SupportedLanguagesPreference pref = new SupportedLanguagesPreference(context);
        pref.setTitle(R.string.espeak_supported_languages);
        pref.setDialogTitle(R.string.espeak_supported_languages);

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

        final SharedPreferences prefs = getPrefs();
        Set<String> selected = LanguageSettings.getSelectedLanguages(prefs);
        if (selected == null) {
            selected = new HashSet<String>();
            for (Voice voice : sortedVoices) {
                selected.add(voice.toString());
            }
        }
        pref.setValues(selected);
        pref.setSummary(getSupportedLanguagesSummary(context, selected, entries.length));
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static String getSupportedLanguagesSummary(Context context, Set<String> selected, int total) {
        int enabled = (selected == null || selected.isEmpty()) ? total : selected.size();
        if (enabled >= total) {
            return context.getString(R.string.espeak_supported_languages_all);
        }
        return context.getString(R.string.espeak_supported_languages_summary, enabled, total);
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
        if (!sLangInfo.isEmpty() || storageContext == null) return;
        File root = new File(CheckVoiceData.getDataPath(storageContext), "lang");
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
     * So it is gathered on a worker thread and the preferences are added when
     * it lands. The Preference objects themselves are still built on the main
     * thread, which is required: they bind to the hosting PreferenceGroup.
     */
    private static void createPreferences(final Context context, final PreferenceGroup group) {
        final Context storage = storageContext;
        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean isWatch = context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_WATCH);

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
                        if (isGone(context)) {
                            return;
                        }
                        addPreferences(context, group, engine, voices, isWatch);
                    }
                });
            }
        }, "espeak-settings-load").start();
    }

    /**
     * True once the hosting activity can no longer accept preference updates.
     * The load outlives a screen the user backed straight out of, and adding
     * preferences to a dead activity's group would leak it.
     */
    private static boolean isGone(Context context) {
        if (!(context instanceof Activity)) {
            return false;
        }
        final Activity activity = (Activity) context;
        return activity.isFinishing() || activity.isDestroyed();
    }


    private static Preference createEmojiProcessingPreference(Context context) {
        final ListPreference pref = new ListPreference(context);
        pref.setTitle(R.string.setting_emoji_processing);
        pref.setDialogTitle(R.string.setting_emoji_processing);
        pref.setKey(VoiceSettings.PREF_EMOJI_PROCESSING);
        pref.setEntries(new CharSequence[] {
                context.getString(R.string.emoji_announce),
                context.getString(R.string.emoji_ignore)
        });
        pref.setEntryValues(new CharSequence[] {
                VoiceSettings.EMOJI_ANNOUNCE,
                VoiceSettings.EMOJI_IGNORE
        });
        pref.setDefaultValue(VoiceSettings.EMOJI_ANNOUNCE);
        pref.setPersistent(true);

        final SharedPreferences prefs = getPrefs();
        String current = prefs.getString(VoiceSettings.PREF_EMOJI_PROCESSING, VoiceSettings.EMOJI_ANNOUNCE);
        pref.setSummary(VoiceSettings.EMOJI_IGNORE.equals(current) ?
                context.getString(R.string.emoji_ignore) : context.getString(R.string.emoji_announce));
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static Preference createRateBoostPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_rate_boost);
        pref.setSummary(R.string.setting_rate_boost_summary);
        pref.setKey(VoiceSettings.PREF_RATE_BOOST);
        pref.setDefaultValue(false);
        pref.setPersistent(true);
        return pref;
    }

    private static CheckBoxPreference createAudioOptimizerPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_audio_optimizer);
        pref.setSummary(R.string.setting_audio_optimizer_summary);
        pref.setKey(VoiceSettings.PREF_AUDIO_OPTIMIZER);
        pref.setDefaultValue(false);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createCapitalsPreference(Context context) {
        final ListPreference pref = new ListPreference(context);
        pref.setTitle(R.string.setting_capitals);
        pref.setDialogTitle(R.string.setting_capitals);
        pref.setKey(VoiceSettings.PREF_CAPITALS);
        pref.setEntries(new CharSequence[] {
                context.getString(R.string.capitals_pitch),
                context.getString(R.string.capitals_pitch_moderate),
                context.getString(R.string.capitals_pitch_strong),
                context.getString(R.string.capitals_none),
                context.getString(R.string.capitals_sound),
                context.getString(R.string.capitals_say)
        });
        // The engine treats any value 3+ as "raise pitch by this many Hz" (see
        // SpeechSynthesis.Capitals), not just a single fixed amount - these three
        // presets give real, audibly different strengths instead of only ever
        // being able to pick the minimum (3Hz, barely audible) raise.
        pref.setEntryValues(new CharSequence[] { "3", "20", "40", "0", "1", "2" });
        pref.setDefaultValue(Integer.toString(VoiceSettings.DEFAULT_CAPITALS));
        pref.setPersistent(true);

        final SharedPreferences prefs = getPrefs();
        String current = prefs.getString(VoiceSettings.PREF_CAPITALS, Integer.toString(VoiceSettings.DEFAULT_CAPITALS));
        int idx = pref.findIndexOfValue(current);
        if (idx >= 0 && idx < pref.getEntries().length) {
            pref.setSummary(pref.getEntries()[idx]);
        }
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static Preference createRecommendedDefaultsPreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.setting_recommended_defaults);
        pref.setSummary(R.string.setting_recommended_defaults_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final SharedPreferences prefs = getPrefs();
                prefs.edit()
                        .putString(VoiceSettings.PREF_VARIANT, VoiceSettings.DEFAULT_VARIANT)
                        .putString(VoiceSettings.PREF_PITCH, Integer.toString(VoiceSettings.DEFAULT_PITCH))
                        .putString(VoiceSettings.PREF_PITCH_RANGE, Integer.toString(VoiceSettings.DEFAULT_PITCH_RANGE))
                        .putString(VoiceSettings.PREF_CAPITALS, Integer.toString(VoiceSettings.DEFAULT_CAPITALS))
                        .commit();

                Toast.makeText(context, R.string.recommended_defaults_applied, Toast.LENGTH_SHORT).show();
                if (context instanceof Activity) {
                    ((Activity) context).recreate();
                }
                return true;
            }
        });
        return pref;
    }

    private static Preference createIndianNumberingPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_indian_numbering);
        pref.setSummary(R.string.setting_indian_numbering_summary);
        pref.setKey(VoiceSettings.PREF_INDIAN_NUMBERING);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createProgrammingSymbolsPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_programming_symbols);
        pref.setSummary(R.string.setting_programming_symbols_summary);
        pref.setKey(VoiceSettings.PREF_SPEAK_PROGRAMMING_SYMBOLS);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
    }

    private static CheckBoxPreference createSmartCodesPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_smart_codes);
        pref.setSummary(R.string.setting_smart_codes_summary);
        pref.setKey(VoiceSettings.PREF_SMART_CODES);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createNatoSpellingPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_nato_spelling);
        pref.setSummary(R.string.setting_nato_spelling_summary);
        pref.setKey(VoiceSettings.PREF_NATO_SPELLING);
        pref.setDefaultValue(false);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createSpokenDiacriticsPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_spoken_diacritics);
        pref.setSummary(R.string.setting_spoken_diacritics_summary);
        pref.setKey(VoiceSettings.PREF_SPOKEN_DIACRITICS);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createDigitGroupingPreference(Context context) {
        final ListPreference pref = new ListPreference(context);
        pref.setTitle(R.string.setting_digit_grouping);
        pref.setDialogTitle(R.string.setting_digit_grouping);
        pref.setKey(VoiceSettings.PREF_DIGIT_GROUPING);
        pref.setEntries(new CharSequence[] {
                context.getString(R.string.digit_group_off),
                context.getString(R.string.digit_group_single),
                context.getString(R.string.digit_group_double),
                context.getString(R.string.digit_group_triple)
        });
        pref.setEntryValues(new CharSequence[] {
                VoiceSettings.DIGIT_GROUP_OFF,
                VoiceSettings.DIGIT_GROUP_SINGLE,
                VoiceSettings.DIGIT_GROUP_DOUBLE,
                VoiceSettings.DIGIT_GROUP_TRIPLE
        });
        pref.setDefaultValue(VoiceSettings.DIGIT_GROUP_OFF);
        pref.setPersistent(true);
        // Same legacy-boolean migration as VoiceSettings.getDigitGroupingMode()
        // (which reads prefs only, so a null engine is fine here).
        String current = new VoiceSettings(getPrefs(), null).getDigitGroupingMode();
        int idx = pref.findIndexOfValue(current);
        if (idx >= 0) pref.setSummary(pref.getEntries()[idx]);
        else pref.setSummary(context.getString(R.string.setting_digit_grouping_summary));
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static Preference createCheckPref(Context context, String key, int titleRes, int summaryRes, boolean def) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(titleRes);
        pref.setSummary(summaryRes);
        pref.setKey(key);
        pref.setDefaultValue(def);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createListPref(Context context, String key,
                                             int titleRes, int summaryRes,
                                             CharSequence[] entries, CharSequence[] values,
                                             String defValue) {
        final ListPreference pref = new ListPreference(context);
        pref.setTitle(titleRes);
        pref.setDialogTitle(titleRes);
        pref.setKey(key);
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setDefaultValue(defValue);
        pref.setPersistent(true);
        final SharedPreferences prefs = getPrefs();
        String current = prefs.getString(key, defValue);
        int idx = pref.findIndexOfValue(current);
        if (idx >= 0 && idx < entries.length) pref.setSummary(entries[idx]);
        else pref.setSummary(summaryRes);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
    }

    private static Preference createReadingModePreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_READING_MODE,
                R.string.setting_reading_mode, R.string.setting_reading_mode_summary,
                new CharSequence[] {
                        context.getString(R.string.reading_normal),
                        context.getString(R.string.reading_spelling),
                        context.getString(R.string.reading_phonetic),
                        context.getString(R.string.reading_code)
                },
                new CharSequence[] {
                        VoiceSettings.READING_NORMAL,
                        VoiceSettings.READING_SPELLING,
                        VoiceSettings.READING_PHONETIC,
                        VoiceSettings.READING_CODE
                },
                new VoiceSettings(getPrefs(),
                        null).getReadingMode());
    }

    private static Preference createDigitGroupThresholdPreference(Context context) {
        CharSequence[] entries = new CharSequence[9];
        CharSequence[] values = new CharSequence[9];
        for (int i = 0; i < 9; i++) {
            int n = i + 4;
            entries[i] = n + " " + context.getString(R.string.digits_suffix);
            values[i] = String.valueOf(n);
        }
        return createListPref(context, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                R.string.setting_digit_threshold, R.string.setting_digit_threshold_summary,
                entries, values, "7");
    }

    private static Preference createAudioProfilePreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_AUDIO_PROFILE,
                R.string.setting_audio_profile, R.string.setting_audio_profile_summary,
                new CharSequence[] {
                        context.getString(R.string.audio_profile_gentle),
                        context.getString(R.string.audio_profile_balanced),
                        context.getString(R.string.audio_profile_full)
                },
                new CharSequence[] {
                        VoiceSettings.AUDIO_PROFILE_GENTLE,
                        VoiceSettings.AUDIO_PROFILE_BALANCED,
                        VoiceSettings.AUDIO_PROFILE_FULL
                },
                VoiceSettings.AUDIO_PROFILE_BALANCED);
    }

    private static Preference createIntonationStylePreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_INTONATION_STYLE,
                R.string.setting_intonation_style, R.string.setting_intonation_style_summary,
                context.getResources().getTextArray(R.array.intonation_style_entries),
                context.getResources().getTextArray(R.array.intonation_style_values),
                VoiceSettings.INTONATION_NATURAL);
    }

    /** Raw espeakINTONATION group (0-7); only meaningful when the style above is Custom. */
    private static Preference createIntonationGroupPreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_INTONATION_GROUP,
                R.string.setting_intonation_group, R.string.setting_intonation_group_summary,
                context.getResources().getTextArray(R.array.intonation_group_entries),
                context.getResources().getTextArray(R.array.intonation_group_values),
                "0");
    }

    /**
     * Only used while Rate boost is on; see createRateBoostPreference(). The
     * entries are generated from VoiceSettings' own MIN/MAX constants rather
     * than a separate hardcoded list, so this can't silently drift out of
     * sync with what getRateBoostMultiplier() actually allows.
     */
    private static Preference createRateBoostMultiplierPreference(Context context) {
        int min = VoiceSettings.RATE_BOOST_MULTIPLIER_MIN;
        int max = VoiceSettings.RATE_BOOST_MULTIPLIER_MAX;
        CharSequence[] entries = new CharSequence[max - min + 1];
        CharSequence[] values = new CharSequence[max - min + 1];
        for (int multiplier = min; multiplier <= max; multiplier++) {
            entries[multiplier - min] = multiplier + "×";
            values[multiplier - min] = Integer.toString(multiplier);
        }
        return createListPref(context, VoiceSettings.PREF_RATE_BOOST_MULTIPLIER,
                R.string.setting_rate_boost_multiplier, R.string.setting_rate_boost_multiplier_summary,
                entries, values,
                Integer.toString(VoiceSettings.RATE_BOOST_MULTIPLIER));
    }

    private static Preference createCapitalsScopePreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_CAPITALS_SCOPE,
                R.string.setting_capitals_scope, R.string.setting_capitals_scope_summary,
                context.getResources().getTextArray(R.array.capitals_scope_entries),
                context.getResources().getTextArray(R.array.capitals_scope_values),
                VoiceSettings.CAPITALS_SCOPE_CHAR);
    }

    private static Preference createRepeatedCharsPreference(Context context) {
        return createListPref(context, VoiceSettings.PREF_REPEATED_CHARS,
                R.string.setting_repeated_chars, R.string.setting_repeated_chars_summary,
                context.getResources().getTextArray(R.array.repeated_chars_entries),
                context.getResources().getTextArray(R.array.repeated_chars_values),
                VoiceSettings.REPEATED_CHARS_OFF);
    }

    private static Preference createSmartMinPreference(Context context) {
        CharSequence[] entries = new CharSequence[7];
        CharSequence[] values = new CharSequence[7];
        for (int i = 0; i < 7; i++) {
            int n = i + 2;
            entries[i] = n + " " + context.getString(R.string.digits_suffix);
            values[i] = String.valueOf(n);
        }
        return createListPref(context, VoiceSettings.PREF_SMART_MIN_LEN,
                R.string.setting_smart_min, R.string.setting_smart_min_summary,
                entries, values, "4");
    }

    private static Preference createSmartMaxPreference(Context context) {
        CharSequence[] entries = new CharSequence[9];
        CharSequence[] values = new CharSequence[9];
        for (int i = 0; i < 9; i++) {
            int n = i + 4;
            entries[i] = n + " " + context.getString(R.string.digits_suffix);
            values[i] = String.valueOf(n);
        }
        return createListPref(context, VoiceSettings.PREF_SMART_MAX_LEN,
                R.string.setting_smart_max, R.string.setting_smart_max_summary,
                entries, values, "10");
    }

    private static Preference createBackupPreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.setting_backup);
        pref.setSummary(R.string.setting_backup_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (context instanceof Activity) {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, "espeak_backup.json");
                    ((Activity) context).startActivityForResult(i, REQUEST_CODE_EXPORT_BACKUP);
                }
                return true;
            }
        });
        return pref;
    }

    private static Preference createRestorePreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.setting_restore);
        pref.setSummary(R.string.setting_restore_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (context instanceof Activity) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    ((Activity) context).startActivityForResult(i, REQUEST_CODE_IMPORT_BACKUP);
                }
                return true;
            }
        });
        return pref;
    }

    private static Preference createLogExportPreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.setting_export_log);
        pref.setSummary(R.string.setting_export_log_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final Handler handler = new Handler(Looper.getMainLooper());
                Toast.makeText(context, R.string.export_log_collecting, Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String log = LogExporter.collect(context);
                        handler.post(new Runnable() {
                            @Override public void run() {
                                Intent share = new Intent(Intent.ACTION_SEND);
                                share.setType("text/plain");
                                share.putExtra(Intent.EXTRA_SUBJECT, "eSpeak NG activity log");
                                share.putExtra(Intent.EXTRA_TEXT, log);
                                context.startActivity(Intent.createChooser(share,
                                        context.getString(R.string.setting_export_log)));
                            }
                        });
                    }
                }, "log-export").start();
                return true;
            }
        });
        return pref;
    }

    private static Preference createUserDictionaryPreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.setting_user_dictionary);
        pref.setSummary(R.string.setting_user_dictionary_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showUserDictionaryDialog(context);
                return true;
            }
        });
        return pref;
    }

    private static void showUserDictionaryDialog(final Context context) {
        showUserDictionaryDialog(context, "", "all");
    }

    /** Adds a visible TextView label bound to an input for TalkBack. */
    private static EditText labeledInput(Context context, LinearLayout layout,
                                         String labelText, String hintText, String initial,
                                         int inputType) {
        float density = context.getResources().getDisplayMetrics().density;
        int minTouch = (int) (48 * density + 0.5f);

        final TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextAppearance(android.R.style.TextAppearance_Small);
        layout.addView(label);
        final EditText et = new EditText(context);
        et.setHint(hintText);
        // Never set contentDescription on EditText: TalkBack needs to read user-typed text!
        if (initial != null && !initial.isEmpty()) et.setText(initial);
        if (inputType != 0) et.setInputType(inputType);
        et.setMinimumHeight(minTouch);
        et.setId(View.generateViewId());
        label.setLabelFor(et.getId());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density + 0.5f);
        et.setLayoutParams(lp);
        layout.addView(et);
        return et;
    }

    private static void showUserDictionaryDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();

        final View dialogView = View.inflate(context, R.layout.user_dictionary_dialog, null);
        final EditText etSearch = dialogView.findViewById(R.id.dict_search);
        final android.widget.Spinner spFilter = dialogView.findViewById(R.id.dict_filter_spinner);
        final ListView lvRules = dialogView.findViewById(R.id.dict_rules_list);
        final TextView tvEmpty = dialogView.findViewById(R.id.dict_empty_view);

        final String[] filterNames = new String[] {
                context.getString(R.string.dict_filter_all),
                context.getString(R.string.dict_filter_main),
                context.getString(R.string.dict_filter_root),
                context.getString(R.string.dict_filter_abbrev) };
        final String[] filterValues = new String[] {
                "all", UserDictionary.CATEGORY_MAIN,
                UserDictionary.CATEGORY_ROOT, UserDictionary.CATEGORY_ABBREV };

        android.widget.ArrayAdapter<String> filterAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, filterNames);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(filterAdapter);

        int sel = 0;
        for (int i = 0; i < filterValues.length; i++) {
            if (filterValues[i].equals(categoryFilter)) { sel = i; break; }
        }
        spFilter.setSelection(sel);

        final List<Integer> viewToReal = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final android.widget.ArrayAdapter<String> listAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, labels);
        lvRules.setAdapter(listAdapter);

        final Runnable updateList = new Runnable() {
            @Override
            public void run() {
                String q = etSearch.getText() != null ? etSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT) : "";
                int filterPos = spFilter.getSelectedItemPosition();
                String filter = (filterPos >= 0 && filterPos < filterValues.length) ? filterValues[filterPos] : "all";

                viewToReal.clear();
                labels.clear();
                for (int i = 0; i < rules.size(); i++) {
                    UserDictionary r = rules.get(i);
                    if (!"all".equals(filter) && !r.getCategory().equals(filter)) continue;
                    if (!q.isEmpty() && !r.getPattern().toLowerCase(java.util.Locale.ROOT).contains(q)
                            && !r.getReplacement().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                    viewToReal.add(i);
                    labels.add((viewToReal.size()) + ". [" + UserDictionary.categoryLabel(r.getCategory()) + "] \""
                            + r.getPattern() + "\" \u2192 \"" + r.getReplacement() + "\""
                            + (r.isRegex() ? " [Regex]" : (r.isWholeWord() ? " [Word]" : ""))
                            + (r.getLanguage().isEmpty() ? "" : " [" + r.getLanguage() + "]"));
                }
                listAdapter.notifyDataSetChanged();
                if (labels.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText(rules.isEmpty()
                            ? context.getString(R.string.dict_empty)
                            : context.getString(R.string.dict_no_match));
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
            }
        };

        if (searchQuery != null && !searchQuery.isEmpty()) {
            etSearch.setText(searchQuery);
        }
        updateList.run();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateList.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateList.run();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.setting_user_dictionary) + " (" + rules.size() + ")")
                .setView(dialogView)
                .setPositiveButton(R.string.dict_add_rule, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int pos = spFilter.getSelectedItemPosition();
                        String f = (pos >= 0 && pos < filterValues.length) ? filterValues[pos] : "all";
                        showAddRuleDialog(context, etSearch.getText().toString(), f);
                    }
                })
                .setNeutralButton(R.string.dict_import_export, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int pos = spFilter.getSelectedItemPosition();
                        String f = (pos >= 0 && pos < filterValues.length) ? filterValues[pos] : "all";
                        showDictionaryImportExportDialog(context, etSearch.getText().toString(), f);
                    }
                })
                .setNegativeButton(R.string.dict_back, null)
                .create();

        lvRules.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < viewToReal.size()) {
                    dialog.dismiss();
                    int pos = spFilter.getSelectedItemPosition();
                    String f = (pos >= 0 && pos < filterValues.length) ? filterValues[pos] : "all";
                    showRuleActionsDialog(context, etSearch.getText().toString(), f,
                            viewToReal.get(position), labels.get(position));
                }
            }
        });

        dialog.show();
    }

    /** TalkBack-friendly rule actions as a list (Preview / Edit / Delete) + Cancel. */
    private static void showRuleActionsDialog(final Context context, final String searchQuery,
                                              final String categoryFilter, final int realIdx,
                                              final String label) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> current = mgr.getRules();
        if (realIdx < 0 || realIdx >= current.size()) {
            showUserDictionaryDialog(context, searchQuery, categoryFilter);
            return;
        }
        final UserDictionary r = current.get(realIdx);
        final String[] actions = new String[] {
                context.getString(R.string.dict_action_preview),
                context.getString(R.string.dict_action_edit),
                context.getString(R.string.dict_action_delete) };
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dict_rule_title, r.getPattern()) + "\n" + label)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            previewText(context, r.getReplacement().isEmpty()
                                    ? r.getPattern() : r.getReplacement());
                            showUserDictionaryDialog(context, searchQuery, categoryFilter);
                        } else if (which == 1) {
                            showEditRuleDialog(context, searchQuery, categoryFilter, realIdx);
                        } else {
                            mgr.removeRule(realIdx);
                            Toast.makeText(context, R.string.dict_rule_deleted,
                                    Toast.LENGTH_SHORT).show();
                            showUserDictionaryDialog(context, searchQuery, categoryFilter);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showUserDictionaryDialog(context, searchQuery, categoryFilter);
                    }
                })
                .show();
    }

    private static void showDictionaryImportExportDialog(final Context context, final String searchQuery, final String categoryFilter) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.dict_import_export_title)
                .setMessage(R.string.dict_import_export_message)
                .setPositiveButton(R.string.dict_import_file, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (context instanceof Activity) {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            ((Activity) context).startActivityForResult(i, REQUEST_CODE_IMPORT_DICT);
                        }
                    }
                })
                .setNeutralButton(R.string.dict_export_file, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (context instanceof Activity) {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("text/plain");
                            i.putExtra(Intent.EXTRA_TITLE, "espeak_dictionary.dic");
                            ((Activity) context).startActivityForResult(i, REQUEST_CODE_EXPORT_DICT);
                        }
                    }
                })
                .setNegativeButton(R.string.dict_back, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showUserDictionaryDialog(context, searchQuery, categoryFilter);
                    }
                })
                .show();
    }

    /**
     * Speaks {@code text} through the lazily-initialized preview engine.
     * Unifies the TTS init/speak shape previewText() and playTestVoice()
     * used to duplicate; only the text and utterance id differ.
     */
    private static void speakPreview(final Context context, final String text,
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

    private static void previewText(final Context context, final String text) {
        speakPreview(context, text, "dict_preview");
    }

    private static void showAddRuleDialog(final Context context) {
        showAddRuleDialog(context, "", "all");
    }

    private static void showAddRuleDialog(final Context context, final String searchQuery, final String categoryFilter) {
        showEditRuleDialog(context, searchQuery, categoryFilter, -1);
    }

    private static void showEditRuleDialog(final Context context, final String searchQuery,
                                           final String categoryFilter, final int editIndex) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final UserDictionary existing = (editIndex >= 0 && editIndex < mgr.getRules().size())
                ? mgr.getRules().get(editIndex) : null;

        float density = context.getResources().getDisplayMetrics().density;
        int minTouch = (int) (48 * density + 0.5f);
        int padH = (int) (20 * density + 0.5f);
        int padV = (int) (12 * density + 0.5f);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, padV, padH, padV);

        final EditText etPattern = labeledInput(context, layout,
                context.getString(R.string.dict_label_pattern),
                context.getString(R.string.dict_hint_pattern),
                existing != null ? existing.getPattern() : null,
                android.text.InputType.TYPE_CLASS_TEXT);
        final EditText etReplacement = labeledInput(context, layout,
                context.getString(R.string.dict_label_replacement),
                context.getString(R.string.dict_hint_replacement),
                existing != null ? existing.getReplacement() : null,
                android.text.InputType.TYPE_CLASS_TEXT);

        final TextView catLabel = new TextView(context);
        catLabel.setText(R.string.dict_label_category);
        catLabel.setTextAppearance(android.R.style.TextAppearance_Small);
        layout.addView(catLabel);
        final android.widget.Spinner spCategory = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> catAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item,
                new String[]{context.getString(R.string.dict_filter_main),
                        context.getString(R.string.dict_filter_root),
                        context.getString(R.string.dict_filter_abbrev)});
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdapter);
        spCategory.setContentDescription(context.getString(R.string.dict_label_category));
        spCategory.setMinimumHeight(minTouch);
        spCategory.setId(View.generateViewId());
        catLabel.setLabelFor(spCategory.getId());
        String preCat = existing != null ? existing.getCategory() : categoryFilter;
        spCategory.setSelection(UserDictionary.CATEGORY_ROOT.equals(preCat) ? 1
                : UserDictionary.CATEGORY_ABBREV.equals(preCat) ? 2 : 0);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.bottomMargin = (int) (8 * density + 0.5f);
        spCategory.setLayoutParams(spLp);
        layout.addView(spCategory);

        final CheckBox cbWholeWord = new CheckBox(context);
        cbWholeWord.setText(R.string.dict_whole_word);
        cbWholeWord.setContentDescription(context.getString(R.string.dict_whole_word) + ". "
                + context.getString(R.string.dict_whole_word_summary));
        cbWholeWord.setChecked(existing != null ? existing.isWholeWord() : true);
        cbWholeWord.setMinimumHeight(minTouch);
        layout.addView(cbWholeWord);

        final CheckBox cbCaseSensitive = new CheckBox(context);
        cbCaseSensitive.setText(R.string.dict_case_sensitive);
        cbCaseSensitive.setContentDescription(context.getString(R.string.dict_case_sensitive));
        cbCaseSensitive.setChecked(existing != null && existing.isCaseSensitive());
        cbCaseSensitive.setMinimumHeight(minTouch);
        layout.addView(cbCaseSensitive);

        final CheckBox cbRegex = new CheckBox(context);
        cbRegex.setText(R.string.dict_regex);
        cbRegex.setContentDescription(context.getString(R.string.dict_regex) + ". "
                + context.getString(R.string.dict_regex_summary));
        cbRegex.setChecked(existing != null && existing.isRegex());
        cbRegex.setMinimumHeight(minTouch);
        layout.addView(cbRegex);

        final EditText etLanguage = labeledInput(context, layout,
                context.getString(R.string.dict_label_language),
                context.getString(R.string.dict_hint_language),
                existing != null ? existing.getLanguage() : null,
                android.text.InputType.TYPE_CLASS_TEXT);

        final String[] chosenCategory = new String[]{
                UserDictionary.CATEGORY_ROOT.equals(preCat) ? UserDictionary.CATEGORY_ROOT
                        : UserDictionary.CATEGORY_ABBREV.equals(preCat) ? UserDictionary.CATEGORY_ABBREV
                        : UserDictionary.CATEGORY_MAIN};
        spCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                chosenCategory[0] = pos == 1 ? UserDictionary.CATEGORY_ROOT
                        : pos == 2 ? UserDictionary.CATEGORY_ABBREV : UserDictionary.CATEGORY_MAIN;
                if (pos == 1) cbWholeWord.setChecked(false);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);

        new AlertDialog.Builder(context)
                .setTitle(existing != null ? R.string.dict_edit_title : R.string.dict_add_title)
                .setView(scrollView)
                .setPositiveButton(R.string.dict_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pattern = etPattern.getText().toString().trim();
                        String replacement = etReplacement.getText().toString().trim();
                        String language = etLanguage.getText().toString().trim();
                        if (!pattern.isEmpty()) {
                            UserDictionary rule = new UserDictionary(
                                    pattern,
                                    replacement,
                                    cbCaseSensitive.isChecked(),
                                    cbRegex.isChecked(),
                                    cbWholeWord.isChecked(),
                                    language,
                                    chosenCategory[0]
                            );
                            if (existing != null) {
                                UserDictionaryManager.getInstance(context).setRule(editIndex, rule);
                                Toast.makeText(context, R.string.dict_rule_saved,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                UserDictionaryManager.getInstance(context).addRule(rule);
                            }
                            // Live audio preview: hear the new pronunciation immediately.
                            previewText(context, replacement.isEmpty() ? pattern : replacement);
                            showUserDictionaryDialog(context, searchQuery, categoryFilter);
                        }
                    }
                })
                .setNeutralButton(R.string.dict_preview, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String replacement = etReplacement.getText().toString().trim();
                        String pattern = etPattern.getText().toString().trim();
                        previewText(context, replacement.isEmpty() ? pattern : replacement);
                        if (existing != null) {
                            showEditRuleDialog(context, searchQuery, categoryFilter, editIndex);
                        } else {
                            showAddRuleDialog(context, searchQuery, categoryFilter);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showUserDictionaryDialog(context, searchQuery, categoryFilter);
                    }
                })
                .show();
    }

    private static Preference createTestVoicePreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.test_voice_title);
        pref.setSummary(R.string.test_voice_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                playTestVoice(context);
                return true;
            }
        });
        return pref;
    }

    private static void playTestVoice(final Context context) {
        speakPreview(context, context.getString(R.string.test_voice_sample), "sample_utterance");
    }

    private static Preference createAboutPreference(final Context context) {
        final Preference pref = new Preference(context);
        pref.setTitle(R.string.about_title);
        pref.setSummary(R.string.about_summary);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showAboutDialog(context);
                return true;
            }
        });
        return pref;
    }

    private static void showAboutDialog(final Context context) {
        String versionName;
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "";
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.about_title)
                .setMessage(context.getString(R.string.about_body, versionName))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void addPreferences(Context context, PreferenceGroup group,
                                       SpeechSynthesis engine, List<Voice> voices,
                                       boolean isWatch) {
        VoiceSettings settings = new VoiceSettings(getPrefs(), engine);
        PreferenceManager pm = group.getPreferenceManager();

        // 1. Number & Code Reading Sub-Screen
        PreferenceScreen numberScreen = pm.createPreferenceScreen(context);
        numberScreen.setKey("sub_number_reading");
        numberScreen.setTitle(R.string.screen_numbers_title);
        numberScreen.setSummary(R.string.screen_numbers_summary);

        PreferenceCategory secDigitsCat = new AccessiblePreferenceCategory(context);
        secDigitsCat.setTitle(R.string.category_security_digits);
        numberScreen.addPreference(secDigitsCat);

        CheckBoxPreference smartCodesPref = createSmartCodesPreference(context);
        secDigitsCat.addPreference(smartCodesPref);
        if (!isWatch) {
            final Preference smartMin = createSmartMinPreference(context);
            final Preference smartMax = createSmartMaxPreference(context);
            boolean smartEnabled = smartCodesPref.isChecked();
            smartMin.setEnabled(smartEnabled);
            smartMax.setEnabled(smartEnabled);
            smartCodesPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean enabled = (Boolean) newValue;
                    smartMin.setEnabled(enabled);
                    smartMax.setEnabled(enabled);
                    return true;
                }
            });
            secDigitsCat.addPreference(smartMin);
            secDigitsCat.addPreference(smartMax);
        }
        secDigitsCat.addPreference(createDigitGroupingPreference(context));
        if (!isWatch) {
            secDigitsCat.addPreference(createDigitGroupThresholdPreference(context));
        }

        PreferenceCategory formatsCat = new AccessiblePreferenceCategory(context);
        formatsCat.setTitle(R.string.category_number_formats);
        numberScreen.addPreference(formatsCat);

        formatsCat.addPreference(createCheckPref(context, VoiceSettings.PREF_ROMAN_NUMERALS,
                R.string.setting_roman_numerals, R.string.setting_roman_numerals_summary, true));
        formatsCat.addPreference(createIndianNumberingPreference(context));
        formatsCat.addPreference(createCheckPref(context, VoiceSettings.PREF_TIME_DATE,
                R.string.setting_time_date, R.string.setting_time_date_summary, true));
        formatsCat.addPreference(createCheckPref(context, VoiceSettings.PREF_CURRENCY,
                R.string.setting_currency, R.string.setting_currency_summary, true));

        // 2. Pronunciation & Text Processing Sub-Screen
        PreferenceScreen textProcessingScreen = pm.createPreferenceScreen(context);
        textProcessingScreen.setKey("sub_text_processing");
        textProcessingScreen.setTitle(R.string.screen_text_processing_title);
        textProcessingScreen.setSummary(R.string.screen_text_processing_summary);

        PreferenceCategory punctCat = new AccessiblePreferenceCategory(context);
        punctCat.setTitle(R.string.category_punctuation_symbols);
        textProcessingScreen.addPreference(punctCat);

        punctCat.addPreference(createSpeakPunctuationPreference(context, settings, R.string.espeak_speak_punctuation));
        punctCat.addPreference(createCapitalsPreference(context));
        punctCat.addPreference(createCapitalsScopePreference(context));
        punctCat.addPreference(createRepeatedCharsPreference(context));
        punctCat.addPreference(createProgrammingSymbolsPreference(context));
        punctCat.addPreference(createUnicodeNormalizationPreference(context));

        PreferenceCategory readingCat = new AccessiblePreferenceCategory(context);
        readingCat.setTitle(R.string.category_reading_modes);
        textProcessingScreen.addPreference(readingCat);

        readingCat.addPreference(createReadingModePreference(context));
        if (!isWatch) {
            readingCat.addPreference(createNatoSpellingPreference(context));
            readingCat.addPreference(createSpokenDiacriticsPreference(context));
            readingCat.addPreference(createEmojiProcessingPreference(context));
            readingCat.addPreference(createCheckPref(context, VoiceSettings.PREF_SIMPLIFY_URLS,
                    R.string.setting_simplify_urls, R.string.setting_simplify_urls_summary, false));
        }
        readingCat.addPreference(createCheckPref(context, VoiceSettings.PREF_EMPHASIZE_QUESTIONS,
                R.string.setting_emphasize_questions, R.string.setting_emphasize_questions_summary, false));

        // 3. Caller-Proof Locks Sub-Screen
        PreferenceScreen locksScreen = pm.createPreferenceScreen(context);
        locksScreen.setKey("sub_caller_locks");
        locksScreen.setTitle(R.string.screen_locks_title);
        locksScreen.setSummary(R.string.screen_locks_summary);

        PreferenceCategory lockCat = new AccessiblePreferenceCategory(context);
        lockCat.setTitle(R.string.category_locks);
        locksScreen.addPreference(lockCat);

        lockCat.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_RATE,
                R.string.setting_force_rate, R.string.setting_force_rate_summary, false));
        lockCat.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_PITCH,
                R.string.setting_force_pitch, R.string.setting_force_pitch_summary, false));
        lockCat.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_VOLUME,
                R.string.setting_force_volume, R.string.setting_force_volume_summary, false));

        // 4. Tools, Backup & About Sub-Screen
        PreferenceScreen toolsScreen = pm.createPreferenceScreen(context);
        toolsScreen.setKey("sub_tools_backup");
        toolsScreen.setTitle(R.string.screen_tools_title);
        toolsScreen.setSummary(R.string.screen_tools_summary);

        if (!isWatch) {
            PreferenceCategory presetCat = new AccessiblePreferenceCategory(context);
            presetCat.setTitle(R.string.category_presets);
            toolsScreen.addPreference(presetCat);
            presetCat.addPreference(createRecommendedDefaultsPreference(context));

            PreferenceCategory dataCat = new AccessiblePreferenceCategory(context);
            dataCat.setTitle(R.string.category_data_management);
            toolsScreen.addPreference(dataCat);
            dataCat.addPreference(createBackupPreference(context));
            dataCat.addPreference(createRestorePreference(context));
            dataCat.addPreference(createLogExportPreference(context));
            dataCat.addPreference(createImportVoicePreference(context));
        }

        PreferenceCategory aboutCat = new AccessiblePreferenceCategory(context);
        aboutCat.setTitle(R.string.category_about);
        toolsScreen.addPreference(aboutCat);
        aboutCat.addPreference(createAboutPreference(context));

        // --- ROOT SCREEN PREFERENCES (Optimized for minimal scrolling) ---

        // 1. Voice and language
        PreferenceCategory langCategory = new AccessiblePreferenceCategory(context);
        langCategory.setTitle(R.string.category_voice_language);
        group.addPreference(langCategory);

        if (!isWatch) {
            langCategory.addPreference(createSupportedLanguagesPreference(context, voices));
        }
        langCategory.addPreference(createVoiceVariantPreference(context, settings, R.string.espeak_variant));
        if (!isWatch) {
            langCategory.addPreference(createTestVoicePreference(context));
        }

        // 2. Voice parameters
        PreferenceCategory paramCategory = new AccessiblePreferenceCategory(context);
        paramCategory.setTitle(R.string.category_voice_parameters);
        group.addPreference(paramCategory);

        Preference ratePref = createSeekBarPreference(context, engine.Rate, VoiceSettings.PREF_RATE, R.string.setting_default_rate);
        if (!isWatch && ratePref instanceof SeekBarPreference) {
            ((SeekBarPreference) ratePref).setRateBoostToggleVisible(false);
        }
        paramCategory.addPreference(ratePref);
        if (!isWatch) {
            final CheckBoxPreference rateBoostPref = (CheckBoxPreference) createRateBoostPreference(context);
            paramCategory.addPreference(rateBoostPref);
            final Preference rateBoostMultiplierPref = createRateBoostMultiplierPreference(context);
            rateBoostMultiplierPref.setEnabled(rateBoostPref.isChecked());
            rateBoostPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    rateBoostMultiplierPref.setEnabled((Boolean) newValue);
                    return true;
                }
            });
            paramCategory.addPreference(rateBoostMultiplierPref);
        }
        paramCategory.addPreference(createSeekBarPreference(context, engine.Pitch, VoiceSettings.PREF_PITCH, R.string.setting_default_pitch));
        paramCategory.addPreference(createSeekBarPreference(context, engine.PitchRange, VoiceSettings.PREF_PITCH_RANGE, R.string.espeak_pitch_range));
        final Preference intonationStylePref = createIntonationStylePreference(context);
        paramCategory.addPreference(intonationStylePref);
        final Preference intonationGroupPref = createIntonationGroupPreference(context);
        intonationGroupPref.setEnabled(VoiceSettings.INTONATION_CUSTOM.equals(
                getPrefs().getString(VoiceSettings.PREF_INTONATION_STYLE, VoiceSettings.INTONATION_NATURAL)));
        intonationStylePref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                // Keep the usual summary-updating behavior for this list preference.
                mOnPreferenceChanged.onPreferenceChange(preference, newValue);
                intonationGroupPref.setEnabled(VoiceSettings.INTONATION_CUSTOM.equals(newValue));
                return true;
            }
        });
        paramCategory.addPreference(intonationGroupPref);
        paramCategory.addPreference(createSeekBarPreference(context, engine.Volume, VoiceSettings.PREF_VOLUME, R.string.espeak_volume));
        paramCategory.addPreference(createSeekBarPreference(context, engine.WordGap, VoiceSettings.PREF_WORD_GAP, R.string.setting_wordgap));
        paramCategory.addPreference(createSeekBarPreference(context, engine.PauseScale, VoiceSettings.PREF_PAUSE_SCALE, R.string.setting_pause_scale));
        CheckBoxPreference audioOptPref = createAudioOptimizerPreference(context);
        paramCategory.addPreference(audioOptPref);
        final Preference audioProfile = createAudioProfilePreference(context);
        audioProfile.setEnabled(audioOptPref.isChecked());
        audioOptPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean enabled = (Boolean) newValue;
                audioProfile.setEnabled(enabled);
                return true;
            }
        });
        paramCategory.addPreference(audioProfile);

        // 3. Advanced & specialized settings
        PreferenceCategory advancedCategory = new AccessiblePreferenceCategory(context);
        advancedCategory.setTitle(R.string.category_advanced_settings);
        group.addPreference(advancedCategory);

        if (!isWatch) {
            advancedCategory.addPreference(createUserDictionaryPreference(context));
        }
        advancedCategory.addPreference(numberScreen);
        advancedCategory.addPreference(textProcessingScreen);
        advancedCategory.addPreference(locksScreen);
        advancedCategory.addPreference(toolsScreen);
    }

    private static final OnPreferenceChangeListener mOnPreferenceChanged =
            new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (newValue instanceof String) {
                        String summary = "";
                        if (preference instanceof ListPreference) {
                            final ListPreference listPreference = (ListPreference) preference;
                            final int index = listPreference.findIndexOfValue((String) newValue);
                            final CharSequence[] entries = listPreference.getEntries();

                            if (index >= 0 && index < entries.length) {
                                summary = entries[index].toString();
                            }
                        } else {
                            summary = (String)newValue;
                        }
                        preference.setSummary(summary);
                    } else if (newValue instanceof Set && preference instanceof MultiSelectListPreference) {
                        @SuppressWarnings("unchecked")
                        final Set<String> values = new HashSet<String>((Set<String>) newValue);
                        final int total = ((MultiSelectListPreference) preference).getEntries().length;
                        preference.setSummary(getSupportedLanguagesSummary(preference.getContext(), values, total));
                    }
                    return true;
                }
            };
}
