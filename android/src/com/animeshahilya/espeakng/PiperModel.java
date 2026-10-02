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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
 * <li>The optimized copy is two models ({@link PiperSplit}): encoder and
 *     decoder, so the decoder can run over pieces of a chunk and speech
 *     start after the first piece ({@link #encode}, {@link #decode}). Same
 *     weights, same audio; a voice that can't be split keeps one model.
 * </ul>
 */
final class PiperModel implements Closeable {
    private static final String OPTIMIZED_PREFIX = "model.";
    /** One whole model, for a voice that can't be split (".opt.ort" before the split: rebuilt). */
    private static final String OPTIMIZED_SUFFIX = ".whole.ort";
    private static final String ENCODER_SUFFIX = ".enc.ort";
    private static final String DECODER_SUFFIX = ".dec.ort";
    /**
     * Latent frames of context decoded on each side of a piece and cropped:
     * enough for the decoder's receptive field. Measured (Priyamvada, Daniela
     * high, VCTK): 12 joins exactly, 8 leaves -26 to -69 dB of error, the 3
     * of Sonata's RT voices -13 to -33 dB.
     */
    static final int DECODE_OVERLAP = 12;
    /**
     * Latent frames per Qualcomm NPU run: its graphs have fixed shapes, so
     * the decoder runs in windows of this size (DECODE_OVERLAP of context
     * each side). Galaxy S25 Ultra, SYSPIN/Rasa decoders: ~22x real time at
     * any of 40-160 frames, vs ~4x on its CPU.
     */
    static final int NPU_FRAMES = 80;
    /**
     * A voice's NPU decoder, downloaded beside its model by the Snapdragon
     * build (PiperDownloads.fetchNpuDecoders): fully INT8 (sherpa-onnx-respin-
     * syspin's build_npu.py), fixed window, the Standard decoder's input
     * names. S25 Ultra: 24.5x real time vs 12.4x for the FP16 graph, same
     * accuracy. Used when it matches this model's split; else the FP16 path.
     */
    static final String NPU_DECODER_FILE = "npu.onnx";
    /**
     * An NPU is used only if it decodes at least this many seconds of audio
     * per second: measured, not assumed, since Snapdragons differ widely
     * (only the 8 Elite was measured: ~22-24x). Below it the CPU decoder stays.
     */
    static final double MIN_NPU_SPEED = 4.0;

    /**
     * Times one more window through a just-attached NPU graph; throws when it
     * is slower than MIN_NPU_SPEED (the caller then keeps the CPU decoder).
     */
    private void checkNpuSpeed(OrtSession npu, Map<String, OnnxTensor> inputs, int frames)
            throws OrtException, IOException {
        final long t0 = System.nanoTime();
        final int samples;
        try (OrtSession.Result r = npu.run(inputs)) {
            final float[] audio = floats(r.get(0));
            samples = audio == null ? 0 : audio.length;
        }
        final double seconds = (System.nanoTime() - t0) / 1e9;
        final double speed = samples / (double) config.sampleRate / Math.max(1e-6, seconds);
        if (speed < MIN_NPU_SPEED) {
            throw new IOException(String.format(java.util.Locale.ROOT,
                    "NPU too slow here: %.1fx real time over %d frames", speed, frames));
        }
    }
    private static final String NPU_SUFFIX = ".npu" + NPU_FRAMES + ".onnx";
    /** Holds why the NPU failed; the revision makes phones retry after NPU code changes. */
    private static final String NPU_FAILED_SUFFIX = ".npu-r2.failed";

    final PiperVoiceConfig config;
    final File file;
    /** True when running through NNAPI (see PiperEngine#setAcceleration). */
    final boolean accelerated;
    /** The whole model, or the encoder when {@link #decoder} is set. */
    private final OrtSession session;
    private final OrtSession decoder;
    /** The mapped optimized model the session reads its weights from; lives as long. */
    private final ByteBuffer mapped;
    /** Same for the decoder (proguard-rules.pro keeps both: never read in Java). */
    private final ByteBuffer mappedDecoder;
    /** Encoder outputs that are decoder inputs (latent first); the next one, if any, is durations. */
    private final String[] boundary;
    private final boolean hasSpeakerInput;
    private final boolean hasDurations;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;
    /** The decoder compiled for Qualcomm's NPU (Snapdragon build), or null for the CPU one. */
    private volatile OrtSession npuDecoder;
    /** Why the NPU is not used on a build that has it (for the log), or null. */
    private volatile String npuProblem;
    /** Latent frames per NPU run: the INT8 file's window, or NPU_FRAMES for the FP16 graph. */
    private volatile int npuFrames = NPU_FRAMES;
    /** "INT8" or "FP16": which NPU graph runs (for the log). */
    private volatile String npuKind;
    /** Shapes of the decoder inputs from the first encoder run (warm-up), for the NPU graph. */
    private volatile long[][] boundaryShapes;
    /** Decoder throughput, audio seconds per second of work (running average; 0 = unmeasured). */
    private volatile double decodeSpeed;

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

    /** A chunk through the encoder: the latent (1 x channels x frames) and what else the decoder reads. */
    static final class Encoded {
        final float[][] tensors;
        final long[][] shapes;
        final int channels;
        final int frames;
        /** Frames per phoneme id, or null. */
        final float[] durations;

        Encoded(float[][] tensors, long[][] shapes, float[] durations) {
            this.tensors = tensors;
            this.shapes = shapes;
            this.channels = (int) shapes[0][1];
            this.frames = (int) shapes[0][2];
            this.durations = durations;
        }
    }

    private PiperModel(PiperVoiceConfig config, File file, OrtSession session, ByteBuffer mapped,
                       OrtSession decoder, ByteBuffer mappedDecoder, boolean accelerated) {
        this.config = config;
        this.file = file;
        this.session = session;
        this.mapped = mapped;
        this.decoder = decoder;
        this.mappedDecoder = mappedDecoder;
        this.accelerated = accelerated;
        this.hasSpeakerInput = session.getInputNames().contains("sid");
        this.boundary = decoder != null ? decoder.getInputNames().toArray(new String[0]) : new String[0];
        this.hasDurations = session.getOutputNames().size() > (decoder != null ? boundary.length : 1);
    }

    /** True when runs report each id's frames (see {@link PiperAlignment}). */
    boolean hasDurations() {
        return hasDurations;
    }

    /** True when {@link #encode}/{@link #decode} can render a chunk in pieces. */
    boolean streams() {
        return decoder != null;
    }

    /** How fast {@link #decode} has run on this device: audio seconds per second, 0 if never. */
    double decodeSpeed() {
        return decodeSpeed;
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
        final File optimized = derivedFile(onnx, OPTIMIZED_SUFFIX);
        final File encoder = derivedFile(onnx, ENCODER_SUFFIX);
        final File decoderFile = derivedFile(onnx, DECODER_SUFFIX);
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile())) {
            buildOptimized(onnx, optimized, encoder, decoderFile, threads);
        }
        OrtSession session = null;
        ByteBuffer mapped = null;
        OrtSession decoder = null;
        ByteBuffer mappedDecoder = null;
        if (encoder.isFile() && decoderFile.isFile()) {
            try {
                mapped = map(encoder);
                session = open(null, mapped, threads, nnapi);
                mappedDecoder = map(decoderFile);
                decoder = open(null, mappedDecoder, threads, nnapi);
            } catch (IOException | OrtException e) {
                closeQuietly(session);
                session = null;
                mapped = null;
                mappedDecoder = null;
                encoder.delete(); // stale or damaged: rebuilt at the next load
                decoderFile.delete();
            }
        }
        if (session == null && optimized.isFile()) {
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
        final PiperModel model = new PiperModel(config, onnx, session, mapped, decoder, mappedDecoder,
                nnapi);
        try {
            model.warmUp();
        } catch (OrtException | RuntimeException e) {
            model.close();
            throw e;
        }
        if (!nnapi && model.decoder != null) {
            model.attachNpu(onnx);
        }
        return model;
    }

    /** True when this ONNX Runtime has Qualcomm's QNN provider: the Snapdragon build. */
    static boolean hasNpuRuntime() {
        try {
            return env().getAvailableProviders().contains(ai.onnxruntime.OrtProvider.QNN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Moves the decoder to Qualcomm's NPU when this build and phone have it.
     * The first time, writes the decoder with fixed input shapes and lets
     * QNN compile it (2-9 s), saving the compiled graph beside the model;
     * later loads read that (~0.15 s). Any failure keeps the CPU decoder
     * and is remembered, so a phone without the NPU pays for it once.
     */
    private void attachNpu(File onnx) {
        final File int8 = new File(onnx.getParentFile(), NPU_DECODER_FILE);
        if (boundaryShapes != null && int8.isFile() && hasNpuRuntime() && attachInt8Npu(onnx, int8)) {
            return;
        }
        attachFp16Npu(onnx);
    }

    /**
     * The downloaded INT8 decoder on the NPU. Its graph is already fixed-size
     * and quantized, so QNN only compiles it (cached beside the model, keyed
     * by the file's size so an updated file recompiles).
     */
    private boolean attachInt8Npu(File onnx, File int8) {
        final File failed = derivedFile(onnx, ".npuq-r1." + int8.length() + ".failed");
        if (failed.exists()) {
            return false;
        }
        final File compiled = derivedFile(onnx, ".npuq." + int8.length() + ".onnx");
        OrtSession npu = null;
        try {
            final boolean compile = !compiled.isFile();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                final Map<String, String> qnn = new HashMap<>();
                qnn.put("backend_type", "htp");
                qnn.put("htp_performance_mode", "burst");
                // The latent's own quantize step runs on the NPU too.
                qnn.put("offload_graph_io_quantization", "1");
                options.addQnn(qnn);
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
                if (compile) {
                    options.addConfigEntry("ep.context_enable", "1");
                    options.addConfigEntry("ep.context_file_path", compiled.getAbsolutePath());
                }
                npu = env().createSession((compile ? int8 : compiled).getAbsolutePath(), options);
            }
            // It must read exactly what this model's encoder hands the decoder.
            final Map<String, ai.onnxruntime.NodeInfo> info = npu.getInputInfo();
            if (!info.keySet().equals(new java.util.HashSet<>(Arrays.asList(boundary)))) {
                throw new IOException("inputs " + info.keySet() + " are not " + Arrays.toString(boundary));
            }
            final long[] latent = ((ai.onnxruntime.TensorInfo) info.get(boundary[0]).getInfo()).getShape();
            if (latent.length != 3 || latent[1] != boundaryShapes[0][1] || latent[2] <= 2 * DECODE_OVERLAP) {
                throw new IOException("latent " + Arrays.toString(latent));
            }
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                for (int i = 0; i < boundary.length; i++) {
                    final long[] shape = i == 0 ? latent : boundaryShapes[i];
                    inputs.put(boundary[i], OnnxTensor.createTensor(env(),
                            FloatBuffer.wrap(new float[(int) (shape[1] * shape[2])]), shape));
                }
                npu.run(inputs).close();
                checkNpuSpeed(npu, inputs, (int) latent[2]);
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
            npuFrames = (int) latent[2];
            npuKind = "INT8";
            npuDecoder = npu;
            return true;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            compiled.delete();
            try (java.io.FileWriter w = new java.io.FileWriter(failed)) {
                w.write(String.valueOf(e));
            } catch (IOException ignored) {
                // Tried again next load.
            }
            return false;
        }
    }

    private void attachFp16Npu(File onnx) {
        final long[][] shapes = boundaryShapes;
        final File failed = derivedFile(onnx, NPU_FAILED_SUFFIX);
        if (shapes == null || !hasNpuRuntime()) {
            return;
        }
        if (failed.exists()) {
            String why = "";
            try {
                why = new String(java.nio.file.Files.readAllBytes(failed.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // The reason is only for the log.
            }
            npuProblem = "on the CPU (NPU failed before: " + why + ")";
            return;
        }
        final File compiled = derivedFile(onnx, NPU_SUFFIX);
        final File source = derivedFile(onnx, ".npu.tmp");
        OrtSession npu = null;
        try {
            final boolean compile = !compiled.isFile();
            if (compile) {
                final Map<String, long[]> fixed = new HashMap<>();
                for (int i = 0; i < boundary.length; i++) {
                    fixed.put(boundary[i], i == 0
                            ? new long[] {1, shapes[0][1], NPU_FRAMES} : shapes[i]);
                }
                if (!PiperSplit.split(onnx, null, source, fixed)) {
                    throw new IOException("not splittable");
                }
            }
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                final Map<String, String> qnn = new HashMap<>();
                qnn.put("backend_type", "htp");
                qnn.put("enable_htp_fp16_precision", "1");
                qnn.put("htp_performance_mode", "burst");
                options.addQnn(qnn);
                // All of it on the NPU or not at all: a half-offloaded graph is slower.
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
                if (compile) {
                    options.addConfigEntry("ep.context_enable", "1");
                    options.addConfigEntry("ep.context_file_path", compiled.getAbsolutePath());
                }
                npu = env().createSession((compile ? source : compiled).getAbsolutePath(), options);
            }
            // A real window through it before it speaks.
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                for (int i = 0; i < boundary.length; i++) {
                    final long[] shape = i == 0 ? new long[] {1, shapes[0][1], NPU_FRAMES} : shapes[i];
                    inputs.put(boundary[i], OnnxTensor.createTensor(env(),
                            FloatBuffer.wrap(new float[(int) (shape[1] * shape[2])]), shape));
                }
                npu.run(inputs).close();
                checkNpuSpeed(npu, inputs, NPU_FRAMES);
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
            npuFrames = NPU_FRAMES;
            npuKind = "FP16";
            npuDecoder = npu;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            compiled.delete();
            npuProblem = "on the CPU (NPU failed: " + e + ")";
            try (java.io.FileWriter w = new java.io.FileWriter(failed)) {
                w.write(npuProblem);
            } catch (IOException ignored) {
                // Tried again next load.
            }
        } finally {
            source.delete();
        }
    }

    /** True when the decoder runs on Qualcomm's NPU. */
    boolean onNpu() {
        return npuDecoder != null;
    }

    /** "INT8" or "FP16" when on the NPU, else null. */
    String npuKind() {
        return npuDecoder != null ? npuKind : null;
    }

    /** Why a build with the NPU runtime isn't using it for this voice, or null. */
    String npuProblem() {
        return npuProblem;
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

    /** model.onnx -> model.<ONNX Runtime version><suffix>: a runtime update re-optimizes. */
    private static File derivedFile(File onnx, String suffix) {
        return new File(onnx.getParentFile(), OPTIMIZED_PREFIX + env().getVersion() + suffix);
    }

    /**
     * Writes the optimized copy: encoder and decoder if the model splits,
     * else one alignment-enabled model. On any failure there is simply none.
     */
    private static void buildOptimized(File onnx, File optimized, File encoder, File decoder,
                                       int threads) {
        final File dir = onnx.getParentFile();
        final File[] stale = dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && (n.endsWith(".ort") || n.endsWith(".tmp") || n.contains(".npu")) && !n.equals(NPU_DECODER_FILE));
        if (stale != null) {
            for (File f : stale) {
                f.delete();
            }
        }
        final File encoderSource = new File(dir, OPTIMIZED_PREFIX + "encoder.tmp");
        final File decoderSource = new File(dir, OPTIMIZED_PREFIX + "decoder.tmp");
        try {
            if (PiperSplit.split(onnx, encoderSource, decoderSource)
                    && saveOptimized(encoderSource, encoder, threads)) {
                if (saveOptimized(decoderSource, decoder, threads)) {
                    return;
                }
                encoder.delete();
            }
        } catch (IOException | RuntimeException ignored) {
            // Not splittable: one model below.
        } finally {
            encoderSource.delete();
            decoderSource.delete();
        }
        final File aligned = new File(dir, OPTIMIZED_PREFIX + "aligned.tmp");
        try {
            saveOptimized(PiperAlignment.addDurationOutput(onnx, aligned) ? aligned : onnx, optimized,
                    threads);
        } catch (IOException | RuntimeException ignored) {
            // No optimized copy: loads read the original.
        } finally {
            aligned.delete();
        }
    }

    /** {@code source} graph-optimized into ONNX Runtime's format at {@code out}. */
    private static boolean saveOptimized(File source, File out, int threads) {
        final File partial = new File(out.getParentFile(), out.getName() + ".tmp");
        try (OrtSession.SessionOptions options = options(threads, false)) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setOptimizedModelFilePath(partial.getAbsolutePath());
            options.addConfigEntry("session.save_model_format", "ORT");
            env().createSession(source.getAbsolutePath(), options).close();
            if (partial.renameTo(out)) {
                return true;
            }
        } catch (OrtException | RuntimeException ignored) {
            // Falls through: no copy.
        }
        partial.delete();
        return false;
    }

    private static void closeQuietly(OrtSession s) {
        if (s != null) {
            try {
                s.close();
            } catch (OrtException ignored) {
                // Nothing more to release.
            }
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
        if (vowel == null) {
            // Text voices in other scripts (SYSPIN Hindi has no "a"): any letter.
            for (Map.Entry<String, int[]> e : config.phonemeIdMap.entrySet()) {
                if (e.getKey().length() == 1 && Character.isLetter(e.getKey().charAt(0))) {
                    vowel = e.getValue();
                    break;
                }
            }
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
        if (decoder != null) {
            final Encoded e = encode(ids, lengthScale, speakerId, noise, noiseW, handle);
            final float[] audio = e == null ? null : decode(e, 0, e.frames, handle);
            return audio == null ? null : new Output(audio, e.durations);
        }
        lock.readLock().lock();
        try {
            if (closed || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtEnvironment env = env();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                putInputs(env, inputs, ids, lengthScale, speakerId, noise, noiseW);
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

    /**
     * A split model's encoder: phoneme ids to the latent the decoder reads.
     * Same arguments as {@link #infer}.
     *
     * @return null when the model was closed or the run cancelled
     */
    Encoded encode(long[] ids, float lengthScale, int speakerId, float noise, float noiseW,
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
                putInputs(env, inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = session.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[][] tensors = new float[boundary.length][];
                    final long[][] shapes = new long[boundary.length][];
                    for (int i = 0; i < boundary.length; i++) {
                        final OnnxValue v = r.get(i);
                        tensors[i] = floats(v);
                        if (tensors[i] == null) {
                            return null;
                        }
                        shapes[i] = ((OnnxTensor) v).getInfo().getShape();
                    }
                    if (shapes[0].length != 3) {
                        return null;
                    }
                    final float[] durations = hasDurations ? floats(r.get(boundary.length)) : null;
                    if (boundaryShapes == null) {
                        boundaryShapes = shapes;
                    }
                    return new Encoded(tensors, shapes,
                            durations != null && durations.length == ids.length ? durations : null);
                }
            } catch (OrtException e) {
                if (handle != null && handle.isCancelled()) {
                    return null;
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

    /**
     * Audio for latent frames [from, to) of an encoded chunk: decoded with
     * {@link #DECODE_OVERLAP} frames of context on each side, which are
     * cropped, so consecutive pieces join exactly.
     *
     * @return null when the model was closed or the run cancelled
     */
    float[] decode(Encoded e, int from, int to, RunHandle handle) throws OrtException {
        lock.readLock().lock();
        try {
            if (closed || (handle != null && handle.isCancelled())) {
                return null;
            }
            if (npuDecoder != null) {
                return decodeOnNpu(e, from, to, handle);
            }
            final int start = Math.max(0, from - DECODE_OVERLAP);
            final int end = Math.min(e.frames, to + DECODE_OVERLAP);
            final int span = end - start;
            if (span <= 0) {
                return new float[0];
            }
            final float[] latent = new float[e.channels * span];
            for (int c = 0; c < e.channels; c++) {
                System.arraycopy(e.tensors[0], c * e.frames + start, latent, c * span, span);
            }
            final OrtEnvironment env = env();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                inputs.put(boundary[0], OnnxTensor.createTensor(env, FloatBuffer.wrap(latent),
                        new long[] {1, e.channels, span}));
                for (int i = 1; i < boundary.length; i++) {
                    inputs.put(boundary[i], OnnxTensor.createTensor(env, FloatBuffer.wrap(e.tensors[i]),
                            e.shapes[i]));
                }
                final long started = System.nanoTime();
                try (OrtSession.Result r = decoder.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    final double speed = audio.length / (double) config.sampleRate
                            / Math.max(1e-6, (System.nanoTime() - started) / 1e9);
                    decodeSpeed = decodeSpeed == 0 ? speed : 0.7 * decodeSpeed + 0.3 * speed;
                    final int hop = audio.length / span;
                    return Arrays.copyOfRange(audio, (from - start) * hop,
                            audio.length - (end - to) * hop);
                }
            } catch (OrtException ex) {
                if (handle != null && handle.isCancelled()) {
                    return null;
                }
                throw ex;
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

    /**
     * {@link #decode} on the NPU: fixed windows of NPU_FRAMES, each with
     * DECODE_OVERLAP frames of context on both sides where the chunk has
     * them, cropped and joined. Past a chunk's end the window is zeros,
     * which only touches the last few frames (the trimmed tail silence).
     * Caller holds the read lock.
     */
    private float[] decodeOnNpu(Encoded e, int from, int to, RunHandle handle) throws OrtException {
        final int frames = npuFrames;
        final int step = frames - 2 * DECODE_OVERLAP;
        final OrtEnvironment env = env();
        final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
        final List<float[]> parts = new ArrayList<>();
        int total = 0;
        final long started = System.nanoTime();
        try {
            for (int s = from; s < to; s += step) {
                if (handle != null && handle.isCancelled()) {
                    return null;
                }
                final int len = Math.min(step, to - s);
                final int ws = Math.max(0, Math.min(s - DECODE_OVERLAP, e.frames - frames));
                final int have = Math.min(frames, e.frames - ws);
                final float[] latent = new float[e.channels * frames];
                for (int c = 0; c < e.channels; c++) {
                    System.arraycopy(e.tensors[0], c * e.frames + ws, latent, c * frames, have);
                }
                final Map<String, OnnxTensor> inputs = new HashMap<>();
                try {
                    inputs.put(boundary[0], OnnxTensor.createTensor(env, FloatBuffer.wrap(latent),
                            new long[] {1, e.channels, frames}));
                    for (int i = 1; i < boundary.length; i++) {
                        inputs.put(boundary[i], OnnxTensor.createTensor(env,
                                FloatBuffer.wrap(e.tensors[i]), e.shapes[i]));
                    }
                    try (OrtSession.Result r = npuDecoder.run(inputs,
                            handle != null ? handle.options : ownOptions)) {
                        final float[] audio = floats(r.get(0));
                        if (audio == null) {
                            return null;
                        }
                        final int hop = audio.length / frames;
                        parts.add(Arrays.copyOfRange(audio, (s - ws) * hop, (s - ws + len) * hop));
                        total += len * hop;
                    }
                } finally {
                    for (OnnxTensor t : inputs.values()) {
                        t.close();
                    }
                }
            }
        } catch (OrtException ex) {
            if (handle != null && handle.isCancelled()) {
                return null;
            }
            throw ex;
        } finally {
            if (ownOptions != null) {
                ownOptions.close();
            }
        }
        final float[] out = new float[total];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        final double speed = total / (double) config.sampleRate
                / Math.max(1e-6, (System.nanoTime() - started) / 1e9);
        decodeSpeed = decodeSpeed == 0 ? speed : 0.7 * decodeSpeed + 0.3 * speed;
        return out;
    }

    private void putInputs(OrtEnvironment env, Map<String, OnnxTensor> inputs, long[] ids,
                           float lengthScale, int speakerId, float noise, float noiseW)
            throws OrtException {
        inputs.put("input", OnnxTensor.createTensor(env, LongBuffer.wrap(ids),
                new long[] {1, ids.length}));
        inputs.put("input_lengths", OnnxTensor.createTensor(env,
                LongBuffer.wrap(new long[] {ids.length}), new long[] {1}));
        inputs.put("scales", OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {
                config.noiseScale * noise, lengthScale, config.noiseW * noiseW}), new long[] {3}));
        if (hasSpeakerInput) {
            // A one-voice config over a multi-speaker file (each Rasa voice)
            // names its speaker as the default.
            final int sid = config.numSpeakers > 1 ? speakerId : config.defaultSpeakerId;
            inputs.put("sid", OnnxTensor.createTensor(env,
                    LongBuffer.wrap(new long[] {sid}), new long[] {1}));
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
            closeQuietly(session);
            closeQuietly(decoder);
            closeQuietly(npuDecoder);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
