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

package com.reecedunn.espeak;

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
    public static final String PREF_SPEAK_DIGITS = "espeak_speak_digits";
    public static final int RATE_BOOST_MULTIPLIER = 3;
    public static final String PREF_NORMALIZE_UNICODE = "espeak_normalize_unicode";
    public static final String PREF_EMOJI_PROCESSING = "espeak_emoji_processing";
    public static final String PREF_CAPITALS = "espeak_capitals";
    public static final String PREF_WORD_GAP = "espeak_wordgap";
    public static final String PREF_INDIAN_NUMBERING = "espeak_indian_numbering";
    public static final String PREF_SPEAK_PROGRAMMING_SYMBOLS = "espeak_speak_programming_symbols";
    public static final String PREF_SMART_CODES = "espeak_smart_codes";
    public static final String PREF_USER_DICTIONARY = "espeak_user_dictionary";
    public static final String PREF_BILINGUAL_SWITCHING = "espeak_bilingual_switching";
    public static final String PREF_SECONDARY_VOICE = "espeak_secondary_voice";
    public static final String PREF_NATO_SPELLING = "espeak_nato_spelling";
    public static final String PREF_SPOKEN_DIACRITICS = "espeak_spoken_diacritics";

    public static final String EMOJI_ANNOUNCE = "announce";
    public static final String EMOJI_IGNORE = "ignore";

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

    public static final String DEFAULT_VARIANT_NVDA = "max";
    public static final int DEFAULT_PITCH_NVDA = 40;
    public static final int DEFAULT_PITCH_RANGE_NVDA = 75;
    public static final int DEFAULT_CAPITALS_NVDA = 3;

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
            return VoiceVariant.parseVoiceVariant(DEFAULT_VARIANT_NVDA);
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
                pitch = DEFAULT_PITCH_NVDA;
            }
        }

        if (pitch > max) pitch = max;
        if (pitch < min) pitch = min;
        return pitch;
    }

    public int getPitchRange() {
        int min = mEngine.PitchRange.getMinValue();
        int max = mEngine.PitchRange.getMaxValue();

        int range = getPreferenceValue(PREF_PITCH_RANGE, DEFAULT_PITCH_RANGE_NVDA);
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
        int value = getPreferenceValue(PREF_CAPITALS, DEFAULT_CAPITALS_NVDA);
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
     * When enabled, "123" is spoken as "one two three" (or equivalent in
     * the active language) by inserting spaces between digits. Off by
     * default so that numbers like "123" are read as "one hundred twenty
     * three".
     */
    public boolean isSpeakDigitsEnabled() {
        return mPreferences.getBoolean(PREF_SPEAK_DIGITS, false);
    }

    public boolean isIndianNumberingEnabled() {
        return mPreferences.getBoolean(PREF_INDIAN_NUMBERING, true);
    }

    public boolean isSpeakProgrammingSymbolsEnabled() {
        return mPreferences.getBoolean(PREF_SPEAK_PROGRAMMING_SYMBOLS, true);
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
        return mPreferences.getString(PREF_SECONDARY_VOICE, "en-in");
    }

    public boolean isNatoSpellingEnabled() {
        return mPreferences.getBoolean(PREF_NATO_SPELLING, false);
    }

    public boolean isSpokenDiacriticsEnabled() {
        return mPreferences.getBoolean(PREF_SPOKEN_DIACRITICS, true);
    }
}
