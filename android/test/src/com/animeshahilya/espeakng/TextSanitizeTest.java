package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;

/** Control stripping and watchdog chunking in TextPreprocessor. */
public class TextSanitizeTest {

    @Test
    public void joinersReachTheEngine() {
        // Malayalam chillu (virama + ZWJ), Persian ZWNJ, an emoji ZWJ family:
        // eSpeak reads all three itself.
        for (String s : new String[] {"അവന്‍", "می‌خواهم", "👨‍👩‍👧"}) {
            assertEquals(s, TextPreprocessor.sanitizeForWatchdog(s));
        }
    }

    @Test
    public void otherInvisibleControlsAreStillStripped() {
        assertEquals("ab", TextPreprocessor.sanitizeForWatchdog("a​b"));
        assertEquals("ab", TextPreprocessor.sanitizeForWatchdog("a‏b"));
        assertEquals("ab", TextPreprocessor.sanitizeForWatchdog("a‮b"));
        assertEquals("ab", TextPreprocessor.sanitizeForWatchdog("﻿a\u0007b"));
    }

    @Test
    public void hardCutNeverSplitsASurrogatePair() {
        // No punctuation or space anywhere: the chunker must cut blindly.
        final StringBuilder sb = new StringBuilder("x");
        while (sb.length() < TextPreprocessor.MAX_CHUNK_CHARS * 3) {
            sb.append("😀");
        }
        final List<String> chunks = TextPreprocessor.chunkForWatchdog(sb.toString());
        for (String chunk : chunks) {
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
        }
        assertEquals(sb.toString(), String.join("", chunks));
    }
}
