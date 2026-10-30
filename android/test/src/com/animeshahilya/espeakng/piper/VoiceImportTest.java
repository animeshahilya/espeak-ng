package com.animeshahilya.espeakng.piper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Importing a sherpa-onnx or Piper voice the user picked (VoiceImport). */
public class VoiceImportTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fileNamesGiveLanguageAndName() {
        VoiceImport.Hint h = VoiceImport.Hint.of("vits-piper-en_US-amy-low.tar.bz2");
        assertEquals("en", h.family);
        assertEquals("US", h.region);
        assertEquals("amy", h.slug);
        h = VoiceImport.Hint.of("vits-coqui-bn-custom_female.tar.bz2");
        assertEquals("bn", h.family);
        assertNull(h.region);
        assertEquals("custom_female", h.slug);
        h = VoiceImport.Hint.of("de_DE-thorsten-high.onnx");
        assertEquals("de", h.family);
        assertEquals("thorsten", h.slug);
        h = VoiceImport.Hint.of("amy-low.onnx");
        assertNull(h.family); // a name, not a language
        assertEquals("amy", h.slug);
        h = VoiceImport.Hint.of("model.onnx");
        assertNull(h.family);
        assertEquals("voice", h.slug);
    }

    @Test
    public void modelNameComesBeforeArchiveName() {
        // A MultiVoice export: the archive's name does not start with the voice's.
        VoiceImport.Hint h = VoiceImport.Hint.of("en_GB-jenny_dioco-medium.onnx",
                "MultiVoice-Sherpa-ONNX-vits-piper-en_GB-jenny_dioco-medium.zip");
        assertEquals("en", h.family);
        assertEquals("GB", h.region);
        assertEquals("jenny_dioco", h.slug);
        // Coqui archives name the model "model.onnx": the archive tells.
        h = VoiceImport.Hint.of("model.onnx", "vits-coqui-bn-custom_female.tar.bz2");
        assertEquals("bn", h.family);
        assertEquals("custom_female", h.slug);
    }

    private static Map<String, String> meta(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void piperTokensKeepTheirIdsAndEspeakVoice() throws Exception {
        final String tokens = "_ 0\r\n^ 1\r\n$ 2\r\n 3\r\na 14\r\nə 59\r\n";
        final JSONObject c = VoiceImport.configFromTokens(tokens, meta("model_type", "vits", "comment", "piper",
                "voice", "en-us", "sample_rate", "22050", "n_speakers", "1", "language", "English"));
        assertEquals("espeak", c.getString("phoneme_type"));
        assertEquals("en-us", c.getJSONObject("espeak").getString("voice"));
        final JSONObject ids = c.getJSONObject("phoneme_id_map");
        assertEquals(3, ids.getJSONArray(" ").getInt(0)); // the space line, CR stripped
        assertEquals(59, ids.getJSONArray("ə").getInt(0));
        VoiceImport.fillLanguage(c, VoiceImport.Hint.of("vits-piper-en_US-amy-low.tar.bz2"));
        assertEquals("en_US", c.getJSONObject("language").getString("code"));
        final PiperVoiceConfig parsed = PiperVoiceConfig.parse("en_US-amy-imported", c.toString());
        assertTrue(parsed.isSupported());
        assertEquals("Amy", parsed.displayName());
    }

    @Test
    public void coquiLetterVoicesMapPadAndEndsToTheBlank() throws Exception {
        final String tokens = "<PAD> 0\nক 1\n<BOS> 2\n<BLNK> 3\nা 4\n 5\n";
        final JSONObject c = VoiceImport.configFromTokens(tokens, meta("model_type", "vits", "comment", "coqui",
                "frontend", "characters", "add_blank", "1", "blank_id", "3", "sample_rate", "22050",
                "n_speakers", "0", "language", "Bengali"));
        assertEquals("text", c.getString("phoneme_type"));
        final JSONObject ids = c.getJSONObject("phoneme_id_map");
        assertFalse(ids.has("<PAD>"));
        assertEquals(3, ids.getJSONArray("_").getInt(0));
        assertEquals(3, ids.getJSONArray("^").getInt(0));
        assertEquals(1, c.getInt("num_speakers"));
        VoiceImport.fillLanguage(c, VoiceImport.Hint.of("vits-coqui-bn-custom_female.tar.bz2"));
        assertEquals("bn", c.getJSONObject("language").getString("family"));
        assertTrue(PiperVoiceConfig.parse("bn-custom_female-imported", c.toString()).isSupported());
    }

    @Test
    public void otherModelsAreRefused() {
        assertRefused(meta("model_type", "vits", "comment", "mms", "frontend", "characters", "add_blank", "0",
                "sample_rate", "16000"));
        assertRefused(meta("model_type", "kokoro", "sample_rate", "24000"));
        assertRefused(meta("model_type", "vits", "comment", "piper", "sample_rate", "22050")); // no voice
    }

    private static void assertRefused(Map<String, String> meta) {
        try {
            VoiceImport.configFromTokens("_ 0\n^ 1\n$ 2\na 3\n", meta);
            fail("accepted " + meta);
        } catch (VoiceImport.ImportException expected) {
            // refused
        } catch (Exception e) {
            fail(String.valueOf(e));
        }
    }

    @Test
    public void unknownLanguageIsRefused() throws Exception {
        final JSONObject c = new JSONObject().put("phoneme_type", "text");
        try {
            VoiceImport.fillLanguage(c, VoiceImport.Hint.of("model.onnx"));
            fail();
        } catch (VoiceImport.ImportException expected) {
            // refused: the settings screen then asks the user
        }
        // The language the user picked.
        VoiceImport.fillLanguage(c, VoiceImport.Hint.of("model.onnx").withFamily("bho"));
        assertEquals("bho", c.getJSONObject("language").getString("family"));
        assertEquals("bho", c.getJSONObject("language").getString("code"));
        assertEquals("ta", VoiceImport.familyOf("Tamil"));
        assertNull(VoiceImport.familyOf("Klingonese"));
    }

    @Test
    public void archivesKeepOnlyTheVoiceFiles() throws Exception {
        // (.tar.bz2 decodes natively: VoiceImportDeviceTest covers it on a phone.)
        final ByteArrayOutputStream gz = new ByteArrayOutputStream();
        final String longDir = "vits-piper-en_US-a-folder-name-well-past-the-hundred-letters-a-tar-header-holds"
                + "-for-a-file-name-so-gnu-tar-writes-it-separately/";
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(gz))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            put(tar, longDir + "en_US-amy-low.onnx", "weights");
            put(tar, "vits-piper-en_US-amy-low/en_US-amy-low.onnx.json", "{}");
            put(tar, "vits-piper-en_US-amy-low/tokens.txt", "_ 0\n");
            put(tar, "vits-piper-en_US-amy-low/espeak-ng-data/phontab", "x");
            put(tar, "vits-piper-en_US-amy-low/MODEL_CARD", "x");
        }
        final File staging = tmp.newFolder("gz");
        VoiceImport.unpack(new ByteArrayInputStream(gz.toByteArray()), "x.tar.gz", staging,
                new long[] {VoiceImport.MAX_BYTES});
        assertEquals(3, staging.list().length);
        assertEquals("en_US-amy-low.onnx", VoiceImport.pickModel(staging).getName());
        assertEquals("weights", new String(java.nio.file.Files.readAllBytes(
                new File(staging, "en_US-amy-low.onnx").toPath()), StandardCharsets.UTF_8));
        assertEquals("_ 0\n", new String(java.nio.file.Files.readAllBytes(
                new File(staging, "tokens.txt").toPath()), StandardCharsets.UTF_8));

        final ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("v/model.onnx"));
            zip.write("weights".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("v/model.int8.onnx"));
            zip.write("w".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("v/tokens.txt"));
            zip.write("_ 0\n".getBytes(StandardCharsets.UTF_8));
        }
        final File z = tmp.newFolder("zip");
        VoiceImport.unpack(new ByteArrayInputStream(zipBytes.toByteArray()), "v.zip", z,
                new long[] {VoiceImport.MAX_BYTES});
        assertEquals("model.onnx", VoiceImport.pickModel(z).getName()); // the larger one

        final File small = tmp.newFolder("small");
        try {
            VoiceImport.unpack(new ByteArrayInputStream(zipBytes.toByteArray()), "v.zip", small, new long[] {4});
            fail("over the size limit");
        } catch (java.io.IOException expected) {
            // refused
        }
    }

    private static final String PIPER_CONFIG = "{\"phoneme_type\": \"espeak\", \"phoneme_id_map\": {\"_\": [0]}}";

    private File staged(String name, String... files) throws Exception {
        final File dir = tmp.newFolder(name);
        for (int i = 0; i < files.length; i += 2) {
            java.nio.file.Files.write(new File(dir, files[i]).toPath(), files[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return dir;
    }

    @Test
    public void configIsFoundByWhatItHolds() throws Exception {
        // Hugging Face repos: config.json beside model.onnx; a browser's "(1)" copy.
        File dir = staged("hf", "model.onnx", "w", "config.json", PIPER_CONFIG);
        assertEquals("config.json", VoiceImport.pickConfig(dir, VoiceImport.pickModel(dir)).getName());
        dir = staged("browser", "en_US-amy-medium.onnx", "w", "en_US-amy-medium (1).onnx.json", PIPER_CONFIG);
        assertEquals("en_US-amy-medium (1).onnx.json",
                VoiceImport.pickConfig(dir, VoiceImport.pickModel(dir)).getName());
        // A MultiVoice export's multivoice.json is not a config; the model's own is.
        dir = staged("multivoice", "a.onnx", "w", "a.onnx.json", PIPER_CONFIG,
                "multivoice.json", "{\"voices\": []}");
        assertEquals("a.onnx.json", VoiceImport.pickConfig(dir, VoiceImport.pickModel(dir)).getName());
        // Kokoro's config.json is JSON but no Piper config.
        dir = staged("kokoro", "model.onnx", "w", "config.json", "{\"istftnet\": {}, \"vocab\": {}}");
        assertNull(VoiceImport.pickConfig(dir, VoiceImport.pickModel(dir)));
    }

    @Test
    public void mmsLanguagesAndNames() {
        // MMS writes ISO 639-3; the app keys voices by the two-letter code where there is one.
        assertEquals("en", VoiceImport.twoLetter("eng"));
        assertEquals("hi", VoiceImport.twoLetter("hin"));
        assertEquals("bho", VoiceImport.twoLetter("bho")); // none
        assertEquals("en", VoiceImport.familyOf("eng"));
        assertNull(VoiceImport.familyOf("unknown")); // willwade's Hindi says this
        // sherpa-onnx's archive names give the language alone.
        VoiceImport.Hint h = VoiceImport.Hint.of("vits-mms-hin.tar.bz2");
        assertEquals("hi", h.family);
        assertEquals(VoiceImport.Hint.NO_NAME, h.slug);
        h = VoiceImport.Hint.of("vits-mms-eng.tar.bz2");
        assertEquals("en", h.family);
        assertNull(VoiceImport.Hint.of("amy-low.onnx").family); // still a name, not a language
        assertEquals("MMS", PiperVoiceConfig.titleCase("mms"));
        assertEquals("MMS 2", PiperVoiceConfig.titleCase("mms_2"));
        assertEquals("Jenny Dioco", PiperVoiceConfig.titleCase("jenny_dioco"));
        assertEquals(" A", PiperVoiceConfig.titleCase("_a")); // one space per separator, as before
    }

    @Test
    public void mmsInputsAreTold() {
        final java.util.Set<String> mms = new java.util.HashSet<>(java.util.Arrays.asList(
                "x", "x_length", "noise_scale", "length_scale", "noise_scale_w"));
        assertTrue(PiperModel.hasMmsInputs(mms));
        assertFalse(PiperModel.hasPiperInputs(mms));
        final java.util.Set<String> piper = new java.util.HashSet<>(java.util.Arrays.asList(
                "input", "input_lengths", "scales", "sid"));
        assertTrue(PiperModel.hasPiperInputs(piper));
        assertFalse(PiperModel.hasMmsInputs(piper));
    }

    @Test
    public void unnamedFilesTakeTheConfigsDataset() throws Exception {
        final JSONObject config = new JSONObject("{\"dataset\": \"Jenny Dioco\"}");
        assertEquals("jenny_dioco", VoiceImport.slugOf(VoiceImport.Hint.of("model.onnx", "model.onnx"), config));
        assertEquals("amy", VoiceImport.slugOf(VoiceImport.Hint.of("en_US-amy-low.onnx", "x"), config));
        assertEquals("voice", VoiceImport.slugOf(VoiceImport.Hint.of("model.onnx", "model.onnx"),
                new JSONObject()));
    }

    @Test
    public void twoVoicesAreRefusedNotHalfImported() throws Exception {
        final File dir = staged("two", "a.onnx", "w", "a.onnx.json", PIPER_CONFIG,
                "b.onnx", "w", "b.onnx.json", PIPER_CONFIG);
        try {
            VoiceImport.pickConfig(dir, VoiceImport.pickModel(dir));
            fail("two voices");
        } catch (VoiceImport.ImportException e) {
            assertEquals(com.animeshahilya.espeakng.R.string.piper_import_several, e.messageRes);
        }
        // Two loose configs for one model: which one is unknowable.
        final File loose = staged("loose", "model.onnx", "w", "x.json", PIPER_CONFIG, "y.json", PIPER_CONFIG);
        try {
            VoiceImport.pickConfig(loose, VoiceImport.pickModel(loose));
            fail("two configs");
        } catch (VoiceImport.ImportException e) {
            assertEquals(com.animeshahilya.espeakng.R.string.piper_import_several, e.messageRes);
        }
    }

    @Test
    public void otherKindsAreNamed() throws Exception {
        final File empty = tmp.newFolder("empty");
        assertEquals("Kokoro", VoiceImport.otherKind(
                java.util.Collections.singletonList("kokoro-multi-lang-v1_0.tar.bz2"), empty));
        assertNull(VoiceImport.otherKind( // MMS imports (PiperModel.hasMmsInputs)
                java.util.Collections.singletonList("vits-mms-hin.tar.bz2"), empty));
        assertEquals("Kitten TTS", VoiceImport.otherKind(
                java.util.Collections.singletonList("kitten_tts_nano_v0_8.onnx"), empty));
        assertEquals("Kokoro", VoiceImport.otherKind(java.util.Arrays.asList("model.onnx", "config.json"),
                staged("hfkokoro", "config.json", "{\"istftnet\": {}}")));
        // Whole words only for short names; Piper and Coqui voices are not "other".
        assertNull(VoiceImport.otherKind(java.util.Collections.singletonList("vits-piper-en_US-hemms-low.tar.bz2"),
                empty));
        assertNull(VoiceImport.otherKind(java.util.Collections.singletonList("vits-coqui-bn-custom_female.tar.bz2"),
                empty));
    }

    @Test
    public void onlyAConfigIsToldApartFromNothing() throws Exception {
        assertTrue(VoiceImport.hasConfig(staged("cfg", "en_US-amy-medium.onnx.json", PIPER_CONFIG)));
        assertTrue(VoiceImport.hasConfig(staged("tok", "tokens.txt", "_ 0\n")));
        assertFalse(VoiceImport.hasConfig(staged("none", "readme.json", "{}")));
    }

    @Test
    public void largeJsonIsNotKept() throws Exception {
        final ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("voices.json"));
            zip.write(new byte[(int) VoiceImport.MAX_JSON_BYTES + 1]);
            zip.putNextEntry(new ZipEntry("v.onnx.json"));
            zip.write(PIPER_CONFIG.getBytes(StandardCharsets.UTF_8));
        }
        final File dir = tmp.newFolder("bigjson");
        VoiceImport.unpack(new ByteArrayInputStream(zipBytes.toByteArray()), "v.zip", dir,
                new long[] {VoiceImport.MAX_BYTES});
        assertArrayEquals(new String[] {"v.onnx.json"}, dir.list()); // and the entry after it still read
    }

    private static void put(TarArchiveOutputStream tar, String name, String text) throws Exception {
        final byte[] b = text.getBytes(StandardCharsets.UTF_8);
        final TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(b.length);
        tar.putArchiveEntry(e);
        tar.write(b);
        tar.closeArchiveEntry();
    }
}
