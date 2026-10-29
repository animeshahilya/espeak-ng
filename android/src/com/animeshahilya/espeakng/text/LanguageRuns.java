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
import com.animeshahilya.espeakng.TtsService;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.ui.VoiceSettings;

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
public final class LanguageRuns {
    private LanguageRuns() {
    }

    /** A piece of text and the language it should be spoken in. */
    public static final class Run {
        /** Code point offset of the run within the split text. */
        public final int start;
        public final String text;
        public final String language;

        public Run(int start, String text, String language) {
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
    public static String standIn(String language) {
        final UnicodeScript s = LANGUAGE_SCRIPT.get(language);
        return s != null ? SCRIPT_LANGUAGE.get(s) : null;
    }

    public static boolean isDevanagariLanguage(String language) {
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
        private final List<SpeechLinks.Range> links;

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
            this.links = SpeechLinks.find(text);

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
            // Web links and emails stay unbroken in Latin script
            if (SpeechLinks.isInLink(links, charIndex)) {
                return latinLanguage;
            }

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
            // Not every digit is common: ५ is Devanagari, and the full split
            // gives it to its language, so it goes there.
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
     *
     * @param lettersOnly the base language's voice reads only its own
     *                    script's letters (a SYSPIN or Rasa natural voice):
     *                    a run in another script then always switches, as
     *                    that voice would drop it
     */
    static List<Run> applySensitivity(List<Run> runs, String baseLanguage, String sensitivity,
                                      boolean lettersOnly) {
        return applySensitivity(runs, baseLanguage, language -> sensitivity, lettersOnly);
    }

    /**
     * As above, with the sensitivity of each foreign run's language (its
     * language card, else the main setting).
     */
    static List<Run> applySensitivity(List<Run> runs, String baseLanguage,
                                      java.util.function.Function<String, String> sensitivityFor,
                                      boolean lettersOnly) {
        if (runs == null || runs.size() <= 1) {
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
            final String sensitivity = sensitivityFor.apply(run.language);
            final boolean phrasesMode = VoiceSettings.SWITCHING_PHRASES.equals(sensitivity);
            final boolean sentencesMode = VoiceSettings.SWITCHING_SENTENCES.equals(sensitivity);
            boolean keep = true;
            if (lettersOnly && !inScriptOf(run.text, baseLanguage)) {
                keep = true;
            } else if (phrasesMode) {
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

    /** Whether every letter of {@code text} is in a script {@code language} is written in. */
    private static boolean inScriptOf(String text, String language) {
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            final UnicodeScript script = UnicodeScript.of(cp);
            if (script != scriptOf(language) && !writtenIn(language, script)) {
                return false;
            }
        }
        return true;
    }

    public static List<Run> split(String text, String language) {
        return split(text, language, Collections.emptyMap(), null, null);
    }

    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen) {
        return split(text, language, chosen, null, null);
    }

    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
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
    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers, String sensitivity) {
        return split(text, language, chosen, numbers, sensitivity, false);
    }

    /** As above; {@code lettersOnly} as in {@link #applySensitivity}. */
    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers, String sensitivity, boolean lettersOnly) {
        return split(text, language, chosen, numbers, l -> sensitivity, lettersOnly);
    }

    /** As above, with each foreign language's own sensitivity (its language card). */
    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers, java.util.function.Function<String, String> sensitivityFor,
                           boolean lettersOnly) {
        return split(text, language, chosen, numbers, sensitivityFor, lettersOnly, false);
    }

    /**
     * As above. {@code detect} (Mixed languages -> "Detect the language of
     * each sentence", off by default) also tells languages apart in a script
     * the user chose a language for, which is then only the usual one: with
     * Arabic script set to Central Kurdish, an Arabic sentence is still
     * Arabic (a Kurdish tester could not have both).
     */
    public static List<Run> split(String text, String language, Map<UnicodeScript, String> chosen,
                           String numbers, java.util.function.Function<String, String> sensitivityFor,
                           boolean lettersOnly, boolean detect) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (isPurelyOwnScript(text, language, chosen, numbers)) {
            return Collections.singletonList(new Run(0, text, language));
        }
        final List<Run> runs = identify(split(text, language,
                new FastLanguageResolver(text, language, chosen, numbers)), text, chosen, detect);
        return applySensitivity(runs, language, sensitivityFor, lettersOnly);
    }

