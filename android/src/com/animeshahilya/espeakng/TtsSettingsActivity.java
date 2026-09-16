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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        storageContext = EspeakApp.getStorageContext();
        if (!CheckVoiceData.hasBaseResources(storageContext)
                || CheckVoiceData.canUpgradeResources(storageContext)) {
            CheckVoiceData.extractVoiceData(storageContext);
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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

    private static void importDictionaryUri(final Activity activity, final Uri uri) {
        Toast.makeText(activity, R.string.dict_import_started, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                int added = 0;
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        added = UserDictionaryManager.getInstance(activity).importFromStream(is);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Dictionary import failed", e);
                }
                final int count = added;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(activity,
                                activity.getString(R.string.dict_import_done, count),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "dict-import").start();
    }

    private static void exportDictionaryUri(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        UserDictionaryManager.getInstance(activity).exportToStream(os);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Dictionary export failed", e);
                }
                final boolean done = ok;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(activity,
                                done ? R.string.dict_export_done : R.string.import_voice_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "dict-export").start();
    }

    private static void exportBackupUri(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        BackupRestoreHelper.exportToStream(activity, os);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Backup export failed", e);
                }
                final boolean done = ok;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(activity,
                                done ? R.string.backup_done : R.string.import_voice_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "backup-export").start();
    }

    private static void importBackupUri(final Activity activity, final Uri uri) {
        Toast.makeText(activity, R.string.restore_started, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                int rules = 0;
                boolean ok = false;
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        rules = BackupRestoreHelper.importFromStream(activity, is);
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Backup import failed", e);
                }
                final boolean done = ok;
                final int count = rules;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
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
        }, "backup-import").start();
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
        final Context storage = storageContext != null ? storageContext : activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
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
                                while ((entry = zis.getNextEntry()) != null) {
                                    String entryName = entry.getName();
                                    // Prevent zip path traversal
                                    if (entryName.contains("..")) continue;
                                    File outFile = new File(targetDir, entryName);
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

                final boolean finalSuccess = success;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalSuccess) {
                            synchronized (TtsSettingsActivity.class) {
                                sLangInfo.clear();
                            }
                            activity.sendBroadcast(new Intent(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED));
                            Toast.makeText(activity, R.string.import_voice_success, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, R.string.import_voice_error, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "voice-import-thread").start();
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

    private static Preference createSpeakDigitsPreference(Context context) {
        final CheckBoxPreference pref = new CheckBoxPreference(context);
        pref.setTitle(R.string.setting_speak_digits);
        pref.setSummary(R.string.setting_speak_digits_summary);
        pref.setKey(VoiceSettings.PREF_SPEAK_DIGITS);
        pref.setDefaultValue(false);
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

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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
        LangInfo info = sLangInfo.get(key1);
        if (info == null && key2 != null) {
            info = sLangInfo.get(key2);
        }
        return info;
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

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
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
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        String current = prefs.getString(VoiceSettings.PREF_DIGIT_GROUPING, null);
        if (current == null) {
            current = prefs.getBoolean(VoiceSettings.PREF_SPEAK_DIGITS, false)
                    ? VoiceSettings.DIGIT_GROUP_SINGLE : VoiceSettings.DIGIT_GROUP_OFF;
        }
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
        showUserDictionaryDialog(context, "", UserDictionary.CATEGORY_MAIN);
    }

    private static void showUserDictionaryDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();

        // Filtered view (search + category), but deletions map back to real indices.
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
        final EditText etSearch = new EditText(context);
        etSearch.setHint("Search words...");
        if (searchQuery != null && !searchQuery.isEmpty()) etSearch.setText(searchQuery);
        layout.addView(etSearch);
        final CheckBox cbMain = new CheckBox(context);
        cbMain.setText("Show Main / Root / Abbrev: tap a header to filter");
        cbMain.setEnabled(false);
        layout.addView(cbMain);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.setting_user_dictionary) + " (" + rules.size() + ")");
        builder.setView(layout);
        if (items.length == 0) {
            builder.setMessage(rules.isEmpty()
                    ? "No custom rules configured. Tap Add Rule to create pronunciation replacements (e.g. AIIMS \u2192 All India Institute of Medical Sciences). Use Import for massive word lists."
                    : "No rules match this search/filter.");
        } else {
            builder.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, final int which) {
                    final int realIdx = viewToReal.get(which);
                    final UserDictionary r = mgr.getRules().get(realIdx);
                    new AlertDialog.Builder(context)
                            .setTitle("Rule: " + r.getPattern())
                            .setMessage(items[which] + "\n\nTap Preview to hear it, Delete to remove.")
                            .setPositiveButton("Preview", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    previewText(context, r.getReplacement().isEmpty()
                                            ? r.getPattern() : r.getReplacement());
                                    showUserDictionaryDialog(context, searchQuery, categoryFilter);
                                }
                            })
                            .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    mgr.removeRule(realIdx);
                                    showUserDictionaryDialog(context, searchQuery, categoryFilter);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    showUserDictionaryDialog(context, searchQuery, categoryFilter);
                                }
                            })
                            .show();
                }
            });
        }

        builder.setPositiveButton("Add rule", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddRuleDialog(context, searchQuery, categoryFilter);
            }
        });
        builder.setNeutralButton("Import / Export", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showDictionaryImportExportDialog(context, searchQuery, categoryFilter);
            }
        });
        builder.setNegativeButton("Close", null);
        final AlertDialog dlg = builder.show();
        // Live search: re-open filtered as the user types (debounced by dialog lifecycle).
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
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

    private static void previewText(final Context context, final String text) {
        if (sTts == null) {
            sTts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS && sTts != null) {
                        sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dict_preview");
                    }
                }
            }, context.getPackageName());
        } else {
            sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dict_preview");
        }
    }

    private static void showAddRuleDialog(final Context context) {
        showAddRuleDialog(context, "", UserDictionary.CATEGORY_MAIN);
    }

    private static void showAddRuleDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText etPattern = new EditText(context);
        etPattern.setHint("Original word / pattern (e.g. AIIMS)");
        layout.addView(etPattern);

        final EditText etReplacement = new EditText(context);
        etReplacement.setHint("Spoken replacement (e.g. All India Institute of Medical Sciences)");
        layout.addView(etReplacement);

        final android.widget.Spinner spCategory = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<String> catAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item,
                new String[]{"Main dictionary", "Root dictionary", "Abbreviation dictionary"});
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdapter);
        layout.addView(spCategory);

        final CheckBox cbWholeWord = new CheckBox(context);
        cbWholeWord.setText("Whole word only");
        cbWholeWord.setChecked(true);
        layout.addView(cbWholeWord);

        final CheckBox cbCaseSensitive = new CheckBox(context);
        cbCaseSensitive.setText("Case sensitive");
        cbCaseSensitive.setChecked(false);
        layout.addView(cbCaseSensitive);

        final EditText etLanguage = new EditText(context);
        etLanguage.setHint("Language code, e.g. en or hi - leave blank for every language");
        layout.addView(etLanguage);

        final String[] chosenCategory = new String[]{UserDictionary.CATEGORY_MAIN};
        spCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                chosenCategory[0] = pos == 1 ? UserDictionary.CATEGORY_ROOT
                        : pos == 2 ? UserDictionary.CATEGORY_ABBREV : UserDictionary.CATEGORY_MAIN;
                if (pos == 1) cbWholeWord.setChecked(false);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        new AlertDialog.Builder(context)
                .setTitle("Add pronunciation rule")
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
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
                                    false,
                                    cbWholeWord.isChecked(),
                                    language,
                                    chosenCategory[0]
                            );
                            UserDictionaryManager.getInstance(context).addRule(rule);
                            // Live audio preview: hear the new pronunciation immediately.
                            previewText(context, replacement.isEmpty() ? pattern : replacement);
                            showUserDictionaryDialog(context, searchQuery, categoryFilter);
                        }
                    }
                })
                .setNeutralButton("Preview", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String replacement = etReplacement.getText().toString().trim();
                        String pattern = etPattern.getText().toString().trim();
                        previewText(context, replacement.isEmpty() ? pattern : replacement);
                        showAddRuleDialog(context, searchQuery, categoryFilter);
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
        final String sampleText = context.getString(R.string.test_voice_sample);
        if (sTts == null) {
            sTts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS && sTts != null) {
                        sTts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, "sample_utterance");
                    }
                }
            }, context.getPackageName());
        } else {
            sTts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, "sample_utterance");
        }
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
        VoiceSettings settings = new VoiceSettings(PreferenceManager.getDefaultSharedPreferences(storageContext), engine);

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
            langCategory.addPreference(createTestVoicePreference(context));
        }

        // 2. Voice parameters (OG interface: dedicated, accessible seekbars with live formatted summary)
        PreferenceCategory paramCategory = new PreferenceCategory(context);
        paramCategory.setTitle(R.string.category_voice_parameters);
        group.addPreference(paramCategory);

        paramCategory.addPreference(createSeekBarPreference(context, engine.Rate, VoiceSettings.PREF_RATE, R.string.setting_default_rate));
        if (!isWatch) {
            paramCategory.addPreference(createRateBoostPreference(context));
        }
        paramCategory.addPreference(createSeekBarPreference(context, engine.Pitch, VoiceSettings.PREF_PITCH, R.string.setting_default_pitch));
        paramCategory.addPreference(createSeekBarPreference(context, engine.PitchRange, VoiceSettings.PREF_PITCH_RANGE, R.string.espeak_pitch_range));
        paramCategory.addPreference(createSeekBarPreference(context, engine.Volume, VoiceSettings.PREF_VOLUME, R.string.espeak_volume));
        paramCategory.addPreference(createSeekBarPreference(context, engine.WordGap, VoiceSettings.PREF_WORD_GAP, R.string.setting_wordgap));
        paramCategory.addPreference(createAudioOptimizerPreference(context));
        if (!isWatch) {
            paramCategory.addPreference(createRecommendedDefaultsPreference(context));
        }

        // 3. Text & speech processing (programming symbols, Indian currency/numbers, smart codes)
        PreferenceCategory processCategory = new PreferenceCategory(context);
        processCategory.setTitle(R.string.category_speech_processing);
        group.addPreference(processCategory);

        processCategory.addPreference(createCapitalsPreference(context));
        processCategory.addPreference(createSpeakPunctuationPreference(context, settings, R.string.espeak_speak_punctuation));
        processCategory.addPreference(createProgrammingSymbolsPreference(context));
        processCategory.addPreference(createIndianNumberingPreference(context));
        processCategory.addPreference(createSmartCodesPreference(context));
        processCategory.addPreference(createSpeakDigitsPreference(context));
        processCategory.addPreference(createDigitGroupingPreference(context));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_TIME_DATE,
                R.string.setting_time_date, R.string.setting_time_date_summary, true));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_CURRENCY,
                R.string.setting_currency, R.string.setting_currency_summary, true));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_SPELLING_MODE,
                R.string.setting_spelling_mode, R.string.setting_spelling_mode_summary, false));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_PHONETIC_MODE,
                R.string.setting_phonetic_mode, R.string.setting_phonetic_mode_summary, false));
        processCategory.addPreference(createCheckPref(context, VoiceSettings.PREF_CODE_READING_MODE,
                R.string.setting_code_reading, R.string.setting_code_reading_summary, false));
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
