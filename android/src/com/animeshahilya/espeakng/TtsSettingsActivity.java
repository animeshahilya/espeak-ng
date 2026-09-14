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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_VOICE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importVoiceUri(this, data.getData());
        }
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

    private static String getVoiceLabel(Voice voice) {
        String name = voice.name; // eSpeak voice id (from engine data)
        LangInfo info = lookupLangInfo(voice);
        if (info != null) {
            return info.language + " - " + info.displayName;
        }
        return name + " - " + name;
    }

    private static class LangInfo {
        final String language;
        final String displayName;
        LangInfo(String language, String displayName) {
            this.language = language;
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
            String language = null;
            String name = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("language")) {
                    language = line.substring("language".length()).trim();
                } else if (line.startsWith("name")) {
                    name = line.substring("name".length()).trim();
                }
                if (language != null && name != null) break;
            }
            if (language == null && name == null) return null;
            if (language == null) language = file.getName();
            if (name == null) name = file.getName();
            return new LangInfo(language, name);
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
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();

        final String[] items = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            UserDictionary r = rules.get(i);
            items[i] = (i + 1) + ". \"" + r.getPattern() + "\" \u2192 \"" + r.getReplacement() + "\""
                    + (r.isRegex() ? " [Regex]" : (r.isWholeWord() ? " [Word]" : ""));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.setting_user_dictionary) + " (" + rules.size() + ")");
        if (rules.isEmpty()) {
            builder.setMessage("No custom rules configured. Tap Add Rule to create pronunciation replacements (e.g. AIIMS \u2192 All India Institute of Medical Sciences).");
        } else {
            builder.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, final int which) {
                    new AlertDialog.Builder(context)
                            .setTitle("Delete rule?")
                            .setMessage(items[which])
                            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    mgr.removeRule(which);
                                    showUserDictionaryDialog(context);
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        }

        builder.setPositiveButton("Add rule", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddRuleDialog(context);
            }
        });

        if (!rules.isEmpty()) {
            builder.setNeutralButton("Clear all", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mgr.clearRules();
                    showUserDictionaryDialog(context);
                }
            });
        }

        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private static void showAddRuleDialog(final Context context) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText etPattern = new EditText(context);
        etPattern.setHint("Original word / pattern (e.g. AIIMS)");
        layout.addView(etPattern);

        final EditText etReplacement = new EditText(context);
        etReplacement.setHint("Spoken replacement (e.g. All India Institute of Medical Sciences)");
        layout.addView(etReplacement);

        final CheckBox cbWholeWord = new CheckBox(context);
        cbWholeWord.setText("Whole word only");
        cbWholeWord.setChecked(true);
        layout.addView(cbWholeWord);

        final CheckBox cbCaseSensitive = new CheckBox(context);
        cbCaseSensitive.setText("Case sensitive");
        cbCaseSensitive.setChecked(false);
        layout.addView(cbCaseSensitive);

        new AlertDialog.Builder(context)
                .setTitle("Add pronunciation rule")
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pattern = etPattern.getText().toString().trim();
                        String replacement = etReplacement.getText().toString().trim();
                        if (!pattern.isEmpty()) {
                            UserDictionary rule = new UserDictionary(
                                    pattern,
                                    replacement,
                                    cbCaseSensitive.isChecked(),
                                    false,
                                    cbWholeWord.isChecked()
                            );
                            UserDictionaryManager.getInstance(context).addRule(rule);
                            showUserDictionaryDialog(context);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
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
        processCategory.addPreference(createUnicodeNormalizationPreference(context));
        if (!isWatch) {
            processCategory.addPreference(createUserDictionaryPreference(context));
            processCategory.addPreference(createNatoSpellingPreference(context));
            processCategory.addPreference(createSpokenDiacriticsPreference(context));
            processCategory.addPreference(createEmojiProcessingPreference(context));
        }

        // 4. About
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
