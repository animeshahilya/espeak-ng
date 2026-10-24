/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.animeshahilya.espeakng.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qualcomm's NPU for natural voices (Snapdragon build): the QNN plugin and its sessions, and the files around them - hosted NPU graphs, what they compile into, their sizes and failure markers.
 */
public final class NpuFiles {
    private NpuFiles() {
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
                        PiperModel.env().registerExecutionProviderLibrary(QNN_EP, lib.getAbsolutePath());
                        for (ai.onnxruntime.OrtEpDevice d : PiperModel.env().getEpDevices()) {
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

    /**
     * The Snapdragons whose NPU graphs are compiled for the app on the PC
     * (sherpa-onnx-respin-syspin build_npu_ctx.py): 8 Gen 3, 8 Elite, 8 Elite
     * Gen 5. The phone compiles nothing - the APK leaves QNN's compiler out
     * (35 MB) - so any other chip runs natural voices on the CPU.
     */
    static final String[] TARGETS = {"SM8650", "SM8750", "SM8850"};

    /** This phone's compile target, or null when its chip has no compiled graphs. */
    public static String target() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                ? targetFor(android.os.Build.SOC_MANUFACTURER, android.os.Build.SOC_MODEL) : null;
    }

    static String targetFor(String manufacturer, String socModel) {
        if (!"QTI".equalsIgnoreCase(manufacturer) || socModel == null) {
            return null;
        }
        for (String t : TARGETS) {
            if (socModel.toUpperCase(java.util.Locale.ROOT).startsWith(t)) {
                return t;
            }
        }
        return null;
    }

    /** True when the NPU runtime is here: the Snapdragon build on a chip with compiled graphs. */
    static boolean hasNpuRuntime() {
        return target() != null && !npuDevices().isEmpty();
    }

    /** Puts the session on the NPU through the QNN plugin with these options. */
    static void addNpu(OrtSession.SessionOptions options, Map<String, String> qnn) throws OrtException {
        options.addExecutionProvider(npuDevices(), qnn);
    }

    /**
     * True when every hosted NPU part of the voice is there and compiled, with
     * no failure marked: it can load without opening its CPU encoder.
     */
    static boolean npuReady(File onnx) {
        if (!hasNpuRuntime()) {
            return false;
        }
        final File dir = onnx.getParentFile();
        final File enc = new File(dir, PiperModel.NPU_ENC_FILE);
        final File flow = new File(dir, PiperModel.NPU_FLOW_FILE);
        final File mid = new File(dir, PiperModel.NPU_MID_FILE);
        if (!hasHosted(mid) || !hasHosted(enc) || !hasHosted(flow)
                || failedFile(onnx, enc).exists() || !compiledAvailable(mid)
                || !compiledFile(onnx, enc).isFile() || !compiledFile(onnx, flow).isFile()) {
            return false;
        }
        final File int8 = new File(dir, PiperModel.NPU_DECODER_FILE);
        if (hasHosted(int8)) {
            return compiledAvailable(int8) && !int8FailedFile(onnx, int8).exists();
        }
        final File dec = new File(dir, PiperModel.NPU_DECODER16_FILE);
        return compiledAvailable(dec) && !failedFile(onnx, dec).exists();
    }

    static File boundaryFile(File onnx) {
        return ModelFiles.derivedFile(onnx, ".boundary");
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

        static void write(File file, File encoder, PiperModel.SessionGroup g) {
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
        final String stem = PiperModel.NPU_DECODER_FILE.equals(hosted.getName()) ? "npuq"
                : hosted.getName().replace(".onnx", "");
        //noinspection ResultOfMethodCallIgnored
        sizeFile(hosted).delete();
        final File[] made = hosted.getParentFile().listFiles((d, n) -> n.startsWith(PiperModel.OPTIMIZED_PREFIX)
                && (n.contains("." + stem + ".") || n.contains("." + stem + "-r")));
        if (made != null) {
            for (File f : made) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Where a hosted NPU file's compiled graph is kept: keyed by its size, so an update recompiles. */
    static File compiledFile(File onnx, File hosted) {
        return ModelFiles.derivedFile(onnx, "." + hosted.getName().replace(".onnx", "") + "." + hostedSize(hosted) + ".ctx.onnx");
    }

    /*
     * Failure markers carry a revision: bump it when a cause is fixed, so
     * phones try again. r2/r4 (2026-10-24): a wrapper fetched before its
     * _qnn.bin made a voice loading meanwhile fail for good.
     */
    static File failedFile(File onnx, File hosted) {
        return ModelFiles.derivedFile(onnx, "." + hosted.getName().replace(".onnx", "") + "-r2." + hostedSize(hosted) + ".failed");
    }

    static File int8FailedFile(File onnx, File int8) {
        return ModelFiles.derivedFile(onnx, ".npuq-r4." + hostedSize(int8) + ".failed");
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

    /** Records a hosted file's size without the file: its compiled graphs are downloaded instead. */
    static void recordHostedSize(File hosted, long size) throws IOException {
        java.nio.file.Files.write(sizeFile(hosted).toPath(),
                String.valueOf(size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static File sizeFile(File hosted) {
        return new File(hosted.getParentFile(), hosted.getName() + ".size");
    }

    /** What this runtime made from a hosted NPU file: compiled graph, INT8 context or ORT copy. */
    static File compiledOf(File hosted) {
        final File onnx = new File(hosted.getParentFile(), "model.onnx");
        if (PiperModel.NPU_DECODER_FILE.equals(hosted.getName())) {
            return ModelFiles.derivedFile(onnx, ".npuq." + hostedSize(hosted) + ".onnx");
        }
        if (PiperModel.NPU_MID_FILE.equals(hosted.getName())) {
            return ModelFiles.derivedFile(onnx, ".npumid." + hostedSize(hosted) + ".ort");
        }
        return compiledFile(onnx, hosted);
    }

    /**
     * True when this runtime's compiled form of a hosted file is here, so the
     * file itself is not needed (PiperDownloads fetches it again otherwise).
     * A compiled graph is its .onnx wrapper and the _qnn.bin weights it names.
     */
    static boolean compiledAvailable(File hosted) {
        final File compiled = compiledOf(hosted);
        return hasHosted(hosted) && compiled.isFile() && (!compiled.getName().endsWith(".onnx")
                || new File(compiled.getParentFile(),
                compiled.getName().replaceFirst("\\.onnx$", "_qnn.bin")).isFile());
    }

    /** Deletes hosted files whose compiled forms loaded, keeping their sizes. */
    static void dropHostedSources(File... hosted) {
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

    /**
     * An NPU session over a graph compiled for this chip on the PC and
     * downloaded ({@link #TARGETS}): fp16 on the HTP (a hosted graph, its INT8
     * weights widened) or fully INT8 (a downloaded decoder). The options are
     * the ones it was compiled with.
     */
    static OrtSession npuSession(File compiled, boolean fp16) throws OrtException {
        if (!compiled.isFile()) {
            throw new OrtException("no compiled NPU graph " + compiled.getName());
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            final Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", npuBackend());
            qnn.put("htp_performance_mode", "burst");
            if (fp16) {
                qnn.put("enable_htp_fp16_precision", "1");
                // The hosted files keep INT8 weights behind DequantizeLinear, which the
                // fp16 NPU graph cannot run; ORT folds them into float weights first.
                options.addConfigEntry("session.disable_quant_qdq", "1");
            } else {
                // The latent's own quantize step runs on the NPU too.
                qnn.put("offload_graph_io_quantization", "1");
            }
            addNpu(options, qnn);
            // All of it on the NPU or not at all: a half-offloaded graph is slower.
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            return PiperModel.env().createSession(compiled.getAbsolutePath(), options);
        }
    }

    /**
     * Marks an NPU part failed, so loads stop trying it - unless its weights
     * file was missing: a download replacing the compiled files while this
     * load opened them (Galaxy S25: two Rasa voices installed together kept
     * Kannada Chetan and Spoorthi on the CPU for good). Tried again next load.
     */
    static void remember(File failed, Exception e) {
        if (String.valueOf(e).contains("ep_cache_context does not exist")) {
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(failed)) {
            w.write(String.valueOf(e));
        } catch (IOException ignored) {
            // Tried again next load.
        }
    }
}
