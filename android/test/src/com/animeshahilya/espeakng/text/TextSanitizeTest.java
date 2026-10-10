package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.TextPreprocessor;
import com.animeshahilya.espeakng.ui.VoiceSettings;

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
    public void simplifyUrlsTrailingPunctuationPreserved() {
        assertEquals("Check this ( link example.com slash path ).",
                TextPreprocessor.simplifyUrls("Check this (https://example.com/path)."));
        assertEquals("Go to  link example.com slash foo , then.",
                TextPreprocessor.simplifyUrls("Go to https://example.com/foo, then."));
        assertEquals("Read  link en.wikipedia.org slash wiki slash Rust_(programming_language) .",
                TextPreprocessor.simplifyUrls("Read https://en.wikipedia.org/wiki/Rust_(programming_language)."));
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

    /**
     * One Indian akshara is one character, as TalkBack types, deletes and
     * moves over it (kept off the natural voices, which garble a lone
     * syllable). Other scripts still count code points (NVDA parity).
     */
    @Test
    public void indianAksharaIsOneCharacter() {
        for (String akshara : new String[] {"कि", "की", "क्", "क्ष",
                "श्र", "ड़", "कं", "क्‍", " कि ",
                "கி", "க்", "కి", "કિ", "কি"}) {
            assertTrue(akshara, TextPreprocessor.isSingleCharacter(akshara));
        }
        for (String text : new String[] {"कख", "किख", "क्ष a",
                "क deleted", "é", "िि"}) {
            assertFalse(text, TextPreprocessor.isSingleCharacter(text));
        }
    }

    @Test
    public void halfLetterIsNamed() {
        assertEquals("क हलन्त", TextPreprocessor.expandDevanagariDiacritic("क्"));
        assertEquals("हलन्त", TextPreprocessor.expandDevanagariDiacritic("्"));
        assertEquals("कि", TextPreprocessor.expandDevanagariDiacritic("कि"));
    }

    @Test
    public void testIsIndianLanguage() {
        // Null & empty
        assertFalse(TextPreprocessor.isIndianLanguage(null));
        assertFalse(TextPreprocessor.isIndianLanguage(""));

        // Indian English
        assertTrue(TextPreprocessor.isIndianLanguage("en-in"));
        assertTrue(TextPreprocessor.isIndianLanguage("en-IN"));
        assertTrue(TextPreprocessor.isIndianLanguage("en-IN-variant"));

        // 2-letter ISO 639 codes
        assertTrue(TextPreprocessor.isIndianLanguage("hi"));
        assertTrue(TextPreprocessor.isIndianLanguage("hi-IN"));
        assertTrue(TextPreprocessor.isIndianLanguage("mr"));
        assertTrue(TextPreprocessor.isIndianLanguage("ta"));
        assertTrue(TextPreprocessor.isIndianLanguage("te"));
        assertTrue(TextPreprocessor.isIndianLanguage("kn"));
        assertTrue(TextPreprocessor.isIndianLanguage("ml"));
        assertTrue(TextPreprocessor.isIndianLanguage("gu"));
        assertTrue(TextPreprocessor.isIndianLanguage("bn"));
        assertTrue(TextPreprocessor.isIndianLanguage("pa"));
        assertTrue(TextPreprocessor.isIndianLanguage("ur"));

        // 3-letter ISO 639 codes
        assertTrue(TextPreprocessor.isIndianLanguage("hin"));
        assertTrue(TextPreprocessor.isIndianLanguage("mar"));
        assertTrue(TextPreprocessor.isIndianLanguage("tam"));
        assertTrue(TextPreprocessor.isIndianLanguage("tel"));
        assertTrue(TextPreprocessor.isIndianLanguage("kan"));
        assertTrue(TextPreprocessor.isIndianLanguage("mal"));
        assertTrue(TextPreprocessor.isIndianLanguage("guj"));
        assertTrue(TextPreprocessor.isIndianLanguage("ben"));
        assertTrue(TextPreprocessor.isIndianLanguage("pan"));
        assertTrue(TextPreprocessor.isIndianLanguage("urd"));
        assertTrue(TextPreprocessor.isIndianLanguage("kok"));
        assertTrue(TextPreprocessor.isIndianLanguage("bpy"));
        assertTrue(TextPreprocessor.isIndianLanguage("bho"));

        // Non-Indian languages must be false
        assertFalse(TextPreprocessor.isIndianLanguage("en"));
        assertFalse(TextPreprocessor.isIndianLanguage("en-US"));
        assertFalse(TextPreprocessor.isIndianLanguage("en-GB"));
        assertFalse(TextPreprocessor.isIndianLanguage("fr"));
        assertFalse(TextPreprocessor.isIndianLanguage("de"));
        assertFalse(TextPreprocessor.isIndianLanguage("es"));
        assertFalse(TextPreprocessor.isIndianLanguage("ja"));
        assertFalse(TextPreprocessor.isIndianLanguage("zh"));
    }

    @Test
    public void testCleanMarkdown() {
        assertEquals("Visit Google for search",
                TextPreprocessor.cleanMarkdown("Visit [Google](https://google.com) for search"));
        assertEquals("Here is Company Logo",
                TextPreprocessor.cleanMarkdown("Here is ![Company Logo](https://logo.png)"));
        assertEquals("todo: buy milk",
                TextPreprocessor.cleanMarkdown("- [ ] buy milk"));
        assertEquals("done: write tests",
                TextPreprocessor.cleanMarkdown("- [x] write tests"));
        assertEquals("Important Title",
                TextPreprocessor.cleanMarkdown("## Important Title"));
        assertEquals("quote: famous words",
                TextPreprocessor.cleanMarkdown("> famous words"));
        assertEquals("use println here",
                TextPreprocessor.cleanMarkdown("use `println` here"));
        assertEquals("code block",
                TextPreprocessor.cleanMarkdown("```python\ncode block\n```"));
        assertEquals("bold text and italic text",
                TextPreprocessor.cleanMarkdown("**bold text** and *italic text*"));
        assertEquals("strikethrough text",
                TextPreprocessor.cleanMarkdown("~~strikethrough text~~"));
    }
}
