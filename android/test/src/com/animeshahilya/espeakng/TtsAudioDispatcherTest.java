/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class TtsAudioDispatcherTest {

    private static class RecordingSink implements TtsAudioDispatcher.AudioSink {
        final int maxBufferSize;
        final List<byte[]> writtenChunks = new ArrayList<>();
        final List<int[]> wordBoundaries = new ArrayList<>(); // [marker, start, end]
        boolean doneCalled = false;
        int errorCode = -1;
        int nextAudioReturn = 0; // 0 = SUCCESS

        RecordingSink(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
        }

        @Override
        public int getMaxBufferSize() {
            return maxBufferSize;
        }

        @Override
        public int audioAvailable(byte[] buffer, int offset, int length) {
            if (nextAudioReturn != 0) {
                return nextAudioReturn;
            }
            byte[] copy = new byte[length];
            System.arraycopy(buffer, offset, copy, 0, length);
            writtenChunks.add(copy);
            return 0;
        }

        @Override
        public void rangeStart(int markerInFrames, int start, int end) {
            wordBoundaries.add(new int[] { markerInFrames, start, end });
        }

        @Override
        public void done() {
            doneCalled = true;
        }

        @Override
        public void error(int errorCode) {
            this.errorCode = errorCode;
        }
    }

    @Test
    public void splitsLargeAudioBuffersAccordingToMaxBufferSize() {
        RecordingSink sink = new RecordingSink(512);
        AtomicBoolean isStopped = new AtomicBoolean(false);
        AtomicBoolean callbackDone = new AtomicBoolean(false);

        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, "Hello world", 0, "Hello world".length(),
                isStopped, callbackDone, null
        );

        byte[] pcm = new byte[1500]; // Should split into 512 + 512 + 476
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 128);
        }

        assertTrue(dispatcher.writeAudio(pcm));
        assertEquals(3, sink.writtenChunks.size());
        assertEquals(512, sink.writtenChunks.get(0).length);
        assertEquals(512, sink.writtenChunks.get(1).length);
        assertEquals(476, sink.writtenChunks.get(2).length);
        assertEquals(750, dispatcher.getRequestFrames()); // 1500 bytes = 750 16-bit frames
    }

    @Test
    public void abortsImmediatelyAndTriggersCallbackWhenAudioSinkRejectsData() {
        RecordingSink sink = new RecordingSink(512);
        sink.nextAudioReturn = -1; // Simulate audioAvailable failure
        AtomicBoolean isStopped = new AtomicBoolean(false);
        AtomicBoolean callbackDone = new AtomicBoolean(false);
        AtomicBoolean abortRan = new AtomicBoolean(false);

        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, "Hello", 0, 5,
                isStopped, callbackDone, () -> abortRan.set(true)
        );

        assertFalse(dispatcher.writeAudio(new byte[100]));
        assertTrue(dispatcher.isStopped());
        assertTrue(isStopped.get());
        assertTrue(abortRan.get());

        // Subsequent writes should be refused without touching sink
        assertFalse(dispatcher.writeAudio(new byte[100]));
    }

    @Test
    public void correctlyConvertsCodePointsToUtf16IndicesWithSurrogates() {
        // "A" (1 cp, 1 char), "😀" (1 cp, 2 chars), "B" (1 cp, 1 char)
        String text = "A\uD83D\uDE00B";
        RecordingSink sink = new RecordingSink(512);
        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, text, 0, text.length(),
                null, null, null
        );

        assertEquals(0, dispatcher.codePointToOffset(0)); // 'A'
        assertEquals(1, dispatcher.codePointToOffset(1)); // start of emoji
        assertEquals(3, dispatcher.codePointToOffset(2)); // 'B'
        assertEquals(4, dispatcher.codePointToOffset(3)); // end of string
    }

    @Test
    public void remapsWordBoundariesThroughTextOffsetMapAndChunkBase() {
        // Original text: "Dr. Smith" (9 chars)
        // Normalized: "Doctor Smith" (12 chars)
        TextOffsetMap map = TextOffsetMap.diff("Dr. Smith", "Doctor Smith");

        RecordingSink sink = new RecordingSink(512);
        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, map, "Doctor Smith", 0, "Dr. Smith".length(),
                null, null, null
        );

        // eSpeak reports word boundary for "Smith": textPosition 8 (1-based code point in "Doctor Smith")
        // "Doctor " is 7 chars. "Smith" starts at index 7 (0-based code point 7 -> textPosition 8)
        // Length 5 ("Smith").
        dispatcher.dispatchWordBoundary(8, 5, 100);

        assertEquals(1, sink.wordBoundaries.size());
        int[] boundary = sink.wordBoundaries.get(0);
        assertEquals(100, boundary[0]); // frame
        assertEquals(4, boundary[1]);   // mapped start in "Dr. Smith": index 4 ("Smith")
        assertEquals(9, boundary[2]);   // mapped end: index 9
    }

    @Test
    public void clampsWordBoundariesToOriginalTextLength() {
        RecordingSink sink = new RecordingSink(512);
        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, "Extended spoken sentence", 0, 10,
                null, null, null
        );

        // Word boundary reporting beyond originalTextLength (10)
        dispatcher.dispatchWordBoundary(1, 25, 0);

        assertEquals(1, sink.wordBoundaries.size());
        int[] boundary = sink.wordBoundaries.get(0);
        assertEquals(0, boundary[1]);
        assertEquals(10, boundary[2]); // Clamped to original length 10
    }

    @Test
    public void piperOutputAdapterRoutesAudioAndWordBoundaries() {
        RecordingSink sink = new RecordingSink(1024);
        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, "Hello world", 0, 11,
                null, null, null
        );
        PiperEngine.Output output = dispatcher.asPiperOutput();

        dispatcher.setChunkContext(0, 200); // 200 unit frame base

        byte[] pcm = new byte[200];
        assertTrue(output.audio(pcm));
        assertEquals(1, sink.writtenChunks.size());
        assertEquals(100, dispatcher.getRequestFrames());

        output.word(1, 5, 50); // "Hello", frame 50 -> total 200 + 50 = 250
        assertEquals(1, sink.wordBoundaries.size());
        assertEquals(250, sink.wordBoundaries.get(0)[0]);
        assertEquals(0, sink.wordBoundaries.get(0)[1]);
        assertEquals(5, sink.wordBoundaries.get(0)[2]);

        assertFalse(output.stopped());
        dispatcher.setStopped(true);
        assertTrue(output.stopped());
        assertFalse(output.audio(pcm));
    }

    @Test
    public void finishAndErrorAreTerminalAndIdempotent() {
        RecordingSink sink = new RecordingSink(512);
        TtsAudioDispatcher dispatcher = new TtsAudioDispatcher(
                sink, null, null, "Test", 0, 4,
                null, null, null
        );

        dispatcher.finish();
        assertTrue(sink.doneCalled);
        assertTrue(dispatcher.isDone());

        // Second finish or error does not re-trigger sink
        dispatcher.finish();
        dispatcher.reportError(-1);
        assertEquals(-1, sink.errorCode);
    }
}
