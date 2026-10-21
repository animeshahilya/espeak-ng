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

package com.animeshahilya.espeakng.ui;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import com.animeshahilya.espeakng.BuildConfig;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.text.ScriptLanguages;
import com.animeshahilya.espeakng.tts.CheckVoiceData;
import com.animeshahilya.espeakng.ui.preference.FavoriteVoicesDialogFragment;
import com.animeshahilya.espeakng.ui.preference.ImportVoicePreference;
import com.animeshahilya.espeakng.ui.preference.SeekBarPreference;
import com.animeshahilya.espeakng.ui.preference.SpeakPunctuationPreference;
import com.animeshahilya.espeakng.ui.preference.SupportedLanguagesPreference;
import com.animeshahilya.espeakng.ui.preference.VoiceVariantPreference;
import com.animeshahilya.espeakng.util.LogExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the settings tree: gathers what it needs from the engine off the main thread, inflates res/xml/preferences.xml, and wires every row (summaries, dependent rows, actions).
 */
public final class SettingsBuilder {
    private static final String TAG = "SettingsBuilder";

    private SettingsBuilder() {
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

        final SharedPreferences prefs = TtsSettingsActivity.getPrefs();
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
    public static void createPreferences(final PreferenceFragmentCompat fragment) {
        final Context context = fragment.requireActivity();
        final Context storage = EspeakApp.requireStorageContext(context);
        final Handler handler = new Handler(Looper.getMainLooper());

        EspeakApp.runAsync(() -> {
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
                // the first LanguageLabels.getVoiceLabel() call, which would drag the whole
                // scan back onto the main thread. Skipped on Wear, where the
                // supported-languages list is not built at all.
                if (!isWatch) {
                    LanguageLabels.ensureLangInfoLoaded();
                }

                handler.post(() -> {
                    if (TtsSettingsActivity.isGone(context) || !fragment.isAdded()) {
                        return;
                    }
                    final PreferenceScreen root = buildPreferences(context,
                            (SettingsFragment) fragment,
                            fragment.getPreferenceManager(), engine, voices, isWatch);
                    fragment.setPreferenceScreen(root);
                    ((SettingsFragment) fragment).onRootScreenReady(root);
                    SettingsDialogs.maybeShowWhatsNew(context);
                    com.animeshahilya.espeakng.util.AppUpdater.checkIfDue(context);
                });
            } catch (Throwable t) {
                // The engine probe (native lib load, phondata, JNI voice
                // enumeration) used to run unguarded on this worker: any
                // failure became an uncaught exception that killed the
                // whole process with zero UI feedback.
                Log.e(TAG, "Failed to build settings preferences", t);
                handler.post(() -> {
                    if (TtsSettingsActivity.isGone(context)) {
                        return;
                    }
                    Toast.makeText(context, R.string.settings_load_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Recursively attaches iOS-style disclosure chevrons to PreferenceScreen sub-pages.
     */
    public static void decoratePreferenceScreens(androidx.preference.PreferenceGroup group) {
        if (group == null) return;
        for (int i = 0, n = group.getPreferenceCount(); i < n; i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceScreen) {
                p.setWidgetLayoutResource(R.layout.preference_widget_chevron);
                decoratePreferenceScreens((androidx.preference.PreferenceGroup) p);
            } else if (p instanceof androidx.preference.PreferenceGroup) {
                decoratePreferenceScreens((androidx.preference.PreferenceGroup) p);
            }
        }
    }

    /** Keys of the action rows in res/xml/preferences.xml (no stored value). */
    private static final String KEY_TEST_VOICE = "action_test_voice";

    /**
     * Inflates res/xml/preferences.xml and fills in what needs the engine
     * (voice parameters, the language list). Runs on the main thread once
     * createPreferences() has loaded the engine.
     */
    private static PreferenceScreen buildPreferences(final Context context,
                                                     final SettingsFragment fragment,
                                                     PreferenceManager pm,
                                                     SpeechSynthesis engine, List<Voice> voices,
                                                     boolean isWatch) {
        final SharedPreferences prefs = TtsSettingsActivity.getPrefs();
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
        if ("phonetic".equals(prefs.getString(VoiceSettings.PREF_READING_MODE, null))
                || !prefs.contains(VoiceSettings.PREF_READING_MODE)) {
            seed.putString(VoiceSettings.PREF_READING_MODE, settings.getReadingMode());
        }
        if (!prefs.contains(VoiceSettings.PREF_INDIAN_NUMBERING_VOICES)) {
            seed.putString(VoiceSettings.PREF_INDIAN_NUMBERING_VOICES, settings.getIndianNumberingVoices());
        }
        if (!prefs.contains(VoiceSettings.PREF_DIGIT_GROUPING)) {
            seed.putString(VoiceSettings.PREF_DIGIT_GROUPING, settings.getDigitGroupingMode());
        }
        seed.apply();

        final PreferenceScreen screen = pm.inflateFromResource(context, R.xml.preferences, null);
        decoratePreferenceScreens(screen);
        configureScriptLanguages(context, screen, voices);

        if (isWatch) {
            for (String key : new String[] {
                    LanguageSettings.PREF_SUPPORTED_LANGUAGES, KEY_TEST_VOICE,
                    "action_preview_numbers", "action_preview_pauses",
                    "action_preview_punctuation", "action_preview_reading",
                    "action_preview_mixed_languages", "action_preview_routing",
                    "action_preview_audio_optimizer",
                    VoiceSettings.PREF_RATE_BOOST_LEVEL,
                    VoiceSettings.PREF_USER_DICTIONARY, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                    VoiceSettings.PREF_PHONETIC_LETTERS, VoiceSettings.PREF_SPOKEN_DIACRITICS,
                    VoiceSettings.PREF_EMOJI_PROCESSING, VoiceSettings.PREF_SIMPLIFY_URLS,
                    "action_favorite_voices", VoiceSettings.PREF_SLEEP_TIMER,
                    "action_save_profile", "action_load_profile",
                    "category_app_cards", "action_recent_speech",
                    "category_presets", "category_data",
                    // Neural voices need more memory and CPU than a watch
                    // should spend on a screen reader voice.
                    PiperSettings.KEY_SCREEN, "category_natural_switching"}) {
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

            onClick(screen, KEY_TEST_VOICE, () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_voice_sample), "sample_utterance"));
            onClick(screen, "action_preview_numbers", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_numbers_sample), "sample_numbers"));
            onClick(screen, "action_preview_pauses", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_pauses_sample), "sample_pauses"));
            onClick(screen, "action_preview_punctuation", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_punctuation_sample), "sample_punctuation"));
            onClick(screen, "action_preview_reading", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_reading_sample), "sample_reading"));
            onClick(screen, "action_preview_mixed_languages", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_mixed_languages_sample), "sample_mixed_languages"));
            onClick(screen, "action_preview_routing", () -> SpeechPreview.previewRouting(context));
            onClick(screen, "action_preview_audio_optimizer", () -> SpeechPreview.speakPreview(context, context.getString(R.string.test_audio_optimizer_sample), "sample_audio_optimizer"));
            onClick(screen, VoiceSettings.PREF_USER_DICTIONARY, () -> UserDictionaryScreen.show(context));
            onClick(screen, "action_recommended_defaults", () -> applyRecommendedDefaults(context));
            onClick(screen, "action_backup", () -> {
                final Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "espeak_backup.json");
                startForResult(context, i, TtsSettingsActivity.REQUEST_CODE_EXPORT_BACKUP);
            });
            onClick(screen, "action_restore", () -> {
                final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startForResult(context, i, TtsSettingsActivity.REQUEST_CODE_IMPORT_BACKUP);
            });
            onClick(screen, "action_export_log", () -> exportLog(context));
            PiperSettings.configure(context, screen, fragment, prefs);
            com.animeshahilya.espeakng.ui.LanguageCards.configure(context, screen, fragment, prefs, voices);
            AppCards.configure(context, screen, fragment, prefs);
            final ImportVoicePreference importVoice = screen.findPreference("action_import_voice");
            importVoice.setOnPreferenceChangeListener(mOnPreferenceChanged);
            importVoice.setDescription(R.string.import_voice_description);
        }
        onClick(screen, "action_about", () -> SettingsDialogs.showAboutDialog(context));
        onClick(screen, "action_check_updates",
                () -> com.animeshahilya.espeakng.util.AppUpdater.checkNow(context));
        final Preference checkUpdates = screen.findPreference("action_check_updates");
        if (checkUpdates != null) {
            checkUpdates.setSummary(context.getString(R.string.update_check_summary, BuildConfig.VERSION_NAME));
        }

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
        showWhile(screen, VoiceSettings.PREF_AUDIO_OPTIMIZER_LEVEL, "category_optimizer_modules",
                value -> VoiceSettings.AUDIO_PROFILE_CUSTOM.equals(value),
                prefs.getString(VoiceSettings.PREF_AUDIO_OPTIMIZER_LEVEL, VoiceSettings.AUDIO_PROFILE_OFF),
                fragment, context.getString(R.string.setting_optimizer_modules_shown));
        showWhile(screen, VoiceSettings.PREF_DIGIT_GROUPING, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                value -> VoiceSettings.DIGIT_GROUP_TRIPLE.equals(value)
                        || VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE.equals(value),
                prefs.getString(VoiceSettings.PREF_DIGIT_GROUPING, VoiceSettings.DIGIT_GROUP_OFF),
                fragment, context.getString(R.string.setting_digit_threshold_shown));

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
                startForResult(context, i, TtsSettingsActivity.REQUEST_CODE_EXPORT_PROFILE);
            });
            onClick(screen, "action_load_profile", () -> {
                final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                startForResult(context, i, TtsSettingsActivity.REQUEST_CODE_IMPORT_PROFILE);
            });
            onClick(screen, "action_recent_speech", () -> openRecentSpeech(context, prefs));
            refreshFavoritesSummary(screen, prefs);
        }
        return screen;
    }

    /**
     * Recent speech: without the tile, Android 13+ offers to add it (the
     * tile is the opt-in); otherwise the screen opens, and says how to add
     * the tile where it is missing.
     */
    private static void openRecentSpeech(Context context, SharedPreferences prefs) {
        if (!prefs.getBoolean(RecentSpeech.PREF_ON, false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            final StatusBarManager bar = context.getSystemService(StatusBarManager.class);
            if (bar != null) {
                bar.requestAddTileService(new ComponentName(context, RecentSpeechTile.class),
                        context.getString(R.string.recent_speech_title),
                        Icon.createWithResource(context, R.drawable.ic_record_voice_over),
                        context.getMainExecutor(), result -> {
                            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                RecentSpeech.setOn(prefs, true);
                            }
                            context.startActivity(new Intent(context, RecentSpeechActivity.class));
                        });
                return;
            }
        }
        context.startActivity(new Intent(context, RecentSpeechActivity.class));
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
        // The XML sets useSimpleSummaryProvider: a SummaryProvider is already
        // installed, and setSummary() throws while one is set. Clear it first;
        // this row's summary is fully owned here from now on.
        pref.setSummaryProvider(null);
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
        final SeekBarPreference rate = screen.findPreference(VoiceSettings.PREF_RATE);
        pref.setOnPreferenceChangeListener((p, value) -> {
            final String level = String.valueOf(value);
            updateBoostSummary(context, (ListPreference) p, level);
            if (rate != null) {
                // The Rate row shows the boosted rate; it was built with the old level.
                rate.setRateBoost(!VoiceSettings.RATE_BOOST_OFF.equals(level),
                        VoiceSettings.boostMultiplier(level));
            }
            return true;
        });
    }

    private static void updateBoostSummary(Context context, ListPreference pref, String value) {
        // Same provider conflict as the threshold row above: the XML installs
        // the simple provider, which must go before manual summaries.
        pref.setSummaryProvider(null);
        if (value == null || VoiceSettings.RATE_BOOST_OFF.equals(value)) {
            pref.setSummary(context.getString(R.string.setting_off));
        } else {
            pref.setSummary(context.getString(R.string.summary_rate_boost_on, value));
        }
    }

    /**
     * Sleep timer choices, generated like the rate-boost list so the UI
     * cannot drift from {@link VoiceSettings#SLEEP_MAX_MINUTES}. Reading
     * apps stop at the chosen time; the screen reader never does, so the
     * row stays reachable. Off lets them talk again at once.
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
                VoiceSettings.clearSleepTimer(prefs);
                Toast.makeText(context, R.string.sleep_timer_resumed, Toast.LENGTH_SHORT).show();
            } else {
                int minutes;
                try {
                    minutes = Math.min(VoiceSettings.SLEEP_MAX_MINUTES, Math.max(1,
                            Integer.parseInt(v)));
                } catch (NumberFormatException e) {
                    return false;
                }
                final long at = VoiceSettings.armSleepTimer(prefs, minutes);
                Toast.makeText(context, context.getString(R.string.sleep_stops_at, clock(context, at)),
                        Toast.LENGTH_SHORT).show();
            }
            updateSleepSummary(context, (ListPreference) p, prefs);
            return true;
        });
    }

    public static void updateSleepSummary(Context context, ListPreference pref,
                                           SharedPreferences prefs) {
        final long at = VoiceSettings.sleepStopAt(prefs, System.currentTimeMillis());
        if (at == 0) {
            pref.setValue(VoiceSettings.SLEEP_OFF); // a timer that ended by itself
            pref.setSummary(context.getString(R.string.setting_sleep_timer_summary));
        } else if (VoiceSettings.isSleepStopped(prefs)) {
            pref.setSummary(context.getString(R.string.sleep_stopped));
        } else {
            pref.setSummary(context.getString(R.string.sleep_stops_at, clock(context, at)));
        }
    }

    private static String clock(Context context, long millis) {
        return android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(millis));
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
                                  SettingsFragment fragment, String revealAnnouncement) {
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

    /**
     * Mixed languages: each script's list offers eSpeak's own choice
     * first (stored as "", so nothing changes until a user picks), then the
     * voices of {@link ScriptLanguages}, named as in the voice picker.
     */
    private static void configureScriptLanguages(Context context, PreferenceScreen screen,
                                                 List<Voice> voices) {
        final Map<String, Voice> byName = new HashMap<>();
        for (Voice voice : voices) {
            byName.put(voice.name, voice);
        }
        for (ScriptLanguages.Script script : ScriptLanguages.SCRIPTS) {
            final CharSequence[] entries = new CharSequence[script.choices.length + 1];
            final CharSequence[] values = new CharSequence[script.choices.length + 1];
            entries[0] = script.espeakDefault == null
                    ? context.getString(R.string.script_language_spell)
                    : context.getString(R.string.script_language_default,
                            voiceLabel(byName, script.espeakDefault));
            values[0] = "";
            for (int i = 0; i < script.choices.length; i++) {
                entries[i + 1] = voiceLabel(byName, script.choices[i]);
                values[i + 1] = script.choices[i];
            }
            setEntries(screen, script.prefKey(), entries, values);
        }
    }

    private static String voiceLabel(Map<String, Voice> byName, String name) {
        final Voice voice = byName.get(name);
        return voice != null ? LanguageLabels.getVoiceLabel(voice) : name;
    }

    private static void setEntries(PreferenceScreen screen, String key,
                                   CharSequence[] entries, CharSequence[] values) {
        final ListPreference pref = screen.findPreference(key);
        pref.setEntries(entries);
        pref.setEntryValues(values);
    }

    /**
     * Opens the system file picker. Some devices have none (Wear OS, some TV
     * and managed profiles): say so instead of crashing the settings screen.
     */
    public static void startForResult(Context context, Intent intent, int requestCode) {
        if (!(context instanceof Activity)) {
            return;
        }
        try {
            ((Activity) context).startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    private static void applyRecommendedDefaults(Context context) {
        TtsSettingsActivity.getPrefs().edit()
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
        EspeakApp.runAsync(() -> {
            final String log = LogExporter.collect(context);
            handler.post(() -> {
                if (TtsSettingsActivity.isGone(context)) return;
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject));
                share.putExtra(Intent.EXTRA_TEXT, log);
                try {
                    context.startActivity(Intent.createChooser(share,
                            context.getString(R.string.setting_export_log)));
                } catch (RuntimeException e) {
                    // No share sheet on this device, or a log too large to hand over.
                    Log.w(TAG, "Log share failed", e);
                    Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
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
            labels.put(voice, LanguageLabels.getVoiceLabel(voice));
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

        Set<String> selected = LanguageSettings.getSelectedLanguages(TtsSettingsActivity.getPrefs());
        if (selected == null) {
            selected = new HashSet<String>();
            for (Voice voice : sortedVoices) {
                selected.add(voice.toString());
            }
        }
        pref.setValues(selected);
        pref.setSummary(LanguageLabels.getSupportedLanguagesSummary(context, selected, pref.getDistinctValueCount()));
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
            preference.setSummary(LanguageLabels.getSupportedLanguagesSummary(preference.getContext(), values, total));
        }
        return true;
    };
}
