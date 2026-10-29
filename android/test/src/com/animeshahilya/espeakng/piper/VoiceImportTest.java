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

    private static void put(TarArchiveOutputStream tar, String name, String text) throws Exception {
        final byte[] b = text.getBytes(StandardCharsets.UTF_8);
        final TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(b.length);
        tar.putArchiveEntry(e);
        tar.write(b);
        tar.closeArchiveEntry();
    }
}
