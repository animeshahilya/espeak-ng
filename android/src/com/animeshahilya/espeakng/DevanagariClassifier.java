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
 * Ultra-fast, zero-unnecessary-allocation discriminator for languages written in
 * the Devanagari script (Unicode U+0900..U+097F).
 *
 * <p>Supported Devanagari languages:
 * <ol>
 *   <li><b>Hindi (hin / hi)</b></li>
 *   <li><b>Marathi (mar / mr)</b></li>
 *   <li><b>Nepali (nep / ne)</b></li>
 *   <li><b>Sanskrit (san / sa)</b></li>
 *   <li><b>Bhojpuri (bho)</b></li>
 *   <li><b>Maithili (mai)</b></li>
 *   <li><b>Chhattisgarhi (hne)</b></li>
 *   <li><b>Konkani (kok)</b></li>
 * </ol>
 *
 * <p>Discriminatory features:
 * <ul>
 *   <li><b>Orthographic & unique characters:</b>
 *     <ul>
 *       <li><b>Sanskrit:</b> Avagraha {@code ऽ} (U+093D, e.g. शिवोऽहम्, सोऽपि) and
 *           frequent nominal visarga {@code ः} (U+0903, e.g. नमः, बालकः, रामः).</li>
 *       <li><b>Marathi:</b> Unique {@code ऱ} (U+0931, RRA / eyelash reph, e.g. कऱ्हाड, तऱ्हा)
 *           and ubiquitous {@code ळ} (U+0933, LLA, e.g. वेळ, शाळा, काळजी, डोळे).</li>
 *       <li><b>Konkani:</b> Shares {@code ळ} with Marathi; distinctively features frequent
 *           final nasalization ({@code -ां}, {@code -ें}) and Goan lexicon.</li>
 *       <li><b>Nepali:</b> Standalone single-letter conjunction {@code "र"} ("and"),
 *           plural suffix {@code -हरू}/{@code -हरु}, and case marker {@code -लाई}.</li>
 *     </ul>
 *   </li>
 *   <li><b>Grammar and verbal systems:</b>
 *     Each language possesses an unambiguous, distinct verbal copula and auxiliary set:
 *     <ul>
 *       <li><b>Hindi:</b> है, हैं, था, थी, थे, नहीं, मुझे, और, बहुत</li>
 *       <li><b>Marathi:</b> आहे, आहेत, नाही, मला, आणि, खूप, छान</li>
 *       <li><b>Nepali:</b> छ, छन्, छैन, हो, होइन, थियो, हुनुहुन्छ, राम्रो</li>
 *       <li><b>Sanskrit:</b> अस्ति, सन्ति, आसीत्, अहम्, त्वम्, च, अपि, एव, भवति</li>
 *       <li><b>Bhojpuri:</b> बा, बाटे, बाड़े, बानी, नाइखे, रउआ, हमरा, जातानी</li>
 *       <li><b>Maithili:</b> अछि, अछी, छथि, छल, छलाह, भेल, नहि, अहाँ, हमर</li>
 *       <li><b>Chhattisgarhi:</b> हे, हेवय, रहिस, होही, नइये, मोर, तोर, अब्बड़, जोहार</li>
 *       <li><b>Konkani:</b> आसा, आसात, आसलो, नाका, हांव, म्हाका, तुका, बरे, करूं</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Prevents cross-language voice hijacking (e.g. Hindi Priyamvada reading Marathi,
 * Nepali, Sanskrit, or Bhojpuri text incorrectly).
 */
final class DevanagariClassifier {

    private DevanagariClassifier() {
    }

    static final String LANG_HINDI = "hin";
    static final String LANG_MARATHI = "mar";
    static final String LANG_NEPALI = "nep";
    static final String LANG_SANSKRIT = "san";
    static final String LANG_BHOJPURI = "bho";
    static final String LANG_MAITHILI = "mai";
    static final String LANG_CHHATTISGARHI = "hne";
    static final String LANG_KONKANI = "kok";

