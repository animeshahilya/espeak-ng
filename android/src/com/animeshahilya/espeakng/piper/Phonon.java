/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import java.io.File;
import java.io.IOException;

/**
 * Gradium's Phonon 31m (English, Apache-2.0) on the Snapdragon NPU: a
 * flow-matching language model producing 80 ms frames of 24 kHz audio, run
 * by its QNN runner (jni/phonon, xn-ptts v0.4.0) from a context binary
 * compiled for this chip. Galaxy S25 Ultra: first audio ~9 ms, ~30x real
 * time. One instance serves every installed Phonon voice; calls block and
 * must come from one thread at a time (PiperModel's lock does that).
 */
public final class Phonon implements AutoCloseable {
    private static final boolean LOADED = loadLibrary();
    private long handle;
    private final int sampleRate;

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("phonon_jni");
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false; // a build without Phonon (standard, or no QAIRT SDK)
        }
    }

    /** True when this build has Phonon's runtime. */
    public static boolean available() {
        return LOADED;
    }

    /** Polled once per frame: true stops the text early. */
    public interface Stop {
        boolean stopped();
    }

    private Phonon(long handle) {
        this.handle = handle;
        this.sampleRate = nativeSampleRate(handle);
    }

    /**
     * Loads a bundle folder (metadata.json, tokenizer, BOS data, voices, the
     * context binary its metadata names for {@code socModel}). Slow (~0.4 s).
     */
    public static Phonon load(File bundle, String socModel, String nativeLibDir) throws IOException {
        if (!LOADED) {
            throw new IOException("Phonon runtime not in this build");
        }
        // The DSP finds QNN's Skel library through ADSP_LIBRARY_PATH. ONNX
        // Runtime's QNN plugin sets it, once and only if unset, to the app's
        // library folder; Phonon may load first, so set that same value here
        // (any other value made ONNX Runtime's NPU graphs fail).
        try {
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, false);
        } catch (android.system.ErrnoException e) {
            throw new IOException(e);
        }
        return new Phonon(nativeLoad(bundle.getAbsolutePath(), socModel, nativeLibDir,
                new File(nativeLibDir, "libptts_text.so").getAbsolutePath(), "en"));
    }

    public int sampleRate() {
        return sampleRate;
    }

    public synchronized String[] voices() {
        return nativeVoices(handle);
    }

    /** The text in {@code voice} (numbers and symbols read in English), or null when stopped. */
    public synchronized float[] synthesize(String text, String voice, Stop stop) throws IOException {
        if (handle == 0) {
            return null;
        }
        return nativeSynthesize(handle, text, voice, stop);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeLoad(String bundle, String soc, String libDir, String textLibrary,
                                          String lang) throws IOException;

    private static native String[] nativeVoices(long handle);

    private static native int nativeSampleRate(long handle);

    private static native float[] nativeSynthesize(long handle, String text, String voice, Stop stop)
            throws IOException;

    private static native void nativeFree(long handle);
}
