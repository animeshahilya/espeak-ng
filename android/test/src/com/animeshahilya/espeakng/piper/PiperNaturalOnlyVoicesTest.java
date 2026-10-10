package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.text.LanguageRuns;
import com.animeshahilya.espeakng.text.TextPreprocessor;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/** Languages only a natural voice speaks get a TTS voice of their own. */
public class PiperNaturalOnlyVoicesTest {
    private static SharedPreferences prefs(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll":
                            return values;
                        case "getBoolean":
                            final Object v = values.get(args[0]);
                            return v instanceof Boolean ? v : args[1];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private static final List<Voice> ESPEAK = Arrays.asList(
            new Voice("hi", "inc/hi", 0, 0, new Locale("hi")),
            new Voice("ta", "dra/ta", 0, 0, new Locale("ta")),
            new Voice("en-us", "gmw/en-US", 0, 0, new Locale("en", "US")));

    @Test
    public void chhattisgarhiGetsAVoiceReadByHindiRules() {
        final Map<String, Object> values = new HashMap<>();
        values.put(PiperVoiceStore.PREF_ENABLED, true);
        values.put(PiperVoiceStore.PREF_VOICE_PREFIX + "hne", "hne_IN-mamta-medium");
        values.put(PiperVoiceStore.PREF_VOICE_PREFIX + "san", "sa_IN-vedant-medium");
        // eSpeak has these: no extra voice.
        values.put(PiperVoiceStore.PREF_VOICE_PREFIX + "hin", "hi_IN-kavya-medium");
        values.put(PiperVoiceStore.PREF_VOICE_PREFIX + "tam", "ta_IN-kaveri-medium");

        final List<Voice> voices = PiperVoiceStore.naturalOnlyVoices(prefs(values), ESPEAK);
        voices.sort((a, b) -> a.name.compareTo(b.name));
        assertEquals(2, voices.size());
        final Voice hne = voices.get(0);
        assertEquals("hne", hne.name);
        assertEquals("hne", hne.locale.getISO3Language());
        assertEquals("IN", hne.locale.getCountry());
        assertEquals("inc/hi", hne.identifier);
        assertEquals("hi", TextPreprocessor.languageTag(hne));
        assertEquals("hne", PiperVoiceStore.languageKey(hne.locale));
        assertEquals("san", PiperVoiceStore.languageKey(voices.get(1).locale));
    }

    @Test
    public void noneWhileNaturalVoicesAreOff() {
        final Map<String, Object> values = new HashMap<>();
        values.put(PiperVoiceStore.PREF_VOICE_PREFIX + "hne", "hne_IN-mamta-medium");
        assertTrue(PiperVoiceStore.naturalOnlyVoices(prefs(values), ESPEAK).isEmpty());
    }

    @Test
    public void devanagariStaysWithChhattisgarhi() {
        final List<LanguageRuns.Run> runs = LanguageRuns.split("जय जोहार", "hne");
        assertEquals(1, runs.size());
        assertEquals("hne", runs.get(0).language);
    }
}
