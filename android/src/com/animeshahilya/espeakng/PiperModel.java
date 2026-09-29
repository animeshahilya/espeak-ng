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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
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
 *
 * <p>Loading, measured on a Pixel 8 (see PiperBenchDeviceTest):
 * <ul>
 * <li>The first load saves an optimized copy of the model next to it, in
 *     ONNX Runtime's own format: graph optimizations done once instead of at
 *     every load, with Piper's duration output exposed
 *     ({@link PiperAlignment}). Loads read that copy: 2.1 -> 1.1 s for a low
 *     voice, 3.5 -> 1.3 s for a medium one. Any problem with it falls back to
 *     the original file.
 * <li>The copy is memory-mapped and its weights used in place, so a voice's
 *     ~63 MB of weights are file pages the kernel can drop and re-read under
 *     memory pressure, not private memory it must keep: native memory per
 *     voice drops from ~80 to ~15 MB. Throughput is 20-40% lower (no weight
 *     pre-packing) but still 5-10x faster than real time, which PiperEngine's
 *     rendering ahead makes inaudible; time to first sound is the same.
 * <li>A tiny warm-up run right after loading, so the first real phrase
 *     doesn't pay ONNX Runtime's first-run setup (100-170 ms).
 * <li>Each run hands its scratch memory back to the system. ONNX Runtime's
 *     arena otherwise keeps its peak, ~160 MB per voice, for the life of the
 *     process - and a screen reader's TTS service is exactly the process
 *     Android must not kill for memory. Time to first sound is unchanged.
 * </ul>
 */
final class PiperModel implements Closeable {
    private static final String OPTIMIZED_PREFIX = "model.";
    private static final String OPTIMIZED_SUFFIX = ".opt.ort";

    final PiperVoiceConfig config;
    final File file;
    /** True when running through NNAPI (see PiperEngine#setAcceleration). */
    final boolean accelerated;
    private final OrtSession session;
    /** The mapped optimized model the session reads its weights from; lives as long. */
    private final ByteBuffer mapped;
    private final boolean hasSpeakerInput;
    private final boolean hasDurations;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    /** One model run: audio in about [-1, 1], and frames per phoneme id if known. */
    static final class Output {
        final float[] audio;
        /** Frames per phoneme id (x config.hopLength = samples), or null. */
        final float[] durations;

        Output(float[] audio, float[] durations) {
            this.audio = audio;
            this.durations = durations;
        }
    }

    private PiperModel(PiperVoiceConfig config, File file, OrtSession session, ByteBuffer mapped,
                       boolean accelerated) {
        this.config = config;
        this.file = file;
        this.session = session;
        this.mapped = mapped;
        this.accelerated = accelerated;
        this.hasSpeakerInput = session.getInputNames().contains("sid");
        this.hasDurations = session.getOutputNames().size() > 1;
    }

    /**
     * Loads a model. Seconds of work - never on a thread a screen reader is
     * waiting on.
     *
     * @param threads intra-op threads; VITS decoders scale to about 4
     * @param nnapi   also offer the graph to NNAPI (throws if that fails,
     *                including on the warm-up run; the caller retries without)
     */
    static PiperModel load(File onnx, PiperVoiceConfig config, int threads, boolean nnapi)
            throws OrtException {
        final File optimized = optimizedFile(onnx);
        if (!optimized.isFile()) {
            buildOptimized(onnx, optimized, threads);
        }
        OrtSession session = null;
        ByteBuffer mapped = null;
        if (optimized.isFile()) {
            try {
                mapped = map(optimized);
                session = open(null, mapped, threads, nnapi);
            } catch (IOException | OrtException e) {
                mapped = null;
                optimized.delete(); // stale or damaged: rebuilt at the next load
            }
        }
        if (session == null) {
            session = open(onnx, null, threads, nnapi);
        }
        final PiperModel model = new PiperModel(config, onnx, session, mapped, nnapi);
        try {
            model.warmUp();
        } catch (OrtException | RuntimeException e) {
            model.close();
            throw e;
        }
        return model;
    }

    private static volatile boolean sTelemetryOff;

    /**
     * The process's ONNX Runtime environment, with the runtime's own
     * telemetry off: speech never touches the network (its startup
     * provider is removed in AndroidManifest.xml for the same reason).
     */
    private static OrtEnvironment env() {
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        if (!sTelemetryOff) {
            try {
                env.setTelemetry(false);
            } catch (OrtException | RuntimeException ignored) {
                // Builds without telemetry support: nothing to turn off.
            }
            sTelemetryOff = true;
        }
        return env;
    }

    /** model.onnx -> model.<ONNX Runtime version>.opt.ort: a runtime update re-optimizes. */
    static File optimizedFile(File onnx) {
        final String version = env().getVersion();
        return new File(onnx.getParentFile(), OPTIMIZED_PREFIX + version + OPTIMIZED_SUFFIX);
    }

