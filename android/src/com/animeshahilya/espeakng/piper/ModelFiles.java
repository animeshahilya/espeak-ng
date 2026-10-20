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
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A natural voice's model files on the phone: the ORT-format optimized copies built from the original, the shared copies voices with the same bytes use, what identifies the bytes, and what can be deleted.
 */
public final class ModelFiles {
    private ModelFiles() {
    }

    /**
     * Deletes the downloaded model once its optimized copy has loaded: the
     * phone kept both, twice the space (Piper medium 17 + 18 MB). A shared
     * model (all Rasa voices) goes too; its optimized copy stays in the
     * shared store. Kept where a voice has no {@code source} record: it could
     * not be matched to its catalog entry again. (The Snapdragon build kept
     * originals to cut NPU graphs from until its NPU files came hosted.)
     */
    static void dropOriginal(File onnx) {
        if (!new File(onnx.getParentFile(), PiperModel.SOURCE_FILE).isFile()) {
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
        final File[] old = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(PiperModel.OPTIMIZED_PREFIX)
                && n.endsWith(PiperModel.ENCODER_SUFFIX) && !n.equals(encoder.getName()));
        if (old != null) {
            for (File enc : old) {
                final File dec = new File(dir, enc.getName().replace(PiperModel.ENCODER_SUFFIX, PiperModel.DECODER_SUFFIX));
                if (dec.isFile() && enc.renameTo(encoder) && dec.renameTo(decoder)) {
                    return;
                }
            }
        }
        final File[] whole = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(PiperModel.OPTIMIZED_PREFIX)
                && n.endsWith(PiperModel.OPTIMIZED_SUFFIX) && !n.equals(optimized.getName()));
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
        final File source = new File(dir, PiperModel.SOURCE_FILE);
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
        final File[] ort = dir.listFiles((d, n) -> n.startsWith(PiperModel.OPTIMIZED_PREFIX) && n.endsWith(".ort"));
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
        final String base = md5.toLowerCase(java.util.Locale.ROOT) + "." + PiperModel.env().getVersion();
        return (new File(sharedDir, base + PiperModel.ENCODER_SUFFIX).isFile() && new File(sharedDir, base + PiperModel.DECODER_SUFFIX).isFile())
                || new File(sharedDir, base + PiperModel.OPTIMIZED_SUFFIX).isFile();
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
        deleteOptimized(dir, PiperModel.OPTIMIZED_PREFIX);
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

    /** model.onnx -> model.<ONNX Runtime version><suffix>: a runtime update re-optimizes. */
    static File derivedFile(File onnx, String suffix) {
        return new File(onnx.getParentFile(), PiperModel.OPTIMIZED_PREFIX + PiperModel.env().getVersion() + suffix);
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
        return new File(sharedDir, md5 + "." + PiperModel.env().getVersion() + suffix);
    }

    /**
     * Writes the optimized copy: encoder and decoder if the model splits,
     * else one alignment-enabled model. On any failure there is simply none.
     */
    static void buildOptimized(File onnx, File optimized, File encoder, File decoder,
                                       int threads) {
        final File dir = onnx.getParentFile();
        final File[] stale = dir.listFiles((d, n) -> n.startsWith(PiperModel.OPTIMIZED_PREFIX)
                && (n.endsWith(".ort") || n.endsWith(".tmp") || n.contains(".npu")) && !n.equals(PiperModel.NPU_DECODER_FILE));
        if (stale != null) {
            for (File f : stale) {
                f.delete();
            }
        }
        final File sharedEnc = sharedDerivedFile(onnx, PiperModel.ENCODER_SUFFIX);
        final File sharedDec = sharedDerivedFile(onnx, PiperModel.DECODER_SUFFIX);
        final File sharedOpt = sharedDerivedFile(onnx, PiperModel.OPTIMIZED_SUFFIX);
        final File encoderSource = new File(dir, PiperModel.OPTIMIZED_PREFIX + "encoder.tmp");
        final File decoderSource = new File(dir, PiperModel.OPTIMIZED_PREFIX + "decoder.tmp");
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
        final File aligned = new File(dir, PiperModel.OPTIMIZED_PREFIX + "aligned.tmp");
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
        try (OrtSession.SessionOptions options = PiperModel.options(threads)) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setOptimizedModelFilePath(partial.getAbsolutePath());
            options.addConfigEntry("session.save_model_format", "ORT");
            PiperModel.env().createSession(source.getAbsolutePath(), options).close();
            if (partial.renameTo(out)) {
                return true;
            }
        } catch (OrtException | RuntimeException ignored) {
            // Falls through: no copy.
        }
        partial.delete();
        return false;
    }

    static ByteBuffer map(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
        }
    }
}
