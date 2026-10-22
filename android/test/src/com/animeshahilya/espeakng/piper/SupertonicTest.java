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
        assertEquals(6, c.companionFiles.size());

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

    private static java.util.List<String> said(String text, float speed) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (PiperPhonemes.Chunk c : Supertonic.chunks(text, "en", speed)) {
            out.add(c.ipa);
        }
        return out;
    }

    @Test
    public void dotsInsideASentenceDoNotEndIt() {
        for (String s : new String[] {"The price rose by 3.5 percent on Monday.",
                "Dr. Sharma met Mr. Gupta at the station.", "Version 1.9.7 fixes the keyboard layout.",
                "The U.S. team won the final in New York.", "Call me at 10.30 tomorrow, not at 9."}) {
            assertEquals(java.util.Collections.singletonList(s), said(s, 1f));
        }
    }

    @Test
    public void shortSentencesShareARun() {
        final String text = "The museum opens at nine in the morning. Visitors can buy tickets at the door. "
                + "Children enter free! Is it open on Sunday? Yes.";
        assertEquals(java.util.Arrays.asList("The museum opens at nine in the morning.",
                "Visitors can buy tickets at the door. Children enter free! Is it open on Sunday? Yes."),
                said(text, 1f));
    }

    @Test
    public void longSentencesSplitAtClausesFirst() {
        final String text = "When the train finally pulled into the crowded station, the passengers gathered "
                + "their bags, said goodbye to the friends they had made on the journey, and stepped out into "
                + "the warm evening air, tired but happy, and walked slowly home through the quiet streets.";
        final java.util.List<PiperPhonemes.Chunk> chunks = Supertonic.chunks(text, "en", 1f);
        assertEquals("When the train finally pulled into the crowded station,", chunks.get(0).ipa);
        assertFalse(chunks.get(0).endsSentence);
        for (PiperPhonemes.Chunk c : chunks) {
            assertTrue(c.ipa, c.ipa.length() <= Supertonic.CHUNK + 1);
            assertTrue(c.ipa, c.ipa.endsWith(",") || c.ipa.endsWith("."));
            // Spans point at the text the chunk says.
            assertTrue(text.startsWith(c.ipa.substring(0, 10), c.start));
        }
        assertTrue(chunks.get(chunks.size() - 1).endsSentence);
        assertEquals(text.length(), chunks.get(chunks.size() - 1).end);
    }

    @Test
    public void aClauseTooLongIsCutEvenlyAtWordsWithAComma() {
        final String text = "Natural voices should start speaking quickly and keep going without gaps even when "
                + "a screen reader reads a long paragraph like this one from the beginning to the very end "
                + "of the page and then reads the next page in the same way without stopping at all.";
        final java.util.List<String> s = said(text, 1f);
        assertEquals("Natural voices should start speaking quickly and keep going,", s.get(0));
        for (int i = 0; i < s.size() - 1; i++) {
            assertTrue(s.get(i), s.get(i).endsWith(","));
        }
        // The rest in even pieces, not one full piece and a scrap.
        final int a = s.get(1).length();
        final int b = s.get(s.size() - 1).length();
        assertTrue(a + " vs " + b, Math.abs(a - b) < 30);
    }

    @Test
    public void fasterSpeechTakesLongerRuns() {
        final String text = "If you turn on the battery saver the phone limits background activity and lowers "
                + "the screen brightness.";
        assertEquals(2, said(text, 1f).size());
        assertEquals(1, said(text, 2f).size());
    }

    @Test
    public void screenReaderPhrasesStayWhole() {
        assertEquals(java.util.Collections.singletonList("Settings"), said("Settings", 1f));
        assertEquals(java.util.Collections.singletonList("Wi-Fi, on"), said("  Wi-Fi, on ", 1f));
        assertTrue(said("   ", 1f).isEmpty());
    }

    @Test
    public void hindiSentencesEndAtDanda() {
        final java.util.List<PiperPhonemes.Chunk> c = Supertonic.chunks("नमस्ते। आप कैसे हैं?", "hi", 1f);
        assertEquals(1, c.size());
        assertEquals("नमस्ते। आप कैसे हैं?", c.get(0).ipa);
    }

    @Test
    public void charactersWithoutAnIdAreRepairedOrDropped() {
        final int[] indexer = new int[0x10000];
        Arrays.fill(indexer, -1);
        for (char c : "<>/hi रिओम्।ष 5.".toCharArray()) {
            indexer[c] = 1;
        }
        // ऋषि: the vowel ऋ has no id, रि stands in; ॥ -> ।; ५ -> 5; a star is dropped.
        assertEquals("<hi>रिषि । 5 </hi>",
                Supertonic.known("<hi>ऋषि ॥ ५ ★</hi>", fill(indexer, 'ि')));
        final String plain = "<hi>रि</hi>";
        assertSame(plain, Supertonic.known(plain, indexer));
    }

    private static int[] fill(int[] indexer, char c) {
        indexer[c] = 1;
        return indexer;
    }

    @Test
    public void downloadSizeCountsOnlyWhatThePhoneLacks() throws Exception {
        final String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", "piper", "supertonic_voices.json")), StandardCharsets.UTF_8);
        final java.util.List<PiperDownloads.CatalogVoice> all = PiperDownloads.parseCatalog(json, true);
        final PiperDownloads.CatalogVoice v = all.get(0);
        assertEquals(6, v.extraFiles.size());
        final java.util.Map<String, Long> none = new java.util.HashMap<>();
        final long first = v.downloadBytes(none);
        // Every model and companion file: about 110 MB, not the 66 MB model alone.
        assertTrue(first + " bytes", first > 105_000_000L && first < 115_000_000L);
        // A second voice: the shared model and companions are there; its style and config are not.
        final java.util.Map<String, Long> shared = new java.util.HashMap<>();
        shared.put(v.modelMd5 + ".onnx", v.modelSize);
        for (java.util.Map.Entry<String, String[]> e : v.extraFiles.entrySet()) {
            if (!e.getKey().equals(Supertonic.STYLE)) {
                final String name = e.getKey();
                shared.put(e.getValue()[1] + name.substring(name.lastIndexOf('.')), Long.parseLong(e.getValue()[0]));
            }
        }
        final long second = v.downloadBytes(shared);
        assertTrue(second + " bytes", second < 400_000L);
        // A partly written shared file does not count as there.
        shared.put(v.modelMd5 + ".onnx", v.modelSize - 1);
        assertTrue(v.downloadBytes(shared) > v.modelSize);
    }
}
