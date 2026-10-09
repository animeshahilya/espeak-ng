/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

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
final class PiperEngine {
    /** eSpeak's default rate (wpm); Piper at length_scale 1 speaks about this fast. */
    static final int NORMAL_RATE = 175;
    /**
     * Fastest speed asked of the model itself. VITS stays clean to about
     * 1.8x shorter phoneme durations, then starts dropping phonemes; the
     * rest of a high screen-reader rate is done by time-stretching the audio
     * (libsonic, the same library eSpeak uses above 450 wpm).
     */
    static final float MAX_MODEL_SPEED = 1.8f;
    static final float MIN_MODEL_SPEED = 0.5f;

    /** This fork's eSpeak NG as Piper's phonemizer (piperPhonemizer.c records). */
    interface Phonemizer {
        String phonemize(String espeakVoice, String text);
    }

    /** Speed and pitch change without re-synthesis; null result = unchanged. */
    interface TimeStretcher {
        short[] process(short[] samples, int sampleRate, float speed, float pitch);
    }

    /** Where audio and word positions go (TtsService's synthesis callback). */
    interface Output {
        /** @param position 1-based code point index in the synthesized text */
        void word(int position, int length, int frame);

        /** @return false once the caller no longer wants audio (stopped) */
        boolean audio(byte[] pcm);

        /** True once the request was stopped, even before any audio. */
        default boolean stopped() {
            return false;
        }
    }

    static final class Params {
        /** 1 = normal; rate / {@link #NORMAL_RATE}. */
        float speed = 1f;
        /** 1 = voice's own pitch. */
        float pitch = 1f;
        /** Linear gain after peak normalization. */
        float volume = 1f;
        /** Reading pace, percent of normal pause lengths. */
        int pauseScale = 100;
        boolean trimSilence = true;
        int speakerId = -1;
        /** Multipliers of the voice's noise_scale and noise_w (speaking style). */
        float noiseScale = 1f;
        float noiseW = 1f;

