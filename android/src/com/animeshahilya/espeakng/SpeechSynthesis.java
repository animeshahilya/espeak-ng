/*
 * Copyright (C) 2012-2015 Reece H. Dunn
 * Copyright (C) 2011 Google Inc.
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

/*
 * This file implements the Java API to eSpeak using the JNI bindings.
 *
 * Minimum Android Version: 8.0 (Oreo)
 * Minimum API Version:     26
 */

package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

public class SpeechSynthesis {
    private static final String TAG = SpeechSynthesis.class.getSimpleName();

    // Obscure/redundant variants, alternate-script transliteration modes (not a distinct spoken
    // accent - the same category as English's own Shavian-alphabet entry below), and internal
    // test-only voices, filtered from the UI to show genuinely distinct, real languages only.
    // Both original-case and lowercased forms are listed since the native voice name's case isn't
    // guaranteed consistent with the lang file's own filename casing.
    public static final Set<String> REDUNDANT_VOICE_NAMES = new HashSet<String>(Arrays.asList(
            "en-029", "en-GB-x-gbclan", "en-GB-x-gbcwmd", "en-GB-x-rp", "en-Shaw", "en-US-nyc",
            "en-gb-x-gbclan", "en-gb-x-gbcwmd", "en-gb-x-rp", "en-shaw", "en-us-nyc", "en-sg",
            "fa-Latn", "fa-latn", "cmn-Latn-pinyin", "cmn-latn-pinyin",
            "yue-Latn-jyutping", "yue-latn-jyutping", "xex"
    ));

    // Cached voice list to avoid repeated native calls and Locale construction.
    // Immutable snapshot; callers get a defensive copy so nobody can mutate it.
    private static volatile List<Voice> sCachedVoices = null;

    // Process-wide lock protecting native synthesis calls. The underlying C engine
    // maintains process-global state and is not re-entrant. stop() intentionally does
    // not acquire this lock so it can immediately signal native abort without waiting.
    private static final Object sSynthLock = new Object();

    public static final int GENDER_UNSPECIFIED = 0;
    public static final int GENDER_MALE = 1;
    public static final int GENDER_FEMALE = 2;

    public static final int AGE_ANY = 0;
    public static final int AGE_YOUNG = 12;
    public static final int AGE_OLD = 60;

    public static final int CHANNEL_COUNT_MONO = 1;
    public static final int FORMAT_PCM_S16 = 2;

    static {
        System.loadLibrary("ttsespeak");

        nativeClassInit();
    }

    private final Context mContext;
    private final SynthReadyCallback mCallback;
    private final String mDatapath;

    private boolean mInitialized = false;
    private int mVoiceCount = 0;
    private static volatile int sSampleRate = 0;
    private int mSampleRate = 0;

