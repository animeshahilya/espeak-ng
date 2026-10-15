package com.animeshahilya.espeakng.piper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.animeshahilya.espeakng.text.BundledData;
import com.ibm.icu.text.RuleBasedNumberFormat;
import com.ibm.icu.util.ULocale;

import java.io.File;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The CLDR number rules bundled for SYSPIN/Rasa voices: {@link Spellout}
 * reads them exactly as ICU4J does, in the lakh/crore system.
 */
public class NumberWordsTest {
    private static final String[] LANGUAGES = {"bn", "gu", "hi", "kn", "ml", "mr", "ne", "pa", "ta", "te"};

    @BeforeClass
    public static void data() {
        BundledData.useDirectory(new File("data"));
    }

    private static String words(String language, long n) {
        final String rules = PiperEngine.bundledRules(language);
        assertNotNull(language, rules);
        return new Spellout(rules).format(n, "%spellout-numbering");
    }

    @Test
    public void sameWordsAsIcu() {
        final java.util.Random random = new java.util.Random(7);
        for (String language : LANGUAGES) {
            final String rules = PiperEngine.bundledRules(language);
            final Spellout ours = new Spellout(rules);
            final RuleBasedNumberFormat icu = new RuleBasedNumberFormat(rules, new ULocale(language));
            for (int i = 0; i < 30000; i++) {
                // Every number to 20000, then spread over 15 digits (PiperEngine's limit).
                final long n = i <= 20000 ? i : (long) Math.pow(10, random.nextDouble() * 15);
                final String w = ours.format(n, "%spellout-numbering");
                assertEquals(language + " " + n, icu.format(n, "%spellout-numbering"), w);
                assertFalse(language + " " + n + " -> " + w, w.isEmpty() || w.matches(".*[0-9].*"));
            }
        }
    }

    @Test
    public void indianGrouping() {
        assertEquals("बारह लाख चौंतीस हज़ार पाँच सौ सड़सठ", words("hi", 1234567));
        assertEquals("రెండు వేల ఇరవై ఆరు", words("te", 2026));
        assertEquals("এক কোটি", words("bn", 10000000));
    }

    @Test
    public void naturalVoicesGetWordsNotDigits() {
        assertEquals("ಸಮಯ ಹತ್ತು : ಮೂವತ್ತು", PiperEngine.numberWords("kn").apply("ಸಮಯ 10:30"));
        assertEquals("एक लाख रुपये", PiperEngine.numberWords("hi").apply("1,00,000 रुपये"));
        // Devanagari languages eSpeak lacks read numbers as its Hindi stand-in did.
        assertEquals("दो सौ पचास", PiperEngine.numberWords("mai").apply("२५०"));
    }
}
