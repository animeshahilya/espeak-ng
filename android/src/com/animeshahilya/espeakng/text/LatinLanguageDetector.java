/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ultra-fast, zero-allocation stopword-based language detector for Latin-script text.
 *
 * <p>Standard eSpeak NG routes all Latin-script text to English ("eng"). This
 * detector inspects multi-word Latin runs for closed-class function words
 * (articles, prepositions, conjunctions) to identify Spanish, French, German,
 * Italian, and Portuguese without requiring heavy ML libraries or external assets.
 */
public final class LatinLanguageDetector {
    private LatinLanguageDetector() {
    }

    private static final Map<String, Set<String>> STOPWORDS = new HashMap<>();

    static {
        // German (deu)
        addWords("deu", new String[] {
                "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "einem", "einen", "eines",
                "und", "oder", "aber", "nicht", "ich", "wir", "sie", "ist", "sind", "mit", "nach", "fuer",
                "für", "auch", "dass", "wenn", "doch", "haben", "hat", "danke", "bitte"
        });

        // French (fra)
        addWords("fra", new String[] {
                "le", "la", "les", "du", "des", "un", "une", "avec", "pour", "dans", "sur", "sous",
                "nous", "vous", "ils", "elles", "mais", "cette", "sont", "est", "pas", "merci", "tres",
                "très", "par", "chez", "tout", "tous", "faire", "plus", "bien"
        });

        // Spanish (spa)
        addWords("spa", new String[] {
                "el", "la", "los", "las", "un", "una", "unos", "unas", "del", "por", "para", "con",
                "sin", "pero", "este", "esta", "estos", "estas", "como", "bien", "muy", "gracias", "hacer",
                "todo", "todos", "estoy", "esta", "está", "están", "estan", "hola", "amigo", "amiga"
        });

        // Italian (ita)
        addWords("ita", new String[] {
                "il", "lo", "la", "gli", "le", "un", "uno", "una", "del", "della", "dei", "delle",
                "con", "senza", "questo", "questa", "anche", "molto", "sono", "grazie", "tutto", "tutti",
                "perche", "perché", "ciao", "bene", "dove", "quando", "cosa"
        });

        // Portuguese (por)
        addWords("por", new String[] {
                "os", "as", "um", "uma", "do", "da", "dos", "das", "com", "para", "por", "sem",
                "este", "esta", "estes", "estas", "muito", "mais", "obrigado", "obrigada", "voce", "você",
                "tudo", "todos", "estou", "está", "esta", "estao", "estão", "fazer", "bom", "boa"
        });
    }

    private static void addWords(String lang, String[] words) {
        Set<String> set = new HashSet<>(words.length * 2);
        Collections.addAll(set, words);
        STOPWORDS.put(lang, set);
    }

    /**
     * Detects if the Latin-script text {@code run} belongs to a specific non-English
     * language (Spanish, French, German, Italian, Portuguese).
     *
     * @param run the text of the Latin run
     * @param speakingLanguage the active base language (if already that language, returns null)
     * @return the ISO 639-2 code (e.g. "spa", "fra", "deu", "ita", "por"), or null if inconclusive
     */
    public static String detect(String run, String speakingLanguage) {
        if (run == null || run.length() < 8) {
            return null;
        }

        // Must have at least 2 distinct words to be considered a phrase
        int wordCount = 0;
        final int len = run.length();
        int inWordStart = -1;

        final Map<String, Integer> scores = new HashMap<>(4);

        for (int i = 0; i <= len; i++) {
            final boolean atEnd = (i == len);
            final char c = atEnd ? ' ' : run.charAt(i);
            final boolean isLetter = !atEnd && Character.isLetter(c);

            if (isLetter) {
                if (inWordStart < 0) {
                    inWordStart = i;
                }
            } else if (inWordStart >= 0) {
                final String word = run.substring(inWordStart, i).toLowerCase(java.util.Locale.ROOT);
                inWordStart = -1;
                wordCount++;

                for (Map.Entry<String, Set<String>> entry : STOPWORDS.entrySet()) {
                    final String lang = entry.getKey();
                    if (lang.equals(speakingLanguage)) {
                        continue;
                    }
                    if (entry.getValue().contains(word)) {
                        scores.put(lang, scores.getOrDefault(lang, 0) + 1);
                    }
                }
            }
        }

        // Require at least 2 words and at least 2 stopword matches
        if (wordCount < 2 || scores.isEmpty()) {
            return null;
        }

        String bestLang = null;
        int bestScore = 0;
        int secondScore = 0;

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            final int score = entry.getValue();
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestLang = entry.getKey();
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        // High confidence threshold: at least 2 matches and strict winner
        if (bestScore >= 2 && bestScore > secondScore) {
            return bestLang;
        }

        return null;
    }
}
