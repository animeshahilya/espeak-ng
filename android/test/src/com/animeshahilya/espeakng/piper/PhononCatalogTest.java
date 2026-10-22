package com.animeshahilya.espeakng.piper;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhononCatalogTest {
    private static List<PiperDownloads.CatalogVoice> bundled() throws Exception {
        return PiperDownloads.parseCatalog(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets", "piper", "phonon_voices.json")), StandardCharsets.UTF_8), true);
    }

    @Test
    public void bundledCatalogListsEveryVoice() throws Exception {
        final List<PiperDownloads.CatalogVoice> voices = bundled();
        assertEquals(37, voices.size());
        final Set<String> files = new HashSet<>();
        for (PiperDownloads.CatalogVoice v : voices) {
            assertTrue(v.key, PiperDownloads.isPhonon(v));
            assertEquals("en", v.family);
            assertTrue(v.key, v.modelPath.startsWith("phonon-v1/") && v.modelPath.endsWith(".bin"));
            assertEquals(PiperDownloads.RESPIN_SYSPIN_RELEASES, v.baseUrl);
            assertEquals("Apache-2.0", v.license);
            assertTrue("shared metadata, tokenizer, BOS", v.extraFiles.keySet()
                    .containsAll(PiperVoiceConfig.PHONON_FILES));
            files.add(v.modelMd5);
        }
        assertEquals("a voice file each", voices.size(), files.size());
    }

    /** Where Phonon is offered, US/UK Piper voices leave the list; Indian English and other languages stay. */
    @Test
    public void phononReplacesUsAndUkPiperVoices() throws Exception {
        final List<PiperDownloads.CatalogVoice> all = new ArrayList<>(bundled());
        all.add(voice("en_US-amy-medium", "en", "US"));
        all.add(voice("en_GB-cori-high", "en", "GB"));
        all.add(voice("en_IN-rahul-medium", "en", "IN"));
        all.add(voice("hi_IN-priyamvada-medium", "hi", "IN"));
        final List<String> kept = new ArrayList<>();
        for (PiperDownloads.CatalogVoice v : PiperDownloads.phononForEnglish(all)) {
            kept.add(v.key);
        }
        assertEquals(37 + 2, kept.size());
        assertTrue(kept.contains("en_IN-rahul-medium"));
        assertTrue(kept.contains("hi_IN-priyamvada-medium"));
        assertFalse(kept.contains("en_US-amy-medium"));
        assertFalse(kept.contains("en_GB-cori-high"));
    }

    @Test
    public void phononConfigNeedsItsRuntime() throws Exception {
        final org.json.JSONObject files = new org.json.JSONObject();
        for (String name : PiperVoiceConfig.PHONON_FILES) {
            files.put(name, new org.json.JSONObject().put("path", "phonon-v1/x").put("size_bytes", 1)
                    .put("md5_digest", "0123456789abcdef0123456789abcdef"));
        }
        final org.json.JSONObject cfg = new org.json.JSONObject()
                .put("audio", new org.json.JSONObject().put("sample_rate", 24000))
                .put("phoneme_type", "phonon")
                .put("language", new org.json.JSONObject().put("code", "en_US").put("family", "en"))
                .put("phoneme_id_map", new org.json.JSONObject().put("_", new org.json.JSONArray().put(0)))
                .put("phonon", new org.json.JSONObject().put("voice", "Easton").put("files", files));
        final PiperVoiceConfig c = PiperVoiceConfig.parse("en_US-phonon_easton-medium", cfg.toString());
        assertTrue(c.isPhonon());
        assertFalse(c.usesEspeak());
        assertEquals("Easton", c.phononVoice);
        assertEquals(3, c.companionFiles.size());
        // No native runtime on the JVM (or in the standard build): never offered as supported.
        assertFalse(c.isSupported());
    }

    private static PiperDownloads.CatalogVoice voice(String key, String family, String region) {
        final PiperDownloads.CatalogVoice v = new PiperDownloads.CatalogVoice();
        v.key = key;
        v.family = family;
        v.region = region;
        return v;
    }
}
