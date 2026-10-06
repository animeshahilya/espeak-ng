package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DevanagariClassifierTest {

    // =========================================================================
    // 1. Script Presence Detection
    // =========================================================================

    @Test
    public void hasDevanagari_detectsScriptPresence() {
        assertFalse(DevanagariClassifier.hasDevanagari("Hello World"));
        assertFalse(DevanagariClassifier.hasDevanagari("12345!@#$%"));
        assertFalse(DevanagariClassifier.hasDevanagari("Привет мир"));
        assertTrue(DevanagariClassifier.hasDevanagari("नमस्ते"));
        assertTrue(DevanagariClassifier.hasDevanagari("नमस्कार"));
        assertTrue(DevanagariClassifier.hasDevanagari("English with हिंदी text"));
        assertTrue(DevanagariClassifier.hasDevanagari("English with मराठी text"));
        assertTrue(DevanagariClassifier.hasDevanagari("English with नेपाली text"));
    }

    // =========================================================================
    // 2. Marathi (mar / mr)
    // =========================================================================

    @Test
    public void classify_marathiPhrasesWithLla() {
        // Words containing ळ (U+0933) are uniquely Marathi / Konkani
        assertEquals("mar", DevanagariClassifier.classify("मला वेळ नाही", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("शाळा सुरू झाली", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("डोळे उघडा आणि पहा", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("सगळे मिळून खेळूया", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("काळजी घ्या", "hin", "hin"));
    }

    @Test
    public void classify_marathiPhrasesWithoutLla() {
        // Marathi phrases without ळ must be identified via grammar and vocabulary
        assertEquals("mar", DevanagariClassifier.classify("कसे आहात तुम्ही?", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("मला हे पुस्तक खूप आवडले", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("तो घरी गेला आणि जेवला", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("आज काम नाही, सुट्टी आहे", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("तुम्ही कुठे राहता?", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("काय चाललंय?", "hin", "hin"));
    }

    @Test
    public void classify_eyelashReph() {
        // ऱ (U+0931) is uniquely Marathi
        assertEquals("mar", DevanagariClassifier.classify("कऱ्हाड", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("तऱ्हा", "hin", "hin"));
    }

    // =========================================================================
    // 3. Hindi (hin / hi)
    // =========================================================================

    @Test
    public void classify_hindiPhrases() {
        // Distinctive Hindi auxiliaries, postpositions, pronouns
        assertEquals("hin", DevanagariClassifier.classify("आज स्कूल बंद है", "mar", "mar"));
        assertEquals("hin", DevanagariClassifier.classify("आप कैसे हैं? क्या सब ठीक है?", "mar", "mar"));
        assertEquals("hin", DevanagariClassifier.classify("मुझे यह किताब बहुत पसंद आई", "mar", "mar"));
        assertEquals("hin", DevanagariClassifier.classify("वह घर गया और खाना खाया", "mar", "mar"));
        assertEquals("hin", DevanagariClassifier.classify("मेरा नाम राहुल है", "mar", "mar"));
        assertEquals("hin", DevanagariClassifier.classify("कल बहुत तेज बारिश हो रही थी", "mar", "mar"));
    }

    @Test
    public void classify_everydayHindiStaysHindi() {
        // Words that once fired other languages' suffix rules: -ें/-ां (Konkani),
        // -लाह/-थि (Maithili), -लाई/-संग (Nepali), -तानी (Bhojpuri)
        assertEquals("hin", DevanagariClassifier.classify("किताबें पढ़ें", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("यहां आओ", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("उसकी सलाह मानो", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("अतिथि आए", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("जनता की भलाई", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("सत्संग में भजन", "eng", "hin"));
        assertEquals("hin", DevanagariClassifier.classify("बच्चों की शैतानी", "eng", "hin"));
    }

    // =========================================================================
    // 4. Nepali (nep / ne)
    // =========================================================================

    @Test
    public void classify_nepaliPhrases() {
        // Nepali distinctive copulas (छ, छन्, छैन), standalone 'र' ("and"), pronouns
        assertEquals("nep", DevanagariClassifier.classify("तपाईं कस्तो हुनुहुन्छ? मलाई नेपाली बोल्न मन पर्छ.", "hin", "hin"));
        assertEquals("nep", DevanagariClassifier.classify("आज पानी पर्ने सम्भावना छ र चिसो छ.", "hin", "hin"));
        assertEquals("nep", DevanagariClassifier.classify("साथीहरू सबै काठमाडौंबाट आए.", "hin", "hin"));
        assertEquals("nep", DevanagariClassifier.classify("हाम्रो देश धेरै राम्रो छ.", "hin", "hin"));
        assertEquals("nep", DevanagariClassifier.classify("घरमा कोही पनि छैन.", "hin", "hin"));

        // Preserves 2-letter code if requested
        assertEquals("ne", DevanagariClassifier.classify("तपाईंको नाम के हो?", "ne", "ne"));
    }

    // =========================================================================
    // 5. Sanskrit (san / sa)
    // =========================================================================

    @Test
    public void classify_sanskritPhrases() {
        // Sanskrit avagraha ऽ (U+093D)
        assertEquals("san", DevanagariClassifier.classify("शिवोऽहम्", "hin", "hin"));
        assertEquals("san", DevanagariClassifier.classify("सोऽपि तत्र गच्छति", "hin", "hin"));

        // Classical grammar, pronouns and verbs
        assertEquals("san", DevanagariClassifier.classify("अहं गच्छामि, त्वं कुत्र गच्छसि?", "hin", "hin"));
        assertEquals("san", DevanagariClassifier.classify("सत्यमेव जयते नानृतम्", "hin", "hin"));
        assertEquals("san", DevanagariClassifier.classify("सर्वे भवन्तु सुखिनः सर्वे सन्तु निरामयाः", "hin", "hin"));
        assertEquals("san", DevanagariClassifier.classify("अस्ति कश्चित् वाग्विशेषः", "hin", "hin"));

        // Preserves 2-letter code if requested
        assertEquals("sa", DevanagariClassifier.classify("सुप्रभातम्, भवन्तः कथम् सन्ति?", "sa", "sa"));
    }

    // =========================================================================
    // 6. Bhojpuri (bho)
    // =========================================================================

    @Test
    public void classify_bhojpuriPhrases() {
        // Distinctive copula बा, negation नाइखे, pronouns रउआ, verb suffix -तानी
        assertEquals("bho", DevanagariClassifier.classify("रउआ कइसे बानी? का हाल बा?", "hin", "hin"));
        assertEquals("bho", DevanagariClassifier.classify("हम घर जातानी, आज काम नाइखे.", "hin", "hin"));
        assertEquals("bho", DevanagariClassifier.classify("तोहार नाव का बाटे?", "hin", "hin"));
        assertEquals("bho", DevanagariClassifier.classify("ओकरा कुछ मत कहल जाव.", "hin", "hin"));
    }

    // =========================================================================
    // 7. Maithili (mai)
    // =========================================================================

    @Test
    public void classify_maithiliPhrases() {
        // Distinctive copula अछि, pronoun अहाँक, negation नहि, words मुदा, किछु
        assertEquals("mai", DevanagariClassifier.classify("अहाँक की नाम अछि? सब किछु नीक अछि.", "hin", "hin"));
        assertEquals("mai", DevanagariClassifier.classify("हमर गाम बहुत सुन्दर अछि, मुदा एखन पानी नहि अछि.", "hin", "hin"));
        assertEquals("mai", DevanagariClassifier.classify("हुनक बाबुजी कतय गेलाह?", "hin", "hin"));
        assertEquals("mai", DevanagariClassifier.classify("की भेल? छथि ओहि ठाम?", "hin", "hin"));
    }

    // =========================================================================
    // 8. Chhattisgarhi (hne)
    // =========================================================================

    @Test
    public void classify_chhattisgarhiPhrases() {
        // Distinctive greeting जोहार, particle संगी, copula हे, adverb अब्बड़
        assertEquals("hne", DevanagariClassifier.classify("जय जोहार संगी, का हाल हे?", "hin", "hin"));
        assertEquals("hne", DevanagariClassifier.classify("मोर नाव रमेश हे, मैं अब्बड़ खुश हंव.", "hin", "hin"));
        assertEquals("hne", DevanagariClassifier.classify("तुमन काबर नइये आइस?", "hin", "hin"));
        assertEquals("hne", DevanagariClassifier.classify("हमन काली रायपुर जाबो.", "hin", "hin"));
    }

    // =========================================================================
    // 9. Konkani (kok)
    // =========================================================================

    @Test
    public void classify_konkaniPhrases() {
        // Distinctive copula आसा, pronoun हांव / तुका, formulaic greeting
        assertEquals("kok", DevanagariClassifier.classify("तुका कशें आसा? हांव बरे आसां.", "hin", "hin"));
        assertEquals("kok", DevanagariClassifier.classify("देव बरें करूं", "hin", "hin"));
        assertEquals("kok", DevanagariClassifier.classify("आमी गांवांत वतांत आणि तांकां सांगात.", "hin", "hin"));
        assertEquals("kok", DevanagariClassifier.classify("कित्याक इतलो वेळ जालो?", "hin", "hin"));
    }

    // =========================================================================
    // 10. Ambiguity & Fallback Behavior
    // =========================================================================

    @Test
    public void classify_neutralProperNounsPreservePrimary() {
        // Words like "भारत" without discriminatory markers preserve speaking voice
        assertEquals("hin", DevanagariClassifier.classify("भारत", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("भारत", "mar", "hin"));
        assertEquals("nep", DevanagariClassifier.classify("भारत", "nep", "hin"));
        assertEquals("san", DevanagariClassifier.classify("भारत", "san", "hin"));
        assertEquals("bho", DevanagariClassifier.classify("भारत", "bho", "hin"));
        assertEquals("mai", DevanagariClassifier.classify("भारत", "mai", "hin"));
        assertEquals("hne", DevanagariClassifier.classify("भारत", "hne", "hin"));
        assertEquals("kok", DevanagariClassifier.classify("भारत", "kok", "hin"));
    }

    // =========================================================================
    // 11. Multi-Language Sentence Segmentation & Spans
    // =========================================================================

    @Test
    public void buildSpans_multiSentenceHindiAndMarathi() {
        final String text = "नमस्ते, आप कैसे हैं? मला वेळ नाही.";
        final DevanagariClassifier.SpanMap map = DevanagariClassifier.buildSpans(text, "hin", null);

        // Sentence 1 is Hindi
        assertEquals("hin", map.languageAt(0, "und"));
        assertEquals("hin", map.languageAt(15, "und"));

        // Sentence 2 is Marathi
        final int sent2Start = text.indexOf("मला");
        assertEquals("mar", map.languageAt(sent2Start, "und"));
        assertEquals("mar", map.languageAt(text.length() - 2, "und"));
    }

    @Test
    public void buildSpans_multiSentenceHindiMarathiNepaliSanskrit() {
        final String text = "आप कैसे हैं? शाळा सुरू झाली. तपाईं कस्तो हुनुहुन्छ? शिवोऽहम्.";
        final DevanagariClassifier.SpanMap map = DevanagariClassifier.buildSpans(text, "hin", null);

        assertEquals("hin", map.languageAt(text.indexOf("आप"), "und"));
        assertEquals("mar", map.languageAt(text.indexOf("शाळा"), "und"));
        assertEquals("nep", map.languageAt(text.indexOf("तपाईं"), "und"));
        assertEquals("san", map.languageAt(text.indexOf("शिवोऽहम्"), "und"));
    }

    @Test
    public void buildSpans_codeSwitchingWithinSingleSentence() {
        // Hindi leading clause switching to Nepali
        final String text = "नमस्ते दोस्त, मलाई सन्चै छ र खुसी लाग्यो.";
        final DevanagariClassifier.SpanMap map = DevanagariClassifier.buildSpans(text, "hin", null);

        final int nepStart = text.indexOf("मलाई");
        assertEquals("nep", map.languageAt(nepStart, "und"));
    }

    // =========================================================================
    // 12. Text Preprocessing Polish for Devanagari
    // =========================================================================

    @Test
    public void marathiTextPreprocessing_natoSpellingAndDiacritics() {
        // Marathi letters take the Marathi primer's words, ळ included
        assertEquals("ळ, बाळ", TextPreprocessor.expandNatoSpelling("ळ", "mr-IN"));
        assertEquals("क, कमळ", TextPreprocessor.expandNatoSpelling("क", "mr-IN"));
        assertEquals("ह, हरीण", TextPreprocessor.expandNatoSpelling("ह", "mr-IN"));
        assertEquals("क से कबूतर", TextPreprocessor.expandNatoSpelling("क", "hi-IN"));

        // Diacritics in Marathi use 'ची मात्रा'
        assertEquals("आ ची मात्रा", TextPreprocessor.expandDevanagariDiacritic("\u093E", "mr-IN"));
        assertEquals("आ की मात्रा", TextPreprocessor.expandDevanagariDiacritic("\u093E", "hi-IN"));
        assertEquals("आ को मात्रा", TextPreprocessor.expandDevanagariDiacritic("\u093E", "ne-NP"));

        // Currency shorthand
        final String marText = TextPreprocessor.preprocessIndianText("किंमत 15k आहे आणि 2cr आहे", "mr-IN");
        assertTrue(marText.contains("हजार"));
        assertTrue(marText.contains("कोटी"));

        final String nepText = TextPreprocessor.preprocessIndianText("मूल्य 15k छ र 2cr छ", "ne-NP");
        assertTrue(nepText.contains("हजार"));
        assertTrue(nepText.contains("करोड"));

        final String hinText = TextPreprocessor.preprocessIndianText("कीमत 15k है और 2cr है", "hi-IN");
        assertTrue(hinText.contains("हज़ार"));
        assertTrue(hinText.contains("करोड़"));
    }

    @Test
    public void colonTypedForVisargaIsRead() {
        // Inside a word, and closing a common visarga word.
        assertEquals("दुःख और निःशुल्क", TextPreprocessor.preprocessIndianText("दु:ख और नि:शुल्क", "hi-IN"));
        assertEquals("पुनः स्थापना, अतः हम चले", TextPreprocessor.preprocessIndianText("पुन: स्थापना, अत: हम चले", "hi-IN"));
        assertEquals("क्रमशः", TextPreprocessor.preprocessIndianText("क्रमश:", "hi-IN"));
        // A real colon stays: after other words, in times, and inside a longer word.
        assertEquals("नाम: राम", TextPreprocessor.preprocessIndianText("नाम: राम", "hi-IN"));
        assertEquals("भारत: एक परिचय", TextPreprocessor.preprocessIndianText("भारत: एक परिचय", "hi-IN"));
        assertEquals("समय 10:30", TextPreprocessor.preprocessIndianText("समय 10:30", "hi-IN"));
        assertEquals("अनुपुन: अब", TextPreprocessor.preprocessIndianText("अनुपुन: अब", "hi-IN"));
    }
}
