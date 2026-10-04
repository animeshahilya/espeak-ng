/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ultra-fast, zero-unnecessary-allocation discriminator between Marathi (mar)
 * and Hindi (hin) for text in the Devanagari script.
 *
 * <p>Both languages share the Devanagari script (Unicode U+0900..U+097F), but
 * possess distinct orthography and grammar:
 * <ul>
 *   <li><b>Unique characters:</b> Marathi ubiquitously employs {@code ळ}
 *       (U+0933, DEVANAGARI LETTER LLA) and {@code ऱ} (U+0931, DEVANAGARI LETTER
 *       RRA / eyelash reph, e.g. वेळ, शाळा, काळजी, डोळे, तऱ्हा). Hindi lacks both.</li>
 *   <li><b>Grammar and vocabulary:</b> Marathi and Hindi auxiliaries, pronouns,
 *       conjunctions, and verb inflections are distinct (e.g. Marathi
 *       आहे/नाही/मला/आणि/खूप vs Hindi है/नहीं/मुझे/और/बहुत).</li>
 * </ul>
 *
 * <p>This prevents Hindi Piper voices like {@code hi_IN-priyamvada-medium} from
 * improperly reading Marathi text, and vice versa.
 */
final class DevanagariClassifier {

    private DevanagariClassifier() {
    }

    static final String LANG_MARATHI = "mar";
    static final String LANG_HINDI = "hin";

    // Ubiquitous unique Marathi characters
    private static final char CHAR_LLA = '\u0933'; // ळ
    private static final char CHAR_RRA = '\u0931'; // ऱ

    // High-frequency distinctive Marathi vocabulary
    private static final String[] MARATHI_WORD_LIST = {
            // Auxiliaries & Negation
            "आहे", "आहेत", "आहात", "आहेस", "आहोत",
            "नाही", "नाहीत", "नाहीस", "नाहीतर",
            "नव्हता", "नव्हती", "नव्हते", "नव्हतो",
            "नसून", "नसल्यास", "नसता", "नसते",

            // Pronouns
            "मला", "तुला", "त्याला", "तिला", "आम्हाला", "तुम्हाला", "त्यांना",
            "माझे", "माझा", "माझी", "माझं",
            "तुझे", "तुझा", "तुझी", "तुझं",
            "त्याचे", "त्याचा", "त्याची", "त्याचं",
            "तिचे", "तिचा", "तिची", "तिचं",
            "आमचे", "आमचा", "आमची", "आमचं",
            "तुमचे", "तुमचा", "तुमची", "तुमचं",
            "त्यांचे", "त्यांचा", "त्यांची", "त्यांचं",
            "आम्ही", "तुम्ही", "आपण", "स्वतः",

            // Conjunctions & Adverbs & Questions
            "आणि", "पण", "किंवा", "म्हणून", "जरी", "तरी",
            "खूप", "छान",
            "कसा", "कसे", "कशी", "कसं",
            "कशा", "कशाला", "कशासाठी", "कशाचा", "कशाची", "कशाचे",
            "काय", "कुठे", "केव्हा", "कधी",
            "इथे", "तिथे", "येथे",
            "आता", "तेव्हा", "जेव्हा",
            "लवकर", "पुन्हा", "नक्की", "फक्त", "काही", "कसेकाय",

            // Verbs, Participles, Infinitives
            "झाले", "झाला", "झाली", "झालं", "झालो", "झालास",
            "केले", "केला", "केली", "केलं", "केलो",
            "करतो", "करते", "करतात", "करतोस", "करा", "करू",
            "करायचे", "करायचा", "करायची", "करायचं", "करणार", "करायला",
            "गेला", "गेली", "गेले", "गेलं", "गेलो",
            "आला", "आली", "आले", "आलं", "आलो",
            "दिला", "दिली", "दिले", "दिलं",
            "घेतला", "घेतली", "घेतले", "घेतलं",
            "सांगितले", "सांगितला", "सांगितली", "सांगितलं",
            "बघितले", "पाहिले", "विचारले", "वाटते", "वाटले",
            "राहतो", "राहते", "राहतात", "राहतोस",
            "येते", "येतो", "येतात",
            "आवडले", "आवडला", "आवडली",

            // Postpositions / Suffixes
            "मध्ये", "सोबत", "शिवाय", "घरी", "वरून", "बद्दल", "विषयी"
    };

