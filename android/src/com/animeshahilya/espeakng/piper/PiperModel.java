/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;
import com.animeshahilya.espeakng.text.Tashkeel;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
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
    static final String OPTIMIZED_PREFIX = "model.";
    /** One whole model, for a voice that can't be split (".opt.ort" before the split: rebuilt). */
    static final String OPTIMIZED_SUFFIX = ".whole.ort";
    static final String ENCODER_SUFFIX = ".enc.ort";
    static final String DECODER_SUFFIX = ".dec.ort";
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
    static final String SOURCE_FILE = "source";
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
    /** Which model bytes these are ({@link #ModelFiles.stamp(File)}): the original may be gone. */
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
        /** Phonon voices: one runtime for every installed Phonon voice (PhononBundle). */
        final Phonon phonon;

        SessionGroup(Phonon phonon) {
            this.phonon = phonon;
            this.supertonic = null;
            this.session = null;
            this.mapped = null;
            this.decoder = null;
            this.mappedDecoder = null;
            this.hasSpeakerInput = false;
            this.boundary = new String[0];
            this.hasDurations = false;
        }

        SessionGroup(Supertonic supertonic) {
            this.supertonic = supertonic;
            this.phonon = null;
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
            this.phonon = null;
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
                if (phonon != null) {
                    phonon.close();
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
        this.stamp = ModelFiles.stamp(file);
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

    /** True for a Phonon voice (text in, its own runtime on the NPU). */
    boolean isPhonon() {
        return group.phonon != null;
    }

    /** True when this voice's Phonon runtime has {@code voice} (one loaded before it was installed may not). */
    boolean phononHas(String voice) {
        return group.phonon != null && Arrays.asList(group.phonon.voices()).contains(voice);
    }

    /** A chunk's text for Phonon, carried as code points through the id-based pipeline. */
    static long[] phononIds(String text) {
        return text.codePoints().asLongStream().toArray();
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
        if (config.isPhonon()) {
            final Phonon runtime;
            try {
                runtime = PhononBundle.load(onnx.getParentFile().getParentFile());
            } catch (IOException e) {
                throw new OrtException("Phonon: " + e.getMessage());
            }
            final PiperModel model = new PiperModel(config, onnx, new SessionGroup(runtime));
            try {
                model.warmUp();
            } catch (OrtException | RuntimeException e) {
                model.close();
                throw e;
            }
            return model;
        }
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
        final File optimized = ModelFiles.derivedFile(onnx, OPTIMIZED_SUFFIX);
        final File encoder = ModelFiles.derivedFile(onnx, ENCODER_SUFFIX);
        final File decoderFile = ModelFiles.derivedFile(onnx, DECODER_SUFFIX);
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile())) {
            final File sharedEnc = ModelFiles.sharedDerivedFile(onnx, ENCODER_SUFFIX);
            final File sharedDec = ModelFiles.sharedDerivedFile(onnx, DECODER_SUFFIX);
            final File sharedOpt = ModelFiles.sharedDerivedFile(onnx, OPTIMIZED_SUFFIX);
            if (sharedEnc != null && sharedEnc.isFile() && sharedDec != null && sharedDec.isFile()) {
                PiperDownloads.linkOrCopy(sharedEnc, encoder);
                PiperDownloads.linkOrCopy(sharedDec, decoderFile);
            } else if (sharedOpt != null && sharedOpt.isFile()) {
                PiperDownloads.linkOrCopy(sharedOpt, optimized);
            }
        }
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile()) && !onnx.isFile()) {
            ModelFiles.adoptOlderOptimized(onnx, optimized, encoder, decoderFile);
        }
        if (!optimized.isFile() && !(encoder.isFile() && decoderFile.isFile()) && onnx.isFile()) {
            ModelFiles.buildOptimized(onnx, optimized, encoder, decoderFile, threads);
        }
        OrtSession session = null;
        ByteBuffer mapped = null;
        OrtSession decoder = null;
        ByteBuffer mappedDecoder = null;
        // NPU parts compiled and shapes known: the NPU encoder warms the voice
        // up and the CPU encoder opens only when a chunk needs it.
        final NpuFiles.Boundary known = encoder.isFile() && decoderFile.isFile() && NpuFiles.npuReady(onnx)
                ? NpuFiles.Boundary.read(NpuFiles.boundaryFile(onnx), encoder) : null;
        if (encoder.isFile() && decoderFile.isFile()) {
            try {
                mapped = ModelFiles.map(encoder);
                if (known == null) {
                    session = open(null, mapped, threads);
                }
                mappedDecoder = ModelFiles.map(decoderFile);
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
                mapped = ModelFiles.map(optimized);
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
            if (NpuFiles.hasNpuRuntime() && group.boundaryShapes != null) {
                NpuFiles.Boundary.write(NpuFiles.boundaryFile(onnx), encoder, group);
            }
            model.attachNpu(onnx); // compiled graphs load in ~0.15 s
        }
        if (mapped != null) {
            ModelFiles.dropOriginal(onnx);
        }
        return model;
    }

    /**
     * Moves the decoder (and the encoder's two NPU parts) to Qualcomm's NPU
     * when this build and phone have it, from the graphs compiled for this
     * chip on the PC and downloaded (PiperDownloads.fetchNpuDecoders); until
     * they are here the CPU speaks. Any failure keeps the CPU part and is
     * remembered until a new file is published for the voice.
     */
    private void attachNpu(File onnx) {
        if (!NpuFiles.hasNpuRuntime() || group.boundaryShapes == null) {
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
        if (!(NpuFiles.hasHosted(int8) && attachInt8Npu(onnx, int8))) {
            attachHostedDecoder(onnx, dec16);
        }
        attachNpuEncoder(onnx);
        if (group.npuDecoder != null) {
            NpuFiles.dropHostedSources(int8, dec16);
        }
        if (group.npuEnc != null) {
            NpuFiles.dropHostedSources(new File(dir, NPU_ENC_FILE), new File(dir, NPU_MID_FILE),
                    new File(dir, NPU_FLOW_FILE));
        }
    }

    /**
     * Takes {@code npu} as the decoder once it reads exactly what this model's
     * encoder hands the decoder, runs, and is fast enough.
     */
    private void useNpuDecoder(OrtSession npu, String kind) throws IOException, OrtException {
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
            closeAll(inputs);
        }
        group.npuFrames = (int) latent[2];
        group.npuKind = kind;
        group.npuProblem = null;
        group.npuDecoder = npu;
    }

    /** The hosted fixed-window decoder for voices with no INT8 one. */
    private void attachHostedDecoder(File onnx, File hosted) {
        if (!NpuFiles.hasHosted(hosted)) {
            group.npuProblem = "on the CPU (no NPU decoder for this voice)";
            return;
        }
        final File compiled = NpuFiles.compiledFile(onnx, hosted);
        if (!compiled.isFile()) {
            group.npuProblem = "on the CPU until its NPU files download";
            return;
        }
        final File failed = NpuFiles.failedFile(onnx, hosted);
        if (failed.exists()) {
            group.npuProblem = "on the CPU (NPU decoder failed before)";
            return;
        }
        OrtSession npu = null;
        try {
            npu = NpuFiles.npuSession(compiled, true);
            useNpuDecoder(npu, "FP16");
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            group.npuProblem = "on the CPU (NPU failed: " + e + ")";
            NpuFiles.remember(failed, e);
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
        if (!NpuFiles.hasHosted(encFile) || !NpuFiles.hasHosted(midFile) || !NpuFiles.hasHosted(flowFile)) {
            return;
        }
        final File failed = NpuFiles.failedFile(onnx, encFile);
        if (failed.exists()) {
            return;
        }
        if (!NpuFiles.compiledFile(onnx, encFile).isFile() || !NpuFiles.compiledFile(onnx, flowFile).isFile()
                || !NpuFiles.compiledOf(midFile).isFile() && !midFile.isFile()) {
            return; // the CPU encoder speaks until the files download
        }
        OrtSession enc = null;
        OrtSession mid = null;
        OrtSession flow = null;
        try {
            enc = NpuFiles.npuSession(NpuFiles.compiledFile(onnx, encFile), true);
            flow = NpuFiles.npuSession(NpuFiles.compiledFile(onnx, flowFile), true);
            // Its optimized ORT-format copy, made at the first load, opens in a
            // fraction of the time (the .onnx took 110-260 ms every load).
            final File midOrt = NpuFiles.compiledOf(midFile);
            if (midOrt.isFile()) {
                final ByteBuffer midBytes = ModelFiles.map(midOrt);
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
            NpuFiles.remember(failed, e);
        }
    }

    /** The voice's INT8 decoder on the NPU, compiled for this chip on the PC. */
    private boolean attachInt8Npu(File onnx, File int8) {
        final File failed = NpuFiles.int8FailedFile(onnx, int8);
        if (failed.exists()) {
            return false;
        }
        final File compiled = NpuFiles.compiledOf(int8);
        if (!NpuFiles.compiledAvailable(int8)) {
            group.npuProblem = "on the CPU until its NPU files download";
            return false;
        }
        OrtSession npu = null;
        try {
            npu = NpuFiles.npuSession(compiled, false);
            useNpuDecoder(npu, "INT8");
            return true;
        } catch (IOException | OrtException | RuntimeException e) {
            closeQuietly(npu);
            NpuFiles.remember(failed, e);
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
    static OrtEnvironment env() {
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

    static void closeQuietly(OrtSession s) {
        if (s != null) {
            try {
                s.close();
            } catch (OrtException ignored) {
                // Nothing more to release.
            }
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
        if (group.phonon != null) {
            infer(phononIds("Hello."), config.lengthScale, 0, 1f, 1f, null);
            return;
        }
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

    private static boolean cancelled(RunHandle handle) {
        return handle != null && handle.isCancelled();
    }

    /** ONNX Runtime work, given the options to run with. */
    private interface Run<T> {
        T with(OrtSession.RunOptions options) throws OrtException;
    }

    /**
     * Runs with the handle's options, so stop() can end it, or with options of
     * its own when nobody can stop it; work stop() ended comes back null.
     * Closes {@code inputs} (if any) afterwards.
     */
    private static <T> T cancellable(RunHandle handle, Map<String, OnnxTensor> inputs, Run<T> run)
            throws OrtException {
        final OrtSession.RunOptions own = handle == null ? runOptions() : null;
        try {
            return run.with(handle != null ? handle.options : own);
        } catch (OrtException e) {
            if (cancelled(handle)) {
                return null; // RunOptions.setTerminate() surfaces as an exception
            }
            throw e;
        } finally {
            if (inputs != null) {
                closeAll(inputs);
            }
            if (own != null) {
                own.close();
            }
        }
    }

    /** Folds a decode's real-time factor into the voice's running estimate. */
    private void noteDecodeSpeed(int samples, long startedNanos) {
        final double speed = samples / (double) config.sampleRate
                / Math.max(1e-6, (System.nanoTime() - startedNanos) / 1e9);
        group.decodeSpeed = group.decodeSpeed == 0 ? speed : 0.7 * group.decodeSpeed + 0.3 * speed;
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
        if (group.phonon != null) {
            return inferPhonon(ids, handle);
        }
        if (group.decoder != null) {
            final Encoded e = encode(ids, lengthScale, speakerId, noise, noiseW, handle);
            final float[] audio = e == null ? null : decode(e, 0, e.frames, handle);
            return audio == null ? null : new Output(audio, e.durations);
        }
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || cancelled(handle)) {
                return null;
            }
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            return cancellable(handle, inputs, run -> {
                putInputs(env(), inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = group.cpuSession().run(inputs, run)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    final float[] durations = group.hasDurations ? floats(r.get(1)) : null;
                    return new Output(audio, durations != null && durations.length == ids.length
                            ? durations : null);
                }
            });
        } finally {
            group.lock.readLock().unlock();
        }
    }

    /**
     * A Phonon chunk as {@link #inferPhonon}, each 80 ms frame handed to
     * {@code frames} as it is made.
     *
     * @return true when the whole chunk was made, false when stopped
     */
    boolean streamPhonon(long[] ids, RunHandle handle, Phonon.Frames frames) throws OrtException {
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || cancelled(handle)) {
                return false;
            }
            return group.phonon.synthesize(phononText(ids), config.phononVoice, () -> cancelled(handle), frames);
        } catch (IOException e) {
            throw new OrtException("Phonon: " + e.getMessage());
        } finally {
            group.lock.readLock().unlock();
        }
    }

    private static String phononText(long[] ids) {
        final int[] cps = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            cps[i] = (int) ids[i];
        }
        return new String(cps, 0, cps.length);
    }

    /** A Phonon chunk: the text in this voice; speed and pitch are libsonic's (no rate input). */
    private Output inferPhonon(long[] ids, RunHandle handle) throws OrtException {
        group.lock.readLock().lock();
        try {
            if (closed || group.isClosed() || cancelled(handle)) {
                return null;
            }
            final float[] audio = group.phonon.synthesize(phononText(ids), config.phononVoice,
                    () -> cancelled(handle));
            return audio == null ? null : new Output(audio, null);
        } catch (IOException e) {
            throw new OrtException("Phonon: " + e.getMessage());
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
            if (closed || group.isClosed() || s == null || cancelled(handle)) {
                return null;
            }
            final float speed = config.lengthScale / Math.max(0.05f, lengthScale);
            return cancellable(handle, null, run -> {
                final float[] audio = group.supertonic.synthesize(ids, s, config.supertonicSteps, speed,
                        Arrays.hashCode(ids), run);
                return audio == null ? null : new Output(audio, null);
            });
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
            if (closed || group.isClosed() || cancelled(handle)) {
                return null;
            }
            final OrtSession npuEnc = group.npuEnc;
            if (npuEnc != null && ids.length <= group.npuEncIds && group.boundaryShapes != null) {
                try {
                    final Encoded e = encodeOnNpu(npuEnc, ids, lengthScale, speakerId, noise, noiseW, handle);
                    if (e != null) {
                        return e;
                    }
                } catch (OrtException | RuntimeException ex) {
                    if (!cancelled(handle)) {
                        retireNpu(npuEnc, ex);
                    }
                }
                if (cancelled(handle)) {
                    return null;
                }
            }
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            return cancellable(handle, inputs, run -> {
                putInputs(env(), inputs, ids, lengthScale, speakerId, noise, noiseW);
                try (OrtSession.Result r = group.cpuSession().run(inputs, run)) {
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
            });
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
        final Map<String, OnnxTensor> in = new HashMap<>();
        return cancellable(handle, in, run -> {
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
        });
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
            if (closed || group.isClosed() || cancelled(handle)) {
                return null;
            }
            final OrtSession npuDecoder = group.npuDecoder;
            if (npuDecoder != null) {
                try {
                    return decodeOnNpu(npuDecoder, e, from, to, handle);
                } catch (OrtException | RuntimeException ex) {
                    if (cancelled(handle)) {
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
            final Map<String, OnnxTensor> inputs = new HashMap<>();
            return cancellable(handle, inputs, run -> {
                putDecoderInputs(inputs, e, start, span);
                final long started = System.nanoTime();
                try (OrtSession.Result r = group.decoder.run(inputs, run)) {
                    final float[] audio = floats(r.get(0));
                    if (audio == null) {
                        return null;
                    }
                    noteDecodeSpeed(audio.length, started);
                    final int hop = Math.max(1, audio.length / span);
                    final int fromIdx = Math.max(0, Math.min(audio.length, (from - start) * hop));
                    final int toIdx = Math.max(fromIdx, Math.min(audio.length, audio.length - (end - to) * hop));
                    return Arrays.copyOfRange(audio, fromIdx, toIdx);
                }
            });
        } finally {
            group.lock.readLock().unlock();
        }
    }

    /**
     * The decoder's inputs: latent frames [start, start+span) (zeros past the
     * chunk's end) and the encoder's other outputs as they are.
     */
    private void putDecoderInputs(Map<String, OnnxTensor> inputs, Encoded e, int start, int span)
            throws OrtException {
        final int have = Math.min(span, e.frames - start);
        final float[] latent = new float[e.channels * span];
        for (int c = 0; c < e.channels; c++) {
            System.arraycopy(e.tensors[0], c * e.frames + start, latent, c * span, have);
        }
        final OrtEnvironment env = env();
        inputs.put(group.boundary[0], OnnxTensor.createTensor(env, FloatBuffer.wrap(latent),
                new long[] {1, e.channels, span}));
        for (int i = 1; i < group.boundary.length; i++) {
            inputs.put(group.boundary[i], OnnxTensor.createTensor(env, FloatBuffer.wrap(e.tensors[i]),
                    e.shapes[i]));
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
        return cancellable(handle, null, run -> {
            final List<float[]> parts = new ArrayList<>();
            int total = 0;
            final long started = System.nanoTime();
            for (int s = from; s < to; s += step) {
                if (cancelled(handle)) {
                    return null;
                }
                final int len = Math.min(step, to - s);
                final int ws = Math.max(0, Math.min(s - DECODE_OVERLAP, e.frames - frames));
                final Map<String, OnnxTensor> inputs = new HashMap<>();
                try {
                    putDecoderInputs(inputs, e, ws, frames);
                    try (OrtSession.Result r = npuDecoder.run(inputs, run)) {
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
                    closeAll(inputs);
                }
            }
            final float[] out = new float[total];
            int at = 0;
            for (float[] p : parts) {
                System.arraycopy(p, 0, out, at, p.length);
                at += p.length;
            }
            noteDecodeSpeed(total, started);
            return out;
        });
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