    static final int IDX_HINDI = 0;
    static final int IDX_MARATHI = 1;
    static final int IDX_NEPALI = 2;
    static final int IDX_SANSKRIT = 3;
    static final int IDX_BHOJPURI = 4;
    static final int IDX_MAITHILI = 5;
    static final int IDX_CHHATTISGARHI = 6;
    static final int IDX_KONKANI = 7;
    static final int NUM_LANGS = 8;

    // Unique orthographic characters
    private static final char CHAR_LLA = '\u0933';      // ळ (Marathi & Konkani)
    private static final char CHAR_RRA = '\u0931';      // ऱ (Marathi)
    private static final char CHAR_AVAGRAHA = '\u093D'; // ऽ (Sanskrit)
    private static final char CHAR_VISARGA = '\u0903';  // ः (Sanskrit)

    // =========================================================================
    // 1. High-Frequency Lexicons
    // =========================================================================

    private static final String[] NEPALI_WORD_LIST = {
            // Auxiliaries & Copulas
            "छ", "छन्", "छैन", "छैनन्", "हुन्", "हो", "होइन", "हैन",
            "थियो", "थिई", "थिए", "थिइन्", "हुन्छ", "हुँदैन", "हुदैन",
            "हुनुहुन्छ", "हुनेछ", "भयो", "भएन", "भएको",

            // Verbs
            "गर्यो", "गर्छ", "गरेको", "गर्नुहोस्", "गर्नुस", "गरे", "गर्दै",
            "भन्छ", "भने", "दिनुहोस्", "दिनुस", "गयो", "आयो", "हेर्नुहोस्", "पो",

            // Pronouns
            "म", "मलाई", "मेरो", "हामी", "हाम्रो", "हामीलाई",
            "तिमी", "तिम्रो", "तिमीलाई", "तपाईं", "तपाई", "तपाईंको", "तपाईंलाई",
            "ऊ", "उसको", "उसलाई", "उनी", "उनीहरू", "उनीहरु", "उनीहरूको",
            "त्यो", "त्यसको", "त्यसलाई", "यो", "यसको", "यसलाई",

            // Conjunctions & Adverbs & Questions
            "अनि", "तर", "किनभने", "धेरै", "थोरै", "अलिकति",
            "राम्रो", "नराम्रो", "कस्तो", "कति", "कहिले", "अहिले",
            "कहाँ", "किन", "के", "कसरी", "सधैं", "सधै", "पनि", "मात्र", "सबै",

            // Postpositions
            "लाई", "बाट", "सँग", "संग", "भन्दा"
    };

    private static final String[] SANSKRIT_WORD_LIST = {
            // Auxiliaries & Copulas & Verbs
            "अस्ति", "सन्ति", "आसीत्", "आसन्", "भवति", "भवन्ति", "स्यात्",
            "वर्त्तते", "वर्तते", "विद्यते", "करोति", "कुर्वन्ति", "कुरु",
            "गच्छति", "गच्छन्ति", "गच्छामि", "गच्छसि", "आगच्छति",
            "पठति", "पठन्ति", "लिखति", "जानाति", "उवाच", "ब्रवीति",
            "पश्यति", "इच्छति", "अभवत्",

            // Pronouns
            "अहम्", "अहं", "त्वाम्", "त्वां", "त्वम्", "त्वं", "वयम्", "वयं",
            "यूयम्", "यूयं", "सह", "सः", "तौ", "ते", "ताः",
            "तस्य", "तस्याः", "तस्मै", "तस्मात्", "तस्मिन", "तस्मिन्",
            "मम", "तव", "अस्माकम्", "अस्माकं", "युष्माकम्", "युष्माकं",
            "मह्यम्", "तुभ्यम्", "किम्", "किं", "कः", "का",

            // Particles, Conjunctions & Adverbs (Avyaya)
            "च", "अपि", "एव", "इति", "तु", "यदि", "तर्हि", "यथा", "तथा",
            "अत्र", "तत्र", "कुत्र", "सर्वत्र", "एकत्र", "कदा", "सदा", "सर्वदा",
            "पुनः", "विना", "अलम्", "अलं", "कथम्", "किमर्थम्", "यतः",
            "इदानीम्", "अधुना", "नूनम्",

            // Salutations & Formulaic
            "नमः", "नमो", "सुप्रभातम्", "शुभरात्रिः", "धन्यवादः", "स्वागतम्",
            "सत्यमेव", "जयते", "शान्तिः", "शांतिः"
    };

