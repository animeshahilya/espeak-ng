package com.animeshahilya.espeakng;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The Piper input side, off-device: config parsing, clause records from
 * piperPhonemizer.c, chunking, tokenizing and phoneme ids. The golden ids
 * below are what piper-tts 1.8's own phonemes_to_ids() produces for the same
 * phonemes with the en_US-amy-low voice (its id map, trimmed to the symbols
 * used here), so a drift from Piper's encoding fails this test.
 */
public class PiperPhonemesTest {

    private static final String AMY_SUBSET = "{"
            + "\"audio\": {\"sample_rate\": 16000, \"quality\": \"low\"},"
            + "\"espeak\": {\"voice\": \"en-us\"},"
            + "\"dataset\": \"amy\","
            + "\"language\": {\"code\": \"en_US\", \"family\": \"en\"},"
            + "\"inference\": {\"noise_scale\": 0.667, \"length_scale\": 1, \"noise_w\": 0.8},"
            + "\"num_speakers\": 1,"
            + "\"phoneme_id_map\": {"
            + "\" \": [3], \"$\": [2], \",\": [8], \".\": [10], \"^\": [1], \"_\": [0], \"d\": [17],"
            + "\"h\": [20], \"l\": [24], \"o\": [27], \"s\": [31], \"t\": [32], \"w\": [35], \"z\": [38],"
            + "\"ð\": [41], \"ɐ\": [50], \"ə\": [59], \"ɛ\": [61], \"ɜ\": [62],"
            + "\"ɪ\": [74], \"ʊ\": [100], \"ˈ\": [120], \"ː\": [122]}"
            + "}";

    /** Records as piperPhonemizer.c emits them for "Hello world, this is a test." */
    private static final String RECORDS =
            "C\u001f0\u001f14\u001fhəlˈoʊ wˈɜːld, \u001e"
            + "S\u001f14\u001f28\u001fðɪs ɪz ɐ tˈɛst.\u001e";

    private static final long[] GOLDEN_IDS = {1, 0, 20, 0, 59, 0, 24, 0, 120, 0, 27, 0, 100, 0, 3, 0,
            35, 0, 120, 0, 62, 0, 122, 0, 24, 0, 17, 0, 8, 0, 3, 0, 41, 0, 74, 0, 31, 0, 3, 0, 74, 0,
            38, 0, 3, 0, 50, 0, 3, 0, 32, 0, 120, 0, 61, 0, 31, 0, 32, 0, 10, 0, 2};

    private static PiperVoiceConfig amy() throws Exception {
        return PiperVoiceConfig.parse("en_US-amy-low", AMY_SUBSET);
    }

    @Test
    public void parsesConfig() throws Exception {
        final PiperVoiceConfig c = amy();
        assertTrue(c.isSupported());
        assertTrue(c.usesEspeak());
        assertEquals(16000, c.sampleRate);
        assertEquals("en-us", c.espeakVoice);
        assertEquals("en", c.languageFamily);
        assertEquals("Amy", c.displayName());
        assertEquals(0.667f, c.noiseScale, 1e-6);
        assertArrayEquals(new int[] {0}, c.phonemeIdMap.get("_"));
    }

