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
import java.lang.Character.UnicodeScript;
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

    /** Active audio and word boundary dispatcher for the ongoing synthesis. */
    private volatile TtsAudioDispatcher mCurrentDispatcher;

    /** Central registry for pluggable voice synthesis engines (eSpeak NG, Piper, etc.). */
    private final VoiceEngineRegistry mEngineRegistry = VoiceEngineRegistry.getInstance();

    /** Caller text as handed to the preprocessor: what reading history keeps. */
    private String mHistoryText;
    /** Length of the original text passed by the caller for boundary clamping. */
    private int mOriginalTextLength;

    /**
     * Piper neural voices (see PiperEngine). Process-wide, like the native
     * eSpeak engine: the settings screen's previews share its loaded models.
     */
    private final PiperEngine mPiper = PiperEngine.get();

    SpeechSynthesis getEngine() {
        return mEngine;
    }

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
                    } else if (PiperVoiceStore.PREF_ENABLED.equals(key)
                            || (key != null && key.startsWith(PiperVoiceStore.PREF_VOICE_PREFIX))) {
                        // A language only a natural voice speaks comes or goes.
                        rebuildAvailableVoices();
                        naturalVoicesChanged(TolerantPreferences.of(sharedPreferences));
                    }
                }
            };

    /**
     * The natural-voice switch or a language's voice changed. Off frees
     * every loaded model at once (100+ MB each) instead of holding them
     * until the process dies; otherwise the voice now needed starts loading.
     */
    private void naturalVoicesChanged(SharedPreferences prefs) {
        if (!PiperVoiceStore.isEnabled(prefs)) {
            mPiper.unloadAll();
            return;
        }
        final Voice current;
        synchronized (mAvailableVoices) {
            current = mMatchingVoice;
        }
        EspeakApp.runAsync(() -> preloadNaturalVoice(current));
    }

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
            EspeakApp.runAsync(() -> {
                try {
                    synchronized (TtsService.this) {
                        initializeTtsEngine();
                    }
                } finally {
                    mVoiceReloadRunning.set(false);
                }
            });
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
            // An updated voice: its new files load on next use (no-op for a new one).
            final String changed = intent.getStringExtra(PiperDownloads.EXTRA_KEY);
            if (changed != null) {
                mPiper.unload(changed);
            }
            final Voice current;
            synchronized (mAvailableVoices) {
                current = mMatchingVoice;
            }
            // Off the main thread: resolving rescans the voice folders and
            // parses their configs.
            EspeakApp.runAsync(() -> preloadNaturalVoice(current));
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
        final PiperVoiceStore.Installed natural = PiperVoiceStore.resolve(mStorageContext,
                TolerantPreferences.of(mPreferences), voice);
        if (natural != null) {
            mPiper.preload(natural.key, natural.model(), natural.config);
        }
    }

    /**
     * Then the voices used most recently, as many as the phone keeps loaded:
     * after Android restarts the speech service, a Hindi word in English text
     * is read by the Hindi voice at once instead of by eSpeak while it loads.
     */
    private void preloadRecentNaturalVoices() {
        final SharedPreferences prefs = TolerantPreferences.of(mPreferences);
        if (!PiperVoiceStore.isEnabled(prefs) || !mPiper.keepsSeveralLoaded()) {
            return;
        }
        int budget = mPiper.maxLoaded() - 1; // the system language's voice took one
        for (String key : PiperVoiceStore.recent(prefs)) {
            if (budget <= 0) {
                break;
            }
            if (mPiper.isLoading(key) || mPiper.getLoaded(key) != null) {
                continue; // the system language's voice, already on its way
            }
            final PiperVoiceStore.Installed v = PiperVoiceStore.find(mStorageContext, key);
            if (v != null && !PiperCrashGuard.isSuspended(prefs, key)) {
                mPiper.preload(v.key, v.model(), v.config);
                budget--;
            }
        }
        // If budget remains, pre-warm assigned natural voices for user's configured
        // script languages (Settings -> Mixed-language text) so the first mixed utterance
        // never falls back or hesitates.
        if (budget > 0 && ScriptLanguages.naturalSwitching(prefs)) {
            final Map<String, String> scriptLanguages = ScriptLanguages.chosen(prefs);
            final Map<java.lang.Character.UnicodeScript, String> runLanguages =
                    ScriptLanguages.runLanguages(scriptLanguages);
            for (String lang : runLanguages.values()) {
                if (budget <= 0) {
                    break;
                }
                final PiperVoiceStore.Installed v = PiperVoiceStore.assignedFor(mStorageContext, prefs, lang);
                if (v != null && !PiperCrashGuard.isSuspended(prefs, v.key)
                        && !mPiper.isLoading(v.key) && mPiper.getLoaded(v.key) == null) {
                    mPiper.preload(v.key, v.model(), v.config);
                    budget--;
                }
            }
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
        mEngineRegistry.register(new EspeakVoiceEngine(this));
        mEngineRegistry.register(new PiperVoiceEngine(mPiper));
        // Warm the user-dictionary singleton, emoji trie, and hinglish list off
        // the main/synth threads: without this the first synthesis request after
        // each process start pays that cost on the latency-critical path. Failure
        // is non-fatal - the lazy path will retry if a synthesis actually needs it.
        EspeakApp.runAsync(() -> {
            try {
                // Natural voice for the system language first: it takes the
                // longest, and the first utterance is usually in that language.
                final Voice systemVoice = findVoice(Voice.iso3Language(Locale.getDefault()),
                        "", "").first;
                preloadNaturalVoice(systemVoice);
                preloadRecentNaturalVoices();
                if (mPreferences != null && PiperVoiceStore.isEnabled(TolerantPreferences.of(mPreferences))) {
                    PiperDownloads.checkForUpdates(getApplicationContext(), mStorageContext);
                    PiperDownloads.restoreMissing(getApplicationContext(), mStorageContext);
                    PiperDownloads.fetchVoiceExtras(getApplicationContext(), mStorageContext);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Natural voice warmup failed", t);
            }
            try {
                UserDictionaryManager.getInstance(mStorageContext);
                NvdaEmoji.warmup();
                NvdaSymbolProcessor.warmup(Locale.getDefault().toLanguageTag());
                if (mPreferences.getBoolean(VoiceSettings.PREF_HINGLISH, false)) {
                    HinglishReader.warmup();
                }
                if (mPreferences.getBoolean(VoiceSettings.PREF_SPLIT_COMPOUNDS, false)) {
                    CompoundSplitter.warmup();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Data warmup failed", t);
            }
        });
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

    @SuppressWarnings("deprecation")
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        mEngineRegistry.trimAll(level);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mEngineRegistry.unloadAll();
        // Break the static-registry -> engine -> service chain: the registry
        // is process-wide, and EspeakVoiceEngine holds this service. Without
        // this, a destroyed service stays reachable until the process dies.
        mEngineRegistry.unregister(EspeakVoiceEngine.ID);
        mEngineRegistry.unregister(PiperVoiceEngine.ID);
        mCurrentDispatcher = null;
        // The native engine stays: it is shared with every other
        // SpeechSynthesis in this process (settings, Test voice, natural
        // voices), and terminating it under them crashed the process. The
        // process ending frees it.
        mEngine = null;
        if (mPreferences != null) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnPreferencesChanged);
        }
        try {
            unregisterReceiver(mLanguagesUpdatedReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered (onCreate() never completed) - nothing to undo.
        }
        try {
            unregisterReceiver(mPiperVoicesReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * Sets up the native eSpeak engine.
     */
    private void initializeTtsEngine() {
        if (mEngine != null) {
            mEngine.reload(); // reads the new voice data; never leaves the engine down
        } else {
            // Clear cached voice list since native engine is being reinitialized
            SpeechSynthesis.clearVoiceCache();
            mEngine = new SpeechSynthesis(mStorageContext, mSynthCallback);
        }
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

    /**
     * The eSpeak voice for a run no natural voice speaks. eSpeak switches
     * some scripts by itself (Devanagari in an English voice is read as
     * Hindi), so the request's voice keeps those; a run eSpeak would spell
     * letter by letter (Cyrillic, Telugu, Thai) or read with the wrong
     * language's rules (Marathi, Ukrainian, Urdu) gets that language's voice
     * when there is one. Each switch reloads a dictionary.
     */
    private Voice espeakVoiceForRun(String runLanguage, String own, Voice voice,
                                    Map<UnicodeScript, String> chosen) {
        return LanguageRuns.espeakReadsItself(runLanguage, own, chosen)
                ? voice : findVoiceForLanguage(runLanguage, voice);
    }

    private Voice findVoiceForLanguage(String language, Voice fallback) {
        if (language == null || language.isEmpty()) {
            return fallback;
        }
        Pair<Voice, Integer> match = findVoice(language, "", "");
        if (match.first != null) {
            return match.first;
        }
        final String standin = LanguageRuns.standIn(language);
        if (standin != null && !standin.equals(language)) {
            match = findVoice(standin, "", "");
            if (match.first != null) {
                return match.first;
            }
        }
        return fallback;
    }

    private Pair<Voice, Integer> getDefaultVoiceFor(String language, String country, String variant) {
        // findVoice() tolerates null codes; the equals() calls below did not.
        language = language != null ? language : "";
        country = country != null ? country : "";
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

    @SuppressWarnings("deprecation")
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
                Set<String> features = onGetFeaturesForLanguage(Voice.iso3Language(voice.locale),
                        Voice.iso3Country(voice.locale), voice.locale.getVariant());
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
        mEngineRegistry.stopAll();
    }

    @SuppressWarnings("deprecation")
    private String getRequestString(SynthesisRequest request) {
        if (request == null) {
            return null;
        }
        CharSequence cs = request.getCharSequenceText();
        if (cs == null) {
            cs = request.getText();
        }
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
        final TtsAudioDispatcher dispatcher = mCurrentDispatcher;
        if (dispatcher != null) {
            dispatcher.reportError(errorCode);
        } else if (callback != null && mCallbackDone.compareAndSet(false, true)) {
            callback.error(errorCode);
        }
    }

    /**
     * Completes a request as a successful empty utterance (the blank-text and
     * sleep-timer fast paths' shared shape), never an error, so clients keep
     * working silently.
     */
    private void completeAsEmpty(SynthesisCallback callback, SpeechSynthesis engine) {
        if (callback.start(engine.getSampleRate(), AudioFormat.ENCODING_PCM_16BIT, engine.getChannelCount())
                != TextToSpeech.SUCCESS) {
            reportError(callback, TextToSpeech.ERROR_SERVICE);
        } else {
            callback.done();
        }
    }

    /** Builds the request's audio dispatcher and publishes it for the synth callbacks. */
    private TtsAudioDispatcher newDispatcher(SynthesisCallback callback, AudioOptimizer optimizer,
                                             TextOffsetMap offsetMap, String text, int textOffset) {
        final TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                TtsAudioDispatcher.fromSynthesisCallback(callback),
                optimizer,
                offsetMap,
                text,
                textOffset,
                mOriginalTextLength,
                mIsStopped,
                mCallbackDone,
                () -> mEngineRegistry.stopAll()
        );
        mCurrentDispatcher = dispatcher;
        return dispatcher;
    }

    /**
     * Shared request tail: record reading history (opt-in, never SSML),
     * release any render-ahead piece, note used natural voices and signal
     * completion. The natural path always passes {@code isSsml} false (SSML
     * stays with eSpeak), so one guard covers both paths.
     */
    private void finishWithHistory(SharedPreferences prefs, String voiceName, List<String> used,
                                   NaturalAhead ahead, boolean isSsml) {
        if (!mIsStopped.get() && mHistoryText != null && !isSsml) {
            ReadingHistory.record(prefs, mHistoryText, voiceName);
        }
        mHistoryText = null;
        ahead.discard(); // stopped with the next natural piece still rendering
        PiperVoiceStore.noteUsed(prefs, used);
        finishRequest();
    }

    /**
     * True when a natural voice cannot read digits itself (its language has
     * no ICU number words and it is not letter-reading): digit runs must go
     * to eSpeak instead of being skipped ("ಸಮಯ 10:30" lost its time).
     */
    private static boolean needsDigitHelp(PiperVoiceStore.Installed natural) {
        return natural != null && !natural.config.usesEspeak()
                && PiperEngine.numberWords(natural.config.languageFamily) == null;
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
        // Cleared here, not after preprocessing: a stop (screen-reader swipe)
        // arriving while the text is prepared belongs to this request, and
        // clearing the flag later used to erase it - the whole first chunk
        // then rendered before the next item could speak.
        mIsStopped.set(false);
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
            completeAsEmpty(callback, engine);
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

        // Tolerant: one setting stored with the wrong type must not silence speech.
        final SharedPreferences prefs = TolerantPreferences.of(mPreferences != null
                ? mPreferences
                : PreferenceManager.getDefaultSharedPreferences(mStorageContext));
        final VoiceSettings settings = new VoiceSettings(prefs, engine);

        // Sleep timer: while the mute window covers now, complete the request
        // as a successful empty utterance (the blank-text fast path's shape),
        // never an error, so clients keep working silently until expiry.
        if (VoiceSettings.isSleepMuted(prefs)) {
            completeAsEmpty(callback, engine);
            return;
        }

        // Languages the user chose for words in other scripts (Settings ->
        // Mixed-language text). None chosen leaves eSpeak's own switching.
        final Map<String, String> scriptLanguages = ScriptLanguages.chosen(prefs);
        engine.setScriptLanguages(scriptLanguages);

        // Detect SSML before normalizing. Real markup is ASCII, which NFKC
        // leaves untouched, but normalization can turn lookalikes such as a
        // fullwidth "＜ｓｐｅａｋ" into "<speak", and plain text must not
        // switch into SSML parsing because of that.
        final boolean isSsml = text.startsWith("<speak");

        // Accumulated map from the text as of each step back to the text the
        // caller supplied, so word-boundary offsets survive every step below
        // that can change the text's length. See TextOffsetMap.
        TextOffsetMap offsetMap = null;

        final PiperVoiceStore.Installed natural = isSsml ? null
                : PiperVoiceStore.resolve(mStorageContext, prefs, voice);
        TextPreprocessor.Result prep = TextPreprocessor.process(
                text, voice, settings, isSsml, offsetMap, mStorageContext, natural != null);
        text = prep.text;
        offsetMap = prep.offsetMap;
        final boolean isSingleCharacterUtterance = prep.isSingleCharacterUtterance;

        mHistoryText = text;

        // Natural voice for this language, when the user chose one and it is
        // already in memory. SSML stays with eSpeak (Piper has no markup
        // support), and so, by default, do single characters: character
        // navigation is where a screen reader user notices latency most.
        if (natural != null && !(isSingleCharacterUtterance && PiperVoiceStore.espeakForCharacters(prefs))) {
            final PiperModel model = mPiper.getLoaded(natural.key);
            if (model != null && !espeakReadsPart(text, natural, prefs, settings, scriptLanguages)) {
                synthesizeNatural(request, callback, engine, settings, prefs, voice, natural,
                        model, text, scriptLanguages, offsetMap, textOffset);
                return;
            }
            // Not loaded yet: eSpeak speaks this one while it loads. Or
            // part of it is in a language no natural voice reads: the
            // eSpeak path below reads that part and hands the rest to
            // the natural voices (withNaturalRuns).
            if (model == null) {
                mPiper.preload(natural.key, natural.model(), natural.config);
            }
        }

        mCallback = callback;
        mCallbackDone.set(false);
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
        final TtsAudioDispatcher dispatcher =
                newDispatcher(callback, mAudioOptimizer, offsetMap, text, textOffset);
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
        final List<SynthUnit> units =
                !isSsml && !(isSingleCharacterUtterance && PiperVoiceStore.espeakForCharacters(prefs))
                        ? withNaturalRuns(buildUnits(text, voice, isSsml), voice, prefs, settings,
                                sampleRate, scriptLanguages)
                        : buildUnits(text, voice, isSsml);
        final List<String> naturalUsed = new ArrayList<>();
        final NaturalAhead ahead = new NaturalAhead(naturalPhonemizer(engine, voice),
                naturalParams(settings, request, prefs), settings, request, prefs, naturalUsed);

        // One plain unit in the request's own voice takes the direct path; a
        // whole text in another language (all Marathi, all Ukrainian) is one
        // unit too, but in that language's voice.
        if (units.size() > 1 || units.get(0).isEarcon() || units.get(0).model != null
                || units.get(0).voice != voice) {
            mSegmentsRemaining.set(units.size());
            for (int ui = 0; ui < units.size(); ui++) {
                if (mIsStopped.get()) {
                    break;
                }
                final SynthUnit unit = units.get(ui);
                dispatcher.setChunkContext(unit.base, dispatcher.getRequestFrames());
                if (unit.isEarcon()) {
                    dispatcher.writeAudio(Earcons.pcm(unit.text.charAt(0),
                            sampleRate, engine.getChannelCount(), targetVolume));
                    segmentFinished();
                    continue;
                }
                if (unit.model != null) {
                    final int next = ui + 1;
                    int produced;
                    try {
                        final PiperEngine.Prepared prepared = ahead.take(units, ui);
                        produced = prepared == null ? -1 : playAt(prepared,
                                dispatcher.asPiperOutput(), sampleRate,
                                () -> ahead.prepare(units, next));
                    } catch (Throwable t) {
                        Log.e(TAG, "Natural voice " + unit.modelKey + " failed; eSpeak reads it", t);
                        produced = -1;
                    }
                    if (produced >= 0 || mIsStopped.get()) {
                        segmentFinished();
                        continue;
                    }
                }
                // A natural voice speaking next renders while eSpeak speaks.
                ahead.prepare(units, ui + 1);
                try {
                    engine.setVoice(unit.voice, voiceVariant);
                    // eSpeak works out speed only when the rate is set: a voice
                    // file's own "speed" (Ukrainian 80) sticks if the voice is
                    // loaded after it, and the next voice keeps it too. Set
                    // after the voice, as a request in that voice is.
                    engine.Rate.setValue(effectiveRate(settings, request));
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
            dispatcher.setChunkContext(units.isEmpty() ? 0 : units.get(0).base, 0);
            try {
                if (!mIsStopped.get()) {
                    engine.synthesize(text, isSsml);
                }
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
        finishWithHistory(prefs, voice != null ? voice.name : null, naturalUsed, ahead, isSsml);
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
                                   String text, Map<String, String> scriptLanguages,
                                   TextOffsetMap offsetMap, int textOffset) {
        final int sampleRate = model.config.sampleRate;
        mCallback = callback;
        mCallbackDone.set(false);
        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            mCallback = null;
            reportError(callback, TextToSpeech.ERROR_SERVICE);
            return;
        }
        mAudioOptimizer = settings.isAudioOptimizerEnabled()
                ? new AudioOptimizer(sampleRate, settings.getAudioProfile())
                : null;
        final TtsAudioDispatcher dispatcher =
                newDispatcher(callback, mAudioOptimizer, offsetMap, text, textOffset);

        final int targetVolume = effectiveVolume(settings, request);
        final PiperEngine.Params params = naturalParams(settings, request, prefs);
        final PiperEngine.Phonemizer phonemizer = naturalPhonemizer(engine, voice);
        final PiperEngine.Output output = dispatcher.asPiperOutput();
        final Map<UnicodeScript, String> runLanguages = ScriptLanguages.naturalSwitching(prefs)
                ? ScriptLanguages.runLanguages(scriptLanguages) : null;
        final String numbers = numbersRunLanguage(settings, natural.languageKey());
        final List<String> used = new ArrayList<>();

        // Like eSpeak switching language by alphabet: a Hindi run in English
        // text goes to the Hindi natural voice, when one is chosen and loaded
        // (it starts loading on first use; until then this voice reads it, as
        // before). Switched off, this voice reads everything.
        final List<SynthUnit> pieces = new ArrayList<>();
        for (SynthUnit unit : buildUnits(text, voice, false)) {
            if (unit.isEarcon()) {
                pieces.add(unit);
                continue;
            }
            if (VoiceSettings.isBlank(unit.text)) {
                continue;
            }
            final List<LanguageRuns.Run> runs = runLanguages == null
                    ? Collections.singletonList(new LanguageRuns.Run(0, unit.text, natural.languageKey()))
                    : naturalRuns(unit.text, natural.languageKey(), runLanguages, numbers, prefs);
            SynthUnit currentPiece = null;
            for (LanguageRuns.Run run : runs) {
                PiperModel runModel = model;
                String runKey = natural.key;
                if (!run.language.equals(natural.languageKey())) {
                    final PiperVoiceStore.Installed other =
                            PiperVoiceStore.assignedFor(mStorageContext, prefs, run.language);
                    if (other != null && !other.key.equals(natural.key)) {
                        final PiperModel loaded = mPiper.getLoaded(other.key);
                        if (loaded != null) {
                            runModel = loaded;
                            runKey = other.key;
                        } else if (mPiper.keepsSeveralLoaded()) {
                            mPiper.preload(other.key, other.model(), other.config);
                        }
                    }
                }
                if (currentPiece != null && currentPiece.model == runModel
                        && currentPiece.modelKey.equals(runKey)) {
                    currentPiece = new SynthUnit(currentPiece.text + run.text, voice,
                            currentPiece.base, runModel, runKey);
                    pieces.set(pieces.size() - 1, currentPiece);
                } else {
                    currentPiece = new SynthUnit(run.text, voice, unit.base + run.start, runModel, runKey);
                    pieces.add(currentPiece);
                }
            }
        }

        final NaturalAhead ahead = new NaturalAhead(phonemizer, params, settings, request, prefs, used);
        try {
            for (int i = 0; i < pieces.size(); i++) {
                if (mIsStopped.get()) {
                    break;
                }
                final SynthUnit piece = pieces.get(i);
                dispatcher.setChunkContext(piece.base, dispatcher.getRequestFrames());
                if (piece.isEarcon()) {
                    final byte[] tone = Earcons.pcm(piece.text.charAt(0), sampleRate, 1, targetVolume);
                    dispatcher.writeAudio(tone);
                    continue;
                }
                final PiperEngine.Prepared prepared = ahead.take(pieces, i);
                if (prepared == null) {
                    Log.w(TAG, "Natural voice " + piece.modelKey + " could not phonemize; skipped");
                    continue;
                }
                final int next = i + 1;
                playAt(prepared, output, sampleRate, () -> ahead.prepare(pieces, next));
            }
        } catch (Throwable t) {
            // A model failure mid-request: whatever was spoken stands, the
            // request ends cleanly, and the next one retries.
            Log.e(TAG, "Natural voice synthesis failed", t);
            if (dispatcher.getRequestFrames() == 0) {
                ahead.discard();
                reportError(callback, TextToSpeech.ERROR_SYNTHESIS);
                return;
            }
        }
        finishWithHistory(prefs, voice.name, used, ahead, false);
    }

    /** The speaker and speed of the voice speaking next (both chosen per voice). */
    private static void forVoice(PiperEngine.Params params, VoiceSettings settings,
                                 SynthesisRequest request, SharedPreferences prefs, String key) {
        params.speakerId = PiperVoiceStore.speakerId(prefs, key);
        params.speed = effectiveRate(settings, request) / (float) PiperEngine.NORMAL_RATE
                * PiperVoiceStore.speedFactor(prefs, key);
    }

    /** {@link PiperEngine#play} into a request at {@code rate}, resampled where the voice's differs. */
    private int playAt(PiperEngine.Prepared prepared, PiperEngine.Output output, int rate,
                       Runnable lastRendered) throws ai.onnxruntime.OrtException {
        if (prepared.model.config.sampleRate == rate) {
            return mPiper.play(prepared, output, lastRendered);
        }
        final PcmResampler.Output converted =
                new PcmResampler.Output(output, prepared.model.config.sampleRate, rate);
        mPiper.play(prepared, converted, lastRendered);
        return converted.finish();
    }

    /**
     * The next natural-voice piece of a request, rendering while the current
     * one plays (see {@link PiperEngine.Prepared}). Holds at most one.
     */
    private final class NaturalAhead {
        private final PiperEngine.Phonemizer phonemizer;
        private final PiperEngine.Params params;
        private final VoiceSettings settings;
        private final SynthesisRequest request;
        private final SharedPreferences prefs;
        private final List<String> used;
        private PiperEngine.Prepared prepared;
        private int preparedIndex = -1;

        NaturalAhead(PiperEngine.Phonemizer phonemizer, PiperEngine.Params params,
                     VoiceSettings settings, SynthesisRequest request, SharedPreferences prefs,
                     List<String> used) {
            this.phonemizer = phonemizer;
            this.params = params;
            this.settings = settings;
            this.request = request;
            this.prefs = prefs;
            this.used = used;
        }

        /** Starts the first natural piece at or after {@code from}, unless one is waiting. */
        void prepare(List<SynthUnit> units, int from) {
            if (prepared != null || mIsStopped.get()) {
                return;
            }
            for (int i = from; i < units.size(); i++) {
                final SynthUnit unit = units.get(i);
                if (unit.model != null) {
                    try {
                        prepared = start(unit);
                        preparedIndex = i;
                    } catch (ai.onnxruntime.OrtException e) {
                        // take() starts it again in its turn, and reports a failure then.
                        Log.w(TAG, "Natural voice " + unit.modelKey + " not prepared ahead", e);
                    }
                    return;
                }
                if (!unit.isEarcon()) {
                    return; // eSpeak speaks next: it starts at once
                }
            }
        }

        /** The piece at {@code index}: the one prepared ahead, or started now. */
        PiperEngine.Prepared take(List<SynthUnit> units, int index) throws ai.onnxruntime.OrtException {
            if (prepared != null && preparedIndex == index) {
                final PiperEngine.Prepared p = prepared;
                prepared = null;
                return p;
            }
            discard();
            return start(units.get(index));
        }

        private PiperEngine.Prepared start(SynthUnit unit) throws ai.onnxruntime.OrtException {
            forVoice(params, settings, request, prefs, unit.modelKey);
            if (!used.contains(unit.modelKey)) {
                used.add(unit.modelKey);
            }
            return mPiper.prepare(unit.model, unit.text, phonemizer, SpeechSynthesis::sonicStretch,
                    params);
        }

        void discard() {
            if (prepared != null) {
                mPiper.discard(prepared);
                prepared = null;
            }
        }
    }

    private static PiperEngine.Params naturalParams(VoiceSettings settings, SynthesisRequest request,
                                                    SharedPreferences prefs) {
        final PiperEngine.Params params = new PiperEngine.Params();
        params.speed = effectiveRate(settings, request) / (float) PiperEngine.NORMAL_RATE
                * PiperVoiceStore.speedFactor(prefs);
        final float[] style = PiperVoiceStore.styleScales(prefs);
        params.noiseScale = style[0];
        params.noiseW = style[1];
        int pitchScale = request.getPitch();
        if (pitchScale <= 0 || settings.isForcePitchEnabled()) {
            pitchScale = 100;
        }
        // eSpeak pitch 0-100 around 50 -> about half an octave down/up,
        // times the caller's pitch. A neural voice has its own natural
        // pitch; this only shifts it, it never flattens intonation.
        final float userPitch = (float) Math.pow(2.0, (settings.getPitch() - 50) / 100.0);
        params.pitch = Math.max(0.5f, Math.min(2.0f, userPitch * pitchScale / 100f));
        params.volume = effectiveVolume(settings, request) / 100f;
        params.pauseScale = settings.getPauseScale();
        return params;
    }

    /**
     * The config's eSpeak voice first (what the model was trained on); if
     * this fork lacks it, the eSpeak voice of the same language.
     */
    private static PiperEngine.Phonemizer naturalPhonemizer(final SpeechSynthesis engine, final Voice voice) {
        return (espeakVoice, unitText) -> {
            String raw = engine.phonemizeForPiper(espeakVoice, unitText);
            if (raw == null && voice.name != null && !voice.name.equals(espeakVoice)) {
                raw = engine.phonemizeForPiper(voice.name, unitText);
            }
            return raw;
        };
    }



    /**
     * The language digits get a run of their own in ({@link LanguageRuns}),
     * or null for the words around them (Mixed-language text -> Numbers and
     * times; English and "around" are also written out as English words by
     * TextPreprocessor for non-Latin voices).
     */
    private static String numbersRunLanguage(VoiceSettings settings, String own) {
        final String numbers = settings.getNumbersLanguage();
        if (VoiceSettings.NUMBERS_VOICE.equals(numbers)) {
            return own;
        }
        return VoiceSettings.NUMBERS_ENGLISH.equals(numbers) ? "eng" : null;
    }

    /** No natural voice is ever chosen for it ("no linguistic content"): eSpeak reads such runs. */
    private static final String ESPEAK_ONLY = "zxx";

    /**
     * {@link LanguageRuns#split}, plus digits taken out of runs whose natural
     * voice reads letters and has no number words for its language (ICU
     * spells out Hindi, Gujarati, Tamil, Nepali and English, not Kannada,
     * Telugu, Bengali, Marathi...): those voices skipped them, so "ಸಮಯ 10:30" lost its time.
     * eSpeak reads them in the request's language instead.
     */
    private List<LanguageRuns.Run> naturalRuns(String text, String own,
                                               Map<UnicodeScript, String> runLanguages,
                                               String numbers, SharedPreferences prefs) {
        final String sensitivity = ScriptLanguages.switchingSensitivity(prefs);
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, own, runLanguages, numbers, sensitivity);
        List<LanguageRuns.Run> out = null;
        for (int i = 0; i < runs.size(); i++) {
            final LanguageRuns.Run run = runs.get(i);
            final PiperVoiceStore.Installed natural =
                    PiperVoiceStore.assignedFor(mStorageContext, prefs, run.language);
            if (!needsDigitHelp(natural) || !AsciiUtils.hasDigit(run.text)) {
                if (out != null) {
                    out.add(run);
                }
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(runs.subList(0, i));
            }
            out.addAll(LanguageRuns.splitDigits(run, ESPEAK_ONLY));
        }
        return out != null ? out : runs;
    }

    /**
     * True when part of the text, spoken with this natural voice, is in a
     * language without a loaded natural voice (a
     * Gujarati word while Hindi and English have natural voices, or an
     * English one while only Hindi has): the eSpeak path then reads that
     * part in its language instead of this voice mangling it, and hands the
     * rest to the natural voices (resampled to eSpeak's rate where theirs
     * differs). Only when switching is on.
     */
    private boolean espeakReadsPart(String text, PiperVoiceStore.Installed natural,
                                    SharedPreferences prefs, VoiceSettings settings,
                                    Map<String, String> scriptLanguages) {
        final boolean digitHelp = needsDigitHelp(natural);
        if (digitHelp && AsciiUtils.hasDigit(text)) {
            return true;
        }
        if (!ScriptLanguages.naturalSwitching(prefs)) {
            return false;
        }
        final String own = natural.languageKey();
        for (LanguageRuns.Run run : naturalRuns(text, own,
                ScriptLanguages.runLanguages(scriptLanguages), numbersRunLanguage(settings, own), prefs)) {
            if (run.language.equals(own) || VoiceSettings.isBlank(run.text)) {
                continue;
            }
            final PiperVoiceStore.Installed other =
                    PiperVoiceStore.assignedFor(mStorageContext, prefs, run.language);
            final PiperModel loaded = other == null ? null : mPiper.getLoaded(other.key);
            if (loaded == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * eSpeak speaking, natural voices where there are some: like eSpeak
     * switching language by alphabet, a run in another script (Hindi inside
     * English) becomes a unit of its own for that language's natural voice,
     * when one is chosen and loaded (resampled to eSpeak's rate), and so does a
     * run in the voice's own language when its natural voice is loaded (the
     * request came here for a part only eSpeak reads, see espeakReadsPart).
     * The first use starts loading a voice and eSpeak reads that run
     * meanwhile. Everything else stays together for eSpeak, which switches
     * language by itself.
     */
    private List<SynthUnit> withNaturalRuns(List<SynthUnit> units, Voice voice,
                                            SharedPreferences prefs, VoiceSettings settings,
                                            int sampleRate, Map<String, String> scriptLanguages) {
        // A script-variant voice (fa-latn): its text is not in the script its
        // language's natural voices, or LanguageRuns, expect.
        if (!PiperVoiceStore.isEnabled(prefs) || voice.isScriptVariant()) {
            return units;
        }
        final PiperVoiceStore.Installed ownNatural = PiperVoiceStore.resolve(mStorageContext, prefs, voice);
        final boolean naturalSwitching = ScriptLanguages.naturalSwitching(prefs);
        final boolean digitHelp = needsDigitHelp(ownNatural);
        if (!naturalSwitching && !digitHelp) {
            return units;
        }
        final Map<UnicodeScript, String> runLanguages = naturalSwitching
                ? ScriptLanguages.runLanguages(scriptLanguages) : null;
        final String own = PiperVoiceStore.languageKey(voice.locale);
        final String numbers = naturalSwitching ? numbersRunLanguage(settings, own) : null;
        // A one-voice phone still loading this language's own natural voice
        // must not evict it for another language's.
        final boolean mayPreload = mPiper.keepsSeveralLoaded() || ownNatural == null;
        // A lone letter in another language ("क deleted" from TalkBack, a
        // Devanagari letter typed with an English UI) stays with eSpeak, like
        // a single-character request: natural voices garble a lone syllable.
        final boolean espeakLetters = PiperVoiceStore.espeakForCharacters(prefs);
        final List<SynthUnit> out = new ArrayList<>();
        for (SynthUnit unit : units) {
            if (unit.isEarcon()) {
                out.add(unit);
                continue;
            }
            final StringBuilder espeak = new StringBuilder();
            int espeakStart = 0;
            Voice currentEspeakVoice = voice;
            // Switching off: only the digits this voice cannot read leave it.
            final List<LanguageRuns.Run> runs = naturalSwitching
                    ? naturalRuns(unit.text, own, runLanguages, numbers, prefs)
                    : LanguageRuns.splitDigits(new LanguageRuns.Run(0, unit.text, own), ESPEAK_ONLY);
            for (LanguageRuns.Run run : runs) {
                final PiperVoiceStore.Installed natural = espeakLetters && isLoneLetter(run.text)
                        ? null
                        : run.language.equals(own) ? ownNatural
                        : PiperVoiceStore.assignedFor(mStorageContext, prefs, run.language);
                final PiperModel model = natural == null ? null : mPiper.getLoaded(natural.key);
                if (natural != null && natural != ownNatural && model == null && mayPreload) {
                    mPiper.preload(natural.key, natural.model(), natural.config);
                }
                if (model == null) {
                    final Voice targetVoice = espeakVoiceForRun(run.language, own, voice, runLanguages);
                    if (espeak.length() > 0 && targetVoice != currentEspeakVoice) {
                        out.add(new SynthUnit(espeak.toString(), currentEspeakVoice, unit.base + espeakStart));
                        espeak.setLength(0);
                    }
                    if (espeak.length() == 0) {
                        espeakStart = run.start;
                        currentEspeakVoice = targetVoice;
                    }
                    espeak.append(run.text);
                    continue;
                }
                if (espeak.length() > 0) {
                    out.add(new SynthUnit(espeak.toString(), currentEspeakVoice, unit.base + espeakStart));
                    espeak.setLength(0);
                }
                out.add(new SynthUnit(run.text, voice, unit.base + run.start, model, natural.key));
            }
            if (espeak.length() > 0) {
                out.add(new SynthUnit(espeak.toString(), currentEspeakVoice, unit.base + espeakStart));
            }
        }
        return out;
    }

    /**
     * One character, ignoring the punctuation and spaces around it: what a
     * run holds when TalkBack says "क्ष, deleted" with an English UI.
     */
    static boolean isLoneLetter(String run) {
        if (run == null || run.isEmpty()) return false;
        int start = 0;
        int end = run.length();
        while (start < end && !isLetterOrMark(run.codePointAt(start))) {
            start += Character.charCount(run.codePointAt(start));
        }
        while (end > start && !isLetterOrMark(run.codePointBefore(end))) {
            end -= Character.charCount(run.codePointBefore(end));
        }
        return start < end && TextPreprocessor.isSingleCharacter(run.substring(start, end));
    }

    private static boolean isLetterOrMark(int cp) {
        final int type = Character.getType(cp);
        return Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
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
        /** Natural voice for this unit, or null for eSpeak. */
        final PiperModel model;
        final String modelKey;

        SynthUnit(String text, Voice voice, int base) {
            this(text, voice, base, null, null);
        }

        SynthUnit(String text, Voice voice, int base, PiperModel model, String modelKey) {
            this.text = text;
            this.voice = voice;
            this.base = base;
            this.model = model;
            this.modelKey = modelKey;
        }

        boolean isEarcon() {
            return text != null && text.length() == 1 && Earcons.isMarker(text.charAt(0));
        }
    }

    /** Signals done() exactly once per request, whichever path gets there first. */
    private void finishRequest() {
        final TtsAudioDispatcher dispatcher = mCurrentDispatcher;
        if (dispatcher != null) {
            dispatcher.finish();
            // Drop the stale dispatcher so a late native callback or a
            // subsequent error path cannot reuse a done dispatcher. Identity
            // check: a new request may already have installed its own.
            if (mCurrentDispatcher == dispatcher) {
                mCurrentDispatcher = null;
            }
        } else if (mCallback != null && mCallbackDone.compareAndSet(false, true)) {
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
            addNaturalOnlyVoices();
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

    /** Languages only a natural voice speaks, as voices of their own (PiperVoiceStore). */
    private void addNaturalOnlyVoices() {
        if (mPreferences == null) {
            return;
        }
        for (Voice v : PiperVoiceStore.naturalOnlyVoices(mPreferences, mAllVoices)) {
            if (!mAvailableVoices.containsKey(v.name)) {
                mAvailableVoices.put(v.name, v);
            }
        }
    }

    /**
     * Pipes synthesizer output from native eSpeak to the active audio dispatcher.
     */
    private final SpeechSynthesis.SynthReadyCallback mSynthCallback = new SynthReadyCallback() {
        @Override
        public void onSynthDataReady(byte[] audioData) {
            if ((audioData == null) || (audioData.length == 0)) {
                onSynthDataComplete();
                return;
            }

            final TtsAudioDispatcher dispatcher = mCurrentDispatcher;
            if (dispatcher != null) {
                dispatcher.writeAudio(audioData);
            }
        }

        @Override
        public void onSynthDataComplete() {
            segmentFinished();
        }

        @Override
        public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
            final TtsAudioDispatcher dispatcher = mCurrentDispatcher;
            if (dispatcher != null) {
                dispatcher.dispatchWordBoundary(textPosition, textLength, markerInFrames);
            }
        }
    };
}