    public SpeechSynthesis(Context context, SynthReadyCallback callback) {
        // First, ensure the data directory exists, otherwise init will crash.
        final File dataPath = CheckVoiceData.getDataPath(context);

        if (!dataPath.exists()) {
            Log.e(TAG, "Missing voice data");
            dataPath.mkdirs();
        }

        mContext = context;
        mCallback = callback;
        mDatapath = dataPath.getParentFile().getPath();

        attemptInit();
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannelCount() {
        return CHANNEL_COUNT_MONO;
    }

    public int getAudioFormat() {
        return FORMAT_PCM_S16;
    }

    private Locale getLocaleFromLanguageName(String name) {
        if (mLocaleFixes.containsKey(name)) {
            return mLocaleFixes.get(name);
        }
        // Manual split: String.split compiles a regex on every call, and this
        // runs once per voice (~120x) on each engine init.
        final int first = name.indexOf('-');
        if (first < 0) {
            return new Locale(name); // language
        }
        final int second = name.indexOf('-', first + 1);
        if (second < 0) {
            return new Locale(name.substring(0, first), name.substring(first + 1)); // language-country
        }
        final int third = name.indexOf('-', second + 1);
        if (third < 0) {
            return new Locale(name.substring(0, first), name.substring(first + 1, second),
                    name.substring(second + 1)); // language-country-variant
        }
        // language-country-x-privateuse: skip the "x", keep the private-use tag.
        // Anything longer (a fifth dash part) is rejected, matching the old
        // split("-") switch which only accepted up to 4 parts.
        final int fourth = name.indexOf('-', third + 1);
        if (fourth >= 0 && name.indexOf('-', fourth + 1) < 0) {
            return new Locale(name.substring(0, first), name.substring(first + 1, second),
                    name.substring(fourth + 1));
        }
        return null;
    }

    /** Case-insensitive redundant-voice check (native name casing is inconsistent). */
    private static boolean isRedundantVoice(String name) {
        if (name == null) {
            return true;
        }
        if (REDUNDANT_VOICE_NAMES.contains(name)) {
            return true;
        }
        return REDUNDANT_VOICE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    public List<Voice> getAvailableVoices() {
        // Return a copy of the cached voices if available.
        List<Voice> cached = sCachedVoices;
        if (cached != null) {
            return new ArrayList<Voice>(cached);
        }

        synchronized (SpeechSynthesis.class) {
            cached = sCachedVoices;
            if (cached != null) {
                return new ArrayList<Voice>(cached);
            }

            final String[] results = nativeGetAvailableVoices();
            final List<Voice> voices = new ArrayList<Voice>();
            if (results != null) {
                mVoiceCount = results.length / 4;
                for (int i = 0; i + 3 < results.length; i += 4) {
                    final String name = results[i];
                    final String identifier = results[i + 1];
                    if (name == null || identifier == null || results[i + 2] == null
                            || results[i + 3] == null) {
                        continue;
                    }
                    final int gender;
                    final int age;
                    try {
                        gender = Integer.parseInt(results[i + 2]);
                        age = Integer.parseInt(results[i + 3]);
                    } catch (NumberFormatException e) {
                        Log.d(TAG, "getAvailableResources: skipping " + name + " => bad number");
                        continue;
                    }

                    if (isRedundantVoice(name)) {
                        continue;
                    }

                    try {
                        final Locale locale;
                        if (identifier.equals("asia/fa-en-us")) {
                            throw new IllegalArgumentException("Voice '" + identifier + "' is a duplicate voice.");
                        } else {
                            locale = getLocaleFromLanguageName(name);
                            if (locale == null) {
                                throw new IllegalArgumentException("Locale not supported.");
                            }
                        }

                        String language = locale.getISO3Language();
                        if (language.equals("")) {
                            throw new IllegalArgumentException("Language '" + locale.getLanguage() + "' not supported.");
                        }

                        String country  = locale.getISO3Country();
                        if (country.equals("") && !locale.getCountry().equals("")) {
                            throw new IllegalArgumentException("Country '" + locale.getCountry() + "' not supported.");
                        }

                        final Voice voice = new Voice(name, identifier, gender, age, locale);
                        voices.add(voice);
                    } catch (MissingResourceException | IllegalArgumentException e) {
                        Log.d(TAG, "getAvailableResources: skipping " + name + " => " + e.getMessage());
                    } catch (Exception e) {
                        Log.w(TAG, "getAvailableResources: unexpected error loading " + name + ": " + e.getMessage());
                    }
                }
            }

            // Cache the filtered voice list as an immutable snapshot - but
            // never an empty one: empty means the voice data isn't extracted
            // yet, and caching it would stick until the next engine reload.
            if (!voices.isEmpty()) {
                sCachedVoices = Collections.unmodifiableList(voices);
            }
            return new ArrayList<Voice>(voices);
        }
    }

    /** Clear cached voice list (call when voice data changes, e.g., after extraction). */
    public static void clearVoiceCache() {
        sCachedVoices = null;
    }

    public void setVoice(Voice voice, VoiceVariant variant) {
        if (voice == null) {
            return;
        }
        synchronized (sSynthLock) {
            // NOTE: espeak_SetVoiceByProperties does not support specifying the
            // voice variant (e.g. klatt), but espeak_SetVoiceByName does.
            if (variant == null || variant.variant == null) {
                final int gender = (variant != null) ? variant.gender : GENDER_UNSPECIFIED;
                final int age = (variant != null) ? variant.age : AGE_ANY;
                nativeSetVoiceByProperties(voice.name, gender, age);
            } else {
                nativeSetVoiceByName(voice.identifier + "+" + variant.variant);
            }
        }
    }

    public void setPunctuationCharacters(String characters) {
        if (characters == null) return;
        nativeSetPunctuationCharacters(characters);
    }

    /** Don't announce any punctuation characters. */
    public static final int PUNCT_NONE = 0;

    /** Announce every punctuation character. */
    public static final int PUNCT_ALL = 1;

    /** Announce some of the punctuation characters. */
    public static final int PUNCT_SOME = 2;

    public enum UnitType {
        Percentage,
        WordsPerMinute,
        /** One of the PUNCT_* constants. */
        Punctuation,
    }

    public class Parameter {
        private final int id;
        private final int min;
        private final int max;
        private final UnitType unitType;

        private Parameter(int id, int min, int max, UnitType unitType) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.unitType = unitType;
        }

        public int getMinValue() {
            return min;
        }

        public int getMaxValue() {
            return max;
        }

        public int getDefaultValue() {
            return nativeGetParameter(id, 0);
        }

        public int getValue() {
            return nativeGetParameter(id, 1);
        }

        public void setValue(int value, int scale) {
            setValue((value * scale) / 100);
        }

        public void setValue(int value) {
            nativeSetParameter(id, value);
        }

        public UnitType getUnitType() {
            return unitType;
        }
    }

    /** Speech rate. */
    public final Parameter Rate = new Parameter(1, 80, 450, UnitType.WordsPerMinute);

    /** Audio volume. */
    public final Parameter Volume = new Parameter(2, 0, 200, UnitType.Percentage);

    /** Base pitch. */
    public final Parameter Pitch = new Parameter(3, 0, 100, UnitType.Percentage);

    /** Pitch range (monotone = 0). */
    public final Parameter PitchRange = new Parameter(4, 0, 100, UnitType.Percentage);

    /** Which punctuation characters to announce. */
    public final Parameter Punctuation = new Parameter(5, 0, 2, UnitType.Punctuation);

    /**
     * Capital letters indicator: 0=none, 1=sound icon, 2=spell out ("capital"),
     * 3 or higher=raise the word's pitch by that many Hz. The engine has no
     * fixed ceiling on the Hz amount; 60 is a generous but sane UI limit -
     * well past where a raise stops reading as emphasis and starts sounding
     * like a glitch.
     */
    public final Parameter Capitals = new Parameter(6, 0, 60, UnitType.Percentage);

    /** Word gap (pause between words in 10mS units, 0=normal). */
    public final Parameter WordGap = new Parameter(7, 0, 50, UnitType.Percentage);

    /** Intonation style (0=default, 1-7=intonation groups, e.g. 3=less intonation / flat). */
    public final Parameter Intonation = new Parameter(9, 0, 7, UnitType.Percentage);

    public void synthesize(String text, boolean isSsml) {
        if (text == null) {
            return;
        }
        synchronized (sSynthLock) {
            nativeSynthesize(text, isSsml);
        }
    }

    public void stop() {
        nativeStop();
    }

    public void terminate() {
        nativeTerminate();
    }

    private void nativeSynthCallback(byte[] audioData) {
        if (mCallback == null)
            return;

        if (audioData == null) {
            mCallback.onSynthDataComplete();
        } else {
            mCallback.onSynthDataReady(audioData);
        }
    }

    @SuppressWarnings("unused") // called from eSpeakService.c
    private void nativeSynthWordCallback(int textPosition, int textLength, int markerInFrames) {
        if (mCallback == null)
            return;

        mCallback.onSynthWordBoundary(textPosition, textLength, markerInFrames);
    }

    private void attemptInit() {
        if (mInitialized) {
            return;
        }

        if (!CheckVoiceData.hasBaseResources(mContext)) {
            Log.e(TAG, "Missing base resources");
            return;
        }

        // sSampleRate is a static field shared by every SpeechSynthesis
        // instance (e.g. TtsService's long-lived engine and a short-lived
        // one CheckVoiceData/DownloadVoiceData construct to probe voices),
        // so this must lock on the class, not `this` - locking per-instance
        // let two instances both see sSampleRate == 0 and both reach
        // nativeCreate() concurrently, racing on the native library's own
        // one-time espeak_Initialize().
        synchronized (SpeechSynthesis.class) {
            if (sSampleRate > 0) {
                mSampleRate = sSampleRate;
                mInitialized = true;
                return;
            }

            mSampleRate = nativeCreate(mDatapath);
            if (mSampleRate == 0) {
                Log.e(TAG, "Failed to initialize speech synthesis library");
                return;
            }

            sSampleRate = mSampleRate;
            Log.i(TAG, "Initialized synthesis library with sample rate = " + getSampleRate());

            mInitialized = true;
        }
    }

    public static String getSampleText(Context context, Locale locale) {
        final String language = getIanaLanguageCode(locale.getLanguage());
        final String country = getIanaCountryCode(locale.getCountry());
        final Locale target = new Locale(language, country, locale.getVariant());

        // Don't mutate the shared Configuration (deprecated config.locale path
        // also raced with concurrent callers); resolve resources against a copy.
        final Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(target);
        final Context localized = context.createConfigurationContext(config);
        return localized.getResources().getString(
                R.string.sample_text, target.getDisplayName(target));
    }

    private static native boolean nativeClassInit();

    private native int nativeCreate(String path);

    private native String[] nativeGetAvailableVoices();

    private native boolean nativeSetVoiceByName(String name);

    private native boolean nativeSetVoiceByProperties(String language, int gender, int age);

    private native boolean nativeSetParameter(int parameter, int value);

    private native int nativeGetParameter(int parameter, int current);

    private native boolean nativeSetPunctuationCharacters(String characters);

    private native boolean nativeSynthesize(String text, boolean isSsml);

    private native boolean nativeStop();

    private native void nativeTerminate();

    public interface SynthReadyCallback {
        void onSynthDataReady(byte[] audioData);

        void onSynthDataComplete();

        /**
         * Reports the start of a spoken word.
         *
         * @param textPosition 1-based index of the word in the synthesized text,
         *                     counted in Unicode code points (not UTF-16 units).
         * @param textLength Word length in code points. eSpeak packs this into a
         *                   single byte, so words longer than 255 report a
         *                   truncated length.
         * @param markerInFrames Absolute frame offset of the word within the audio
         *                       generated for this request.
         */
        void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames);
    }

