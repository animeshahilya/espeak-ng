package com.animeshahilya.espeakng.piper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * tiny-voca ids from eSpeak IPA. Expected ids come from the PC adapter the
 * voices were graded with (scratch/voice-eval/tinyvoca.py, tokenize), packed
 * as id | stress << 16; the config is the hosted one (sherpa-onnx-respin-syspin
 * build_tinyvoca.py, tinyvoca-v1).
 */
public class TinyVocaTest {
    private static PiperVoiceConfig config() throws Exception {
        return PiperVoiceConfig.parse("en_US-tiny_voca_m2-medium", new String(Files.readAllBytes(
                Paths.get("test", "resources", "tiny_voca_m2.onnx.json")), StandardCharsets.UTF_8));
    }

    @Test
    public void configReadsAsAnEspeakVoiceOfItsOwnKind() throws Exception {
        final PiperVoiceConfig c = config();
        assertTrue(c.isTinyVoca());
        assertTrue(c.usesEspeak());
        assertTrue(c.isSupported());
        assertEquals("en-us", c.espeakVoice);
        assertEquals(24000, c.sampleRate);
        assertEquals(1, c.defaultSpeakerId);
        assertEquals("Tiny Voca M2", c.displayName());
        // Squeezed past 1.25 it is less clear than libsonic stretching (S25 check set)
        assertEquals(1.25f, PiperEngine.maxModelSpeed(c), 0f);
    }

    @Test
    public void idsMatchTheGradedAdapter() throws Exception {
        final PiperVoiceConfig c = config();
        final Object[][] cases = {
            {"həlˈoʊ!", new long[] {1, 31, 74, 65598, 8, 2}},
            {"ðɪs ɪz tˈaɪni vˈoʊkə, ", new long[] {1, 26, 47, 27, 3, 47, 28, 3, 19, 65596, 33, 48, 3, 24,
                65598, 21, 43, 5, 2}},
            {"sˌoʊ wiː hæv ɐbˌaʊt fˈɔːɹɾi fˈaɪv mˈɪnɪts lˈɛft.", new long[] {1, 27, 131134, 3, 38, 49, 3, 31,
                51, 24, 3, 44, 18, 131135, 19, 3, 23, 65603, 41, 48, 3, 23, 65596, 24, 3, 32, 65583, 33, 47, 19,
                27, 3, 35, 65586, 23, 19, 6, 2}},
            // syllabic n carries stress, a closing '.' is added
            {"bˈʌʔn̩ ðə kˈæt", new long[] {1, 18, 65588, 42, 75, 3, 26, 43, 3, 21, 65587, 19, 6, 2}},
            {"kjˈʊɹ, fˈaɪɚ? jɛs!", new long[] {1, 21, 37, 65607, 5, 3, 23, 65608, 7, 3, 37, 50, 27, 8, 2}},
            // Sounds the voice lacks take its nearest one (Kyrgyzstan r -> ɹ),
            // keeping their stress (blanc ɑ̃ -> ɑː, the nasal mark dropped)
            {"kˌɜːrɡɪstˈɑːn", new long[] {1, 21, 131130, 36, 22, 47, 27, 19, 65589, 33, 6, 2}},
            {"blˈɑ̃ŋk", new long[] {1, 18, 35, 65589, 34, 21, 6, 2}},
        };
        for (Object[] t : cases) {
            assertArrayEquals((String) t[0], (long[]) t[1], TinyVoca.ids((String) t[0], c, null));
        }
    }

    @Test
    public void wordStartsAndUnpacking() throws Exception {
        final List<Integer> starts = new ArrayList<>();
        final long[] ids = TinyVoca.ids("ðə kˈæt, sˈæt.", config(), starts);
        assertEquals(Arrays.asList(1, 4, 9), starts);
        assertArrayEquals(new long[] {1, 26, 43, 3, 21, 51, 19, 5, 3, 27, 51, 19, 6, 2}, TinyVoca.tokens(ids));
        assertArrayEquals(new long[] {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0}, TinyVoca.stress(ids));
    }

    @Test
    public void paceFollowsThePiperVoices() {
        assertEquals(0.876f, TinyVoca.pace(1f), 1e-4f);
        assertEquals(0.5f * 0.876f, TinyVoca.pace(0.5f), 1e-4f);      // slower: the 1.0 factor
        assertEquals(1.8f * 0.772f, TinyVoca.pace(1.8f), 1e-4f);
        assertEquals(2.57f * 0.772f, TinyVoca.pace(2.57f), 1e-4f);    // the user's 450 wpm
        assertEquals(1.15f * (0.833f + 0.808f) / 2, TinyVoca.pace(1.15f), 1e-4f);
        float last = 0f;
        for (float s = 0.3f; s < 4f; s += 0.01f) {
            final float p = TinyVoca.pace(s);
            assertTrue("faster asked must stay faster at " + s, p > last);
            last = p;
        }
    }

    @Test
    public void tinyVocaInputsAreRecognised() {
        assertTrue(PiperModel.hasTinyVocaInputs(new java.util.HashSet<>(Arrays.asList(
                "tokens", "stress", "voice", "speed", "pitch_shift", "pitch_range"))));
    }
}
