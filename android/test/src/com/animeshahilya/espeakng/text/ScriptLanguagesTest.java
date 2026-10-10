package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.ScriptLanguages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.Character.UnicodeScript;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** The per-script language choices of Settings -> Mixed-language text. */
public class ScriptLanguagesTest {

    /** SharedPreferences over a map: only getString/getBoolean are used. */
    private static SharedPreferences prefs(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    final Object v = values.get((String) args[0]);
                    return v != null ? v : args[1];
                });
    }

    /** Nothing chosen: eSpeak's own switching, nothing sent to the engine. */
    @Test
    public void defaultChoosesNothing() {
        final SharedPreferences p = prefs(new HashMap<>());
        assertTrue(ScriptLanguages.chosen(p).isEmpty());
        assertTrue(ScriptLanguages.naturalSwitching(p));
    }

    @Test
    public void chosenKeepsOnlyOfferedVoices() {
        final Map<String, Object> values = new HashMap<>();
        values.put("espeak_script_hi", "mr");
        values.put("espeak_script_latin", "en-in");
        values.put("espeak_script_cyr", "fr");   // not offered for Cyrillic
        values.put("espeak_script_he", "");      // eSpeak default
        final Map<String, String> chosen = ScriptLanguages.chosen(prefs(values));
        assertEquals("{hi=mr, latin=en-in}", chosen.toString());
    }

    @Test
    public void runLanguagesAreNaturalVoiceKeys() {
        final Map<String, String> chosen = new HashMap<>();
        chosen.put("hi", "mr");
        chosen.put("latin", "en-in");
        chosen.put("zh", "cmn");
        chosen.put("ja", "ja");
        final Map<UnicodeScript, String> runs = ScriptLanguages.runLanguages(chosen);
        assertEquals("mar", runs.get(UnicodeScript.DEVANAGARI));
        assertEquals("eng", runs.get(UnicodeScript.LATIN));
        assertEquals("zho", runs.get(UnicodeScript.HAN));
        assertEquals("jpn", runs.get(UnicodeScript.HIRAGANA));
        assertEquals("jpn", runs.get(UnicodeScript.KATAKANA));
    }

    /** Every script row has a unique key and at least one choice. */
    @Test
    public void scriptsAreWellFormed() {
        final Map<String, Boolean> seen = new HashMap<>();
        for (ScriptLanguages.Script s : ScriptLanguages.SCRIPTS) {
            assertTrue(s.key, seen.put(s.key, true) == null);
            assertTrue(s.key, s.choices.length > 0);
            assertTrue(s.key, s.scripts.length > 0);
        }
    }

    @Test
    public void runLanguagesConcurrentCacheAccess() throws Exception {
        final Map<String, String> mapA = new HashMap<>();
        mapA.put("hi", "mr");
        final Map<String, String> mapB = new HashMap<>();
        mapB.put("hi", "hi");

        final int threads = 8;
        final int iterations = 1000;
        final java.util.concurrent.atomic.AtomicBoolean failure = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final boolean chooseA = (i % 2 == 0);
            pool.execute(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        Map<String, String> chosen = chooseA ? mapA : mapB;
                        String expected = chooseA ? "mar" : "hin";
                        Map<UnicodeScript, String> res = ScriptLanguages.runLanguages(chosen);
                        if (!expected.equals(res.get(UnicodeScript.DEVANAGARI))) {
                            failure.set(true);
                            break;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        assertFalse("Race condition detected in runLanguages cache!", failure.get());
    }
}
