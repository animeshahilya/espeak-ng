package com.animeshahilya.espeakng.piper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pocket TTS text preparation and SentencePiece tokens. Expected values come from
 * pocket-tts 4.0.1 itself (prepare_text_prompt and its tokenizer), written by
 * sherpa-onnx-respin-syspin build_pocket.py --cases; the tokenizer and settings are
 * the hosted pocket-v1 files.
 */
public class PocketTest {
    private static String resource(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("test", "resources", name)), StandardCharsets.UTF_8);
    }

    private static Pocket pocket() throws Exception {
        return Pocket.forText(resource("pocket_meta.json"), resource("pocket_tokenizer.json"));
    }

    @Test
    public void preparesAndTokenizesLikePocketTts() throws Exception {
        final Pocket p = pocket();
        final JSONArray cases = new JSONArray(resource("pocket_cases.json"));
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final String text = c.getString("text");
            final Object[] prepared = p.prepare(text);
            assertEquals(text, c.getString("prepared"), prepared[0]);
            assertEquals(text, c.getInt("frames_guess"), ((Integer) prepared[1]).intValue());
            final JSONArray want = c.getJSONArray("ids");
            final long[] ids = p.ids(text);
            // {frames after EOS (the guess + 2: frames_after_eos is null for this model), tokens...}
            assertEquals(text, c.getInt("frames_guess") + 2, ids[0]);
            final long[] tokens = java.util.Arrays.copyOfRange(ids, 1, ids.length);
            final long[] expected = new long[want.length()];
            for (int k = 0; k < expected.length; k++) {
                expected[k] = want.getLong(k);
            }
            assertArrayEquals(text, expected, tokens);
        }
    }

    @Test
    public void emptyTextHasNoIds() throws Exception {
        assertEquals(0, pocket().ids("   ").length);
    }

    @Test
    public void halfFloats() {
        assertEquals(1f, Pocket.halfToFloat((short) 0x3c00), 0f);
        assertEquals(-2f, Pocket.halfToFloat((short) 0xc000), 0f);
        assertEquals(65504f, Pocket.halfToFloat((short) 0x7bff), 0f);
        assertEquals(5.9604645e-8f, Pocket.halfToFloat((short) 0x0001), 0f); // smallest subnormal
        assertEquals(6.097555e-5f, Pocket.halfToFloat((short) 0x03ff), 1e-12f); // largest subnormal
        assertTrue(Float.isNaN(Pocket.halfToFloat((short) 0x7e00)));
        assertEquals(0f, Pocket.halfToFloat((short) 0x0000), 0f);
    }

    @Test
    public void configNamesItsVoiceAndFiles() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("en_US-pocket_alba-medium", resource("pocket_alba.onnx.json"));
        assertTrue(c.isPocket());
        assertTrue(c.readsText());
        assertTrue(c.isSupported());
        assertEquals("alba", c.pocketVoice);
        assertEquals(24000, c.sampleRate);
        assertTrue(c.companionFiles.keySet().containsAll(Pocket.FILES));
        assertEquals(1f, PiperEngine.maxModelSpeed(c), 0f);
    }
}
