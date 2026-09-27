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

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;
import android.util.Pair;

import com.animeshahilya.espeakng.SpeechSynthesis.SynthReadyCallback;

import java.util.MissingResourceException;
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
    private Context mStorageContext;
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
    /** Caller text as handed to the preprocessor: what reading history keeps. */
    private String mHistoryText;
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

    /**
     * Piper neural voices (see PiperEngine). Process-wide, like the native
     * eSpeak engine: the settings screen's previews share its loaded models.
     */
    private final PiperEngine mPiper = PiperEngine.get();

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

    /**
     * Last voice selection, keyed by the request that produced it. Screen
     * readers issue the same voice+language on every utterance, and without
     * this each request re-ran the full voice scan in getDefaultVoiceFor()
     * (plus its fr/pt/vi second passes). Invalidated wherever the selection
     * can change outside selectVoice(): rebuildAvailableVoices() (voice set
     * changed), onLoadLanguage() and onLoadVoice() (the framework retargets
     * the selection directly). Volatile holder, so a concurrent invalidation
     * degrades to one redundant scan, never a torn read.
     */
    private static final class VoiceSelection {
        final String key;
        final Voice voice;
        final int result;

        VoiceSelection(String key, Voice voice, int result) {
            this.key = key;
            this.voice = voice;
            this.result = result;
        }
    }

    private volatile VoiceSelection mLastSelection = null;

    private static String voiceRequestKey(SynthesisRequest request) {
        // '\u0001' separators: engine voice names never contain it, so two
        // different requests cannot fold into the same key.
        return String.valueOf(request.getVoiceName()) + '\u0001'
                + String.valueOf(request.getLanguage()) + '\u0001'
                + String.valueOf(request.getCountry()) + '\u0001'
                + String.valueOf(request.getVariant());
    }

    private SharedPreferences mPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnPreferencesChanged =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (LanguageSettings.PREF_SUPPORTED_LANGUAGES.equals(key)
                            || LanguageSettings.PREF_FAVORITE_VOICES.equals(key)) {
                        // Favorites only reorder the cached framework list, but
                        // the same rebuild produces it, so share the path.
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
    /**
     * Guards {@link #mLanguagesUpdatedReceiver}: initializeTtsEngine() costs
     * seconds of disk/JNI work, and broadcasts can arrive back-to-back (bulk
     * voice import, restore, reinstall). Without this each broadcast spawns a
     * thread and they queue on the TtsService monitor re-running the same
     * re-init. A concurrent invalidation degrades to one redundant reload at
     * most: the loser re-checks the flag after the winner finishes.
     */
    private final AtomicBoolean mVoiceReloadRunning = new AtomicBoolean(false);

    private final BroadcastReceiver mLanguagesUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED.equals(intent.getAction())) {
                return;
            }
            if (!mVoiceReloadRunning.compareAndSet(false, true)) {
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (TtsService.this) {
                            initializeTtsEngine();
                        }
                    } finally {
                        mVoiceReloadRunning.set(false);
                    }
                }
            }, "espeak-voices-reload").start();
        }
    };

    /**
     * A natural voice was downloaded or deleted: forget the cached voice
     * list and, if it now speaks the current language, start loading it so
     * the next utterance can already use it.
     */
    private final BroadcastReceiver mPiperVoicesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            PiperVoiceStore.invalidate();
            final Voice current;
            synchronized (mAvailableVoices) {
                current = mMatchingVoice;
            }
            preloadNaturalVoice(current);
        }
    };

    private static final PiperEngine.Listener PIPER_LOG = new PiperEngine.Listener() {
        @Override
        public void onLoaded(String key, long millis) {
            Log.i(TAG, "Natural voice " + key + " loaded in " + millis + " ms");
        }

        @Override
        public void onLoadFailed(String key, Throwable error) {
            Log.e(TAG, "Natural voice " + key + " failed to load; using eSpeak", error);
        }

        @Override
        public void onMissingPhonemes(String key, List<String> phonemes) {
            // Phonemes this fork's rules produce that the voice was never
            // trained on (the fork's pronunciation fixes can do that); they
            // are skipped. Logged once per voice per process.
            Log.w(TAG, "Natural voice " + key + " has no ids for phonemes " + phonemes);
        }
    };

    /**
     * Starts loading the natural voice that speaks this eSpeak voice's
     * language, if one is chosen. Loading takes a second or two; until it
     * finishes eSpeak keeps speaking, so a screen reader is never silent.
     */
    private void preloadNaturalVoice(Voice voice) {
        if (voice == null || mPreferences == null || mStorageContext == null) {
            return;
        }
        final PiperVoiceStore.Installed natural =
                PiperVoiceStore.resolve(mStorageContext, mPreferences, voice);
        if (natural != null) {
            mPiper.preload(natural.key, natural.model(), natural.config);
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate() {
        super.onCreate();
        mStorageContext = EspeakApp.requireStorageContext(getApplicationContext());

        mPreferences = PreferenceManager.getDefaultSharedPreferences(mStorageContext);
        mPreferences.registerOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        CheckVoiceData.ensureVoiceData(mStorageContext);
        initializeTtsEngine();
        // Warm the user-dictionary singleton, emoji trie, and hinglish list off
        // the main/synth threads: without this the first synthesis request after
        // each process start pays that cost on the latency-critical path. Failure
        // is non-fatal - the lazy path will retry if a synthesis actually needs it.
        mPiper.setListener(PIPER_LOG);
        new Thread(() -> {
            try {
                // Natural voice for the system language first: it takes the
                // longest, and the first utterance is usually in that language.
                final Voice systemVoice = findVoice(Locale.getDefault().getISO3Language(),
                        "", "").first;
                preloadNaturalVoice(systemVoice);
            } catch (Throwable t) {
                Log.w(TAG, "Natural voice warmup failed", t);
            }
            try {
                UserDictionaryManager.getInstance(mStorageContext);
                NvdaEmoji.warmup();
                if (mPreferences.getBoolean(VoiceSettings.PREF_HINGLISH, false)) {
                    HinglishReader.warmup();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Data warmup failed", t);
            }
        }, "espeak-data-warmup").start();
        final IntentFilter filter = new IntentFilter(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED);
        // The 3-arg registerReceiver(..., flags) overload requires API 33 (Tiramisu);
        // this app's minSdk is 26, so it must fall back to the unflagged overload below
        // that API level. Pre-33 receivers are unexported by default anyway unless the
        // app explicitly requests otherwise, so this is not a behavior regression there.
        final IntentFilter piperFilter = new IntentFilter(PiperDownloads.ACTION_VOICES_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mLanguagesUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(mPiperVoicesReceiver, piperFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mLanguagesUpdatedReceiver, filter);
            registerReceiver(mPiperVoicesReceiver, piperFilter);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // A loaded natural voice is 60-150 MB of native memory. Under pressure
        // keep only the one in use: evicting that too would just mean a
        // reload (and eSpeak in the meantime) on the very next utterance.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            mPiper.trim(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mEngine != null) {
            mEngine.terminate();
            mEngine = null;
        }
        if (mPreferences != null) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        }
        try {
            unregisterReceiver(mLanguagesUpdatedReceiver);
            unregisterReceiver(mPiperVoicesReceiver);
        } catch (IllegalArgumentException e) {
            // Not registered (onCreate() never completed) - nothing to undo.
        }
    }

    /**
     * Sets up the native eSpeak engine.
     */
    private void initializeTtsEngine() {
        if (mEngine != null) {
            mEngine.terminate();
            mEngine = null;
        }

        // Clear cached voice list since native engine is being reinitialized
        SpeechSynthesis.clearVoiceCache();

        mEngine = new SpeechSynthesis(mStorageContext, mSynthCallback);
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
        if (voice == null || voice.locale == null) {
            return new String[] { "eng", "GBR", "" };
        }
        String language, country, variant;
        try {
            language = voice.locale.getISO3Language();
        } catch (MissingResourceException e) {
            language = "eng";
        }
        try {
            country = voice.locale.getISO3Country();
        } catch (MissingResourceException e) {
            country = "GBR";
        }
        variant = voice.locale.getVariant() != null ? voice.locale.getVariant() : "";
        return new String[] { language, country, variant };
    }

    /**
     * Build a query Locale from raw framework/engine codes (ISO 639-2/T like
     * "eng", ISO 3166 3-letter regions like "USA"). The deprecated constructor
     * is used deliberately: Locale.forLanguageTag would canonicalize legacy
     * codes or drop non-BCP47 parts, and Locale.Builder rejects 3-letter
     * regions - either would silently change voice matching.
     */
    @SuppressWarnings("deprecation")
    private static Locale legacyLocale(String language, String country, String variant) {
        return new Locale(language, country, variant);
    }

    private Pair<Voice, Integer> findVoice(String language, String country, String variant) {
        if (!CheckVoiceData.hasBaseResources(mStorageContext)) {
            return new Pair<>(null, TextToSpeech.LANG_MISSING_DATA);
        }

        // Null/empty codes from the framework must not crash the matcher.
        final Locale query = legacyLocale(
                language != null ? language : "",
                country != null ? country : "",
                variant != null ? variant : "");

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
        // Retargets the selection outside selectVoice(): drop its memo.
        mLastSelection = null;
        final Pair<Voice, Integer> match = getDefaultVoiceFor(language, country, variant);
        if (match.first != null) {
            synchronized (mAvailableVoices) {
                mMatchingVoice = match.first;
            }
            preloadNaturalVoice(match.first);
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
        // The cache below never hit: every call rebuilt (and invalidated)
        // first, so the framework's frequent polling re-ran the whole
        // Locale/HashSet/Voice construction each time. The set only changes
        // inside rebuildAvailableVoices(), which already invalidates, so a
        // cached list is exactly what a rebuild would produce.
        synchronized (mAvailableVoices) {
            if (mCachedFrameworkVoices != null) {
                return new ArrayList<android.speech.tts.Voice>(mCachedFrameworkVoices);
            }
        }
        rebuildAvailableVoices();
        synchronized (mAvailableVoices) {
            List<android.speech.tts.Voice> voices = new ArrayList<android.speech.tts.Voice>(mAvailableVoices.size());
            for (Voice voice : mAvailableVoices.values()) {
                int quality = android.speech.tts.Voice.QUALITY_NORMAL;
                int latency = android.speech.tts.Voice.LATENCY_VERY_LOW;
                Locale locale = legacyLocale(voice.locale.getISO3Language(), voice.locale.getISO3Country(), voice.locale.getVariant());
                Set<String> features = onGetFeaturesForLanguage(locale.getLanguage(), locale.getCountry(), locale.getVariant());
                voices.add(new android.speech.tts.Voice(voice.name, voice.locale, quality, latency, false, features));
            }
            // Favorite voices lead (in name order) so clients that present
            // the list top-first surface the user's pins; the rest keep
            // their existing order. Unknown/stale favorites are ignored.
            if (mPreferences != null) {
                final Set<String> favorites = LanguageSettings.getFavoriteVoices(mPreferences);
                if (!favorites.isEmpty()) {
                    final List<android.speech.tts.Voice> pinned = new ArrayList<android.speech.tts.Voice>();
                    final List<android.speech.tts.Voice> rest = new ArrayList<android.speech.tts.Voice>();
                    for (android.speech.tts.Voice v : voices) {
                        (favorites.contains(v.getName()) ? pinned : rest).add(v);
                    }
                    if (!pinned.isEmpty()) {
                        Collections.sort(pinned, (a, b) -> a.getName().compareTo(b.getName()));
                        pinned.addAll(rest);
                        voices = pinned;
                    }
                }
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
        // Retargets the selection outside selectVoice(): drop its memo.
        mLastSelection = null;
        final Voice voice;
        synchronized (mAvailableVoices) {
            voice = mAvailableVoices.get(name);
            if (voice == null) {
                return TextToSpeech.ERROR;
            }
            mMatchingVoice = voice;
        }
        preloadNaturalVoice(voice);
        return TextToSpeech.SUCCESS;
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
        mPiper.stop();
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
        final String key = voiceRequestKey(request);
        final VoiceSelection memo = mLastSelection;
        if (memo != null && memo.key.equals(key)) {
            synchronized (mAvailableVoices) {
                mMatchingVoice = memo.voice;
            }
            return memo.result;
        }
        final int result = selectVoiceUncached(request);
        final Voice selected;
        synchronized (mAvailableVoices) {
            selected = mMatchingVoice;
        }
        mLastSelection = new VoiceSelection(key, selected, result);
        return result;
    }

    private int selectVoiceUncached(SynthesisRequest request) {
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

    // BaseBundle.get(String) was deprecated in API 33, but no typed getter
    // preserves these reads: the debug dump takes arbitrary keys, and volume
    // defensively accepts Number or String. Both callers keep exact behavior.
    @SuppressWarnings("deprecation")
    private static void logSynthesisParams(Bundle params) {
        if (params == null) {
            return;
        }
        for (String key : params.keySet()) {
            Log.v(TAG,
                    "Synthesis request contained param {" + key + ", " + params.get(key) + "}");
        }
    }

    // See logSynthesisParams for why Bundle.get() stays here.
    @SuppressWarnings("deprecation")
    private static float getVolumeScale(Bundle params) {
        Object volObj = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME);
        if (volObj instanceof Number) {
            return ((Number) volObj).floatValue();
        } else if (volObj instanceof String) {
            try {
                return Float.parseFloat((String) volObj);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1.0f;
    }

    @Override
    protected synchronized void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (selectVoice(request) == TextToSpeech.ERROR) {
            reportError(callback, CheckVoiceData.hasBaseResources(mStorageContext)
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
        if (engine.getSampleRate() <= 0) {
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }

        // Fast-path empty or whitespace-only utterances: avoid full voice/param setup
        // and JNI overhead for TalkBack spacers, empty lines, and blank elements.
        if (VoiceSettings.isBlank(text)) {
            if (callback.start(engine.getSampleRate(), AudioFormat.ENCODING_PCM_16BIT, engine.getChannelCount())
                    != TextToSpeech.SUCCESS) {
                reportError(callback, TextToSpeech.ERROR_SERVICE);
            } else {
                callback.done();
            }
            return;
        }

        mOriginalTextLength = text.length();

        if (DEBUG) {
            Log.i(TAG, "Received synthesis request: {language=\"" + voice.name + "\"}");

            logSynthesisParams(request.getParams());
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
                : PreferenceManager.getDefaultSharedPreferences(mStorageContext);
        final VoiceSettings settings = new VoiceSettings(prefs, engine);

        // Sleep timer: while the mute window covers now, complete the request
        // as a successful empty utterance (the blank-text fast path's shape),
        // never an error, so clients keep working silently until expiry.
        if (VoiceSettings.isSleepMuted(prefs)) {
            if (callback.start(engine.getSampleRate(), AudioFormat.ENCODING_PCM_16BIT, engine.getChannelCount())
                    != TextToSpeech.SUCCESS) {
                reportError(callback, TextToSpeech.ERROR_SERVICE);
            } else {
                callback.done();
            }
            return;
        }

        // Detect SSML before normalizing. Real markup is ASCII, which NFKC
        // leaves untouched, but normalization can turn lookalikes such as a
        // fullwidth "＜ｓｐｅａｋ" into "<speak", and plain text must not
        // switch into SSML parsing because of that.
        final boolean isSsml = text.startsWith("<speak");

        // Accumulated map from the text as of each step back to the text the
        // caller supplied, so word-boundary offsets survive every step below
        // that can change the text's length. See TextOffsetMap.
        TextOffsetMap offsetMap = null;

        TextPreprocessor.Result prep = TextPreprocessor.process(
                text, voice, settings, isSsml, offsetMap, mStorageContext);
        text = prep.text;
        offsetMap = prep.offsetMap;
        final boolean isSingleCharacterUtterance = prep.isSingleCharacterUtterance;

        mSynthText = text;
        mHistoryText = text;
        mSynthTextOffset = textOffset;
        mSynthOffsetMap = offsetMap;
        mSynthTextCodePoints = text.codePointCount(0, text.length());
        mAnchorCodePoint = 0;
        mAnchorOffset = 0;
        mChunkBase = 0;

        // Natural voice for this language, when the user chose one and it is
        // already in memory. SSML stays with eSpeak (Piper has no markup
        // support), and so, by default, do single characters: character
        // navigation is where a screen reader user notices latency most.
        if (!isSsml && !(isSingleCharacterUtterance && PiperVoiceStore.espeakForCharacters(prefs))) {
            final PiperVoiceStore.Installed natural =
                    PiperVoiceStore.resolve(mStorageContext, prefs, voice);
            if (natural != null) {
                final PiperModel model = mPiper.getLoaded(natural.key);
                if (model != null) {
                    synthesizeNatural(request, callback, engine, settings, prefs, voice, natural,
                            model, text);
                    return;
                }
                // Not loaded yet: eSpeak speaks this one while it loads.
                mPiper.preload(natural.key, natural.model(), natural.config);
            }
        }

        mCallback = callback;
        mCallbackDone.set(false);
        mIsStopped.set(false);
        int sampleRate = engine.getSampleRate();
        if (sampleRate <= 0) {
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }
        int startStatus = mCallback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, engine.getChannelCount());
        if (startStatus != TextToSpeech.SUCCESS) {
            mCallback = null;
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }
        mAudioOptimizer = settings.isAudioOptimizerEnabled() && sampleRate > 0
                ? new AudioOptimizer(sampleRate, settings.getAudioProfile())
                : null;
        // Parsed once: getVoiceVariant() re-reads SharedPreferences and
        // re-splits the stored string, and it was previously called at every
        // setVoice() site below (up to 3x per request, more when chunked).
        final VoiceVariant voiceVariant = settings.getVoiceVariant();
        engine.setVoice(voice, voiceVariant);

        engine.Rate.setValue(effectiveRate(settings, request));

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
        final String intonationStyle = settings.getIntonationStyle();
        if (VoiceSettings.INTONATION_FLAT.equals(intonationStyle)) {
            pitchRange = Math.min(15, pitchRange / 3);
            engine.Intonation.setValue(3);
        } else if (VoiceSettings.INTONATION_EXPRESSIVE.equals(intonationStyle)) {
            int maxRange = engine.PitchRange.getMaxValue();
            pitchRange = Math.min(maxRange, pitchRange + Math.max(5, pitchRange / 4));
            engine.Intonation.setValue(1);
        } else if (VoiceSettings.INTONATION_CUSTOM.equals(intonationStyle)) {
            // No pitchRange adjustment here: the +/- scaling above is tuned
            // specifically to how Flat/Expressive map to groups 3/1, and this
            // is an untuned raw group (see getIntonationGroup()), so leave
            // pitchRange at the user's own configured value instead of
            // guessing at a scaling that fits an unverified tone mapping.
            engine.Intonation.setValue(settings.getIntonationGroup());
        } else {
            engine.Intonation.setValue(0);
        }
        if (!isSsml && settings.isEmphasizeQuestionsEnabled() && TextPreprocessor.endsWithQuestionOrExclamation(text)) {
            int max = engine.PitchRange.getMaxValue();
            pitchRange = Math.min(max, pitchRange + Math.max(1, pitchRange / 5));
        }
        engine.PitchRange.setValue(pitchRange);

        final int targetVolume = effectiveVolume(settings, request);
        engine.Volume.setValue(targetVolume);

        if (isSsml) {
            engine.Punctuation.setValue(settings.getPunctuationLevel());
            engine.setPunctuationCharacters(settings.getPunctuationCharacters());
        } else {
            // NVDA architecture: the Java layer (NvdaSymbolProcessor, driven
            // by the same preset) owns symbol pronunciation, so the engine
            // must stay silent on punctuation or every symbol would announce
            // twice. Code-reading mode needs no engine override either: the
            // Java pass simply runs at ALL.
            engine.Punctuation.setValue(SpeechSynthesis.PUNCT_NONE);
            engine.setPunctuationCharacters(null);
        }
        // Announcing capitalization: character navigation only by default, or all reading if enabled.
        boolean applyCapitals = isSingleCharacterUtterance || settings.isCapitalsScopeAll();
        engine.Capitals.setValue(applyCapitals ? settings.getCapitals() : 0);
        engine.WordGap.setValue(settings.getWordGap());
        engine.PauseScale.setValue(settings.getPauseScale());

        // No setVoice() for the units: the call at the top of setup already
        // applied this exact voice+variant (SpeechSynthesis memoizes it),
        // and re-applying cost a full native dictionary reload per call.
        final List<SynthUnit> units = buildUnits(text, voice, isSsml);

        if (units.size() > 1 || units.get(0).isEarcon()) {
            mSegmentsRemaining.set(units.size());
            for (int ui = 0; ui < units.size(); ui++) {
                if (mIsStopped.get()) {
                    break;
                }
                final SynthUnit unit = units.get(ui);
                if (unit.isEarcon()) {
                    mSynthCallback.onSynthDataReady(Earcons.pcm(unit.text.charAt(0),
                            sampleRate, engine.getChannelCount(), targetVolume));
                    segmentFinished();
                    continue;
                }
                try {
                    mChunkBase = unit.base;
                    engine.setVoice(unit.voice, voiceVariant);
                    engine.synthesize(unit.text, false);
                } catch (Throwable t) {
                    // One bad chunk (mixed-script edge case) must never kill
                    // the whole request or hang the service — skip and continue.
                    if (DEBUG) Log.w(TAG, "Chunk synth failed, skipping", t);
                    segmentFinished();
                }
            }
        } else {
            mSegmentsRemaining.set(1);
            mChunkBase = units.isEmpty() ? 0 : units.get(0).base;
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
        //
        // Reading history (opt-in): what was spoken, for re-hearing later.
        // Recorded once per completed request; stopped requests and SSML
        // markup are skipped inside, along with short/code-like text.
        if (!mIsStopped.get() && mHistoryText != null && !isSsml) {
            ReadingHistory.record(prefs, mHistoryText, voice != null ? voice.name : null);
        }
        mHistoryText = null;
        finishRequest();
    }

    /**
     * The request's speaking rate in wpm: the saved rate scaled by the
     * caller's rate unless locked, capped at the engine maximum unless rate
     * boost is on.
     *
     * <p>The cap used to be 449 per NVDA issue #131, to avoid unintended
     * Sonic engagement at 450 WPM - but upstream #2165 moved Sonic
     * engagement strictly above 450, and #2355 made 450 the engine max,
     * so exactly 450 never engages Sonic (no shipped voice lowers
     * fast_settings below the 450 default either). NVDA still caps at
     * 449 out of caution for engines predating #2165; this app always
     * ships its own engine, so it follows the engine it ships.
     */
    private static int effectiveRate(VoiceSettings settings, SynthesisRequest request) {
        int rate = settings.getRate();
        int rateScale = request.getSpeechRate();
        if (rateScale <= 0) {
            rateScale = 100;
        }
        // Force override: lock to the saved rate regardless of caller requests.
        if (!settings.isForceRateEnabled()) {
            rate = (int)(((long)rate * rateScale) / 100);
        }
        if (!settings.isRateBoostEnabled() && rate > 450) {
            rate = 450;
        }
        return rate;
    }

    /**
     * Volume, 0-200 (100 = normal): the saved volume times the caller's
     * KEY_PARAM_VOLUME (accessibility ducking) unless volume is locked.
     */
    private static int effectiveVolume(VoiceSettings settings, SynthesisRequest request) {
        float volumeScale = 1.0f;
        final Bundle params = request.getParams();
        if (!settings.isForceVolumeEnabled() && params != null) {
            volumeScale = getVolumeScale(params);
        }
        if (volumeScale < 0.0f) {
            volumeScale = 0.0f;
        } else if (volumeScale > 1.0f) {
            volumeScale = 1.0f;
        }
        return Math.round(settings.getVolume() * volumeScale);
    }

    /**
     * Zero-hang watchdog: never hand an engine one giant buffer. Anything
     * over the chunk limit is split at clause boundaries, so rapid swipes
     * just queue short bounded units instead of one unbounded synth call.
     * SSML is never chunked: splitting markup across units would corrupt
     * it. Punctuation sounds: each marker becomes a unit of its own, played
     * as a tone between the pieces of speech around it.
     */
    private static List<SynthUnit> buildUnits(String text, Voice voice, boolean isSsml) {
        final List<SynthUnit> units = new ArrayList<>();
        if (isSsml) {
            units.add(new SynthUnit(text, voice, 0));
            return units;
        }
        int base = 0;
        for (String chunk : TextPreprocessor.chunkForWatchdog(text)) {
            int start = 0;
            for (int i = 0; i <= chunk.length(); i++) {
                if (i < chunk.length() && !Earcons.isMarker(chunk.charAt(i))) {
                    continue;
                }
                if (i > start) {
                    String piece = chunk.substring(start, i);
                    units.add(new SynthUnit(piece, voice, base));
                    base += piece.codePointCount(0, piece.length());
                }
                if (i < chunk.length()) {
                    units.add(new SynthUnit(chunk.substring(i, i + 1), voice, base));
                    base++;
                }
                start = i + 1;
            }
        }
        if (units.isEmpty()) {
            units.add(new SynthUnit("", voice, 0));
        }
        return units;
    }

    /**
     * Speaks a request with a Piper voice. Same text pipeline, same units
     * (watchdog chunks, earcons), same callback plumbing as eSpeak -
     * reading history, word ranges, audio optimizer and stop all behave the
     * same - only the audio comes from the neural model, phonemized by this
     * fork's own eSpeak rules.
     */
    private void synthesizeNatural(SynthesisRequest request, SynthesisCallback callback,
                                   final SpeechSynthesis engine, VoiceSettings settings,
                                   SharedPreferences prefs, final Voice voice,
                                   PiperVoiceStore.Installed natural, PiperModel model,
                                   String text) {
        final int sampleRate = model.config.sampleRate;
        mCallback = callback;
        mCallbackDone.set(false);
        mIsStopped.set(false);
        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            mCallback = null;
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }
        mAudioOptimizer = settings.isAudioOptimizerEnabled()
                ? new AudioOptimizer(sampleRate, settings.getAudioProfile())
                : null;

        final PiperEngine.Params params = new PiperEngine.Params();
        params.speed = effectiveRate(settings, request) / (float) PiperEngine.NORMAL_RATE
                * PiperVoiceStore.speedFactor(prefs);
        int pitchScale = request.getPitch();
        if (pitchScale <= 0 || settings.isForcePitchEnabled()) {
            pitchScale = 100;
        }
        // eSpeak pitch 0-100 around 50 -> about half an octave down/up,
        // times the caller's pitch. A neural voice has its own natural
        // pitch; this only shifts it, it never flattens intonation.
        final float userPitch = (float) Math.pow(2.0, (settings.getPitch() - 50) / 100.0);
        params.pitch = Math.max(0.5f, Math.min(2.0f, userPitch * pitchScale / 100f));
        final int targetVolume = effectiveVolume(settings, request);
        params.volume = targetVolume / 100f;
        params.pauseScale = settings.getPauseScale();
        params.speakerId = PiperVoiceStore.speakerId(prefs, natural.key);

        // The config's eSpeak voice first (what the model was trained on);
        // if this fork lacks it, the eSpeak voice of the same language.
        final PiperEngine.Phonemizer phonemizer = (espeakVoice, unitText) -> {
            String raw = engine.phonemizeForPiper(espeakVoice, unitText);
            if (raw == null && voice.name != null && !voice.name.equals(espeakVoice)) {
                raw = engine.phonemizeForPiper(voice.name, unitText);
            }
            return raw;
        };
        final PiperEngine.TimeStretcher stretcher = SpeechSynthesis::sonicStretch;
        final long[] frames = {0};
        final PiperEngine.Output output = new PiperEngine.Output() {
            @Override
            public void word(int position, int length, int frame) {
                mSynthCallback.onSynthWordBoundary(position, length, (int) (frames[0] + frame));
            }

            @Override
            public boolean audio(byte[] pcm) {
                if (pcm.length == 0) {
                    return !mIsStopped.get(); // empty would read as end-of-stream
                }
                mSynthCallback.onSynthDataReady(pcm);
                return !mIsStopped.get() && !mCallbackDone.get();
            }
        };

        try {
            for (SynthUnit unit : buildUnits(text, voice, false)) {
                if (mIsStopped.get()) {
                    break;
                }
                if (unit.isEarcon()) {
                    final byte[] tone = Earcons.pcm(unit.text.charAt(0), sampleRate, 1, targetVolume);
                    output.audio(tone);
                    frames[0] += tone.length / 2;
                    continue;
                }
                if (VoiceSettings.isBlank(unit.text)) {
                    continue;
                }
                mChunkBase = unit.base;
                final int produced = mPiper.synthesize(model, unit.text, phonemizer, stretcher,
                        params, output);
                if (produced > 0) {
                    frames[0] += produced;
                } else if (produced < 0) {
                    Log.w(TAG, "Natural voice " + natural.key + " could not phonemize; skipped");
                }
            }
        } catch (Throwable t) {
            // A model failure mid-request: whatever was spoken stands, the
            // request ends cleanly, and the next one retries.
            Log.e(TAG, "Natural voice synthesis failed", t);
            if (frames[0] == 0) {
                reportError(callback, TextToSpeech.ERROR_SYNTHESIS);
                return;
            }
        }

        if (!mIsStopped.get() && mHistoryText != null) {
            ReadingHistory.record(prefs, mHistoryText, voice.name);
        }
        mHistoryText = null;
        finishRequest();
    }

    /**
     * One watchdog chunk: its text, the voice speaking it, and its code-point
     * base within the full request (for word-boundary re-basing). A single
     * list of these replaces three parallel lists (text/voice/base) whose
     * index alignment was load-bearing but invisible.
     */
    private static final class SynthUnit {
        final String text;
        final Voice voice;
        final int base;

        SynthUnit(String text, Voice voice, int base) {
            this.text = text;
            this.voice = voice;
            this.base = base;
        }

        boolean isEarcon() {
            return text.length() == 1 && Earcons.isMarker(text.charAt(0));
        }
    }

    /** Signals done() exactly once per request, whichever path gets there first. */
    private void finishRequest() {
        if (mCallback != null && mCallbackDone.compareAndSet(false, true)) {
            mCallback.done();
        }
    }

    /** One chunk ended (synthesized or skipped); finish once all have, or on stop. */
    private void segmentFinished() {
        if (mSegmentsRemaining.decrementAndGet() <= 0 || mIsStopped.get()) {
            finishRequest();
        }
    }

    // Protected (not private) as a test hook: eSpeakTests subclasses call
    // this to refresh the voice list without a full engine re-init.
    protected void rebuildAvailableVoices() {
        synchronized (mAvailableVoices) {
            // Invalidate the framework voice list BEFORE the (user-preference)
            // filter runs: mAvailableVoices must not keep serving the previous
            // selection if filterVoices() were to throw, and the field's
            // documented invariant is that a rebuild always produces a fresh
            // list. The voice-selection memo goes with it: it keys a voice
            // object from the previous set.
            mCachedFrameworkVoices = null;
            mLastSelection = null;
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
                    //
                    // Also mark the whole request stopped, not just this
                    // chunk's native synth: for a multi-chunk request the
                    // outer loop in onSynthesizeText() only checks
                    // mIsStopped between chunks, so without this it would
                    // plow ahead into the next chunk against an audio pipe
                    // that just rejected data -- silently dropping the rest
                    // of the request while still reporting done() as if it
                    // had read everything (the exact "stops reading midway"
                    // symptom, for the same request that made the pipe fail
                    // once and then kept trying).
                    mIsStopped.set(true);
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
            segmentFinished();
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
