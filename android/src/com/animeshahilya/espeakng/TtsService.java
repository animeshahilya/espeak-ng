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
import java.util.Collections;
import java.util.HashMap;
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
     * Offset map from {@link #mSynthText} back to the text the caller
     * supplied, accumulated across every preprocessing step that ran (user
     * dictionary, NATO spelling, Indian numbering, Unicode normalization,
     * ...); null when {@link #mSynthText} is identical to the caller's text.
     */
    private TextOffsetMap mSynthOffsetMap;
    /** Number of code points in {@link #mSynthText}. */
    private int mSynthTextCodePoints;
    /** Anchor for incremental code point to UTF-16 index conversion. */
    private int mAnchorCodePoint;
    private int mAnchorOffset;
    /**
     * Code-point offset of the chunk currently being synthesized within
     * {@link #mSynthText}. Synthesis is synchronous, so every word callback
     * belongs to the chunk whose base is set here; 0 for single-chunk
     * requests. Without this, word boundaries for chunks after the first
     * would be reported against the wrong part of the text.
     */
    private int mChunkBase;

    private List<Voice> mAllVoices = new ArrayList<Voice>();
    private final Map<String, Voice> mAvailableVoices = new HashMap<String, Voice>();
    /**
     * Cached framework voice list built by {@link #onGetVoices}. The
     * framework polls voices often; rebuilding ~120 Locale/HashSet/Voice
     * objects per call is wasteful. Invalidated in
     * {@link #rebuildAvailableVoices}, the only mutator of mAvailableVoices.
     * Guarded by the mAvailableVoices monitor.
     */
    private List<android.speech.tts.Voice> mCachedFrameworkVoices = null;
    // Protected (not private) as a test hook: eSpeakTests subclasses read and
    // drive voice selection through these members.
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
            if (intent == null
                    || !DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED.equals(intent.getAction())) {
                return;
            }
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
        storageContext = EspeakApp.requireStorageContext(this);

        mPreferences = PreferenceManager.getDefaultSharedPreferences(storageContext);
        mPreferences.registerOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        CheckVoiceData.ensureVoiceData(storageContext);
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

        // Clear cached voice list since native engine is being reinitialized
        SpeechSynthesis.clearVoiceCache();

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
        return Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
    }

    @Override
    public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        final Voice match = getDefaultVoiceFor(language, country, variant).first;
        return (match == null) ? null : match.name;
    }

    @Override
    public List<android.speech.tts.Voice> onGetVoices() {
        rebuildAvailableVoices();
        synchronized (mAvailableVoices) {
            if (mCachedFrameworkVoices != null) {
                return new ArrayList<android.speech.tts.Voice>(mCachedFrameworkVoices);
            }
            List<android.speech.tts.Voice> voices = new ArrayList<android.speech.tts.Voice>(mAvailableVoices.size());
            for (Voice voice : mAvailableVoices.values()) {
                int quality = android.speech.tts.Voice.QUALITY_NORMAL;
                int latency = android.speech.tts.Voice.LATENCY_VERY_LOW;
                Locale locale = new Locale(voice.locale.getISO3Language(), voice.locale.getISO3Country(), voice.locale.getVariant());
                Set<String> features = onGetFeaturesForLanguage(locale.getLanguage(), locale.getCountry(), locale.getVariant());
                voices.add(new android.speech.tts.Voice(voice.name, voice.locale, quality, latency, false, features));
            }
            mCachedFrameworkVoices = Collections.unmodifiableList(voices);
            return new ArrayList<android.speech.tts.Voice>(voices);
        }
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
        if (callback != null && mCallbackDone.compareAndSet(false, true)) {
            callback.error(errorCode);
        }
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

    /**
     * Folds one preprocessing step's effect into the accumulated offset map,
     * or leaves it unchanged if the step didn't actually alter the text (the
     * common case, and cheap to check up front rather than diffing).
     */
    private static TextOffsetMap chainOffset(TextOffsetMap previous, String before, String after) {
        if (before.equals(after)) {
            return previous;
        }
        return TextOffsetMap.diff(before, after).composeWith(previous);
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

        // Snapshot: initializeTtsEngine() can swap mEngine on the voices-reload
        // thread while a synthesis is in flight; a null read here used to NPE
        // instead of reporting an error.
        final SpeechSynthesis engine = mEngine;
        if (engine == null) {
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }

        // Fast-path empty or whitespace-only utterances: avoid full voice/param setup
        // and JNI overhead for TalkBack spacers, empty lines, and blank elements.
        if (text.trim().isEmpty()) {
            callback.start(engine.getSampleRate(), engine.getAudioFormat(), engine.getChannelCount());
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

        final SharedPreferences prefs = mPreferences != null
                ? mPreferences
                : PreferenceManager.getDefaultSharedPreferences(storageContext);
        final VoiceSettings settings = new VoiceSettings(prefs, engine);

        // Detect SSML before normalizing. Real markup is ASCII, which NFKC
        // leaves untouched, but normalization can turn lookalikes such as a
        // fullwidth "＜ｓｐｅａｋ" into "<speak", and plain text must not
        // switch into SSML parsing because of that.
        final boolean isSsml = text.startsWith("<speak");

        // Accumulated map from the text as of each step back to the text the
        // caller supplied, so word-boundary offsets survive every step below
        // that can change the text's length. See TextOffsetMap.
        TextOffsetMap offsetMap = null;

        if (!isSsml) {
            // NVDA eSpeak driver fix: Strip control character 0x01, which eSpeak reserves
            // for embedded commands and whose presence causes pronunciation corruption or aborts.
            if (text.indexOf('\u0001') != -1) {
                String before = text;
                text = text.replace("\u0001", "");
                offsetMap = chainOffset(offsetMap, before, text);
            }
            // NVDA-style hardening (NVDA's _espeak.py encodes with errors="ignore" before
            // the native call): drop unpaired UTF-16 surrogates - e.g. from a clipboard paste
            // truncated mid-emoji - before they reach the JNI/native layer, which expects
            // well-formed text and can otherwise mis-decode or corrupt trailing output.
            String beforeSurrogates = text;
            text = stripUnpairedSurrogates(text);
            offsetMap = chainOffset(offsetMap, beforeSurrogates, text);
            // NVDA eSpeak driver fix: Prevent unintentional [[ phoneme syntax entry by
            // separating consecutive left brackets when not in phoneme mode.
            if (text.contains("[[")) {
                String before = text;
                text = text.replace("[[", "[ [");
                offsetMap = chainOffset(offsetMap, before, text);
            }
        }

        if (settings.isUnicodeNormalizationEnabled()) {
            UnicodeNormalization.Result normalization = UnicodeNormalization.normalize(text);
            if (normalization != null) {
                text = normalization.text;
                // Compose the normalizer's own boundary map directly: it is
                // exact, and avoids re-diffing two strings it already aligned.
                offsetMap = TextOffsetMap.fromBoundaryMap(normalization.boundaryMap())
                        .composeWith(offsetMap);
            }
        }

        // Zero-hang watchdog: sanitize hang-inducing controls (bidi, zero-width,
        // C0 controls) early so all downstream modules (UserDictionary, numbers,
        // currency, grouping) operate on clean text without corrupted matching.
        {
            String before = text;
            text = sanitizeForWatchdog(text, isSsml);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isUserDictionaryEnabled()) {
            String before = text;
            text = UserDictionaryManager.getInstance(storageContext).applyRules(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // A single-character utterance is how TalkBack (and NVDA on desktop)
        // signals character-by-character navigation, as opposed to normal
        // continuous reading of words/sentences.
        final boolean isSingleCharacterUtterance = !isSsml && (text.length() == 1 || text.trim().length() == 1);

        if (isSingleCharacterUtterance) {
            if (settings.isNatoSpellingEnabled()) {
                String before = text;
                text = expandNatoSpelling(text);
                offsetMap = chainOffset(offsetMap, before, text);
            }
            if (settings.isSpokenDiacriticsEnabled()) {
                String before = text;
                text = expandDevanagariDiacritic(text);
                offsetMap = chainOffset(offsetMap, before, text);
            }
        }

        if (!isSsml && settings.isSpeakProgrammingSymbolsEnabled()) {
            String before = text;
            text = expandProgrammingSymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isIndianNumberingEnabled()) {
            String before = text;
            text = preprocessIndianText(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isCurrencyEnabled()) {
            String before = text;
            text = expandCurrencySymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isTimeDateEnabled()) {
            String before = text;
            text = expandTimeDate(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Digit handling lives in one place: the grouping mode. "Single" is
        // exactly the old digit-by-digit behavior; the legacy boolean only
        // survives as a migration default inside getDigitGroupingMode().
        final boolean smartCodes = settings.isSmartCodesEnabled() && !isSsml;
        final int smartMin = settings.getSmartMinLen();
        final int smartMax = settings.getSmartMaxLen();
        final String digitGrouping = settings.getDigitGroupingMode();
        final boolean useGrouping = !isSsml && digitGrouping != null
                && !VoiceSettings.DIGIT_GROUP_OFF.equals(digitGrouping);
        if (useGrouping) {
            String before = text;
            text = formatDigitGrouping(text, digitGrouping, settings.getDigitGroupThreshold());
            offsetMap = chainOffset(offsetMap, before, text);
            if (smartCodes && !VoiceSettings.DIGIT_GROUP_SINGLE.equals(digitGrouping)) {
                before = text;
                text = spaceSeparateSmartCodes(text, smartMin, smartMax);
                offsetMap = chainOffset(offsetMap, before, text);
            }
        } else if (smartCodes) {
            String before = text;
            text = spaceSeparateSmartCodes(text, smartMin, smartMax);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Specialized modes: spelling / phonetic / code-reading. Explicit user
        // modes run after number handling so "A1B2" spells letters but keeps
        // the digit grouping already applied above.
        if (!isSsml && settings.isCodeReadingModeEnabled()) {
            String before = text;
            text = expandProgrammingSymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }
        if (!isSsml && settings.isSpellingModeEnabled()) {
            String before = text;
            text = expandSpellingMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        } else if (!isSsml && settings.isPhoneticModeEnabled()) {
            String before = text;
            text = expandPhoneticMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && containsPotentialEmoji(text)) {
            String before = text;
            text = settings.isEmojiIgnoreEnabled() ? filterEmojis(text) : clarifyEmojiAnnouncements(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        mSynthText = text;
        mSynthTextOffset = textOffset;
        mSynthOffsetMap = offsetMap;
        mSynthTextCodePoints = text.codePointCount(0, text.length());
        mAnchorCodePoint = 0;
        mAnchorOffset = 0;
        mChunkBase = 0;

        mCallback = callback;
        mCallbackDone.set(false);
        mIsStopped.set(false);
        int sampleRate = engine.getSampleRate();
        int startStatus = mCallback.start(sampleRate, engine.getAudioFormat(), engine.getChannelCount());
        if (startStatus != TextToSpeech.SUCCESS) {
            mCallback = null;
            return;
        }
        mAudioOptimizer = settings.isAudioOptimizerEnabled() && sampleRate > 0
                ? new AudioOptimizer(sampleRate, settings.getAudioProfile())
                : null;
        engine.setVoice(voice, settings.getVoiceVariant());

        int rate = settings.getRate();
        int rateScale = request.getSpeechRate();
        if (rateScale <= 0) {
            rateScale = 100;
        }
        // Force override: lock to the saved rate regardless of caller requests.
        if (!settings.isForceRateEnabled()) {
            rate = (int)(((long)rate * rateScale) / 100);
        }
        // Cap at the engine max (espeakRATE_MAXIMUM) unless rate boost is on.
        // This used to cap at 449 per NVDA issue #131, to avoid unintended
        // Sonic engagement at 450 WPM - but upstream #2165 moved Sonic
        // engagement strictly above 450, and #2355 made 450 the engine max,
        // so exactly 450 never engages Sonic (no shipped voice lowers
        // fast_settings below the 450 default either). NVDA still caps at
        // 449 out of caution for engines predating #2165; this app always
        // ships its own engine, so it follows the engine it ships.
        if (!settings.isRateBoostEnabled() && rate > 450) {
            rate = 450;
        }
        engine.Rate.setValue(rate);

        int pitchScale = request.getPitch();
        if (pitchScale <= 0) {
            pitchScale = 100;
        }
        if (settings.isForcePitchEnabled()) {
            pitchScale = 100;
        }
        engine.Pitch.setValue(settings.getPitch(), pitchScale);

        // Optional accessibility aid (espeak-ng community issue #1658): widen
        // the pitch rise on questions/exclamations for hard-of-hearing
        // listeners. Boosts PitchRange (inflection magnitude) rather than
        // Pitch (which would shift the whole register) - this amplifies
        // whatever rise the engine's own intonation model already produces
        // for the sentence instead of adding a separate mechanism. Scoped
        // per full utterance, same as Capitals-pitch below: a screen reader
        // normally calls onSynthesizeText once per sentence already, so a
        // mixed multi-sentence request only sees this if it ends in ?/!.
        int pitchRange = settings.getPitchRange();
        if (!isSsml && settings.isEmphasizeQuestionsEnabled() && endsWithQuestionOrExclamation(text)) {
            int max = engine.PitchRange.getMaxValue();
            pitchRange = Math.min(max, pitchRange + Math.max(1, pitchRange / 5));
        }
        engine.PitchRange.setValue(pitchRange);

        // Accessibility volume ducking support (KEY_PARAM_VOLUME)
        float volumeScale = 1.0f;
        final Bundle params = request.getParams();
        if (!settings.isForceVolumeEnabled() && params != null) {
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
        engine.Volume.setValue(targetVolume);

        engine.Punctuation.setValue(settings.getPunctuationLevel());
        // Code-reading mode forces full punctuation announcement regardless of
        // the global preset, so symbols in source code are never swallowed.
        if (!isSsml && settings.isCodeReadingModeEnabled()) {
            engine.Punctuation.setValue(SpeechSynthesis.PUNCT_ALL);
        }
        engine.setPunctuationCharacters(settings.getPunctuationCharacters());
        // Announcing capitalization (by pitch, beep, or saying "capital") only
        // makes sense while spelling out individual characters - NVDA's own
        // capPitchChange/beepForCapitals/sayCapForCapitals behave the same
        // way, scoped to its character-navigation code path only. eSpeak's
        // own espeakCAPITALS has no such scoping built in: left as-is, it
        // fires on every capitalized WORD during ordinary continuous reading
        // (sentence starts, names, acronyms...), which is a much more
        // pervasive and, per user feedback, distracting application of the
        // same cue than any polished screen reader actually does.
        engine.Capitals.setValue(isSingleCharacterUtterance ? settings.getCapitals() : 0);
        engine.WordGap.setValue(settings.getWordGap());

        boolean enableBilingual = settings.isBilingualSwitchingEnabled() && !isSsml;
        List<ScriptSpan> spans = null;
        Voice latinVoice = null;
        Voice indicVoice = null;
        if (enableBilingual && hasMixedLatinAndIndic(text)) {
            synchronized (mAvailableVoices) {
                Voice secondary = mAvailableVoices.get(settings.getSecondaryVoice());
                // Route each script to a voice that can actually read it. The
                // old code always read Indic spans with the primary voice, so
                // en-in + Hindi mixes mumbled the Hindi through an English
                // phoneme table. Now Indic spans prefer an Indic voice
                // (primary if Indic, else the secondary if Indic, else Hindi
                // as a last resort) and Latin spans prefer a Latin voice.
                boolean primaryIndic = isIndicVoice(voice);
                boolean secondaryIndic = isIndicVoice(secondary);
                if (primaryIndic) {
                    indicVoice = voice;
                    latinVoice = (secondary != null && !secondary.name.equals(voice.name))
                            ? secondary : mAvailableVoices.get("en-in");
                } else {
                    latinVoice = voice;
                    if (secondaryIndic) {
                        indicVoice = secondary;
                    } else {
                        indicVoice = mAvailableVoices.get("hi");
                    }
                }
                if (latinVoice == null) latinVoice = voice;
                if (indicVoice == null) indicVoice = voice;
            }
            if (!latinVoice.name.equals(indicVoice.name)) {
                spans = splitByScriptRuns(text);
            }
        }

        // Zero-hang watchdog: never hand the native engine one giant buffer.
        // Bilingual spans already split by script; anything else over the
        // chunk limit is split at clause boundaries. Rapid swipes just queue
        // short bounded units instead of one unbounded synth call. SSML is
        // never chunked: splitting markup across units would corrupt it.
        List<String> units = new ArrayList<>();
        List<Voice> unitVoices = new ArrayList<>();
        List<Integer> unitBases = new ArrayList<>();
        if (isSsml) {
            units.add(text);
            unitVoices.add(voice);
            unitBases.add(0);
        } else if (spans != null && spans.size() > 1 && latinVoice != null && indicVoice != null) {
            int base = 0;
            for (ScriptSpan span : spans) {
                Voice spanVoice = span.isLatin ? latinVoice : indicVoice;
                int spanBase = base;
                base += span.text.codePointCount(0, span.text.length());
                for (String chunk : chunkForWatchdog(span.text)) {
                    units.add(chunk);
                    unitVoices.add(spanVoice);
                    unitBases.add(spanBase);
                    spanBase += chunk.codePointCount(0, chunk.length());
                }
                if (units.size() >= MAX_CHUNKS) break;
            }
        } else {
            engine.setVoice(voice, settings.getVoiceVariant());
            int base = 0;
            for (String chunk : chunkForWatchdog(text)) {
                units.add(chunk);
                unitVoices.add(voice);
                unitBases.add(base);
                base += chunk.codePointCount(0, chunk.length());
            }
        }

        if (units.size() > 1) {
            mSegmentsRemaining.set(units.size());
            for (int ui = 0; ui < units.size(); ui++) {
                if (mIsStopped.get()) {
                    break;
                }
                try {
                    mChunkBase = unitBases.get(ui);
                    engine.setVoice(unitVoices.get(ui), settings.getVoiceVariant());
                    engine.synthesize(units.get(ui), false);
                } catch (Throwable t) {
                    // One bad chunk (mixed-script edge case) must never kill
                    // the whole request or hang the service — skip and continue.
                    if (DEBUG) Log.w(TAG, "Chunk synth failed, skipping", t);
                }
            }
        } else {
            mSegmentsRemaining.set(1);
            mChunkBase = unitBases.isEmpty() ? 0 : unitBases.get(0);
            try {
                engine.synthesize(text, isSsml);
            } catch (Throwable t) {
                if (DEBUG) Log.w(TAG, "Synth failed", t);
                reportError(callback, TextToSpeech.ERROR_SERVICE);
            }
        }

        // Guaranteed terminal callback: if neither onSynthDataComplete() nor
        // reportError() has signaled completion yet (e.g. native synthesis
        // error, empty string, or early stop), finalize here so the framework
        // is never hung waiting for the request to end.
        if (mCallback != null && mCallbackDone.compareAndSet(false, true)) {
            mCallback.done();
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

    // "code" is deliberately not a keyword on its own: it's an ordinary
    // English word ("dress code", "zip code", "area code") common enough
    // to false-positive on plain sentences with a nearby number (e.g. a
    // year), and every real OTP/verification-code message already matches
    // via a more specific word below (verification, security, pin,
    // passcode), so dropping it loses no real detections.
    private static final java.util.regex.Pattern SMART_CODE_KEYWORD =
            java.util.regex.Pattern.compile("(?i)\\b(otp|pin|passcode|password|secret|verification|security|token|login|id|txn|ref|vpa|cvv)\\b");

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

    // Smart text: currency symbols ($/€/£/¥ beyond the ₹ handled above), time, and dates.
    private static final java.util.regex.Pattern CURRENCY_DOLLAR_PREFIX =
            java.util.regex.Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)");
    private static final java.util.regex.Pattern CURRENCY_DOLLAR_SUFFIX =
            java.util.regex.Pattern.compile("([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:dollars?|USD)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CURRENCY_EURO =
            java.util.regex.Pattern.compile("(?:€\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:€|euros?|EUR\\b))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CURRENCY_POUND =
            java.util.regex.Pattern.compile("(?:£\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:£|pounds?|GBP\\b))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CURRENCY_YEN =
            java.util.regex.Pattern.compile("(?:¥\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:¥|yen|JPY\\b))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TIME_HM =
            java.util.regex.Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([AP])\\.?M\\.?)?",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DATE_NUMERIC =
            java.util.regex.Pattern.compile("\\b(\\d{1,4})[/\\-](\\d{1,2})[/\\-](\\d{1,4})\\b");
    // Edge-case controls that hang or corrupt synthesis on rapid swipes / mixed-script pastes.
    private static final java.util.regex.Pattern HANG_CONTROLS =
            java.util.regex.Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final java.util.regex.Pattern EDGE_BRACKET_RUN =
            java.util.regex.Pattern.compile("\\[{2,}|\\]{2,}");
    /** Longest single native synth call; longer input is chunked (watchdog). */
    static final int MAX_CHUNK_CHARS = 800;
    /** Absolute cap per request; beyond this the tail is dropped, never hung on. */
    static final int MAX_REQUEST_CHARS = 16000;
    /** Max chunks per request — bounds worst-case synthesis time on rapid swipes. */
    static final int MAX_CHUNKS = 20;

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

    /**
     * The language/country tag (e.g. "en-in", "hi") a voice's locale
     * corresponds to, matching the lowercase hyphenated form used for this
     * app's own language folder names - so it's what a user would naturally
     * type into a user-dictionary rule's language field.
     */
    static String languageTag(Voice voice) {
        if (voice == null || voice.locale == null) return "";
        String language = voice.locale.getLanguage();
        if (language == null) return "";
        language = language.toLowerCase(Locale.ROOT);
        String country = voice.locale.getCountry();
        if (country != null && !country.isEmpty()) {
            language += "-" + country.toLowerCase(Locale.ROOT);
        }
        return language;
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

    /** True when the voice's language is an Indic language (Devanagari/Bengali/Dravidian/...). */
    static boolean isIndicVoice(Voice voice) {
        if (voice == null || voice.locale == null) return false;
        String lang = voice.locale.getLanguage();
        if (lang == null) return false;
        lang = lang.toLowerCase(java.util.Locale.ROOT);
        // ISO 639-1 codes of the Indic languages eSpeak NG ships.
        return lang.equals("hi") || lang.equals("bn") || lang.equals("pa")
                || lang.equals("gu") || lang.equals("or") || lang.equals("mr")
                || lang.equals("ta") || lang.equals("te") || lang.equals("kn")
                || lang.equals("ml") || lang.equals("as") || lang.equals("ne")
                || lang.equals("ur") || lang.equals("sa") || lang.equals("sd")
                || lang.equals("ks") || lang.equals("kok") || lang.equals("mni")
                || lang.equals("sat");
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
                    if (i > spanStart) {
                        String spanText = text.substring(spanStart, i);
                        if (!spanText.isEmpty()) {
                            spans.add(new ScriptSpan(spanText, currentIsLatin));
                        }
                    }
                    spanStart = i;
                    currentIsLatin = charIsLatin;
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
     * True for Devanagari-script languages (Hindi, Marathi, Nepali, Sanskrit,
     * Konkani): number/currency units are emitted in Devanagari (लाख, करोड़,
     * रुपये, पैसे) so the voice reads natively instead of stumbling through
     * Latin transliterations. Every other language keeps Latin units.
     */
    public static boolean isDevanagariNumberLang(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return false;
        String base = languageTag.trim().toLowerCase(java.util.Locale.ROOT);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        return base.equals("hi") || base.equals("mr") || base.equals("ne")
                || base.equals("sa") || base.equals("kok");
    }

    /**
     * Verbalizes an Indian-comma-grouped figure using lakh/crore units, which
     * is how Indian English actually says these numbers ("1,00,000" is "one
     * lakh", not "one hundred thousand"). Only the grouping commas carry the
     * signal, so plain digit runs are never touched here.
     *
     * Decomposition leaves remainders below one lakh as digits for the engine
     * ("1,23,45,678" -&gt; "1 crore 23 lakh 45678"), since eSpeak verbalizes
     * small numbers naturally. Figures below one lakh strip to digits
     * ("10,000" -&gt; "10000": Western and Indian readings agree there).
     * Absurdly large figures (&gt; 999 crore) also strip, rather than
     * producing an unreadable word chain.
     */
    public static String indianGroupedNumberToWords(String grouped) {
        return indianGroupedNumberToWords(grouped, false);
    }

    public static String indianGroupedNumberToWords(String grouped, boolean devanagari) {
        String lakhWord = devanagari ? "लाख" : "lakh";
        String croreWord = devanagari ? "करोड़" : "crore";
        if (grouped == null || grouped.isEmpty()) return grouped;
        String digits = grouped.replace(",", "");
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return digits;
        }
        if (value < 100000 || value > 9999999999L) {
            return digits;
        }
        StringBuilder out = new StringBuilder();
        long crore = value / 10000000L;
        long rest = value % 10000000L;
        if (crore > 0) {
            out.append(crore).append(' ').append(croreWord);
            if (rest > 0) out.append(' ');
        }
        if (rest > 0) {
            long lakh = rest / 100000L;
            long rest2 = rest % 100000L;
            if (lakh > 0) {
                out.append(lakh).append(' ').append(lakhWord);
                if (rest2 > 0) out.append(' ').append(rest2);
            } else {
                out.append(rest2);
            }
        }
        return out.toString();
    }

    /**
     * "₹1,00,000" -&gt; "1 lakh rupees", "₹10.50" -&gt; "10 rupees 50 paise",
     * "₹500" -&gt; "500 rupees". The fractional part becomes paise only for a
     * 1-2 digit nonzero fraction; anything else stays with the engine
     * ("10.567" -&gt; "10.567 rupees" reads as "ten point five...").
     */
    public static String indianRupeeAmountToWords(String amount) {
        return indianRupeeAmountToWords(amount, false);
    }

    public static String indianRupeeAmountToWords(String amount, boolean devanagari) {
        String rupeesWord = devanagari ? "रुपये" : "rupees";
        String paiseWord = devanagari ? "पैसे" : "paise";
        if (amount == null || amount.isEmpty()) return " " + rupeesWord;
        int dot = amount.indexOf('.');
        String intPart = dot >= 0 ? amount.substring(0, dot) : amount;
        String fracPart = dot >= 0 ? amount.substring(dot + 1) : "";
        String intWords = intPart.contains(",")
                ? indianGroupedNumberToWords(intPart, devanagari)
                : intPart.replace(",", "");
        if (intWords.isEmpty()) intWords = "0";
        StringBuilder out = new StringBuilder(intWords).append(' ').append(rupeesWord);
        if (fracPart.length() >= 1 && fracPart.length() <= 2) {
            int paise = -1;
            try {
                paise = Integer.parseInt(fracPart);
            } catch (NumberFormatException ignored) {
            }
            if (paise > 0) {
                out.append(' ').append(paise).append(' ').append(paiseWord);
            } else if (paise < 0) {
                // Unparseable fraction: keep it verbatim instead of dropping value.
                out.append('.').append(fracPart);
            }
        } else if (!fracPart.isEmpty()) {
            // Long fractions ("10.567") stay decimal for the engine.
            return intWords + "." + fracPart + " " + rupeesWord;
        }
        return out.toString();
    }

    /**
     * Preprocesses Indian-specific textual nuances and common technical syntax before synthesis:
     * 1. Normalizes native Indic numerals across 9 scripts to ASCII 0-9.
     * 2. Inserts spacing after Danda (।) and Double Danda (॥) if directly adjacent to text.
     * 3. Separates slash-concatenated banking tokens (UPI/423891028341/PAYTM -> UPI / 423891028341 / PAYTM).
     * 4. Normalizes currency prefixes (₹500 -> 500 rupees, ₹10.50 -> 10 rupees 50 paise,
     *    ₹1,00,000 -> 1 lakh rupees), stripping commas via lakh/crore verbalization.
     * 5. Verbalizes Indian comma grouping (1,00,000 -> 1 lakh, 1,00,00,000 -> 1 crore).
     * 6. Expands common Indian shorthand quantities (10k -> 10 thousand, 5L -> 5 lakh, 2cr -> 2 crore).
     */
    public static String preprocessIndianText(String text) {
        return preprocessIndianText(text, "");
    }

    /**
     * @param languageTag BCP-47-ish tag of the synthesis voice ("hi", "en-in",
     *                    ...); Devanagari-script languages get native units
     *                    (लाख/करोड़/रुपये/पैसे), all others get Latin units.
     */
    public static String preprocessIndianText(String text, String languageTag) {
        if (text == null || text.isEmpty() || !containsIndianNuanceChars(text)) {
            return text;
        }
        final boolean devanagari = isDevanagariNumberLang(languageTag);
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

        // Normalize Indian currency prefixes. Indian-grouped figures verbalize
        // to lakh/crore ("₹1,00,000" -> "1 lakh rupees"); decimals become
        // paise ("₹10.50" -> "10 rupees 50 paise").
        java.util.regex.Matcher currMatcher = CURRENCY_PREFIX.matcher(text);
        if (currMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String amount = currMatcher.group(1);
                currMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        indianRupeeAmountToWords(amount, devanagari)));
            } while (currMatcher.find());
            currMatcher.appendTail(sb);
            text = sb.toString();
        }

        // Verbalize Indian number comma groupings (e.g. 1,00,000 -> 1 lakh);
        // plain thousands ("10,000") still strip to digits for natural reading.
        java.util.regex.Matcher numMatcher = INDIAN_NUMBER_COMMAS.matcher(text);
        if (numMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String grouped = numMatcher.group(0);
                String words = indianGroupedNumberToWords(grouped, devanagari);
                numMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(words));
            } while (numMatcher.find());
            numMatcher.appendTail(sb);
            text = sb.toString();
        }

        if (devanagari) {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 हज़ार");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 लाख");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 करोड़");
        } else {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 thousand");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 lakh");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 crore");
        }
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
     * Groups long digit runs for natural announcement.
     * single: "123" -&gt; "1 2 3". double/pairs: "123456" -&gt; "12 34 56".
     * triple: groups of three, but only when the run length reaches
     * {@code threshold} (e.g. a 10-digit mobile number grouped, a 4-digit
     * year left natural). Runs shorter than 4 digits are never regrouped.
     */
    public static String formatDigitGrouping(String text, String mode, int threshold) {
        if (text == null || text.isEmpty() || mode == null
                || VoiceSettings.DIGIT_GROUP_OFF.equals(mode)) {
            return text;
        }
        if (VoiceSettings.DIGIT_GROUP_SINGLE.equals(mode)) {
            return spaceSeparateDigits(text);
        }
        final int groupSize = VoiceSettings.DIGIT_GROUP_DOUBLE.equals(mode) ? 2 : 3;
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
                boolean regroup = digitCount >= 4
                        && (groupSize == 2 || digitCount >= Math.max(4, threshold));
                if (!regroup) {
                    out.append(text, runStart, runEnd);
                } else {
                    int groupCount = 0;
                    for (int j = runStart; j < runEnd; ) {
                        int c = text.codePointAt(j);
                        if (groupCount > 0 && groupCount % groupSize == 0) {
                            out.append(' ');
                        }
                        out.appendCodePoint(c);
                        groupCount++;
                        j += Character.charCount(c);
                    }
                }
            } else {
                out.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    /**
     * Cheap pre-scan shared by the currency/time/date expanders: every amount,
     * time, and numeric date contains a digit, so digit-free utterances (the
     * common TalkBack-navigation case) skip all of those regexes outright.
     */
    private static boolean containsDigit(String text) {
        final int len = text.length();
        for (int i = 0; i < len; ) {
            final int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Expands $/€/£/¥ amounts to words ("$5" -&gt; "5 dollars"). Commas stripped. */
    public static String expandCurrencySymbols(String text) {
        if (text == null || text.isEmpty() || !containsDigit(text)) return text;
        text = CURRENCY_DOLLAR_PREFIX.matcher(text).replaceAll("$1 dollars");
        // Suffix form ("5 USD", "5 dollars"): normalizes to "5 dollars".
        // Idempotent on already-expanded text, so pipeline re-runs are safe.
        text = CURRENCY_DOLLAR_SUFFIX.matcher(text).replaceAll("$1 dollars");
        text = CURRENCY_EURO.matcher(text).replaceAll("$1$2 euros");
        text = CURRENCY_POUND.matcher(text).replaceAll("$1$2 pounds");
        text = CURRENCY_YEN.matcher(text).replaceAll("$1$2 yen");
        return text;
    }

    /**
     * Natural time/date pronunciation: "10:30" -&gt; "10 30", "10:30 PM" keeps
     * the meridiem, numeric slash/dash dates ("15/01/2024", "2024-01-15") get
     * separators spaced so they read as number groups. Dots are deliberately
     * excluded: "1.2.3" is a version number, not a date.
     */
    public static String expandTimeDate(String text) {
        if (text == null || text.isEmpty() || !containsDigit(text)) return text;
        // Times need ':', dates need '/' or '-'; without one there is nothing to expand.
        boolean hasSeparator = false;
        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '/' || c == '-') {
                hasSeparator = true;
                break;
            }
        }
        if (!hasSeparator) return text;
        java.util.regex.Matcher tm = TIME_HM.matcher(text);
        if (tm.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String mer = tm.group(3);
                String rep = tm.group(1) + " " + tm.group(2)
                        + (mer != null ? " " + mer + " M" : "");
                tm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
            } while (tm.find());
            tm.appendTail(sb);
            text = sb.toString();
        }
        java.util.regex.Matcher dm = DATE_NUMERIC.matcher(text);
        if (dm.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String rep = dm.group(1) + " " + dm.group(2) + " " + dm.group(3);
                dm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
            } while (dm.find());
            dm.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private static final java.util.regex.Pattern SPACE_RUNS =
            java.util.regex.Pattern.compile(" {2,}");

    /** Spelling mode: "hi" -&gt; "h i" so each letter is announced. */
    public static String expandSpellingMode(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                if (out.length() > 0) {
                    int last = out.length() - 1;
                    if (out.charAt(last) != ' ') out.append(' ');
                }
                out.appendCodePoint(cp);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return SPACE_RUNS.matcher(out.toString()).replaceAll(" ");
    }

    /** Phonetic mode: each letter -&gt; NATO word ("AB" -&gt; "Alpha Bravo"). */
    public static String expandPhoneticMode(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() * 6);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                int idx = Character.toUpperCase(cp) - 'A';
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                out.append(NATO_PHONETICS[idx]);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * True if the last non-trailing-whitespace/quote/bracket character of
     * {@code text} is '?' or '!' (plain or fullwidth). Used to scope the
     * question/exclamation pitch-range boost (espeak-ng community issue
     * #1658) to the whole utterance, the same per-call scope the existing
     * Capitals-pitch handling above uses.
     */
    public static boolean endsWithQuestionOrExclamation(String text) {
        if (text == null) return false;
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ')' || c == ']'
                    || c == '”' || c == '’') {
                end--;
                continue;
            }
            break;
        }
        if (end == 0) return false;
        char last = text.charAt(end - 1);
        return last == '?' || last == '!' || last == '？' || last == '！';
    }

    /**
     * Zero-hang sanitize: strips bidi/zero-width/C0 controls and collapses
     * edge-case bracket runs that corrupt eSpeak's [[ phoneme parser or stall
     * mixed-script synthesis. Idempotent and safe to run on every request.
     */
    public static String sanitizeForWatchdog(String text) {
        return sanitizeForWatchdog(text, false);
    }

    /**
     * @param isSsml when true, only C0/bidi controls are stripped (XML forbids
     *               them anyway) while bracket runs are left intact, so SSML
     *               markup is never mangled.
     */
    public static String sanitizeForWatchdog(String text, boolean isSsml) {
        if (text == null || text.isEmpty()) return text;
        text = HANG_CONTROLS.matcher(text).replaceAll("");
        if (!isSsml && (text.contains("[[") || text.contains("]]"))) {
            text = EDGE_BRACKET_RUN.matcher(text).replaceAll(" ");
        }
        return text;
    }

    /**
     * Splits over-long input into speakable chunks at sentence/clause
     * boundaries so one rapid swipe or pasted document can never hang the
     * engine on a single giant espeak_Synth call. Always returns at least
     * one chunk; total capped by MAX_CHUNKS.
     *
     * The returned chunks are an exact sequential partition of the (possibly
     * capped) input - including whitespace-only pieces - so callers can map
     * per-chunk word positions back to full-text offsets by accumulation.
     */
    public static List<String> chunkForWatchdog(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        String capped = text.length() > MAX_REQUEST_CHARS
                ? text.substring(0, MAX_REQUEST_CHARS) : text;
        if (capped.length() <= MAX_CHUNK_CHARS) {
            chunks.add(capped);
            return chunks;
        }

        final int len = capped.length();
        int start = 0;
        while (start < len && chunks.size() < MAX_CHUNKS) {
            if (len - start <= MAX_CHUNK_CHARS) {
                chunks.add(capped.substring(start));
                break;
            }

            int targetEnd = start + MAX_CHUNK_CHARS;
            int cutPoint = -1;

            // 1. Scan backwards from targetEnd for sentence-ending punctuation or newlines
            for (int i = targetEnd - 1; i > start; i--) {
                char c = capped.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == ';' || c == '\n') {
                    int p = i + 1;
                    while (p < len && isPunctOrNewline(capped.charAt(p))) {
                        p++;
                    }
                    while (p < len && (capped.charAt(p) == ' ' || capped.charAt(p) == '\t')) {
                        p++;
                    }
                    if (p <= targetEnd) {
                        cutPoint = p;
                        break;
                    }
                }
            }

            // 2. Fall back to whitespace boundary if no punctuation boundary was found
            if (cutPoint <= start) {
                for (int i = targetEnd - 1; i > start; i--) {
                    if (capped.charAt(i) <= ' ') {
                        int p = i + 1;
                        while (p < len && capped.charAt(p) <= ' ') {
                            p++;
                        }
                        if (p <= targetEnd) {
                            cutPoint = p;
                            break;
                        }
                    }
                }
            }

            // 3. Fall back to hard split at targetEnd
            if (cutPoint <= start) {
                cutPoint = targetEnd;
            }

            chunks.add(capped.substring(start, cutPoint));
            start = cutPoint;
        }
        return chunks;
    }

    private static boolean isPunctOrNewline(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';' || c == '\n';
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
     * Intelligently detects verification codes, OTPs, and PINs (min-to-max digit runs
     * surrounded by a keyword like "OTP", "PIN", "code", "verification") and
     * space-separates only those numbers so they are read digit-by-digit, while
     * preserving natural reading for normal quantities ("25 items", "year 2024",
     * "₹150000 credited") that happen to have the same digit count but no
     * such keyword nearby. Also expands the Indian Rupee symbol (₹) to "rupees".
     */
    public static String spaceSeparateSmartCodes(String text) {
        return spaceSeparateSmartCodes(text, 4, 8);
    }

    public static String spaceSeparateSmartCodes(String text, int minLen, int maxLen) {
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
                if (digitCount >= Math.max(2, minLen) && digitCount <= Math.max(minLen, maxLen)) {
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

    // Protected (not private) as a test hook: eSpeakTests subclasses call
    // this to refresh the voice list without a full engine re-init.
    protected void rebuildAvailableVoices() {
        synchronized (mAvailableVoices) {
            // Invalidate the framework voice list BEFORE the (user-preference)
            // filter runs: mAvailableVoices must not keep serving the previous
            // selection if filterVoices() were to throw, and the field's
            // documented invariant is that a rebuild always produces a fresh
            // list.
            mCachedFrameworkVoices = null;
            mAvailableVoices.clear();
            List<Voice> voices = mAllVoices;
            if (mPreferences != null) {
                voices = LanguageSettings.filterVoices(mAllVoices, mPreferences);
            }
            for (Voice voice : voices) {
                mAvailableVoices.put(voice.name, voice);
            }
            if (DEBUG && mPreferences != null) {
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

            final SynthesisCallback callback = mCallback;
            if (callback == null) {
                return;
            }

            final int maxBytesToCopy = Math.max(callback.getMaxBufferSize(), 512);

            int offset = 0;

            while (offset < audioData.length) {
                if (mIsStopped.get() || mCallbackDone.get()) {
                    return;
                }
                final int bytesToWrite = Math.min(maxBytesToCopy, (audioData.length - offset));
                if (callback.audioAvailable(audioData, offset, bytesToWrite)
                        != TextToSpeech.SUCCESS) {
                    // The framework has stopped accepting audio for this
                    // request, so the rest of the buffer has nowhere to go.
                    // A stop normally reaches the engine through onStop();
                    // stopping here as well covers a failure that arrives
                    // without one. Local snapshot: the same brief
                    // initializeTtsEngine() null window documented in onStop().
                    final SpeechSynthesis engine = mEngine;
                    if (engine != null) {
                        engine.stop();
                    }
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
            // Local snapshot: the fields can be swapped by a fresh request on
            // the synth thread while this callback is in flight, and the
            // guard-then-use pattern below must observe one consistent pair.
            final String synthText = mSynthText;
            final SynthesisCallback callback = mCallback;
            if (synthText == null || callback == null || mCallbackDone.get() || mIsStopped.get()) {
                return;
            }

            // eSpeak counts code points from 1, rangeStart() wants 0-based UTF-16
            // indices into the text the caller supplied. The engine's position
            // is relative to the current chunk; mChunkBase re-bases it into
            // the full request text (0 for single-chunk requests).
            final int wordStart = textPosition - 1 + mChunkBase;
            int start = codePointToOffset(wordStart);
            int end = codePointToOffset(wordStart + Math.max(textLength, 0));
            final TextOffsetMap offsetMap = mSynthOffsetMap;
            if (offsetMap != null) {
                // The engine spoke text that one or more preprocessing steps
                // changed the length of; report the range against the
                // original so highlighting tracks the caller's string.
                start = offsetMap.toPrevious(start);
                end = offsetMap.toPrevious(end);
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
                callback.rangeStart(markerInFrames, finalStart, finalEnd);
            } catch (Throwable ignored) {
            }
        }
    };
}
