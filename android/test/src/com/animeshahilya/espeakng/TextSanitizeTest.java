package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void stripUnpairedSurrogates() {
        assertEquals("Valid 😀 text", TextPreprocessor.stripUnpairedSurrogates("Valid 😀 text"));
        assertEquals("ab", TextPreprocessor.stripUnpairedSurrogates("a\uD800b"));
        assertEquals("ab", TextPreprocessor.stripUnpairedSurrogates("a\uDC00b"));
        assertEquals("a😀b", TextPreprocessor.stripUnpairedSurrogates("a\uD800\uD83D\uDE00b"));
        assertEquals("a😀b", TextPreprocessor.stripUnpairedSurrogates("a\uD83D\uDE00\uDC00b"));
    }

    @Test
    public void endsWithQuestionOrExclamationDetection() {
        // Standard ASCII
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Is this ready?"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Look at that!"));

        // With trailing quotes and brackets
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Really?\""));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Are you sure? )"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Done! ]"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Important! }"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Yes? »"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Right? 』"));

        // Multilingual: Arabic, Greek, fullwidth, interrobang
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("هل هذا صحيح؟"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Τι κάνεις;"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("你好嗎？"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("太好了！"));
        assertTrue(TextPreprocessor.endsWithQuestionOrExclamation("Really‽"));

        // Negatives
        assertFalse(TextPreprocessor.endsWithQuestionOrExclamation("Just a sentence."));
        assertFalse(TextPreprocessor.endsWithQuestionOrExclamation("Thinking..."));
        assertFalse(TextPreprocessor.endsWithQuestionOrExclamation(""));
        assertFalse(TextPreprocessor.endsWithQuestionOrExclamation(null));
    }

    @Test
    public void simplifyUrlsNoDanglingTrailingSlash() {
        assertEquals(" link example.com ", TextPreprocessor.simplifyUrls("https://example.com/"));
        assertEquals(" link example.com slash path ", TextPreprocessor.simplifyUrls("https://example.com/path/"));
        assertEquals(" link example.com slash path with parameters ",
                TextPreprocessor.simplifyUrls("https://example.com/path/?tracking_token_123456789"));
    }

    @Test
    public void condenseRepeatedEmojisWithSurrogates() {
        // Mathematical bold capital A is U+1D400 (surrogate pair \uD835\uDC00)
        String mathText = "\uD835\uDC00\uD835\uDC01";
        assertEquals(mathText, TextPreprocessor.condenseRepeatedEmojis(mathText, VoiceSettings.REPEATED_CHARS_COUNT));
    }

    @Test
    public void singleCharacterUtteranceDetection() {
        assertTrue(TextPreprocessor.isSingleCharacter("a"));
        assertTrue(TextPreprocessor.isSingleCharacter("  a  "));
        assertTrue(TextPreprocessor.isSingleCharacter("?"));
        // Supplementary unicode code points (surrogate pairs: emoji, math symbols)
        assertTrue(TextPreprocessor.isSingleCharacter("😀"));
        assertTrue(TextPreprocessor.isSingleCharacter("  😀  "));
        assertTrue(TextPreprocessor.isSingleCharacter("\uD835\uDC00")); // Mathematical bold A
        assertTrue(TextPreprocessor.isSingleCharacter("  \uD835\uDC00  "));

        // Multi-character / empty
        assertFalse(TextPreprocessor.isSingleCharacter(""));
        assertFalse(TextPreprocessor.isSingleCharacter("   "));
        assertFalse(TextPreprocessor.isSingleCharacter("ab"));
        assertFalse(TextPreprocessor.isSingleCharacter("😀😀"));
        assertFalse(TextPreprocessor.isSingleCharacter("a😀"));
    }
}