    /**
     * The runs eSpeak alone needs for "Detect the language of each sentence":
     * only Cyrillic, Arabic and Bengali script parts keep a language of their
     * own; the rest is the request's language, so eSpeak switches there by
     * itself exactly as without the setting.
     */
    public static List<Run> detectedRuns(String text, String language, Map<UnicodeScript, String> chosen) {
        final List<Run> runs = split(text, language, chosen, null, l -> null, false, true);
        final List<Run> out = new ArrayList<>(runs.size());
        for (Run run : runs) {
            final String lang = hasLetterTable(scriptOf(run.language)) ? run.language : language;
            final Run last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.language.equals(lang)) {
                out.set(out.size() - 1, new Run(last.start, last.text + run.text, lang));
            } else {
                out.add(lang.equals(run.language) ? run : new Run(run.start, run.text, lang));
            }
        }
        return out;
    }

    private static volatile Map<String, String> sTags;

    /** "rus" -> "ru", for data keyed by two-letter codes; a language without one keeps its code ("ckb"). */
    public static String languageTag(String iso3) {
        Map<String, String> tags = sTags;
        if (tags == null) {
            tags = new HashMap<>();
            for (String code : java.util.Locale.getISOLanguages()) {
                try {
                    tags.put(new java.util.Locale(code).getISO3Language(), code);
                } catch (java.util.MissingResourceException ignored) {
                    // no three-letter code
                }
            }
            sTags = tags;
        }
        final String tag = tags.get(iso3);
        return tag != null ? tag : iso3;
    }

    /** Whether {@code script} is told apart by letters ({@link #identify}): Cyrillic, Arabic, Bengali. */
    public static boolean hasLetterTable(UnicodeScript script) {
        return script == UnicodeScript.CYRILLIC || script == UnicodeScript.ARABIC
                || script == UnicodeScript.BENGALI;
    }

    /**
     * Which language a run in another script is, where the script alone
     * does not say: Cyrillic is Russian unless letters only Ukrainian,
     * Serbian, Kazakh... use say otherwise; Arabic script likewise for Urdu,
     * Persian, Pashto, Sindhi, Sorani and Uyghur; Bengali script for
     * Assamese. Han is Japanese when the text has kana, Korean when it has
     * Hangul. A script the user chose a language for keeps it. Runs that end
     * up in the same language are joined (東京 + タワー).
     *
     * <p>Cyrillic, Arabic and Bengali script are decided per sentence, in
     * the speaking language's own script too: a Ukrainian sentence in a
     * Russian request, an Arabic quotation in Urdu. Before, one marker letter
     * moved the whole run ("Слава Україні" read a Russian request in
     * Ukrainian) and the speaking language's own text was never looked at.
     */
    private static List<Run> identify(List<Run> runs, String text, Map<UnicodeScript, String> chosen,
                                      boolean detect) {
        boolean changed = false;
        final List<Run> out = new ArrayList<>(runs.size());
        for (Run run : runs) {
            final UnicodeScript script = letterScript(run.language);
            if (script != null && (detect || chosen == null || !chosen.containsKey(script))) {
                // With detection on, the language the user chose for the
                // script settles what letters alone cannot.
                final String preferred = detect && chosen != null ? chosen.get(script) : null;
                String previous = null;
                for (Run sentence : sentences(run)) {
                    // No letters (an emoji or number after "?"): it belongs
                    // to the sentence before it, not to the script's default.
                    final String better = previous != null && !hasLetter(sentence.text) ? previous
                            : bySentence(script, run.language, sentence.text, preferred);
                    previous = better;
                    changed |= !better.equals(run.language);
                    out.add(new Run(sentence.start, sentence.text, better));
                }
                continue;
            }
            final String better = identify(run.language, run.text, text, chosen);
            changed |= !better.equals(run.language);
            out.add(better.equals(run.language) ? run : new Run(run.start, run.text, better));
        }
        if (!changed) {
            return runs;
        }
        final List<Run> joined = new ArrayList<>(out.size());
        for (Run run : out) {
            final Run last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && last.language.equals(run.language)) {
                joined.set(joined.size() - 1, new Run(last.start, last.text + run.text, last.language));
            } else {
                joined.add(run);
            }
        }
        return joined;
    }

    private static String identify(String language, String run, String text, Map<UnicodeScript, String> chosen) {
        final UnicodeScript script;
        switch (language) {
            case "zho": script = UnicodeScript.HAN; break;
            case "eng": script = UnicodeScript.LATIN; break;
            default: return language;
        }
        if (chosen != null && chosen.containsKey(script)) {
            return language;
        }
        if (script == UnicodeScript.LATIN) {
            final String found = LatinLanguageDetector.detect(run, language);
            return found != null ? found : language;
        }
        final String found = hanLanguage(text);
        return found != null ? found : language;
    }

    /** The script whose letter table tells {@code language} from its neighbours, or null. */
    private static UnicodeScript letterScript(String language) {
        final UnicodeScript script = scriptOf(language);
        if (!hasLetterTable(script)) {
            return null;
        }
        if (language.equals(SCRIPT_LANGUAGE.get(script))) {
            return script;
        }
        for (String entry : table(script)) {
            if (entry.startsWith(language + ":", entry.charAt(0) == '~' ? 1 : 0)) {
                return script;
            }
        }
        return null;
    }

    /**
     * The sentences of a run, each with its terminator and the spaces after
     * it: a full stop, ? or ! (also the Arabic, Urdu, Devanagari and
     * Armenian ones) before a space or the end, or a line break. "3.5" is
     * one word.
     */
    static List<Run> sentences(Run run) {
        final String t = run.text;
        final List<Run> out = new ArrayList<>();
        int from = 0;
        int fromCp = run.start;
        int cp = run.start;
        for (int i = 0; i < t.length(); ) {
            final int c = t.codePointAt(i);
            i += Character.charCount(c);
            cp++;
            final boolean end = c == '\n' || ((c == '.' || c == '?' || c == '!' || c == '۔'
                    || c == '؟' || c == '।' || c == '॥' || c == '։')
                    && (i == t.length() || Character.isWhitespace(t.codePointAt(i))));
            if (!end) {
                continue;
            }
            while (i < t.length() && Character.isWhitespace(t.codePointAt(i))) {
                i += Character.charCount(t.codePointAt(i));
                cp++;
            }
            out.add(new Run(fromCp, t.substring(from, i), run.language));
            from = i;
            fromCp = cp;
        }
        if (from < t.length()) {
            out.add(new Run(fromCp, t.substring(from), run.language));
        }
        return out;
    }

    /**
     * The language of one sentence labelled {@code language}. Letters only
     * one language uses decide. A label with a {@link #NOT_WRITTEN} list
     * stays unless the sentence has one of its letters (a Belarusian
     * "Добры дзень" has ы, which Russian shares). Otherwise the weaker shared
     * letters (Persian's ی ک) count only where the label is just the
     * script's default, or for Arabic with two of its letters: a short Urdu
     * sentence ("کیا؟") has only ی ک and must stay Urdu.
     */
    private static String bySentence(UnicodeScript script, String language, String sentence,
                                     String preferred) {
        final int[] count = new int[1];
        final boolean[] weak = new boolean[1];
        final String found = byLetters(script, sentence, count, weak);
        if (language.equals(found)) {
            return language;
        }
        if (found == null || weak[0]) {
            final String byWords = byWords(script, language, sentence);
            if (byWords != null) {
                return byWords;
            }
        }
        if (preferred != null && !preferred.equals(language) && (found == null || weak[0])
                && writes(preferred, sentence) && hasNotWritten(preferred)) {
            // Only shared letters ("من کوردم." is Persian or Sorani): the
            // chosen language, when it can be written this way at all.
            return preferred;
        }
        if (found != null && !weak[0]) {
            return found; // letters only that language writes (Urdu's ہ ے in Sorani text)
        }
        if (hasNotWritten(language)) {
            if (writes(language, sentence)) {
                return language;
            }
            // Not this language, and no other one's own letters: the script's
            // usual one ("الحمد لله رب العالمين" in Sorani text is Arabic).
            final String usual = SCRIPT_LANGUAGE.get(script);
            return found != null ? found : usual != null ? usual : language;
        }
        if (found == null) {
            return language;
        }
        if (weak[0] && !language.equals(SCRIPT_LANGUAGE.get(script))
                && !("ara".equals(found) && count[0] >= 2)) {
            return language;
        }
        return found;
    }

    /**
     * Common words only one language of a pair uses, for sentences whose
     * letters do not decide: "Як справи? Дякую, добре." has no і, ї, є or ґ
     * and was read as Russian (4 of 18 short Ukrainian sentences were
     * Ukrainian). Words both languages write ("так", "все", "тебе", "де",
     * "зараз") are left out. Written from general vocabulary, then checked
     * on held-out sentences (ScriptDetectionCorpusTest).
     */
    private static final String[] WORDS = {
            "ukr:як що це ця цей ми ви вона воно вони його йому мене але або щоб тому чому дуже вже ще"
                    + " теж також буде була було був можна добре дякую будь ласка звичайно ось нема хто куди"
                    + " завжди котра котрий робити робиш роблю бачу гарна гарний гарно звати кохаю година"
                    + " тиждень ранок мова мову чекати",
            "rus:как что это этот он она оно они его ему меня мне тебя но или если чтобы потому почему"
                    + " когда где очень уже тоже также будет была было можно нужно хорошо спасибо"
                    + " пожалуйста конечно да нет вот сейчас всегда никогда кто куда сколько который"
                    + " которая делать делаешь делаю вижу говорю красивая красивый зовут неделя год утро"
                    + " вечер ночь язык привет пока сегодня здравствуйте свидания ждать",
            // Arabic script: letters settle most sentences; these settle short ones.
            "ara:نعم هل قد مع عن لقد لم لن",
            "fas:که را خیلی چه هست هستم نیست برای",
            "ckb:سوپاس باش باشم باشی چی کوا"};

    /**
     * The language whose {@link #WORDS} the sentence has more of than any
     * other's, if it can be written this way; null when none leads. In
     * Cyrillic only Russian and Ukrainian sentences are judged (Belarusian
     * shares "як").
     */
    private static String byWords(UnicodeScript script, String language, String sentence) {
        if (script == UnicodeScript.CYRILLIC && !"rus".equals(language) && !"ukr".equals(language)) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        int second = 0;
        final String[] words = sentence.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{M}]+");
        for (String entry : WORDS) {
            final int colon = entry.indexOf(':');
            final String lang = entry.substring(0, colon);
            if (scriptOf(lang) != script) {
                continue;
            }
            int n = 0;
            for (String w : words) {
                if (!w.isEmpty() && (" " + entry.substring(colon + 1) + " ").contains(" " + w + " ")) {
                    n++;
                }
            }
            if (n > bestCount) {
                second = bestCount;
                best = lang;
                bestCount = n;
            } else if (n > second) {
                second = n;
            }
        }
        return best != null && bestCount > second && writes(best, sentence) ? best : null;
    }

    private static boolean hasNotWritten(String language) {
        for (String entry : NOT_WRITTEN) {
            if (entry.startsWith(language + ":")) {
                return true;
            }
        }
        return false;
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
            "ukr:їєґ", "bel:ў", "srp:ђћјљњџ", "mkd:ѓќѕјљњџ", "kaz:әғқңөұүһ", "tat:әөүҗңһ", "bak:ҙҫҡғәөүңһ",
            "~ukr:і", "~bel:і", "~rus:ыэъё"};
    private static final String[] ARABIC_LETTERS = {
            // Persian: ی/ک (not Arabic's ي/ك) and پ چ ژ گ; Arabic: ة ي ك ى.
            "urd:ٹڈڑںےۓہھ", "pus:ټډړښږځڅۍې", "snd:ٻڀٺٽٿڃڄڇڊڌڍڏڙڦڱڳ", "ckb:ڵۆێڕە",
            "uig:ۈۋۇېەڭ", "~fas:یکپچژگ", "~ara:ةيكى"};
    private static final String[] BENGALI_LETTERS = {"asm:ৰৱ", "~ben:র"};
    /**
     * Letters a language never writes, as "language:letters": a run with
     * one of them is not that language, whatever its other letters say
     * ("Слава Україні и ушёл" has ё, which Ukrainian lacks).
     */
    private static final String[] NOT_WRITTEN = {
            "ukr:ыэъё", "bel:ищъ", "srp:йщъыьэюяёєїі", "mkd:йщъыьэюяёєїі",
            "rus:іїєґўђћјљњџѓќѕәғқңөұүһҗҙҫҡ", "asm:র", "ben:ৰৱ",
            // Sorani: ی ک and ە, never Arabic's ي ك ة ى, its ث ذ ض ظ أ إ or
            // vowel marks; a word never starts with a bare ا (see writes).
            "ckb:ةيكىثذضظأإًٌٍَُِّْ"};

    private static String[] table(UnicodeScript script) {
        return script == UnicodeScript.CYRILLIC ? CYRILLIC_LETTERS
                : script == UnicodeScript.ARABIC ? ARABIC_LETTERS : BENGALI_LETTERS;
    }

    /** False if {@code run} has a letter {@code language} never writes. */
    private static boolean writes(String language, String run) {
        if ("ckb".equals(language)) {
            // Sorani writes a vowel at a word's start after ئ: ئەمڕۆ, ئاو.
            // A bare ا there is Arabic or Persian (the article ال...).
            for (int i = 0; i < run.length(); i++) {
                if (run.charAt(i) == 'ا' && (i == 0 || !isArabicLetter(run.charAt(i - 1)))) {
                    return false;
                }
            }
        }
        for (String entry : NOT_WRITTEN) {
            if (entry.startsWith(language + ":")) {
                for (int i = 0; i < run.length(); i++) {
                    if (entry.indexOf(Character.toLowerCase(run.charAt(i)), language.length() + 1) >= 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasLetter(String text) {
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            if (Character.isLetter(c)) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }

    private static boolean isArabicLetter(char c) {
        return Character.isLetter(c) && UnicodeScript.of(c) == UnicodeScript.ARABIC;
    }

    /** The language of {@code run} by its letters, or null; {@code count} and {@code weak} describe the win. */
    private static String byLetters(UnicodeScript script, String run, int[] count, boolean[] weak) {
        String best = null;
        int bestCount = 0;
        for (boolean w : new boolean[] {false, true}) {
            for (String entry : table(script)) {
                if ((entry.charAt(0) == '~') != w) {
                    continue;
                }
                final int colon = entry.indexOf(':');
                final String language = entry.substring(w ? 1 : 0, colon);
                int n = 0;
                for (int i = 0; i < run.length(); i++) {
                    if (entry.indexOf(Character.toLowerCase(run.charAt(i)), colon + 1) >= 0) {
                        n++;
                    }
                }
                if (n > bestCount && writes(language, run)) {
                    best = language;
                    bestCount = n;
                }
            }
            if (best != null) {
                count[0] = bestCount;
                weak[0] = w;
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
    public static boolean espeakReadsItself(String language, String own, Map<UnicodeScript, String> chosen) {
        if (language == null) {
            return false;
        }
        if (language.equals(own) || "zxx".equals(language)) {
            return true;
        }
        if (chosen != null && chosen.containsValue(language)) {
            // eSpeak switches into the chosen language only from another
            // script: an Arabic voice reads Arabic script with its own rules,
            // whatever Arabic script is set to (Sorani was spelled out).
            return !writtenIn(own, scriptOf(language));
        }
        // eSpeak reads this script in the language chosen for it, so another
        // language detected in it (Arabic with Arabic script set to Sorani)
        // needs its own voice.
        final String chosenForScript = chosen != null ? chosen.get(scriptOf(language)) : null;
        if (chosenForScript != null) {
            return false;
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
    public static List<Run> splitDigits(Run run, String numbers) {
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
