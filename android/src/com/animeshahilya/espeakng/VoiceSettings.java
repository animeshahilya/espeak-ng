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
    public static final String PREF_RATE_BOOST = "espeak_rate_boost";
    public static final String PREF_UNICODE_NORMALIZATION = "espeak_unicode_normalization";
    /** @deprecated Legacy eyes-free-era digit toggle; see {@link #isSpeakDigitsEnabled}. */
    @Deprecated
    public static final String PREF_SPEAK_DIGITS = "espeak_speak_digits";
    public static final int RATE_BOOST_MULTIPLIER = 3;
    public static final String PREF_NORMALIZE_UNICODE = "espeak_normalize_unicode";
    public static final String PREF_EMOJI_PROCESSING = "espeak_emoji_processing";
    public static final String PREF_CAPITALS = "espeak_capitals";
    public static final String PREF_EMPHASIZE_QUESTIONS = "espeak_emphasize_questions";
    public static final String PREF_WORD_GAP = "espeak_wordgap";
    public static final String PREF_INDIAN_NUMBERING = "espeak_indian_numbering";
    public static final String PREF_SPEAK_PROGRAMMING_SYMBOLS = "espeak_speak_programming_symbols";
    public static final String PREF_SMART_CODES = "espeak_smart_codes";
    public static final String PREF_USER_DICTIONARY = "espeak_user_dictionary";
    public static final String PREF_BILINGUAL_SWITCHING = "espeak_bilingual_switching";
    public static final String PREF_SECONDARY_VOICE = "espeak_secondary_voice";
    public static final String PREF_NATO_SPELLING = "espeak_nato_spelling";
    public static final String PREF_SPOKEN_DIACRITICS = "espeak_spoken_diacritics";
    public static final String PREF_AUDIO_OPTIMIZER = "espeak_audio_optimizer";
    // Smart text processing (digit grouping, time/date, currency, specialized modes).
    public static final String PREF_DIGIT_GROUPING = "espeak_digit_grouping";
    public static final String PREF_DIGIT_GROUP_THRESHOLD = "espeak_digit_group_threshold";
    public static final String PREF_TIME_DATE = "espeak_time_date";
    public static final String PREF_CURRENCY = "espeak_currency";
    /** @deprecated Legacy single-mode booleans; migrated by {@link #getReadingMode}. Keys kept. */
    @Deprecated
    public static final String PREF_SPELLING_MODE = "espeak_spelling_mode";
    /** @deprecated See {@link #PREF_SPELLING_MODE}. */
    @Deprecated
    public static final String PREF_PHONETIC_MODE = "espeak_phonetic_mode";
    /** @deprecated See {@link #PREF_SPELLING_MODE}. */
    @Deprecated
    public static final String PREF_CODE_READING_MODE = "espeak_code_reading";
    // Deep-tuning: force overrides, optimizer intensity, smart-code window, unified reading mode.
    public static final String PREF_FORCE_RATE = "espeak_force_rate";
    public static final String PREF_FORCE_PITCH = "espeak_force_pitch";
    public static final String PREF_FORCE_VOLUME = "espeak_force_volume";
    public static final String PREF_AUDIO_PROFILE = "espeak_audio_profile";
    public static final String PREF_SMART_MIN_LEN = "espeak_smart_min_len";
    public static final String PREF_SMART_MAX_LEN = "espeak_smart_max_len";
    public static final String PREF_READING_MODE = "espeak_reading_mode";

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
    public static final String READING_PHONETIC = "phonetic";
    public static final String READING_CODE = "code";

    /** Audio optimizer intensity profiles. */
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

    /** Default bilingual secondary voice (Indian English). */
    public static final String DEFAULT_SECONDARY_VOICE = "en-in";

    public VoiceSettings(SharedPreferences preferences, SpeechSynthesis engine) {
        mPreferences = preferences;
        mEngine = engine;
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
            rate = rate * RATE_BOOST_MULTIPLIER;
            int boostedMax = max * RATE_BOOST_MULTIPLIER; // keep within a sensible upper bound
            if (rate > boostedMax) rate = boostedMax;
        } else if (rate > max) {
            rate = max;
        }

        if (rate < min) rate = min;
        return rate;
    }

    public int getPitch() {
        int min = mEngine.Pitch.getMinValue();
        int max = mEngine.Pitch.getMaxValue();

        int pitch = getPreferenceValue(PREF_PITCH, Integer.MIN_VALUE);
        if (pitch == Integer.MIN_VALUE) {
            if (mPreferences.contains(PREF_DEFAULT_PITCH)) {
                pitch = getPreferenceValue(PREF_DEFAULT_PITCH, 100) / 2;
            } else {
                pitch = DEFAULT_PITCH;
            }
        }

        if (pitch > max) pitch = max;
        if (pitch < min) pitch = min;
        return pitch;
    }

    public int getPitchRange() {
        int min = mEngine.PitchRange.getMinValue();
        int max = mEngine.PitchRange.getMaxValue();

        int range = getPreferenceValue(PREF_PITCH_RANGE, DEFAULT_PITCH_RANGE);
        if (range > max) range = max;
        if (range < min) range = min;
        return range;
    }

    public int getVolume() {
        int min = mEngine.Volume.getMinValue();
        int max = mEngine.Volume.getMaxValue();

        int range = getPreferenceValue(PREF_VOLUME, mEngine.Volume.getDefaultValue());
        if (range > max) range = max;
        if (range < min) range = min;
        return range;
    }

    public int getPunctuationLevel() {
        int min = mEngine.Punctuation.getMinValue();
        int max = mEngine.Punctuation.getMaxValue();

        int level = getPreferenceValue(PREF_PUNCTUATION_LEVEL, mEngine.Punctuation.getDefaultValue());
        if (level > max) level = max;
        if (level < min) level = min;
        return level;
    }

    public String getPunctuationCharacters() {
        return mPreferences.getString(PREF_PUNCTUATION_CHARACTERS, null);
    }

    private int getPreferenceValue(String preference, int defaultValue) {
        String prefString = mPreferences.getString(preference, null);
        if (prefString == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(prefString);
        } catch (NumberFormatException e) {
            // A malformed value here (e.g. hand-edited prefs) would otherwise
            // crash every synthesis request through this method, since
            // getRate()/getPitch()/etc. all route through it.
            return defaultValue;
        }
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

    public boolean isRateBoostEnabled() {
        return mPreferences.getBoolean(PREF_RATE_BOOST, false);
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
        int min = mEngine.Capitals.getMinValue();
        int max = mEngine.Capitals.getMaxValue();
        int value = getPreferenceValue(PREF_CAPITALS, DEFAULT_CAPITALS);
        if (value > max) value = max;
        if (value < min) value = min;
        return value;
    }

    public int getWordGap() {
        int min = mEngine.WordGap.getMinValue();
        int max = mEngine.WordGap.getMaxValue();
        int value = getPreferenceValue(PREF_WORD_GAP, mEngine.WordGap.getDefaultValue());
        if (value > max) value = max;
        if (value < min) value = min;
        return value;
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

    public boolean isSpeakProgrammingSymbolsEnabled() {
        return mPreferences.getBoolean(PREF_SPEAK_PROGRAMMING_SYMBOLS, true);
    }

    /**
     * Default false: this tone-shaping chain was tuned by ear against a different synthesis
     * engine (see {@link AudioOptimizer}'s own doc comment) - worth trying, not assumed to suit
     * eSpeak's own formant timbre until actually confirmed on a real device.
     */
    public boolean isAudioOptimizerEnabled() {
        return mPreferences.getBoolean(PREF_AUDIO_OPTIMIZER, false);
    }

    public boolean isSmartCodesEnabled() {
        return mPreferences.getBoolean(PREF_SMART_CODES, true);
    }

    public boolean isUserDictionaryEnabled() {
        return mPreferences.getBoolean(PREF_USER_DICTIONARY, true);
    }

    public boolean isBilingualSwitchingEnabled() {
        return mPreferences.getBoolean(PREF_BILINGUAL_SWITCHING, true);
    }

    public String getSecondaryVoice() {
        return mPreferences.getString(PREF_SECONDARY_VOICE, DEFAULT_SECONDARY_VOICE);
    }

    public boolean isNatoSpellingEnabled() {
        return mPreferences.getBoolean(PREF_NATO_SPELLING, false);
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

    public boolean isTimeDateEnabled() {
        return mPreferences.getBoolean(PREF_TIME_DATE, true);
    }

    public boolean isCurrencyEnabled() {
        return mPreferences.getBoolean(PREF_CURRENCY, true);
    }

    public boolean isSpellingModeEnabled() {
        return READING_SPELLING.equals(getReadingMode());
    }

    public boolean isPhoneticModeEnabled() {
        return READING_PHONETIC.equals(getReadingMode());
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
        String p = mPreferences.getString(PREF_AUDIO_PROFILE, AUDIO_PROFILE_BALANCED);
        if (AUDIO_PROFILE_GENTLE.equals(p) || AUDIO_PROFILE_FULL.equals(p)) return p;
        return AUDIO_PROFILE_BALANCED;
    }

    public int getSmartMinLen() {
        try {
            String raw = mPreferences.getString(PREF_SMART_MIN_LEN, "4");
            if (raw == null) return 4;
            int v = Integer.parseInt(raw);
            if (v < 2) return 2;
            if (v > 8) return 8;
            return v;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    public int getSmartMaxLen() {
        try {
            String raw = mPreferences.getString(PREF_SMART_MAX_LEN, "8");
            if (raw == null) return 8;
            int v = Integer.parseInt(raw);
            if (v < getSmartMinLen()) return getSmartMinLen();
            if (v > 12) return 12;
            return v;
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
