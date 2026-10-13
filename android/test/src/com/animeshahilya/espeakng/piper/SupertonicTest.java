package com.animeshahilya.espeakng.piper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class SupertonicTest {

    /**
     * Supertonic.preprocess against Supertone's own py/helper.py: every case
     * in supertonic_text_cases.tsv was written by that code
     * (sherpa-onnx-respin-syspin/supertonic_text_cases.py). A difference here
     * is text the model never saw that way.
     */
    @Test
    public void preprocessMatchesReference() throws Exception {
        int n = 0;
        try (InputStream in = getClass().getResourceAsStream("/supertonic_text_cases.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                final String[] f = line.split("\t");
                final String input = new JSONArray("[" + f[1] + "]").getString(0);
                final String expected = new JSONArray("[" + f[2] + "]").getString(0);
                assertEquals(f[0] + " " + f[1], expected, Supertonic.preprocess(input, f[0]));
                n++;
            }
        }
        assertTrue(n >= 20);
    }

    @Test
    public void textIdsAreOnePerCodePointThroughTheTable() {
        final int[] indexer = new int[0x10000];
        Arrays.fill(indexer, -1);
        indexer['<'] = 1;
        indexer['>'] = 2;
        indexer['/'] = 3;
        indexer['e'] = 4;
        indexer['n'] = 5;
        indexer['a'] = 6;
        indexer['.'] = 7;
        assertArrayEquals(new long[] {1, 4, 5, 2, 6, 7, 1, 3, 4, 5, 2},
                Supertonic.textIds(Supertonic.preprocess("a", "en"), indexer));
        // A code point past U+FFFF reads as its low 16 bits, as their uint16 array.
        indexer[0x0001] = 9;
        assertArrayEquals(new long[] {9}, Supertonic.textIds(new String(Character.toChars(0x10001)), indexer));
    }

    @Test
    public void indexerReadsJsonList() throws Exception {
        final int[] t = Supertonic.readIndexer("[-1, 5, 7]");
        assertEquals(-1, t[0]);
        assertEquals(5, t[1]);
        assertEquals(7, t[2]);
        assertEquals(-1, t[3]);
        assertEquals(0x10000, t.length);
    }

    @Test
    public void styleFlattensNestedData() throws Exception {
        final String json = "{\"style_ttl\": {\"dims\": [1, 2, 2], \"data\": [[[1, 2], [3, 4]]]},"
                + " \"style_dp\": {\"dims\": [1, 1, 2], \"data\": [[[5, 6]]]}}";
        final Supertonic.Style s = Supertonic.Style.parse(json);
        assertArrayEquals(new float[] {1, 2, 3, 4}, s.ttl, 0f);
        assertArrayEquals(new long[] {1, 2, 2}, s.ttlShape);
        assertArrayEquals(new float[] {5, 6}, s.dp, 0f);
    }

    @Test
    public void configIsSupportedOnlyWithItsFiles() throws Exception {
        final JSONObject files = new JSONObject();
        for (String name : new String[] {Supertonic.DURATION_PREDICTOR, Supertonic.TEXT_ENCODER, Supertonic.VOCODER,
                Supertonic.INDEXER, Supertonic.TTS_JSON, Supertonic.STYLE}) {
            files.put(name, new JSONObject().put("path", "supertonic-v1/x").put("size_bytes", 1)
                    .put("md5_digest", "0123456789abcdef0123456789abcdef"));
        }
        final JSONObject cfg = new JSONObject()
                .put("audio", new JSONObject().put("sample_rate", 44100).put("quality", "medium"))
                .put("phoneme_type", "supertonic")
                .put("language", new JSONObject().put("code", "hi_IN").put("family", "hi"))
                .put("phoneme_id_map", new JSONObject().put("_", new JSONArray().put(0)))
                .put("supertonic", new JSONObject().put("lang", "hi").put("style", "F1").put("steps", 8)
                        .put("files", files));
        final PiperVoiceConfig c = PiperVoiceConfig.parse("hi_IN-supertonic_female_1-medium", cfg.toString());
        assertTrue(c.isSupertonic());
        assertTrue(c.isSupported());
        assertFalse(c.usesEspeak());
        assertEquals("hi", c.supertonicLang);
        assertEquals(8, c.supertonicSteps);
        assertEquals(6, c.supertonicFiles.size());

        files.remove(Supertonic.VOCODER);
        assertFalse(PiperVoiceConfig.parse("x", cfg.toString()).isSupported());
        cfg.getJSONObject("supertonic").put("lang", "xx");
        assertFalse(PiperVoiceConfig.parse("x", cfg.toString()).isSupported());
    }

    @Test
    public void bundledCatalogListsEveryStyleInEveryLanguage() throws Exception {
        final String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", "piper", "supertonic_voices.json")), StandardCharsets.UTF_8);
        final java.util.List<PiperDownloads.CatalogVoice> voices = PiperDownloads.parseCatalog(json, true);
        assertEquals(31 * 10, voices.size());
        final java.util.Set<String> models = new java.util.HashSet<>();
        for (PiperDownloads.CatalogVoice v : voices) {
            assertTrue(v.key, PiperDownloads.isSupertonic(v));
            assertTrue(v.key, Supertonic.LANGUAGES.contains(v.family));
            assertEquals(PiperDownloads.RESPIN_SYSPIN_RELEASES, v.baseUrl);
            models.add(v.modelMd5);
        }
        assertEquals("one shared model for all of them", 1, models.size());
        // Kept-voice filtering never hides them.
        final java.util.Map<String, java.util.Set<String>> kept = new java.util.HashMap<>();
        kept.put("hi", new java.util.HashSet<>(Arrays.asList("hi_IN-rohan-medium")));
        assertEquals(voices.size(), PiperDownloads.keptOnly(voices, kept).size());
    }

    @Test
    public void longSentencesSplitAtWordsForTheBuckets() {
        final String text = "This sentence is clearly much longer than sixty letters, so it is cut into words.";
        final java.util.List<PiperPhonemes.Clause> clauses = PiperPhonemes.textClauses(text, null, Supertonic.FIRST_CHUNK);
        assertTrue(clauses.size() > 5);
        final java.util.List<PiperPhonemes.Chunk> chunks = PiperPhonemes.chunk(clauses, Supertonic.FIRST_CHUNK,
                Supertonic.CHUNK, false);
        assertTrue(chunks.get(0).ipa.length() <= Supertonic.FIRST_CHUNK + 15);
        assertTrue(chunks.get(chunks.size() - 1).endsSentence);
        final java.util.List<PiperPhonemes.Clause> shortOnes = PiperPhonemes.textClauses("Short one. Two.", null,
                Supertonic.FIRST_CHUNK);
        assertEquals(2, shortOnes.size());
    }
}
