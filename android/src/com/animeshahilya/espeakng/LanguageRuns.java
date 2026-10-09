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
    /**
     * Further scripts a language is commonly written in, besides its main
     * one: its own text in them is not "another language" (Serbian Latin,
     * Sindhi in Devanagari, Uzbek Cyrillic).
     */
    private static final Map<String, UnicodeScript[]> ALSO_WRITTEN_IN = new HashMap<>();

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

        for (String lang : new String[] {"hin", "hi", "mar", "mr", "nep", "ne", "san", "sa", "kok", "mai", "bho",
                "hne", "mag", "brx", "doi"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.DEVANAGARI);
        }
        LANGUAGE_SCRIPT.put("ben", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("bn", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("asm", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("as", UnicodeScript.BENGALI);
        LANGUAGE_SCRIPT.put("bpy", UnicodeScript.BENGALI); // Bishnupriya Manipuri
        LANGUAGE_SCRIPT.put("mni", UnicodeScript.BENGALI); // Manipuri
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
        // Not "ku"/"kur": that is Kurmanji (eSpeak's and Piper's ku voices),
        // written in Latin. Only Sorani (ckb) is Arabic script.
        for (String lang : new String[] {"ara", "ar", "urd", "ur", "fas", "fa", "pus", "ps", "snd", "sd", "uig", "ug", "ckb", "kas"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.ARABIC);
        }
        for (String lang : new String[] {"rus", "ru", "ukr", "uk", "bul", "bg", "srp", "sr", "mkd", "mk", "bel", "be",
                "kaz", "kk", "kir", "ky", "tat", "tt", "bak", "ba", "chv", "cv", "mon", "mn", "nog", "abk", "ab",
                "tgk", "tg", "sah"}) {
            LANGUAGE_SCRIPT.put(lang, UnicodeScript.CYRILLIC);
        }
        LANGUAGE_SCRIPT.put("ell", UnicodeScript.GREEK);
        LANGUAGE_SCRIPT.put("el", UnicodeScript.GREEK);
        LANGUAGE_SCRIPT.put("grc", UnicodeScript.GREEK); // Ancient Greek
        LANGUAGE_SCRIPT.put("heb", UnicodeScript.HEBREW);
        LANGUAGE_SCRIPT.put("he", UnicodeScript.HEBREW);
        LANGUAGE_SCRIPT.put("tha", UnicodeScript.THAI);
        LANGUAGE_SCRIPT.put("th", UnicodeScript.THAI);
        LANGUAGE_SCRIPT.put("zho", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("zh", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("yue", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("cmn", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("hak", UnicodeScript.HAN); // Hakka
        LANGUAGE_SCRIPT.put("jpn", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("ja", UnicodeScript.HAN);
        LANGUAGE_SCRIPT.put("kor", UnicodeScript.HANGUL);
        LANGUAGE_SCRIPT.put("ko", UnicodeScript.HANGUL);
        LANGUAGE_SCRIPT.put("hye", UnicodeScript.ARMENIAN);
        LANGUAGE_SCRIPT.put("hy", UnicodeScript.ARMENIAN);
        LANGUAGE_SCRIPT.put("hyw", UnicodeScript.ARMENIAN); // Western Armenian
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
        LANGUAGE_SCRIPT.put("shn", UnicodeScript.MYANMAR); // Shan
        LANGUAGE_SCRIPT.put("khm", UnicodeScript.KHMER);
        LANGUAGE_SCRIPT.put("km", UnicodeScript.KHMER);
        LANGUAGE_SCRIPT.put("lao", UnicodeScript.LAO);
        LANGUAGE_SCRIPT.put("lo", UnicodeScript.LAO);
        LANGUAGE_SCRIPT.put("div", UnicodeScript.THAANA);
        LANGUAGE_SCRIPT.put("dv", UnicodeScript.THAANA);
        LANGUAGE_SCRIPT.put("chr", UnicodeScript.CHEROKEE);
        LANGUAGE_SCRIPT.put("sat", UnicodeScript.OL_CHIKI); // Santali

        for (String lang : new String[] {"srp", "sr", "kaz", "kk"}) {
            ALSO_WRITTEN_IN.put(lang, new UnicodeScript[] {UnicodeScript.LATIN});
        }
        for (String lang : new String[] {"uzb", "uz", "aze", "az", "tuk", "tk", "bos", "bs"}) {
            ALSO_WRITTEN_IN.put(lang, new UnicodeScript[] {UnicodeScript.CYRILLIC});
        }
        for (String lang : new String[] {"snd", "sd", "kas", "ks", "sat"}) {
            ALSO_WRITTEN_IN.put(lang, new UnicodeScript[] {UnicodeScript.DEVANAGARI});
        }
        ALSO_WRITTEN_IN.put("pan", new UnicodeScript[] {UnicodeScript.ARABIC}); // Shahmukhi
        ALSO_WRITTEN_IN.put("pa", new UnicodeScript[] {UnicodeScript.ARABIC});
        ALSO_WRITTEN_IN.put("mni", new UnicodeScript[] {UnicodeScript.MEETEI_MAYEK});
        ALSO_WRITTEN_IN.put("uig", new UnicodeScript[] {UnicodeScript.LATIN, UnicodeScript.CYRILLIC});
        ALSO_WRITTEN_IN.put("ug", new UnicodeScript[] {UnicodeScript.LATIN, UnicodeScript.CYRILLIC});
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

    /**
     * Whether {@code language}'s own text can be in {@code script}. Japanese
     * mixes kana with Han; Korean mixes Hangul with Hanja; Chinese is Han alone.
     */
    static boolean writtenIn(String language, UnicodeScript script) {
        if (language == null || script == null) {
            return false;
        }
        if ("jpn".equals(language) || "ja".equals(language)) {
            return script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA
                    || script == UnicodeScript.KATAKANA;
        }
        if ("kor".equals(language) || "ko".equals(language)) {
            return script == UnicodeScript.HANGUL || script == UnicodeScript.HAN;
        }
        if (script == scriptOf(language)) {
            return true;
        }
        final UnicodeScript[] also = ALSO_WRITTEN_IN.get(language);
        if (also != null) {
            for (UnicodeScript s : also) {
                if (s == script) {
                    return true;
                }
            }
        }
        return false;
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
        /** Devanagari is one of the language's scripts, not its main one (Sindhi). */
        private final boolean ownDevanagari;
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
            this.ownDevanagari = ownScript != UnicodeScript.DEVANAGARI
                    && writtenIn(language, UnicodeScript.DEVANAGARI);
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
            if (script == UnicodeScript.DEVANAGARI && !ownDevanagari) {
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
            if (writtenIn(ownLanguage, script)) {
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
                if (AsciiUtils.isAsciiDigit((char) c)) {
                    return numbers;
                }
                if (AsciiUtils.isAsciiLetter((char) c)) {
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

    /**
     * Fast-path check: returns true if all characters in {@code text} belong to
     * {@code language}'s own script (or common whitespace/digits/punctuation when
     * {@code numbers == null}), and the script has no intra-script dialect ambiguity.
     * When true, {@code text} is guaranteed to produce a single monolingual run,
     * avoiding resolver and classifier allocations entirely.
     */
    static boolean isPurelyOwnScript(String text, String language, Map<UnicodeScript, String> chosen,
                                     String numbers) {
        if (text == null || text.isEmpty() || numbers != null) {
            return false;
        }
        final UnicodeScript ownScript = scriptOf(language);
        // Scripts with intra-script linguistic ambiguity require full classification
        // or letter-frequency analysis.
        if (ownScript == UnicodeScript.DEVANAGARI
                || ownScript == UnicodeScript.CYRILLIC
                || ownScript == UnicodeScript.ARABIC
                || ownScript == UnicodeScript.BENGALI
                || ownScript == UnicodeScript.HAN) {
            return false;
        }
        if (chosen != null && chosen.containsKey(ownScript)) {
            return false;
        }
        final int len = text.length();
        for (int i = 0; i < len; ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c < 128) {
                if (AsciiUtils.isAsciiLetter((char) c)) {
                    if (ownScript != UnicodeScript.LATIN) {
                        return false;
                    }
                }
                // ASCII digits, punctuation, and whitespace belong to the run around them.
                continue;
            }
            if (Character.isDigit(c)) {
                continue;
            }
            final UnicodeScript script = UnicodeScript.of(c);
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED
                    || script == UnicodeScript.UNKNOWN) {
                continue;
            }
            if (script != ownScript && !writtenIn(language, script)) {
                return false;
            }
        }
        return true;
    }

    static int countWords(String text) {
        return countWords(text, Integer.MAX_VALUE);
    }

    static int countWords(String text, int maxCount) {
        if (text == null || text.isEmpty() || maxCount <= 0) {
            return 0;
        }
        int words = 0;
        boolean inWord = false;
        final int n = text.length();
        for (int i = 0; i < n; ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (!inWord) {
                    words++;
                    if (words >= maxCount) {
                        return words;
                    }
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return words;
    }

    static boolean hasSentenceBoundary(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '.' || c == '?' || c == '!' || c == '\n' || c == '\r'
                    || c == '\u0964' /* danda */ || c == '\u0965' /* double danda */
                    || c == '\u061F' /* Arabic ? */ || c == '\u06D4' /* Urdu . */
                    || c == '\u3002' /* CJK . */) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies switching sensitivity to language runs:
     * <ul>
     *   <li>{@code "words"} (default): keeps every foreign run.</li>
     *   <li>{@code "phrases"}: absorbs isolated foreign runs of fewer than 2 words into
     *       the surrounding base language, preventing jarring voice switches on single words
     *       like brand names or loanwords.</li>
     *   <li>{@code "sentences"}: only keeps foreign runs that form complete clauses or sentences.</li>
     * </ul>
     */
    static List<Run> applySensitivity(List<Run> runs, String baseLanguage, String sensitivity) {
        if (runs == null || runs.size() <= 1 || sensitivity == null
                || VoiceSettings.SWITCHING_WORDS.equals(sensitivity) || "".equals(sensitivity)) {
            return runs;
        }
        final boolean phrasesMode = VoiceSettings.SWITCHING_PHRASES.equals(sensitivity);
        final boolean sentencesMode = VoiceSettings.SWITCHING_SENTENCES.equals(sensitivity);
        if (!phrasesMode && !sentencesMode) {
            return runs;
        }

        boolean changed = false;
        final List<Run> filtered = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            final Run run = runs.get(i);
            if (run.language.equals(baseLanguage) || "zxx".equals(run.language)) {
                filtered.add(run);
                continue;
            }
            boolean keep = true;
            if (phrasesMode) {
                // Keep alphanumeric compounds (e.g. 1st, 4G, 10am) in Latin engine so ordinals are spoken
                if (AsciiUtils.hasDigit(run.text) && AsciiUtils.hasAsciiLetter(run.text)) {
                    keep = true;
                } else if (countWords(run.text, 2) < 2) {
                    keep = false;
                }
            } else if (sentencesMode) {
                if (!hasSentenceBoundary(run.text) && countWords(run.text, 3) < 3) {
                    keep = false;
                }
            }
            if (keep) {
                filtered.add(run);
            } else {
                filtered.add(new Run(run.start, run.text, baseLanguage));
                changed = true;
            }
        }

        if (!changed) {
            return runs;
        }

        final List<Run> merged = new ArrayList<>(filtered.size());
        for (Run r : filtered) {
            if (merged.isEmpty()) {
                merged.add(r);
            } else {
                final Run prev = merged.get(merged.size() - 1);
                if (prev.language.equals(r.language)) {
                    merged.set(merged.size() - 1, new Run(prev.start, prev.text + r.text, prev.language));
                } else {
                    merged.add(r);
                }
            }
        }
        return merged;
    }

    static List<Run> split(String text, String language) {
        return split(text, language, Collections.emptyMap(), null, null);
    }

    static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen) {
        return split(text, language, chosen, null, null);
    }

    static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers) {
        return split(text, language, chosen, numbers, null);
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
     * @param sensitivity switching sensitivity (words, phrases, sentences),
     *                    or null for default (words)
     */
    static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers, String sensitivity) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (isPurelyOwnScript(text, language, chosen, numbers)) {
            return Collections.singletonList(new Run(0, text, language));
        }
        final List<Run> runs = identify(split(text, language,
                new FastLanguageResolver(text, language, chosen, numbers)), text, chosen);
        return applySensitivity(runs, language, sensitivity);
    }

    /**
     * Which language a run in another script is, where the script alone
     * does not say: Cyrillic is Russian unless letters only Ukrainian,
     * Serbian, Kazakh... use say otherwise; Arabic script likewise for Urdu,
     * Persian, Pashto, Sindhi, Sorani and Uyghur; Bengali script for
     * Assamese. Han is Japanese when the text has kana, Korean when it has
     * Hangul. A script the user chose a language for keeps it. Runs that end
     * up in the same language are joined (東京 + タワー).
     */
    private static List<Run> identify(List<Run> runs, String text, Map<UnicodeScript, String> chosen) {
        boolean changed = false;
        for (int i = 0; i < runs.size(); i++) {
            final Run run = runs.get(i);
            final String better = identify(run.language, run.text, text, chosen);
            if (!better.equals(run.language)) {
                runs.set(i, new Run(run.start, run.text, better));
                changed = true;
            }
        }
        if (!changed) {
            return runs;
        }
        final List<Run> out = new ArrayList<>(runs.size());
        for (Run run : runs) {
            final Run last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.language.equals(run.language)) {
                out.set(out.size() - 1, new Run(last.start, last.text + run.text, last.language));
            } else {
                out.add(run);
            }
        }
        return out;
    }

    private static String identify(String language, String run, String text, Map<UnicodeScript, String> chosen) {
        final UnicodeScript script;
        switch (language) {
            case "rus": script = UnicodeScript.CYRILLIC; break;
            case "ara": script = UnicodeScript.ARABIC; break;
            case "ben": script = UnicodeScript.BENGALI; break;
            case "zho": script = UnicodeScript.HAN; break;
            default: return language;
        }
        if (chosen != null && chosen.containsKey(script)) {
            return language;
        }
        final String found = script == UnicodeScript.HAN ? hanLanguage(text) : byLetters(script, run);
        return found != null ? found : language;
    }

    /** Japanese or Korean when the text around the Han has kana or Hangul, else null. */
    private static String hanLanguage(String text) {
        boolean hangul = false;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c < 0x1100) {
                continue;
            }
            final UnicodeScript s = UnicodeScript.of(c);
            if (s == UnicodeScript.HIRAGANA || s == UnicodeScript.KATAKANA) {
                return "jpn";
            }
            hangul |= s == UnicodeScript.HANGUL;
        }
        return hangul ? "kor" : null;
    }

    /**
     * Letters only one language of the script uses, as "language:letters";
     * the language with the most of them in the run wins. Shared letters
     * count for each of their languages. A "~" entry counts only when no
     * other one matched: Urdu and Pashto also write Persian's ی ک پ گ, so a
     * single ے outweighs them.
     */
    private static final String[] CYRILLIC_LETTERS = {
            "ukr:їєґі", "bel:ўі", "srp:ђћјљњџ", "mkd:ѓќѕјљњџ", "kaz:әғқңөұүһі", "tat:әөүҗңһ", "bak:ҙҫҡғәөүңһ"};
    private static final String[] ARABIC_LETTERS = {
            // Persian: ی/ک (not Arabic's ي/ك) and پ چ ژ گ; Arabic: ة ي ك ى.
            "urd:ٹڈڑںےۓہھ", "pus:ټډړښږځڅۍې", "snd:ٻڀٺٽٿڃڄڇڊڌڍڏڙڦڱڳ", "ckb:ڵۆێڕە",
            "uig:ۈۋۇېەڭ", "~fas:یکپچژگ", "~ara:ةيكى"};
    private static final String[] BENGALI_LETTERS = {"asm:ৰৱ"};

    private static String byLetters(UnicodeScript script, String run) {
        final String[] table = script == UnicodeScript.CYRILLIC ? CYRILLIC_LETTERS
                : script == UnicodeScript.ARABIC ? ARABIC_LETTERS : BENGALI_LETTERS;
        String best = null;
        int bestCount = 0;
        for (boolean weak : new boolean[] {false, true}) {
            for (String entry : table) {
                if ((entry.charAt(0) == '~') != weak) {
                    continue;
                }
                final int colon = entry.indexOf(':');
                int count = 0;
                for (int i = 0; i < run.length(); i++) {
                    if (entry.indexOf(Character.toLowerCase(run.charAt(i)), colon + 1) >= 0) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    best = entry.substring(weak ? 1 : 0, colon);
                    bestCount = count;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * Whether eSpeak, speaking {@code own}, reads a run in {@code language}
     * by itself: the language it switches to for that script (Hindi for
     * Devanagari, Arabic for Arabic script, English for Latin...) or the one
     * the user chose for the script. Otherwise (Cyrillic, Telugu, Thai,
     * Japanese, or a language told apart from its script's default) it would
     * spell the letters or use the wrong rules, and needs its own voice.
     * "zxx" (digits for eSpeak) is read in the request's language.
     */
    static boolean espeakReadsItself(String language, String own, Map<UnicodeScript, String> chosen) {
        if (language == null) {
            return false;
        }
        if (language.equals(own) || "zxx".equals(language)
                || (chosen != null && chosen.containsValue(language))) {
            return true;
        }
        switch (language) {
            case "eng": case "hin": case "ben": case "pan": case "guj": case "tam": case "kan":
            case "mal": case "sin": case "ara": case "ell": case "hye": case "kat": case "kor":
                // Only from another script: in its own script (Hindi inside
                // Marathi) eSpeak keeps the speaking voice's rules.
                return !writtenIn(own, scriptOf(language));
            default:
                return false;
        }
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
                            && AsciiUtils.isAsciiDigit(text.codePointBefore(cut - 1));
                    if (("eng".equals(lang) || "en".equals(lang) || UnicodeScript.LATIN.equals(scriptOf(lang)))
                            && ((beforeCut >= '0' && beforeCut <= '9') || beforeCut == '%' || isHyphenAfterDigit)) {
                        int probe = cut;
                        int probeCp = cutCp;
                        while (probe > runStart) {
                            final int b = text.codePointBefore(probe);
                            if (AsciiUtils.isAsciiDigit(b)
                                    || ((b == ':' || b == '.' || b == ',' || b == '-') && probe - 1 > runStart
                                    && AsciiUtils.isAsciiDigit(text.codePointBefore(probe - 1)))
                                    || (b == '%' && probe - 1 > runStart
                                    && AsciiUtils.isAsciiDigit(text.codePointBefore(probe - 1)))) {
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
