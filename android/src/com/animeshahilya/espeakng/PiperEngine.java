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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.onnxruntime.OrtException;

/**
 * Piper speech for the TTS service: which models are in memory, and how one
 * piece of text becomes audio with them.
 *
 * <p>Text -> this fork's eSpeak NG (phonemes, via {@link Phonemizer}) ->
 * Piper phoneme ids -> ONNX Runtime -> PCM, one chunk at a time so audio
 * starts after the first clause instead of after the whole utterance, and
 * so a stop lands between chunks (or aborts the run in progress).
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
    /** Voices kept loaded: enough for a two-language reader, bounded memory. */
    static final int MAX_LOADED = 2;

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
    private final Set<String> mMissingLogged = ConcurrentHashMap.newKeySet();
    private volatile PiperModel.RunHandle mCurrentRun;
    private volatile Listener mListener;

    /** Load/failure notifications, for logging and the settings screen. */
    interface Listener {
        void onLoaded(String key, long millis);

        void onLoadFailed(String key, Throwable error);

        void onMissingPhonemes(String key, List<String> phonemes);
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
                install(key, PiperModel.load(onnx, config, inferenceThreads()));
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

    /** Loads synchronously (tests, and the settings screen's preview). */
    PiperModel loadNow(String key, File onnx, PiperVoiceConfig config) throws OrtException {
        PiperModel model = getLoaded(key);
        if (model != null) {
            return model;
        }
        model = PiperModel.load(onnx, config, inferenceThreads());
        install(key, model);
        return model;
    }

    /** Makes a freshly loaded model current, evicting beyond {@link #MAX_LOADED}. */
    private void install(String key, PiperModel model) {
        final List<PiperModel> evicted = new ArrayList<>();
        synchronized (mLoaded) {
            final PiperModel old = mLoaded.put(key, model);
            if (old != null && old != model) {
                evicted.add(old);
            }
            final Iterator<Map.Entry<String, PiperModel>> it = mLoaded.entrySet().iterator();
            while (mLoaded.size() > MAX_LOADED && it.hasNext()) {
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

    static int inferenceThreads() {
        final int cores = Runtime.getRuntime().availableProcessors();
        // Big cores only, roughly: on 8-core big.LITTLE phones 4 threads beat
        // 8, which drags inference onto the little cores.
        return Math.max(1, Math.min(4, cores / 2));
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

        final PiperModel.RunHandle handle = new PiperModel.RunHandle();
        mCurrentRun = handle;
        int frames = 0;
        final List<String> missing = new ArrayList<>();
        try {
            final int[] cpToIndex = codePointIndex(text);
            for (int c = 0; c < chunks.size(); c++) {
                if (handle.isCancelled()) {
                    break;
                }
                final PiperPhonemes.Chunk chunk = chunks.get(c);
                final long[] ids = PiperPhonemes.toIds(
                        PiperPhonemes.tokenize(chunk.ipa, config), config, missing);
                if (ids.length <= 3) {
                    continue; // BOS PAD EOS: nothing speakable in this chunk
                }
                final float[] audio = model.infer(ids, lengthScale, speaker, handle);
                if (audio == null || handle.isCancelled()) {
                    break;
                }
                final PiperAudio.Pcm pcm = PiperAudio.process(audio, params.volume, rate,
                        params.trimSilence);
                short[] samples = pcm.samples;
                if (stretch && samples.length > 0) {
                    final short[] stretched = stretcher.process(samples, rate, residualSpeed, pitch);
                    if (stretched != null) {
                        samples = stretched;
                    }
                }

                // Word positions first, then the audio they point into (the
                // framework takes rangeStart() markers ahead of the frames).
                final int chunkStart = Math.max(0, Math.min(chunk.start, cpToIndex.length - 1));
                final int chunkEnd = Math.max(chunkStart, Math.min(chunk.end, cpToIndex.length - 1));
                final String chunkText = text.substring(cpToIndex[chunkStart], cpToIndex[chunkEnd]);
                final int[] words = PiperAudio.findWords(chunkText);
                final int[] wordFrames = PiperAudio.estimateWordFrames(words,
                        chunkEnd - chunkStart, samples.length);
                for (int w = 0; w < wordFrames.length; w++) {
                    out.word(chunkStart + words[2 * w] + 1, words[2 * w + 1] - words[2 * w],
                            frames + wordFrames[w]);
                }

                if (!out.audio(PiperAudio.toBytes(samples, samples.length))) {
                    handle.cancel();
                    break;
                }
                frames += samples.length;

                final boolean last = c == chunks.size() - 1;
                if (!last) {
                    // No pause after the last chunk: trailing silence would
                    // only delay the screen reader's next utterance.
                    final int pause = PiperAudio.pauseSamples(rate, chunk.endsSentence,
                            params.pauseScale, speed);
                    if (pause > 0) {
                        if (!out.audio(new byte[pause * 2])) {
                            handle.cancel();
                            break;
                        }
                        frames += pause;
                    }
                }
            }
        } finally {
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
