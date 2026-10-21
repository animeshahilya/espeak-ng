package com.animeshahilya.espeakng.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Presets: saving, matching, switching in turn; profiles keep values as strings. */
public class VoicePresetsTest {

    /** SharedPreferences over a map; edit() writes straight into it. */
    private static SharedPreferences prefs(Map<String, Object> values) {
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("put")) {
                        values.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if (method.getName().equals("remove")) {
                        values.remove((String) args[0]);
                        return proxy;
                    }
                    if (method.getName().equals("commit")) {
                        return true;
                    }
                    return method.getReturnType() == SharedPreferences.Editor.class ? proxy : null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit":
                            return editor;
                        case "contains":
                            return values.containsKey((String) args[0]);
                        default:
                            final Object v = values.get((String) args[0]);
                            return v != null ? v : args[1];
                    }
                });
    }

    @Test
    public void savedPresetsSwitchInTurnAndMatchTheSettings() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences p = prefs(values);
        values.put(VoiceSettings.PREF_RATE, "400");
        values.put(VoiceSettings.PREF_PUNCTUATION_LEVEL, "0");
        VoicePresets.save(p, "Fast navigation");
        values.put(VoiceSettings.PREF_RATE, "170");
        values.put(VoiceSettings.PREF_PUNCTUATION_LEVEL, "2");
        values.put(VoiceSettings.PREF_PUNCTUATION_CHARACTERS, VoiceSettings.PUNCTUATION_CHARS_SOME);
        VoicePresets.save(p, "Relaxed reading");
        assertEquals(Arrays.asList("Fast navigation", "Relaxed reading"), VoicePresets.names(p));
        assertEquals("Relaxed reading", VoicePresets.current(p));

        assertEquals("Fast navigation", VoicePresets.next(p));
        assertEquals("400", values.get(VoiceSettings.PREF_RATE));
        assertEquals("0", values.get(VoiceSettings.PREF_PUNCTUATION_LEVEL));
        // "Fast navigation" saved no character list: its level's list goes too.
        assertFalse(values.containsKey(VoiceSettings.PREF_PUNCTUATION_CHARACTERS));
        assertEquals("Fast navigation", VoicePresets.current(p));
        assertEquals("Relaxed reading", VoicePresets.next(p));

        values.put(VoiceSettings.PREF_RATE, "250"); // changed by hand: no preset matches
        assertNull(VoicePresets.current(p));

        // A preset saved with a setting at its default puts the default back.
        values.remove(VoiceSettings.PREF_PITCH);
        VoicePresets.save(p, "Default pitch");
        values.put(VoiceSettings.PREF_PITCH, "80");
        assertNull(VoicePresets.current(p)); // pitch differs: not in use
        assertTrue(VoicePresets.use(p, "Default pitch"));
        assertFalse(values.containsKey(VoiceSettings.PREF_PITCH));
        assertEquals("Default pitch", VoicePresets.current(p));
        VoicePresets.delete(p, "Default pitch");
        assertFalse(VoicePresets.use(p, "Missing"));
        VoicePresets.delete(p, "Fast navigation");
        assertEquals(Arrays.asList("Relaxed reading"), VoicePresets.names(p));
    }

    @Test
    public void profilesWriteNumbersAsStrings() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        // An older profile file stored the punctuation level as a number.
        assertTrue(VoiceProfile.apply(prefs(values), new org.json.JSONObject()
                .put(VoiceSettings.PREF_PUNCTUATION_LEVEL, 2).put("unknown_key", "x")));
        assertEquals("2", values.get(VoiceSettings.PREF_PUNCTUATION_LEVEL));
        assertFalse(values.containsKey("unknown_key"));
    }
}