        Params copy() {
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

    static PiperEngine get() {
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
     * up to 3 s, kept from their first sighting (PiperPhraseCache).
     */
    private final PiperPhraseCache mCache = new PiperPhraseCache(8L * 1024 * 1024, 3 * 24000, 1, 1024);
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
    void setPhraseCacheDir(java.io.File dir) {
        mCache.setDisk(dir, 32L * 1024 * 1024, 2);
    }

    /** A deleted voice's cached phrases go with it, from memory and storage. */
    void deleteCachedPhrases(String key) {
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
    interface NativeGuard {
        void enter(String key);

        void exit(String key);
    }

    void setNativeGuard(NativeGuard guard) {
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
    interface Listener {
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

    void setListener(Listener listener) {
        mListener = listener;
    }

    /** The model if it is in memory right now; never blocks on a load. */
    PiperModel getLoaded(String key) {
        synchronized (mLoaded) {
            final PiperModel model = mLoaded.get(key);
            return model != null && !model.isClosed() ? model : null;
        }
    }

    boolean isLoading(String key) {
        return mLoading.contains(key);
    }

    /**
     * Starts loading a voice in the background unless it is loaded, already
     * loading, or failed within the last minute. The caller speaks with
     * eSpeak meanwhile: a screen reader must never go silent waiting.
     */
    void preload(final String key, final File onnx, final PiperVoiceConfig config) {
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
                    l.onLoaded(key, System.currentTimeMillis() - t0, loaded == null ? null
                            : loaded.onNpu() ? "on the NPU (" + loaded.npuKind() + ")" : loaded.npuProblem());
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
     * Loads and waits (the settings screen's test). On the loader thread like
     * preload(), so two loads of one voice never build its optimized copy at
     * the same time.
     */
    PiperModel loadNow(String key, File onnx, PiperVoiceConfig config) throws OrtException {
        final Future<PiperModel> load = mLoader.submit(() -> {
            PiperModel model = getLoaded(key);
            if (model == null) {
                final long t0 = System.currentTimeMillis();
                model = loadModel(key, onnx, config);
                install(key, model);
                final Listener l = mListener;
                if (l != null) { // the settings screen's Test voice is logged too
                    l.onLoaded(key, System.currentTimeMillis() - t0, model.onNpu()
                            ? "on the NPU (" + model.npuKind() + ")" : model.npuProblem());
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
                        final PiperModel cloned = existing.withConfig(config);
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

    void setMaxLoaded(int voices) {
        mMaxLoaded = Math.max(1, voices);
    }

    /**
     * Whether a second voice can stay loaded next to the current one.
     * Language switching inside a request needs that; on a phone that keeps
     * one voice, loading the other would evict the first and both would
     * reload on every switch.
     */
    boolean keepsSeveralLoaded() {
        return mMaxLoaded >= 2;
    }

    int maxLoaded() {
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
                if (!eldest.getKey().equals(key) && !eldest.getValue().sharesSessionWith(model)) {
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

    void unload(String key) {
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
     * Natural voices switched off: frees every loaded model. On the loader
     * thread - closing waits for an inference in flight, and the caller is
     * the main thread - and queued after any load already started.
     */
    void unloadAll() {
        mLoader.execute(() -> trim(true));
    }

    /**
     * Memory pressure: keep only the most recently used voice (and voices on
     * its shared session, which cost nothing more).
     */
    void trim(boolean all) {
        final List<PiperModel> evicted = new ArrayList<>();
        synchronized (mLoaded) {
            PiperModel newest = null;
            for (PiperModel m : mLoaded.values()) {
                newest = m; // access order: the last is the most recent
            }
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (it.hasNext()) {
                final PiperModel m = it.next().getValue();
                if (all || (m != newest && !m.sharesSessionWith(newest))) {
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
    void stop() {
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

    /** True when every core is a fast one (Snapdragon 8 Elite), false if unknown. */
    static boolean noLittleCores() {
        final int[] capacities = cpuCapacities();
        if (capacities.length == 0) {
            return false;
        }
        int top = 0;
        for (int c : capacities) {
            top = Math.max(top, c);
        }
        for (int c : capacities) {
            if (c * 2 < top) {
                return false;
            }
        }
        return true;
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

    /**
     * "Text" voices read letters, and their alphabets have no digits: numbers
     * become words in the voice's language, where ICU has spell-out rules
     * for it (Hindi, Bengali, Tamil...). Otherwise digits stay and are skipped.
     */
    static java.util.function.UnaryOperator<String> numberWords(String language) {
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

    private static java.util.function.UnaryOperator<String> buildNumberWords(String language) {
        final android.icu.text.MessageFormat format;
        try {
            format = new android.icu.text.MessageFormat("{0,spellout}",
                    new android.icu.util.ULocale(language));
            // Without rules for the language ICU falls back to English words (or digits).
            final String seven = format.format(new Object[] {7L});
            if (seven.matches(".*\\d.*") || (!"en".equals(language) && seven.matches("(?i).*seven.*"))) {
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
                        synchronized (format) { // MessageFormat is not thread-safe
                            words = format.format(new Object[] {Long.parseLong(toAscii(digits))});
                        }
                    }
                } catch (RuntimeException e) {
                    words = m.group();
                }
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(" " + words + " "));
            } while (m.find());
            m.appendTail(out);
            return out.toString().replaceAll("\\s+", " ").trim();
        };
    }

    /** "१२३" -> "123": Long.parseLong reads only ASCII digits. */
    private static String toAscii(String digits) {
        if (digits == null) {
            return "";
        }
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
    static final class Prepared {
        final PiperModel model;
        final Pass pass;
        final List<Job> jobs;
        final List<String> missing;
        final PiperModel.RunHandle handle;
        final Params params;
        final float speed;
        final Future<Rendered> first;

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
    Prepared prepare(PiperModel model, String text, Phonemizer phonemizer, TimeStretcher stretcher,
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
        } else {
            clauses = PiperPhonemes.textClauses(text, numberWords(config.languageFamily), config.shortChunks);
        }
        final List<PiperPhonemes.Chunk> chunks = PiperPhonemes.chunk(clauses,
                PiperPhonemes.FIRST_CHUNK_PHONEMES,
                config.shortChunks ? PiperPhonemes.SHORT_CHUNK : PiperPhonemes.CHUNK_PHONEMES, config.finalStop);

        final float speed = shortInputSpeed(text, config, Math.max(0.1f, params.speed));
        final float modelSpeed = Math.max(MIN_MODEL_SPEED, Math.min(MAX_MODEL_SPEED, speed));
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
            final long[] ids = PiperPhonemes.toIds(PiperPhonemes.tokenize(chunk.ipa, config),
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
    void discard(Prepared p) {
        if (p == null) {
            return;
        }
        p.handle.cancel();
        if (p.first != null) {
            try {
                final Rendered r = p.first.get();
                if (r != null && r.rest != null) {
                    awaitQuietly(r.rest);
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
    int play(Prepared p, Output out, Runnable lastRendered) throws OrtException {
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
        // A stop() that came before mCurrentRun was set found nothing to
        // cancel; the caller's flag still says so (it is set before stop()).
        if (out.stopped()) {
            handle.cancel();
        }
        int frames = 0;
        Future<Rendered> pending = p.first;
        Future<Rendered> pendingRest = null;
        boolean announced = false;
        try {
            for (int j = 0; j < jobs.size(); j++) {
                Rendered rendered;
                rendered = await(pending);
                // The first chunk's second piece was queued ahead of this.
                pendingRest = rendered != null ? rendered.rest : null;
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
                    pendingRest = null;
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
            final String key = model.config.key;
            final int words = job.words.length / 2;
            // A chunk said before with these settings: its model run, re-processed.
            final PiperPhraseCache.Key cacheKey = new PiperPhraseCache.Key(voiceStamp(model), job.ids,
                    speaker, lengthScale, noise, noiseW);
            final PiperPhraseCache.Entry cached = mCache.get(cacheKey);
            if (cached != null) {
                final Rendered r = finish(cached.audio, cached.durations, job.wordStarts, 0, words,
                        true, true, 0, trebleCut());
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
                final Rendered r = finish(audio, o.durations, job.wordStarts, 0, words, true, true, 0,
                        trebleCut());
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
            final int cut = e.durations == null ? -1 : firstPieceEnd(job.wordStarts, e.durations,
                    e.frames - lead, speed, (int) Math.ceil(MIN_PIECE_S * rate / hop));
            if (cut < 0) {
                final float[] audio = decode(e, lead, e.frames);
                if (audio == null) {
                    return null;
                }
                mCache.offer(cacheKey, audio, e.durations);
                final Rendered r = finish(audio, e.durations, job.wordStarts, 0, words, true, true, 0,
                        trebleCut());
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
                    job.wordStarts.subList(0, cut), 0, cut, true, false, 0, treble);
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
                return finish(tail, Arrays.copyOfRange(e.durations, splitId, e.durations.length),
                        restStarts, cut, words, false, true, PiperAudio.speechPower(whole, rate), treble);
            });
            return first;
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

        /** Treble cut, level, trim, pause cap, stretch and PCM bytes for one piece of audio. */
        private Rendered finish(float[] audio, float[] durations, List<Integer> wordStarts,
                                int wordFrom, int wordTo, boolean lead, boolean tail, double level,
                                PiperAudio.TrebleCut treble) {
            final int rate = model.config.sampleRate;
            if (treble != null) {
                audio = treble.apply(audio);
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