    // High-frequency distinctive Hindi vocabulary
    private static final String[] HINDI_WORD_LIST = {
            // Auxiliaries & Negation
            "है", "हैं", "हो", "हूँ", "हूं",
            "था", "थी", "थे",
            "नहीं", "मत",
            "होगा", "होगी", "होंगे",

            // Pronouns
            "मुझे", "तुझे", "उसे", "हमें", "उन्हें",
            "मेरा", "मेरी", "मेरे",
            "तुम्हारा", "तुम्हारी", "तुम्हारे",
            "उसका", "उसकी", "उसके",
            "उनका", "उनकी", "उनके",
            "हमारा", "हमारी", "हमारे",
            "आप", "आपका", "आपकी", "आपके",
            "यह", "वह", "ये", "वो",
            "किसे", "किसका", "किसकी", "किसके", "किन्हें",

            // Postpositions
            "का", "की", "के",
            "में", "से", "को", "ने", "पर", "तक",

            // Conjunctions & Adverbs & Questions
            "और", "तथा", "एवं", "लेकिन", "मगर", "किंतु", "परंतु", "इसलिए", "क्योंकि",
            "बहुत", "अच्छा", "अच्छी", "अच्छे",
            "कैसा", "कैसे", "कैसी",
            "क्या", "क्यों", "कहाँ", "कहा", "कब", "यहाँ", "वहाँ", "अब", "जब", "तब",
            "जल्दी", "फिर", "सिर्फ", "बिल्कुल", "कुछ", "नमस्ते", "शुक्रिया",

            // Verbs & Participles
            "किया", "किए", "करता", "करती", "करते", "करेंगे", "करेगा", "करेगी", "करना",
            "गया", "गयी", "गई", "गए",
            "आया", "आयी", "आई", "आए",
            "दिया", "दी", "दिए",
            "लिया", "ली", "लिए",
            "रहा", "रही", "रहे",
            "सकता", "सकती", "सकते",
            "बोला", "बोली", "बोले",
            "कहता", "कहती", "कहते"
    };

    private static final Set<String> MARATHI_WORDS;
    private static final Set<String> HINDI_WORDS;

    static {
        final Set<String> m = new HashSet<>(MARATHI_WORD_LIST.length * 2);
        Collections.addAll(m, MARATHI_WORD_LIST);
        MARATHI_WORDS = Collections.unmodifiableSet(m);

        final Set<String> h = new HashSet<>(HINDI_WORD_LIST.length * 2);
        Collections.addAll(h, HINDI_WORD_LIST);
        HINDI_WORDS = Collections.unmodifiableSet(h);
    }