    public static String getIanaLanguageCode(String code) {
        return getIanaLocaleCode(code, mJavaToIanaLanguageCode);
    }

    public static String getIanaCountryCode(String code) {
        return getIanaLocaleCode(code, mJavaToIanaCountryCode);
    }

    private static String getIanaLocaleCode(String code, final Map<String, String> javaToIana) {
        final String iana = javaToIana.get(code);
        if (iana != null) {
            return iana;
        }
        return code;
    }

    private static final Map<String, String> mJavaToIanaLanguageCode = new HashMap<String, String>();
    private static final Map<String, String> mJavaToIanaCountryCode = new HashMap<String, String>();
    private static final HashMap<String, Locale> mLocaleFixes = new HashMap<String, Locale>();
    static {
        mJavaToIanaLanguageCode.put("afr", "af");
        mJavaToIanaLanguageCode.put("amh", "am");
        mJavaToIanaLanguageCode.put("ara", "ar");
        mJavaToIanaLanguageCode.put("arg", "an");
        mJavaToIanaLanguageCode.put("asm", "as");
        mJavaToIanaLanguageCode.put("aze", "az");
        mJavaToIanaLanguageCode.put("bul", "bg");
        mJavaToIanaLanguageCode.put("ben", "bn");
        mJavaToIanaLanguageCode.put("bos", "bs");
        mJavaToIanaLanguageCode.put("cat", "ca");
        mJavaToIanaLanguageCode.put("ces", "cs");
        mJavaToIanaLanguageCode.put("cym", "cy");
        mJavaToIanaLanguageCode.put("dan", "da");
        mJavaToIanaLanguageCode.put("deu", "de");
        mJavaToIanaLanguageCode.put("ell", "el");
        mJavaToIanaLanguageCode.put("eng", "en");
        mJavaToIanaLanguageCode.put("epo", "eo");
        mJavaToIanaLanguageCode.put("spa", "es");
        mJavaToIanaLanguageCode.put("est", "et");
        mJavaToIanaLanguageCode.put("eus", "eu");
        mJavaToIanaLanguageCode.put("fas", "fa");
        mJavaToIanaLanguageCode.put("fin", "fi");
        mJavaToIanaLanguageCode.put("fra", "fr");
        mJavaToIanaLanguageCode.put("gle", "ga");
        mJavaToIanaLanguageCode.put("gla", "gd");
        mJavaToIanaLanguageCode.put("grn", "gn");
        mJavaToIanaLanguageCode.put("guj", "gu");
        mJavaToIanaLanguageCode.put("hin", "hi");
        mJavaToIanaLanguageCode.put("hrv", "hr");
        mJavaToIanaLanguageCode.put("hun", "hu");
        mJavaToIanaLanguageCode.put("hye", "hy");
        mJavaToIanaLanguageCode.put("ina", "ia");
        mJavaToIanaLanguageCode.put("ind", "in"); // NOTE: The deprecated 'in' code is used by Java/Android.
        mJavaToIanaLanguageCode.put("isl", "is");
        mJavaToIanaLanguageCode.put("ita", "it");
        mJavaToIanaLanguageCode.put("jpn", "ja");
        mJavaToIanaLanguageCode.put("kat", "ka");
        mJavaToIanaLanguageCode.put("kal", "kl");
        mJavaToIanaLanguageCode.put("kan", "kn");
        mJavaToIanaLanguageCode.put("kir", "ky");
        mJavaToIanaLanguageCode.put("kor", "ko");
        mJavaToIanaLanguageCode.put("kur", "ku");
        mJavaToIanaLanguageCode.put("lat", "la");
        mJavaToIanaLanguageCode.put("lit", "lt");
        mJavaToIanaLanguageCode.put("lav", "lv");
        mJavaToIanaLanguageCode.put("mkd", "mk");
        mJavaToIanaLanguageCode.put("mal", "ml");
        mJavaToIanaLanguageCode.put("mar", "mr");
        mJavaToIanaLanguageCode.put("mlt", "mt");
        mJavaToIanaLanguageCode.put("mri", "mi");
        mJavaToIanaLanguageCode.put("msa", "ms");
        mJavaToIanaLanguageCode.put("mya", "my");
        mJavaToIanaLanguageCode.put("nep", "ne");
        mJavaToIanaLanguageCode.put("nld", "nl");
        mJavaToIanaLanguageCode.put("nob", "nb");
        mJavaToIanaLanguageCode.put("nor", "no");
        mJavaToIanaLanguageCode.put("ori", "or");
        mJavaToIanaLanguageCode.put("orm", "om");
        mJavaToIanaLanguageCode.put("pan", "pa");
        mJavaToIanaLanguageCode.put("pol", "pl");
        mJavaToIanaLanguageCode.put("por", "pt");
        mJavaToIanaLanguageCode.put("ron", "ro");
        mJavaToIanaLanguageCode.put("rus", "ru");
        mJavaToIanaLanguageCode.put("sin", "si");
        mJavaToIanaLanguageCode.put("slk", "sk");
        mJavaToIanaLanguageCode.put("slv", "sl");
        mJavaToIanaLanguageCode.put("snd", "sd");
        mJavaToIanaLanguageCode.put("sqi", "sq");
        mJavaToIanaLanguageCode.put("srp", "sr");
        mJavaToIanaLanguageCode.put("swe", "sv");
        mJavaToIanaLanguageCode.put("swa", "sw");
        mJavaToIanaLanguageCode.put("tam", "ta");
        mJavaToIanaLanguageCode.put("tel", "te");
        mJavaToIanaLanguageCode.put("tat", "tt");
        mJavaToIanaLanguageCode.put("tsn", "tn");
        mJavaToIanaLanguageCode.put("tur", "tr");
        mJavaToIanaLanguageCode.put("urd", "ur");
        mJavaToIanaLanguageCode.put("vie", "vi");
        mJavaToIanaLanguageCode.put("zho", "zh");

        mJavaToIanaCountryCode.put("ARM", "AM");
        mJavaToIanaCountryCode.put("AUS", "AU");
        mJavaToIanaCountryCode.put("BEL", "BE");
        mJavaToIanaCountryCode.put("BRA", "BR");
        mJavaToIanaCountryCode.put("CAN", "CA");
        mJavaToIanaCountryCode.put("CHE", "CH");
        mJavaToIanaCountryCode.put("FRA", "FR");
        mJavaToIanaCountryCode.put("GBR", "GB");
        mJavaToIanaCountryCode.put("HKG", "HK");
        mJavaToIanaCountryCode.put("IND", "IN");
        mJavaToIanaCountryCode.put("JAM", "JM");
        mJavaToIanaCountryCode.put("MEX", "MX");
        mJavaToIanaCountryCode.put("PRT", "PT");
        mJavaToIanaCountryCode.put("SGP", "SG");
        mJavaToIanaCountryCode.put("USA", "US");
        mJavaToIanaCountryCode.put("VNM", "VN");

        // Fix up BCP47 locales not handled correctly by Android:
        mLocaleFixes.put("cmn", new Locale("zh"));
        mLocaleFixes.put("en-029", new Locale("en", "JM"));
        mLocaleFixes.put("es-419", new Locale("es", "MX"));
        mLocaleFixes.put("hy-arevmda", new Locale("hy", "AM", "arevmda")); // hy-arevmda crashes on Android 5.0
        mLocaleFixes.put("yue", new Locale("zh", "HK"));
    }
}
