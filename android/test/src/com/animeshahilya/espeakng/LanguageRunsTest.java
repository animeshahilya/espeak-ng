package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** Language runs by writing system, as eSpeak switches by alphabet. */
public class LanguageRunsTest {

    private static String describe(List<LanguageRuns.Run> runs) {
        final StringBuilder sb = new StringBuilder();
        for (LanguageRuns.Run r : runs) {
            sb.append(r.language).append('@').append(r.start).append('[').append(r.text).append(']');
        }
        return sb.toString();
    }

    @Test
    public void hindiInsideEnglishGoesToHindi() {
        assertEquals("eng@0[Hello,]hin@6[ नमस्ते दोस्त]eng@19[ how are you?]",
                describe(LanguageRuns.split("Hello, नमस्ते दोस्त how are you?", "eng")));
    }

    @Test
    public void englishInsideHindiGoesToEnglish() {
        assertEquals("hin@0[मेरा फ़ोन]eng@9[ Samsung]hin@17[ है।]",
                describe(LanguageRuns.split("मेरा फ़ोन Samsung है।", "hin")));
    }

    /** Marathi is written in Devanagari: it stays Marathi, not Hindi. */
    @Test
    public void ownScriptStaysWithTheLanguage() {
        assertEquals("mar@0[नमस्कार, कसे आहात?]",
                describe(LanguageRuns.split("नमस्कार, कसे आहात?", "mar")));
    }

    /** Digits and punctuation never split a run on their own. */
    @Test
    public void digitsAndPunctuationFollowTheirRun() {
        assertEquals("eng@0[Call 123-456, now!]",
                describe(LanguageRuns.split("Call 123-456, now!", "eng")));
        assertEquals("eng@0[42 !!]", describe(LanguageRuns.split("42 !!", "eng")));
    }

    @Test
    public void runsRejoinToTheText() {
        final String text = "Tamil வணக்கம் and Hindi नमस्ते, done.";
        final StringBuilder joined = new StringBuilder();
        for (LanguageRuns.Run r : LanguageRuns.split(text, "eng")) {
            joined.append(r.text);
        }
        assertEquals(text, joined.toString());
        assertEquals(5, LanguageRuns.split(text, "eng").size());
    }

    /** Code point offsets, not UTF-16: emoji before a switch must not shift it. */
    @Test
    public void startsAreCodePointOffsets() {
        final List<LanguageRuns.Run> runs = LanguageRuns.split("Hi 😀 नमस्ते", "eng");
        assertEquals(2, runs.size());
        assertEquals(4, runs.get(1).start); // "Hi", space, emoji, then the space before नमस्ते
    }
}
