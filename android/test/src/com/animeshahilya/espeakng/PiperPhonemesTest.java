package com.animeshahilya.espeakng;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /** A modifier letter the voice lacks falls back to its plain letter, else is reported. */
    @Test
    public void modifierLetterFallsBackToPlainLetter() throws Exception {
        final List<String> missing = new java.util.ArrayList<>();
        final long[] ids = PiperPhonemes.toIds(java.util.Arrays.asList("t", "ʰ", "ⁿ"),
                amy(), missing);
        // ^ _ t _ h(from ʰ) _ $ : ⁿ -> n, which this subset lacks, is skipped and reported.
        assertArrayEquals(new long[] {1, 0, 32, 0, 20, 0, 2}, ids);
        assertEquals(java.util.Collections.singletonList("ⁿ"), missing);
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

    /** Community config shape (tinisoft Tamil): generic dataset, language without region. */
    @Test
    public void communityConfigTakesNameAndRegionFromKey() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("ta_IN-rasa_female-medium",
                "{\"dataset\": \"data\", \"espeak\": {\"voice\": \"ta\"},"
                        + " \"language\": {\"code\": \"ta\"}, \"phoneme_id_map\": {\"_\": [0]}}");
        assertEquals("Rasa Female", c.displayName());
        assertEquals("ta_IN", c.languageCode);
        assertEquals("ta", c.languageFamily);
    }

    @Test
    public void legacyConfigWithoutLanguageUsesEspeakVoice() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("x",
                "{\"espeak\": {\"voice\": \"pt-br\"}, \"phoneme_id_map\": {\"_\": [0]}}");
        assertEquals("pt", c.languageFamily);
        assertEquals(22050, c.sampleRate);
    }

    @Test
    public void titleCaseHandlesNullEmptyAndIdentifiers() {
        assertEquals("", PiperVoiceConfig.titleCase(null));
        assertEquals("", PiperVoiceConfig.titleCase(""));
        assertEquals("Libritts R", PiperVoiceConfig.titleCase("libritts_r"));
        assertEquals("Amy", PiperVoiceConfig.titleCase("amy"));
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

    /** Fork-only Indic IPA goes back to upstream's spelling, which the voices were trained on. */
    @Test
    public void indicVoicesGetTheSpellingTheyWereTrainedOn() throws Exception {
        final String map = "\"phoneme_id_map\": {\"_\": [0]}}";
        final PiperVoiceConfig hi = PiperVoiceConfig.parse("hi_IN-x-medium",
                "{\"espeak\": {\"voice\": \"hi\"}," + map);
        // पढ़ी, सड़क, बैंक
        assertEquals(Arrays.asList("p", "ʌ", "r", ".", "h", "i", "ː", " ", "s", "ʌ", "r", ".", "ə",
                "k", " ", "b", "ɛ", "ː", "ŋ", "k"),
                PiperPhonemes.tokenize("pʌɽʱiː sʌɽək bæːŋk", hi));
        final PiperVoiceConfig ne = PiperVoiceConfig.parse("ne_NP-x-medium",
                "{\"espeak\": {\"voice\": \"ne\"}," + map);
        // झलक, चल: Nepali voices learned ɟʰ/c where the fork writes dzʱ/ts.
        assertEquals("ɟʰˈʌ cˈʌl", String.join("", PiperPhonemes.tokenize("dzʱˈʌ tsˈʌl", ne)));
        // Other languages keep their own ɽ (Pashto defines it upstream too).
        final PiperVoiceConfig ps = PiperVoiceConfig.parse("ps_AF-x-medium",
                "{\"espeak\": {\"voice\": \"ps\"}," + map);
        assertEquals(Arrays.asList("ɽ"), PiperPhonemes.tokenize("ɽ", ps));
    }

    /** Upstream's own changes since 2023 only apply to voices trained before piper 1.3. */
    @Test
    public void spellingsFollowTheEspeakTheVoiceWasTrainedOn() throws Exception {
        final String map = "\"phoneme_id_map\": {\"_\": [0]}}";
        final PiperVoiceConfig nst = PiperVoiceConfig.parse("sv_SE-nst-medium",
                "{\"espeak\": {\"voice\": \"sv\"}, \"piper_version\": \"0.2.0\"," + map);
        final PiperVoiceConfig alma = PiperVoiceConfig.parse("sv_SE-alma-medium",
                "{\"espeak\": {\"voice\": \"sv\"}, \"piper_version\": \"1.3.0\"," + map);
        // skiljetecken
        assertEquals("sxˈɪljə", String.join("", PiperPhonemes.tokenize("ɧˈɪljə", nst)));
        assertEquals("ɧˈɪljə", String.join("", PiperPhonemes.tokenize("ɧˈɪljə", alma)));
        // Word-final and pre-consonant rules: abertura, atenção, produto.
        final PiperVoiceConfig faber = PiperVoiceConfig.parse("pt_BR-faber-medium",
                "{\"espeak\": {\"voice\": \"pt-br\"}, \"piper_version\": \"1.0.0\"," + map);
        assertEquals(nfd("ˌabeɾətˈuɾæ ateɪŋsɐ̃ʊ̃ prˌodˈutʊ"),
                String.join("", PiperPhonemes.tokenize("ˌabeɾtˈuɾɐ atẽnsɐ̃ʊ̃ pɾˌodˈutʊ", faber)));
        // Ukrainian в before a voiceless consonant and elsewhere, soft consonants: вовк, вона, пря.
        final PiperVoiceConfig uk = PiperVoiceConfig.parse("uk_UA-lada-x",
                "{\"espeak\": {\"voice\": \"uk\"}, \"piper_version\": \"1.0.0\"," + map);
        assertEquals("βofk βona prja",
                String.join("", PiperPhonemes.tokenize("ʋowk ʋona prʲa", uk)));
        assertTrue(PiperVoiceConfig.isOldPiper(null));
        assertTrue(PiperVoiceConfig.isOldPiper(""));
        assertTrue(PiperVoiceConfig.isOldPiper("1.2.0"));
        assertFalse(PiperVoiceConfig.isOldPiper("1.3.0"));
        assertFalse(PiperVoiceConfig.isOldPiper("2.0"));
    }

    /**
     * The Java table does what the measured Python one does: real words per
     * language from tools/piper_spellings.py (regenerate with its "fixture"
     * command after changing either).
     */
    @Test
    public void javaSpellingsMatchTheMeasuredTable() throws Exception {
        final java.io.InputStream in = getClass().getResourceAsStream("/piper_spellings_cases.tsv");
        assertNotNull(in);
        final List<String> failures = new ArrayList<>();
        int cases = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                final String[] f = line.split("\t");
                final PiperVoiceConfig c = PiperVoiceConfig.parse("x", "{\"espeak\": {\"voice\": \""
                        + f[0] + "\"}, \"piper_version\": \"" + f[1] + "\","
                        + "\"phoneme_id_map\": {\"_\": [0]}}");
                final String got = String.join("", PiperPhonemes.tokenize(f[2], c));
                if (!got.equals(nfd(f[3]))) {
                    failures.add(f[0] + " " + f[2] + ": " + got + " != " + f[3]);
                }
                cases++;
            }
        }
        assertTrue(cases > 400);
        assertEquals(Collections.emptyList(), failures);
    }

    private static String nfd(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
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

        // Null and empty safety
        assertTrue(PiperPhonemes.textClauses(null).isEmpty());
        assertTrue(PiperPhonemes.textClauses("").isEmpty());
        assertTrue(PiperPhonemes.textClauses(null, null).isEmpty());
        assertTrue(PiperPhonemes.textClauses("", null).isEmpty());
        assertTrue(PiperPhonemes.alignToText(null, "hello").isEmpty());
        assertTrue(PiperPhonemes.alignToText(Collections.emptyList(), "hello").isEmpty());
        assertEquals(c, PiperPhonemes.alignToText(c, null));
        assertEquals(c, PiperPhonemes.alignToText(c, ""));
    }

    /** A character voice (SYSPIN/Rasa): composed letters it knows stay whole, others decompose. */
    @Test
    public void textVoicesKeepComposedLettersTheyKnow() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("bn_IN-tithi-medium", "{\"phoneme_type\": \"text\","
                + " \"num_speakers\": 1, \"default_speaker_id\": 2,"
                + " \"phoneme_id_map\": {\"_\": [0], \"^\": [0], \"$\": [0],"
                + " \"ক\": [5], \"ো\": [6], \"ক়\": [9], \"়\": [7], \"a\": [8]}}");
        assertTrue(c.isSupported());
        assertEquals(2, c.defaultSpeakerId);
        // "কো" in NFD would be ক + ে + া: the voice was trained on the composed vowel sign.
        assertEquals(Arrays.asList("ক", "ো", "a"),
                PiperPhonemes.tokenize("কোA", c));
        // য় (U+09DF) is not in the map: its NFD parts are.
        assertEquals(Arrays.asList("য", "়"), PiperPhonemes.tokenize("য়", c));
        assertArrayEquals(new long[] {0, 0, 5, 0, 6, 0, 0},
                PiperPhonemes.toIds(PiperPhonemes.tokenize("কো", c), c, null));
    }

    @Test
    public void textClausesSpellEachSentence() {
        final List<PiperPhonemes.Clause> c = PiperPhonemes.textClauses("A 5. B", t -> t.replace("5", "five"));
        assertEquals("A five. ", c.get(0).ipa);
        assertEquals(4, c.get(0).end);
    }
    @Test
    public void recordsWhereEachWordStarts() throws Exception {
        final PiperVoiceConfig c = amy();
        final List<Integer> starts = new ArrayList<>();
        final long[] ids = PiperPhonemes.toIds(PiperPhonemes.tokenize("həlˈoʊ wˈɜːld, ðɪs.", c),
                c, null, starts);
        // Same ids as without word tracking.
        assertArrayEquals(PiperPhonemes.toIds(PiperPhonemes.tokenize("həlˈoʊ wˈɜːld, ðɪs.", c), c, null),
                ids);
        // BOS PAD, then h; "wˈɜːld," starts after 6 phonemes + space (2 ids each);
        // the comma and space are not words.
        assertEquals(Arrays.asList(2, 16, 32), starts);
        assertEquals(20, ids[2]);  // h
        assertEquals(35, ids[16]); // w
        assertEquals(41, ids[32]); // ð
    }

    private static PiperVoiceConfig textVoice(String key, String family) throws Exception {
        return PiperVoiceConfig.parse(key, "{\"phoneme_type\": \"text\", \"language\": {\"family\": \""
                + family + "\"}, \"phoneme_id_map\": {\"_\": [0]}}");
    }

    /** English text voices end a chunk on a comma (no "phone" -> "phoned"); others keep theirs. */
    @Test
    public void englishTextVoicesEndChunksWithAComma() throws Exception {
        final PiperVoiceConfig priya = textVoice("en_IN-priya-medium", "en");
        assertEquals("Hello. Missed call on phone,",
                PiperPhonemes.englishChunkEnd("Hello. Missed call on phone. ", priya));
        assertEquals("Is it on?", PiperPhonemes.englishChunkEnd("Is it on?", priya));
        assertEquals("version 2.8", PiperPhonemes.englishChunkEnd("version 2.8", priya));
        final PiperVoiceConfig hetal = textVoice("gu_IN-hetal-medium", "gu");
        assertEquals("ફોન. ", PiperPhonemes.englishChunkEnd("ફોન. ", hetal));
        assertTrue(hetal.hissy);
        assertTrue(textVoice("gu_IN-hetal-compact", "gu").hissy);
        assertFalse(textVoice("hi_IN-kavya-medium", "hi").hissy);
        assertTrue(textVoice("en_US-ljspeech-compact", "en").hissy);
        assertFalse(textVoice("en_US-ljspeech-medium", "en").hissy);
    }

    @Test
    public void englishChunkEndHandlesEllipsis() throws Exception {
        final PiperVoiceConfig priya = textVoice("en_IN-priya-medium", "en");
        assertEquals("Wait,", PiperPhonemes.englishChunkEnd("Wait...", priya));
        assertEquals("Call on phone,", PiperPhonemes.englishChunkEnd("Call on phone.. ", priya));
    }

    @Test
    public void multilingualSentenceEndsRecognizedInTextClauses() {
        // Indic double danda (॥) and Arabic/Urdu question mark (؟)
        final List<PiperPhonemes.Clause> devanagari = PiperPhonemes.textClauses("नमस्ते॥ आप कैसे हैं?");
        assertEquals(2, devanagari.size());
        assertEquals("नमस्ते॥ ", devanagari.get(0).ipa);

        final List<PiperPhonemes.Clause> urdu = PiperPhonemes.textClauses("کیا حال ہے؟ ٹھیک ہے.");
        assertEquals(2, urdu.size());
        assertEquals("کیا حال ہے؟ ", urdu.get(0).ipa);
    }

    @Test
    public void indicLigaturesPreserveJoinersInAlignment() {
        // Bengali/Hindi word with zero-width joiner (ZWJ U+200D) or non-joiner (ZWNJ U+200C)
        final String text = "क्\u200Dष और क्\u200Cष";
        final List<PiperPhonemes.Clause> raw = Arrays.asList(
                new PiperPhonemes.Clause(false, 0, 4, "ksha"),
                new PiperPhonemes.Clause(true, 4, 11, "aur ksha")
        );
        final List<PiperPhonemes.Clause> aligned = PiperPhonemes.alignToText(raw, text);
        assertEquals(2, aligned.size());
        assertTrue(aligned.get(0).end >= 3);
    }

    @Test
    public void nonBreakingSpaceResetsWordBoundaryInToIds() throws Exception {
        final PiperVoiceConfig c = amy();
        final List<Integer> starts = new ArrayList<>();
        // "h \u00a0w" - normal space then non-breaking space
        PiperPhonemes.toIds(Arrays.asList("h", " ", "\u00a0", "w"), c, null, starts);
        assertEquals(2, starts.size());
    }
}
