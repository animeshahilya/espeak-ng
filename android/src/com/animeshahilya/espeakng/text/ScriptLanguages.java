/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.ui.VoiceSettings;

import android.content.SharedPreferences;

import java.lang.Character.UnicodeScript;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The language words in each script are read in (Settings -> Mixed-language
 * text). eSpeak switches language by alphabet on its own: Devanagari in
 * English text is read as Hindi, English words in Hindi text as British
 * English, and some scripts (Cyrillic, Hebrew, Telugu...) are spelled letter
 * by letter. Each script here can be given another language instead
 * (espeak_SetScriptLanguage). Unset, the default, keeps eSpeak's own choice,
 * so output stays NVDA's until a user picks one.
 *
 * <p>The same choice routes that script to the chosen language's natural
 * voice ({@link LanguageRuns}). Offered languages were each checked to read
 * their script as words (Thai, Burmese, Hakka and Ancient Greek were not).
 */
public final class ScriptLanguages {
    private ScriptLanguages() {
    }

    public static final String PREF_PREFIX = "espeak_script_";

    /** Natural voices take over runs in other scripts (on by default, as before). */
    public static final String PREF_NATURAL_SWITCHING = "piper_switch_languages";

    public static final class Script {
        /** Native alphabet name, see espeak_SetScriptLanguage. */
        public final String key;
        /** Voice named as eSpeak's own choice for it, or null when it spells the letters. */
        public final String espeakDefault;
        /** eSpeak voice names the user can choose. */
        public final String[] choices;
        public final UnicodeScript[] scripts;

        public Script(String key, String espeakDefault, String[] choices, UnicodeScript... scripts) {
            this.key = key;
            this.espeakDefault = espeakDefault;
            this.choices = choices;
            this.scripts = scripts;
        }

        public String prefKey() {
            return PREF_PREFIX + key;
        }
    }

    public static final Script[] SCRIPTS = {
            new Script("latin", "en-gb", new String[] {"en-in", "en-us"}, UnicodeScript.LATIN),
            new Script("hi", "hi", new String[] {"mr", "ne", "kok"}, UnicodeScript.DEVANAGARI),
            new Script("bn", "bn", new String[] {"as", "bpy"}, UnicodeScript.BENGALI),
            new Script("ar", "ar", new String[] {"ur", "fa", "ps", "sd", "ckb", "ug"}, UnicodeScript.ARABIC),
            new Script("cyr", null, new String[] {"ru", "uk", "be", "bg", "mk", "sr", "kk", "ky", "tt",
                    "ba", "cv", "mn"}, UnicodeScript.CYRILLIC),
            new Script("he", null, new String[] {"he"}, UnicodeScript.HEBREW),
            new Script("el", "el", new String[] {"grc"}, UnicodeScript.GREEK),
            new Script("te", null, new String[] {"te"}, UnicodeScript.TELUGU),
            new Script("or", null, new String[] {"or"}, UnicodeScript.ORIYA),
            new Script("zh", null, new String[] {"cmn", "yue"}, UnicodeScript.HAN),
            new Script("ja", null, new String[] {"ja"}, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
            new Script("eth", null, new String[] {"am", "ti"}, UnicodeScript.ETHIOPIC),
    };

    /**
     * Native script name -> eSpeak voice name, for each script the user chose
     * a language for. Empty when nothing was chosen.
     */
    public static Map<String, String> chosen(SharedPreferences prefs) {
        if (prefs == null) {
            return Collections.emptyMap();
        }
        Map<String, String> chosen = null;
        for (Script script : SCRIPTS) {
            final String voice = prefs.getString(script.prefKey(), "");
            if (voice != null && !voice.isEmpty() && isChoice(script.choices, voice)) {
                if (chosen == null) {
                    chosen = new TreeMap<>();
                }
                chosen.put(script.key, voice);
            }
        }
        return chosen != null ? chosen : Collections.emptyMap();
    }

    public static boolean isChoice(String[] choices, String voice) {
        if (choices == null || voice == null) {
            return false;
        }
        for (String c : choices) {
            if (c.equals(voice)) {
                return true;
            }
        }
        return false;
    }

    private static final class CacheHolder {
        final Map<String, String> chosen;
        final Map<UnicodeScript, String> runLanguages;

        CacheHolder(Map<String, String> chosen, Map<UnicodeScript, String> runLanguages) {
            this.chosen = chosen;
            this.runLanguages = runLanguages;
        }
    }

    private static volatile CacheHolder sCache = null;

    /**
     * The chosen language of each Unicode script, from {@link #chosen}, as
     * natural voices key languages ({@link PiperVoiceStore#languageKey}):
     * en-in -> eng, mr -> mar.
     */
    public static Map<UnicodeScript, String> runLanguages(Map<String, String> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return Collections.emptyMap();
        }
        final CacheHolder cached = sCache;
        if (cached != null && chosen.equals(cached.chosen)) {
            return cached.runLanguages;
        }
        final Map<UnicodeScript, String> out = new EnumMap<>(UnicodeScript.class);
        for (Script script : SCRIPTS) {
            final String voice = chosen.get(script.key);
            if (voice != null) {
                final String language = PiperVoiceStore.languageKey(AsciiUtils.baseLanguage(voice));
                for (UnicodeScript s : script.scripts) {
                    out.put(s, language);
                }
            }
        }
        Map<UnicodeScript, String> unmodifiable = Collections.unmodifiableMap(out);
        sCache = new CacheHolder(chosen, unmodifiable);
        return unmodifiable;
    }

    public static boolean naturalSwitching(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_NATURAL_SWITCHING, true);
    }

    public static String switchingSensitivity(SharedPreferences prefs) {
        if (prefs == null) {
            return VoiceSettings.SWITCHING_WORDS;
        }
        final String val = prefs.getString(VoiceSettings.PREF_SWITCHING_SENSITIVITY, VoiceSettings.SWITCHING_WORDS);
        return val != null ? val : VoiceSettings.SWITCHING_WORDS;
    }
}
