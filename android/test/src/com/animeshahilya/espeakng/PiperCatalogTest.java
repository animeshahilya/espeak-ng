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
            + " \"language\": {\"code\": \"en_US\", \"family\": \"en\"}, \"quality\": \"medium\","
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

    private static String entry(String key, String quality) {
        final String name = key.split("-")[1];
        return "\"" + key + "\": {\"key\": \"" + key + "\", \"name\": \"" + name + "\","
                + " \"language\": {\"code\": \"en_US\", \"family\": \"en\"}, \"quality\": \""
                + quality + "\", \"files\": {\"" + key + ".onnx\": {}, \"" + key + ".onnx.json\": {}}}";
    }

    @Test
    public void offersStandardAndEnhancedOnly() throws Exception {
        final List<PiperDownloads.CatalogVoice> voices = PiperDownloads.parseCatalog("{"
                + entry("en_US-amy-high", "high") + ","
                + entry("en_US-amy-low", "low") + ","
                + entry("en_US-amy-x_low", "x_low") + ","
                + entry("en_US-amy-medium", "medium") + "}");
        assertEquals(2, voices.size());
        // Same voice: Standard (medium) listed before Enhanced (high).
        assertEquals("en_US-amy-medium", voices.get(0).key);
        assertEquals("en_US-amy-high", voices.get(1).key);
        assertTrue(PiperDownloads.isEnhanced(voices.get(1).quality));
        assertFalse(PiperDownloads.isEnhanced(voices.get(0).quality));
    }

    /** base_url is honored only in the bundled list: the remote catalog cannot redirect downloads. */
    @Test
    public void baseUrlOnlyFromBundledList() throws Exception {
        final String json = "{\"ta_IN-x-medium\": {\"key\": \"ta_IN-x-medium\", \"name\": \"x\","
                + " \"language\": {\"code\": \"ta_IN\", \"family\": \"ta\"}, \"quality\": \"medium\","
                + " \"base_url\": \"https://huggingface.co/someone/voice/resolve/abc/\","
                + " \"source\": \"someone\", \"license\": \"MIT\","
                + " \"files\": {\"a.onnx\": {}, \"a.onnx.json\": {}}}}";
        final PiperDownloads.CatalogVoice remote = PiperDownloads.parseCatalog(json).get(0);
        assertEquals(PiperDownloads.REPO_BASE, remote.baseUrl);
        assertEquals(null, remote.source);
        final PiperDownloads.CatalogVoice bundled = PiperDownloads.parseCatalog(json, true).get(0);
        assertEquals("https://huggingface.co/someone/voice/resolve/abc/", bundled.baseUrl);
        assertEquals("someone", bundled.source);
        assertEquals(null, bundled.sampleUrl());
        // Anything but a Hugging Face repo is dropped.
        assertEquals(0, PiperDownloads.parseCatalog(json.replace("https://huggingface.co/",
                "http://evil.example/"), true).size());
    }

    /** The shipped extras file parses, every entry is kept, and each is pinned with checksums. */
    @Test
    public void bundledExtrasParse() throws Exception {
        final String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", PiperDownloads.EXTRA_CATALOG_ASSET)), "UTF-8");
        final List<PiperDownloads.CatalogVoice> voices = PiperDownloads.parseCatalog(json, true);
        assertEquals(10, voices.size());
        for (PiperDownloads.CatalogVoice v : voices) {
            assertTrue(v.key, v.baseUrl.matches("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/"));
            assertEquals(v.key, 32, v.modelMd5.length());
            assertTrue(v.key, v.modelSize > 10_000_000L);
            assertTrue(v.key, v.license != null && v.source != null);
        }
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
