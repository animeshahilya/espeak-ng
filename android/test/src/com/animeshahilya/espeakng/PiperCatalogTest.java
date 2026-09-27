package com.animeshahilya.espeakng;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class PiperCatalogTest {

    /** Two entries in the shape of rhasspy/piper-voices' voices.json. */
    private static final String CATALOG = "{"
            + "\"hi_IN-priyamvada-medium\": {\"key\": \"hi_IN-priyamvada-medium\", \"name\": \"priyamvada\","
            + " \"language\": {\"code\": \"hi_IN\", \"family\": \"hi\", \"region\": \"IN\","
            + "  \"name_native\": \"हिन्दी\", \"name_english\": \"Hindi\","
            + "  \"country_english\": \"India\"},"
            + " \"quality\": \"medium\", \"num_speakers\": 1, \"speaker_id_map\": {},"
            + " \"files\": {"
            + "  \"hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx\": {\"size_bytes\": 63516050,"
            + "   \"md5_digest\": \"7d5e20c2d1e72de8ed772f222e679626\"},"
            + "  \"hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx.json\": {\"size_bytes\": 4973,"
            + "   \"md5_digest\": \"abc\"},"
            + "  \"hi/hi_IN/priyamvada/medium/MODEL_CARD\": {\"size_bytes\": 1, \"md5_digest\": \"x\"}},"
            + " \"aliases\": []},"
            + "\"../evil\": {\"key\": \"../evil\", \"name\": \"evil\","
            + " \"language\": {\"code\": \"en_US\", \"family\": \"en\"}, \"quality\": \"low\","
            + " \"files\": {\"a.onnx\": {}, \"a.onnx.json\": {}}}"
            + "}";

    @Test
    public void parsesEntriesAndRejectsUnsafeKeys() throws Exception {
        final List<PiperDownloads.CatalogVoice> voices = PiperDownloads.parseCatalog(CATALOG);
        assertEquals(1, voices.size());
        final PiperDownloads.CatalogVoice v = voices.get(0);
        assertEquals("hi_IN-priyamvada-medium", v.key);
        assertEquals("Priyamvada", v.displayName());
        assertEquals("hi", v.family);
        assertEquals("India", v.country);
        assertEquals(63516050L, v.modelSize);
        assertEquals("7d5e20c2d1e72de8ed772f222e679626", v.modelMd5);
        assertTrue(v.configPath.endsWith(".onnx.json"));
        assertEquals("https://rhasspy.github.io/piper-samples/samples/hi/hi_IN/priyamvada/medium/speaker_0.mp3",
                v.sampleUrl());
    }

    @Test
    public void nameFromKey() {
        assertEquals("Libritts R", PiperDownloads.nameFromKey("en_US-libritts_r-medium"));
        assertEquals("Amy", PiperDownloads.nameFromKey("en_US-amy-low"));
    }

    @Test
    public void md5() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                PiperDownloads.md5("abc".getBytes()));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void languageKeysBridgeLegacyCodes() {
        // Piper says "id", Java may say "in": both must meet at "ind".
        assertEquals(PiperVoiceStore.languageKey("id"), PiperVoiceStore.languageKey(new Locale("in")));
        assertEquals("hin", PiperVoiceStore.languageKey("hi"));
        assertEquals(PiperVoiceStore.languageKey("en"), PiperVoiceStore.languageKey(Locale.US));
    }
}
