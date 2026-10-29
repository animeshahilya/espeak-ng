/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.animeshahilya.espeakng.ui.VoiceSettings;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Mixed languages -> Emoji: which language names an emoji. */
public class EmojiLanguageTest {
    private static final String SMILE = "😊";

    @BeforeClass
    public static void data() {
        BundledData.useDirectory(new File("data"));
    }

    private static VoiceSettings settings(String emoji, boolean detect) {
        final Map<String, Object> values = new HashMap<>();
        values.put(VoiceSettings.PREF_EMOJI_LANGUAGE, emoji);
        values.put(VoiceSettings.PREF_DETECT_SCRIPT_LANGUAGES, detect);
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    final Object v = args != null && args.length > 0 ? values.get((String) args[0]) : null;
                    return v != null ? v : args != null && args.length > 1 ? args[1] : null;
                });
        return new VoiceSettings(prefs, null);
    }

    private static String name(String language) {
        return NvdaEmoji.substitute(SMILE, language).trim();
    }

    @Test
    public void namesDiffer() {
        // The test means nothing if the data names it alike.
        assertTrue(!name("en").equals(name("uk")) && !name("en").equals(name("hi"))
                && !name("en").equals(name("ru")));
    }

    @Test
    public void voiceLanguageByDefault() {
        final String out = TextPreprocessor.nameEmoji("Привіт, як справи? " + SMILE, "en", "eng",
                settings(VoiceSettings.EMOJI_IN_VOICE, false));
        assertTrue(out, out.contains(name("en")));
    }

    @Test
    public void wordsAroundThem() {
        final VoiceSettings around = settings(VoiceSettings.EMOJI_AROUND, false);
        String out = TextPreprocessor.nameEmoji("Hello. Привіт, як справи? " + SMILE + " Bye.", "en", "eng", around);
        assertTrue(out, out.contains(name("uk")) && !out.contains(name("en")));
        out = TextPreprocessor.nameEmoji("Meeting at ten. मैं ठीक हूँ " + SMILE, "en", "eng", around);
        assertTrue(out, out.contains(name("hi")));
        // In the voice's own words: the voice's language.
        out = TextPreprocessor.nameEmoji("All good " + SMILE + " Привет", "en", "eng", around);
        assertTrue(out, out.contains(name("en")));
        // A Russian voice with a Ukrainian sentence, the emoji after its "?".
        out = TextPreprocessor.nameEmoji("Как дела? Дякую, все добре? " + SMILE, "ru", "rus", around);
        assertTrue(out, out.contains(name("uk")) && !out.contains(name("ru")));
    }

    @Test
    public void alwaysEnglish() {
        final String out = TextPreprocessor.nameEmoji("Как дела? " + SMILE, "ru", "rus",
                settings(VoiceSettings.EMOJI_ENGLISH, false));
        assertTrue(out, out.contains(name("en")));
    }

    @Test
    public void languageTags() {
        assertEquals("ru", LanguageRuns.languageTag("rus"));
        assertEquals("hi", LanguageRuns.languageTag("hin"));
        assertEquals("ar", LanguageRuns.languageTag("ara"));
        assertEquals("ckb", LanguageRuns.languageTag("ckb"));
    }
}