    private static final String[] BHOJPURI_WORD_LIST = {
            // Auxiliaries & Copulas & Negation
            "बा", "बाटे", "बाड़न", "बाड़े", "बानी", "बाड़ऽ", "बाड़ा",
            "नाइखे", "नइखे", "निखे", "नाइखीं", "नइखीं",
            "रहलन", "रहले", "रहल", "रहनी", "रहला", "होई", "होखे", "हवे",

            // Pronouns
            "हमरा", "हमार", "हमारो", "तोहरा", "तोहार", "ओकरा", "ओकर",
            "रउआ", "रउवा", "हमनिके", "हमारन", "तहार", "एकरा", "एकर",
            "एह", "ओह", "केहू", "काहे", "काहेला",

            // Verbs
            "जातानी", "करतानी", "कइल", "गइल", "आइल", "भइल", "देखल",
            "बोलल", "सुतल", "कइले", "गइले", "आइले", "भइले", "कहलन", "कहले",
            "करत", "जात", "आवत",

            // Adverbs & Conjunctions & Questions
            "का", "कइसे", "कहवाँ", "एहजा", "ओहजा", "तनिक", "ढेर", "बदे", "सन", "जाव"
    };

    private static final String[] MAITHILI_WORD_LIST = {
            // Auxiliaries & Copulas & Negation
            "अछि", "अछी", "छथि", "छल", "छलाह", "छलीह", "छलहुँ",
            "भेल", "भेलहुँ", "नहि", "नै", "रहथिन", "रहए", "होएत", "होयत",

            // Pronouns
            "हमर", "तोहर", "अहाँ", "अहाँक", "तोरा", "हुनक", "हुनका",
            "ओकर", "ओकरा", "अपने", "केओ", "कथिला",

            // Verbs
            "गेल", "गेलाह", "आयल", "आएल", "आएलाह", "कएल", "केल",
            "कहथि", "कहलय", "देलखिन", "लेल", "देखल", "कहल",

            // Conjunctions & Adverbs & Questions
            "मुदा", "आर", "किएक", "कतय", "एखन", "तखन", "जखन",
            "किछु", "नीक", "कते", "कोना"
    };

    private static final String[] CHHATTISGARHI_WORD_LIST = {
            // Auxiliaries & Copulas & Negation
            "हे", "हेवय", "हेन", "रहिस", "रहिसे", "रहिन", "होही", "होवय",
            "नइये", "नइहे", "नोहे", "हंव",

            // Pronouns
            "मोर", "हमन", "तँय", "तें", "तोर", "तुमन", "तुंहर",
            "ओहा", "ओकर", "एहा", "एकर", "कोनो",

            // Conjunctions & Adverbs & Questions & Particles
            "काबर", "कतका", "अब्बड़", "संगी", "जोहार", "गोठ", "गा",
            "अउ", "अउर", "फेर", "तभे", "जबे", "बने",

            // Verbs
            "करिस", "गेहिस", "आइस", "दीस", "करबो", "जाबो", "पाबो",
            "कहिस", "देखिस"
    };

    private static final String[] KONKANI_WORD_LIST = {
            // Auxiliaries & Copulas & Verbs
            "आसा", "आसात", "आसलो", "आसली", "आसले", "आसलेत",
            "नाका", "जाय", "जालो", "जाली", "जाले", "जाला",
            "केला", "केली", "केले", "केलो", "करता", "करतात", "करूं",
            "गेलो", "गेली", "गेले", "आयलो", "आयली", "आयले", "सांगले",

            // Pronouns
            "हांव", "म्हाका", "म्हाजो", "म्हाजी", "म्हाजे", "म्हाजें",
            "तू", "तुका", "तुजो", "तुजी", "तुजे", "तुजें",
            "तो", "ती", "तें", "ताका", "ताजो", "ताजी", "ताजे", "ताजें",
            "आमी", "आमकां", "आमचो", "आमची", "आमचे", "आमचें",
            "तुम्ही", "तांकां", "तांचो", "तांची", "तांचे",

            // Conjunctions & Adverbs & Questions
            "आनी", "पूण", "कित्याक", "खंय", "कितें", "कशें", "कसो", "कशी",
            "केन्ना", "आतां", "थंय", "हांगा", "भोव", "बरे", "बरें", "मागीर",
            "दीस"
    };

