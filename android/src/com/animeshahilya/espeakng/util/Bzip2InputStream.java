/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes a bzip2 stream with the bzip2 library built into libttsespeak
 * (src/bzip2, jni/bzip2Stream.c). Native, not Java: a voice archive is
 * decoded once per import, so a Java decoder never got compiled by ART and
 * ran interpreted (Pixel 8, first import of a 64 MB archive: 65 s, now 9 s).
 */
public final class Bzip2InputStream extends InputStream {
    static {
        System.loadLibrary("ttsespeak");
    }

    private final InputStream source;
    private final byte[] in = new byte[64 * 1024];
    private final int[] used = new int[1];
    private int inOff;
    private int inLen;
    private boolean sourceDone;
    private long handle;

    public Bzip2InputStream(InputStream source) throws IOException {
        this.source = source;
        handle = nativeOpen();
        if (handle == 0) {
            throw new IOException("bzip2 decoder unavailable");
        }
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] out, int off, int len) throws IOException {
        if (handle == 0) {
            throw new IOException("closed");
        }
        if (len == 0) {
            return 0;
        }
        while (true) {
            if (inLen == 0 && !sourceDone) {
                inOff = 0;
                inLen = Math.max(0, source.read(in));
                sourceDone = inLen == 0;
            }
            final int n = nativeDecode(handle, in, inOff, inLen, out, off, len, used);
            inOff += used[0];
            inLen -= used[0];
            if (n > 0) {
                return n;
            }
            if (n == -2) {
                throw new IOException("Damaged bzip2 data");
            }
            if (sourceDone && inLen == 0) {
                if (n == -1) {
                    return -1;
                }
                throw new EOFException("bzip2 data ends early");
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
        source.close();
    }

    private static native long nativeOpen();

    private static native int nativeDecode(long handle, byte[] in, int inOff, int inLen,
                                           byte[] out, int outOff, int outLen, int[] used);

    private static native void nativeClose(long handle);
}
