/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.tts.RequestSettings;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.text.Tashkeel;
import com.animeshahilya.espeakng.TtsService;
import com.animeshahilya.espeakng.Voice;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ai.onnxruntime.OrtException;

/**
 * Piper speech for the TTS service: which models are in memory, and how one
 * piece of text becomes audio with them.
 *
 * <p>Text -> this fork's eSpeak NG (phonemes, via {@link Phonemizer}) ->
 * Piper phoneme ids -> ONNX Runtime -> PCM, one chunk at a time so audio
 * starts after the first clause instead of after the whole utterance, and
 * so a stop lands between chunks (or aborts the run in progress). The next
 * chunk renders on a worker while the current one plays: the framework
 * blocks the synthesis thread until only ~0.5 s of audio is left queued, so
 * rendering in turn left long reading with gaps: 142 s of Hindi played in
 * 148 s on a Pixel 8, AudioFlinger counting 250835 underrun frames; with
 * rendering ahead (post-processing included, see synthesize) there are none.
 *
 * <p>The first chunk of each text is itself rendered in two pieces when the
 * voice is split ({@link PiperSplit}): the encoder over the whole chunk (so
 * its prosody is unchanged), then the decoder over the words up to a cut,
 * which play while it decodes the rest. The cut follows the voice's measured
 * decoder speed, so the rest is ready before the first piece ends - a slow
 * (Enhanced) voice gets a longer first piece instead of a gap. Both pieces
 * are at least a second long (a short chunk renders whole, as fast as
 * before), and the rest is levelled from the whole chunk, as if whole.
 *
 * <p>No Android imports: the unit tests drive it on the JVM with the desktop
 * ONNX Runtime and a real voice.
 */
public final class PiperEngine {
    /** eSpeak's default rate (wpm); Piper at length_scale 1 speaks about this fast. */
    public static final int NORMAL_RATE = 175;
    /**
     * Fastest speed asked of the model itself. VITS stays clean to about
     * 1.8x shorter phoneme durations, then starts dropping phonemes; the
     * rest of a high screen-reader rate is done by time-stretching the audio
     * (libsonic, the same library eSpeak uses above 450 wpm).
     */
    public static final float MAX_MODEL_SPEED = 1.8f;
    public static final float MIN_MODEL_SPEED = 0.5f;

    /** {@link #MAX_MODEL_SPEED}, or Supertonic's own limit ({@link Supertonic#MAX_SPEED}). */
    static float maxModelSpeed(PiperVoiceConfig config) {
        return config.isSupertonic() ? Supertonic.MAX_SPEED : MAX_MODEL_SPEED;
    }

    /** This fork's eSpeak NG as Piper's phonemizer (piperPhonemizer.c records). */
    public interface Phonemizer {
        String phonemize(String espeakVoice, String text);
    }

    /** Speed and pitch change without re-synthesis; null result = unchanged. */
    public interface TimeStretcher {
        short[] process(short[] samples, int sampleRate, float speed, float pitch);
    }

    /** Where audio and word positions go (TtsService's synthesis callback). */
    public interface Output {
        /** @param position 1-based code point index in the synthesized text */
        void word(int position, int length, int frame);

        /** @return false once the caller no longer wants audio (stopped) */
        boolean audio(byte[] pcm);

        /** True once the request was stopped, even before any audio. */
        default boolean stopped() {
            return false;
        }
    }

    public static final class Params {
        /** 1 = normal; rate / {@link #NORMAL_RATE}. */
        public float speed = 1f;
        /** 1 = voice's own pitch. */
        public float pitch = 1f;
        /** Linear gain after peak normalization. */
        public float volume = 1f;
        /** Reading pace, percent of normal pause lengths. */
        public int pauseScale = 100;
        public boolean trimSilence = true;
        public int speakerId = -1;
        /** Multipliers of the voice's noise_scale and noise_w (speaking style). */
        public float noiseScale = 1f;
        public float noiseW = 1f;

        public Params copy() {
            final Params c = new Params();
            c.speed = speed;
            c.pitch = pitch;
            c.volume = volume;
            c.pauseScale = pauseScale;
            c.trimSilence = trimSilence;
            c.speakerId = speakerId;
            c.noiseScale = noiseScale;
            c.noiseW = noiseW;
            return c;
        }
    }

    private static final PiperEngine INSTANCE = new PiperEngine();

    private static final java.util.concurrent.atomic.AtomicLong RENDERED_CHUNKS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Chunks a natural voice has spoken (rendered or from the phrase cache)
     * in this process. Device tests read it to tell which engine spoke: the
     * sample rate cannot, most Piper voices are 22050 Hz like eSpeak.
     */
    public static long renderedChunks() {
        return RENDERED_CHUNKS.get();
    }

    public static PiperEngine get() {
        return INSTANCE;
    }

