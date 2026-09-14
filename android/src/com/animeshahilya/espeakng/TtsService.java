/*
 * Copyright (C) 2022 Beka Gozalishvili
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
 * This file implements the Android Text-to-Speech engine for eSpeak.
 *
 * Minimum Android Version: 8.0 (Oreo)
 * Minimum API Version:     26
 */

package com.animeshahilya.espeakng;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;
import android.util.Pair;

import com.animeshahilya.espeakng.SpeechSynthesis.SynthReadyCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the eSpeak engine as a {@link TextToSpeechService}.
 *
 * @author msclrhd@gmail.com (Reece H. Dunn)
 * @author alanv@google.com (Alan Viverette)
 */
public class TtsService extends TextToSpeechService {
    private static final String TAG = TtsService.class.getSimpleName();
    private static Context storageContext;
    private static final boolean DEBUG = BuildConfig.DEBUG;

    /**
     * volatile: onStop() reads this from the framework's control thread,
     * deliberately without taking the {@code this} monitor (it has to be
     * able to interrupt a synthesis in progress on the synth thread, not
     * queue up behind it - see stop_requested in eSpeakService.c). That
     * makes it a genuine concurrent reader against initializeTtsEngine(),
     * which now also runs off the main thread (via mLanguagesUpdatedReceiver)
     * instead of only ever during the single-threaded onCreate() it used to.
     */
    private volatile SpeechSynthesis mEngine;
    private SynthesisCallback mCallback;
    /**
     * Post-synthesis tone shaping for the current request only - null when the setting is off,
     * or freshly constructed per {@link #onSynthesizeText} call otherwise (never reused across
     * requests: it carries per-utterance filter/leveler state that must not leak into the next
     * one). Read from {@link #mSynthCallback}'s onSynthDataReady on the same thread that set it.
     */
    private AudioOptimizer mAudioOptimizer;
    private final AtomicBoolean mCallbackDone = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger mSegmentsRemaining = new java.util.concurrent.atomic.AtomicInteger(1);
    private final AtomicBoolean mIsStopped = new AtomicBoolean(false);

    /** Text handed to eSpeak for the current request. */
    private String mSynthText;
    /** Where {@link #mSynthText} starts within the text the caller supplied. */
    private int mSynthTextOffset;
    /** Length of the original text passed by the caller for boundary clamping. */
    private int mOriginalTextLength;
    /**
     * Offset map back to the caller's text when {@link #mSynthText} is a
     * normalized copy of it, or null when they are the same string.
     */
    private UnicodeNormalization.Result mSynthNormalization;
    /** Number of code points in {@link #mSynthText}. */
    private int mSynthTextCodePoints;
    /** Anchor for incremental code point to UTF-16 index conversion. */
    private int mAnchorCodePoint;
    private int mAnchorOffset;

    private List<Voice> mAllVoices = new ArrayList<Voice>();
    private final Map<String, Voice> mAvailableVoices = new HashMap<String, Voice>();
    protected Voice mMatchingVoice = null;