    private static final String[] MARATHI_WORD_LIST = {
            // Auxiliaries & Negation
            "आहे", "आहेत", "आहात", "आहेस", "आहोत",
            "नाही", "नाहीत", "नाहीस", "नाहीतर",
            "नव्हता", "नव्हती", "नव्हते", "नव्हतो",
            "नसून", "नसल्यास", "नसता", "नसते",

            // Pronouns
            "हे", "हा", "ही",
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
            "जल्दी", "फिर", "सिर्फ", "बिल्कुल", "कुछ", "शुक्रिया",

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

    private static final Set<String> NEPALI_WORDS;
    private static final Set<String> SANSKRIT_WORDS;
    private static final Set<String> BHOJPURI_WORDS;
    private static final Set<String> MAITHILI_WORDS;
    private static final Set<String> CHHATTISGARHI_WORDS;
    private static final Set<String> KONKANI_WORDS;
    private static final Set<String> MARATHI_WORDS;
    private static final Set<String> HINDI_WORDS;

    static {
        NEPALI_WORDS = createSet(NEPALI_WORD_LIST);
        SANSKRIT_WORDS = createSet(SANSKRIT_WORD_LIST);
        BHOJPURI_WORDS = createSet(BHOJPURI_WORD_LIST);
        MAITHILI_WORDS = createSet(MAITHILI_WORD_LIST);
        CHHATTISGARHI_WORDS = createSet(CHHATTISGARHI_WORD_LIST);
        KONKANI_WORDS = createSet(KONKANI_WORD_LIST);
        MARATHI_WORDS = createSet(MARATHI_WORD_LIST);
        HINDI_WORDS = createSet(HINDI_WORD_LIST);
    }

    private static Set<String> createSet(String[] words) {
        final Set<String> s = new HashSet<>(words.length * 2);
        Collections.addAll(s, words);
        return Collections.unmodifiableSet(s);
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

        // Check if sentence has conflicting markers from multiple languages
        final int[] sentenceScores = scoreRange(text, devStart, devEnd);
        int maxScore = 0;
        int secondMaxScore = 0;
        for (int i = 0; i < NUM_LANGS; i++) {
            final int sc = sentenceScores[i];
            if (sc > maxScore) {
                secondMaxScore = maxScore;
                maxScore = sc;
            } else if (sc > secondMaxScore) {
                secondMaxScore = sc;
            }
        }

        // Only treat as conflicted if at least 2 languages have substantial conflicting evidence
        if (secondMaxScore >= 5 && maxScore >= 5) {
            processConflictedSentence(text, devStart, devEnd, sentenceScores, ownLanguage, chosenDevanagari, out);
        } else {
            // Cohesive sentence: determine language for the whole Devanagari span
            final String lang = decideLanguage(sentenceScores, ownLanguage, chosenDevanagari);
            out.add(new Span(devStart, devEnd, lang));
        }
    }

    private static void processConflictedSentence(String text, int devStart, int devEnd,
                                                  int[] sentenceScores,
                                                  String ownLanguage, String chosenDevanagari,
                                                  List<Span> out) {
        int spanStart = devStart;
        int currentLangIdx = -1;

        int sentenceWinner = -1;
        int maxSc = 0;
        for (int l = 0; l < NUM_LANGS; l++) {
            if (sentenceScores[l] > maxSc) {
                maxSc = sentenceScores[l];
                sentenceWinner = l;
            }
        }

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
                final int wordLangIdx = classifyWord(word, currentLangIdx, sentenceWinner);

                if (wordLangIdx >= 0) {
                    if (currentLangIdx < 0) {
                        currentLangIdx = wordLangIdx;
                    } else if (wordLangIdx != currentLangIdx) {
                        if (tokenStart > spanStart) {
                            final String lang = toOutputCode(currentLangIdx, ownLanguage, chosenDevanagari);
                            out.add(new Span(spanStart, tokenStart, lang));
                            spanStart = tokenStart;
                        }
                        currentLangIdx = wordLangIdx;
                    }
                }
                tokenStart = -1;
            }
        }

