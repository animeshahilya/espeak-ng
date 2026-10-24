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

package com.animeshahilya.espeakng.ui;

import com.animeshahilya.espeakng.tts.AudioOptimizer;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.util.TolerantPreferences;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.VoiceVariant;

import android.content.SharedPreferences;


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
    /** @deprecated Legacy eyes-free-era digit toggle; migrated by {@link #getDigitGroupingMode}. */
    @Deprecated
    public static final String PREF_SPEAK_DIGITS = "espeak_speak_digits";
    public static final int RATE_BOOST_MULTIPLIER = 3;
    public static final int RATE_BOOST_MULTIPLIER_MIN = 2;
    public static final int RATE_BOOST_MULTIPLIER_MAX = 5;
    public static final String PREF_NORMALIZE_UNICODE = "espeak_normalize_unicode";
    public static final String PREF_EMOJI_PROCESSING = "espeak_emoji_processing";
    public static final String PREF_CAPITALS = "espeak_capitals";
    public static final String PREF_WORD_GAP = "espeak_wordgap";
    public static final String PREF_PAUSE_SCALE = "espeak_pause_scale";
    /** Which voices get lakh/crore and rupee reading: off, Indian voices or all voices. */
    public static final String PREF_INDIAN_NUMBERING_VOICES = "espeak_indian_numbering_voices";
    public static final String INDIAN_NUMBERING_OFF = "off";
    public static final String INDIAN_NUMBERING_INDIAN = "indian";
    public static final String INDIAN_NUMBERING_ALL = "all";
    /** @deprecated On/off toggle, now {@link #PREF_INDIAN_NUMBERING_VOICES}; kept as the migration source. */
    @Deprecated
    public static final String PREF_INDIAN_NUMBERING = "espeak_indian_numbering";
    // Opt-in extras beyond NVDA (new keys: the removed pre-parity toggles defaulted on).
    public static final String PREF_READ_MONEY = "espeak_read_money";
    public static final String PREF_READ_CODES = "espeak_read_codes";
    public static final String PREF_READ_DATES = "espeak_read_dates";
    public static final String PREF_READ_DIMENSIONS = "espeak_read_dimensions";
    public static final String PREF_READ_TEMPERATURES = "espeak_read_temperatures";
    public static final String PREF_READ_ROMAN_NUMERALS = "espeak_read_roman_numerals";
    public static final String PREF_READ_PHONE_NUMBERS = "espeak_read_phone_numbers";
    public static final String PREF_READ_MATH = "espeak_read_math";
    public static final String PREF_SPLIT_CAMEL_CASE = "espeak_split_camel_case";
    public static final String PREF_CLEAN_MARKDOWN = "espeak_clean_markdown";
    public static final String PREF_READ_FRACTIONS = "espeak_read_fractions";
    public static final String PREF_READ_SUB_SUPER = "espeak_read_sub_super";
    public static final String PREF_READ_ORDINALS = "espeak_read_ordinals";
    public static final String PREF_PUNCTUATION_SOUNDS = "espeak_punctuation_sounds";
    public static final String PREF_PHRASE_PAUSES = "espeak_phrase_pauses";
    /** A lone symbol in quotes is named ("press '_' to"), espeak-ng #150. */
    public static final String PREF_NAME_QUOTED_SYMBOLS = "espeak_name_quoted_symbols";
    /** Other apps (Tasker...) may change settings by broadcast: {@code tts.SettingsReceiver}. */
    public static final String PREF_ALLOW_OTHER_APPS = "espeak_allow_other_apps";
    public static final String PREF_EXPAND_ABBREVIATIONS = "espeak_expand_abbreviations";
    /** Language numbers and times are read in: {@link #NUMBERS_AUTO} and the other NUMBERS_ values. */
    public static final String PREF_NUMBERS_LANGUAGE = "espeak_numbers_language";
    /** Language switching sensitivity: {@link #SWITCHING_WORDS}, {@link #SWITCHING_PHRASES}, or {@link #SWITCHING_SENTENCES}. */
    public static final String PREF_SWITCHING_SENSITIVITY = "espeak_switching_sensitivity";
    public static final String SWITCHING_WORDS = "words";
    public static final String SWITCHING_PHRASES = "phrases";
    public static final String SWITCHING_SENTENCES = "sentences";
    /** Follow active IME keyboard subtype language for single-character typing echo. */
    public static final String PREF_FOLLOW_KEYBOARD_LANGUAGE = "espeak_follow_keyboard_language";
    /** Spanish, French, German, Italian and Portuguese phrases inside other text get their own voice. */
    public static final String PREF_DETECT_LATIN_LANGUAGES = "espeak_detect_latin_languages";
    /** The "Numbers in English text" switch it replaced; on reads as {@link #NUMBERS_AROUND}. */
    private static final String LEGACY_ENGLISH_NUMBERS = "espeak_english_numbers";
    /** As eSpeak reads them (the voice's language), and natural voices (the words around them). */
    public static final String NUMBERS_AUTO = "";
    /** The language of the words around them: numbers next to English words in English. */
    public static final String NUMBERS_AROUND = "around";
    /** Always the voice's own language, also inside words in another script. */
    public static final String NUMBERS_VOICE = "voice";
    /** Always English. */
    public static final String NUMBERS_ENGLISH = "en";
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
    public static final String PREF_OPTIMIZER_GLOTTAL_TILT = "espeak_opt_glottal_tilt";
    public static final String PREF_OPTIMIZER_SIBILANT_BYPASS = "espeak_opt_sibilant_bypass";
    public static final String PREF_OPTIMIZER_WARMTH = "espeak_opt_warmth";
    public static final String PREF_OPTIMIZER_PRESENCE = "espeak_opt_presence";
    public static final String PREF_OPTIMIZER_LEVELER = "espeak_opt_leveler";
    public static final String PREF_OPTIMIZER_LIMITER = "espeak_opt_limiter";
    public static final String PREF_OPTIMIZER_DECLICKER = "espeak_opt_declicker";
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
    public static final String PREF_NORMALIZE_TECH_WORDS = "espeak_normalize_tech_words";

    /** Repeated character handling modes. */
    public static final String REPEATED_CHARS_OFF = "off";
    public static final String REPEATED_CHARS_COUNT = "count";
    public static final String REPEATED_CHARS_TRUNCATE = "truncate";

    /** Intonation styles. */
    public static final String INTONATION_NATURAL = "natural";
    public static final String INTONATION_FLAT = "flat";
    public static final String INTONATION_EXPRESSIVE = "expressive";
    /** Suffix of the styles with clearer questions ("flat_questions"). */
    public static final String INTONATION_QUESTIONS = "_questions";

    /** Capital letter announcement scope. */
    public static final String CAPITALS_SCOPE_CHAR = "char_only";
    public static final String CAPITALS_SCOPE_ALL = "all";

    public static final String EMOJI_ANNOUNCE = "announce";
    public static final String EMOJI_IGNORE = "ignore";

    /** Digit grouping modes for long numbers. */
    public static final String DIGIT_GROUP_OFF = "off";
    public static final String DIGIT_GROUP_SINGLE = "single";
    public static final String DIGIT_GROUP_SINGLE_PAUSE = "single_pause";
    public static final String DIGIT_GROUP_DOUBLE = "double";
    public static final String DIGIT_GROUP_DOUBLE_PAUSE = "double_pause";
    public static final String DIGIT_GROUP_TRIPLE = "triple";
    public static final String DIGIT_GROUP_TRIPLE_PAUSE = "triple_pause";

    public static boolean isDigitGroupingWithPause(String mode) {
        return DIGIT_GROUP_SINGLE_PAUSE.equals(mode)
                || DIGIT_GROUP_DOUBLE_PAUSE.equals(mode)
                || DIGIT_GROUP_TRIPLE_PAUSE.equals(mode);
    }

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
    public static final String AUDIO_PROFILE_CUSTOM = "custom";


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

    /** The app this request is for (Speech by app), or null. */
    private String mCaller;

    public VoiceSettings(SharedPreferences preferences, SpeechSynthesis engine) {
        mPreferences = TolerantPreferences.of(preferences);
        mEngine = engine;
    }

    /** Applies that app's card ({@link AppCards}) to this request's settings; null for none. */
    public void setCaller(String packageName) {
        mCaller = packageName;
    }

    /** The calling app's speed, pitch or volume as a factor of the main setting: 1 without a card. */
    public float appFactor(String field) {
        return mCaller == null ? 1f : AppCards.factor(mPreferences, mCaller, field);
    }

    /** True when the calling app's card keeps natural voices out of its speech. */
    public boolean isAppEspeakOnly() {
        return mCaller != null && AppCards.VOICES_ESPEAK.equals(AppCards.voices(mPreferences, mCaller));
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
        final String app = appPunctuation();
        if (!app.isEmpty()) {
            return PUNCTUATION_PRESET_ALL.equals(app) ? SpeechSynthesis.PUNCT_ALL
                    : PUNCTUATION_PRESET_NONE.equals(app) ? SpeechSynthesis.PUNCT_NONE
                    : SpeechSynthesis.PUNCT_SOME;
        }
        return clamp(mEngine.Punctuation, getPreferenceValue(PREF_PUNCTUATION_LEVEL, mEngine.Punctuation));
    }

    public String getPunctuationCharacters() {
        final String app = appPunctuation();
        if (!app.isEmpty()) {
            return PUNCTUATION_PRESET_SOME.equals(app) ? PUNCTUATION_CHARS_SOME
                    : PUNCTUATION_PRESET_MOST.equals(app) ? PUNCTUATION_CHARS_MOST : null;
        }
        return mPreferences.getString(PREF_PUNCTUATION_CHARACTERS, null);
    }

    /** The calling app's punctuation preset (none/some/most/all), "" for the main setting. */
    private String appPunctuation() {
        return mCaller == null ? "" : AppCards.punctuation(mPreferences, mCaller);
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
        return boostMultiplier(getRateBoostLevel());
    }

    /** The multiplier a stored {@link #PREF_RATE_BOOST_LEVEL} value gives. */
    public static int boostMultiplier(String level) {
        try {
            return clampBoost(Integer.parseInt(level));
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

    /** {@link #INDIAN_NUMBERING_OFF}, _INDIAN or _ALL, migrating the old toggle on first read. */
    public String getIndianNumberingVoices() {
        String voices = mPreferences.getString(PREF_INDIAN_NUMBERING_VOICES, null);
        if (voices != null) return voices;
        return mPreferences.getBoolean(PREF_INDIAN_NUMBERING, true)
                ? INDIAN_NUMBERING_INDIAN : INDIAN_NUMBERING_OFF;
    }

    public boolean isReadMoneyEnabled() {
        return mPreferences.getBoolean(PREF_READ_MONEY, false);
    }

    public boolean isReadCodesEnabled() {
        return mPreferences.getBoolean(PREF_READ_CODES, false);
    }

    public boolean isReadDatesEnabled() {
        return mPreferences.getBoolean(PREF_READ_DATES, false);
    }

    public boolean isReadDimensionsEnabled() {
        return mPreferences.getBoolean(PREF_READ_DIMENSIONS, false);
    }

    public boolean isReadTemperaturesEnabled() {
        return mPreferences.getBoolean(PREF_READ_TEMPERATURES, false);
    }

    public boolean isReadRomanNumeralsEnabled() {
        return mPreferences.getBoolean(PREF_READ_ROMAN_NUMERALS, false);
    }

    public boolean isReadPhoneNumbersEnabled() {
        return mPreferences.getBoolean(PREF_READ_PHONE_NUMBERS, false);
    }

    public boolean isReadMathEnabled() {
        return mPreferences.getBoolean(PREF_READ_MATH, false);
    }

    public boolean isSplitCamelCaseEnabled() {
        return mPreferences.getBoolean(PREF_SPLIT_CAMEL_CASE, false);
    }

    public boolean isCleanMarkdownEnabled() {
        return mPreferences.getBoolean(PREF_CLEAN_MARKDOWN, false);
    }

    public boolean isReadFractionsEnabled() {
        return mPreferences.getBoolean(PREF_READ_FRACTIONS, false);
    }

    public boolean isReadSubSuperEnabled() {
        return mPreferences.getBoolean(PREF_READ_SUB_SUPER, false);
    }

    public boolean isReadOrdinalsEnabled() {
        return mPreferences.getBoolean(PREF_READ_ORDINALS, false);
    }

    public boolean isPunctuationSoundsEnabled() {
        return mPreferences.getBoolean(PREF_PUNCTUATION_SOUNDS, false);
    }

    public boolean isNameQuotedSymbolsEnabled() {
        return mPreferences.getBoolean(PREF_NAME_QUOTED_SYMBOLS, false);
    }

    public boolean isPhrasePausesEnabled() {
        return mPreferences.getBoolean(PREF_PHRASE_PAUSES, false);
    }

    public boolean isExpandAbbreviationsEnabled() {
        return mPreferences.getBoolean(PREF_EXPAND_ABBREVIATIONS, false);
    }

    /**
     * The numbers setting for text in {@code language} (ISO 639-2): its
     * language card's choice (Speech by language), else the main one.
     */
    public String getNumbersLanguage(String language) {
        final String card = LanguageCards.numbers(mPreferences, language);
        if (card.isEmpty()) {
            return getNumbersLanguage();
        }
        return LanguageCards.NUMBERS_AUTO.equals(card) ? NUMBERS_AUTO : card;
    }

    /** See {@link #PREF_NUMBERS_LANGUAGE}. */
    public String getNumbersLanguage() {
        final String value = mPreferences.getString(PREF_NUMBERS_LANGUAGE, null);
        if (value == null) {
            return mPreferences.getBoolean(LEGACY_ENGLISH_NUMBERS, false) ? NUMBERS_AROUND : NUMBERS_AUTO;
        }
        return value;
    }

    /** Beta, off by default: Hindi typed in Latin letters, read as Hindi. */
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

    public boolean isOptimizerGlottalTiltEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_GLOTTAL_TILT, true);
    }

    public boolean isOptimizerSibilantBypassEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_SIBILANT_BYPASS, true);
    }

    public boolean isOptimizerWarmthEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_WARMTH, true);
    }

    public boolean isOptimizerPresenceEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_PRESENCE, true);
    }

    public boolean isOptimizerLevelerEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_LEVELER, true);
    }

    public boolean isOptimizerLimiterEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_LIMITER, true);
    }

    public boolean isOptimizerDeclickerEnabled() {
        return mPreferences.getBoolean(PREF_OPTIMIZER_DECLICKER, true);
    }

    public AudioOptimizer createAudioOptimizer(int sampleRateHz, int speechRateWpm, boolean isSingleCharUtterance) {
        if (!isAudioOptimizerEnabled() || sampleRateHz <= 0) {
            return null;
        }
        final String profile = getAudioOptimizerLevel();
        final boolean glottal = isOptimizerGlottalTiltEnabled();
        final boolean sibilant = isOptimizerSibilantBypassEnabled();
        final boolean presence = isOptimizerPresenceEnabled();
        final boolean warmth = isOptimizerWarmthEnabled();
        final boolean declicker = isOptimizerDeclickerEnabled();
        final boolean leveler = isOptimizerLevelerEnabled();
        final boolean limiter = isOptimizerLimiterEnabled();

        return new AudioOptimizer(sampleRateHz, profile, glottal, sibilant,
                presence, warmth, declicker, leveler, limiter, speechRateWpm, isSingleCharUtterance);
    }

    public AudioOptimizer createAudioOptimizer(int sampleRateHz) {
        return createAudioOptimizer(sampleRateHz, 175, false);
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
            String raw = mPreferences.getString(PREF_DIGIT_GROUP_THRESHOLD, "6");
            if (raw == null) return 6;
            int v = Integer.parseInt(raw);
            if (v < 4) return 4;
            if (v > 12) return 12;
            return v;
        } catch (NumberFormatException e) {
            return 6;
        }
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
     * The intonation choice's "clearer questions" (espeak-ng #1658): a wider
     * pitch rise on questions and exclamations, for hard-of-hearing or fast
     * listeners. Was its own switch until 2026-10-21.
     */
    public boolean isEmphasizeQuestionsEnabled() {
        return mPreferences.getString(PREF_INTONATION_STYLE, "").endsWith(INTONATION_QUESTIONS);
    }

    public boolean isForceVolumeEnabled() {
        return mPreferences.getBoolean(PREF_FORCE_VOLUME, false);
    }

    public String getRepeatedCharactersMode() {
        String mode = mPreferences.getString(PREF_REPEATED_CHARS, REPEATED_CHARS_OFF);
        if (REPEATED_CHARS_COUNT.equals(mode) || REPEATED_CHARS_TRUNCATE.equals(mode)) {
            return mode;
        }
        return REPEATED_CHARS_OFF;
    }

    /** Natural, Flat or Expressive, without the "clearer questions" part. */
    public String getIntonationStyle() {
        String style = mPreferences.getString(PREF_INTONATION_STYLE, INTONATION_NATURAL);
        if (style.endsWith(INTONATION_QUESTIONS)) {
            style = style.substring(0, style.length() - INTONATION_QUESTIONS.length());
        }
        if (INTONATION_FLAT.equals(style) || INTONATION_EXPRESSIVE.equals(style)) {
            return style;
        }
        return INTONATION_NATURAL;
    }

    /** {@code style} (null for Natural) with clearer questions. */
    public static String withClearerQuestions(String style) {
        final String base = INTONATION_FLAT.equals(style) || INTONATION_EXPRESSIVE.equals(style)
                ? style : INTONATION_NATURAL;
        return base + INTONATION_QUESTIONS;
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

    public boolean isNormalizeTechWordsEnabled() {
        return mPreferences.getBoolean(PREF_NORMALIZE_TECH_WORDS, false);
    }

    public boolean isFollowKeyboardLanguage() {
        return mPreferences.getBoolean(PREF_FOLLOW_KEYBOARD_LANGUAGE, false);
    }

    public static boolean isFollowKeyboardLanguage(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_FOLLOW_KEYBOARD_LANGUAGE, false);
    }

    public static boolean isDetectLatinLanguages(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_DETECT_LATIN_LANGUAGES, false);
    }

    // Sleep timer: reading apps stop at a chosen time.
    //
    // A TTS engine cannot "stop after the current book" the way a player app
    // can: every utterance arrives as a fresh client request. Once the time
    // has come, requests from other apps fail (TtsService), so a book reader
    // stops on its page instead of racing through silent ones; the screen
    // reader and this app's own speech always go on. It ends by itself after
    // SLEEP_HOLD_MS, and the settings screen offers Off to end it early.
    public static final String PREF_SLEEP_TIMER = "espeak_sleep_timer";
    public static final String SLEEP_OFF = "off";
    /** When reading apps stop (millis), set by the sleep timer. Not a UI row. */
    public static final String PREF_SLEEP_STOP_AT = "espeak_sleep_stop_at";
    public static final int SLEEP_MAX_MINUTES = 60;
    /** How long reading apps stay stopped before the timer ends by itself. */
    public static final long SLEEP_HOLD_MS = 6 * 60 * 60_000L;

    /**
     * The sleep timer's stop time, or 0 when none is running: never set,
     * over (stopped longer than {@link #SLEEP_HOLD_MS}), or further out than
     * any timer can set (the clock was moved back, or a backup carried it).
     */
    public static long sleepStopAt(SharedPreferences prefs, long now) {
        final long at = prefs.getLong(PREF_SLEEP_STOP_AT, 0);
        return at > 0 && at - now <= (SLEEP_MAX_MINUTES + 1) * 60000L && now - at < SLEEP_HOLD_MS ? at : 0;
    }

    /**
     * True once the timer's time has come: reading apps stay silent until it
     * is turned off or {@link #SLEEP_HOLD_MS} passes. The screen reader and
     * this app's own speech never stop (TtsService).
     */
    public static boolean isSleepStopped(SharedPreferences prefs) {
        final long now = System.currentTimeMillis();
        final long at = sleepStopAt(prefs, now);
        return at > 0 && now >= at;
    }

    /** Reading apps stop {@code minutes} from now; returns that time. */
    public static long armSleepTimer(SharedPreferences prefs, int minutes) {
        final long at = System.currentTimeMillis() + Math.max(1, minutes) * 60000L;
        prefs.edit().putLong(PREF_SLEEP_STOP_AT, at).apply();
        return at;
    }

    public static void clearSleepTimer(SharedPreferences prefs) {
        prefs.edit().remove(PREF_SLEEP_STOP_AT).apply();
    }

    // What's-new dialog: last version code the user has seen. Not a UI row.
    public static final String PREF_WHATS_NEW_SEEN = "espeak_whats_new_seen";
}
