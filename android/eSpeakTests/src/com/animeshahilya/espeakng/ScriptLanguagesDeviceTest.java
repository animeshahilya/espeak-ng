package com.animeshahilya.espeakng;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings -> Mixed-language text reaches the native engine: a language
 * chosen for a script changes how its words are read, and clearing it gives
 * eSpeak's own reading back. Phonemes (IPA) through phonemizeForPiper, the
 * same engine state eSpeak speech uses.
 */
@RunWith(AndroidJUnit4.class)
public class ScriptLanguagesDeviceTest {
    private SpeechSynthesis mEngine;

    @Before
    public void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CheckVoiceData.ensureVoiceData(ctx);
        mEngine = new SpeechSynthesis(ctx, new SpeechSynthesis.SynthReadyCallback() {
            @Override public void onSynthDataReady(byte[] audioData) { }
            @Override public void onSynthDataComplete() { }
            @Override public void onSynthWordBoundary(int a, int b, int c) { }
        });
    }

    @After
    public void tearDown() {
        mEngine.setScriptLanguages(Collections.emptyMap());
    }

    private String ipa(String voice, String text, Map<String, String> chosen) {
        mEngine.setScriptLanguages(chosen);
        return mEngine.phonemizeForPiper(voice, text);
    }

    /** Cyrillic in English: spelled letter by letter by default, Russian words when chosen. */
    @Test
    public void cyrillicReadAsRussianWhenChosen() {
        assertFalse(ipa("en", "Привет", Collections.emptyMap()).contains("prʲ"));
        assertTrue(ipa("en", "Привет", Collections.singletonMap("cyr", "ru")).contains("prʲ"));
        assertFalse(ipa("en", "Привет", Collections.emptyMap()).contains("prʲ"));
    }

    /** English words in Hindi: Indian English (o: where British English has əʊ) when chosen. */
    @Test
    public void latinInHindiReadAsIndianEnglishWhenChosen() {
        assertTrue(ipa("hi", "मेरा phone", Collections.emptyMap()).contains("əʊ"));
        final Map<String, String> chosen = new HashMap<>();
        chosen.put("latin", "en-in");
        final String indian = ipa("hi", "मेरा phone", chosen);
        assertTrue(indian, indian.contains("oː"));
        assertFalse(indian, indian.contains("əʊ"));
    }
}