    /** key -> model, least recently used first. Guarded by itself. */
    private final LinkedHashMap<String, PiperModel> mLoaded = new LinkedHashMap<>(4, 0.75f, true);
    private final Set<String> mLoading = ConcurrentHashMap.newKeySet();
    /** Keys that failed to load, so a broken file isn't retried per utterance. */
    private final Map<String, Long> mFailed = new ConcurrentHashMap<>();
    private final ExecutorService mLoader = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "piper-loader");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Renders the chunk after the one being delivered (see class comment). */
    /**
     * Repeated chunks (screen-reader UI words): 8 MB of 16-bit audio, chunks
     * up to 3 s (at Supertonic's 44.1 kHz; longer at the others' 22-24 kHz),
     * kept from their first sighting (PiperPhraseCache).
     */
    private final PiperPhraseCache mCache = new PiperPhraseCache(8L * 1024 * 1024, 3 * 44100, 1, 1024);
    private long mHitNanos;
    private long mMissNanos;
    private long mHitCount;
    private long mMissCount;

    /** A chunk's time to first audio, for the cache's numbers; reported every 25 chunks. */
    private void timed(boolean hit, long startNanos) {
        final long[] snapshot;
        synchronized (mCache) {
            if (hit) {
                mHitNanos += System.nanoTime() - startNanos;
                mHitCount++;
            } else {
                mMissNanos += System.nanoTime() - startNanos;
                mMissCount++;
            }
            if ((mHitCount + mMissCount) % 25 != 0) {
                return;
            }
            snapshot = new long[] {mHitCount, mMissCount, mHitNanos, mMissNanos};
        }
        final Listener l = mListener;
        if (l != null) {
            l.onPhraseCache(snapshot[0], snapshot[1], mCache.diskHits(),
                    snapshot[0] == 0 ? 0 : snapshot[2] / 1e6 / snapshot[0],
                    snapshot[1] == 0 ? 0 : snapshot[3] / 1e6 / snapshot[1], mCache.bytes(), mCache.size());
        }
    }

    /**
     * Keeps the phrase cache on storage too (32 MB, a phrase written once
     * heard twice), so it survives Android restarting the speech service.
     */
    public void setPhraseCacheDir(java.io.File dir) {
        mCache.setDisk(dir, 32L * 1024 * 1024, 2);
    }

    /** A deleted voice's cached phrases go with it, from memory and storage. */
    public void deleteCachedPhrases(String key) {
        mCache.deleteVoice(key);
    }

    /** Cache identity of a loaded voice: its key and model bytes, so an update never matches. */
    private static String voiceStamp(PiperModel model) {
        return model.config.key + "|" + model.stamp;
    }

    private final ExecutorService mRenderer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "piper-render");
        t.setPriority(Thread.NORM_PRIORITY + 2);
        return t;
    });
    private final Set<String> mMissingLogged = ConcurrentHashMap.newKeySet();
    /** Voices kept loaded (least recently used evicted); set per device (PiperDevice). */
    private volatile int mMaxLoaded = 2;
    private volatile PiperModel.RunHandle mCurrentRun;
    private volatile Listener mListener;
    private volatile NativeGuard mGuard;

    /**
     * Brackets native (ONNX Runtime) work per voice, so a crash inside it can
     * be pinned on the voice after the process restarts (PiperCrashGuard).
     */
    public interface NativeGuard {
        void enter(String key);

        void exit(String key);
    }

    public void setNativeGuard(NativeGuard guard) {
        mGuard = guard;
    }

    private void enterNative(String key) {
        final NativeGuard g = mGuard;
        if (g != null) {
            g.enter(key);
        }
    }

    private void exitNative(String key) {
        final NativeGuard g = mGuard;
        if (g != null) {
            g.exit(key);
        }
    }

    /** Load/failure notifications, for logging and the settings screen. */
    public interface Listener {
        /**
         * @param npu the decoder's place on a build with Qualcomm's NPU
         *            ("on the NPU", or why not), null on other builds
         */
        void onLoaded(String key, long millis, String npu);

        void onLoadFailed(String key, Throwable error);

        void onMissingPhonemes(String key, List<String> phonemes);

        /**
         * Phrase cache counts since the process started (no text): chunks
         * served from it and rendered, and their average time to first audio.
         */
        default void onPhraseCache(long hits, long misses, long fromStorage, double hitMs, double missMs,
                                   long bytes, int entries) {
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** The model if it is in memory right now; never blocks on a load. */
    public PiperModel getLoaded(String key) {
        synchronized (mLoaded) {
            final PiperModel model = mLoaded.get(key);
            return model != null && !model.isClosed() ? model : null;
        }
    }

    public boolean isLoading(String key) {
        return mLoading.contains(key);
    }

    /**
     * Starts loading a voice in the background unless it is loaded, already
     * loading, or failed within the last minute. The caller speaks with
     * eSpeak meanwhile: a screen reader must never go silent waiting.
     */
    public void preload(final String key, final File onnx, final PiperVoiceConfig config) {
        if (key == null || onnx == null || config == null || getLoaded(key) != null) {
            return;
        }
        final Long failedAt = mFailed.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000L) {
            return;
        }
        if (!mLoading.add(key)) {
            return;
        }
        mLoader.execute(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                if (getLoaded(key) == null) {
                    install(key, loadModel(key, onnx, config));
                }
                mFailed.remove(key);
                final Listener l = mListener;
                if (l != null) {
                    final PiperModel loaded = getLoaded(key);
                    l.onLoaded(key, System.currentTimeMillis() - t0, loaded == null ? null : loaded.npuState());
                }
            } catch (Throwable t) {
                mFailed.put(key, System.currentTimeMillis());
                final Listener l = mListener;
                if (l != null) {
                    l.onLoadFailed(key, t);
                }
            } finally {
                mLoading.remove(key);
            }
        });
    }

    /**
     * Builds a just-downloaded voice's phone-specific files - the optimized
     * CPU copy and, in the Snapdragon build, the compiled NPU graph - without
     * keeping it loaded, so its first use is as quick as any later one and
     * the voice in use is not evicted for it. The NPU graph compiles on its
     * own thread after this returns (PiperModel.attachNpuLater).
     */
    public void prepare(String key, File onnx, PiperVoiceConfig config) {
        mLoader.execute(() -> {
            if (getLoaded(key) != null) {
                return;
            }
            try {
                loadModel(key, onnx, config).close();
            } catch (Throwable t) {
                // Built at its first use instead.
            }
        });
    }

    /**
     * Loads and waits (the settings screen's test). On the loader thread like
     * preload(), so two loads of one voice never build its optimized copy at
     * the same time.
     */
    public PiperModel loadNow(String key, File onnx, PiperVoiceConfig config) throws OrtException {
        final Future<PiperModel> load = mLoader.submit(() -> {
            PiperModel model = getLoaded(key);
            if (model == null) {
                final long t0 = System.currentTimeMillis();
                model = loadModel(key, onnx, config);
                install(key, model);
                final Listener l = mListener;
                if (l != null) { // the settings screen's Test voice is logged too
                    l.onLoaded(key, System.currentTimeMillis() - t0, model.npuState());
                }
            }
            return model;
        });
        try {
            return load.get();
        } catch (ExecutionException e) {
            throw asOrt(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrtException("Interrupted while loading " + key);
        }
    }

    private PiperModel loadModel(String key, File onnx, PiperVoiceConfig config) throws OrtException {
        enterNative(key);
        try {
            synchronized (mLoaded) {
                for (PiperModel existing : mLoaded.values()) {
                    if (existing != null && !existing.isClosed()
                            && existing.stamp.equals(PiperModel.stamp(onnx))) {
                        final PiperModel cloned = existing.withConfig(config, onnx);
                        if (cloned != null) {
                            return cloned;
                        }
                    }
                }
            }
            return PiperModel.load(onnx, config, inferenceThreads());
        } finally {
            exitNative(key);
        }
    }

    public void setMaxLoaded(int voices) {
        mMaxLoaded = Math.max(1, voices);
    }

    /**
     * Whether a second voice can stay loaded next to the current one.
     * Language switching inside a request needs that; on a phone that keeps
     * one voice, loading the other would evict the first and both would
     * reload on every switch.
     */
    public boolean keepsSeveralLoaded() {
        return mMaxLoaded >= 2;
    }

    public int maxLoaded() {
        return mMaxLoaded;
    }

    private static OrtException asOrt(Throwable t) {
        if (t instanceof OrtException) {
            return (OrtException) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new OrtException(String.valueOf(t));
    }

    /**
     * Models a run is playing, with how many runs: never evicted, as closing
     * one ends the run's later pieces and chunks - the rest of the text went
     * silent when a voice loading in the background (startup preload) pushed
     * out the one speaking. Guarded by mLoaded.
     */
    private final Map<PiperModel, Integer> mPlaying = new java.util.IdentityHashMap<>();

    private void playing(PiperModel model, boolean start) {
        synchronized (mLoaded) {
            mPlaying.merge(model, start ? 1 : -1, (a, b) -> a + b == 0 ? null : a + b);
        }
    }

    /** True when a playing run uses the model or its shared session; caller holds mLoaded. */
    private boolean isPlaying(PiperModel model) {
        for (PiperModel p : mPlaying.keySet()) {
            if (p == model || p.sharesSessionWith(model)) {
                return true;
            }
        }
        return false;
    }

    /** Makes a freshly loaded model current, evicting beyond {@link #setMaxLoaded}. */
    private void install(String key, PiperModel model) {
        final List<PiperModel> evicted = new ArrayList<>();
        synchronized (mLoaded) {
            final PiperModel old = mLoaded.put(key, model);
            if (old != null && old != model) {
                evicted.add(old);
            }
            // Budget in models, not voices: Rasa voices share one session
            // (PiperModel.withConfig), so a second one costs no memory.
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (distinctModels() > mMaxLoaded && it.hasNext()) {
                final Map.Entry<String, PiperModel> eldest = it.next();
                // One sharing the new voice's session frees nothing.
                if (!eldest.getKey().equals(key) && !eldest.getValue().sharesSessionWith(model)
                        && !isPlaying(eldest.getValue())) {
                    evicted.add(eldest.getValue());
                    it.remove();
                }
            }
        }
        for (PiperModel m : evicted) {
            m.close(); // waits for an in-flight inference on it
        }
    }

    /** Loaded models counting each shared session once; caller holds mLoaded. */
    private int distinctModels() {
        final List<PiperModel> seen = new ArrayList<>();
        outer:
        for (PiperModel m : mLoaded.values()) {
            for (PiperModel s : seen) {
                if (m.sharesSessionWith(s)) {
                    continue outer;
                }
            }
            seen.add(m);
        }
        return seen.size();
    }

    public void unload(String key) {
        final PiperModel model;
        synchronized (mLoaded) {
            model = mLoaded.remove(key);
        }
        mFailed.remove(key);
        mCache.forget(key + "|");
        if (model != null) {
            model.close();
        }
    }

    /**
     * Frees every loaded model, unless {@code stillNeeded} says otherwise when
     * the unload's turn comes. On the loader thread - closing waits for an
     * inference in flight, and the caller is the main thread - and queued
     * after any load already started: a client that rebinds right after the last one
     * left starts a new service whose preload finds the voices still loading
     * and skips them, and the unload queued behind those loads then emptied
     * them (its first requests went to eSpeak).
     */
    public void unloadAllUnless(java.util.function.BooleanSupplier stillNeeded) {
        mLoader.execute(() -> {
            if (!stillNeeded.getAsBoolean()) {
                trim(true);
            }
        });
    }

    /**
     * Memory pressure: keep only the most recently used voice (and voices on
     * its shared session, which cost nothing more).
     */
    public void trim(boolean all) {
        final List<PiperModel> evicted = new ArrayList<>();
        synchronized (mLoaded) {
            PiperModel newest = null;
            for (PiperModel m : mLoaded.values()) {
                newest = m; // access order: the last is the most recent
            }
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (it.hasNext()) {
                final PiperModel m = it.next().getValue();
                if (all || (m != newest && !m.sharesSessionWith(newest) && !isPlaying(m))) {
                    evicted.add(m);
                    it.remove();
                }
            }
        }
        for (PiperModel m : evicted) {
            m.close();
        }
        if (all) {
            mCache.clear();
        }
    }

    /** Cancels the synthesis in progress, if any (framework control thread). */
    public void stop() {
        final PiperModel.RunHandle run = mCurrentRun;
        if (run != null) {
            run.cancel();
        }
        for (PiperModel.RunHandle ahead : mAhead) {
            ahead.cancel();
        }
    }

    /**
     * Lazily sized, once per process: reading the kernel's per-core
     * capacities is sysfs I/O, and class init runs on the main thread at
     * process start (EspeakApp.onCreate -> PiperEngine.get()). First use is
     * model load on the loader thread, so sizing happens there instead of
     * on the TTS bind path.
     */
    private static final class InferenceThreads {
        static final int VALUE = threadsFor(cpuCapacities(),
                Runtime.getRuntime().availableProcessors());
    }

    static int inferenceThreads() {
        return InferenceThreads.VALUE;
    }

    /**
     * One intra-op thread per fast core, at most 4 (VITS stops scaling
     * there), or 6 on chips with no little cores. A parallel step waits for its slowest thread, so a thread on a
     * little core slows every step: count cores with at least half the top
     * core's capacity. Pixel 8 (4x182, 4x725, 1x1024): 4. A 2+6 phone: 2,
     * where cores/2 gave 4. Without capacities (old kernels): cores/2.
     */
    static int threadsFor(int[] capacities, int cores) {
        if (capacities.length == 0) {
            return Math.max(1, Math.min(4, cores / 2));
        }
        int top = 0;
        for (int c : capacities) {
            top = Math.max(top, c);
        }
        int fast = 0;
        for (int c : capacities) {
            if (c * 2 >= top) {
                fast++;
            }
        }
        // No little cores at all (Snapdragon 8 Elite: 6x765 + 2x1024): 6 threads
        // ran SYSPIN/Rasa decoders at 4.0x real time vs 2.6x with 4 (8: 1.8x).
        return Math.max(1, Math.min(fast == capacities.length ? 6 : 4, fast));
    }

    /** Core capacities never change at runtime: read sysfs once per process. */
    private static volatile int[] sCapacities;

    /** The kernel's relative core performance (EAS, 1024 = fastest); empty if unavailable. */
    private static int[] cpuCapacities() {
        int[] caps = sCapacities;
        if (caps == null) {
            caps = readCpuCapacities();
            sCapacities = caps;
        }
        return caps;
    }

    private static int[] readCpuCapacities() {
        final List<Integer> caps = new ArrayList<>();
        for (int cpu = 0; cpu < 64; cpu++) {
            final File f = new File("/sys/devices/system/cpu/cpu" + cpu + "/cpu_capacity");
            if (!f.isFile()) {
                break;
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                caps.add(Integer.parseInt(r.readLine().trim()));
            } catch (IOException | RuntimeException e) {
                return new int[0];
            }
        }
        final int[] out = new int[caps.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = caps.get(i);
        }
        return out;
    }

    private static final java.util.regex.Pattern DIGITS =
            java.util.regex.Pattern.compile("\\p{Nd}+(?:,\\p{Nd}{2,3})*");
    private static final java.util.regex.Pattern HAS_DIGIT =
            java.util.regex.Pattern.compile(".*\\d.*");
    private static final java.util.regex.Pattern HAS_SEVEN =
            java.util.regex.Pattern.compile("(?i).*seven.*");
    private static final java.util.regex.Pattern WHITESPACE_RUN =
            java.util.regex.Pattern.compile("\\s+");

    /**
     * "Text" voices read letters, and their alphabets have no digits: numbers
     * become words in the voice's language. Indian languages use CLDR's rules
     * bundled in {@code data/numbers/} (Android's ICU has only a few of them);
     * other languages use the phone's ICU. Null when neither has rules: the
     * digits then go to eSpeak (RequestSettings.needsDigitHelp).
     */
    public static java.util.function.UnaryOperator<String> numberWords(String language) {
        if (language == null) {
            return null;
        }
        final java.util.function.UnaryOperator<String> words =
                NUMBER_WORDS.computeIfAbsent(language, PiperEngine::buildNumberWords);
        return words == NO_NUMBER_WORDS ? null : words;
    }

    private static final Map<String, java.util.function.UnaryOperator<String>> NUMBER_WORDS =
            new ConcurrentHashMap<>();
    private static final java.util.function.UnaryOperator<String> NO_NUMBER_WORDS = s -> s;

    /**
     * Devanagari languages eSpeak has no voice for: its Hindi stand-in
     * already read their digits as Hindi words, so their natural voices get
     * the same words (every letter checked against their alphabets).
     */
    private static final java.util.Set<String> HINDI_NUMBERS = new java.util.HashSet<>(
            java.util.Arrays.asList("bho", "brx", "doi", "hne", "mag", "mai", "sa"));

    private static java.util.function.UnaryOperator<String> buildNumberWords(String language) {
        final java.util.function.LongFunction<String> format;
        try {
            format = spellout(language);
            // Without rules for the language ICU falls back to English words (or digits).
            final String seven = format == null ? null : format.apply(7L);
            if (seven == null || HAS_DIGIT.matcher(seven).matches()
                    || (!"en".equals(language) && HAS_SEVEN.matcher(seven).matches())) {
                return NO_NUMBER_WORDS;
            }
        } catch (RuntimeException e) { // no ICU data for it: digits stay
            return NO_NUMBER_WORDS;
        }
        return s -> {
            if (s == null) {
                return null;
            }
            final java.util.regex.Matcher m = DIGITS.matcher(s);
            if (!m.find()) {
                return s;
            }
            final StringBuffer out = new StringBuffer(s.length() * 2);
            do {
                final String digits = m.group().replace(",", "");
                String words;
                try {
                    if (digits.length() > 15) {
                        words = m.group();
                    } else {
                        words = format.apply(Long.parseLong(toAscii(digits)));
                    }
                } catch (RuntimeException e) {
                    words = m.group();
                }
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(" " + words + " "));
            } while (m.find());
            m.appendTail(out);
            return WHITESPACE_RUN.matcher(out.toString()).replaceAll(" ").trim();
        };
    }

    /** The bundled CLDR rules for the language, else the phone's ICU; null if neither loads. */
    private static java.util.function.LongFunction<String> spellout(String language) {
        final String rules = bundledRules(HINDI_NUMBERS.contains(language) ? "hi" : language);
        if (rules != null) {
            final Spellout f = new Spellout(rules); // immutable, thread-safe
            return n -> f.format(n, "%spellout-numbering");
        }
        final android.icu.text.MessageFormat f = new android.icu.text.MessageFormat("{0,spellout}",
                new android.icu.util.ULocale(language));
        return n -> {
            synchronized (f) {
                return f.format(new Object[] {n});
            }
        };
    }

    /** data/numbers/&lt;language&gt;.txt (tools/update_number_words.py), or null. */
    static String bundledRules(String language) {
        if (!com.animeshahilya.espeakng.text.BundledData.ready()) {
            return null;
        }
        try (java.io.BufferedReader in =
                     com.animeshahilya.espeakng.text.BundledData.reader("numbers/" + language + ".txt")) {
            final StringBuilder sb = new StringBuilder();
            for (String line; (line = in.readLine()) != null; ) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * "१,००,०००" -> "100000": Long.parseLong reads only ASCII digits, so the
     * group commas DIGITS matches are dropped along with the conversion.
     */
    static String toAscii(String digits) {
        final StringBuilder sb = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); ) {
            final int cp = digits.codePointAt(i);
            final int d = Character.digit(cp, 10);
            if (d >= 0) {
                sb.append((char) ('0' + d));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * Synthesizes one piece of text.
     *
     * @return audio frames written, or -1 if the text could not be
     *         phonemized (the caller falls back to eSpeak for it)
     */
    int synthesize(PiperModel model, String text, Phonemizer phonemizer, TimeStretcher stretcher,
                   Params params, Output out) throws OrtException {
        final Prepared prepared = prepare(model, text, phonemizer, stretcher, params);
        return prepared == null ? -1 : play(prepared, out, null);
    }

    /**
     * A piece of text phonemized, with its first chunk already rendering, so
     * the next voice of a mixed-language request starts while the current one
     * still plays: before, each run began rendering only once the previous
     * one had delivered its last audio, a gap at every switch on slow voices.
     * Each one is {@link #play}ed or {@link #discard}ed.
     */
    public static final class Prepared {
        public final PiperModel model;
        public final Pass pass;
        public final List<Job> jobs;
        public final List<String> missing;
        public final PiperModel.RunHandle handle;
        public final Params params;
        public final float speed;
        public final Future<Rendered> first;

        Prepared(PiperModel model, Pass pass, List<Job> jobs, List<String> missing,
                 PiperModel.RunHandle handle, Params params, float speed, Future<Rendered> first) {
            this.model = model;
            this.pass = pass;
            this.jobs = jobs;
            this.missing = missing;
            this.handle = handle;
            this.params = params;
            this.speed = speed;
            this.first = first;
        }
    }

    /** Prepared runs not yet playing: {@link #stop} cancels them too. */
    private final Set<PiperModel.RunHandle> mAhead = ConcurrentHashMap.newKeySet();

    /**
     * Phonemizes {@code text} and starts rendering its first chunk. Copies
     * the params: callers change them per voice before the run plays.
     *
     * @return null if the text could not be phonemized
     */
    public Prepared prepare(PiperModel model, String text, Phonemizer phonemizer, TimeStretcher stretcher,
                     Params callerParams) throws OrtException {
        final Params params = callerParams.copy();
        final PiperVoiceConfig config = model.config;
        final List<PiperPhonemes.Clause> clauses;
        if (config.usesEspeak()) {
            final Tashkeel.Result vowelled = config.tashkeel
                    ? Tashkeel.apply(model.file.getParentFile(), text) : null;
            final String said = vowelled != null ? vowelled.text : text;
            final String raw = phonemizer.phonemize(config.espeakVoice, said);
            if (raw == null) {
                return null;
            }
            final List<PiperPhonemes.Clause> aligned =
                    PiperPhonemes.alignToText(PiperPhonemes.parseRecords(raw), said);
            clauses = vowelled != null ? PiperPhonemes.remap(aligned, vowelled.toOriginal) : aligned;
        } else if (config.isSupertonic()) {
            clauses = null; // Supertonic.chunks below
        } else {
            clauses = PiperPhonemes.textClauses(text, numberWords(config.languageFamily), config.shortChunks);
        }
        final float speed = shortInputSpeed(text, config, Math.max(0.1f, params.speed))
                * (config.isSupertonic() ? Supertonic.RATE_FACTOR : 1f);
        final float modelSpeed = Math.max(MIN_MODEL_SPEED, Math.min(maxModelSpeed(config), speed));
        // Supertonic reads numbers, currency and units itself, from whole sentences.
        final List<PiperPhonemes.Chunk> chunks = config.isSupertonic()
                ? Supertonic.chunks(text, config.supertonicLang, modelSpeed)
                : PiperPhonemes.chunk(clauses, PiperPhonemes.FIRST_CHUNK_PHONEMES,
                config.shortChunks ? PiperPhonemes.SHORT_CHUNK : PiperPhonemes.CHUNK_PHONEMES, config.finalStop);

        final float residualSpeed = speed / modelSpeed;
        final float pitch = params.pitch;
        final boolean stretch = stretcher != null
                && (Math.abs(residualSpeed - 1f) > 0.01f || Math.abs(pitch - 1f) > 0.01f);
        final float lengthScale = config.lengthScale / modelSpeed;
        final int speaker = params.speakerId >= 0 && params.speakerId < config.numSpeakers
                ? params.speakerId : config.defaultSpeakerId;
        final float noise = params.noiseScale;
        final float noiseW = params.noiseW;
        // The voice's own pauses follow reading pace too (PiperAudio.MAX_PAUSE_MS).
        final int maxPauseMs = PiperAudio.maxPauseMs(params.pauseScale, speed);
        final int rate = config.sampleRate;

        // Ids for every chunk up front (microseconds); the model runs are
        // what the renderer overlaps with delivery.
        final int[] cpToIndex = codePointIndex(text);
        final List<String> missing = new ArrayList<>();
        final List<Job> jobs = new ArrayList<>(chunks.size());
        for (PiperPhonemes.Chunk chunk : chunks) {
            final List<Integer> wordStarts = new ArrayList<>();
            // Supertonic: its own text ids; no per-id durations, so word
            // timing is estimated over the chunk (PiperAudio.estimateWordFrames).
            final long[] ids = model.isSupertonic() ? model.supertonicIds(chunk.ipa)
                    : PiperPhonemes.toIds(PiperPhonemes.tokenize(chunk.ipa, config),
                    config, missing, wordStarts);
            if (ids.length > 3) { // more than BOS PAD EOS: something to say
                final int lead = needsLeadIn(model) ? LEAD_IN : 0;
                jobs.add(new Job(chunk, withLeadIn(ids, config, lead, wordStarts), wordStarts, lead,
                        text, cpToIndex));
            }
        }

        final PiperModel.RunHandle handle = new PiperModel.RunHandle();
        mAhead.add(handle);
        try {
            // Everything but delivery happens on the renderer: model run, level,
            // trim, pitch/speed stretch and PCM bytes. Measured: libsonic alone
            // took 200-500 ms per sentence on the synthesis thread while the
            // model used the fast cores - enough to drain the framework's ~0.5 s
            // of queued audio and stall playback.
            final Pass pass = new Pass(model, params, stretch ? stretcher : null, residualSpeed, pitch,
                    lengthScale, speaker, noise, noiseW, maxPauseMs, handle);
            final Future<Rendered> first = jobs.isEmpty() ? null
                    : mRenderer.submit(() -> pass.render(jobs.get(0), true));
            return new Prepared(model, pass, jobs, missing, handle, params, speed, first);
        } catch (Throwable t) {
            mAhead.remove(handle);
            handle.close();
            throw t;
        }
    }

    /** Drops a prepared run that will not play, ending its render first. */
    public void discard(Prepared p) {
        if (p == null) {
            return;
        }
        p.handle.cancel();
        if (p.first != null) {
            try {
                final Rendered r = p.first.get();
                if (r != null && r.restTask != null) {
                    awaitQuietly(r.restTask);
                }
            } catch (Exception ignored) {
                // Cancelled or failed: nothing left in flight.
            }
        }
        mAhead.remove(p.handle);
        p.handle.close();
    }

    /**
     * Delivers a prepared run.
     *
     * @param lastRendered called once the run's last chunk has rendered and
     *                     only delivery is left: the moment to prepare the
     *                     next run without delaying this one's chunks on the
     *                     single renderer. May be null.
     * @return audio frames written
     */
    public int play(Prepared p, Output out, Runnable lastRendered) throws OrtException {
        final PiperModel model = p.model;
        final PiperVoiceConfig config = model.config;
        final List<Job> jobs = p.jobs;
        final Pass pass = p.pass;
        final Params params = p.params;
        final float speed = p.speed;
        final int rate = config.sampleRate;
        final PiperModel.RunHandle handle = p.handle;
        mCurrentRun = handle;
        mAhead.remove(handle);
        playing(model, true);
        // A stop() that came before mCurrentRun was set found nothing to
        // cancel; the caller's flag still says so (it is set before stop()).
        if (out.stopped()) {
            handle.cancel();
        }
        int frames = 0;
        Future<Rendered> pending = p.first;
        Future<?> pendingRest = null;
        boolean announced = false;
        try {
            for (int j = 0; j < jobs.size(); j++) {
                Rendered rendered;
                rendered = await(pending);
                // The chunk's later pieces were queued ahead of this.
                pendingRest = rendered != null ? rendered.restTask : null;
                if (j + 1 < jobs.size()) {
                    final Job next = jobs.get(j + 1);
                    pending = mRenderer.submit(() -> pass.render(next, false));
                } else {
                    pending = null;
                    if (lastRendered != null && rendered != null) {
                        announced = true;
                        lastRendered.run();
                    }
                }
                final Job job = jobs.get(j);
                boolean delivered = true;
                while (rendered != null && !handle.isCancelled()) {
                    // Word positions first, then the audio they point into (the
                    // framework takes rangeStart() markers ahead of the frames).
                    int[] wordFrames = PiperAudio.alignedWordFrames(rendered.wordStarts,
                            rendered.durations, config.hopLength, rendered.wordTo - rendered.wordFrom,
                            rendered.trimmedLead, rendered.cuts, rendered.speechSamples,
                            rendered.samples);
                    if (wordFrames == null) {
                        wordFrames = PiperAudio.estimateWordFrames(job.words, rendered.wordFrom,
                                rendered.wordTo, job.chunkEnd - job.chunkStart, rendered.samples);
                    }
                    for (int w = 0; w < wordFrames.length && (rendered.wordFrom + w) < (job.words.length / 2); w++) {
                        final int word = 2 * (rendered.wordFrom + w);
                        out.word(job.chunkStart + job.words[word] + 1,
                                job.words[word + 1] - job.words[word], frames + wordFrames[w]);
                    }
                    if (!out.audio(rendered.pcm)) {
                        delivered = false;
                        break;
                    }
                    frames += rendered.samples;
                    if (rendered.rest == null) {
                        break;
                    }
                    rendered = await(rendered.rest);
                }
                if (rendered != null && rendered.rest == null) {
                    pendingRest = null; // every piece delivered: nothing left in flight
                }
                if (rendered == null || handle.isCancelled() || !delivered) {
                    break;
                }

                if (j < jobs.size() - 1) {
                    // No pause after the last chunk: trailing silence would
                    // only delay the screen reader's next utterance.
                    final int pause = PiperAudio.pauseSamples(rate, job.chunk.endsSentence,
                            params.pauseScale, speed);
                    if (pause > 0) {
                        if (!out.audio(new byte[pause * 2])) {
                            break;
                        }
                        frames += pause;
                    }
                }
            }
        } finally {
            if (pending != null || pendingRest != null) {
                // Stopped or failed with the next chunk in flight: end it
                // before its RunOptions are closed below.
                handle.cancel();
                if (pendingRest != null) {
                    awaitQuietly(pendingRest);
                }
                if (pending != null) {
                    awaitQuietly(pending);
                }
            }
            if (mCurrentRun == handle) {
                mCurrentRun = null;
            }
            handle.close();
            playing(model, false);
        }
        if (!announced && lastRendered != null) {
            lastRendered.run(); // nothing said, stopped or failed: the caller still moves on
        }
        if (!p.missing.isEmpty() && mMissingLogged.add(config.key)) {
            final Listener l = mListener;
            if (l != null) {
                l.onMissingPhonemes(config.key, p.missing);
            }
        }
        return frames;
    }

    /**
     * Shortest piece on either side of a cut, in seconds of audio. The
     * first piece sets its own level: one word of it could make that a few
     * dB off (a Hindi run inside English text came out ~3 dB quieter), a
     * second of speech keeps it within about a dB of the whole chunk's.
     */
    static final float MIN_PIECE_S = 1f;
    /** Share of the measured decoder speed counted on when placing the cut. */
    static final float DECODE_SPEED_MARGIN = 0.7f;
    /**
     * Shortest first piece once the voice's usual level is known
     * ({@link #pieceEnds}): its level then comes from the voice, not from
     * the piece, so a short piece is not a loudness risk.
     */
    static final float FIRST_PIECE_S = 0.3f;

    /**
     * Where to cut a chunk of {@code frames} latent frames so the rest decodes
     * while the first piece plays: at the first word start with at least
     * (frames + overlap) / (1 + speed) frames before it, speed being audio
     * seconds per second of decoding (with a margin), and at least
     * {@code minFrames} on each side.
     *
     * @param wordStarts id index of each word
     * @param durations  frames per id
     * @return the index of the word that starts the second piece, or -1 for
     *         no cut (too short, or no word start far enough in)
     */
    static int firstPieceEnd(List<Integer> wordStarts, float[] durations, int frames, double speed,
                             int minFrames) {
        final double s = Math.max(0, speed) * DECODE_SPEED_MARGIN;
        final int needed = Math.max(minFrames,
                (int) Math.ceil((frames + PiperModel.DECODE_OVERLAP) / (1 + s)));
        int id = 0;
        double at = 0;
        for (int w = 1; w < wordStarts.size(); w++) {
            while (id < wordStarts.get(w)) {
                at += durations[id++];
            }
            if (at >= needed) {
                return frames - at >= minFrames ? w : -1;
            }
        }
        return -1;
    }

    /**
     * Growing pieces for a chunk: the first ends at the first word start at
     * least {@code firstFrames} in, and each later piece is as long as can
     * still decode while the ones before it play (the decoder runs at
     * {@code speed}, with a margin, plus {@link PiperModel#DECODE_OVERLAP}
     * context each side). First audio then waits for a fraction of a second
     * of speech, not a second or the whole chunk. Only for a decoder faster
     * than real time; a slower one keeps {@link #firstPieceEnd}.
     *
     * @return the word index each piece after the first starts at; empty for
     *         no split
     */
    static int[] pieceEnds(List<Integer> wordStarts, float[] durations, int frames, double speed,
                           int firstFrames) {
        final double s = Math.max(0, speed) * DECODE_SPEED_MARGIN;
        final int n = wordStarts.size();
        if (s < 1 || n < 2) {
            return new int[0];
        }
        final double[] pos = new double[n];
        int id = 0;
        double at = 0;
        for (int w = 1; w < n; w++) {
            while (id < wordStarts.get(w)) {
                at += durations[id++];
            }
            pos[w] = at;
        }
        // The earliest first cut from which every later piece is in time.
        for (int first = 1; first < n; first++) {
            if (pos[first] < firstFrames) {
                continue;
            }
            if (frames - pos[first] < firstFrames) {
                break;
            }
            final int[] plan = laterPieces(pos, frames, s, first);
            if (plan != null) {
                return plan;
            }
        }
        return new int[0];
    }

    /** {@link #pieceEnds} from a first cut: each next piece the longest still in time; null if none is. */
    private static int[] laterPieces(double[] pos, int frames, double s, int first) {
        final int n = pos.length;
        final int ov = PiperModel.DECODE_OVERLAP;
        final List<Integer> ends = new ArrayList<>();
        ends.add(first);
        int cur = first;
        double decoding = 0; // frames of play the later pieces' decoding has taken so far
        while (decoding + (frames - pos[cur] + ov) / s > pos[cur]) {
            int next = -1;
            for (int w = cur + 1; w < n && decoding + (pos[w] - pos[cur] + 2 * ov) / s <= pos[cur]; w++) {
                next = w;
            }
            if (next < 0) {
                return null; // even the next word would arrive late
            }
            decoding += (pos[next] - pos[cur] + 2 * ov) / s;
            cur = next;
            ends.add(cur);
        }
        final int[] out = new int[ends.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ends.get(i);
        }
        return out;
    }

    /**
     * Each voice's usual speech power (log-average of its recent chunks):
     * the level for pieces too short to measure their own.
     */
    private final Map<String, Double> mVoiceLevel = new ConcurrentHashMap<>();

    private void noteLevel(String key, float[] whole, int rate) {
        final double p = PiperAudio.speechPower(whole, rate);
        if (p > 0) {
            mVoiceLevel.merge(key, p, (old, now) -> Math.exp(0.7 * Math.log(old) + 0.3 * Math.log(now)));
        }
    }

    /** Everything one synthesize() call renders with; runs on the renderer thread. */
    private final class Pass {
        final PiperModel model;
        final Params params;
        final TimeStretcher stretcher;
        final float residualSpeed;
        final float pitch;
        final float lengthScale;
        final int speaker;
        final float noise;
        final float noiseW;
        final int maxPauseMs;
        final PiperModel.RunHandle handle;
        /** Speech power the passage has been set to so far (0 before its first chunk). */
        private volatile double passageLevel;

        Pass(PiperModel model, Params params, TimeStretcher stretcher, float residualSpeed,
             float pitch, float lengthScale, int speaker, float noise, float noiseW, int maxPauseMs,
             PiperModel.RunHandle handle) {
            this.model = model;
            this.params = params;
            this.stretcher = stretcher;
            this.residualSpeed = residualSpeed;
            this.pitch = pitch;
            this.lengthScale = lengthScale;
            this.speaker = speaker;
            this.noise = noise;
            this.noiseW = noiseW;
            this.maxPauseMs = maxPauseMs;
            this.handle = handle;
        }

        /** A chunk, in two pieces if {@code split} and the voice allows (see class comment). */
        Rendered render(Job job, boolean split) throws OrtException {
            final long started = System.nanoTime();
            RENDERED_CHUNKS.incrementAndGet();
            final double passage = passageLevel;
            final String key = model.config.key;
            final int words = job.words.length / 2;
            // A chunk said before with these settings: its model run, re-processed.
            final PiperPhraseCache.Key cacheKey = new PiperPhraseCache.Key(voiceStamp(model), job.ids,
                    speaker, lengthScale, noise, noiseW);
            final PiperPhraseCache.Entry cached = mCache.get(cacheKey);
            if (cached != null) {
                noteLevel(key, cached.audio, model.config.sampleRate);
                final Rendered r = finish(cached.audio, cached.durations, job.wordStarts, 0, words,
                        true, true, 0, passage, trebleCut());
                timed(true, started);
                return r;
            }
            if (!split || !model.streams() || job.wordStarts.size() != words || words < 2) {
                final PiperModel.Output o;
                enterNative(key);
                try {
                    o = model.infer(job.ids, lengthScale, speaker, noise, noiseW, handle);
                } finally {
                    exitNative(key);
                }
                if (o == null) {
                    return null;
                }
                final int leadSamples = cutLeadIn(o.durations, job.lead) * model.config.hopLength;
                final float[] audio = leadSamples == 0 ? o.audio
                        : Arrays.copyOfRange(o.audio, Math.min(o.audio.length, leadSamples), o.audio.length);
                if (leadSamples > 0) {
                    PiperAudio.fadeIn(audio, model.config.sampleRate);
                }
                mCache.offer(cacheKey, audio, o.durations); // before finish() alters the audio
                noteLevel(key, audio, model.config.sampleRate);
                final Rendered r = finish(audio, o.durations, job.wordStarts, 0, words, true, true, 0,
                        passage, trebleCut());
                timed(false, started);
                return r;
            }
            final PiperModel.Encoded e;
            enterNative(key);
            try {
                e = model.encode(job.ids, lengthScale, speaker, noise, noiseW, handle);
            } finally {
                exitNative(key);
            }
            if (e == null) {
                return null;
            }
            final int hop = model.config.hopLength;
            final int rate = model.config.sampleRate;
            // Decoding starts after the lead-in, which stays as the decoder's context.
            final int lead = cutLeadIn(e.durations, job.lead);
            // Played faster by the time stretch: less time to decode the rest in.
            final double speed = model.decodeSpeed() / (stretcher != null ? residualSpeed : 1f);
            final Double voiceLevel = mVoiceLevel.get(key);
            if (voiceLevel != null && e.durations != null) {
                final int[] ends = pieceEnds(job.wordStarts, e.durations, e.frames - lead, speed,
                        (int) Math.ceil(FIRST_PIECE_S * rate / hop));
                if (ends.length > 0) {
                    return renderPieces(job, e, lead, ends, voiceLevel, passage, cacheKey, started);
                }
            }
            final int cut = voiceLevel != null || e.durations == null ? -1 : firstPieceEnd(job.wordStarts,
                    e.durations, e.frames - lead, speed, (int) Math.ceil(MIN_PIECE_S * rate / hop));
            if (cut < 0) {
                final float[] audio = decode(e, lead, e.frames);
                if (audio == null) {
                    return null;
                }
                mCache.offer(cacheKey, audio, e.durations);
                noteLevel(key, audio, rate);
                final Rendered r = finish(audio, e.durations, job.wordStarts, 0, words, true, true, 0,
                        passage, trebleCut());
                timed(false, started);
                return r;
            }
            final int splitId = job.wordStarts.get(cut);
            int splitFrame = lead;
            for (int i = 0; i < splitId; i++) {
                splitFrame += Math.round(e.durations[i]);
            }
            final float[] head = decode(e, lead, splitFrame);
            if (head == null) {
                return null;
            }
            final float[] headRaw = head.clone(); // finish() may alter head; the cache needs it as made
            final PiperAudio.TrebleCut treble = trebleCut(); // one filter across both pieces
            final Rendered first = finish(head, Arrays.copyOfRange(e.durations, 0, splitId),
                    job.wordStarts.subList(0, cut), 0, cut, true, false, 0, passage, treble);
            timed(false, started);
            final List<Integer> restStarts = new ArrayList<>();
            for (int w = cut; w < words; w++) {
                restStarts.add(job.wordStarts.get(w) - splitId);
            }
            final int from = splitFrame;
            // Queued now, so it runs before the next chunk's render.
            first.rest = mRenderer.submit(() -> {
                final float[] tail = decode(e, from, e.frames);
                if (tail == null) {
                    return null;
                }
                final float[] whole = Arrays.copyOf(headRaw, headRaw.length + tail.length);
                System.arraycopy(tail, 0, whole, headRaw.length, tail.length);
                mCache.offer(cacheKey, whole, e.durations);
                noteLevel(key, whole, rate);
                return finish(tail, Arrays.copyOfRange(e.durations, splitId, e.durations.length),
                        restStarts, cut, words, false, true, PiperAudio.speechPower(whole, rate), passage,
                        treble);
            });
            first.restTask = first.rest;
            return first;
        }

        /**
         * A chunk in growing pieces ({@link #pieceEnds}), all at the voice's
         * usual level: the first decoded here, the rest by one renderer task
         * queued now (so before the next chunk's render), each handed over as
         * soon as it is ready.
         */
        private Rendered renderPieces(Job job, PiperModel.Encoded e, int lead, int[] ends, double level,
                                      double passage, PiperPhraseCache.Key cacheKey, long started)
                throws OrtException {
            final int rate = model.config.sampleRate;
            final int words = job.words.length / 2;
            final int n = ends.length + 1;
            final int[] wordAt = new int[n + 1]; // piece i: words [wordAt[i], wordAt[i + 1])
            final int[] idAt = new int[n + 1];
            final int[] frameAt = new int[n + 1];
            wordAt[n] = words;
            idAt[n] = e.durations.length;
            frameAt[0] = lead;
            frameAt[n] = e.frames;
            for (int i = 1; i < n; i++) {
                wordAt[i] = ends[i - 1];
                idAt[i] = job.wordStarts.get(wordAt[i]);
                int f = lead;
                for (int k = 0; k < idAt[i]; k++) {
                    f += Math.round(e.durations[k]);
                }
                frameAt[i] = f;
            }
            final PiperAudio.TrebleCut treble = trebleCut(); // one filter across all pieces
            final float[] head = decode(e, frameAt[0], frameAt[1]);
            if (head == null) {
                return null;
            }
            final List<float[]> raws = new ArrayList<>();
            raws.add(head.clone()); // finish() may alter head; the cache needs it as made
            final Rendered first = piece(job, e, wordAt, idAt, 0, head, level, passage, treble);
            timed(false, started);
            final List<java.util.concurrent.CompletableFuture<Rendered>> next = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                next.add(new java.util.concurrent.CompletableFuture<>());
            }
            first.rest = next.get(0);
            first.restTask = mRenderer.submit(() -> {
                int i = 1;
                try {
                    for (; i < n; i++) {
                        final float[] audio = decode(e, frameAt[i], frameAt[i + 1]);
                        if (audio == null) {
                            break;
                        }
                        raws.add(audio.clone());
                        final Rendered r = piece(job, e, wordAt, idAt, i, audio, level, passage, treble);
                        if (i + 1 < n) {
                            r.rest = next.get(i);
                        }
                        next.get(i - 1).complete(r);
                    }
                    if (i == n) {
                        int length = 0;
                        for (float[] raw : raws) {
                            length += raw.length;
                        }
                        final float[] whole = new float[length];
                        int at = 0;
                        for (float[] raw : raws) {
                            System.arraycopy(raw, 0, whole, at, raw.length);
                            at += raw.length;
                        }
                        mCache.offer(cacheKey, whole, e.durations);
                        noteLevel(model.config.key, whole, rate);
                    }
                } catch (Throwable t) {
                    for (int k = i; k < n; k++) {
                        next.get(k - 1).completeExceptionally(t);
                    }
                    throw t;
                }
                for (int k = i; k < n; k++) {
                    next.get(k - 1).complete(null); // stopped: nothing more to play
                }
                return null;
            });
            return first;
        }

        /** Piece {@code i} of {@link #renderPieces}, post-processed. */
        private Rendered piece(Job job, PiperModel.Encoded e, int[] wordAt, int[] idAt, int i,
                               float[] audio, double level, double passage, PiperAudio.TrebleCut treble) {
            final List<Integer> starts = new ArrayList<>();
            for (int w = wordAt[i]; w < wordAt[i + 1]; w++) {
                starts.add(job.wordStarts.get(w) - idAt[i]);
            }
            final int last = wordAt.length - 2;
            return finish(audio, Arrays.copyOfRange(e.durations, idAt[i], idAt[i + 1]), starts,
                    wordAt[i], wordAt[i + 1], i == 0, i == last, level, passage, treble);
        }

        private float[] decode(PiperModel.Encoded e, int from, int to) throws OrtException {
            enterNative(model.config.key);
            try {
                return model.decode(e, from, to, handle);
            } finally {
                exitNative(model.config.key);
            }
        }

        /** The treble cut for a hissy voice ({@link PiperVoiceConfig#hissy}), else null. */
        private PiperAudio.TrebleCut trebleCut() {
            return model.config.hissy ? new PiperAudio.TrebleCut(model.config.sampleRate) : null;
        }

        /**
         * Treble cut, level, trim, pause cap, stretch and PCM bytes for one piece of audio.
         *
         * @param passage the passage's level when this chunk started ({@link #passageLevel});
         *                the piece that ends the chunk ({@code tail}) moves it on
         */
        private Rendered finish(float[] audio, float[] durations, List<Integer> wordStarts,
                                int wordFrom, int wordTo, boolean lead, boolean tail, double level,
                                double passage, PiperAudio.TrebleCut treble) {
            final int rate = model.config.sampleRate;
            if (treble != null) {
                audio = treble.apply(audio);
            }
            level = PiperAudio.passageLevel(passage, level > 0 ? level : PiperAudio.speechPower(audio, rate));
            if (tail && level > 0) {
                passageLevel = level;
            }
            final PiperAudio.Pcm pcm = PiperAudio.process(audio, params.volume, rate,
                    params.trimSilence && lead, params.trimSilence && tail, maxPauseMs, level,
                    model.config.hissy);
            short[] samples = pcm.samples;
            if (stretcher != null && samples.length > 0) {
                final short[] stretched = stretcher.process(samples, rate, residualSpeed, pitch);
                if (stretched != null) {
                    samples = stretched;
                }
            }
            return new Rendered(PiperAudio.toBytes(samples, samples.length), samples.length,
                    pcm.trimmedLead, pcm.cuts, pcm.speechSamples, durations, wordStarts, wordFrom,
                    wordTo);
        }
    }

    /** A chunk, or a piece of one, ready to deliver: 16-bit PCM plus what word timing needs. */
    private static final class Rendered {
        final byte[] pcm;
        final int samples;
        final int trimmedLead;
        final int[] cuts;
        final int speechSamples;
        final float[] durations;
        /** Id index of each of this piece's words, from its first id. */
        final List<Integer> wordStarts;
        /** This piece's words: [wordFrom, wordTo) of the chunk's. */
        final int wordFrom;
        final int wordTo;
        /** The chunk's next piece, being rendered; null for the last. */
        volatile Future<Rendered> rest;
        /** First piece only: the task rendering the later pieces, awaited before a stop closes the run. */
        volatile Future<?> restTask;

        Rendered(byte[] pcm, int samples, int trimmedLead, int[] cuts, int speechSamples,
                 float[] durations, List<Integer> wordStarts, int wordFrom, int wordTo) {
            this.pcm = pcm;
            this.samples = samples;
            this.trimmedLead = trimmedLead;
            this.cuts = cuts;
            this.speechSamples = speechSamples;
            this.durations = durations;
            this.wordStarts = wordStarts;
            this.wordFrom = wordFrom;
            this.wordTo = wordTo;
        }
    }

    /**
     * Blank ids put before each chunk and cut from its audio. The SYSPIN
     * voices stumble on the first sound of an input (as rhasspy/piper#252
     * reports for Piper voices): a word a language switch leaves on its own
     * lost its start ("Delhi" -> "E", "battery" -> "vatri"). With a lead-in
     * to settle on, then cut at its exact frames, the word starts cleanly; the
     * decoder's overlap context makes the cut seamless. Measured (Whisper,
     * 20 English words x 3 renders): Priya Compact 10/60 -> 32/60 words
     * recognised. Rasa and Piper voices said lone words well already and the
     * lead-in made them worse (Mrunal 9/12 -> 4/12), so they get none.
     */
    static final int LEAD_IN = 8;

    /** Up to this many words is "short input" ({@link PiperVoiceConfig#normalSpeedShortInput}). */
    static final int SHORT_INPUT_WORDS = 4;

    /** {@code speed}, capped at normal for a voice that rushes short input when the text is short. */
    static float shortInputSpeed(String text, PiperVoiceConfig config, float speed) {
        if (!config.normalSpeedShortInput || speed <= 1f || text == null) {
            return speed;
        }
        final String trimmed = text.trim();
        return !trimmed.isEmpty() && trimmed.split("\\s+").length <= SHORT_INPUT_WORDS ? 1f : speed;
    }

    /** SYSPIN: a letter-reading voice at 22050 Hz (Rasa's are 24 kHz, Piper's read phonemes). */
    static boolean needsLeadIn(PiperModel model) {
        return model.hasDurations() && !model.config.usesEspeak() && model.config.sampleRate == 22050;
    }

    /** {@code ids} with {@code lead} blanks after BOS; word starts shift with them. */
    static long[] withLeadIn(long[] ids, PiperVoiceConfig config, int lead, List<Integer> wordStarts) {
        final int[] pad = config.phonemeIdMap.get(PiperVoiceConfig.PAD);
        if (lead <= 0 || pad == null || pad.length != 1 || ids.length == 0) {
            return ids;
        }
        final long[] out = new long[ids.length + lead];
        out[0] = ids[0];
        Arrays.fill(out, 1, 1 + lead, pad[0]);
        System.arraycopy(ids, 1, out, 1 + lead, ids.length - 1);
        for (int i = 0; i < wordStarts.size(); i++) {
            wordStarts.set(i, wordStarts.get(i) + lead);
        }
        return out;
    }

    /**
     * Frames of BOS and the lead-in, which are then zeroed in {@code durations}
     * so word timing and the first-piece cut count from the audio that stays.
     */
    static int cutLeadIn(float[] durations, int lead) {
        if (lead <= 0 || durations == null) {
            return 0;
        }
        int frames = 0;
        for (int i = 0; i <= lead && i < durations.length; i++) {
            frames += Math.round(durations[i]);
            durations[i] = 0;
        }
        return frames;
    }

    /** One chunk's model input, its text's words, and where they start in the ids. */
    private static final class Job {
        final PiperPhonemes.Chunk chunk;
        final long[] ids;
        final List<Integer> wordStarts;
        /** Blank ids after BOS to cut from the audio ({@link #LEAD_IN} or 0). */
        final int lead;
        /** Code point span of the chunk in the text, and its words ([start, end) pairs in it). */
        final int chunkStart;
        final int chunkEnd;
        final int[] words;

        Job(PiperPhonemes.Chunk chunk, long[] ids, List<Integer> wordStarts, int lead, String text,
            int[] cpToIndex) {
            this.chunk = chunk;
            this.ids = ids;
            this.wordStarts = wordStarts;
            this.lead = lead;
            chunkStart = Math.max(0, Math.min(chunk.start, cpToIndex.length - 1));
            chunkEnd = Math.max(chunkStart, Math.min(chunk.end, cpToIndex.length - 1));
            words = PiperAudio.findWords(text.substring(cpToIndex[chunkStart], cpToIndex[chunkEnd]));
        }
    }

    private static <T> T await(Future<T> future) throws OrtException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw asOrt(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (Exception ignored) {
            // Cancelled or failed: either way it no longer runs.
        }
    }

    /** code point index -> UTF-16 index, with one extra entry for the end. */
    private static int[] codePointIndex(String text) {
        final int cps = text.codePointCount(0, text.length());
        final int[] index = new int[cps + 1];
        int i = 0;
        for (int cp = 0; cp < cps; cp++) {
            index[cp] = i;
            i += Character.charCount(text.codePointAt(i));
        }
        index[cps] = text.length();
        return index;
    }
}
