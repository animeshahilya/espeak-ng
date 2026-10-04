package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DevanagariClassifierTest {

    @Test
    public void hasDevanagari_detectsScriptPresence() {
        assertFalse(DevanagariClassifier.hasDevanagari("Hello World"));
        assertFalse(DevanagariClassifier.hasDevanagari("12345!@#$%"));
        assertFalse(DevanagariClassifier.hasDevanagari("Привет мир"));
        assertTrue(DevanagariClassifier.hasDevanagari("नमस्ते"));
        assertTrue(DevanagariClassifier.hasDevanagari("नमस्कार"));
        assertTrue(DevanagariClassifier.hasDevanagari("English with हिंदी text"));
        assertTrue(DevanagariClassifier.hasDevanagari("English with मराठी text"));
    }

    @Test
    public void classify_marathiPhrasesWithLla() {
        // Words containing ळ (U+0933) are uniquely Marathi
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
    public void classify_eyelashReph() {
        // ऱ (U+0931) is uniquely Marathi
        assertEquals("mar", DevanagariClassifier.classify("कऱ्हाड", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("तऱ्हा", "hin", "hin"));
    }

    @Test
    public void classify_neutralProperNounsPreservePrimary() {
        // Words like "भारत" without discriminatory markers preserve speaking voice
        assertEquals("hin", DevanagariClassifier.classify("भारत", "hin", "hin"));
        assertEquals("mar", DevanagariClassifier.classify("भारत", "mar", "hin"));
    }

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
    public void marathiTextPreprocessing_natoSpellingAndDiacritics() {
        // In Marathi, ळ is 'बाळाचा' and letters take 'चा'
        assertEquals("ळ बाळाचा", TextPreprocessor.expandNatoSpelling("ळ", "mr-IN"));
        assertEquals("क कबूतरचा", TextPreprocessor.expandNatoSpelling("क", "mr-IN"));
        assertEquals("क से कबूतर", TextPreprocessor.expandNatoSpelling("क", "hi-IN"));

        // Diacritics in Marathi use 'ची मात्रा'
        assertEquals("आ ची मात्रा", TextPreprocessor.expandDevanagariDiacritic("\u093E", "mr-IN"));
        assertEquals("आ की मात्रा", TextPreprocessor.expandDevanagariDiacritic("\u093E", "hi-IN"));

        // Marathi currency shorthand: कोटी and हजार
        final String marText = TextPreprocessor.preprocessIndianText("किंमत 15k आहे आणि 2cr आहे", "mr-IN");
        assertTrue(marText.contains("हजार"));
        assertTrue(marText.contains("कोटी"));

        final String hinText = TextPreprocessor.preprocessIndianText("कीमत 15k है और 2cr है", "hi-IN");
        assertTrue(hinText.contains("हज़ार"));
        assertTrue(hinText.contains("करोड़"));
    }
}
