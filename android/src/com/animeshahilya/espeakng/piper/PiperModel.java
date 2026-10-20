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
    static final String OPTIMIZED_SUFFIX = ".whole.ort";
    static final String ENCODER_SUFFIX = ".enc.ort";
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
     * Hosted NPU files (sherpa-onnx-respin-syspin's build_npu_enc.py, release
     * npu-v2, fetched by PiperDownloads.fetchNpuDecoders): the decoder with its
     * latent fixed to 80 frames for voices with no INT8 one (fp16 on the NPU),
     * and the encoder in three parts - text encoder (NPU, fixed ids), the
     * duration predictor and length regulator between (CPU), flow (NPU, fixed
     * frames). The phone only compiles them; it no longer cuts models itself.
     */
    static final String NPU_DECODER16_FILE = "npudec.onnx";
    static final String NPU_ENC_FILE = "npuenc.onnx";
    static final String NPU_MID_FILE = "npumid.onnx";
    static final String NPU_FLOW_FILE = "npuflow.onnx";
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
    /** Holds why the NPU failed; the revision makes phones retry after NPU code changes. */

    public final PiperVoiceConfig config;
    public final File file;
    /** Which model bytes these are ({@link #stamp(File)}): the original may be gone. */
    public final String stamp;
    private final SessionGroup group;
    private boolean closed;
    /** A Supertonic voice's style (each voice of the shared runtime has its own); null otherwise. */
    volatile Supertonic.Style style;

    /**
     * Group of ONNX Runtime sessions and memory-mapped buffers.
     * Can be shared across multiple PiperModel instances that use the same underlying
     * ONNX model file (e.g. multi-speaker AI4Bharat Rasa voices).
     */
    static final class SessionGroup {
        /**
         * The CPU encoder (the whole model for voices that don't split). Null
         * until first needed when the voice loaded NPU-first ({@link #load}):
         * opening it is most of a load (~0.5 s on a Galaxy S25 Ultra, a graph of
         * thousands of nodes) and the NPU encoder takes every usual chunk.
         */
        volatile OrtSession session;
        final OrtSession decoder;
        final ByteBuffer mapped;
        final ByteBuffer mappedDecoder;
        final String[] boundary;
        volatile boolean hasSpeakerInput;
        volatile boolean hasDurations;
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private int refCount = 1;
        private boolean closed;
        volatile OrtSession npuDecoder;
        /**
         * NPU sessions that failed while speaking: out of use at once, closed
         * with the rest (another run may still be inside one). Guarded by this.
         */
        final List<OrtSession> retired = new ArrayList<>();
        /** The hosted encoder parts ({@link #NPU_ENC_FILE}...), all set or all null. */
        volatile OrtSession npuEnc;
        volatile OrtSession npuMid;
        volatile OrtSession npuFlow;
        /** Keeps the mapped middle part alive while ONNX Runtime reads it (see proguard-rules.pro). */
        volatile ByteBuffer mappedMid;
        volatile int npuEncIds;
        volatile int npuFlowFrames;
        /** Intra-op threads of this voice's CPU sessions (the encoder's middle part uses them too). */
        volatile int threads = 2;
        volatile String npuProblem;
        volatile int npuFrames = NPU_FRAMES;
        volatile String npuKind;
        volatile long[][] boundaryShapes;
        volatile double decodeSpeed;
        /** Supertonic voices: their runtime instead of the VITS sessions (all of these null). */
        final Supertonic supertonic;

        SessionGroup(Supertonic supertonic) {
            this.supertonic = supertonic;
            this.session = null;
            this.mapped = null;
            this.decoder = null;
            this.mappedDecoder = null;
            this.hasSpeakerInput = false;
            this.boundary = new String[0];
            this.hasDurations = false;
        }

        SessionGroup(OrtSession session, ByteBuffer mapped, OrtSession decoder,
                     ByteBuffer mappedDecoder) {
            this.supertonic = null;
            this.session = session;
            this.mapped = mapped;
            this.decoder = decoder;
            this.mappedDecoder = mappedDecoder;
            this.boundary = decoder != null ? decoder.getInputNames().toArray(new String[0]) : new String[0];
            if (session != null) {
                readNames(session);
            }
        }

        private void readNames(OrtSession session) {
            hasSpeakerInput = session.getInputNames().contains("sid");
            hasDurations = session.getOutputNames().size() > (decoder != null ? boundary.length : 1);
        }

        /** The CPU encoder, opened now if the voice loaded without it. Caller holds the read lock. */
        OrtSession cpuSession() throws OrtException {
            final OrtSession s = session;
            if (s != null) {
                return s;
            }
            synchronized (this) {
                if (closed) {
                    throw new OrtException("closed");
                }
                if (session == null) {
                    final long t0 = System.nanoTime();
                    final OrtSession opened = open(null, mapped, threads);
                    readNames(opened);
                    session = opened;
                    android.util.Log.i("PiperVoices", "CPU encoder opened when first needed in "
                            + (System.nanoTime() - t0) / 1_000_000 + " ms");
                }
                return session;
            }
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
                closeQuietly(npuEnc);
                closeQuietly(npuMid);
                closeQuietly(npuFlow);
                for (OrtSession s : retired) {
                    closeQuietly(s);
                }
                if (supertonic != null) {
                    supertonic.close();
                }
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
        return withConfig(newConfig, null);
    }

    /** @param voiceFile the new voice's own model.onnx: a Supertonic voice's style is read beside it */
    PiperModel withConfig(PiperVoiceConfig newConfig, File voiceFile) {
        if (newConfig == null) {
            return null;
        }
        Supertonic.Style newStyle = null;
        if (group.supertonic != null) {
            try {
                newStyle = Supertonic.Style.load((voiceFile != null ? voiceFile : file).getParentFile());
            } catch (IOException e) {
                return null; // its style is missing: loaded on its own, which reports why
            }
        }
        synchronized (this) {
            if (closed || !group.acquire()) {
                return null;
            }
        }
        final PiperModel m = new PiperModel(newConfig, file, group);
        m.style = newStyle;
        return m;
    }

    /** True for a Supertonic voice (text in, its own runtime). */
    boolean isSupertonic() {
        return group.supertonic != null;
    }

    /** Supertonic text ids for a chunk of this voice's language. */
    long[] supertonicIds(String text) {
        return group.supertonic.textIds(text, config.supertonicLang);
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
        if (config.isSupertonic()) {
            final Supertonic.Style style;
            try {
                style = Supertonic.Style.load(onnx.getParentFile());
            } catch (IOException e) {
                throw new OrtException("No style for " + onnx.getParent() + ": " + e);
            }
            final PiperModel model = new PiperModel(config, onnx, new SessionGroup(Supertonic.load(onnx, threads)));
            model.style = style;
            try {
                model.warmUp();
            } catch (OrtException | RuntimeException e) {
                model.close();
                throw e;
            }
            return model;
        }
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
        // NPU parts compiled and shapes known: the NPU encoder warms the voice
        // up and the CPU encoder opens only when a chunk needs it.
        final Boundary known = encoder.isFile() && decoderFile.isFile() && npuReady(onnx)
                ? Boundary.read(boundaryFile(onnx), encoder) : null;
        if (encoder.isFile() && decoderFile.isFile()) {
            try {
                mapped = map(encoder);
                if (known == null) {
                    session = open(null, mapped, threads);
                }
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
        final boolean npuFirst = known != null && decoder != null;
        if (session == null && !npuFirst && optimized.isFile()) {
            try {
                mapped = map(optimized);
                session = open(null, mapped, threads);
            } catch (IOException | OrtException e) {
                mapped = null;
                optimized.delete(); // stale or damaged: rebuilt at the next load
            }
        }
        if (session == null && !npuFirst) {
            if (!onnx.isFile()) {
                // Neither the original nor a working optimized copy: the
                // service downloads the voice again (PiperDownloads.restoreMissing).
                throw new OrtException("No model for " + onnx.getParent());
            }
            session = open(onnx, null, threads);
        }
        final SessionGroup group = new SessionGroup(session, mapped, decoder, mappedDecoder);
        group.threads = threads;
        final PiperModel model = new PiperModel(config, onnx, group);
        try {
            if (npuFirst) {
                group.boundaryShapes = known.shapes;
                group.hasSpeakerInput = known.speaker;
                group.hasDurations = known.durations;
                model.attachNpu(onnx);
                model.warmUp(); // through the NPU; any part that fails hands over to the CPU
            } else {
                model.warmUp();
            }
        } catch (OrtException | RuntimeException e) {
            model.close();
            throw e;
        }
        if (model.streams() && !npuFirst) {
            if (hasNpuRuntime() && group.boundaryShapes != null) {
                Boundary.write(boundaryFile(onnx), encoder, group);
            }
            if (model.npuCompiled(onnx) || !hasNpuRuntime()) {
                model.attachNpu(onnx); // a compiled graph loads in ~0.15 s
            } else {
                model.attachNpuLater(onnx);
            }
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
     * shared store. Kept where a voice has no {@code source} record: it could
     * not be matched to its catalog entry again. (The Snapdragon build kept
     * originals to cut NPU graphs from until its NPU files came hosted.)
     */
    private static void dropOriginal(File onnx) {
        if (!new File(onnx.getParentFile(), SOURCE_FILE).isFile()) {
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

    /**
     * Forgets a voice's optimized copies, and the shared store's for its
     * model, so the next load splits it again (a Compact voice split before
     * PiperSplit moved its quantize pair into the decoder, whose boundary no
     * INT8 NPU decoder reads). Only while the original is here to split
     * from: the Snapdragon build keeps it, the standard build has no NPU.
     */
    static void forgetOptimized(File dir) {
        if (!new File(dir, "model.onnx").isFile()) {
            return;
        }
        deleteOptimized(dir, OPTIMIZED_PREFIX);
        final String md5 = sourceMd5(dir);
        final File voices = dir.getParentFile();
        if (md5 != null && voices != null && voices.getParentFile() != null) {
            deleteOptimized(new File(voices.getParentFile(), PiperVoiceStore.SHARED_DIR), md5 + ".");
        }
    }

    private static void deleteOptimized(File dir, String prefix) {
        final File[] files = dir.listFiles((d, n) -> n.startsWith(prefix)
                && (n.endsWith(".ort") || n.contains(".npu")));
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Name Qualcomm's QNN plugin registers its NPU device under. */
    private static final String QNN_EP = "QNNExecutionProvider";
    private static volatile List<ai.onnxruntime.OrtEpDevice> sNpuDevices;

    /**
     * Qualcomm's QNN plugin (com.qualcomm.qti:onnxruntime-android-qnn, in the
     * Snapdragon build only) registered with this runtime, and its NPU
     * devices; empty without one. The standard build answers without
     * loading the runtime (the settings screen asks on the main thread).
     */
    private static List<ai.onnxruntime.OrtEpDevice> npuDevices() {
        if (!BuildConfig.SNAPDRAGON) {
            return java.util.Collections.emptyList();
        }
        List<ai.onnxruntime.OrtEpDevice> devices = sNpuDevices;
        if (devices == null) {
            synchronized (PiperModel.class) {
                devices = sNpuDevices;
                if (devices == null) {
                    devices = new ArrayList<>();
                    try {
                        final File lib = new File(com.animeshahilya.espeakng.EspeakApp.getStorageContext()
                                .getApplicationInfo().nativeLibraryDir, "libonnxruntime_providers_qnn.so");
                        env().registerExecutionProviderLibrary(QNN_EP, lib.getAbsolutePath());
                        for (ai.onnxruntime.OrtEpDevice d : env().getEpDevices()) {
                            if (QNN_EP.equals(d.getEpName())) {
                                devices.add(d);
                            }
                        }
                    } catch (OrtException | RuntimeException e) {
                        android.util.Log.w("PiperVoices", "QNN plugin not loaded: " + e);
                    }
                    sNpuDevices = devices;
                }
            }
        }
        return devices;
    }

    /**
     * QNN's HTP backend by full path, from the app's native libraries: the
     * plugin then finds the NPU-side libraries (libQnnHtpV*Skel.so) beside
     * it; by type alone the DSP could not open them (Galaxy S25 Ultra).
     */
    static String npuBackend() {
        return new File(com.animeshahilya.espeakng.EspeakApp.getStorageContext()
                .getApplicationInfo().nativeLibraryDir, "libQnnHtp.so").getAbsolutePath();
    }

    /** True when the NPU runtime is here: the Snapdragon build on a phone QNN accepts. */
    static boolean hasNpuRuntime() {
        return !npuDevices().isEmpty();
    }

    /** Puts the session on the NPU through the QNN plugin with these options. */
    static void addNpu(OrtSession.SessionOptions options, Map<String, String> qnn) throws OrtException {
        options.addExecutionProvider(npuDevices(), qnn);
    }

    /** One low-priority thread compiles NPU graphs, one voice at a time. */
    private static final java.util.concurrent.ExecutorService NPU_COMPILER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "piper-npu-compile");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /**
     * True when this voice's NPU graph is already compiled, so attaching it
     * is quick. Mirrors {@link #attachNpu}'s choice of INT8 or FP16 graph.
     */
    private boolean npuCompiled(File onnx) {
        final File dir = onnx.getParentFile();
        final File int8 = new File(dir, NPU_DECODER_FILE);
        if (group.boundaryShapes != null && hasHosted(int8)
                && !derivedFile(onnx, ".npuq-r3." + hostedSize(int8) + ".failed").exists()
                && !derivedFile(onnx, ".npuq." + hostedSize(int8) + ".onnx").isFile()) {
            return false;
        }
        if (!hasHosted(int8) && !hostedReady(onnx, new File(dir, NPU_DECODER16_FILE))) {
            return false;
        }
        // One failure marker (the text encoder's) stands for all three parts:
        // a flow that never compiled because its encoder failed is not waited on.
        final File enc = new File(dir, NPU_ENC_FILE);
        return failedFile(onnx, enc).exists()
                || hostedReady(onnx, enc) && hostedReady(onnx, new File(dir, NPU_FLOW_FILE));
    }

    /**
     * True when every hosted NPU part of the voice is there and compiled, with
     * no failure marked: it can load without opening its CPU encoder.
     */
    private static boolean npuReady(File onnx) {
        if (!hasNpuRuntime()) {
            return false;
        }
        final File dir = onnx.getParentFile();
        final File enc = new File(dir, NPU_ENC_FILE);
        final File flow = new File(dir, NPU_FLOW_FILE);
        final File mid = new File(dir, NPU_MID_FILE);
        if (!hasHosted(mid) || !hasHosted(enc) || !hasHosted(flow)
                || failedFile(onnx, enc).exists() || !compiledAvailable(mid)
                || !compiledFile(onnx, enc).isFile() || !compiledFile(onnx, flow).isFile()) {
            return false;
        }
        final File int8 = new File(dir, NPU_DECODER_FILE);
        if (hasHosted(int8)) {
            return compiledAvailable(int8)
                    && !derivedFile(onnx, ".npuq-r3." + hostedSize(int8) + ".failed").exists();
        }
        final File dec = new File(dir, NPU_DECODER16_FILE);
        return compiledAvailable(dec) && !failedFile(onnx, dec).exists();
    }

    private static File boundaryFile(File onnx) {
        return derivedFile(onnx, ".boundary");
    }

    /**
     * What the CPU encoder tells about a voice that the NPU path needs: the
     * shapes it hands the decoder and whether it reads a speaker and reports
     * durations. Kept beside the voice after the first load, tied to the
     * encoder file it came from (size and time), so a later load can skip
     * opening that encoder.
     */
    static final class Boundary {
        final long[][] shapes;
        final boolean speaker;
        final boolean durations;

        private Boundary(long[][] shapes, boolean speaker, boolean durations) {
            this.shapes = shapes;
            this.speaker = speaker;
            this.durations = durations;
        }

        static void write(File file, File encoder, SessionGroup g) {
            final StringBuilder b = new StringBuilder();
            b.append(encoder.length()).append(' ').append(encoder.lastModified()).append('\n');
            b.append(g.hasSpeakerInput).append(' ').append(g.hasDurations).append('\n');
            for (long[] shape : g.boundaryShapes) {
                for (int i = 0; i < shape.length; i++) {
                    b.append(i > 0 ? "," : "").append(shape[i]);
                }
                b.append('\n');
            }
            try {
                java.nio.file.Files.write(file.toPath(), b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The next load opens the CPU encoder again and retries.
            }
        }

        /** Null when missing, unreadable or written for another encoder file. */
        static Boundary read(File file, File encoder) {
            if (!file.isFile()) {
                return null;
            }
            try {
                final String[] lines = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n");
                if (lines.length < 3 || !lines[0].equals(encoder.length() + " " + encoder.lastModified())) {
                    return null;
                }
                final String[] flags = lines[1].split(" ");
                final long[][] shapes = new long[lines.length - 2][];
                for (int i = 2; i < lines.length; i++) {
                    final String[] dims = lines[i].split(",");
                    shapes[i - 2] = new long[dims.length];
                    for (int d = 0; d < dims.length; d++) {
                        shapes[i - 2][d] = Long.parseLong(dims[d].trim());
                    }
                }
                return new Boundary(shapes, Boolean.parseBoolean(flags[0]), Boolean.parseBoolean(flags[1]));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Deletes what the phone made from a hosted NPU file that is being
     * replaced: its compiled graphs (any runtime, any size) and failure
     * markers. They are named by the file's size, so a rebuilt file of the
     * same size would otherwise keep running the old graph, and one of a new
     * size left the old graph behind (~10 MB a part).
     */
    static void forgetCompiled(File hosted) {
        final String stem = NPU_DECODER_FILE.equals(hosted.getName()) ? "npuq"
                : hosted.getName().replace(".onnx", "");
        //noinspection ResultOfMethodCallIgnored
        sizeFile(hosted).delete();
        final File[] made = hosted.getParentFile().listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && (n.contains("." + stem + ".") || n.contains("." + stem + "-r")));
        if (made != null) {
            for (File f : made) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** A hosted NPU file compiled already, failed before, or absent: nothing to wait for. */
    private static boolean hostedReady(File onnx, File hosted) {
        return !hasHosted(hosted) || compiledFile(onnx, hosted).isFile() || failedFile(onnx, hosted).exists();
    }

    /** Where a hosted NPU file's compiled graph is kept: keyed by its size, so an update recompiles. */
    private static File compiledFile(File onnx, File hosted) {
        return derivedFile(onnx, "." + hosted.getName().replace(".onnx", "") + "." + hostedSize(hosted) + ".ctx.onnx");
    }

    private static File failedFile(File onnx, File hosted) {
        return derivedFile(onnx, "." + hosted.getName().replace(".onnx", "") + "-r1." + hostedSize(hosted) + ".failed");
    }

    /**
     * A hosted NPU file's size: its compiled forms are named by it. Once they
     * are made the file itself is deleted (~16 MB a voice; it is needed again
     * only to recompile, after a runtime update, and then downloads again) and
     * its size is kept in {@code <name>.size}. -1 when the voice has no such file.
     */
    static long hostedSize(File hosted) {
        if (hosted.isFile()) {
            return hosted.length();
        }
        try {
            return Long.parseLong(new String(java.nio.file.Files.readAllBytes(sizeFile(hosted).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    static boolean hasHosted(File hosted) {
        return hostedSize(hosted) >= 0;
    }

    private static File sizeFile(File hosted) {
        return new File(hosted.getParentFile(), hosted.getName() + ".size");
    }

    /** What this runtime made from a hosted NPU file: compiled graph, INT8 context or ORT copy. */
    private static File compiledOf(File hosted) {
        final File onnx = new File(hosted.getParentFile(), "model.onnx");
        if (NPU_DECODER_FILE.equals(hosted.getName())) {
            return derivedFile(onnx, ".npuq." + hostedSize(hosted) + ".onnx");
        }
        if (NPU_MID_FILE.equals(hosted.getName())) {
            return derivedFile(onnx, ".npumid." + hostedSize(hosted) + ".ort");
        }
        return compiledFile(onnx, hosted);
    }

    /**
     * True when this runtime's compiled form of a hosted file is here, so the
     * file itself is not needed (PiperDownloads fetches it again otherwise).
     */
    static boolean compiledAvailable(File hosted) {
        return hasHosted(hosted) && compiledOf(hosted).isFile();
    }

    /** Deletes hosted files whose compiled forms loaded, keeping their sizes. */
    private static void dropHostedSources(File... hosted) {
        for (File h : hosted) {
            if (!h.isFile() || !compiledOf(h).isFile()) {
                continue;
            }
            try {
                java.nio.file.Files.write(sizeFile(h).toPath(),
                        String.valueOf(h.length()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                //noinspection ResultOfMethodCallIgnored
                h.delete();
            } catch (IOException ignored) {
                // Kept: it costs space, nothing else.
            }
        }
    }

    /** No compiled form for this runtime and the file to make one from is gone: it downloads again. */
    private static boolean awaitingSource(File onnx, File hosted, File compiled) {
        return !compiled.isFile() && !hosted.isFile();
    }

    /**
     * The first load of a voice (or the first after an update) has to compile
     * its NPU graph, 2-17 s. The voice speaks with its CPU decoder meanwhile
     * and moves to the NPU when the graph is ready, instead of the whole voice
     * waiting (and eSpeak speaking) that long. The compile holds a reference,
     * so a voice unloaded meanwhile closes the NPU session with the rest.
     */
    private void attachNpuLater(File onnx) {
        group.npuProblem = "on the CPU until its NPU graph is compiled";
        if (!group.acquire()) {
            return;
        }
        NPU_COMPILER.execute(() -> {
            try {
                if (!group.isClosed()) {
                    final long t0 = System.nanoTime();
                    attachNpu(onnx);
                    android.util.Log.i("PiperVoices", "Natural voice " + config.key + " decoder "
                            + (onNpu() ? "moved to the NPU (" + npuKind() + ") after "
                                    + (System.nanoTime() - t0) / 1_000_000 + " ms of compiling"
                                    : npuProblem())
                            + (group.npuEnc != null ? ", encoder on the NPU" : ", encoder on the CPU"));
                }
            } finally {
                group.release();
            }
        });
    }

    /**
     * Moves the decoder to Qualcomm's NPU when this build and phone have it.
     * The first time, writes the decoder with fixed input shapes and lets
     * QNN compile it (2-9 s), saving the compiled graph beside the model;
     * later loads read that (~0.15 s). Any failure keeps the CPU decoder
     * and is remembered, so a phone without the NPU pays for it once.
     */
    private void attachNpu(File onnx) {
        if (!hasNpuRuntime() || group.boundaryShapes == null) {
            return;
        }
        final File dir = onnx.getParentFile();
        // Graphs the phone used to cut and compile itself (before hosted files):
        // model.<runtime>.npu80.onnx and its _qnn.bin, and their failure markers.
        final File[] legacy = dir.listFiles((d, n) -> n.startsWith(OPTIMIZED_PREFIX)
                && (n.contains(".npu80") || n.contains(".npu-r")));
        if (legacy != null) {
            for (File f : legacy) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        final File int8 = new File(dir, NPU_DECODER_FILE);
        final File dec16 = new File(dir, NPU_DECODER16_FILE);
        if (!(hasHosted(int8) && attachInt8Npu(onnx, int8))) {
            attachHostedDecoder(onnx, dec16);
        }
        attachNpuEncoder(onnx);
        if (group.npuDecoder != null) {
            dropHostedSources(int8, dec16);
        }
        if (group.npuEnc != null) {
            dropHostedSources(new File(dir, NPU_ENC_FILE), new File(dir, NPU_MID_FILE),
                    new File(dir, NPU_FLOW_FILE));
        }
    }

    /**
     * An NPU session over a hosted fixed-shape graph (fp16 on the HTP; its INT8
     * weights widened), compiled once into {@link #compiledFile}. Null, with the
     * reason remembered, when it fails.
     */
    private static OrtSession hostedNpuSession(File onnx, File hosted) throws IOException, OrtException {
        final File compiled = compiledFile(onnx, hosted);
        final boolean compile = !compiled.isFile();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            final Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", npuBackend());
            qnn.put("enable_htp_fp16_precision", "1");
            qnn.put("htp_performance_mode", "burst");
            addNpu(options, qnn);
            // All of it on the NPU or not at all: a half-offloaded graph is slower.
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            // The hosted files keep INT8 weights behind DequantizeLinear, which the
            // fp16 NPU graph cannot run; ORT folds them into float weights first.
            options.addConfigEntry("session.disable_quant_qdq", "1");
            if (compile) {
                options.addConfigEntry("ep.context_enable", "1");
                options.addConfigEntry("ep.context_file_path", compiled.getAbsolutePath());
            }
            return env().createSession((compile ? hosted : compiled).getAbsolutePath(), options);
        } catch (OrtException | RuntimeException e) {
            compiled.delete();
            throw e;
        }
    }

    private static void remember(File failed, Exception e) {
        try (java.io.FileWriter w = new java.io.FileWriter(failed)) {
            w.write(String.valueOf(e));
        } catch (IOException ignored) {
            // Tried again next load.
        }
    }

    /** The hosted fixed-window decoder for voices with no INT8 one. */
    private void attachHostedDecoder(File onnx, File hosted) {
        if (!hasHosted(hosted)) {
            group.npuProblem = "on the CPU (no NPU decoder for this voice)";
            return;
        }
        if (awaitingSource(onnx, hosted, compiledFile(onnx, hosted))) {
            group.npuProblem = "on the CPU until its NPU file downloads again";
            return;
        }
        final File failed = failedFile(onnx, hosted);
        if (failed.exists()) {
            group.npuProblem = "on the CPU (NPU decoder failed before)";
            return;
        }
        OrtSession npu = null;
        try {
            npu = hostedNpuSession(onnx, hosted);
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
            group.npuKind = "FP16";
            group.npuProblem = null;
            group.npuDecoder = npu;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            group.npuProblem = "on the CPU (NPU failed: " + e + ")";
            remember(failed, e);
        }
    }

    /**
     * The hosted encoder parts: text encoder and flow on the NPU, the middle on
     * the CPU. Used only if they hand the decoder exactly what this voice's own
     * encoder does (names checked); else the CPU encoder stays, remembered.
     */
    private void attachNpuEncoder(File onnx) {
        final File dir = onnx.getParentFile();
        final File encFile = new File(dir, NPU_ENC_FILE);
        final File midFile = new File(dir, NPU_MID_FILE);
        final File flowFile = new File(dir, NPU_FLOW_FILE);
        if (!hasHosted(encFile) || !hasHosted(midFile) || !hasHosted(flowFile)) {
            return;
        }
        final File failed = failedFile(onnx, encFile);
        if (failed.exists()) {
            return;
        }
        if (awaitingSource(onnx, encFile, compiledFile(onnx, encFile))
                || awaitingSource(onnx, flowFile, compiledFile(onnx, flowFile))
                || awaitingSource(onnx, midFile, compiledOf(midFile))) {
            return; // the CPU encoder speaks until the files download again
        }
        OrtSession enc = null;
        OrtSession mid = null;
        OrtSession flow = null;
        try {
            enc = hostedNpuSession(onnx, encFile);
            flow = hostedNpuSession(onnx, flowFile);
            // Its optimized ORT-format copy, made at the first load, opens in a
            // fraction of the time (the .onnx took 110-260 ms every load).
            final File midOrt = compiledOf(midFile);
            if (midOrt.isFile()) {
                final ByteBuffer midBytes = map(midOrt);
                mid = open(null, midBytes, group.threads);
                group.mappedMid = midBytes; // ONNX Runtime reads the weights from it
            } else {
                final File partial = new File(midOrt.getParentFile(), midOrt.getName() + ".tmp");
                try (OrtSession.SessionOptions o = options(group.threads)) {
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                    o.setOptimizedModelFilePath(partial.getAbsolutePath());
                    o.addConfigEntry("session.save_model_format", "ORT");
                    mid = env().createSession(midFile.getAbsolutePath(), o);
                }
                if (!partial.renameTo(midOrt)) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                }
            }
            final java.util.Set<String> midOut = mid.getOutputNames();
            if (!flow.getOutputNames().contains(group.boundary[0])) {
                throw new IOException("flow makes " + flow.getOutputNames() + ", not " + group.boundary[0]);
            }
            for (int i = 1; i < group.boundary.length; i++) {
                if (!midOut.contains(group.boundary[i])) {
                    throw new IOException("no " + group.boundary[i] + " from " + midOut);
                }
            }
            if (!mid.getInputNames().containsAll(enc.getOutputNames())
                    || !midOut.containsAll(flow.getInputNames())) {
                throw new IOException("parts do not join");
            }
            final long[] ids = ((ai.onnxruntime.TensorInfo) enc.getInputInfo().get("input").getInfo()).getShape();
            int frames = 0;
            for (ai.onnxruntime.NodeInfo n : flow.getInputInfo().values()) {
                final long[] shape = ((ai.onnxruntime.TensorInfo) n.getInfo()).getShape();
                frames = Math.max(frames, (int) shape[shape.length - 1]);
            }
            group.npuEncIds = (int) ids[1];
            group.npuFlowFrames = frames;
            group.npuMid = mid;
            group.npuFlow = flow;
            group.npuEnc = enc;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(enc);
            closeQuietly(mid);
            closeQuietly(flow);
            android.util.Log.w("PiperVoices", "Natural voice " + config.key + " encoder stays on the CPU: " + e);
            remember(failed, e);
        }
    }

    /**
     * The downloaded INT8 decoder on the NPU. Its graph is already fixed-size
     * and quantized, so QNN only compiles it (cached beside the model, keyed
     * by the file's size so an updated file recompiles).
     */
    private boolean attachInt8Npu(File onnx, File int8) {
        final File failed = derivedFile(onnx, ".npuq-r3." + hostedSize(int8) + ".failed");
        if (failed.exists()) {
            return false;
        }
        final File compiled = compiledOf(int8);
        if (awaitingSource(onnx, int8, compiled)) {
            group.npuProblem = "on the CPU until its NPU file downloads again";
            return false;
        }
        OrtSession npu = null;
        try {
            final boolean compile = !compiled.isFile();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                final Map<String, String> qnn = new HashMap<>();
                qnn.put("backend_path", npuBackend());
                qnn.put("htp_performance_mode", "burst");
                // The latent's own quantize step runs on the NPU too.
                qnn.put("offload_graph_io_quantization", "1");
                addNpu(options, qnn);
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
            group.npuProblem = null;
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

    /** True when the decoder runs on Qualcomm's NPU. */
    boolean onNpu() {
        return group.npuDecoder != null;
    }

    /** "INT8" or "FP16" when on the NPU, else null. */
    String npuKind() {
        return group.npuDecoder != null ? group.npuKind : null;
    }

    /** For the load log: where the decoder (and encoder) run, or why not on the NPU. */
    String npuState() {
        return onNpu() ? "on the NPU (" + npuKind() + ")" + (group.npuEnc != null ? ", encoder too" : "")
                : npuProblem();
    }

    /** Why a build with the NPU runtime isn't using it for this voice, or null. */
    String npuProblem() {
        return group.npuProblem;
    }

    /** The process's ONNX Runtime environment. */
    private static OrtEnvironment env() {
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        sRuntimeVersion = env.getVersion();
        return env;
    }

    private static volatile String sRuntimeVersion;

    /**
     * The ONNX Runtime version, once a voice has loaded it in this process;
     * null before (asking would load the runtime, which natural voices off
     * must never do).
     */
    static String loadedRuntimeVersion() {
        return sRuntimeVersion;
    }

    /** model.onnx -> model.<ONNX Runtime version><suffix>: a runtime update re-optimizes. */
    private static File derivedFile(File onnx, String suffix) {
        return new File(onnx.getParentFile(), OPTIMIZED_PREFIX + env().getVersion() + suffix);
    }

    /**
     * File in the shared directory holding the pre-optimized model, if this
     * voice shares its model weights with others (e.g. AI4Bharat Rasa voices).
     */
    static File sharedDerivedFile(File onnx, String suffix) {
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

    static void closeQuietly(OrtSession s) {
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

    static OrtSession.SessionOptions options(int threads) throws OrtException {
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
        if (group.supertonic != null) {
            infer(supertonicIds("a"), config.lengthScale, 0, 1f, 1f, null);
            return;
        }
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
        if (group.supertonic != null) {
            return inferSupertonic(ids, lengthScale, handle);
        }
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
                try (OrtSession.Result r = group.cpuSession().run(inputs,
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
     * A Supertonic chunk: speed from the length scale, noise seeded by the
     * text (the same chunk reads the same way each time).
     */
    private Output inferSupertonic(long[] ids, float lengthScale, RunHandle handle) throws OrtException {
        group.lock.readLock().lock();
        try {
            final Supertonic.Style s = style;
            if (closed || group.isClosed() || s == null || (handle != null && handle.isCancelled())) {
                return null;
            }
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                final float speed = config.lengthScale / Math.max(0.05f, lengthScale);
                final float[] audio = group.supertonic.synthesize(ids, s, config.supertonicSteps, speed,
                        Arrays.hashCode(ids), handle != null ? handle.options : ownOptions);
                return audio == null ? null : new Output(audio, null);
            } catch (OrtException e) {
                if (handle != null && handle.isCancelled()) {
                    return null;
                }
                throw e;
            } finally {
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
            final OrtSession npuEnc = group.npuEnc;
            if (npuEnc != null && ids.length <= group.npuEncIds && group.boundaryShapes != null) {
                try {
                    final Encoded e = encodeOnNpu(npuEnc, ids, lengthScale, speakerId, noise, noiseW, handle);
                    if (e != null) {
                        return e;
                    }
                } catch (OrtException | RuntimeException ex) {
                    if (handle == null || !handle.isCancelled()) {
                        retireNpu(npuEnc, ex);
                    }
                }
                if (handle != null && handle.isCancelled()) {
                    return null;
                }
            }
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
            try {
                putInputs(env, inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = group.cpuSession().run(inputs,
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
     * An NPU part failed while speaking (the DSP restarted, a graph it cannot
     * run after all): the voice goes on with its CPU part until it loads
     * again, instead of every request after it failing. Its run is redone on
     * the CPU by the caller.
     */
    private void retireNpu(OrtSession failed, Exception why) {
        synchronized (group) {
            if (group.npuDecoder == failed) {
                group.npuDecoder = null;
                group.npuProblem = "on the CPU (NPU stopped working: " + why + ")";
            } else if (group.npuEnc == failed) {
                group.npuEnc = null; // the gate; its middle and flow parts close with the group
            } else {
                return; // another run retired it already
            }
            group.retired.add(failed);
            group.decodeSpeed = 0; // measured again on the CPU before pieces rely on it
        }
        android.util.Log.w("PiperVoices", "Natural voice " + config.key
                + " NPU part failed while speaking, the CPU takes over: " + why);
    }

    /** A float tensor's values and shape. */
    private static final class Tensor {
        final float[] data;
        final long[] shape;

        Tensor(float[] data, long[] shape) {
            this.data = data;
            this.shape = shape;
        }

        /** The first {@code n} along the last axis (or along axis 1 when that one is {@code from} long). */
        Tensor crop(int from, int n) {
            final int axis = shape[shape.length - 1] == from ? shape.length - 1
                    : shape.length > 1 && shape[1] == from ? 1 : -1;
            if (axis < 0) {
                return this;
            }
            long outer = 1;
            for (int i = 0; i < axis; i++) {
                outer *= shape[i];
            }
            long inner = 1;
            for (int i = axis + 1; i < shape.length; i++) {
                inner *= shape[i];
            }
            final long[] s = shape.clone();
            s[axis] = n;
            final float[] out = new float[(int) (outer * n * inner)];
            for (int o = 0; o < outer; o++) {
                System.arraycopy(data, (int) (o * from * inner), out, (int) (o * n * inner), (int) (n * inner));
            }
            return new Tensor(out, s);
        }

        /** Zero-padded along the last axis to {@code to}. */
        Tensor pad(int to) {
            final int last = (int) shape[shape.length - 1];
            final long[] s = shape.clone();
            s[s.length - 1] = to;
            final int rows = data.length / last;
            final float[] out = new float[rows * to];
            for (int r = 0; r < rows; r++) {
                System.arraycopy(data, r * last, out, r * to, last);
            }
            return new Tensor(out, s);
        }
    }

    private static Map<String, Tensor> results(OrtSession.Result r) throws OrtException {
        final Map<String, Tensor> out = new HashMap<>();
        for (Map.Entry<String, OnnxValue> e : r) {
            final float[] f = floats(e.getValue());
            if (f == null) {
                throw new OrtException("not a float tensor: " + e.getKey());
            }
            out.put(e.getKey(), new Tensor(f, ((OnnxTensor) e.getValue()).getInfo().getShape()));
        }
        return out;
    }

    /**
     * The encoder as the hosted parts run it: ids padded to the text encoder's
     * size, its outputs cropped back, the middle part on the CPU (durations,
     * length regulator, noise), the latent padded to the flow's size and
     * cropped back. Null when the chunk's frames outgrow the flow (the CPU
     * encoder then takes the chunk).
     */
    private Encoded encodeOnNpu(OrtSession npuEnc, long[] ids, float lengthScale, int speakerId, float noise,
                                float noiseW, RunHandle handle) throws OrtException {
        final OrtEnvironment env = env();
        final int n = ids.length;
        final int bucket = group.npuEncIds;
        final OrtSession.RunOptions ownOptions = handle == null ? runOptions() : null;
        final OrtSession.RunOptions run = handle != null ? handle.options : ownOptions;
        final Map<String, OnnxTensor> in = new HashMap<>();
        try {
            final long[] padded = Arrays.copyOf(ids, bucket);
            in.put("input", OnnxTensor.createTensor(env, LongBuffer.wrap(padded), new long[] {1, bucket}));
            in.put("input_lengths", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {n}), new long[] {1}));
            final Map<String, Tensor> enc;
            try (OrtSession.Result r = npuEnc.run(in, run)) {
                enc = results(r);
            }
            closeAll(in);
            putInputs(env, in, ids, lengthScale, speakerId, noise, noiseW);
            final java.util.Set<String> midNames = group.npuMid.getInputNames();
            in.keySet().retainAll(midNames); // inputs it does not read are dropped (and closed below)
            for (Map.Entry<String, Tensor> e : enc.entrySet()) {
                final Tensor t = e.getValue().crop(bucket, n);
                in.put(e.getKey(), OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), t.shape));
            }
            final Map<String, Tensor> mid;
            try (OrtSession.Result r = group.npuMid.run(in, run)) {
                mid = results(r);
            }
            closeAll(in);
            int frames = 0;
            for (String name : group.npuFlow.getInputNames()) {
                final long[] shape = mid.get(name).shape;
                frames = Math.max(frames, (int) shape[shape.length - 1]);
            }
            if (frames > group.npuFlowFrames) {
                return null;
            }
            for (String name : group.npuFlow.getInputNames()) {
                Tensor t = mid.get(name);
                if (t.shape[t.shape.length - 1] == frames && frames > 1) {
                    t = t.pad(group.npuFlowFrames);
                }
                in.put(name, OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), t.shape));
            }
            final Tensor latent;
            try (OrtSession.Result r = group.npuFlow.run(in, run)) {
                final Map<String, Tensor> out = results(r);
                latent = out.get(group.boundary[0]).crop(group.npuFlowFrames, frames);
            }
            final float[][] tensors = new float[group.boundary.length][];
            final long[][] shapes = new long[group.boundary.length][];
            tensors[0] = latent.data;
            shapes[0] = latent.shape;
            for (int i = 1; i < group.boundary.length; i++) {
                final Tensor t = mid.get(group.boundary[i]);
                tensors[i] = t.data;
                shapes[i] = t.shape;
            }
            // The durations are the middle part's last output (build_npu_enc.py).
            final String[] midOut = group.npuMid.getOutputNames().toArray(new String[0]);
            final Tensor durations = mid.get(midOut[midOut.length - 1]);
            return new Encoded(tensors, shapes,
                    durations != null && durations.data.length == n ? durations.data : null);
        } catch (OrtException e) {
            if (handle != null && handle.isCancelled()) {
                return null;
            }
            throw e;
        } finally {
            closeAll(in);
            if (ownOptions != null) {
                ownOptions.close();
            }
        }
    }

    private static void closeAll(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor t : tensors.values()) {
            t.close();
        }
        tensors.clear();
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
            final OrtSession npuDecoder = group.npuDecoder;
            if (npuDecoder != null) {
                try {
                    return decodeOnNpu(npuDecoder, e, from, to, handle);
                } catch (OrtException | RuntimeException ex) {
                    if (handle != null && handle.isCancelled()) {
                        return null;
                    }
                    retireNpu(npuDecoder, ex);
                }
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
    private float[] decodeOnNpu(OrtSession npuDecoder, Encoded e, int from, int to, RunHandle handle)
            throws OrtException {
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
                    try (OrtSession.Result r = npuDecoder.run(inputs,
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


