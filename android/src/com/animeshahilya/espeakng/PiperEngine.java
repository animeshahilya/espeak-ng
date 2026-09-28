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
    private final ExecutorService mRenderer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "piper-render");
        t.setPriority(Thread.NORM_PRIORITY + 2);
        return t;
    });
    private final Set<String> mMissingLogged = ConcurrentHashMap.newKeySet();
    /** Offer models to NNAPI (user setting); a voice it fails for runs on the CPU. */
    private volatile boolean mAcceleration;
    /** Voices kept loaded (least recently used evicted); set per device (PiperDevice). */
    private volatile int mMaxLoaded = 2;
    /** Voices NNAPI failed for, at load or mid-speech: CPU only from then on. */
    private final Set<String> mNoAcceleration = ConcurrentHashMap.newKeySet();
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
        void onLoaded(String key, long millis);

        void onLoadFailed(String key, Throwable error);

        void onMissingPhonemes(String key, List<String> phonemes);

        /** NNAPI could not run this voice; it was loaded for the CPU instead. */
        void onAccelerationFailed(String key, Throwable error);
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
                    l.onLoaded(key, System.currentTimeMillis() - t0);
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
                model = loadModel(key, onnx, config);
                install(key, model);
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
            if (mAcceleration && !mNoAcceleration.contains(key)) {
                try {
                    return PiperModel.load(onnx, config, inferenceThreads(), true);
                } catch (Throwable t) {
                    accelerationFailed(key, t);
                }
            }
            return PiperModel.load(onnx, config, inferenceThreads(), false);
        } finally {
            exitNative(key);
        }
    }

    /**
     * Measured on a Pixel 8: NNAPI takes ~10 of ~2700 VITS nodes, runs them
     * on Android's reference CPU driver (15% slower overall), and fails the
     * run outright for the unoptimized graph - so this is an expected path.
     */
    private void accelerationFailed(String key, Throwable t) {
        mNoAcceleration.add(key);
        final Listener l = mListener;
        if (l != null) {
            l.onAccelerationFailed(key, t);
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

    /** Takes effect for voices loaded from now on: loaded ones are dropped and reload. */
    void setAcceleration(boolean enabled) {
        if (mAcceleration != enabled) {
            mAcceleration = enabled;
            mNoAcceleration.clear(); // switched on again: give every voice a new try
            trim(true);
        }
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
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (mLoaded.size() > mMaxLoaded && it.hasNext()) {
                final Map.Entry<String, PiperModel> eldest = it.next();
                if (!eldest.getKey().equals(key)) {
                    evicted.add(eldest.getValue());
                    it.remove();
                }
            }
        }
        for (PiperModel m : evicted) {
            m.close(); // waits for an in-flight inference on it
        }
    }

    void unload(String key) {
        final PiperModel model;
        synchronized (mLoaded) {
            model = mLoaded.remove(key);
        }
        mFailed.remove(key);
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

    /** Memory pressure: keep only the most recently used voice. */
    void trim(boolean all) {
        final List<PiperModel> evicted = new ArrayList<>();
        synchronized (mLoaded) {
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (it.hasNext() && mLoaded.size() > (all ? 0 : 1)) {
                evicted.add(it.next().getValue());
                it.remove();
            }
        }
        for (PiperModel m : evicted) {
            m.close();
        }
    }

    /** Cancels the synthesis in progress, if any (framework control thread). */
    void stop() {
        final PiperModel.RunHandle run = mCurrentRun;
        if (run != null) {
            run.cancel();
        }
    }

    private static final int INFERENCE_THREADS = threadsFor(cpuCapacities(),
            Runtime.getRuntime().availableProcessors());

    static int inferenceThreads() {
        return INFERENCE_THREADS;
    }

    /**
     * One intra-op thread per fast core, at most 4 (VITS stops scaling
     * there). A parallel step waits for its slowest thread, so a thread on a
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
        return Math.max(1, Math.min(4, fast));
    }

    /** The kernel's relative core performance (EAS, 1024 = fastest); empty if unavailable. */
    private static int[] cpuCapacities() {
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

    /**
     * Synthesizes one piece of text.
     *
     * @return audio frames written, or -1 if the text could not be
     *         phonemized (the caller falls back to eSpeak for it)
     */
    int synthesize(PiperModel model, String text, Phonemizer phonemizer, TimeStretcher stretcher,
                   Params params, Output out) throws OrtException {
        final PiperVoiceConfig config = model.config;
        final List<PiperPhonemes.Clause> clauses;
        if (config.usesEspeak()) {
            final String raw = phonemizer.phonemize(config.espeakVoice, text);
            if (raw == null) {
                return -1;
            }
            clauses = PiperPhonemes.alignToText(PiperPhonemes.parseRecords(raw), text);
        } else {
            clauses = PiperPhonemes.textClauses(text);
        }
        final List<PiperPhonemes.Chunk> chunks = PiperPhonemes.chunk(clauses,
                PiperPhonemes.FIRST_CHUNK_PHONEMES, PiperPhonemes.CHUNK_PHONEMES);

        final float speed = Math.max(0.1f, params.speed);
        final float modelSpeed = Math.max(MIN_MODEL_SPEED, Math.min(MAX_MODEL_SPEED, speed));
        final float residualSpeed = speed / modelSpeed;
        final float pitch = params.pitch;
        final boolean stretch = stretcher != null
                && (Math.abs(residualSpeed - 1f) > 0.01f || Math.abs(pitch - 1f) > 0.01f);
        final float lengthScale = config.lengthScale / modelSpeed;
        final int speaker = params.speakerId >= 0 ? params.speakerId : config.defaultSpeakerId;
        final int rate = config.sampleRate;

        // Ids for every chunk up front (microseconds); the model runs are
        // what the renderer overlaps with delivery.
        final List<String> missing = new ArrayList<>();
        final List<Job> jobs = new ArrayList<>(chunks.size());
        for (PiperPhonemes.Chunk chunk : chunks) {
            final List<Integer> wordStarts = new ArrayList<>();
            final long[] ids = PiperPhonemes.toIds(PiperPhonemes.tokenize(chunk.ipa, config),
                    config, missing, wordStarts);
            if (ids.length > 3) { // more than BOS PAD EOS: something to say
                jobs.add(new Job(chunk, ids, wordStarts));
            }
        }

        final PiperModel.RunHandle handle = new PiperModel.RunHandle();
        mCurrentRun = handle;
        // A stop() that came before mCurrentRun was set found nothing to
        // cancel; the caller's flag still says so (it is set before stop()).
        if (out.stopped()) {
            handle.cancel();
        }
        // Everything but delivery happens on the renderer: model run, level,
        // trim, pitch/speed stretch and PCM bytes. Measured: libsonic alone
        // took 200-500 ms per sentence on the synthesis thread while the
        // model used the fast cores - enough to drain the framework's ~0.5 s
        // of queued audio and stall playback.
        final Renderer renderer = job -> mRenderer.submit(() -> {
            final PiperModel.Output o;
            enterNative(config.key);
            try {
                o = model.infer(job.ids, lengthScale, speaker, handle);
            } finally {
                exitNative(config.key);
            }
            if (o == null) {
                return null;
            }
            final PiperAudio.Pcm pcm = PiperAudio.process(o.audio, params.volume, rate,
                    params.trimSilence);
            short[] samples = pcm.samples;
            if (stretch && samples.length > 0) {
                final short[] stretched = stretcher.process(samples, rate, residualSpeed, pitch);
                if (stretched != null) {
                    samples = stretched;
                }
            }
            return new Rendered(PiperAudio.toBytes(samples, samples.length), samples.length,
                    pcm.trimmedLead, pcm.speechSamples, o.durations);
        });
        int frames = 0;
        Future<Rendered> pending = null;
        try {
            final int[] cpToIndex = codePointIndex(text);
            if (!jobs.isEmpty()) {
                pending = renderer.render(jobs.get(0));
            }
            for (int j = 0; j < jobs.size(); j++) {
                final Rendered rendered;
                try {
                    rendered = await(pending);
                } catch (OrtException e) {
                    if (model.accelerated) {
                        // Mid-speech NNAPI failure: this request ends (the
                        // service reports it), the next loads for the CPU.
                        accelerationFailed(config.key, e);
                        mLoader.execute(() -> unload(config.key));
                    }
                    throw e;
                }
                pending = j + 1 < jobs.size() ? renderer.render(jobs.get(j + 1)) : null;
                if (rendered == null || handle.isCancelled()) {
                    break;
                }
                final Job job = jobs.get(j);

                // Word positions first, then the audio they point into (the
                // framework takes rangeStart() markers ahead of the frames).
                final int chunkStart = Math.max(0, Math.min(job.chunk.start, cpToIndex.length - 1));
                final int chunkEnd = Math.max(chunkStart, Math.min(job.chunk.end, cpToIndex.length - 1));
                final String chunkText = text.substring(cpToIndex[chunkStart], cpToIndex[chunkEnd]);
                final int[] words = PiperAudio.findWords(chunkText);
                int[] wordFrames = PiperAudio.alignedWordFrames(job.wordStarts, rendered.durations,
                        config.hopLength, words.length / 2, rendered.trimmedLead,
                        rendered.speechSamples, rendered.samples);
                if (wordFrames == null) {
                    wordFrames = PiperAudio.estimateWordFrames(words, chunkEnd - chunkStart,
                            rendered.samples);
                }
                for (int w = 0; w < wordFrames.length; w++) {
                    out.word(chunkStart + words[2 * w] + 1, words[2 * w + 1] - words[2 * w],
                            frames + wordFrames[w]);
                }

                if (!out.audio(rendered.pcm)) {
                    break;
                }
                frames += rendered.samples;

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
            if (pending != null) {
                // Stopped or failed with the next chunk in flight: end it
                // before its RunOptions are closed below.
                handle.cancel();
                awaitQuietly(pending);
            }
            if (mCurrentRun == handle) {
                mCurrentRun = null;
            }
            handle.close();
        }
        if (!missing.isEmpty() && mMissingLogged.add(config.key)) {
            final Listener l = mListener;
            if (l != null) {
                l.onMissingPhonemes(config.key, missing);
            }
        }
        return frames;
    }

    private interface Renderer {
        Future<Rendered> render(Job job);
    }

    /** A chunk ready to deliver: 16-bit PCM plus what word timing needs. */
    private static final class Rendered {
        final byte[] pcm;
        final int samples;
        final int trimmedLead;
        final int speechSamples;
        final float[] durations;

        Rendered(byte[] pcm, int samples, int trimmedLead, int speechSamples, float[] durations) {
            this.pcm = pcm;
            this.samples = samples;
            this.trimmedLead = trimmedLead;
            this.speechSamples = speechSamples;
            this.durations = durations;
        }
    }

    /** One chunk's model input, and where its words start in it. */
    private static final class Job {
        final PiperPhonemes.Chunk chunk;
        final long[] ids;
        final List<Integer> wordStarts;

        Job(PiperPhonemes.Chunk chunk, long[] ids, List<Integer> wordStarts) {
            this.chunk = chunk;
            this.ids = ids;
            this.wordStarts = wordStarts;
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
