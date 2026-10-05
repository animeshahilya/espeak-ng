package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Hindi, Marathi, Nepali and Sanskrit sentences (test/resources/devanagari)
 * through the classifier, with the speaking voice in each language: a Hindi
 * voice (Priyamvada) must hand Marathi to the Marathi voice and keep Hindi,
 * and the other way round. Every misroute is listed on failure.
 */
public class DevanagariCorpusTest {

    private static List<String> lines(String name) throws IOException {
        final List<String> out = new ArrayList<>();
        try (InputStream in = DevanagariCorpusTest.class.getResourceAsStream("/devanagari/" + name + ".txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    /** Misroutes of corpus {@code name} (expected {@code expected}) with the voice in {@code own}. */
    private static List<String> misroutes(String name, String expected, String own, String chosen)
            throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (String s : lines(name)) {
            final String got = DevanagariClassifier.classify(s, own, chosen);
            if (!expected.equals(got)) {
                wrong.add(got + " <- " + s);
            }
        }
        return wrong;
    }

    @Test
    public void hindiVoice_routesMarathiAway_keepsHindi() throws IOException {
        assertEquals("[]", misroutes("mar", "mar", "hin", "hin").toString());
        assertEquals("[]", misroutes("hin", "hin", "hin", "hin").toString());
    }

    @Test
    public void marathiVoice_routesHindiAway_keepsMarathi() throws IOException {
        assertEquals("[]", misroutes("hin", "hin", "mar", "mar").toString());
        assertEquals("[]", misroutes("mar", "mar", "mar", "mar").toString());
    }

    @Test
    public void englishVoice_tellsHindiFromMarathi() throws IOException {
        assertEquals("[]", misroutes("hin", "hin", "eng", "hin").toString());
        assertEquals("[]", misroutes("mar", "mar", "eng", "hin").toString());
    }

    /**
     * Sentences the rules were not tuned on: a measure of how well they
     * generalize. A few misses are tolerated; Hindi must never go to Marathi
     * when Hindi is speaking (that moves text off the user's voice).
     */
    @Test
    public void heldOut_generalizes() throws IOException {
        assertEquals("[]", misroutes("hin_heldout", "hin", "hin", "hin").toString());
        final List<String> missed = misroutes("mar_heldout", "mar", "hin", "hin");
        java.nio.file.Files.write(java.nio.file.Paths.get("build/heldout.txt"), missed.toString().getBytes(StandardCharsets.UTF_8));
        if (missed.size() > 2) {
            throw new AssertionError(missed.size() + "/20 held-out Marathi kept Hindi: " + missed);
        }
    }

    @Test
    public void nepaliAndSanskrit_neverTakenForMarathi() throws IOException {
        for (String name : new String[] {"nep", "san"}) {
            for (String s : lines(name)) {
                final String got = DevanagariClassifier.classify(s, "hin", "hin");
                if ("mar".equals(got)) {
                    throw new AssertionError(name + " read as Marathi: " + s);
                }
            }
        }
    }
}