        if (devEnd > spanStart) {
            final String lang = currentLangIdx >= 0
                    ? toOutputCode(currentLangIdx, ownLanguage, chosenDevanagari)
                    : decideLanguage(sentenceScores, ownLanguage, chosenDevanagari);
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
        final String lang = decideLanguage(scores, ownLanguage, chosenDevanagari);
        out.add(new Span(devStart, devEnd, lang));
    }

    private static String decideLanguage(int[] scores, String ownLanguage, String chosenDevanagari) {
        int bestLang = -1;
        int maxScore = 0;
        int secondScore = 0;
        for (int i = 0; i < NUM_LANGS; i++) {
            if (scores[i] > maxScore) {
                secondScore = maxScore;
                maxScore = scores[i];
                bestLang = i;
            } else if (scores[i] > secondScore) {
                secondScore = scores[i];
            }
        }

        if (maxScore > 0 && maxScore > secondScore) {
            return toOutputCode(bestLang, ownLanguage, chosenDevanagari);
        }

        // Tied scores: check if ownLanguage or chosenDevanagari is among the winners
        if (maxScore > 0) {
            final int ownIdx = languageToIndex(ownLanguage);
            if (ownIdx >= 0 && scores[ownIdx] == maxScore) {
                return toOutputCode(ownIdx, ownLanguage, chosenDevanagari);
            }
            final int chosenIdx = languageToIndex(chosenDevanagari);
            if (chosenIdx >= 0 && scores[chosenIdx] == maxScore) {
                return toOutputCode(chosenIdx, ownLanguage, chosenDevanagari);
            }
            return toOutputCode(bestLang, ownLanguage, chosenDevanagari);
        }

        // Neutral or tied with 0 score (e.g. proper nouns like "भारत"):
        // If speaking voice is in Devanagari, preserve it.
        if (ownLanguage != null && LanguageRuns.isDevanagariLanguage(ownLanguage)) {
            return ownLanguage;
        }
        if (chosenDevanagari != null && !chosenDevanagari.isEmpty()) {
            return chosenDevanagari;
        }
        return "hi".equals(ownLanguage) ? "hi" : LANG_HINDI;
    }

    /**
     * Maps an internal language index to the appropriate 2-letter or 3-letter code.
     */
    static String toOutputCode(int langIndex, String ownLanguage) {
        return toOutputCode(langIndex, ownLanguage, null);
    }

    static String toOutputCode(int langIndex, String ownLanguage, String chosenDevanagari) {
        switch (langIndex) {
            case IDX_HINDI:
                return ("hi".equals(ownLanguage) || "hi".equals(chosenDevanagari)) ? "hi" : LANG_HINDI;
            case IDX_MARATHI:
                return ("mr".equals(ownLanguage) || "mr".equals(chosenDevanagari)) ? "mr" : LANG_MARATHI;
            case IDX_NEPALI:
                return ("ne".equals(ownLanguage) || "ne".equals(chosenDevanagari)) ? "ne" : LANG_NEPALI;
            case IDX_SANSKRIT:
                return ("sa".equals(ownLanguage) || "sa".equals(chosenDevanagari)) ? "sa" : LANG_SANSKRIT;
            case IDX_BHOJPURI:
                return LANG_BHOJPURI;
            case IDX_MAITHILI:
                return LANG_MAITHILI;
            case IDX_CHHATTISGARHI:
                return LANG_CHHATTISGARHI;
            case IDX_KONKANI:
                return LANG_KONKANI;
            default:
                return LANG_HINDI;
        }
    }

