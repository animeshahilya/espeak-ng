/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits text into runs by writing system, the way eSpeak switches
 * language by alphabet: with an English voice, Devanagari is read with
 * Hindi rules. Natural voices get the same behaviour from this - each run
 * goes to the natural voice of its language (TtsService.synthesizeNatural).
 *
 * <p>Language keys are ISO 639-2 codes, as {@link PiperVoiceStore#languageKey}
 * produces. A script the speaking language itself is written in stays with
 * it (Devanagari in Marathi text stays Marathi); digits, spaces, punctuation
 * and combining marks join the run around them. A language the user chose
 * for a script ({@link ScriptLanguages}) replaces eSpeak's.
 */
final class LanguageRuns {
    private LanguageRuns() {
    }

    /** A piece of text and the language it should be spoken in. */
    static final class Run {
        /** Code point offset of the run within the split text. */
        final int start;
        final String text;
        final String language;

        Run(int start, String text, String language) {
            this.start = start;
            this.text = text;
            this.language = language;
        }
    }

    /** The language eSpeak switches to for text in each script. */
    private static final Map<UnicodeScript, String> SCRIPT_LANGUAGE = new EnumMap<>(UnicodeScript.class);
    /** Scripts a language is written in, where not Latin. */
    private static final Map<String, UnicodeScript> LANGUAGE_SCRIPT = new HashMap<>();

    static {
        SCRIPT_LANGUAGE.put(UnicodeScript.LATIN, "eng");
        SCRIPT_LANGUAGE.put(UnicodeScript.DEVANAGARI, "hin");
        SCRIPT_LANGUAGE.put(UnicodeScript.BENGALI, "ben");
        SCRIPT_LANGUAGE.put(UnicodeScript.TAMIL, "tam");
        SCRIPT_LANGUAGE.put(UnicodeScript.TELUGU, "tel");
        SCRIPT_LANGUAGE.put(UnicodeScript.MALAYALAM, "mal");
        SCRIPT_LANGUAGE.put(UnicodeScript.KANNADA, "kan");
        SCRIPT_LANGUAGE.put(UnicodeScript.GUJARATI, "guj");
        SCRIPT_LANGUAGE.put(UnicodeScript.GURMUKHI, "pan");
        SCRIPT_LANGUAGE.put(UnicodeScript.ORIYA, "ori");
        SCRIPT_LANGUAGE.put(UnicodeScript.SINHALA, "sin");
        SCRIPT_LANGUAGE.put(UnicodeScript.ARABIC, "ara");
        SCRIPT_LANGUAGE.put(UnicodeScript.CYRILLIC, "rus");
        SCRIPT_LANGUAGE.put(UnicodeScript.GREEK, "ell");
        SCRIPT_LANGUAGE.put(UnicodeScript.HEBREW, "heb");
        SCRIPT_LANGUAGE.put(UnicodeScript.THAI, "tha");
        SCRIPT_LANGUAGE.put(UnicodeScript.HAN, "zho");
        SCRIPT_LANGUAGE.put(UnicodeScript.HIRAGANA, "jpn");
        SCRIPT_LANGUAGE.put(UnicodeScript.KATAKANA, "jpn");
        SCRIPT_LANGUAGE.put(UnicodeScript.HANGUL, "kor");
        SCRIPT_LANGUAGE.put(UnicodeScript.ARMENIAN, "hye");
        SCRIPT_LANGUAGE.put(UnicodeScript.GEORGIAN, "kat");

        for (String lang : new String[] {"hin", "mar", "nep", "san", "kok", "mai", "bho", "sat",
                "hne", "mag", "brx", "doi"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.DEVANAGARI);
        }
        LANGUAGE_SCRIPT.put("ben", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("asm", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("tam", UnicodeScript.TAMIL);
        LANGUAGE_SCRIPT.put("tel", UnicodeScript.TELUGU);
        LANGUAGE_SCRIPT.put("mal", UnicodeScript.MALAYALAM);
        LANGUAGE_SCRIPT.put("kan", UnicodeScript.KANNADA);
        LANGUAGE_SCRIPT.put("guj", UnicodeScript.GUJARATI);
        LANGUAGE_SCRIPT.put("pan", UnicodeScript.GURMUKHI);
        LANGUAGE_SCRIPT.put("ori", UnicodeScript.ORIYA);
        LANGUAGE_SCRIPT.put("sin", UnicodeScript.SINHALA);
        for (String lang : new String[] {"ara", "urd", "fas", "pus", "snd", "uig", "kur"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.ARABIC);
        }
        for (String lang : new String[] {"rus", "ukr", "bul", "srp", "mkd", "bel", "kaz", "kir", "tat", "bak", "chv", "mon"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.CYRILLIC);
        }
        LANGUAGE_SCRIPT.put("ell", UnicodeScript.GREEK);
        LANGUAGE_SCRIPT.put("heb", UnicodeScript.HEBREW);
        LANGUAGE_SCRIPT.put("tha", UnicodeScript.THAI);
        LANGUAGE_SCRIPT.put("zho", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("yue", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("jpn", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("kor", UnicodeScript.HANGUL);
        LANGUAGE_SCRIPT.put("hye", UnicodeScript.ARMENIAN);
        LANGUAGE_SCRIPT.put("kat", UnicodeScript.GEORGIAN);
    }

    /**
     * The language eSpeak reads {@code language}'s script in, for a language
     * eSpeak has no voice for (Chhattisgarhi -> Hindi); null if its script is
     * not listed.
     */
    static String standIn(String language) {
        final UnicodeScript s = LANGUAGE_SCRIPT.get(language);
        return s != null ? SCRIPT_LANGUAGE.get(s) : null;
    }

    /** The script {@code language} is written in (Latin unless listed). */
    private static UnicodeScript scriptOf(String language) {
        final UnicodeScript s = LANGUAGE_SCRIPT.get(language);
        return s != null ? s : UnicodeScript.LATIN;
    }

    /** Japanese mixes kana with Han; Chinese is Han alone. */
    private static boolean belongsTo(UnicodeScript script, String language) {
        if ("jpn".equals(language)) {
            return script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA
                    || script == UnicodeScript.KATAKANA;
        }
        return script == scriptOf(language);
    }

    /** The language a letter in {@code script} is read in, speaking {@code language}. */
    private static String languageFor(UnicodeScript script, String language,
                                      Map<UnicodeScript, String> chosen) {
        if (belongsTo(script, language)) {
            return language;
        }
        String other = chosen.get(script);
        if (other == null) {
            other = SCRIPT_LANGUAGE.get(script);
        }
        return other != null ? other : language;
    }

    static List<Run> split(String text, String language) {
        return split(text, language, Collections.emptyMap());
    }

    static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen) {
        return split(text, language, chosen, null);
    }

    /**
     * Runs of {@code text} with the language each is read in, speaking
     * {@code language}, with {@code chosen} the languages the user chose for
     * scripts. Always at least one run for non-empty text; the runs' texts
     * concatenate back to {@code text}.
     *
     * @param numbers language digits are read in (their separators, like the
     *                colon of 10:30, go with them), or null for the language
     *                of the words around them
     */
    static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers) {
        final List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return runs;
        }
        String current = null;
        int runStart = 0;      // UTF-16 index
        int runStartCp = 0;    // code point index
        int cp = 0;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            final UnicodeScript script = UnicodeScript.of(c);
            final boolean digit = numbers != null && Character.isDigit(c);
            // Common (digits, spaces, punctuation) and inherited (combining
            // marks) characters never start a new run - digits only when
            // numbers have a language of their own.
            if (digit || (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED
                    && script != UnicodeScript.UNKNOWN)) {
                final String lang = digit ? numbers : languageFor(script, language, chosen);
                if (current == null) {
                    current = lang;
                } else if (!lang.equals(current)) {
                    // Break before the whitespace that precedes the new run,
                    // so each run keeps its own words whole.
                    int cut = i;
                    int cutCp = cp;
                    while (cut > runStart) {
                        final int before = text.codePointBefore(cut);
                        if (!Character.isWhitespace(before)) {
                            break;
                        }
                        cut -= Character.charCount(before);
                        cutCp--;
                    }
                    if (cut == runStart) {
                        cut = i;
                        cutCp = cp;
                    }
                    runs.add(new Run(runStartCp, text.substring(runStart, cut), current));
                    runStart = cut;
                    runStartCp = cutCp;
                    current = lang;
                }
            }
            i += Character.charCount(c);
            cp++;
        }
        runs.add(new Run(runStartCp, text.substring(runStart), current != null ? current : language));
        return runs;
    }
}
