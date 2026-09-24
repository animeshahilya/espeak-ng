/*
 * Copyright (C) 2013 Reece H. Dunn
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

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public class VoiceSettings {
    private final SharedPreferences mPreferences;
    private final SpeechSynthesis mEngine;

    public static final String PREF_DEFAULT_GENDER = "default_gender";
    public static final String PREF_VARIANT = "espeak_variant";
    public static final String PREF_DEFAULT_RATE = "default_rate";
    public static final String PREF_RATE = "espeak_rate";
    public static final String PREF_DEFAULT_PITCH = "default_pitch";
    public static final String PREF_PITCH = "espeak_pitch";
    public static final String PREF_PITCH_RANGE = "espeak_pitch_range";
    public static final String PREF_VOLUME = "espeak_volume";
    public static final String PREF_PUNCTUATION_LEVEL = "espeak_punctuation_level";
    public static final String PREF_PUNCTUATION_CHARACTERS = "espeak_punctuation_characters";
    /** Rate boost as one choice: {@link #RATE_BOOST_OFF} or the multiplier ("2"-"5"). */
    public static final String PREF_RATE_BOOST_LEVEL = "espeak_rate_boost_level";
    public static final String RATE_BOOST_OFF = "off";
    /** @deprecated Toggle + amount, merged into {@link #PREF_RATE_BOOST_LEVEL}; kept as the migration source. */
    @Deprecated
    public static final String PREF_RATE_BOOST = "espeak_rate_boost";
    /** @deprecated See {@link #PREF_RATE_BOOST}. */
    @Deprecated
    public static final String PREF_RATE_BOOST_MULTIPLIER = "espeak_rate_boost_multiplier";
    public static final String PREF_UNICODE_NORMALIZATION = "espeak_unicode_normalization";
    /** @deprecated Legacy eyes-free-era digit toggle; see {@link #isSpeakDigitsEnabled}. */
    @Deprecated
    public static final String PREF_SPEAK_DIGITS = "espeak_speak_digits";
    public static final int RATE_BOOST_MULTIPLIER = 3;
    public static final int RATE_BOOST_MULTIPLIER_MIN = 2;
    public static final int RATE_BOOST_MULTIPLIER_MAX = 5;
    public static final String PREF_NORMALIZE_UNICODE = "espeak_normalize_unicode";
    public static final String PREF_EMOJI_PROCESSING = "espeak_emoji_processing";
    public static final String PREF_CAPITALS = "espeak_capitals";
    public static final String PREF_EMPHASIZE_QUESTIONS = "espeak_emphasize_questions";
    public static final String PREF_WORD_GAP = "espeak_wordgap";
    public static final String PREF_PAUSE_SCALE = "espeak_pause_scale";
    public static final String PREF_INDIAN_NUMBERING = "espeak_indian_numbering";
    // Opt-in extras beyond NVDA (new keys: the removed pre-parity toggles defaulted on).
    public static final String PREF_READ_MONEY = "espeak_read_money";
    public static final String PREF_READ_CODES = "espeak_read_codes";
    public static final String PREF_PUNCTUATION_SOUNDS = "espeak_punctuation_sounds";
    public static final String PREF_PHRASE_PAUSES = "espeak_phrase_pauses";
    public static final String PREF_EXPAND_ABBREVIATIONS = "espeak_expand_abbreviations";
    public static final String PREF_HINGLISH = "espeak_hinglish";
    public static final String PREF_USER_DICTIONARY = "espeak_user_dictionary";
    /** Phonetic letters: {@link #PHONETIC_OFF}, {@link #PHONETIC_CHARACTER} or {@link #PHONETIC_ALWAYS}. */
    public static final String PREF_PHONETIC_LETTERS = "espeak_phonetic_letters";
    public static final String PHONETIC_OFF = "off";
    public static final String PHONETIC_CHARACTER = "character";
    public static final String PHONETIC_ALWAYS = "always";
    /** @deprecated Merged into {@link #PREF_PHONETIC_LETTERS} (with the old "phonetic" reading mode). */
    @Deprecated
    public static final String PREF_NATO_SPELLING = "espeak_nato_spelling";
    public static final String PREF_SPOKEN_DIACRITICS = "espeak_spoken_diacritics";
    /** Audio optimizer as one choice: {@link #AUDIO_PROFILE_OFF} or a profile. */
    public static final String PREF_AUDIO_OPTIMIZER_LEVEL = "espeak_audio_optimizer_level";
    /** @deprecated Toggle + profile, merged into {@link #PREF_AUDIO_OPTIMIZER_LEVEL}. */
    @Deprecated
    public static final String PREF_AUDIO_OPTIMIZER = "espeak_audio_optimizer";
    // Digit grouping.
    public static final String PREF_DIGIT_GROUPING = "espeak_digit_grouping";
    public static final String PREF_DIGIT_GROUP_THRESHOLD = "espeak_digit_group_threshold";
    /** @deprecated Legacy single-mode booleans; migrated by {@link #getReadingMode}. Keys kept. */
    @Deprecated
    public static final String PREF_SPELLING_MODE = "espeak_spelling_mode";
    /** @deprecated See {@link #PREF_SPELLING_MODE}. */
    @Deprecated
    public static final String PREF_PHONETIC_MODE = "espeak_phonetic_mode";
    /** @deprecated See {@link #PREF_SPELLING_MODE}. */
    @Deprecated
    public static final String PREF_CODE_READING_MODE = "espeak_code_reading";
    // Deep-tuning: force overrides, optimizer intensity, unified reading mode.
    public static final String PREF_FORCE_RATE = "espeak_force_rate";
    public static final String PREF_FORCE_PITCH = "espeak_force_pitch";
    public static final String PREF_FORCE_VOLUME = "espeak_force_volume";
    /** @deprecated See {@link #PREF_AUDIO_OPTIMIZER}. */
    @Deprecated
    public static final String PREF_AUDIO_PROFILE = "espeak_audio_profile";
    public static final String PREF_READING_MODE = "espeak_reading_mode";
    public static final String PREF_REPEATED_CHARS = "espeak_repeated_chars";
    public static final String PREF_INTONATION_STYLE = "espeak_intonation_style";
    public static final String PREF_CAPITALS_SCOPE = "espeak_capitals_scope";
    public static final String PREF_SIMPLIFY_URLS = "espeak_simplify_urls";
    public static final String PREF_INTONATION_GROUP = "espeak_intonation_group";

    /** Repeated character handling modes. */
    public static final String REPEATED_CHARS_OFF = "off";
    public static final String REPEATED_CHARS_COUNT = "count";
    public static final String REPEATED_CHARS_TRUNCATE = "truncate";

    /** Intonation styles. */
    public static final String INTONATION_NATURAL = "natural";
    public static final String INTONATION_FLAT = "flat";
    public static final String INTONATION_EXPRESSIVE = "expressive";
    public static final String INTONATION_CUSTOM = "custom";

    /** Capital letter announcement scope. */
    public static final String CAPITALS_SCOPE_CHAR = "char_only";
    public static final String CAPITALS_SCOPE_ALL = "all";

    public static final String EMOJI_ANNOUNCE = "announce";
    public static final String EMOJI_IGNORE = "ignore";

    /** Digit grouping modes for long numbers. */
    public static final String DIGIT_GROUP_OFF = "off";
    public static final String DIGIT_GROUP_SINGLE = "single";
    public static final String DIGIT_GROUP_DOUBLE = "double";
    public static final String DIGIT_GROUP_TRIPLE = "triple";

    /** Unified reading modes (single-select; migrates legacy booleans). */
    public static final String READING_NORMAL = "normal";
    public static final String READING_SPELLING = "spelling";
    /** @deprecated Now {@link #PHONETIC_ALWAYS}; read only to migrate. */
    @Deprecated
    public static final String READING_PHONETIC = "phonetic";
    public static final String READING_CODE = "code";

    /** Audio optimizer intensity profiles. */
    public static final String AUDIO_PROFILE_OFF = "off";
    public static final String AUDIO_PROFILE_GENTLE = "gentle";
    public static final String AUDIO_PROFILE_BALANCED = "balanced";
    public static final String AUDIO_PROFILE_FULL = "full";

    public static final String PRESET_VARIANT = "variant";
    public static final String PRESET_RATE = "rate";
    public static final String PRESET_PITCH = "pitch";
    public static final String PRESET_PITCH_RANGE = "pitch-range";
    public static final String PRESET_VOLUME = "volume";
    public static final String PRESET_PUNCTUATION_LEVEL = "punctuation-level";
    public static final String PRESET_PUNCTUATION_CHARACTERS = "punctuation-characters";

    public static final String PUNCTUATION_NONE = "none";
    public static final String PUNCTUATION_SOME = "some";
    public static final String PUNCTUATION_ALL = "all";

    /**
     * UI-facing punctuation presets, shown as the radio choices in the "Speak
     * punctuation" dialog. These sit on top of the engine's three-level
     * PUNCT_NONE/PUNCT_SOME/PUNCT_ALL model (see SpeechSynthesis) the same
     * way NVDA's None/Some/Most/All/Character symbol levels sit on top of a
     * flat symbol dictionary - "Some" and "Most" are both PUNCT_SOME under
     * the hood, just with different built-in character lists, so no engine
     * or storage changes were needed to add them.
     */
    public static final String PUNCTUATION_PRESET_NONE = "none";
    public static final String PUNCTUATION_PRESET_SOME = "some";
    public static final String PUNCTUATION_PRESET_MOST = "most";
    public static final String PUNCTUATION_PRESET_ALL = "all";
    public static final String PUNCTUATION_PRESET_CUSTOM = "custom";

    /** "Some": the punctuation needed to follow the shape of a sentence. */
    public static final String PUNCTUATION_CHARS_SOME = ".,!?;:'\"-";

    /** "Most": Some, plus symbols that commonly appear in ordinary text. */
    public static final String PUNCTUATION_CHARS_MOST = PUNCTUATION_CHARS_SOME + "()[]{}/@#$%&*+=<>_~^|\\";

    public static final String DEFAULT_VARIANT = "max";
    public static final int DEFAULT_PITCH = 40;
    public static final int DEFAULT_PITCH_RANGE = 75;
    public static final int DEFAULT_CAPITALS = 3;

    public VoiceSettings(SharedPreferences preferences, SpeechSynthesis engine) {
        mPreferences = preferences;
        mEngine = engine;
    }

    /**
     * True when text has no char above ' ' - exactly {@code trim().isEmpty()}
     * semantics without copying the string (requests can be up to 300k chars,
     * and this runs before any other setup on every one of them).
     */
    public static boolean isBlank(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > ' ') {
                return false;
            }
        }
        return true;
    }

    public VoiceVariant getVoiceVariant() {
        String variant = mPreferences.getString(PREF_VARIANT, null);
        if (variant == null) {
            int gender = getPreferenceValue(PREF_DEFAULT_GENDER, -1);
            if (gender == SpeechSynthesis.GENDER_FEMALE) {
                return VoiceVariant.parseVoiceVariant(VoiceVariant.FEMALE);
            } else if (gender == SpeechSynthesis.GENDER_MALE) {
                return VoiceVariant.parseVoiceVariant(VoiceVariant.MALE);
            }
            return VoiceVariant.parseVoiceVariant(DEFAULT_VARIANT);
        }
        return VoiceVariant.parseVoiceVariant(variant);
    }

    public int getRate() {
        int min = mEngine.Rate.getMinValue();
        int max = mEngine.Rate.getMaxValue();

        int rate = getPreferenceValue(PREF_RATE, Integer.MIN_VALUE);
        if (rate == Integer.MIN_VALUE) {
            rate = (int)((float)getPreferenceValue(PREF_DEFAULT_RATE, 100) / 100 * (float)mEngine.Rate.getDefaultValue());
        }

        if (isRateBoostEnabled()) {
            // Allow values beyond the normal espeakRATE_MAXIMUM so the native
            // engine can engage its Sonic fast path for very high rates.
            int multiplier = getRateBoostMultiplier();
            rate = rate * multiplier;
            int boostedMax = max * multiplier; // keep within a sensible upper bound
            if (rate > boostedMax) rate = boostedMax;
        } else if (rate > max) {
            rate = max;
        }

        if (rate < min) rate = min;
        return rate;
    }

    public int getPitch() {
        int pitch = getPreferenceValue(PREF_PITCH, Integer.MIN_VALUE);
        if (pitch == Integer.MIN_VALUE) {
            if (mPreferences.contains(PREF_DEFAULT_PITCH)) {
                pitch = getPreferenceValue(PREF_DEFAULT_PITCH, 100) / 2;
            } else {
                pitch = DEFAULT_PITCH;
            }
        }

        return clamp(mEngine.Pitch, pitch);
    }

    public int getPitchRange() {
        return clamp(mEngine.PitchRange, getPreferenceValue(PREF_PITCH_RANGE, DEFAULT_PITCH_RANGE));
    }

    public int getVolume() {
        return clamp(mEngine.Volume, getPreferenceValue(PREF_VOLUME, mEngine.Volume));
    }

    public int getPunctuationLevel() {
        return clamp(mEngine.Punctuation, getPreferenceValue(PREF_PUNCTUATION_LEVEL, mEngine.Punctuation));
    }

    public String getPunctuationCharacters() {
        return mPreferences.getString(PREF_PUNCTUATION_CHARACTERS, null);
    }

    private static int clamp(SpeechSynthesis.Parameter parameter, int value) {
        return Math.max(parameter.getMinValue(), Math.min(parameter.getMaxValue(), value));
    }

    /**
     * The int stored as a string under {@code preference}, or null when it is
     * missing or malformed (e.g. hand-edited prefs), which would otherwise
     * crash every synthesis request routed through here.
     */
    private Integer parsePreference(String preference) {
        String prefString = mPreferences.getString(preference, null);
        if (prefString == null) {
            return null;
        }
        try {
            return Integer.parseInt(prefString);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int getPreferenceValue(String preference, int defaultValue) {
        Integer value = parsePreference(preference);
        return value != null ? value : defaultValue;
    }

    /**
     * As {@link #getPreferenceValue(String, int)}, but defers
     * {@link SpeechSynthesis.Parameter#getDefaultValue()} - which is a JNI
     * call - to the missing/malformed paths only. The int overload cannot do
     * this: Java evaluates the default argument eagerly, so every request was
     * paying four pointless JNI round-trips even with the preference set.
     */
    private int getPreferenceValue(String preference, SpeechSynthesis.Parameter parameter) {
        Integer value = parsePreference(preference);
        return value != null ? value : parameter.getDefaultValue();
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject settings = new JSONObject();
        settings.put(PRESET_VARIANT, getVoiceVariant().toString());
        settings.put(PRESET_RATE, getRate());
        settings.put(PRESET_PITCH, getPitch());
        settings.put(PRESET_PITCH_RANGE, getPitchRange());
        settings.put(PRESET_VOLUME, getVolume());
        settings.put(PRESET_PUNCTUATION_CHARACTERS, getPunctuationCharacters());
        switch (getPunctuationLevel()) {
            case SpeechSynthesis.PUNCT_NONE:
                settings.put(PRESET_PUNCTUATION_LEVEL, PUNCTUATION_NONE);
                break;
            case SpeechSynthesis.PUNCT_SOME:
                settings.put(PRESET_PUNCTUATION_LEVEL, PUNCTUATION_SOME);
                break;
            case SpeechSynthesis.PUNCT_ALL:
                settings.put(PRESET_PUNCTUATION_LEVEL, PUNCTUATION_ALL);
                break;
        }
        return settings;
    }

    /** {@link #RATE_BOOST_OFF} or the multiplier, migrating the old toggle + amount on first read. */
    public String getRateBoostLevel() {
        String level = mPreferences.getString(PREF_RATE_BOOST_LEVEL, null);
        if (level != null) return level;
        return mPreferences.getBoolean(PREF_RATE_BOOST, false)
                ? Integer.toString(clampBoost(getPreferenceValue(PREF_RATE_BOOST_MULTIPLIER, RATE_BOOST_MULTIPLIER)))
                : RATE_BOOST_OFF;
    }

    public boolean isRateBoostEnabled() {
        return !RATE_BOOST_OFF.equals(getRateBoostLevel());
    }

    /**
     * How far {@link #isRateBoostEnabled()} multiplies the rate past the
     * engine's normal ceiling, once boost is on. Defaults to the historical
     * fixed amount ({@link #RATE_BOOST_MULTIPLIER}) so existing installs keep
     * the exact rate they had; user-adjustable from 2x-5x so "boost" isn't a
     * single fixed jump. 5x is not an engine limit, just a conservative UI
     * ceiling - well past it, Sonic's resampling would be expected to start
     * costing intelligibility rather than just speed.
     */
    public int getRateBoostMultiplier() {
        try {
            return clampBoost(Integer.parseInt(getRateBoostLevel()));
        } catch (NumberFormatException e) {
            return RATE_BOOST_MULTIPLIER; // off, or a malformed value
        }
    }

    private static int clampBoost(int value) {
        if (value < RATE_BOOST_MULTIPLIER_MIN) value = RATE_BOOST_MULTIPLIER_MIN;
        if (value > RATE_BOOST_MULTIPLIER_MAX) value = RATE_BOOST_MULTIPLIER_MAX;
        return value;
    }

    /**
     * Whether to NFKC-normalize text before synthesis, so stylized Unicode
     * (e.g. 𝖇𝖔𝖑𝖉 social media "fonts") is read as words instead of being
     * spelled out codepoint by codepoint. On by default, matching NVDA's
     * speech setting and speech-dispatcher's always-on server behaviour.
     */
    public boolean isUnicodeNormalizationEnabled() {
        if (mPreferences.contains(PREF_UNICODE_NORMALIZATION)) {
            return mPreferences.getBoolean(PREF_UNICODE_NORMALIZATION, true);
        }
        return mPreferences.getBoolean(PREF_NORMALIZE_UNICODE, true);
    }

    public String getEmojiProcessingMode() {
        return mPreferences.getString(PREF_EMOJI_PROCESSING, EMOJI_ANNOUNCE);
    }

    public boolean isEmojiIgnoreEnabled() {
        return EMOJI_IGNORE.equals(getEmojiProcessingMode());
    }

    public int getCapitals() {
        return clamp(mEngine.Capitals, getPreferenceValue(PREF_CAPITALS, DEFAULT_CAPITALS));
    }

    public int getWordGap() {
        return clamp(mEngine.WordGap, getPreferenceValue(PREF_WORD_GAP, mEngine.WordGap));
    }

    /** Reading pace: percent multiplier on clause/sentence/paragraph pauses. 100=normal. */
    public int getPauseScale() {
        return clamp(mEngine.PauseScale, getPreferenceValue(PREF_PAUSE_SCALE, mEngine.PauseScale));
    }

    /**
     * Whether to read numbers digit-by-digit instead of as whole numbers.
     *
     * @deprecated Superseded by {@link #getDigitGroupingMode()} ("single" is
     *             identical behavior). Kept only as the migration source for
     *             installs that set this boolean before grouping existed, and
     *             as the persisted key - do not remove the key.
     */
    @Deprecated
    public boolean isSpeakDigitsEnabled() {
        return mPreferences.getBoolean(PREF_SPEAK_DIGITS, false);
    }

    public boolean isIndianNumberingEnabled() {
        return mPreferences.getBoolean(PREF_INDIAN_NUMBERING, true);
    }

    public boolean isReadMoneyEnabled() {
        return mPreferences.getBoolean(PREF_READ_MONEY, false);
    }

    public boolean isReadCodesEnabled() {
        return mPreferences.getBoolean(PREF_READ_CODES, false);
    }

    public boolean isPunctuationSoundsEnabled() {
        return mPreferences.getBoolean(PREF_PUNCTUATION_SOUNDS, false);
    }

    public boolean isPhrasePausesEnabled() {
        return mPreferences.getBoolean(PREF_PHRASE_PAUSES, false);
    }

    public boolean isExpandAbbreviationsEnabled() {
        return mPreferences.getBoolean(PREF_EXPAND_ABBREVIATIONS, false);
    }

    /** Beta, off by default: Hindi typed in Latin letters, read as Hindi (English voices). */
    public boolean isHinglishEnabled() {
        return mPreferences.getBoolean(PREF_HINGLISH, false);
    }

    /**
     * Default false: this tone-shaping chain was tuned by ear against a different synthesis
     * engine (see {@link AudioOptimizer}'s own doc comment) - worth trying, not assumed to suit
     * eSpeak's own formant timbre until actually confirmed on a real device.
     */
    public boolean isAudioOptimizerEnabled() {
        return !AUDIO_PROFILE_OFF.equals(getAudioOptimizerLevel());
    }

    /** {@link #AUDIO_PROFILE_OFF} or a profile, migrating the old toggle + profile on first read. */
    public String getAudioOptimizerLevel() {
        String level = mPreferences.getString(PREF_AUDIO_OPTIMIZER_LEVEL, null);
        if (level != null) return level;
        return mPreferences.getBoolean(PREF_AUDIO_OPTIMIZER, false)
                ? mPreferences.getString(PREF_AUDIO_PROFILE, AUDIO_PROFILE_BALANCED)
                : AUDIO_PROFILE_OFF;
    }

    public boolean isUserDictionaryEnabled() {
        return mPreferences.getBoolean(PREF_USER_DICTIONARY, true);
    }

    /** Migrates the old NATO toggle and the old "phonetic" reading mode on first read. */
    public String getPhoneticLetters() {
        String mode = mPreferences.getString(PREF_PHONETIC_LETTERS, null);
        if (mode != null) return mode;
        if (READING_PHONETIC.equals(getStoredReadingMode())) return PHONETIC_ALWAYS;
        return mPreferences.getBoolean(PREF_NATO_SPELLING, false) ? PHONETIC_CHARACTER : PHONETIC_OFF;
    }

    public boolean isSpokenDiacriticsEnabled() {
        return mPreferences.getBoolean(PREF_SPOKEN_DIACRITICS, true);
    }

    public String getDigitGroupingMode() {
        String mode = mPreferences.getString(PREF_DIGIT_GROUPING, null);
        if (mode == null) {
            // Migrate legacy boolean: digit-by-digit ON means single-digit grouping.
            return mPreferences.getBoolean(PREF_SPEAK_DIGITS, false)
                    ? DIGIT_GROUP_SINGLE : DIGIT_GROUP_OFF;
        }
        return mode;
    }

    public int getDigitGroupThreshold() {
        try {
            String raw = mPreferences.getString(PREF_DIGIT_GROUP_THRESHOLD, "7");
            if (raw == null) return 7;
            int v = Integer.parseInt(raw);
            if (v < 4) return 4;
            if (v > 12) return 12;
            return v;
        } catch (NumberFormatException e) {
            return 7;
        }
    }

    public boolean isSpellingModeEnabled() {
        return READING_SPELLING.equals(getReadingMode());
    }

    public boolean isCodeReadingModeEnabled() {
        return READING_CODE.equals(getReadingMode());
    }

    /**
     * Unified reading mode. Migrates the three legacy booleans on first read:
     * spelling wins over phonetic, code combines independently (mapped to code
     * when alone, else spelling &gt; phonetic &gt; code &gt; normal).
     */
    public String getReadingMode() {
        String mode = getStoredReadingMode();
        // Phonetic is now its own setting (getPhoneticLetters()).
        return READING_PHONETIC.equals(mode) ? READING_NORMAL : mode;
    }

    private String getStoredReadingMode() {
        String mode = mPreferences.getString(PREF_READING_MODE, null);
        if (mode != null) return mode;
        boolean spelling = mPreferences.getBoolean(PREF_SPELLING_MODE, false);
        boolean phonetic = mPreferences.getBoolean(PREF_PHONETIC_MODE, false);
        boolean code = mPreferences.getBoolean(PREF_CODE_READING_MODE, false);
        if (spelling) return READING_SPELLING;
        if (phonetic) return READING_PHONETIC;
        if (code) return READING_CODE;
        return READING_NORMAL;
    }

    /** Lock speech rate/pitch/volume to the saved values, ignoring caller apps. */
    public boolean isForceRateEnabled() {
        return mPreferences.getBoolean(PREF_FORCE_RATE, false);
    }

    public boolean isForcePitchEnabled() {
        return mPreferences.getBoolean(PREF_FORCE_PITCH, false);
    }

    /**
     * espeak-ng community issue #1658: widen the pitch rise on questions and
     * exclamations for listeners with hearing difficulty. Off by default -
     * changes the character of the intonation, so it's opt-in like the other
     * perceptible-audio toggles here.
     */
    public boolean isEmphasizeQuestionsEnabled() {
        return mPreferences.getBoolean(PREF_EMPHASIZE_QUESTIONS, false);
    }

    public boolean isForceVolumeEnabled() {
        return mPreferences.getBoolean(PREF_FORCE_VOLUME, false);
    }

    public String getAudioProfile() {
        String p = getAudioOptimizerLevel();
        if (AUDIO_PROFILE_GENTLE.equals(p) || AUDIO_PROFILE_FULL.equals(p)) return p;
        return AUDIO_PROFILE_BALANCED;
    }

    public String getRepeatedCharactersMode() {
        String mode = mPreferences.getString(PREF_REPEATED_CHARS, REPEATED_CHARS_OFF);
        if (REPEATED_CHARS_COUNT.equals(mode) || REPEATED_CHARS_TRUNCATE.equals(mode)) {
            return mode;
        }
        return REPEATED_CHARS_OFF;
    }

    public String getIntonationStyle() {
        String style = mPreferences.getString(PREF_INTONATION_STYLE, INTONATION_NATURAL);
        if (INTONATION_FLAT.equals(style) || INTONATION_EXPRESSIVE.equals(style)
                || INTONATION_CUSTOM.equals(style)) {
            return style;
        }
        return INTONATION_NATURAL;
    }

    /**
     * Raw espeakINTONATION group (0-7), used only when {@link #getIntonationStyle()}
     * is {@link #INTONATION_CUSTOM}. Groups 0, 1 and 3 are the ones this app
     * already exposes by name (Natural/Expressive/Flat); the remaining groups
     * (2, 4-7) are tone-mapping variants the engine ships internally but that
     * this app has never listened through and named - one of them (group 6)
     * is even labelled "test" in upstream's own default tone table - so they
     * are exposed as a raw number for advanced users to try rather than
     * guessing at descriptive names for behavior nobody here has verified.
     */
    public int getIntonationGroup() {
        int value = getPreferenceValue(PREF_INTONATION_GROUP, 0);
        if (value < 0) value = 0;
        if (value > 7) value = 7;
        return value;
    }

    public String getCapitalsScope() {
        String scope = mPreferences.getString(PREF_CAPITALS_SCOPE, CAPITALS_SCOPE_CHAR);
        if (CAPITALS_SCOPE_ALL.equals(scope)) {
            return scope;
        }
        return CAPITALS_SCOPE_CHAR;
    }

    public boolean isCapitalsScopeAll() {
        return CAPITALS_SCOPE_ALL.equals(getCapitalsScope());
    }

    public boolean isSimplifyUrlsEnabled() {
        return mPreferences.getBoolean(PREF_SIMPLIFY_URLS, false);
    }
}