    static int languageToIndex(String lang) {
        if (lang == null) {
            return -1;
        }
        switch (lang) {
            case "hin": case "hi": return IDX_HINDI;
            case "mar": case "mr": return IDX_MARATHI;
            case "nep": case "ne": return IDX_NEPALI;
            case "san": case "sa": return IDX_SANSKRIT;
            case "bho": return IDX_BHOJPURI;
            case "mai": return IDX_MAITHILI;
            case "hne": return IDX_CHHATTISGARHI;
            case "kok": return IDX_KONKANI;
            default: return -1;
        }
    }

    /**
     * Computes language scores across all 8 Devanagari languages for text in [start, end).
     */
    static int[] scoreRange(CharSequence text, int start, int end) {
        final int[] scores = new int[NUM_LANGS];

        // 1. Scan for unique orthographic characters
        for (int i = start; i < end; i++) {
            final char c = text.charAt(i);
            if (c == CHAR_AVAGRAHA) {
                scores[IDX_SANSKRIT] += 15;
            } else if (c == CHAR_RRA) {
                scores[IDX_MARATHI] += 12;
            } else if (c == CHAR_LLA) {
                scores[IDX_MARATHI] += 6;
                scores[IDX_KONKANI] += 4;
            } else if (c == '\u0930' && i + 2 < end
                    && text.charAt(i + 1) == '\u094D' && text.charAt(i + 2) == '\u200D') {
                // Eyelash reph sequence (ra + virama + ZWJ)
                scores[IDX_MARATHI] += 12;
                i += 2;
            }
        }

        // 2. Tokenize words and score them against distinctive lexicons & grammar
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
                scoreWord(word, scores);
                tokenStart = -1;
            }
        }

        return scores;
    }

    private static void scoreWord(String word, int[] scores) {
        final int len = word.length();
        if (len == 0) {
            return;
        }

        // 1. Single-character standalone words
        if (len == 1) {
            final char c = word.charAt(0);
            if (c == 'र') {
                scores[IDX_NEPALI] += 8;
                return;
            }
            if (c == 'च') {
                scores[IDX_SANSKRIT] += 8;
                return;
            }
        }

        // 2. Exact lexicon matches
        if (NEPALI_WORDS.contains(word)) {
            scores[IDX_NEPALI] += 3;
        }
        if (SANSKRIT_WORDS.contains(word)) {
            scores[IDX_SANSKRIT] += 3;
        }
        if (MARATHI_WORDS.contains(word)) {
            scores[IDX_MARATHI] += 3;
        }
        if (BHOJPURI_WORDS.contains(word)) {
            scores[IDX_BHOJPURI] += 3;
        }
        if (MAITHILI_WORDS.contains(word)) {
            scores[IDX_MAITHILI] += 3;
        }
        if (CHHATTISGARHI_WORDS.contains(word)) {
            scores[IDX_CHHATTISGARHI] += 3;
        }
        if (KONKANI_WORDS.contains(word)) {
            scores[IDX_KONKANI] += 3;
        }
        if (HINDI_WORDS.contains(word)) {
            scores[IDX_HINDI] += 3;
        }

        // 3. High-confidence distinctive vocabulary boosters
        if ("अस्ति".equals(word) || "सन्ति".equals(word) || "आसीत्".equals(word)) {
            scores[IDX_SANSKRIT] += 5;
        } else if ("बा".equals(word) || "नाइखे".equals(word) || "नइखे".equals(word)
                || "रउआ".equals(word) || "रउवा".equals(word) || "बानी".equals(word)
                || "ओकरा".equals(word) || "तोहरा".equals(word) || "हमरा".equals(word)
                || "हमार".equals(word) || "तोहार".equals(word)) {
            scores[IDX_BHOJPURI] += 5;
        } else if ("अछि".equals(word) || "अछी".equals(word) || "छथि".equals(word)
                || "छलाह".equals(word) || "भेल".equals(word) || "अहाँक".equals(word)
                || "हुनक".equals(word)) {
            scores[IDX_MAITHILI] += 5;
        } else if ("जोहार".equals(word)) {
            scores[IDX_CHHATTISGARHI] += 8;
        } else if ("संगी".equals(word) || "अब्बड़".equals(word) || "हेवय".equals(word)
                || "नइये".equals(word) || "काबर".equals(word)) {
            scores[IDX_CHHATTISGARHI] += 5;
        } else if ("आसा".equals(word) || "आसात".equals(word) || "नाका".equals(word)
                || "हांव".equals(word)) {
            scores[IDX_KONKANI] += 5;
        } else if ("आहे".equals(word) || "आहेत".equals(word) || "नाही".equals(word)) {
            scores[IDX_MARATHI] += 4;
        }

        // 4. Suffix and morphological rules
        if (len > 3) {
            // Nepali suffixes: -हरू / -हरु, -लाई, -बाट, -सँग, -संग
            if (word.endsWith("हरू") || word.endsWith("हरु")) {
                scores[IDX_NEPALI] += 8;
            } else if (word.endsWith("लाई")) {
                scores[IDX_NEPALI] += 6;
            } else if (word.endsWith("बाट")) {
                scores[IDX_NEPALI] += 5;
            } else if (word.endsWith("सँग") || word.endsWith("संग")) {
                scores[IDX_NEPALI] += 4;
            }

            // Marathi suffix: -मध्ये
            if (len > 5 && word.endsWith("मध्ये")) {
                scores[IDX_MARATHI] += 5;
            }

            // Bhojpuri verbal suffix: -तानी
            if (word.endsWith("तानी")) {
                scores[IDX_BHOJPURI] += 6;
            }

            // Maithili honorific verbal suffix: -लाह / -थि
            if (word.endsWith("लाह") || (len > 4 && word.endsWith("थि"))) {
                scores[IDX_MAITHILI] += 4;
            }

            // Chhattisgarhi past -इस or future -बो
            if (word.endsWith("इस")) {
                scores[IDX_CHHATTISGARHI] += 4;
            } else if (word.endsWith("बो")) {
                scores[IDX_CHHATTISGARHI] += 3;
            }

            // Sanskrit nominal endings: -ः (visarga) or -म् (halanta ma)
            if (word.endsWith("ः")) {
                scores[IDX_SANSKRIT] += 3;
            } else if (word.endsWith("म्")) {
                scores[IDX_SANSKRIT] += 3;
            }
        }

        // Konkani anusvara endings: -ां or -ें
        if (len >= 2 && (word.endsWith("ां") || word.endsWith("ें"))) {
            scores[IDX_KONKANI] += 2;
        }
    }

    private static int classifyWord(String word, int currentLangIdx, int sentenceWinner) {
        final int[] wordScores = new int[NUM_LANGS];
        scoreWord(word, wordScores);

        // Check if word contains character-level indicators
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            if (c == CHAR_AVAGRAHA) {
                wordScores[IDX_SANSKRIT] += 15;
            } else if (c == CHAR_RRA) {
                wordScores[IDX_MARATHI] += 12;
            } else if (c == CHAR_LLA) {
                wordScores[IDX_MARATHI] += 6;
                wordScores[IDX_KONKANI] += 4;
            } else if (c == '\u0930' && i + 2 < word.length()
                    && word.charAt(i + 1) == '\u094D' && word.charAt(i + 2) == '\u200D') {
                wordScores[IDX_MARATHI] += 12;
                i += 2;
            }
        }

        int bestIdx = -1;
        int maxScore = 0;
        for (int i = 0; i < NUM_LANGS; i++) {
            if (wordScores[i] > maxScore) {
                maxScore = wordScores[i];
                bestIdx = i;
            }
        }

        if (maxScore == 0) {
            return -1; // Neutral word
        }

        // Hysteresis: if the current language has positive score on this word and ties for best, stay
        if (currentLangIdx >= 0 && wordScores[currentLangIdx] == maxScore) {
            return currentLangIdx;
        }

        // Initial tie-break: if starting a sentence, prefer the sentence's overall winning language
        if (currentLangIdx < 0 && sentenceWinner >= 0 && wordScores[sentenceWinner] == maxScore) {
            return sentenceWinner;
        }

        return bestIdx;
    }

    private static boolean isDevanagariWordChar(int cp) {
        // Devanagari letters, vowels, marks, nukta, virama, avagraha
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
        return decideLanguage(scores, ownLanguage, fallback);
    }
}