    /** Writes the optimized, alignment-enabled copy; on any failure there is simply none. */
    private static void buildOptimized(File onnx, File optimized, int threads) {
        final File dir = onnx.getParentFile();
        final File[] stale = dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && (n.contains(".opt.") || n.endsWith(".tmp")));
        if (stale != null) {
            for (File f : stale) {
                f.delete();
            }
        }
        final File aligned = new File(dir, OPTIMIZED_PREFIX + "aligned.tmp");
        final File partial = new File(dir, optimized.getName() + ".tmp");
        try (OrtSession.SessionOptions options = options(threads, false)) {
            final File source = PiperAlignment.addDurationOutput(onnx, aligned) ? aligned : onnx;
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setOptimizedModelFilePath(partial.getAbsolutePath());
            options.addConfigEntry("session.save_model_format", "ORT");
            env().createSession(source.getAbsolutePath(), options).close();
            if (!partial.renameTo(optimized)) {
                partial.delete();
            }
        } catch (IOException | OrtException | RuntimeException e) {
            partial.delete();
        } finally {
            aligned.delete();
        }
    }

    private static ByteBuffer map(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
        }
    }

    /** A session over the original .onnx ({@code model}) or the mapped optimized copy. */
    private static OrtSession open(File model, ByteBuffer mapped, int threads, boolean nnapi)
            throws OrtException {
        try (OrtSession.SessionOptions options = options(threads, nnapi)) {
            if (mapped != null) {
                // Already optimized; weights read straight from the mapping.
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                options.addConfigEntry("session.use_ort_model_bytes_directly", "1");
                options.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
                return env().createSession(mapped, options);
            }
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return env().createSession(model.getAbsolutePath(), options);
        }
    }

    private static OrtSession.SessionOptions options(int threads, boolean nnapi) throws OrtException {
        final OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setInterOpNumThreads(1);
        // One utterance at a time, of varying length: the memory
        // pattern planner only helps fixed-shape batch inference.
        options.setMemoryPatternOptimization(false);
        if (nnapi) {
            options.addNnapi();
        }
        return options;
    }

    /** A few phonemes through the model: first-run setup off the speech path, and a real test. */
    private void warmUp() throws OrtException {
        final int[] pad = config.phonemeIdMap.get(PiperVoiceConfig.PAD);
        final int[] bos = config.phonemeIdMap.get(PiperVoiceConfig.BOS);
        final int[] eos = config.phonemeIdMap.get(PiperVoiceConfig.EOS);
        int[] vowel = config.phonemeIdMap.get("a");
        if (vowel == null) {
            vowel = config.phonemeIdMap.get("ə");
        }
        if (pad == null || bos == null || eos == null || vowel == null) {
            return;
        }
        final long[] ids = {bos[0], pad[0], vowel[0], pad[0], vowel[0], pad[0], eos[0]};
        infer(ids, config.lengthScale, config.defaultSpeakerId, 1f, 1f, null);
    }

    /**
     * Stops a run in progress from another thread; see {@link #infer}.
     * cancel() (the framework's stop, on every screen-reader swipe) races
     * the synthesis thread's close() when a request ends: terminating closed
     * RunOptions throws IllegalStateException, and in the window inside
     * close() it would touch freed native memory - either one killed the
     * whole TTS service. Both are synchronized and cancel() skips a closed run.
     */
    static final class RunHandle {
        private final OrtSession.RunOptions options;
        private volatile boolean cancelled;
        private boolean closed;

        RunHandle() throws OrtException {
            options = runOptions();
        }

        synchronized void cancel() {
            cancelled = true;
            if (closed) {
                return;
            }
            try {
                options.setTerminate(true);
            } catch (OrtException ignored) {
                // Already finished: nothing left to stop.
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        synchronized void close() {
            if (!closed) {
                closed = true;
                options.close();
            }
        }
    }

    private static OrtSession.RunOptions runOptions() throws OrtException {
        final OrtSession.RunOptions options = new OrtSession.RunOptions();
        options.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0");
        return options;
    }

    /**
     * Phoneme ids to audio (at config.sampleRate).
     *
     * @param noise  multiplier of the voice's noise_scale (speaking style)
     * @param noiseW multiplier of the voice's noise_w (rhythm variation)
     * @return null when the model was closed or the run cancelled
     */
    Output infer(long[] ids, float lengthScale, int speakerId, float noise, float noiseW,
                 RunHandle handle) throws OrtException {
        lock.readLock().lock();
        try {
            if (closed || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtEnvironment env = env();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                inputs.put("input", OnnxTensor.createTensor(env, LongBuffer.wrap(ids),
                        new long[] {1, ids.length}));
                inputs.put("input_lengths", OnnxTensor.createTensor(env,
                        LongBuffer.wrap(new long[] {ids.length}), new long[] {1}));
                inputs.put("scales", OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {
                        config.noiseScale * noise, lengthScale, config.noiseW * noiseW}), new long[] {3}));
                if (hasSpeakerInput) {
                    final int sid = config.numSpeakers > 1 ? speakerId : 0;
                    inputs.put("sid", OnnxTensor.createTensor(env,
                            LongBuffer.wrap(new long[] {sid}), new long[] {1}));
                }
                try (OrtSession.Result r = session.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    final float[] durations = hasDurations ? floats(r.get(1)) : null;
                    return new Output(audio, durations != null && durations.length == ids.length
                            ? durations : null);
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
                if (ownOptions != null) {
                    ownOptions.close();
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    private static float[] floats(OnnxValue value) {
        if (!(value instanceof OnnxTensor)) {
            return null;
        }
        final FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
        if (buffer == null) {
            return null;
        }
        final float[] out = new float[buffer.remaining()];
        buffer.get(out);
        return out;
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