    /**
     * Fast check whether the text contains any character in the Devanagari
     * Unicode block (U+0900..U+097F).
     */
    static boolean hasDevanagari(CharSequence text) {
        if (text == null) {
            return false;
        }
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            final char c = text.charAt(i);
            if (c >= 0x0900 && c <= 0x097F) {
                return true;
            }
        }
        return false;
    }

    static boolean isDevanagariCodePoint(int c) {
        return c >= 0x0900 && c <= 0x097F;
    }

    /**
     * A contiguous character range in text classified as a specific language.
     */
    static final class Span {
        final int start;
        final int end;
        final String language;

        Span(int start, int end, String language) {
            this.start = start;
            this.end = end;
            this.language = language;
        }
    }

    /**
     * Precomputed map of Devanagari spans. Provides O(1) sequential lookup
     * during character-by-character resolution with zero heap allocations.
     */
    static final class SpanMap {
        static final SpanMap EMPTY = new SpanMap(Collections.emptyList());

        private final List<Span> spans;
        private int cursor = 0;

        SpanMap(List<Span> spans) {
            this.spans = spans != null ? spans : Collections.emptyList();
        }

        boolean isEmpty() {
            return spans.isEmpty();
        }

        String languageAt(int charIndex, String fallback) {
            final int size = spans.size();
            if (size == 0) {
                return fallback;
            }
            if (cursor < size) {
                final Span s = spans.get(cursor);
                if (charIndex >= s.start && charIndex < s.end) {
                    return s.language;
                }
                if (charIndex >= s.end) {
                    cursor++;
                    while (cursor < size) {
                        final Span next = spans.get(cursor);
                        if (charIndex < next.end) {
                            if (charIndex >= next.start) {
                                return next.language;
                            }
                            break;
                        }
                        cursor++;
                    }
                }
            }
            for (int i = 0; i < size; i++) {
                final Span s = spans.get(i);
                if (charIndex >= s.start && charIndex < s.end) {
                    cursor = i;
                    return s.language;
                }
            }
            return fallback;
        }
    }

    /**
     * Builds language spans for all Devanagari sections in {@code text}.
     */
    static SpanMap buildSpans(String text, String ownLanguage, String chosenDevanagari) {
        if (!hasDevanagari(text)) {
            return SpanMap.EMPTY;
        }

        final int length = text.length();
        final List<Span> rawSpans = new ArrayList<>();

        // Process sentence by sentence (delimiters: . । ॥ ? ! ; \n \r)
        int sentenceStart = 0;
        for (int i = 0; i <= length; i++) {
            final boolean isEnd = (i == length);
            final char c = isEnd ? '\0' : text.charAt(i);
            if (isEnd || isSentenceDelimiter(c)) {
                if (i > sentenceStart) {
                    processSentence(text, sentenceStart, i, ownLanguage, chosenDevanagari, rawSpans);
                }
                sentenceStart = i + 1;
            }
        }

        if (rawSpans.isEmpty()) {
            return SpanMap.EMPTY;
        }

        // Merge adjacent spans with identical language
        final List<Span> merged = new ArrayList<>(rawSpans.size());
        Span current = rawSpans.get(0);
        for (int i = 1; i < rawSpans.size(); i++) {
            final Span next = rawSpans.get(i);
            if (current.language.equals(next.language)) {
                current = new Span(current.start, next.end, current.language);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return new SpanMap(merged);
    }

    private static boolean isSentenceDelimiter(char c) {
        return c == '.' || c == '\u0964' || c == '\u0965' || c == '?' || c == '!'
                || c == ';' || c == '\n' || c == '\r';
    }

    private static boolean isClauseDelimiter(char c) {
        return c == ',' || c == ':' || c == '\u2014' || c == '-' || c == '(' || c == ')'
                || c == '"' || c == '\'';
    }

    private static void processSentence(String text, int start, int end,
                                        String ownLanguage, String chosenDevanagari,
                                        List<Span> out) {
        // Find Devanagari range in sentence
        int devStart = -1;
        int devEnd = -1;
        for (int i = start; i < end; i++) {
            if (isDevanagariCodePoint(text.codePointAt(i))) {
                if (devStart < 0) {
                    devStart = i;
                }
                devEnd = i + 1;
            }
        }
        if (devStart < 0) {
            return; // No Devanagari in this sentence
        }

        // Check if sentence has conflicting markers (both Hindi and Marathi)
        final int[] sentenceScores = scoreRange(text, devStart, devEnd);
        final int mScore = sentenceScores[0];
        final int hScore = sentenceScores[1];

        if (mScore > 0 && hScore > 0) {
            // Conflict within a single sentence: split where language transitions
            processConflictedSentence(text, devStart, devEnd, ownLanguage, chosenDevanagari, out);
        } else {
            // Cohesive sentence: determine language for the whole Devanagari span
            final String lang = decideLanguage(mScore, hScore, ownLanguage, chosenDevanagari);
            out.add(new Span(devStart, devEnd, lang));
        }
    }

    private static void processConflictedSentence(String text, int devStart, int devEnd,
                                                  String ownLanguage, String chosenDevanagari,
                                                  List<Span> out) {
        int spanStart = devStart;
        String currentLang = null;

        int tokenStart = -1;
        for (int i = devStart; i <= devEnd; i++) {
            final boolean isEnd = (i == devEnd);
            final int cp = isEnd ? 0 : Character.codePointAt(text, i);
            final boolean isDevWord = !isEnd && isDevanagariWordChar(cp);

            if (isDevWord) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
            } else if (tokenStart >= 0) {
                final String word = text.subSequence(tokenStart, i).toString();
                int wm = 0;
                int wh = 0;
                if (containsMarathiChar(word)) {
                    wm += 10;
                }
                if (MARATHI_WORDS.contains(word) || (word.length() > 5 && word.endsWith("मध्ये"))) {
                    wm += 3;
                }
                if (HINDI_WORDS.contains(word)) {
                    wh += 3;
                }

                String wordLang = null;
                if (wm > wh) {
                    wordLang = "mr".equals(ownLanguage) ? "mr" : LANG_MARATHI;
                } else if (wh > wm) {
                    wordLang = "hi".equals(ownLanguage) ? "hi" : LANG_HINDI;
                }

                if (wordLang != null) {
                    if (currentLang == null) {
                        currentLang = wordLang;
                    } else if (!wordLang.equals(currentLang)) {
                        if (tokenStart > spanStart) {
                            out.add(new Span(spanStart, tokenStart, currentLang));
                            spanStart = tokenStart;
                        }
                        currentLang = wordLang;
                    }
                }
                tokenStart = -1;
            }
        }

        if (devEnd > spanStart) {
            final String lang = currentLang != null ? currentLang
                    : decideLanguage(0, 0, ownLanguage, chosenDevanagari);
            out.add(new Span(spanStart, devEnd, lang));
        }
    }

    private static void processClause(String text, int start, int end,
                                      String ownLanguage, String chosenDevanagari,
                                      List<Span> out) {
        int devStart = -1;
        int devEnd = -1;
        for (int i = start; i < end; i++) {
            if (isDevanagariCodePoint(text.codePointAt(i))) {
                if (devStart < 0) {
                    devStart = i;
                }
                devEnd = i + 1;
            }
        }
        if (devStart < 0) {
            return;
        }

        final int[] scores = scoreRange(text, devStart, devEnd);
        final String lang = decideLanguage(scores[0], scores[1], ownLanguage, chosenDevanagari);
        out.add(new Span(devStart, devEnd, lang));
    }

    private static String decideLanguage(int mScore, int hScore,
                                         String ownLanguage, String chosenDevanagari) {
        if (mScore > hScore) {
            return "mr".equals(ownLanguage) ? "mr" : LANG_MARATHI;
        }
        if (hScore > mScore) {
            return "hi".equals(ownLanguage) ? "hi" : LANG_HINDI;
        }
        // Neutral or tied: if the speaking voice's language is written in Devanagari
        // (hne, bho, san, nep, mai, kok, mr, hi...), text stays with the speaking language.
        if (ownLanguage != null && LanguageRuns.isDevanagariLanguage(ownLanguage)) {
            return ownLanguage;
        }
        if (chosenDevanagari != null && !chosenDevanagari.isEmpty()) {
            return chosenDevanagari;
        }
        return "hi".equals(ownLanguage) ? "hi" : LANG_HINDI;
    }

    /**
     * Computes [marathiScore, hindiScore] for text in [start, end).
     */
    static int[] scoreRange(CharSequence text, int start, int end) {
        int marathi = 0;
        int hindi = 0;

        // 1. Scan for unique Marathi characters
        for (int i = start; i < end; i++) {
            final char c = text.charAt(i);
            if (c == CHAR_LLA || c == CHAR_RRA) {
                marathi += 10;
            } else if (c == '\u0930' && i + 2 < end
                    && text.charAt(i + 1) == '\u094D' && text.charAt(i + 2) == '\u200D') {
                // Eyelash reph sequence (ra + virama + ZWJ)
                marathi += 10;
                i += 2;
            }
        }

        // 2. Tokenize words and match against distinctive lexicons
        int tokenStart = -1;
        for (int i = start; i <= end; i++) {
            final boolean isEnd = (i == end);
            final int cp = isEnd ? 0 : Character.codePointAt(text, i);
            final boolean isDevWordChar = !isEnd && isDevanagariWordChar(cp);

            if (isDevWordChar) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
            } else if (tokenStart >= 0) {
                final String word = text.subSequence(tokenStart, i).toString();
                if (MARATHI_WORDS.contains(word) || (word.length() > 5 && word.endsWith("मध्ये"))) {
                    marathi += 3;
                } else if (HINDI_WORDS.contains(word)) {
                    hindi += 3;
                }
                tokenStart = -1;
            }
        }

        return new int[] {marathi, hindi};
    }

    private static boolean containsMarathiChar(CharSequence s) {
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            if (c == CHAR_LLA || c == CHAR_RRA) {
                return true;
            }
            if (c == '\u0930' && i + 2 < len && s.charAt(i + 1) == '\u094D' && s.charAt(i + 2) == '\u200D') {
                return true;
            }
        }
        return false;
    }

    private static boolean isDevanagariWordChar(int cp) {
        // Devanagari letters, vowels, marks, nukta, virama
        return cp >= 0x0900 && cp <= 0x097F && cp != '\u0964' && cp != '\u0965';
    }

    /**
     * Standalone classification of a Devanagari string.
     */
    static String classify(CharSequence text, String ownLanguage, String fallback) {
        if (text == null || text.length() == 0 || !hasDevanagari(text)) {
            return fallback;
        }
        final int[] scores = scoreRange(text, 0, text.length());
        return decideLanguage(scores[0], scores[1], ownLanguage, fallback);
    }
}