    @Test
    public void refusesNonEspeakPhonemizers() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("zh", AMY_SUBSET.replace(
                "\"num_speakers\"", "\"phoneme_type\": \"pinyin\", \"num_speakers\""));
        assertFalse(c.isSupported());
    }

    @Test
    public void legacyConfigWithoutLanguageUsesEspeakVoice() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("x",
                "{\"espeak\": {\"voice\": \"pt-br\"}, \"phoneme_id_map\": {\"_\": [0]}}");
        assertEquals("pt", c.languageFamily);
        assertEquals(22050, c.sampleRate);
    }

    @Test
    public void matchesPiperIdsExactly() throws Exception {
        final PiperVoiceConfig c = amy();
        final List<PiperPhonemes.Clause> clauses = PiperPhonemes.parseRecords(RECORDS);
        assertEquals(2, clauses.size());
        assertFalse(clauses.get(0).endsSentence);
        assertTrue(clauses.get(1).endsSentence);

        // One sentence = one chunk when the budget allows, as in Piper.
        final List<PiperPhonemes.Chunk> chunks = PiperPhonemes.chunk(clauses, 1000, 1000);
        assertEquals(1, chunks.size());
        final List<String> missing = new ArrayList<>();
        final long[] ids = PiperPhonemes.toIds(PiperPhonemes.tokenize(chunks.get(0).ipa, c), c, missing);
        assertArrayEquals(GOLDEN_IDS, ids);
        assertTrue(missing.isEmpty());
    }

    @Test
    public void longFirstSentenceIsCutAtAClauseForLatency() {
        final List<PiperPhonemes.Clause> clauses = PiperPhonemes.parseRecords(RECORDS);
        final List<PiperPhonemes.Chunk> chunks = PiperPhonemes.chunk(clauses, 10, 1000);
        assertEquals(2, chunks.size());
        assertFalse(chunks.get(0).endsSentence);
        assertEquals(0, chunks.get(0).start);
        assertEquals(14, chunks.get(0).end);
        assertTrue(chunks.get(1).endsSentence);
    }

    @Test
    public void unknownPhonemesAreSkippedAndReported() throws Exception {
        final PiperVoiceConfig c = amy();
        final List<String> missing = new ArrayList<>();
        final long[] ids = PiperPhonemes.toIds(Arrays.asList("h", "ʈ", "o"), c, missing);
        assertArrayEquals(new long[] {1, 0, 20, 0, 27, 0, 2}, ids);
        assertEquals(Arrays.asList("ʈ"), missing);
    }

    @Test
    public void tokenizeDecomposesAndMergesVowelClusters() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("x", "{\"espeak\": {\"voice\": \"en\"},"
                + "\"vowel_clusters\": [[\"a\", \"ɪ\"]], \"phoneme_id_map\": {\"_\": [0]}}");
        // NFD splits the precomposed c-cedilla; the cluster a+ɪ is one symbol.
        assertEquals(Arrays.asList("c", "̧", "aɪ", "t"),
                PiperPhonemes.tokenize("çaɪt", c));
    }

    @Test
    public void clauseBoundariesAreAlignedToText() {
        // eSpeak's one-character look-ahead reports "Hello, t|his" as the cut.
        final String text = "Hello, this is it. How are you?";
        final List<PiperPhonemes.Clause> raw = Arrays.asList(
                new PiperPhonemes.Clause(false, 0, 8, "a, "),
                new PiperPhonemes.Clause(true, 8, 20, "b."),
                new PiperPhonemes.Clause(true, 20, 31, "c?"));
        final List<PiperPhonemes.Clause> fixed = PiperPhonemes.alignToText(raw, text);
        assertEquals(7, fixed.get(0).end);   // after "Hello, "
        assertEquals(7, fixed.get(1).start);
        assertEquals(19, fixed.get(1).end);  // after "this is it. "
        assertEquals(19, fixed.get(2).start);
        assertEquals(31, fixed.get(2).end);
    }

    @Test
    public void malformedRecordsAreIgnored() {
        assertTrue(PiperPhonemes.parseRecords(null).isEmpty());
        assertTrue(PiperPhonemes.parseRecords("garbage").isEmpty());
        assertEquals(1, PiperPhonemes.parseRecords("S\u001fx\u001f1\u001fa\u001e"
                + "S\u001f0\u001f1\u001fa\u001e").size());
    }

    @Test
    public void textVoicesSplitAtSentences() {
        final List<PiperPhonemes.Clause> c = PiperPhonemes.textClauses("One. Two! Three");
        assertEquals(3, c.size());
        assertEquals("One. ", c.get(0).ipa);
        assertEquals(15, c.get(2).end);
    }
}
