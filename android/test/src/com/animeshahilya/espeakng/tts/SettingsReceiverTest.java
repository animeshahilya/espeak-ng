package com.animeshahilya.espeakng.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;

import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.ui.VoiceSettings;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Settings from other apps: what a broadcast's extras change. */
public class SettingsReceiverTest {

    /** SharedPreferences over a map; edit() writes straight into it. */
    static SharedPreferences prefs(Map<String, Object> values) {
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("put")) {
                        values.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if (method.getName().equals("commit")) {
                        return true;
                    }
                    return method.getReturnType() == SharedPreferences.Editor.class ? proxy : null;
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

    private static Map<String, String> extras(String... pairs) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void absoluteValuesAreClamped() {
        final Map<String, Object> values = new HashMap<>();
        assertEquals(2, SettingsReceiver.apply(prefs(values),
                extras("rate", "300", "pitch", "150", "volume", "-5x")));
        assertEquals("300", values.get(VoiceSettings.PREF_RATE));
        assertEquals("100", values.get(VoiceSettings.PREF_PITCH));
        assertNull(values.get(VoiceSettings.PREF_VOLUME)); // not a number: ignored
        values.clear();
        SettingsReceiver.apply(prefs(values), extras("rate", "10"));
        assertEquals("80", values.get(VoiceSettings.PREF_RATE));
    }

    @Test
    public void relativeValuesStartFromTheSavedOneOrTheDefault() {
        final Map<String, Object> values = new HashMap<>();
        values.put(VoiceSettings.PREF_RATE, "200");
        SettingsReceiver.apply(prefs(values), extras("rate", "+25", "volume", "-30"));
        assertEquals("225", values.get(VoiceSettings.PREF_RATE));
        assertEquals("70", values.get(VoiceSettings.PREF_VOLUME)); // from the default 100
    }

    @Test
    public void punctuationIsStoredAsTheDialogStoresIt() {
        final Map<String, Object> values = new HashMap<>();
        SettingsReceiver.apply(prefs(values), extras("punctuation", "most"));
        assertEquals(Integer.toString(SpeechSynthesis.PUNCT_SOME), values.get(VoiceSettings.PREF_PUNCTUATION_LEVEL));
        assertEquals(VoiceSettings.PUNCTUATION_CHARS_MOST, values.get(VoiceSettings.PREF_PUNCTUATION_CHARACTERS));
        SettingsReceiver.apply(prefs(values), extras("punctuation", "none"));
        assertEquals(Integer.toString(SpeechSynthesis.PUNCT_NONE), values.get(VoiceSettings.PREF_PUNCTUATION_LEVEL));
        assertEquals(0, SettingsReceiver.apply(prefs(values), extras("punctuation", "custom")));
    }

    @Test
    public void modesAndSwitchesTakeOnlyKnownValues() {
        final Map<String, Object> values = new HashMap<>();
        assertEquals(2, SettingsReceiver.apply(prefs(values),
                extras("reading_mode", "spelling", "natural_voices", "FALSE", "voice", "x")));
        assertEquals(VoiceSettings.READING_SPELLING, values.get(VoiceSettings.PREF_READING_MODE));
        assertEquals(false, values.get(PiperVoiceStore.PREF_ENABLED));
        assertEquals(0, SettingsReceiver.apply(prefs(values), extras("reading_mode", "fast")));
        assertFalse(values.containsKey("voice"));
    }

    @Test
    public void languageAndEmojiChoices() {
        final Map<String, Object> values = new HashMap<>();
        assertEquals(5, SettingsReceiver.apply(prefs(values), extras("detect_languages", "true",
                "detect_european", "false", "numbers_language", "auto", "emoji", "no_skin_tone",
                "emoji_language", "around")));
        assertEquals(true, values.get(VoiceSettings.PREF_DETECT_SCRIPT_LANGUAGES));
        assertEquals(false, values.get(VoiceSettings.PREF_DETECT_LATIN_LANGUAGES));
        assertEquals("", values.get(VoiceSettings.PREF_NUMBERS_LANGUAGE));
        assertEquals(VoiceSettings.EMOJI_NO_SKIN_TONE, values.get(VoiceSettings.PREF_EMOJI_PROCESSING));
        assertEquals(VoiceSettings.EMOJI_AROUND, values.get(VoiceSettings.PREF_EMOJI_LANGUAGE));
        SettingsReceiver.apply(prefs(values), extras("emoji_language", "voice", "numbers_language", "en"));
        assertEquals("", values.get(VoiceSettings.PREF_EMOJI_LANGUAGE));
        assertEquals(VoiceSettings.NUMBERS_ENGLISH, values.get(VoiceSettings.PREF_NUMBERS_LANGUAGE));
        assertEquals(0, SettingsReceiver.apply(prefs(values), extras("emoji", "loud", "detect_languages", "yes",
                "emoji_language", "loud")));
    }
}
