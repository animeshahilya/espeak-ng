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
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.animeshahilya.espeakng.preference.ImportVoicePreference;
import com.animeshahilya.espeakng.preference.SeekBarPreference;
import com.animeshahilya.espeakng.preference.SpeakPunctuationPreference;
import com.animeshahilya.espeakng.preference.SupportedLanguagesPreference;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TtsSettingsActivity extends PreferenceActivity {

    private static Context storageContext;
    private static final String TAG = TtsSettingsActivity.class.getSimpleName();

    /** Single accessor for the device-protected default prefs (see EspeakApp). */
    private static SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(storageContext);
    }

    /**
     * Identifies the combined voice-parameters preference. Nothing is stored
     * under it -- the preference writes the individual VoiceSettings keys --
     * but a Preference without a key cannot save its instance state, so its
     * dialog would not survive a rotation.
     */
    private static final String PREF_VOICE_PARAMETERS = "espeak_voice_parameters";

    private static final java.util.HashMap<String, LangInfo> sLangInfo = new java.util.HashMap<String, LangInfo>();

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Migrate old eyes-free settings to the new settings:

        storageContext = EspeakApp.requireStorageContext(this);
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
                int rateValue = (Integer.parseInt(rate) / 100) * defaultValue;
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

        getFragmentManager().beginTransaction().replace(
                android.R.id.content,
                new PrefsEspeakFragment()).commit();
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
                        if (fileName.toLowerCase().endsWith(".zip")) {
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

    public static class PrefsEspeakFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // The fragment has its own PreferenceManager, separate from the
            // activity's. Everything on this screen persists through it, so it
            // must use the device-protected file that TtsService reads. A
            // Preference left on the default credential-encrypted storage has
            // no effect on speech, and its stray file is what #2536 copied
            // over the real settings on every screen reader restart.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getPreferenceManager().setStorageDeviceProtected();
            }
            addPreferencesFromResource(R.xml.preferences);
            createPreferences(getActivity(), getPreferenceScreen());
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

        if (VoiceSettings.PREF_RATE.equals(key)) {
            voiceParam.enableRateBoost(prefs.getBoolean(VoiceSettings.PREF_RATE_BOOST, false));
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

    /** All four voice parameters, edited together in one dialog. */
    private static Preference createVoiceParamsPreference(Context context,
                                                          SpeechSynthesis engine,
                                                          int titleRes) {
        final SeekBarPreference pref = newSeekBarPreference(context, PREF_VOICE_PARAMETERS,
                context.getString(titleRes));
        pref.addParameter(voiceParameter(context, engine.Rate, VoiceSettings.PREF_RATE, R.string.setting_default_rate));
        pref.addParameter(voiceParameter(context, engine.Pitch, VoiceSettings.PREF_PITCH, R.string.setting_default_pitch));
        pref.addParameter(voiceParameter(context, engine.PitchRange, VoiceSettings.PREF_PITCH_RANGE, R.string.espeak_pitch_range));
        pref.addParameter(voiceParameter(context, engine.Volume, VoiceSettings.PREF_VOLUME, R.string.espeak_volume));
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
     * A clean, human-readable name only - e.g. "English (India)", "Vietnamese (Central)".
     * Earlier this prefixed the voice's raw internal code ("en-in  2 - English (India)",
     * "vi-vn-x-central - Vietnamese (Central)"), which is meaningless to anyone but a developer
     * and doubly so for dialect variants using BCP-47 private-use subtags. The lang files'
     * own `name` field already carries the useful distinction (region/dialect in parentheses),
     * so showing it alone is both cleaner and sufficient.
     */
    private static String getVoiceLabel(Voice voice) {
        LangInfo info = lookupLangInfo(voice);
        return info != null ? info.displayName : voice.name;
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

    private static Preference createAudioOptimizerPreference(Context context) {
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
                context.getString(R.string.capitals_none),
                context.getString(R.string.capitals_sound),
                context.getString(R.string.capitals_say)
        });
        pref.setEntryValues(new CharSequence[] { "3", "0", "1", "2" });
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

    private static Preference createSmartCodesPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_smart_codes);
        pref.setSummary(R.string.setting_smart_codes_summary);
        pref.setKey(VoiceSettings.PREF_SMART_CODES);
        pref.setDefaultValue(true);
        pref.setPersistent(true);
        return pref;
    }

    private static Preference createBilingualSwitchingPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_bilingual_switching);
        pref.setSummary(R.string.setting_bilingual_switching_summary);
        pref.setKey(VoiceSettings.PREF_BILINGUAL_SWITCHING);
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
                entries, values, "8");
    }

    private static Preference createSecondaryVoicePreference(Context context, List<Voice> voices) {
        final List<Voice> sorted = new ArrayList<Voice>(voices);
        Collections.sort(sorted, new Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        CharSequence[] entries = new CharSequence[sorted.size()];
        CharSequence[] values = new CharSequence[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            entries[i] = getVoiceLabel(sorted.get(i));
            values[i] = sorted.get(i).name;
        }
        final SharedPreferences prefs = getPrefs();
        String current = prefs.getString(VoiceSettings.PREF_SECONDARY_VOICE,
                VoiceSettings.DEFAULT_SECONDARY_VOICE);
        final ListPreference pref = new ListPreference(context);
        pref.setTitle(R.string.setting_secondary_voice);
        pref.setDialogTitle(R.string.setting_secondary_voice);
        pref.setKey(VoiceSettings.PREF_SECONDARY_VOICE);
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setDefaultValue(VoiceSettings.DEFAULT_SECONDARY_VOICE);
        pref.setPersistent(true);
        int idx = pref.findIndexOfValue(current);
        if (idx >= 0) pref.setSummary(entries[idx]);
        else pref.setSummary(R.string.setting_secondary_voice_summary);
        pref.setOnPreferenceChangeListener(mOnPreferenceChanged);
        return pref;
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

    /** Adds a visible TextView label bound to an input for TalkBack (hint alone is not enough). */
    private static EditText labeledInput(Context context, LinearLayout layout,
                                         String labelText, String hintText, String initial,
                                         int inputType) {
        final TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextAppearance(context, android.R.style.TextAppearance_Small);
        layout.addView(label);
        final EditText et = new EditText(context);
        et.setHint(hintText);
        et.setContentDescription(labelText + ". " + hintText);
        if (initial != null && !initial.isEmpty()) et.setText(initial);
        if (inputType != 0) et.setInputType(inputType);
        et.setMinimumHeight(48);
        et.setId(View.generateViewId());
        label.setLabelFor(et.getId());
        layout.addView(et);
        return et;
    }

    private static void showUserDictionaryDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();

        // Filtered view (search + category), but edits map back to real indices.
        final List<Integer> viewToReal = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        String q = searchQuery != null ? searchQuery.trim().toLowerCase(java.util.Locale.ROOT) : "";
        for (int i = 0; i < rules.size(); i++) {
            UserDictionary r = rules.get(i);
            if (categoryFilter != null && !"all".equals(categoryFilter)
                    && !r.getCategory().equals(categoryFilter)) continue;
            if (!q.isEmpty() && !r.getPattern().toLowerCase(java.util.Locale.ROOT).contains(q)
                    && !r.getReplacement().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            viewToReal.add(i);
            labels.add((viewToReal.size()) + ". [" + UserDictionary.categoryLabel(r.getCategory()) + "] \""
                    + r.getPattern() + "\" \u2192 \"" + r.getReplacement() + "\""
                    + (r.isRegex() ? " [Regex]" : (r.isWholeWord() ? " [Word]" : ""))
                    + (r.getLanguage().isEmpty() ? "" : " [" + r.getLanguage() + "]"));
        }
        final String[] items = labels.toArray(new String[0]);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        final EditText etSearch = labeledInput(context, layout,
                context.getString(R.string.dict_search_label),
                context.getString(R.string.dict_search_hint),
                searchQuery, android.text.InputType.TYPE_CLASS_TEXT);

        final TextView filterLabel = new TextView(context);
        filterLabel.setText(R.string.dict_filter_label);
        filterLabel.setTextAppearance(context, android.R.style.TextAppearance_Small);
        layout.addView(filterLabel);
        final android.widget.Spinner spFilter = new android.widget.Spinner(context);
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
        spFilter.setContentDescription(context.getString(R.string.dict_filter_label));
        int sel = 0;
        for (int i = 0; i < filterValues.length; i++) {
            if (filterValues[i].equals(categoryFilter)) { sel = i; break; }
        }
        spFilter.setSelection(sel);
        spFilter.setMinimumHeight(48);
        spFilter.setId(View.generateViewId());
        filterLabel.setLabelFor(spFilter.getId());
        layout.addView(spFilter);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.setting_user_dictionary) + " (" + rules.size() + ")");
        builder.setView(layout);
        if (items.length == 0) {
            builder.setMessage(rules.isEmpty()
                    ? context.getString(R.string.dict_empty)
                    : context.getString(R.string.dict_no_match));
        } else {
            builder.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, final int which) {
                    showRuleActionsDialog(context, searchQuery, categoryFilter,
                            viewToReal.get(which), items[which]);
                }
            });
        }

        builder.setPositiveButton("Add rule", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddRuleDialog(context,
                        etSearch.getText().toString(),
                        filterValues[spFilter.getSelectedItemPosition()]);
            }
        });
        builder.setNeutralButton("Import / Export", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDictionaryImportExportDialog(context,
                        etSearch.getText().toString(),
                        filterValues[spFilter.getSelectedItemPosition()]);
            }
        });
        // Explicit filter action: TalkBack announces the result count, and
        // focus stays predictable (no live re-open loop stealing focus).
        builder.setNegativeButton(R.string.dict_apply_filter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String nq = etSearch.getText().toString();
                String nf = filterValues[spFilter.getSelectedItemPosition()];
                showUserDictionaryDialog(context, nq, nf);
                Toast.makeText(context,
                        context.getString(R.string.dict_filter_applied, labels.size()),
                        Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
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
                .setTitle(context.getString(R.string.dict_rule_title, r.getPattern()))
                .setMessage(label)
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
                .setTitle("Dictionary import / export")
                .setMessage("Import massive word lists (.dic tab-delimited, word=replacement lists, or JSON) without crashing — runs in the background. Export shares the full list.")
                .setPositiveButton("Import file", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (context instanceof Activity) {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            ((Activity) context).startActivityForResult(i, REQUEST_CODE_IMPORT_DICT);
                        }
                    }
                })
                .setNeutralButton("Export file", new DialogInterface.OnClickListener() {
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
                .setNegativeButton("Back", new DialogInterface.OnClickListener() {
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

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

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
        catLabel.setTextAppearance(context, android.R.style.TextAppearance_Small);
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
        spCategory.setMinimumHeight(48);
        spCategory.setId(View.generateViewId());
        catLabel.setLabelFor(spCategory.getId());
        String preCat = existing != null ? existing.getCategory() : categoryFilter;
        spCategory.setSelection(UserDictionary.CATEGORY_ROOT.equals(preCat) ? 1
                : UserDictionary.CATEGORY_ABBREV.equals(preCat) ? 2 : 0);
        layout.addView(spCategory);

        final CheckBox cbWholeWord = new CheckBox(context);
        cbWholeWord.setText(R.string.dict_whole_word);
        cbWholeWord.setContentDescription(context.getString(R.string.dict_whole_word_summary));
        cbWholeWord.setChecked(existing != null ? existing.isWholeWord() : true);
        cbWholeWord.setMinimumHeight(48);
        layout.addView(cbWholeWord);

        final CheckBox cbCaseSensitive = new CheckBox(context);
        cbCaseSensitive.setText(R.string.dict_case_sensitive);
        cbCaseSensitive.setChecked(existing != null && existing.isCaseSensitive());
        cbCaseSensitive.setMinimumHeight(48);
        layout.addView(cbCaseSensitive);

        final CheckBox cbRegex = new CheckBox(context);
        cbRegex.setText(R.string.dict_regex);
        cbRegex.setContentDescription(context.getString(R.string.dict_regex_summary));
        cbRegex.setChecked(existing != null && existing.isRegex());
        cbRegex.setMinimumHeight(48);
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

        new AlertDialog.Builder(context)
                .setTitle(existing != null ? R.string.dict_edit_title : R.string.dict_add_title)
                .setView(layout)
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

        // 1. Voice and language
        PreferenceCategory langCategory = new PreferenceCategory(context);
        langCategory.setTitle(R.string.category_voice_language);
        group.addPreference(langCategory);

        if (!isWatch) {
            langCategory.addPreference(createSupportedLanguagesPreference(context, voices));
            langCategory.addPreference(createImportVoicePreference(context));
        }
        langCategory.addPreference(createVoiceVariantPreference(context, settings, R.string.espeak_variant));
        if (!isWatch) {
            langCategory.addPreference(createBilingualSwitchingPreference(context));
            if (!voices.isEmpty()) {
                Preference secondary = createSecondaryVoicePreference(context, voices);
                langCategory.addPreference(secondary);
                // setDependency AFTER attach: the old framework throws
                // IllegalStateException when the key cannot be resolved yet.
                secondary.setDependency(VoiceSettings.PREF_BILINGUAL_SWITCHING);
            }
            langCategory.addPreference(createTestVoicePreference(context));
        }

        // 2. Voice parameters (OG interface: dedicated, accessible seekbars with live formatted summary)
        PreferenceCategory paramCategory = new PreferenceCategory(context);
        paramCategory.setTitle(R.string.category_voice_parameters);
        group.addPreference(paramCategory);

        // The rate dialog's embedded boost toggle is hidden wherever the
        // standalone Rate boost checkbox exists (phones): one control per
        // setting. Watches keep the embedded toggle as their only control.
        Preference ratePref = createSeekBarPreference(context, engine.Rate, VoiceSettings.PREF_RATE, R.string.setting_default_rate);
        if (!isWatch && ratePref instanceof SeekBarPreference) {
            ((SeekBarPreference) ratePref).setRateBoostToggleVisible(false);
        }
        paramCategory.addPreference(ratePref);
        if (!isWatch) {
            paramCategory.addPreference(createRateBoostPreference(context));
        }
        paramCategory.addPreference(createSeekBarPreference(context, engine.Pitch, VoiceSettings.PREF_PITCH, R.string.setting_default_pitch));
        paramCategory.addPreference(createSeekBarPreference(context, engine.PitchRange, VoiceSettings.PREF_PITCH_RANGE, R.string.espeak_pitch_range));
        paramCategory.addPreference(createSeekBarPreference(context, engine.Volume, VoiceSettings.PREF_VOLUME, R.string.espeak_volume));
        paramCategory.addPreference(createSeekBarPreference(context, engine.WordGap, VoiceSettings.PREF_WORD_GAP, R.string.setting_wordgap));
        paramCategory.addPreference(createAudioOptimizerPreference(context));
        Preference audioProfile = createAudioProfilePreference(context);
        paramCategory.addPreference(audioProfile);
        audioProfile.setDependency(VoiceSettings.PREF_AUDIO_OPTIMIZER);
        if (!isWatch) {
            paramCategory.addPreference(createRecommendedDefaultsPreference(context));
        }

        // 2b. Caller-proof consistency locks (force overrides).
        PreferenceCategory lockCategory = new PreferenceCategory(context);
        lockCategory.setTitle(R.string.category_locks);
        group.addPreference(lockCategory);
        lockCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_RATE,
                R.string.setting_force_rate, R.string.setting_force_rate_summary, false));
        lockCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_PITCH,
                R.string.setting_force_pitch, R.string.setting_force_pitch_summary, false));
        lockCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_FORCE_VOLUME,
                R.string.setting_force_volume, R.string.setting_force_volume_summary, false));

        // 3. Text & speech processing (programming symbols, Indian currency/numbers, smart codes)
        PreferenceCategory processCategory = new PreferenceCategory(context);
        processCategory.setTitle(R.string.category_speech_processing);
        group.addPreference(processCategory);

        processCategory.addPreference(createCapitalsPreference(context));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_EMPHASIZE_QUESTIONS,
                R.string.setting_emphasize_questions, R.string.setting_emphasize_questions_summary, false));
        processCategory.addPreference(createSpeakPunctuationPreference(context, settings, R.string.espeak_speak_punctuation));
        processCategory.addPreference(createProgrammingSymbolsPreference(context));
        processCategory.addPreference(createIndianNumberingPreference(context));
        processCategory.addPreference(createSmartCodesPreference(context));
        if (!isWatch) {
            Preference smartMin = createSmartMinPreference(context);
            processCategory.addPreference(smartMin);
            smartMin.setDependency(VoiceSettings.PREF_SMART_CODES);
            Preference smartMax = createSmartMaxPreference(context);
            processCategory.addPreference(smartMax);
            smartMax.setDependency(VoiceSettings.PREF_SMART_CODES);
        }
        // Single control for digit handling: the grouping list's "Single
        // digits" mode is the old digit-by-digit toggle, which was removed.
        processCategory.addPreference(createDigitGroupingPreference(context));
        if (!isWatch) {
            processCategory.addPreference(createDigitGroupThresholdPreference(context));
        }
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_TIME_DATE,
                R.string.setting_time_date, R.string.setting_time_date_summary, true));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_CURRENCY,
                R.string.setting_currency, R.string.setting_currency_summary, true));
        processCategory.addPreference(createReadingModePreference(context));
        processCategory.addPreference(createUnicodeNormalizationPreference(context));
        if (!isWatch) {
            processCategory.addPreference(createUserDictionaryPreference(context));
            processCategory.addPreference(createNatoSpellingPreference(context));
            processCategory.addPreference(createSpokenDiacriticsPreference(context));
            processCategory.addPreference(createEmojiProcessingPreference(context));
        }

        // 4. Backup, troubleshooting, about
        PreferenceCategory backupCategory = new PreferenceCategory(context);
        backupCategory.setTitle(R.string.category_backup);
        group.addPreference(backupCategory);
        if (!isWatch) {
            backupCategory.addPreference(createBackupPreference(context));
            backupCategory.addPreference(createRestorePreference(context));
            backupCategory.addPreference(createLogExportPreference(context));
        }

        // 5. About
        PreferenceCategory aboutCategory = new PreferenceCategory(context);
        aboutCategory.setTitle(R.string.category_about);
        group.addPreference(aboutCategory);
        aboutCategory.addPreference(createAboutPreference(context));
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