    private SharedPreferences mPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnPreferencesChanged =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (LanguageSettings.PREF_SUPPORTED_LANGUAGES.equals(key)) {
                        rebuildAvailableVoices();
                    }
                }
            };

    /**
     * Reloads the voice list from the native engine when new voice data lands
     * on disk (first-run extraction or a user-imported voice/zip). Without
     * this, a service instance that was already running keeps serving the
     * voice list it enumerated at onCreate() until the process happens to be
     * killed and restarted, so an imported voice never becomes selectable.
     *
     * initializeTtsEngine() re-runs native JNI init (the same seconds-long
     * disk/JNI cost documented on TtsSettingsActivity.createPreferences()),
     * so it is pushed off this receiver's main-thread callback. It still
     * needs to serialize with onSynthesizeText() -- both touch mEngine
     * without their own lock -- so the background thread takes the same
     * monitor onSynthesizeText() is synchronized on, rather than racing it.
     */
    private final BroadcastReceiver mLanguagesUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (TtsService.this) {
                        initializeTtsEngine();
                    }
                }
            }, "espeak-voices-reload").start();
        }
    };

    @Override
    public void onCreate() {
        storageContext = EspeakApp.getStorageContext();

        mPreferences = PreferenceManager.getDefaultSharedPreferences(storageContext);
        mPreferences.registerOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        if (!CheckVoiceData.hasBaseResources(storageContext)
                || CheckVoiceData.canUpgradeResources(storageContext)) {
            CheckVoiceData.extractVoiceData(storageContext);
        }
        initializeTtsEngine();
        final IntentFilter filter = new IntentFilter(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED);
        // The 3-arg registerReceiver(..., flags) overload requires API 33 (Tiramisu);
        // this app's minSdk is 26, so it must fall back to the unflagged overload below
        // that API level. Pre-33 receivers are unexported by default anyway unless the
        // app explicitly requests otherwise, so this is not a behavior regression there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mLanguagesUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mLanguagesUpdatedReceiver, filter);
        }
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPreferences != null) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        }
        try {
            unregisterReceiver(mLanguagesUpdatedReceiver);
        } catch (IllegalArgumentException e) {
            // Not registered (onCreate() never completed) - nothing to undo.
        }
    }

    /**
     * Sets up the native eSpeak engine.
     */
    private void initializeTtsEngine() {
        if (mEngine != null) {
            mEngine.stop();
            mEngine = null;
        }

        mEngine = new SpeechSynthesis(storageContext, mSynthCallback);
        mMatchingVoice = null;
        List<Voice> voices = mEngine.getAvailableVoices();
        synchronized (mAvailableVoices) {
            mAllVoices = new ArrayList<Voice>(voices);
            if (DEBUG) Log.i(TAG, "initializeTtsEngine(): loaded voices=" + mAllVoices.size());
        }
        rebuildAvailableVoices();
    }

    @Override
    protected String[] onGetLanguage() {
        // This is used to specify the language requested from GetSampleText.
        final Voice voice;
        synchronized (mAvailableVoices) {
            voice = mMatchingVoice;
        }
        if (voice == null) {
            return new String[] { "eng", "GBR", "" };
        }
        return new String[] {
            voice.locale.getISO3Language(),
            voice.locale.getISO3Country(),
            voice.locale.getVariant()
        };
    }

    private Pair<Voice, Integer> findVoice(String language, String country, String variant) {
        if (!CheckVoiceData.hasBaseResources(storageContext)) {
            return new Pair<>(null, TextToSpeech.LANG_MISSING_DATA);
        }

        final Locale query = new Locale(language, country, variant);

        Voice languageVoice = null;
        Voice countryVoice = null;

        synchronized (mAvailableVoices) {
            for (Voice voice : mAvailableVoices.values()) {
                switch (voice.match(query)) {
                    case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
                        return new Pair<>(voice, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE);
                    case TextToSpeech.LANG_COUNTRY_AVAILABLE:
                        countryVoice = voice;
                    case TextToSpeech.LANG_AVAILABLE:
                        languageVoice = voice;
                        break;
                }
            }
        }

        if (languageVoice == null) {
            return new Pair<>(null, TextToSpeech.LANG_NOT_SUPPORTED);
        } else if (countryVoice == null) {
            return new Pair<>(languageVoice, TextToSpeech.LANG_AVAILABLE);
        } else {
            return new Pair<>(countryVoice, TextToSpeech.LANG_COUNTRY_AVAILABLE);
        }
    }

    private Pair<Voice, Integer> getDefaultVoiceFor(String language, String country, String variant) {
        final Pair<Voice, Integer> match = findVoice(language, country, variant);
        switch (match.second) {
            case TextToSpeech.LANG_AVAILABLE:
                if (language.equals("fr") || language.equals("fra")) {
                    return new Pair<>(findVoice(language, "FRA", "").first, match.second);
                }
                if (language.equals("pt") || language.equals("por")) {
                    return new Pair<>(findVoice(language, "PRT", "").first, match.second);
                }
                return new Pair<>(findVoice(language, "", "").first, match.second);
            case TextToSpeech.LANG_COUNTRY_AVAILABLE:
                if ((language.equals("vi") || language.equals("vie")) && (country.equals("VN") || country.equals("VNM"))) {
                    return new Pair<>(findVoice(language, country, "hue").first, match.second);
                }
                return new Pair<>(findVoice(language, country, "").first, match.second);
            default:
                return match;
        }
    }

    @Override
    protected int onIsLanguageAvailable(String language, String country, String variant) {
        final int result = findVoice(language, country, variant).second;
        if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // When the requested language is filtered out but other voices are
            // available, report the language as available so that screen readers
            // (e.g. Jieshuo) don't skip this engine entirely.
            synchronized (mAvailableVoices) {
                if (!mAvailableVoices.isEmpty()) {
                    return TextToSpeech.LANG_AVAILABLE;
                }
            }
        }
        return result;
    }

    @Override
    protected int onLoadLanguage(String language, String country, String variant) {
        final Pair<Voice, Integer> match = getDefaultVoiceFor(language, country, variant);
        if (match.first != null) {
            synchronized (mAvailableVoices) {
                mMatchingVoice = match.first;
            }
            return match.second;
        }
        if (match.second == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to a previously selected or available voice so that
            // screen readers requesting the system language still get speech.
            synchronized (mAvailableVoices) {
                if (mMatchingVoice != null) {
                    return TextToSpeech.LANG_AVAILABLE;
                }
                if (!mAvailableVoices.isEmpty()) {
                    mMatchingVoice = mAvailableVoices.values().iterator().next();
                    return TextToSpeech.LANG_AVAILABLE;
                }
            }
        }
        return match.second;
    }

    @Override
    protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        // eSpeak synthesizes on the device for every language it offers, and never
        // needs a network connection. Clients read this set -- directly, or through
        // the features of the voices built in onGetVoices() -- to decide whether a
        // language can be spoken offline; leaving it empty makes eSpeak look like an
        // engine that cannot answer the question.
        final Set<String> features = new HashSet<String>();
        features.add(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
        return features;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        final Voice match = getDefaultVoiceFor(language, country, variant).first;
        return (match == null) ? null : match.name;
    }

    @Override
    public List<android.speech.tts.Voice> onGetVoices() {
        rebuildAvailableVoices();
        List<android.speech.tts.Voice> voices = new ArrayList<android.speech.tts.Voice>();
        synchronized (mAvailableVoices) {
            for (Voice voice : mAvailableVoices.values()) {
                int quality = android.speech.tts.Voice.QUALITY_NORMAL;
                int latency = android.speech.tts.Voice.LATENCY_VERY_LOW;
                Locale locale = new Locale(voice.locale.getISO3Language(), voice.locale.getISO3Country(), voice.locale.getVariant());
                Set<String> features = onGetFeaturesForLanguage(locale.getLanguage(), locale.getCountry(), locale.getVariant());
                voices.add(new android.speech.tts.Voice(voice.name, voice.locale, quality, latency, false, features));
            }
        }
        return voices;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        synchronized (mAvailableVoices) {
            Voice voice = mAvailableVoices.get(name);
            return (voice == null) ? TextToSpeech.ERROR : TextToSpeech.SUCCESS;
        }
    }

    @Override
    public int onLoadVoice(String name) {
        synchronized (mAvailableVoices) {
            Voice voice = mAvailableVoices.get(name);
            if (voice == null) {
                return TextToSpeech.ERROR;
            }
            mMatchingVoice = voice;
            return TextToSpeech.SUCCESS;
        }
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "Received stop request.");
        mIsStopped.set(true);

        // Local snapshot: mEngine can be briefly reassigned (old engine
        // stopped, new one not yet in place) by a concurrent
        // initializeTtsEngine() reload; a null field read here would NPE
        // instead of just missing that one edge of the reload window.
        final SpeechSynthesis engine = mEngine;
        if (engine != null) {
            engine.stop();
        }
    }

    private String getRequestString(SynthesisRequest request) {
        if (request == null) {
            return null;
        }
        final CharSequence cs = request.getCharSequenceText();
        return cs != null ? cs.toString() : null;
    }

    protected int selectLanguageWithFallback(String language, String country, String variant) {
        final int result = onLoadLanguage(language, country, variant);
        switch (result) {
            case TextToSpeech.LANG_MISSING_DATA:
                return TextToSpeech.ERROR;
            case TextToSpeech.LANG_NOT_SUPPORTED:
                // fall back to a previously selected or available voice instead of failing.
                // This allows screen readers that request the system language (e.g. Jieshuo)
                // to still work when the user has selected only other languages.
                synchronized (mAvailableVoices) {
                    // Prefer reusing the last matching voice if one is already selected.
                    if (mMatchingVoice != null) {
                        return TextToSpeech.SUCCESS;
                    }
                    // Otherwise, pick an arbitrary available voice if any exist.
                    if (!mAvailableVoices.isEmpty()) {
                        mMatchingVoice = mAvailableVoices.values().iterator().next();
                        return TextToSpeech.SUCCESS;
                    }
                }
                // No previous voice and no available voices to fall back to.
                return TextToSpeech.ERROR;
        }
        return TextToSpeech.SUCCESS;
    }

    private int selectVoice(SynthesisRequest request) {
        final String name = request.getVoiceName();
        if (name != null && !name.isEmpty()
                && onLoadVoice(name) == TextToSpeech.SUCCESS) {
            return TextToSpeech.SUCCESS;
        }
        // Deliberately fall through when the named voice is unknown rather
        // than returning its error. The framework attaches a voice name to
        // every request -- including the system default one when the client
        // never picked a voice itself -- so a name that has been filtered out
        // of the user's language selection would otherwise fail every
        // request and make selectLanguageWithFallback() below unreachable.
        // The name is a hint; the language cascade decides.
        return selectLanguageWithFallback(request.getLanguage(), request.getCountry(), request.getVariant());
    }

    /**
     * Reports a synthesis failure to the caller.
     *
     * <p>The framework only dispatches an error once {@code done()} follows, and
     * treats a request that returns without calling either as a successful empty
     * utterance, which hides failures from screen readers.
     */
    private void reportError(SynthesisCallback callback, int errorCode) {
        // error(int) has been available since API 21, which is minSdk here, so
        // the code always reaches the caller.
        callback.error(errorCode);
        callback.done();
    }

    /**
     * Converts a 0-based code point index within {@link #mSynthText} into the
     * UTF-16 index that {@link SynthesisCallback#rangeStart} expects.
     *
     * <p>Walks forward from the previous result, since word events arrive in text
     * order; the occasional out-of-order event falls back to a full rescan.
     */
    private int codePointToOffset(int codePointIndex) {
        if (mSynthText == null || codePointIndex <= 0) {
            return 0;
        }
        if (codePointIndex >= mSynthTextCodePoints) {
            return mSynthText.length();
        }
        try {
            if (codePointIndex < mAnchorCodePoint || mAnchorOffset > mSynthText.length()) {
                mAnchorCodePoint = 0;
                mAnchorOffset = 0;
            }
            mAnchorOffset = mSynthText.offsetByCodePoints(
                    mAnchorOffset, codePointIndex - mAnchorCodePoint);
            mAnchorCodePoint = codePointIndex;
            return mAnchorOffset;
        } catch (Exception e) {
            mAnchorCodePoint = 0;
            mAnchorOffset = 0;
            return 0;
        }
    }

    @Override
    protected synchronized void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (selectVoice(request) == TextToSpeech.ERROR) {
            reportError(callback, CheckVoiceData.hasBaseResources(storageContext)
                    ? TextToSpeech.ERROR_SERVICE : TextToSpeech.ERROR_NOT_INSTALLED_YET);
            return;
        }

        final Voice voice;
        synchronized (mAvailableVoices) {
            voice = mMatchingVoice;
        }
        if (voice == null) {
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }

        String text = getRequestString(request);
        if (text == null) {
            reportError(callback, TextToSpeech.ERROR_INVALID_REQUEST);
            return;
        }

        // Fast-path empty or whitespace-only utterances: avoid full voice/param setup
        // and JNI overhead for TalkBack spacers, empty lines, and blank elements.
        if (text.trim().isEmpty()) {
            callback.start(mEngine.getSampleRate(), mEngine.getAudioFormat(), mEngine.getChannelCount());
            callback.done();
            return;
        }

        mOriginalTextLength = text.length();

        if (DEBUG) {
            Log.i(TAG, "Received synthesis request: {language=\"" + voice.name + "\"}");

            final Bundle params = request.getParams();
            for (String key : params.keySet()) {
                Log.v(TAG,
                        "Synthesis request contained param {" + key + ", " + params.get(key) + "}");
            }
        }

        int textOffset = 0;
        if (text.startsWith("<?xml"))
        {
            // eSpeak does not recognise/skip "<?...?>" preprocessing tags,
            // so need to remove these before passing to synthesize. A
            // declaration missing its "?>" is left alone rather than having its
            // first character eaten by a -1 index.
            final int terminator = text.indexOf("?>");
            if (terminator >= 0)
            {
                final int declarationEnd = terminator + 2;
                // Track what was dropped from the front, so that word boundaries can
                // be reported against the text the caller actually passed in. This
                // mirrors what String.trim() strips (anything <= ' ').
                textOffset = declarationEnd;
                while (textOffset < text.length() && text.charAt(textOffset) <= ' ') {
                    textOffset++;
                }
                text = text.substring(declarationEnd).trim();
            }
        }

        final VoiceSettings settings = new VoiceSettings(PreferenceManager.getDefaultSharedPreferences(storageContext), mEngine);

        // Detect SSML before normalizing. Real markup is ASCII, which NFKC
        // leaves untouched, but normalization can turn lookalikes such as a
        // fullwidth "＜ｓｐｅａｋ" into "<speak", and plain text must not
        // switch into SSML parsing because of that.
        final boolean isSsml = text.startsWith("<speak");

        if (!isSsml) {
            // NVDA eSpeak driver fix: Strip control character 0x01, which eSpeak reserves
            // for embedded commands and whose presence causes pronunciation corruption or aborts.
            if (text.indexOf('\u0001') != -1) {
                text = text.replace("\u0001", "");
            }
            // NVDA-style hardening (NVDA's _espeak.py encodes with errors="ignore" before
            // the native call): drop unpaired UTF-16 surrogates - e.g. from a clipboard paste
            // truncated mid-emoji - before they reach the JNI/native layer, which expects
            // well-formed text and can otherwise mis-decode or corrupt trailing output.
            text = stripUnpairedSurrogates(text);
            // NVDA eSpeak driver fix: Prevent unintentional [[ phoneme syntax entry by
            // separating consecutive left brackets when not in phoneme mode.
            if (text.contains("[[")) {
                text = text.replace("[[", "[ [");
            }
        }

        if (!isSsml && settings.isUserDictionaryEnabled()) {
            text = UserDictionaryManager.getInstance(storageContext).applyRules(text);
        }

        if (!isSsml && (text.length() == 1 || text.trim().length() == 1)) {
            if (settings.isNatoSpellingEnabled()) {
                text = expandNatoSpelling(text);
            }
            if (settings.isSpokenDiacriticsEnabled()) {
                text = expandDevanagariDiacritic(text);
            }
        }

        if (!isSsml && settings.isSpeakProgrammingSymbolsEnabled()) {
            text = expandProgrammingSymbols(text);
        }

        if (!isSsml && settings.isIndianNumberingEnabled()) {
            text = preprocessIndianText(text);
        }

        final boolean speakDigits = settings.isSpeakDigitsEnabled() && !isSsml;
        final boolean smartCodes = settings.isSmartCodesEnabled() && !isSsml;
        if (speakDigits) {
            text = spaceSeparateDigits(text);
        } else if (smartCodes) {
            text = spaceSeparateSmartCodes(text);
        }

        UnicodeNormalization.Result normalization = null;
        if (settings.isUnicodeNormalizationEnabled()) {
            normalization = UnicodeNormalization.normalize(text);
            if (normalization != null) {
                text = normalization.text;
                if (!isSsml && settings.isSpeakProgrammingSymbolsEnabled()) {
                    text = expandProgrammingSymbols(text);
                }
                if (!isSsml && settings.isIndianNumberingEnabled()) {
                    text = preprocessIndianText(text);
                }
                if (speakDigits) {
                    text = spaceSeparateDigits(text);
                    normalization = null;
                } else if (smartCodes) {
                    final String smart = spaceSeparateSmartCodes(text);
                    if (!smart.equals(text)) {
                        text = smart;
                        normalization = null;
                    }
                }
            }
        }

        if (!isSsml && containsPotentialEmoji(text)) {
            text = settings.isEmojiIgnoreEnabled() ? filterEmojis(text) : clarifyEmojiAnnouncements(text);
        }

        mSynthText = text;
        mSynthTextOffset = textOffset;
        mSynthNormalization = normalization;
        mSynthTextCodePoints = text.codePointCount(0, text.length());
        mAnchorCodePoint = 0;
        mAnchorOffset = 0;

        mCallback = callback;
        mCallbackDone.set(false);
        mIsStopped.set(false);
        int startStatus = mCallback.start(mEngine.getSampleRate(), mEngine.getAudioFormat(), mEngine.getChannelCount());
        if (startStatus != TextToSpeech.SUCCESS) {
            mCallback = null;
            return;
        }
        mAudioOptimizer = settings.isAudioOptimizerEnabled()
                ? new AudioOptimizer(mEngine.getSampleRate())
                : null;
        mEngine.setVoice(voice, settings.getVoiceVariant());

        int rate = settings.getRate();
        int rateScale = request.getSpeechRate();
        if (rateScale <= 0) {
            rateScale = 100;
        }
        rate = (int)(((long)rate * rateScale) / 100);
        if (!settings.isRateBoostEnabled() && rate > 449) {
            rate = 449; // NVDA issue #131: avoid unintended Sonic engagement at 450 WPM
        }
        mEngine.Rate.setValue(rate);

        int pitchScale = request.getPitch();
        if (pitchScale <= 0) {
            pitchScale = 100;
        }
        mEngine.Pitch.setValue(settings.getPitch(), pitchScale);

        mEngine.PitchRange.setValue(settings.getPitchRange());

        // Accessibility volume ducking support (KEY_PARAM_VOLUME)
        float volumeScale = 1.0f;
        final Bundle params = request.getParams();
        if (params != null) {
            Object volObj = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME);
            if (volObj instanceof Number) {
                volumeScale = ((Number) volObj).floatValue();
            } else if (volObj instanceof String) {
                try {
                    volumeScale = Float.parseFloat((String) volObj);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (volumeScale < 0.0f) {
            volumeScale = 0.0f;
        } else if (volumeScale > 1.0f) {
            volumeScale = 1.0f;
        }
        int targetVolume = Math.round(settings.getVolume() * volumeScale);
        mEngine.Volume.setValue(targetVolume);

        mEngine.Punctuation.setValue(settings.getPunctuationLevel());
        mEngine.setPunctuationCharacters(settings.getPunctuationCharacters());
        mEngine.Capitals.setValue(settings.getCapitals());
        mEngine.WordGap.setValue(settings.getWordGap());

        boolean enableBilingual = settings.isBilingualSwitchingEnabled() && !isSsml;
        List<ScriptSpan> spans = null;
        Voice secondaryVoice = null;
        if (enableBilingual && hasMixedLatinAndIndic(text)) {
            synchronized (mAvailableVoices) {
                secondaryVoice = mAvailableVoices.get(settings.getSecondaryVoice());
            }
            if (secondaryVoice != null && !secondaryVoice.name.equals(voice.name)) {
                spans = splitByScriptRuns(text);
            }
        }

        if (spans != null && spans.size() > 1 && secondaryVoice != null) {
            mSegmentsRemaining.set(spans.size());
            for (ScriptSpan span : spans) {
                if (mIsStopped.get()) {
                    break;
                }
                Voice spanVoice = span.isLatin ? secondaryVoice : voice;
                mEngine.setVoice(spanVoice, settings.getVoiceVariant());
                mEngine.synthesize(span.text, false);
            }
        } else {
            mSegmentsRemaining.set(1);
            mEngine.setVoice(voice, settings.getVoiceVariant());
            mEngine.synthesize(text, isSsml);
        }
    }

    private static boolean containsPotentialEmoji(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) || c >= 0x2600) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        final int type = Character.getType(codePoint);
        return (type == Character.OTHER_SYMBOL || type == Character.SURROGATE)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF);
    }

    private static String filterEmojis(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        final int len = text.length();
        for (int i = 0; i < len; ) {
            final int codePoint = text.codePointAt(i);
            if (!isEmojiCodePoint(codePoint)) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append(' ');
            }
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern SMART_CODE_KEYWORD =
            java.util.regex.Pattern.compile("(?i)\\b(otp|pin|code|passcode|password|secret|verification|security|token|login|id|txn|ref|vpa|cvv)\\b");

    private static final java.util.regex.Pattern DANDA_BOUNDARY =
            java.util.regex.Pattern.compile("([।॥])([^\\s])");

    private static final java.util.regex.Pattern BANKING_SLASH_TXN =
            java.util.regex.Pattern.compile("(?i)\\b(UPI|TXN|REF|IMPS|NEFT|RTGS)/([A-Za-z0-9/]+)");

    private static final java.util.regex.Pattern CURRENCY_PREFIX =
            java.util.regex.Pattern.compile("(?i)(?:₹|\\b(?:Rs\\.?|INR)\\s*)([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)");

    private static final java.util.regex.Pattern INDIAN_NUMBER_COMMAS =
            java.util.regex.Pattern.compile("\\b(\\d{1,2}(?:,\\d{2})+),(\\d{3})\\b");

    private static final java.util.regex.Pattern SHORTHAND_THOUSAND =
            java.util.regex.Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*k\\b");

    private static final java.util.regex.Pattern SHORTHAND_LAKH =
            java.util.regex.Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:l|lac|lakh|lakhs)\\b");

    private static final java.util.regex.Pattern SHORTHAND_CRORE =
            java.util.regex.Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:cr|crore|crores)\\b");
    private static final java.util.regex.Pattern SLASH_RUN =
            java.util.regex.Pattern.compile("/+");

    // NVDA-inspired programming, mathematical, and syntax symbol patterns (from NVDA symbols.dic):
    private static final java.util.regex.Pattern SYM_NOT_EQUAL = java.util.regex.Pattern.compile("!=|≠");
    private static final java.util.regex.Pattern SYM_DOUBLE_EQUALS = java.util.regex.Pattern.compile("==");
    private static final java.util.regex.Pattern SYM_LESS_EQUAL = java.util.regex.Pattern.compile("<=|≤");
    private static final java.util.regex.Pattern SYM_GREATER_EQUAL = java.util.regex.Pattern.compile(">=|≥");
    private static final java.util.regex.Pattern SYM_FAT_ARROW = java.util.regex.Pattern.compile("=>|⇒");
    private static final java.util.regex.Pattern SYM_THIN_ARROW = java.util.regex.Pattern.compile("->|→");
    private static final java.util.regex.Pattern SYM_LEFT_ARROW = java.util.regex.Pattern.compile("<-|←");
    private static final java.util.regex.Pattern SYM_UP_ARROW = java.util.regex.Pattern.compile("↑");
    private static final java.util.regex.Pattern SYM_DOWN_ARROW = java.util.regex.Pattern.compile("↓");
    private static final java.util.regex.Pattern SYM_LOGICAL_AND = java.util.regex.Pattern.compile("&&");
    private static final java.util.regex.Pattern SYM_LOGICAL_OR = java.util.regex.Pattern.compile("\\|\\|");
    private static final java.util.regex.Pattern SYM_COMMENT_START = java.util.regex.Pattern.compile("/\\*");
    private static final java.util.regex.Pattern SYM_COMMENT_END = java.util.regex.Pattern.compile("\\*/");
    private static final java.util.regex.Pattern SYM_DOUBLE_SLASH = java.util.regex.Pattern.compile("(?<!https?:)//");
    private static final java.util.regex.Pattern SYM_ELLIPSIS = java.util.regex.Pattern.compile("\\.{3,}|…");
    private static final java.util.regex.Pattern SYM_PLUS_MINUS = java.util.regex.Pattern.compile("±|\\+/-");
    private static final java.util.regex.Pattern SYM_TIMES = java.util.regex.Pattern.compile("(?<=\\d)\\s*[×*]\\s*(?=\\d)");
    private static final java.util.regex.Pattern SYM_DIVIDE = java.util.regex.Pattern.compile("(?<=\\d)\\s*÷\\s*(?=\\d)|÷");
    private static final java.util.regex.Pattern SYM_ALMOST_EQUAL = java.util.regex.Pattern.compile("≈");
    private static final java.util.regex.Pattern SYM_CHECKMARK = java.util.regex.Pattern.compile("[✓✔]");
    private static final java.util.regex.Pattern SYM_BULLET = java.util.regex.Pattern.compile("[•⁃◦]");
    private static final java.util.regex.Pattern SYM_DEGREES = java.util.regex.Pattern.compile("(?<=\\d)°");
    // Extended math, set-theory, and currency symbols from NVDA's symbols.dic
    // (source/locale/en/symbols.dic) not already covered above. Deliberately
    // excludes common punctuation like ~ ^ _ | ` that NVDA only reads at
    // certain verbosity levels - this app has no such tiering, and those
    // characters are frequent enough in ordinary prose/code (snake_case,
    // markdown, etc.) that always expanding them would be noisy rather than
    // helpful. These symbols are rare outside genuinely symbolic text.
    private static final java.util.regex.Pattern SYM_SQRT = java.util.regex.Pattern.compile("√");
    private static final java.util.regex.Pattern SYM_INFINITY = java.util.regex.Pattern.compile("∞");
    private static final java.util.regex.Pattern SYM_INTEGRAL = java.util.regex.Pattern.compile("∫");
    private static final java.util.regex.Pattern SYM_FOR_ALL = java.util.regex.Pattern.compile("∀");
    private static final java.util.regex.Pattern SYM_EXISTS = java.util.regex.Pattern.compile("∃");
    private static final java.util.regex.Pattern SYM_NOT_ELEMENT_OF = java.util.regex.Pattern.compile("∉");
    private static final java.util.regex.Pattern SYM_ELEMENT_OF = java.util.regex.Pattern.compile("∈");
    private static final java.util.regex.Pattern SYM_UNION = java.util.regex.Pattern.compile("∪");
    private static final java.util.regex.Pattern SYM_INTERSECTION = java.util.regex.Pattern.compile("∩");
    private static final java.util.regex.Pattern SYM_LOGICAL_NOT = java.util.regex.Pattern.compile("¬");
    private static final java.util.regex.Pattern SYM_SET_AND = java.util.regex.Pattern.compile("∧");
    private static final java.util.regex.Pattern SYM_SET_OR = java.util.regex.Pattern.compile("∨");
    private static final java.util.regex.Pattern SYM_CENT = java.util.regex.Pattern.compile("¢");
    private static final java.util.regex.Pattern SYM_YEN = java.util.regex.Pattern.compile("¥");
    private static final java.util.regex.Pattern SYM_FLORIN = java.util.regex.Pattern.compile("ƒ");

    private static boolean containsProgrammingSymbolChars(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '!': case '=': case '<': case '>':
                case '-': case '&': case '|': case '/':
                case '*': case '.': case '≠': case '≤':
                case '≥': case '⇒': case '→': case '←':
                case '↑': case '↓': case '…': case '±':
                case '×': case '÷': case '≈': case '✓':
                case '✔': case '•': case '⁃': case '◦':
                case '°': case '√': case '∞': case '∫':
                case '∀': case '∃': case '∉': case '∈':
                case '∪': case '∩': case '¬': case '∧':
                case '∨': case '¢': case '¥': case 'ƒ':
                    return true;
            }
        }
        return false;
    }

    /**
     * Expands multi-character programming, logical, and mathematical symbols
     * (borrowed from NVDA symbols.dic) so they read naturally instead of literal character lists.
     */
    public static String expandProgrammingSymbols(String text) {
        if (text == null || text.isEmpty() || !containsProgrammingSymbolChars(text)) {
            return text;
        }
        text = SYM_NOT_EQUAL.matcher(text).replaceAll(" not equal ");
        text = SYM_DOUBLE_EQUALS.matcher(text).replaceAll(" double equals ");
        text = SYM_LESS_EQUAL.matcher(text).replaceAll(" less than or equal to ");
        text = SYM_GREATER_EQUAL.matcher(text).replaceAll(" greater than or equal to ");
        text = SYM_FAT_ARROW.matcher(text).replaceAll(" implies ");
        text = SYM_THIN_ARROW.matcher(text).replaceAll(" arrow ");
        text = SYM_LEFT_ARROW.matcher(text).replaceAll(" left arrow ");
        text = SYM_UP_ARROW.matcher(text).replaceAll(" up arrow ");
        text = SYM_DOWN_ARROW.matcher(text).replaceAll(" down arrow ");
        text = SYM_LOGICAL_AND.matcher(text).replaceAll(" double ampersand ");
        text = SYM_LOGICAL_OR.matcher(text).replaceAll(" double pipe ");
        text = SYM_COMMENT_START.matcher(text).replaceAll(" comment start ");
        text = SYM_COMMENT_END.matcher(text).replaceAll(" comment end ");
        text = SYM_DOUBLE_SLASH.matcher(text).replaceAll(" double slash ");
        text = SYM_ELLIPSIS.matcher(text).replaceAll(" dot dot dot ");
        text = SYM_PLUS_MINUS.matcher(text).replaceAll(" plus or minus ");
        text = SYM_TIMES.matcher(text).replaceAll(" times ");
        text = SYM_DIVIDE.matcher(text).replaceAll(" divided by ");
        text = SYM_ALMOST_EQUAL.matcher(text).replaceAll(" almost equal to ");
        text = SYM_CHECKMARK.matcher(text).replaceAll(" check ");
        text = SYM_BULLET.matcher(text).replaceAll(" bullet ");
        text = SYM_DEGREES.matcher(text).replaceAll(" degrees ");
        text = SYM_SQRT.matcher(text).replaceAll(" square root ");
        text = SYM_INFINITY.matcher(text).replaceAll(" infinity ");
        text = SYM_INTEGRAL.matcher(text).replaceAll(" integral ");
        text = SYM_FOR_ALL.matcher(text).replaceAll(" for all ");
        text = SYM_EXISTS.matcher(text).replaceAll(" there exists ");
        text = SYM_NOT_ELEMENT_OF.matcher(text).replaceAll(" not an element of ");
        text = SYM_ELEMENT_OF.matcher(text).replaceAll(" element of ");
        text = SYM_UNION.matcher(text).replaceAll(" union ");
        text = SYM_INTERSECTION.matcher(text).replaceAll(" intersection ");
        text = SYM_LOGICAL_NOT.matcher(text).replaceAll(" not ");
        text = SYM_SET_AND.matcher(text).replaceAll(" and ");
        text = SYM_SET_OR.matcher(text).replaceAll(" or ");
        text = SYM_CENT.matcher(text).replaceAll(" cents ");
        text = SYM_YEN.matcher(text).replaceAll(" yen ");
        text = SYM_FLORIN.matcher(text).replaceAll(" florin ");
        return text;
    }

    public static String normalizeIndicDigits(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            char ascii = 0;
            if (c >= 0x0966 && c <= 0x0D6F) {
                if (c <= 0x096F) ascii = (char) ('0' + (c - 0x0966)); // Devanagari ०-९
                else if (c >= 0x09E6 && c <= 0x09EF) ascii = (char) ('0' + (c - 0x09E6)); // Bengali ০-৯
                else if (c >= 0x0A66 && c <= 0x0A6F) ascii = (char) ('0' + (c - 0x0A66)); // Gurmukhi ੦-੯
                else if (c >= 0x0AE6 && c <= 0x0AEF) ascii = (char) ('0' + (c - 0x0AE6)); // Gujarati ૦-૯
                else if (c >= 0x0B66 && c <= 0x0B6F) ascii = (char) ('0' + (c - 0x0B66)); // Odia ୦-୯
                else if (c >= 0x0BE6 && c <= 0x0BEF) ascii = (char) ('0' + (c - 0x0BE6)); // Tamil ௦-௯
                else if (c >= 0x0C66 && c <= 0x0C6F) ascii = (char) ('0' + (c - 0x0C66)); // Telugu ౦-౯
                else if (c >= 0x0CE6 && c <= 0x0CEF) ascii = (char) ('0' + (c - 0x0CE6)); // Kannada ೦-೯
                else if (c >= 0x0D66 && c <= 0x0D6F) ascii = (char) ('0' + (c - 0x0D66)); // Malayalam ൦-൯
            }

            if (ascii != 0) {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(text, 0, i);
                }
                sb.append(ascii);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    /**
     * Drops any UTF-16 surrogate code unit that isn't part of a valid
     * high/low surrogate pair, leaving well-formed text otherwise untouched.
     * A trailing high surrogate with no low surrogate after it (or vice
     * versa) most often comes from a clipboard paste or IME composition
     * truncated mid-codepoint.
     */
    static String stripUnpairedSurrogates(String text) {
        if (text == null) return null;
        StringBuilder sb = null;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            boolean drop = false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    drop = true;
                }
            } else if (Character.isLowSurrogate(c)) {
                if (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1))) {
                    drop = true;
                }
            }
            if (drop && sb == null) {
                sb = new StringBuilder(length);
                sb.append(text, 0, i);
            }
            if (sb != null && !drop) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    private static final String[] NATO_PHONETICS = {
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf",
            "Hotel", "India", "Juliett", "Kilo", "Lima", "Mike", "November",
            "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"
    };

    public static String expandNatoSpelling(String text) {
        if (text == null) return text;
        String trimmed = text.trim();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return c + ", " + NATO_PHONETICS[c - 'a'];
            } else if (c >= 'A' && c <= 'Z') {
                return c + ", " + NATO_PHONETICS[c - 'A'];
            }
        }
        return text;
    }

    public static String expandDevanagariDiacritic(String text) {
        if (text == null) return text;
        String trimmed = text.trim();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            switch (c) {
                case '\u093E': return "आ की मात्रा"; // ा
                case '\u093F': return "इ की मात्रा"; // ि
                case '\u0940': return "ई की मात्रा"; // ी
                case '\u0941': return "उ की मात्रा"; // ु
                case '\u0942': return "ऊ की मात्रा"; // ू
                case '\u0943': return "ऋ की मात्रा"; // ृ
                case '\u0947': return "ए की मात्रा"; // े
                case '\u0948': return "ऐ की मात्रा"; // ै
                case '\u094B': return "ओ की मात्रा"; // ो
                case '\u094C': return "औ की मात्रा"; // ौ
                case '\u0902': return "अनुस्वार"; // ं
                case '\u0903': return "विसर्ग"; // ः
                case '\u0901': return "चन्द्रबिन्दु"; // ँ
                case '\u094D': return "हलन्त"; // ्
                case '\u093C': return "नुक्ता"; // ़
            }
        }
        return text;
    }

    public static class ScriptSpan {
        public final String text;
        public final boolean isLatin;

        public ScriptSpan(String text, boolean isLatin) {
            this.text = text;
            this.isLatin = isLatin;
        }
    }

    public static boolean hasMixedLatinAndIndic(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        boolean hasLatin = false;
        boolean hasIndic = false;
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLatin = true;
            } else if (c >= 0x0900 && c <= 0x0D7F) {
                hasIndic = true;
            }
            if (hasLatin && hasIndic) {
                return true;
            }
        }
        return false;
    }

    public static List<ScriptSpan> splitByScriptRuns(String text) {
        List<ScriptSpan> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }

        final int len = text.length();
        int spanStart = 0;
        Boolean currentIsLatin = null;

        for (int i = 0; i < len; ) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            boolean isLatinChar = (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
            boolean isIndicChar = (cp >= 0x0900 && cp <= 0x0D7F);

            if (isLatinChar || isIndicChar) {
                boolean charIsLatin = isLatinChar;
                if (currentIsLatin == null) {
                    currentIsLatin = charIsLatin;
                } else if (currentIsLatin != charIsLatin) {
                    String spanText = text.substring(spanStart, i);
                    if (!spanText.trim().isEmpty()) {
                        spans.add(new ScriptSpan(spanText, currentIsLatin));
                        spanStart = i;
                        currentIsLatin = charIsLatin;
                    }
                }
            }
            i += charCount;
        }

        if (spanStart < len) {
            String remaining = text.substring(spanStart);
            if (!remaining.isEmpty()) {
                spans.add(new ScriptSpan(remaining, currentIsLatin != null ? currentIsLatin : false));
            }
        }

        return spans;
    }

    public static boolean containsIndianNuanceChars(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0x0900 && c <= 0x0D7F) || c == 0x20B9 || c == '/' || c == ',' ||
                c == 'k' || c == 'K' || c == 'l' || c == 'L' || c == 'c' || c == 'C' ||
                c == 'r' || c == 'R' || c == 's' || c == 'S' || c == 'i' || c == 'I') {
                return true;
            }
        }
        return false;
    }

    /**
     * Preprocesses Indian-specific textual nuances and common technical syntax before synthesis:
     * 1. Normalizes native Indic numerals across 9 scripts to ASCII 0-9.
     * 2. Inserts spacing after Danda (।) and Double Danda (॥) if directly adjacent to text.
     * 3. Separates slash-concatenated banking tokens (UPI/423891028341/PAYTM -> UPI / 423891028341 / PAYTM).
     * 4. Normalizes currency prefixes (₹500, Rs. 500, INR 500 -> 500 rupees), stripping commas.
     * 5. Normalizes Indian comma grouping (1,00,000 -> 100000).
     * 6. Expands common Indian shorthand quantities (10k -> 10 thousand, 5L -> 5 lakh, 2cr -> 2 crore).
     */
    public static String preprocessIndianText(String text) {
        if (text == null || text.isEmpty() || !containsIndianNuanceChars(text)) {
            return text;
        }
        text = normalizeIndicDigits(text);
        text = DANDA_BOUNDARY.matcher(text).replaceAll("$1 $2");
        java.util.regex.Matcher txnMatcher = BANKING_SLASH_TXN.matcher(text);
        if (txnMatcher.find()) {
            // StringBuffer, not StringBuilder: Matcher.appendReplacement/appendTail only
            // gained StringBuilder overloads in API 34; the StringBuffer ones work on any
            // API level and this loop is never multi-threaded, so there's no downside.
            StringBuffer sb = new StringBuffer();
            do {
                String expanded = SLASH_RUN.matcher(txnMatcher.group(0)).replaceAll(" / ");
                txnMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(expanded));
            } while (txnMatcher.find());
            txnMatcher.appendTail(sb);
            text = sb.toString();
        }

        // Normalize Indian currency prefixes, stripping grouping commas from the figure
        java.util.regex.Matcher currMatcher = CURRENCY_PREFIX.matcher(text);
        if (currMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String amount = currMatcher.group(1).replace(",", "");
                currMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(amount + " rupees"));
            } while (currMatcher.find());
            currMatcher.appendTail(sb);
            text = sb.toString();
        }

        // Normalize Indian number comma groupings (e.g. 1,00,000 -> 100000)
        java.util.regex.Matcher numMatcher = INDIAN_NUMBER_COMMAS.matcher(text);
        if (numMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String normalizedNum = numMatcher.group(0).replace(",", "");
                numMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(normalizedNum));
            } while (numMatcher.find());
            numMatcher.appendTail(sb);
            text = sb.toString();
        }

        text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 thousand");
        text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 lakh");
        text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 crore");
        return text;
    }

    private static boolean containsSmartCodeKeyword(String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        return SMART_CODE_KEYWORD.matcher(context).find();
    }


    /**
     * Sets off each run of emoji from the surrounding sentence with a light
     * pause (", "), so eSpeak's own emoji dictionary description (e.g. "😂"
     * -&gt; "face with tears of joy") reads as an aside rather than plain
     * sentence text. Without this, "I'm happy 😀 today" is indistinguishable
     * by ear from someone literally describing a face - "I'm happy, grinning
     * face, today" makes clear to a blind listener that a symbol was there.
     */
    public static String clarifyEmojiAnnouncements(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        final StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final int codePoint = text.codePointAt(i);
            if (!isEmojiCodePoint(codePoint)) {
                out.appendCodePoint(codePoint);
                i += Character.charCount(codePoint);
                continue;
            }

            final int runStart = i;
            while (i < len) {
                final int c = text.codePointAt(i);
                if (!isEmojiCodePoint(c)) break;
                i += Character.charCount(c);
            }

            // Only add a leading pause if the emoji isn't already at the very
            // start of the text or right after existing punctuation/space.
            final int lastOut = out.length() - 1;
            if (lastOut >= 0) {
                char prev = out.charAt(lastOut);
                if (prev != ' ' && prev != ',' && prev != '.' && prev != '!' && prev != '?' && prev != ':' && prev != ';') {
                    out.append(',');
                }
                if (prev != ' ') {
                    out.append(' ');
                }
            }

            // Separate adjacent emojis in the run with spaces so eSpeak announces each one distinctly
            for (int j = runStart; j < i; ) {
                int cp = text.codePointAt(j);
                if (j > runStart) {
                    out.append(' ');
                }
                out.appendCodePoint(cp);
                j += Character.charCount(cp);
            }

            // Only add a trailing pause if more text follows and it isn't
            // already punctuation (avoids ",." or ",," doubling up).
            if (i < len) {
                char next = text.charAt(i);
                if (next != ' ' && next != ',' && next != '.' && next != '!' && next != '?' && next != ':' && next != ';') {
                    out.append(',');
                }
                if (next != ' ') {
                    out.append(' ');
                }
            }
        }
        return out.toString();
    }

    /**
     * Inserts spaces between adjacent digits so that eSpeak reads each digit
     * individually (e.g. "123" becomes "1 2 3").
     *
     * <p>Supports all Unicode decimal digit ranges (ASCII 0-9, Arabic-Indic
     * ٠-٩, Extended Arabic-Indic ۰-۹, Devanagari ०-९, etc.) as classified by
     * {@link Character#isDigit(int)}.
     */
    public static String spaceSeparateDigits(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        StringBuilder out = new StringBuilder(len * 2);
        boolean prevWasDigit = false;
        for (int i = 0; i < len; ) {
            final int c = text.codePointAt(i);
            final int charCount = Character.charCount(c);
            final boolean isDigit = Character.isDigit(c);
            if (isDigit && prevWasDigit) {
                out.append(' ');
            }
            out.appendCodePoint(c);
            prevWasDigit = isDigit;
            i += charCount;
        }
        return out.toString();
    }

    /**
     * Intelligently detects verification codes, OTPs, and PINs (4-to-8 digit runs
     * surrounded by a keyword like "OTP", "PIN", "code", "verification") and
     * space-separates only those numbers so they are read digit-by-digit, while
     * preserving natural reading for normal quantities ("25 items", "year 2024",
     * "₹150000 credited") that happen to have the same digit count but no
     * such keyword nearby. Also expands the Indian Rupee symbol (₹) to "rupees".
     */
    public static String spaceSeparateSmartCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                int runStart = i;
                int digitCount = 0;
                while (i < len) {
                    int c = text.codePointAt(i);
                    if (!Character.isDigit(c)) break;
                    digitCount++;
                    i += Character.charCount(c);
                }
                int runEnd = i;

                boolean separate = false;
                if (digitCount >= 4 && digitCount <= 8) {
                    int contextStart = Math.max(0, runStart - 25);
                    String prefix = text.substring(contextStart, runStart);
                    int contextEnd = Math.min(len, runEnd + 25);
                    String suffix = text.substring(runEnd, contextEnd);
                    if (containsSmartCodeKeyword(prefix) || containsSmartCodeKeyword(suffix)) {
                        separate = true;
                    }
                }

                if (separate) {
                    for (int j = runStart; j < runEnd; ) {
                        int c = text.codePointAt(j);
                        if (j > runStart) {
                            out.append(' ');
                        }
                        out.appendCodePoint(c);
                        j += Character.charCount(c);
                    }
                } else {
                    out.append(text, runStart, runEnd);
                }
            } else {
                if (cp == 0x20B9) { // '₹' Indian Rupee symbol
                    out.append(" rupees ");
                } else {
                    out.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    protected void rebuildAvailableVoices() {
        synchronized (mAvailableVoices) {
            mAvailableVoices.clear();
            List<Voice> voices = mAllVoices;
            if (mPreferences != null) {
                voices = LanguageSettings.filterVoices(mAllVoices, mPreferences);
            }
            for (Voice voice : voices) {
                mAvailableVoices.put(voice.name, voice);
            }
            if (DEBUG) {
                Set<String> selected = LanguageSettings.getSelectedLanguages(mPreferences);
                Log.i(TAG, "Rebuilt voices: selected=" + (selected == null ? "ALL" : selected.size()) +
                        ", exposed=" + mAvailableVoices.size());
            }
            if (mMatchingVoice != null && !mAvailableVoices.containsKey(mMatchingVoice.name)) {
                mMatchingVoice = null;
            }
        }
    }

    /**
     * Pipes synthesizer output from native eSpeak to an {@link AudioTrack}.
     */
    private final SpeechSynthesis.SynthReadyCallback mSynthCallback = new SynthReadyCallback() {
        @Override
        public void onSynthDataReady(byte[] audioData) {
            if ((audioData == null) || (audioData.length == 0)) {
                onSynthDataComplete();
                return;
            }

            if (mCallback == null || mCallbackDone.get() || mIsStopped.get()) {
                return;
            }

            if (mAudioOptimizer != null) {
                mAudioOptimizer.process(audioData, audioData.length);
            }

            final int maxBytesToCopy = Math.max(mCallback.getMaxBufferSize(), 512);

            int offset = 0;

            while (offset < audioData.length) {
                if (mIsStopped.get() || mCallbackDone.get()) {
                    return;
                }
                final int bytesToWrite = Math.min(maxBytesToCopy, (audioData.length - offset));
                if (mCallback.audioAvailable(audioData, offset, bytesToWrite)
                        != TextToSpeech.SUCCESS) {
                    // The framework has stopped accepting audio for this
                    // request, so the rest of the buffer has nowhere to go.
                    // A stop normally reaches the engine through onStop();
                    // stopping here as well covers a failure that arrives
                    // without one.
                    mEngine.stop();
                    return;
                }
                offset += bytesToWrite;
            }
        }

        @Override
        public void onSynthDataComplete() {
            if (mSegmentsRemaining.decrementAndGet() <= 0 || mIsStopped.get()) {
                if (mCallback != null && mCallbackDone.compareAndSet(false, true)) {
                    mCallback.done();
                }
            }
        }

        @Override
        public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
            if (mSynthText == null || mCallback == null || mCallbackDone.get() || mIsStopped.get()) {
                return;
            }

            // eSpeak counts code points from 1, rangeStart() wants 0-based UTF-16
            // indices into the text the caller supplied.
            final int wordStart = textPosition - 1;
            int start = codePointToOffset(wordStart);
            int end = codePointToOffset(wordStart + Math.max(textLength, 0));
            if (mSynthNormalization != null) {
                // The engine spoke normalized text; report the range against
                // the original so highlighting tracks the caller's string.
                start = mSynthNormalization.toOriginalOffset(start);
                end = mSynthNormalization.toOriginalOffset(end);
            }

            int finalStart = mSynthTextOffset + start;
            int finalEnd = mSynthTextOffset + end;
            if (mOriginalTextLength > 0) {
                finalStart = Math.max(0, Math.min(mOriginalTextLength, finalStart));
                finalEnd = Math.max(0, Math.min(mOriginalTextLength, finalEnd));
            }
            if (finalEnd <= finalStart) {
                return;
            }

            try {
                mCallback.rangeStart(markerInFrames, finalStart, finalEnd);
            } catch (Throwable ignored) {
            }
        }
    };
}
