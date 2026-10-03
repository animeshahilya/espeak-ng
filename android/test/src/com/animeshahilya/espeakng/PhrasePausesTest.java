package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhrasePausesTest {

    @Test
    public void testEnglishPhrasePause() {
        assertTrue(PhrasePauses.supports("en"));
        assertTrue(PhrasePauses.supports("en-US"));
        assertTrue(PhrasePauses.supports("en-GB"));

        // "one two three four and five six seven."
        // 4 words before "and", 3 words after before "." -> comma inserted
        String input = "one two three four and five six seven.";
        String expected = "one two three four, and five six seven.";
        assertEquals(expected, PhrasePauses.process(input, "en-US"));
    }

    @Test
    public void testEnglishNotEnoughWordsBefore() {
        // Only 3 words before "and" -> no comma
        String input = "one two three and five six seven.";
        assertEquals(input, PhrasePauses.process(input, "en-US"));
    }

    @Test
    public void testEnglishNotEnoughWordsAfter() {
        // 4 words before, but only 2 words after before "." -> no comma
        String input = "one two three four and five six.";
        assertEquals(input, PhrasePauses.process(input, "en-US"));
    }

    @Test
    public void testExistingPauseResetsCount() {
        // Pause resets word counter
        String input = "one two, three four and five six seven eight.";
        assertEquals(input, PhrasePauses.process(input, "en-US"));
    }

    @Test
    public void testHindiPhrasePause() {
        assertTrue(PhrasePauses.supports("hi"));
        assertTrue(PhrasePauses.supports("hi-IN"));

        String input = "एक दो तीन चार लेकिन पांच छह सात।";
        String expected = "एक दो तीन चार, लेकिन पांच छह सात।";
        assertEquals(expected, PhrasePauses.process(input, "hi"));
    }

    @Test
    public void testUnsupportedLanguage() {
        assertFalse(PhrasePauses.supports("es"));
        assertFalse(PhrasePauses.supports("fr"));
        String input = "one two three four and five six seven.";
        assertEquals(input, PhrasePauses.process(input, "es"));
    }
}
