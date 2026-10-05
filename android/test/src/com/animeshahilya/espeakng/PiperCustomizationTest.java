package com.animeshahilya.espeakng;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Per-voice speed, speaking style, speakers and recently used voices. */
public class PiperCustomizationTest {

    /** SharedPreferences over a map; edit() writes straight into it. */
    private static SharedPreferences prefs(Map<String, Object> values) {
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putString":
                            values.put((String) args[0], args[1]);
                            return proxy;
                        case "remove":
                            values.remove((String) args[0]);
                            return proxy;
                        case "commit":
                            return true;
                        default:
                            return method.getReturnType() == SharedPreferences.Editor.class ? proxy : null;
                    }
                });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) {
                        return editor;
                    }
                    final Object v = values.get((String) args[0]);
                    return v != null ? v : args[1];
                });
    }

    @Test
    public void voiceSpeedOverridesTheSharedOne() {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences p = prefs(values);
        assertEquals(1f, PiperVoiceStore.speedFactor(p, "hi_IN-priyamvada-medium"), 0f);
        values.put(PiperVoiceStore.PREF_SPEED, "125");
        assertEquals(1.25f, PiperVoiceStore.speedFactor(p, "hi_IN-priyamvada-medium"), 0f);
        values.put(PiperVoiceStore.PREF_VOICE_SPEED_PREFIX + "hi_IN-priyamvada-medium", "90");
        assertEquals(0.9f, PiperVoiceStore.speedFactor(p, "hi_IN-priyamvada-medium"), 0f);
        assertEquals(1.25f, PiperVoiceStore.speedFactor(p, "ta_IN-rasa_female-medium"), 0f);
        values.put(PiperVoiceStore.PREF_VOICE_SPEED_PREFIX + "ta_IN-rasa_female-medium", "junk");
        assertEquals(1.25f, PiperVoiceStore.speedFactor(p, "ta_IN-rasa_female-medium"), 0f);
    }

    /** Natural (the default) leaves the voice's own scales alone. */
    @Test
    public void styleScales() {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences p = prefs(values);
        assertArrayEquals(new float[] {1f, 1f}, PiperVoiceStore.styleScales(p), 0f);
        values.put(PiperVoiceStore.PREF_STYLE, PiperVoiceStore.STYLE_STEADY);
        assertArrayEquals(new float[] {0.5f, 0.5f}, PiperVoiceStore.styleScales(p), 0f);
        values.put(PiperVoiceStore.PREF_STYLE, PiperVoiceStore.STYLE_LIVELY);
        assertArrayEquals(new float[] {1.3f, 1.25f}, PiperVoiceStore.styleScales(p), 0f);
    }

    @Test
    public void recentVoicesNewestFirstAndCapped() {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences p = prefs(values);
        PiperVoiceStore.noteUsed(p, Collections.emptyList());
        assertNull(values.get(PiperVoiceStore.PREF_RECENT));
        PiperVoiceStore.noteUsed(p, Arrays.asList("a"));
        PiperVoiceStore.noteUsed(p, Arrays.asList("b", "c"));
        PiperVoiceStore.noteUsed(p, Arrays.asList("d"));
        assertEquals(Arrays.asList("d", "b", "c"), PiperVoiceStore.recent(p));
        PiperVoiceStore.noteUsed(p, Arrays.asList("c"));
        assertEquals(Arrays.asList("c", "d", "b"), PiperVoiceStore.recent(p));
    }

    @Test
    public void speakerNamesById() throws Exception {
        final PiperVoiceConfig c = PiperVoiceConfig.parse("en_GB-vctk-medium",
                "{\"num_speakers\":3,\"speaker_id_map\":{\"p239\":0,\"p236\":2},"
                        + "\"phoneme_id_map\":{\"_\":[0]},\"espeak\":{\"voice\":\"en-gb\"}}");
        assertEquals(3, c.speakerNames.length);
        assertEquals("p239", c.speakerNames[0]);
        assertNull(c.speakerNames[1]);
        assertEquals("p236", c.speakerNames[2]);
    }

    /** Common Voice speakers are named by hash: shown as numbers only. */
    @Test
    public void readableSpeakerNames() {
        org.junit.Assert.assertTrue(PiperSettings.isReadableName("son_of_the_exiles", 0));
        org.junit.Assert.assertTrue(PiperSettings.isReadableName("p239", 0));
        org.junit.Assert.assertFalse(PiperSettings.isReadableName("85084e5a3d8d8c7950d4c6cd07f8d625", 0));
        org.junit.Assert.assertFalse(PiperSettings.isReadableName("3", 3));
        org.junit.Assert.assertFalse(PiperSettings.isReadableName(null, 0));
    }

    @Test
    public void malformedRegionCodesNameNoCountry() {
        // Region codes reach the page from the remote catalog and imported
        // configs; Locale.Builder throws on a bad one.
        assertEquals(new java.util.Locale("", "IN").getDisplayCountry(), PiperSettings.countryName("IN"));
        assertNull(PiperSettings.countryName("US_x"));
        assertNull(PiperSettings.countryName("india"));
        assertNull(PiperSettings.countryName(""));
        assertNull(PiperSettings.countryName(null));
    }
}
