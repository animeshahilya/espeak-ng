/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;
import com.animeshahilya.espeakng.BuildConfig;
import com.animeshahilya.espeakng.text.Tashkeel;
import com.animeshahilya.espeakng.Voice;

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
public final class PiperModel implements Closeable {
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
    /** PiperDownloads.SOURCE_FILE: the installed model and config MD5s. */
    private static final String SOURCE_FILE = "source";
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
    private static final String NPU_FAILED_SUFFIX = ".npu-r3.failed";

    public final PiperVoiceConfig config;
    public final File file;
    /** Which model bytes these are ({@link #stamp(File)}): the original may be gone. */
    public final String stamp;
    private final SessionGroup group;
    private boolean closed;

    /**
     * Group of ONNX Runtime sessions and memory-mapped buffers.
     * Can be shared across multiple PiperModel instances that use the same underlying
     * ONNX model file (e.g. multi-speaker AI4Bharat Rasa voices).
     */
    static final class SessionGroup {
        final OrtSession session;
        final OrtSession decoder;
        final ByteBuffer mapped;
        final ByteBuffer mappedDecoder;
        final String[] boundary;
        final boolean hasSpeakerInput;
        final boolean hasDurations;
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private int refCount = 1;
        private boolean closed;
        volatile OrtSession npuDecoder;
        volatile String npuProblem;
        volatile int npuFrames = NPU_FRAMES;
        volatile String npuKind;
        volatile long[][] boundaryShapes;
        volatile double decodeSpeed;

        SessionGroup(OrtSession session, ByteBuffer mapped, OrtSession decoder,
                     ByteBuffer mappedDecoder) {
            this.session = session;
            this.mapped = mapped;
            this.decoder = decoder;
            this.mappedDecoder = mappedDecoder;
            this.hasSpeakerInput = session.getInputNames().contains("sid");
            this.boundary = decoder != null ? decoder.getInputNames().toArray(new String[0]) : new String[0];
            this.hasDurations = session.getOutputNames().size() > (decoder != null ? boundary.length : 1);
        }

        /** False once the sessions are closed: there is nothing left to share. */
        synchronized boolean acquire() {
            if (closed) {
                return false;
            }
            refCount++;
            return true;
        }

        void release() {
            synchronized (this) {
                refCount--;
                if (refCount > 0 || closed) {
                    return;
                }
                closed = true;
            }
            lock.writeLock().lock();
            try {
                closeQuietly(session);
                closeQuietly(decoder);
                closeQuietly(npuDecoder);
            } finally {
                lock.writeLock().unlock();
            }
        }

        boolean isClosed() {
            synchronized (this) {
                return closed;
            }
        }
    }

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

    PiperModel(PiperVoiceConfig config, File file, SessionGroup group) {
        this.config = config;
        this.file = file;
        this.stamp = stamp(file);
        this.group = group;
    }

    /**
     * Creates a new PiperModel sharing the existing ONNX Runtime session and memory-mapped
     * weights with a new voice configuration (e.g. different speaker id and phoneme map
     * over the same multi-speaker model).
     */
    PiperModel withConfig(PiperVoiceConfig newConfig) {
        if (newConfig == null) {
            return null;
        }
        synchronized (this) {
            if (closed || !group.acquire()) {
                return null;
            }
        }
        return new PiperModel(newConfig, file, group);
    }

    /** Whether this and {@code other} run on one shared session (see {@link #withConfig}). */
    boolean sharesSessionWith(PiperModel other) {
        return other != null && group == other.group;
    }

    /** True when runs report each id's frames (see {@link PiperAlignment}). */
    boolean hasDurations() {
        return group.hasDurations;
    }

    /** True when {@link #encode}/{@link #decode} can render a chunk in pieces. */
    boolean streams() {
        return group.decoder != null;
    }

    /** How fast {@link #decode} has run on this device: audio seconds per second, 0 if never. */
    double decodeSpeed() {
        return group.decodeSpeed;
    }

