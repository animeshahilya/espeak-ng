/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.io.Closeable;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * One loaded Piper (VITS) model: an ONNX Runtime session plus its config.
 *
 * <p>Inference runs under a read lock and {@link #close()} takes the write
 * lock, so the model cache can evict a voice while another thread is still
 * mid-sentence with it without freeing the session under that thread.
 */
final class PiperModel implements Closeable {
    final PiperVoiceConfig config;
    final File file;
    private final OrtSession session;
    private final boolean hasSpeakerInput;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    private PiperModel(PiperVoiceConfig config, File file, OrtSession session) {
        this.config = config;
        this.file = file;
        this.session = session;
        this.hasSpeakerInput = session.getInputNames().contains("sid");
    }

    /**
     * Loads a model. Seconds of work for a medium voice - never on a thread
     * a screen reader is waiting on.
     *
     * @param threads intra-op threads; VITS decoders scale to about 4
     */
    static PiperModel load(File onnx, PiperVoiceConfig config, int threads) throws OrtException {
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setIntraOpNumThreads(Math.max(1, threads));
            options.setInterOpNumThreads(1);
            // One utterance at a time, of varying length: the memory
            // pattern planner only helps fixed-shape batch inference.
            options.setMemoryPatternOptimization(false);
            final OrtSession session = env.createSession(onnx.getAbsolutePath(), options);
            return new PiperModel(config, onnx, session);
        }
    }

    /** Stops a run in progress from another thread; see {@link #infer}. */
    static final class RunHandle {
        private final OrtSession.RunOptions options;
        private volatile boolean cancelled;

        RunHandle() throws OrtException {
            options = new OrtSession.RunOptions();
        }

        void cancel() {
            cancelled = true;
            try {
                options.setTerminate(true);
            } catch (OrtException ignored) {
                // Already finished or closed: nothing left to stop.
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        void close() {
            options.close();
        }
    }

    /**
     * Phoneme ids to audio (floats in about [-1, 1], at config.sampleRate).
     *
     * @return null when the model was closed or the run cancelled
     */
    float[] infer(long[] ids, float lengthScale, int speakerId, RunHandle handle) throws OrtException {
        lock.readLock().lock();
        try {
            if (closed || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtEnvironment env = OrtEnvironment.getEnvironment();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                inputs.put("input", OnnxTensor.createTensor(env, LongBuffer.wrap(ids),
                        new long[] {1, ids.length}));
                inputs.put("input_lengths", OnnxTensor.createTensor(env,
                        LongBuffer.wrap(new long[] {ids.length}), new long[] {1}));
                inputs.put("scales", OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {
                        config.noiseScale, lengthScale, config.noiseW}), new long[] {3}));
                if (hasSpeakerInput) {
                    final int sid = config.numSpeakers > 1 ? speakerId : 0;
                    inputs.put("sid", OnnxTensor.createTensor(env,
                            LongBuffer.wrap(new long[] {sid}), new long[] {1}));
                }
                final OrtSession.Result result = handle != null
                        ? session.run(inputs, handle.options)
                        : session.run(inputs);
                try (OrtSession.Result r = result) {
                    final OnnxValue out = r.get(0);
                    if (!(out instanceof OnnxTensor)) {
                        return null;
                    }
                    final FloatBuffer buffer = ((OnnxTensor) out).getFloatBuffer();
                    final float[] audio = new float[buffer.remaining()];
                    buffer.get(audio);
                    return audio;
                }
            } catch (OrtException e) {
                if (handle != null && handle.isCancelled()) {
                    return null; // RunOptions.setTerminate() surfaces as an exception
                }
                throw e;
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean isClosed() {
        lock.readLock().lock();
        try {
            return closed;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            try {
                session.close();
            } catch (OrtException ignored) {
                // Nothing more to release.
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
