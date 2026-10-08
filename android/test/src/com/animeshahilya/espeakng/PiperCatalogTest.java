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
            + "   \"md5_digest\": \"0123456789abcdef0123456789abcdef\"},"
            + "  \"hi/hi_IN/priyamvada/medium/MODEL_CARD\": {\"size_bytes\": 1, \"md5_digest\": \"x\"}},"
            + " \"aliases\": []},"
            + "\"../evil\": {\"key\": \"../evil\", \"name\": \"evil\","
            + " \"language\": {\"code\": \"en_US\", \"family\": \"en\"}, \"quality\": \"medium\","
            + " \"files\": {\"a.onnx\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}, \"a.onnx.json\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}}}"
            + "}";

    @Test
    public void rejectsMissingOrPathLikeChecksums() throws Exception {
        // The MD5 names the shared copy (shared/<md5>.onnx) and every download
        // is verified against it: no checksum or a path in it, no voice.
        final String entry = "{\"x\": {\"key\": \"x\", \"name\": \"x\","
                + " \"language\": {\"code\": \"en_US\", \"family\": \"en\"}, \"quality\": \"medium\","
                + " \"files\": {\"a.onnx\": {\"md5_digest\": \"%s\"},"
                + " \"a.onnx.json\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}}}}";
        assertEquals(0, PiperDownloads.parseCatalog(String.format(entry, "../../files/x")).size());
        assertEquals(0, PiperDownloads.parseCatalog(entry.replace("\"md5_digest\": \"%s\"", "")).size());
        assertEquals(1, PiperDownloads.parseCatalog(
                String.format(entry, "0123456789abcdef0123456789abcdef")).size());
    }

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
                + quality + "\", \"files\": {\"" + key + ".onnx\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}, \"" + key + ".onnx.json\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}}}";
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
                + " \"files\": {\"a.onnx\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}, \"a.onnx.json\": {\"md5_digest\": \"0123456789abcdef0123456789abcdef\"}}}}";
        final PiperDownloads.CatalogVoice remote = PiperDownloads.parseCatalog(json).get(0);
        assertEquals(PiperDownloads.REPO_BASE, remote.baseUrl);
        assertEquals(null, remote.source);
        final PiperDownloads.CatalogVoice bundled = PiperDownloads.parseCatalog(json, true).get(0);
        assertEquals("https://huggingface.co/someone/voice/resolve/abc/", bundled.baseUrl);
        assertEquals("someone", bundled.source);
        assertEquals(PiperDownloads.EXTRA_SAMPLES + bundled.key + ".mp3", bundled.sampleUrl());
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
        assertEquals(135, voices.size());
        // Only kept voices are bundled: none is filtered out again.
        assertEquals(voices.size(), PiperDownloads.keptOnly(voices, keptAsset()).size());
        for (PiperDownloads.CatalogVoice v : voices) {
            if (PiperDownloads.RESPIN_SYSPIN_RELEASES.equals(v.baseUrl)) {
                // Release assets: each path starts with its tag; the MD5s pin the bytes.
                // Every model is the INT8-weight build (int8-v1).
                if (PiperDownloads.isCompact(v.quality)) {
                    assertTrue(v.key, v.key.endsWith("-compact"));
                    assertTrue(v.key, v.modelPath.matches("int8-v1/[^/]+-compact\\.onnx"));
                    assertTrue(v.key, v.configPath.matches("(compact-v1|piper-v2)/[^/]+\\.onnx\\.json"));
                } else if (v.heavy) {
                    // SYSPIN / Rasa Standard
                    assertTrue(v.key, v.modelPath.matches("int8-v1/[^/]+\\.onnx"));
                    assertTrue(v.key, v.configPath.matches("piper-v2/[^/]+\\.onnx\\.json"));
                } else {
                    // A Piper or community voice under its own key
                    assertEquals(v.key, "int8-v1/" + v.key + ".onnx", v.modelPath);
                    assertEquals(v.key, "int8-v1/" + v.key + ".onnx.json", v.configPath);
                }
            } else {
                assertTrue(v.key, v.baseUrl.matches("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/"));
            }
            assertEquals(v.key, 32, v.modelMd5.length());
            assertTrue(v.key, v.modelSize > 10_000_000L);
            assertTrue(v.key, v.license != null && v.source != null);
        }
    }

    private static java.util.Map<String, java.util.Set<String>> keptAsset() throws Exception {
        return PiperDownloads.parseKept(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", PiperDownloads.KEPT_ASSET)), "UTF-8"));
    }

    /** A bundled entry replaces Piper's of the same key (its INT8 copy); the rest keep their order. */
    @Test
    public void bundledEntryReplacesPipersOfTheSameKey() {
        final List<PiperDownloads.CatalogVoice> piper = new java.util.ArrayList<>();
        final List<PiperDownloads.CatalogVoice> extras = new java.util.ArrayList<>();
        for (String key : new String[] {"hi_IN-priyamvada-medium", "hi_IN-pratham-medium"}) {
            final PiperDownloads.CatalogVoice v = new PiperDownloads.CatalogVoice();
            v.key = key;
            piper.add(v);
        }
        final PiperDownloads.CatalogVoice small = new PiperDownloads.CatalogVoice();
        small.key = "hi_IN-priyamvada-medium";
        small.baseUrl = PiperDownloads.RESPIN_SYSPIN_RELEASES;
        extras.add(small);
        final List<PiperDownloads.CatalogVoice> out = PiperDownloads.mergeExtras(piper, extras);
        assertEquals(2, out.size());
        assertSame(small, out.get(0));
        assertEquals("hi_IN-pratham-medium", out.get(1).key);
    }

    @Test
    public void keptOnlyFiltersTestedLanguages() throws Exception {
        final java.util.Map<String, java.util.Set<String>> kept =
                PiperDownloads.parseKept("{\"hi\": [\"hi_IN-rohan-medium\", \"hi_IN-priyamvada-medium\"]}");
        final List<PiperDownloads.CatalogVoice> in = new java.util.ArrayList<>();
        for (String key : new String[] {"hi_IN-rohan-medium", "hi_IN-rohan-compact",
                "hi_IN-pratham-medium", "bho_IN-kajal-medium"}) {
            final PiperDownloads.CatalogVoice v = new PiperDownloads.CatalogVoice();
            v.key = key;
            v.family = key.substring(0, key.indexOf('_'));
            in.add(v);
        }
        final List<PiperDownloads.CatalogVoice> out = PiperDownloads.keptOnly(in, kept);
        assertEquals(3, out.size());  // a kept voice, its Compact version, an untested language
        for (PiperDownloads.CatalogVoice v : out) {
            assertNotEquals("hi_IN-pratham-medium", v.key);
        }
        // The shipped list: two voices at most per language (each with its
        // Compact key), plus Indian English Rahul for English.
        for (java.util.Map.Entry<String, java.util.Set<String>> e : keptAsset().entrySet()) {
            assertTrue(e.getKey(), e.getValue().size() <= ("en".equals(e.getKey()) ? 6 : 4));
        }
        assertTrue(keptAsset().get("en").contains("en_IN-rahul-medium"));
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

    /** The Snapdragon build's NPU decoders: our release only, pinned, Standard keys of the catalog. */
    @Test
    public void bundledNpuDecodersArePinnedToOurRelease() throws Exception {
        final org.json.JSONObject list = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", PiperDownloads.NPU_DECODERS_ASSET)), "UTF-8"));
        assertEquals(PiperDownloads.RESPIN_SYSPIN_RELEASES, list.getString("base_url"));
        final org.json.JSONObject voices = list.getJSONObject("voices");
        assertTrue(voices.length() >= 30);
        final java.util.Iterator<String> keys = voices.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final org.json.JSONObject f = voices.getJSONObject(key);
            assertFalse(key, key.endsWith("-compact"));
            assertTrue(key, f.getString("path").matches("npu-v1/[^/]+-npu\\.onnx"));
            assertTrue(key, PiperDownloads.isSafePath(f.getString("path")));
            assertEquals(key, 32, f.getString("md5_digest").length());
            assertTrue(key, f.getLong("size_bytes") > 1_000_000L);
        }
    }
}