    /**
     * Loads a model. Seconds of work - never on a thread a screen reader is
     * waiting on.
     *
     * @param threads intra-op threads; VITS decoders scale to about 4
     */
    static PiperModel load(File onnx, PiperVoiceConfig config, int threads)
            throws OrtException {
        final File optimized = derivedFile(onnx, OPTIMIZED_SUFFIX);
        final File encoder = derivedFile(onnx, ENCODER_SUFFIX);
        final File decoderFile = derivedFile(onnx, DECODER_SUFFIX);
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile())) {
            final File sharedEnc = sharedDerivedFile(onnx, ENCODER_SUFFIX);
            final File sharedDec = sharedDerivedFile(onnx, DECODER_SUFFIX);
            final File sharedOpt = sharedDerivedFile(onnx, OPTIMIZED_SUFFIX);
            if (sharedEnc != null && sharedEnc.isFile() && sharedDec != null && sharedDec.isFile()) {
                PiperDownloads.linkOrCopy(sharedEnc, encoder);
                PiperDownloads.linkOrCopy(sharedDec, decoderFile);
            } else if (sharedOpt != null && sharedOpt.isFile()) {
                PiperDownloads.linkOrCopy(sharedOpt, optimized);
            }
        }
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile()) && !onnx.isFile()) {
            adoptOlderOptimized(onnx, optimized, encoder, decoderFile);
        }
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile()) && onnx.isFile()) {
            buildOptimized(onnx, optimized, encoder, decoderFile, threads);
        }
        OrtSession session = null;
        ByteBuffer mapped = null;
        OrtSession decoder = null;
        ByteBuffer mappedDecoder = null;
        if (encoder.isFile() && decoderFile.isFile()) {
            try {
                mapped = map(encoder);
                session = open(null, mapped, threads);
                mappedDecoder = map(decoderFile);
                decoder = open(null, mappedDecoder, threads);
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
                session = open(null, mapped, threads);
            } catch (IOException | OrtException e) {
                mapped = null;
                optimized.delete(); // stale or damaged: rebuilt at the next load
            }
        }
        if (session == null) {
            if (!onnx.isFile()) {
                // Neither the original nor a working optimized copy: the
                // service downloads the voice again (PiperDownloads.restoreMissing).
                throw new OrtException("No model for " + onnx.getParent());
            }
            session = open(onnx, null, threads);
        }
        final SessionGroup group = new SessionGroup(session, mapped, decoder, mappedDecoder);
        final PiperModel model = new PiperModel(config, onnx, group);
        try {
            model.warmUp();
        } catch (OrtException | RuntimeException e) {
            model.close();
            throw e;
        }
        if (model.streams()) {
            model.attachNpu(onnx);
        }
        if (mapped != null) {
            dropOriginal(onnx);
        }
        return model;
    }

    /**
     * Deletes the downloaded model once its optimized copy has loaded: the
     * phone kept both, twice the space (Piper medium 17 + 18 MB). A shared
     * model (all Rasa voices) goes too; its optimized copy stays in the
     * shared store. Kept where it is still needed: the Snapdragon build
     * compiles NPU graphs from it, and a voice without a {@code source}
     * record could not be matched to its catalog entry again.
     */
    private static void dropOriginal(File onnx) {
        if (hasNpuRuntime() || !new File(onnx.getParentFile(), SOURCE_FILE).isFile()) {
            return;
        }
        try {
            final java.nio.file.Path path = onnx.toPath();
            if (java.nio.file.Files.isSymbolicLink(path)) {
                final java.nio.file.Path target = path.resolveSibling(java.nio.file.Files.readSymbolicLink(path));
                java.nio.file.Files.deleteIfExists(target);
            }
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // Kept; tried again at the next load.
        }
    }

    /**
     * After an ONNX Runtime update, with the original deleted: takes the
     * optimized copy an older runtime wrote (ORT format loads in newer
     * runtimes) under the current name. If it fails to load, the caller
     * deletes it and the voice is downloaded again.
     */
    static void adoptOlderOptimized(File onnx, File optimized, File encoder, File decoder) {
        final File dir = onnx.getParentFile();
        final File[] old = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && n.endsWith(ENCODER_SUFFIX) && !n.equals(encoder.getName()));
        if (old != null) {
            for (File enc : old) {
                final File dec = new File(dir, enc.getName().replace(ENCODER_SUFFIX, DECODER_SUFFIX));
                if (dec.isFile() && enc.renameTo(encoder) && dec.renameTo(decoder)) {
                    return;
                }
            }
        }
        final File[] whole = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && n.endsWith(OPTIMIZED_SUFFIX) && !n.equals(optimized.getName()));
        if (whole != null && whole.length > 0) {
            //noinspection ResultOfMethodCallIgnored
            whole[0].renameTo(optimized);
        }
    }

    /**
     * Identifies a model's bytes for caches and session sharing: the MD5 the
     * voice was installed with ({@code source}), else the file's size and
     * time. Works with the original deleted.
     */
    static String stamp(File onnx) {
        final String md5 = sourceMd5(onnx.getParentFile());
        return md5 != null ? md5 : onnx.length() + ":" + onnx.lastModified();
    }

    /** {@code source}'s first line: the installed model's MD5, or null. */
    static String sourceMd5(File dir) {
        if (dir == null) {
            return null;
        }
        final File source = new File(dir, SOURCE_FILE);
        if (!source.isFile()) {
            return null;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(source),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            final String md5 = r.readLine();
            if (md5 == null || md5.trim().isEmpty() || "null".equals(md5.trim())) {
                return null;
            }
            return md5.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether a voice folder still has something to load: the model or an optimized copy of it. */
    static boolean hasModel(File dir) {
        if (new File(dir, "model.onnx").isFile()) {
            return true;
        }
        final File[] ort = dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX) && n.endsWith(".ort"));
        if (ort != null) {
            for (File f : ort) {
                if (f.isFile()) { // a link into the shared store counts only while it resolves
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the shared store holds an optimized copy of the model with
     * this MD5 for this runtime: enough to install another voice of it
     * (a second Rasa voice) with no model at all.
     */
    static boolean hasSharedOptimized(File sharedDir, String md5) {
        final String base = md5.toLowerCase(java.util.Locale.ROOT) + "." + env().getVersion();
        return (new File(sharedDir, base + ENCODER_SUFFIX).isFile() && new File(sharedDir, base + DECODER_SUFFIX).isFile())
                || new File(sharedDir, base + OPTIMIZED_SUFFIX).isFile();
    }

    private static volatile Boolean sNpuRuntime;

    /**
     * True when this ONNX Runtime has Qualcomm's QNN provider: the Snapdragon
     * build. The standard build answers without loading the runtime (the
     * settings screen asks on the main thread, natural voices on or off).
     */
    static boolean hasNpuRuntime() {
        if (!BuildConfig.SNAPDRAGON) {
            return false;
        }
        Boolean has = sNpuRuntime;
        if (has == null) {
            try {
                has = env().getAvailableProviders().contains(ai.onnxruntime.OrtProvider.QNN);
            } catch (RuntimeException e) {
                has = false;
            }
            sNpuRuntime = has;
        }
        return has;
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
        if (group.boundaryShapes != null && int8.isFile() && hasNpuRuntime() && attachInt8Npu(onnx, int8)) {
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
            if (!info.keySet().equals(new java.util.HashSet<>(Arrays.asList(group.boundary)))) {
                throw new IOException("inputs " + info.keySet() + " are not " + Arrays.toString(group.boundary));
            }
            final long[] latent = ((ai.onnxruntime.TensorInfo) info.get(group.boundary[0]).getInfo()).getShape();
            if (latent.length != 3 || latent[1] != group.boundaryShapes[0][1] || latent[2] <= 2 * DECODE_OVERLAP) {
                throw new IOException("latent " + Arrays.toString(latent));
            }
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                for (int i = 0; i < group.boundary.length; i++) {
                    final long[] shape = i == 0 ? latent : group.boundaryShapes[i];
                    inputs.put(group.boundary[i], OnnxTensor.createTensor(env(),
                            FloatBuffer.wrap(new float[(int) (shape[1] * shape[2])]), shape));
                }
                npu.run(inputs).close();
                checkNpuSpeed(npu, inputs, (int) latent[2]);
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
            group.npuFrames = (int) latent[2];
            group.npuKind = "INT8";
            group.npuDecoder = npu;
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
        final long[][] shapes = group.boundaryShapes;
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
            group.npuProblem = "on the CPU (NPU failed before: " + why + ")";
            return;
        }
        final File compiled = derivedFile(onnx, NPU_SUFFIX);
        final File source = derivedFile(onnx, ".npu.tmp");
        OrtSession npu = null;
        try {
            final boolean compile = !compiled.isFile();
            if (compile) {
                final Map<String, long[]> fixed = new HashMap<>();
                for (int i = 0; i < group.boundary.length; i++) {
                    fixed.put(group.boundary[i], i == 0
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
                // INT8-weight voices (int8-v1) widen their weights with
                // DequantizeLinear, which the fp16 NPU graph cannot run;
                // without QDQ handling ORT folds those into float weights
                // first, so the NPU gets the plain float decoder.
                options.addConfigEntry("session.disable_quant_qdq", "1");
                if (compile) {
                    options.addConfigEntry("ep.context_enable", "1");
                    options.addConfigEntry("ep.context_file_path", compiled.getAbsolutePath());
                }
                npu = env().createSession((compile ? source : compiled).getAbsolutePath(), options);
            }
            // A real window through it before it speaks.
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                for (int i = 0; i < group.boundary.length; i++) {
                    final long[] shape = i == 0 ? new long[] {1, shapes[0][1], NPU_FRAMES} : shapes[i];
                    inputs.put(group.boundary[i], OnnxTensor.createTensor(env(),
                            FloatBuffer.wrap(new float[(int) (shape[1] * shape[2])]), shape));
                }
                npu.run(inputs).close();
                checkNpuSpeed(npu, inputs, NPU_FRAMES);
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
            group.npuFrames = NPU_FRAMES;
            group.npuKind = "FP16";
            group.npuDecoder = npu;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            compiled.delete();
            group.npuProblem = "on the CPU (NPU failed: " + e + ")";
            try (java.io.FileWriter w = new java.io.FileWriter(failed)) {
                w.write(group.npuProblem);
            } catch (IOException ignored) {
                // Tried again next load.
            }
        } finally {
            source.delete();
        }
    }

    /** True when the decoder runs on Qualcomm's NPU. */
    boolean onNpu() {
        return group.npuDecoder != null;
    }

    /** "INT8" or "FP16" when on the NPU, else null. */
    String npuKind() {
        return group.npuDecoder != null ? group.npuKind : null;
    }

    /** Why a build with the NPU runtime isn't using it for this voice, or null. */
    String npuProblem() {
        return group.npuProblem;
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
     * File in the shared directory holding the pre-optimized model, if this
     * voice shares its model weights with others (e.g. AI4Bharat Rasa voices).
     */
    private static File sharedDerivedFile(File onnx, String suffix) {
        final File dir = onnx.getParentFile();
        final String md5 = sourceMd5(dir);
        if (md5 == null) {
            return null;
        }
        final File parent = dir.getParentFile();
        if (parent == null) {
            return null;
        }
        final File sharedDir;
        if ("voices".equals(parent.getName())) {
            sharedDir = new File(parent.getParentFile(), "shared");
        } else {
            sharedDir = new File(parent, "shared");
        }
        if (!sharedDir.isDirectory() && !sharedDir.mkdirs()) {
            return null;
        }
        return new File(sharedDir, md5 + "." + env().getVersion() + suffix);
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
        final File sharedEnc = sharedDerivedFile(onnx, ENCODER_SUFFIX);
        final File sharedDec = sharedDerivedFile(onnx, DECODER_SUFFIX);
        final File sharedOpt = sharedDerivedFile(onnx, OPTIMIZED_SUFFIX);
        final File encoderSource = new File(dir, OPTIMIZED_PREFIX + "encoder.tmp");
        final File decoderSource = new File(dir, OPTIMIZED_PREFIX + "decoder.tmp");
        try {
            if (PiperSplit.split(onnx, encoderSource, decoderSource)
                    && saveOptimized(encoderSource, encoder, threads)) {
                if (saveOptimized(decoderSource, decoder, threads)) {
                    if (sharedEnc != null && !sharedEnc.isFile()) {
                        share(encoder, sharedEnc);
                        share(decoder, sharedDec);
                    }
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
            if (saveOptimized(PiperAlignment.addDurationOutput(onnx, aligned) ? aligned : onnx, optimized,
                    threads)) {
                if (sharedOpt != null && !sharedOpt.isFile()) {
                    share(optimized, sharedOpt);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No optimized copy: loads read the original.
        } finally {
            aligned.delete();
        }
    }

    /** {@code source} graph-optimized into ONNX Runtime's format at {@code out}. */
    /**
     * Moves a freshly built file into the shared store and links it back, so
     * the real bytes live in the shared dir (kept while any voice of that
     * model is installed) rather than inside the first voice's dir, where
     * deleting that voice left every other voice's link dangling.
     */
    private static void share(File built, File shared) {
        if (built.renameTo(shared) && !PiperDownloads.linkOrCopy(shared, built)) {
            shared.renameTo(built); // keep this voice working without a share
        }
    }

    private static boolean saveOptimized(File source, File out, int threads) {
        final File partial = new File(out.getParentFile(), out.getName() + ".tmp");
        try (OrtSession.SessionOptions options = options(threads)) {
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
    private static OrtSession open(File model, ByteBuffer mapped, int threads)
            throws OrtException {
        try (OrtSession.SessionOptions options = options(threads)) {
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

    private static OrtSession.SessionOptions options(int threads) throws OrtException {
        final OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setInterOpNumThreads(1);
        // One utterance at a time, of varying length: the memory
        // pattern planner only helps fixed-shape batch inference.
        options.setMemoryPatternOptimization(false);
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
        if (vowel == null) {
            // Any speakable symbol if no single ASCII/letter was mapped
            for (Map.Entry<String, int[]> e : config.phonemeIdMap.entrySet()) {
                final String k = e.getKey();
                if (!PiperVoiceConfig.PAD.equals(k) && !PiperVoiceConfig.BOS.equals(k)
                        && !PiperVoiceConfig.EOS.equals(k) && !" ".equals(k)
                        && e.getValue() != null && e.getValue().length > 0) {
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
        if (group.decoder != null) {
            final Encoded e = encode(ids, lengthScale, speakerId, noise, noiseW, handle);
            final float[] audio = e == null ? null : decode(e, 0, e.frames, handle);
            return audio == null ? null : new Output(audio, e.durations);
        }
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtEnvironment env = env();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                putInputs(env, inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = group.session.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    final float[] durations = group.hasDurations ? floats(r.get(1)) : null;
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
            group.lock.readLock().unlock();
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
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtEnvironment env = env();
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                putInputs(env, inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = group.session.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[][] tensors = new float[group.boundary.length][];
                    final long[][] shapes = new long[group.boundary.length][];
                    for (int i = 0; i < group.boundary.length; i++) {
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
                    final float[] durations = group.hasDurations ? floats(r.get(group.boundary.length)) : null;
                    if (group.boundaryShapes == null) {
                        group.boundaryShapes = shapes;
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
            group.lock.readLock().unlock();
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
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || (handle != null && handle.isCancelled())) {
                return null;
            }
            if (group.npuDecoder != null) {
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
                inputs.put(group.boundary[0], OnnxTensor.createTensor(env, FloatBuffer.wrap(latent),
                        new long[] {1, e.channels, span}));
                for (int i = 1; i < group.boundary.length; i++) {
                    inputs.put(group.boundary[i], OnnxTensor.createTensor(env, FloatBuffer.wrap(e.tensors[i]),
                            e.shapes[i]));
                }
                final long started = System.nanoTime();
                try (OrtSession.Result r = group.decoder.run(inputs,
                        handle != null ? handle.options : ownOptions)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    final double speed = audio.length / (double) config.sampleRate
                            / Math.max(1e-6, (System.nanoTime() - started) / 1e9);
                    group.decodeSpeed = group.decodeSpeed == 0 ? speed : 0.7 * group.decodeSpeed + 0.3 * speed;
                    final int hop = Math.max(1, audio.length / span);
                    final int fromIdx = Math.max(0, Math.min(audio.length, (from - start) * hop));
                    final int toIdx = Math.max(fromIdx, Math.min(audio.length, audio.length - (end - to) * hop));
                    return Arrays.copyOfRange(audio, fromIdx, toIdx);
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
            group.lock.readLock().unlock();
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
        final int frames = group.npuFrames;
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
                    inputs.put(group.boundary[0], OnnxTensor.createTensor(env, FloatBuffer.wrap(latent),
                            new long[] {1, e.channels, frames}));
                    for (int i = 1; i < group.boundary.length; i++) {
                        inputs.put(group.boundary[i], OnnxTensor.createTensor(env,
                                FloatBuffer.wrap(e.tensors[i]), e.shapes[i]));
                    }
                    try (OrtSession.Result r = group.npuDecoder.run(inputs,
                            handle != null ? handle.options : ownOptions)) {
                        final float[] audio = floats(r.get(0));
                        if (audio == null) {
                            return null;
                        }
                        final int hop = Math.max(1, audio.length / frames);
                        final int fromIdx = Math.max(0, Math.min(audio.length, (s - ws) * hop));
                        final int toIdx = Math.max(fromIdx, Math.min(audio.length, (s - ws + len) * hop));
                        parts.add(Arrays.copyOfRange(audio, fromIdx, toIdx));
                        total += toIdx - fromIdx;
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
        group.decodeSpeed = group.decodeSpeed == 0 ? speed : 0.7 * group.decodeSpeed + 0.3 * speed;
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
        if (group.hasSpeakerInput) {
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
        synchronized (this) {
            return closed || group.isClosed();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        group.release();
        if (config.tashkeel) {
            Tashkeel.close(file.getParentFile());
        }
    }
}


