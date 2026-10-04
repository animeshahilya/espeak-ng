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

        for (String lang : new String[] {"hin", "hi", "mar", "mr", "nep", "ne", "san", "sa", "kok", "mai", "bho", "sat",
                "hne", "mag", "brx", "doi"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.DEVANAGARI);
        }
        LANGUAGE_SCRIPT.put("ben", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("bn", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("asm", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("as", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("tam", UnicodeScript.TAMIL);
        LANGUAGE_SCRIPT.put("ta", UnicodeScript.TAMIL);
        LANGUAGE_SCRIPT.put("tel", UnicodeScript.TELUGU);
        LANGUAGE_SCRIPT.put("te", UnicodeScript.TELUGU);
        LANGUAGE_SCRIPT.put("mal", UnicodeScript.MALAYALAM);
        LANGUAGE_SCRIPT.put("ml", UnicodeScript.MALAYALAM);
        LANGUAGE_SCRIPT.put("kan", UnicodeScript.KANNADA);
        LANGUAGE_SCRIPT.put("kn", UnicodeScript.KANNADA);
        LANGUAGE_SCRIPT.put("guj", UnicodeScript.GUJARATI);
        LANGUAGE_SCRIPT.put("gu", UnicodeScript.GUJARATI);
        LANGUAGE_SCRIPT.put("pan", UnicodeScript.GURMUKHI);
        LANGUAGE_SCRIPT.put("pa", UnicodeScript.GURMUKHI);
        LANGUAGE_SCRIPT.put("ori", UnicodeScript.ORIYA);
        LANGUAGE_SCRIPT.put("or", UnicodeScript.ORIYA);
        LANGUAGE_SCRIPT.put("sin", UnicodeScript.SINHALA);
        LANGUAGE_SCRIPT.put("si", UnicodeScript.SINHALA);
        for (String lang : new String[] {"ara", "ar", "urd", "ur", "fas", "fa", "pus", "ps", "snd", "sd", "uig", "ug", "kur", "ku", "ckb", "kas"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.ARABIC);
        }
        for (String lang : new String[] {"rus", "ru", "ukr", "uk", "bul", "bg", "srp", "sr", "mkd", "mk", "bel", "be", "kaz", "kk", "kir", "ky", "tat", "tt", "bak", "ba", "chv", "cv", "mon", "mn"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.CYRILLIC);
        }
        LANGUAGE_SCRIPT.put("ell", UnicodeScript.GREEK);
        LANGUAGE_SCRIPT.put("el", UnicodeScript.GREEK);
        LANGUAGE_SCRIPT.put("heb", UnicodeScript.HEBREW);
        LANGUAGE_SCRIPT.put("he", UnicodeScript.HEBREW);
        LANGUAGE_SCRIPT.put("tha", UnicodeScript.THAI);
        LANGUAGE_SCRIPT.put("th", UnicodeScript.THAI);
        LANGUAGE_SCRIPT.put("zho", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("zh", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("yue", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("jpn", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("ja", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("kor", UnicodeScript.HANGUL);
        LANGUAGE_SCRIPT.put("ko", UnicodeScript.HANGUL);
        LANGUAGE_SCRIPT.put("hye", UnicodeScript.ARMENIAN);
        LANGUAGE_SCRIPT.put("hy", UnicodeScript.ARMENIAN);
        LANGUAGE_SCRIPT.put("kat", UnicodeScript.GEORGIAN);
        LANGUAGE_SCRIPT.put("ka", UnicodeScript.GEORGIAN);
        LANGUAGE_SCRIPT.put("amh", UnicodeScript.ETHIOPIC);
        LANGUAGE_SCRIPT.put("am", UnicodeScript.ETHIOPIC);
        LANGUAGE_SCRIPT.put("tir", UnicodeScript.ETHIOPIC);
        LANGUAGE_SCRIPT.put("ti", UnicodeScript.ETHIOPIC);
        LANGUAGE_SCRIPT.put("bod", UnicodeScript.TIBETAN);
        LANGUAGE_SCRIPT.put("bo", UnicodeScript.TIBETAN);
        LANGUAGE_SCRIPT.put("mya", UnicodeScript.MYANMAR);
        LANGUAGE_SCRIPT.put("my", UnicodeScript.MYANMAR);
        LANGUAGE_SCRIPT.put("khm", UnicodeScript.KHMER);
        LANGUAGE_SCRIPT.put("km", UnicodeScript.KHMER);
        LANGUAGE_SCRIPT.put("lao", UnicodeScript.LAO);
        LANGUAGE_SCRIPT.put("lo", UnicodeScript.LAO);
        LANGUAGE_SCRIPT.put("div", UnicodeScript.THAANA);
        LANGUAGE_SCRIPT.put("dv", UnicodeScript.THAANA);
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

    static boolean isDevanagariLanguage(String language) {
        return language != null && UnicodeScript.DEVANAGARI.equals(LANGUAGE_SCRIPT.get(language));
    }

    /** The script {@code language} is written in (Latin unless listed). */
    private static UnicodeScript scriptOf(String language) {
        final UnicodeScript s = LANGUAGE_SCRIPT.get(language);
        return s != null ? s : UnicodeScript.LATIN;
    }

    /** Japanese mixes kana with Han; Korean mixes Hangul with Hanja; Chinese is Han alone. */
    private static boolean belongsTo(UnicodeScript script, String language) {
        if ("jpn".equals(language) || "ja".equals(language)) {
            return script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA
                    || script == UnicodeScript.KATAKANA;
        }
        if ("kor".equals(language) || "ko".equals(language)) {
            return script == UnicodeScript.HANGUL || script == UnicodeScript.HAN;
        }
        return script == scriptOf(language);
    }

    /** "(", "[", "“", "¡", "¿", "«", "‹", "@", "#", or a straight quote (callers check it starts a word). */
    private static boolean isOpening(int c) {
        if (c == '"' || c == '\'' || c == '¡' || c == '¿' || c == '«' || c == '‹'
                || c == '@' || c == '#') {
            return true;
        }
        final int type = Character.getType(c);
        return type == Character.START_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION;
    }

    @FunctionalInterface
    private interface LanguageResolver {
        String resolve(int codePoint, int charIndex);
    }

    /**
     * Resolves the language for code points in a text string.
     * Precomputes invariant lookups, uses an ASCII fast-path and
     * single-element cluster cache for maximum throughput, and dynamically
     * discriminates Marathi (mar) from Hindi (hin) in Devanagari text.
     */
    private static final class FastLanguageResolver implements LanguageResolver {
        private final String ownLanguage;
        private final UnicodeScript ownScript;
        private final boolean isJpn;
        private final boolean isKor;
        private final Map<UnicodeScript, String> chosen;
        private final String numbers;
        private final String latinLanguage;
        private final DevanagariClassifier.SpanMap devanagariSpans;
        private final String defaultDevanagari;

        private UnicodeScript lastScript = null;
        private String lastLanguage = null;

        FastLanguageResolver(String text, String language, Map<UnicodeScript, String> chosen, String numbers) {
            this.ownLanguage = language;
            this.ownScript = scriptOf(language);
            this.isJpn = "jpn".equals(language) || "ja".equals(language);
            this.isKor = "kor".equals(language) || "ko".equals(language);
            this.chosen = (chosen != null && !chosen.isEmpty()) ? chosen : null;
            this.numbers = numbers;
            this.latinLanguage = resolveScript(UnicodeScript.LATIN);

            final String chosenDev = this.chosen != null ? this.chosen.get(UnicodeScript.DEVANAGARI) : null;
            if (chosenDev != null) {
                this.defaultDevanagari = chosenDev;
            } else if (UnicodeScript.DEVANAGARI.equals(this.ownScript)) {
                this.defaultDevanagari = this.ownLanguage;
            } else {
                final String standin = SCRIPT_LANGUAGE.get(UnicodeScript.DEVANAGARI);
                this.defaultDevanagari = standin != null ? standin : "hin";
            }

            this.devanagariSpans = DevanagariClassifier.buildSpans(text, language, chosenDev);
        }

        @Override
        public String resolve(int c, int charIndex) {
            // Fast path for ASCII (c < 128) - covers >90% of characters in common text
            if (c < 128) {
                if (AsciiUtils.isAsciiLetter((char) c)) {
                    return latinLanguage;
                }
                if (AsciiUtils.isAsciiDigit((char) c)) {
                    return numbers;
                }
                // ASCII spaces, control characters, punctuation
                return null;
            }

            // Non-ASCII digits
            if (numbers != null && Character.isDigit(c)) {
                return numbers;
            }

            final UnicodeScript script = UnicodeScript.of(c);
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED
                    || script == UnicodeScript.UNKNOWN) {
                return null;
            }

            // Special handling for Devanagari: dynamic classification between Marathi and Hindi
            if (script == UnicodeScript.DEVANAGARI) {
                final String lang = devanagariSpans.languageAt(charIndex, defaultDevanagari);
                lastScript = script;
                lastLanguage = lang;
                return lang;
            }

            // Cache check: contiguous characters of the same script reuse the resolution
            if (script == lastScript) {
                return lastLanguage;
            }

            final String lang = resolveScript(script);
            lastScript = script;
            lastLanguage = lang;
            return lang;
        }

        private String resolveScript(UnicodeScript script) {
            if (isJpn) {
                if (script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA
                        || script == UnicodeScript.KATAKANA) {
                    return ownLanguage;
                }
            } else if (isKor) {
                if (script == UnicodeScript.HANGUL || script == UnicodeScript.HAN) {
                    return ownLanguage;
                }
            } else if (script == ownScript) {
                return ownLanguage;
            }

            String other = chosen != null ? chosen.get(script) : null;
            if (other == null) {
                other = SCRIPT_LANGUAGE.get(script);
            }
            return other != null ? other : ownLanguage;
        }
    }

    /**
     * Resolves characters for digit-splitting in voices without number words.
     */
    private static final class FastDigitResolver implements LanguageResolver {
        private final String runLanguage;
        private final String numbers;

        FastDigitResolver(String runLanguage, String numbers) {
            this.runLanguage = runLanguage;
            this.numbers = numbers;
        }

        @Override
        public String resolve(int c, int charIndex) {
            if (c < 128) {
                if (c >= '0' && c <= '9') {
                    return numbers;
                }
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    return runLanguage;
                }
                return null;
            }
            if (Character.isDigit(c)) {
                return numbers;
            }
            final UnicodeScript script = UnicodeScript.of(c);
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED
                    || script == UnicodeScript.UNKNOWN) {
                return null;
            }
            return runLanguage;
        }
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
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        return split(text, language, new FastLanguageResolver(text, language, chosen, numbers));
    }

    /**
     * {@code run} with its digits (and their separators) moved to runs in
     * {@code numbers}; the rest keeps the run's language. For a voice that
     * cannot read digits (no number words for its language).
     */
    static List<Run> splitDigits(Run run, String numbers) {
        if (run == null || run.text == null || run.text.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Run> parts = split(run.text, run.language, new FastDigitResolver(run.language, numbers));
        if (parts.size() == 1 && run.start == 0) {
            return parts;
        }
        final List<Run> out = new ArrayList<>(parts.size());
        for (Run part : parts) {
            out.add(new Run(run.start + part.start, part.text, part.language));
        }
        return out;
    }

    /**
     * Splits text into language runs using the provided resolver.
     */
    private static List<Run> split(String text, String language, LanguageResolver resolver) {
        final List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return runs;
        }
        String current = null;
        int runStart = 0;      // UTF-16 index
        int runStartCp = 0;    // code point index
        int cp = 0;
        final int length = text.length();
        for (int i = 0; i < length; ) {
            final int c = text.codePointAt(i);
            // Common (digits, spaces, punctuation) and inherited (combining
            // marks) characters never start a new run - digits only when
            // numbers have a language of their own.
            final String lang = resolver.resolve(c, i);
            if (lang != null) {
                if (current == null) {
                    current = lang;
                } else if (!lang.equals(current)) {
                    // Break before the whitespace that precedes the new run,
                    // so each run keeps its own words whole, and before an
                    // opening bracket or quote, which belongs to the words it
                    // opens: "हो (Arijit" gave Hindi the "(" and English the ")".
                    int cut = i;
                    int cutCp = cp;
                    // If switching from non-Latin to Latin, and digits / currency / numeric compounds
                    // immediately precede the Latin letter without whitespace (e.g. "1st", "2nd", "4G", "10am",
                    // "10:30am", "10,000rpm", "$50k", "15%off"), pull the numeric token into the Latin run
                    // so ordinal and alphanumeric tokens are pronounced whole rather than split across engines.
                    final int beforeCut = cut > runStart ? text.codePointBefore(cut) : -1;
                    final boolean isHyphenAfterDigit = beforeCut == '-' && cut - 1 > runStart
                            && AsciiUtils.isAsciiDigit((char) text.codePointBefore(cut - 1));
                    if (("eng".equals(lang) || "en".equals(lang) || UnicodeScript.LATIN.equals(scriptOf(lang)))
                            && ((beforeCut >= '0' && beforeCut <= '9') || beforeCut == '%' || isHyphenAfterDigit)) {
                        int probe = cut;
                        int probeCp = cutCp;
                        while (probe > runStart) {
                            final int b = text.codePointBefore(probe);
                            if (AsciiUtils.isAsciiDigit((char) b)
                                    || ((b == ':' || b == '.' || b == ',' || b == '-') && probe - 1 > runStart
                                    && AsciiUtils.isAsciiDigit((char) text.codePointBefore(probe - 1)))
                                    || (b == '%' && probe - 1 > runStart
                                    && AsciiUtils.isAsciiDigit((char) text.codePointBefore(probe - 1)))) {
                                probe -= Character.charCount(b);
                                probeCp--;
                            } else {
                                break;
                            }
                        }
                        if (probe > runStart) {
                            final int b = text.codePointBefore(probe);
                            if (Character.getType(b) == Character.CURRENCY_SYMBOL) {
                                probe -= Character.charCount(b);
                                probeCp--;
                            }
                        }
                        if (probe < cut && (probe == runStart || Character.isWhitespace(text.codePointBefore(probe))
                                || isOpening(text.codePointBefore(probe)))) {
                            cut = probe;
                            cutCp = probeCp;
                        }
                    }
                    while (cut > runStart) {
                        final int before = text.codePointBefore(cut);
                        final int at = cut - Character.charCount(before);
                        if (!Character.isWhitespace(before) && !(isOpening(before)
                                && (at == runStart || Character.isWhitespace(text.codePointBefore(at))))) {
                            break;
                        }
                        cut = at;
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
