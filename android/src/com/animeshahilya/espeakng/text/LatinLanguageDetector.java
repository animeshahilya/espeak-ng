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
 * Stopword-based language detector for Latin-script text: Spanish, French,
 * German, Italian, Portuguese, Dutch, Polish, Turkish, Indonesian and Kurdish
 * (Kurmanji) phrases
 * inside other text (since 2026-10-29 also sentence by sentence inside
 * English, and without natural voices).
 *
 * <p>Opt-in ({@link #setEnabled}): eSpeak NG and NVDA read all Latin text as
 * the voice's Latin language. Words that are also everyday English or
 * Hinglish ("do", "as", "plus", "par", "lo", "todo") are left out, English
 * function words compete, and the winner needs a real share of the run, so
 * "Do as I say" or "El Paso and La Jolla" stay English.
 */
public final class LatinLanguageDetector {
    private LatinLanguageDetector() {
    }

    private static volatile boolean sEnabled = false;

    /** Whether {@link #detect} looks at all (the user's setting). */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    private static final String ENGLISH = "eng";
    private static final Map<String, Set<String>> STOPWORDS = new HashMap<>();

    static {
        addWords(ENGLISH, new String[] {
                "the", "and", "of", "to", "is", "are", "was", "were", "in", "on", "at", "for", "with",
                "this", "that", "it", "you", "i", "my", "your", "not", "have", "has", "be", "from",
                "or", "by", "we", "they", "he", "she", "a", "an", "what", "how", "will", "can"
        });

        addWords("deu", new String[] {
                "der", "die", "das", "des", "ein", "eine", "einer", "einem", "einen", "eines",
                "und", "oder", "aber", "nicht", "ich", "wir", "sie", "ist", "sind", "nach", "fuer",
                "für", "auch", "dass", "wenn", "doch", "haben", "danke", "bitte", "wie", "geht",
                "dir", "sehr", "guten", "morgen", "mich", "noch"
        });

        addWords("fra", new String[] {
                "le", "la", "les", "du", "un", "une", "avec", "pour", "dans", "sous",
                "nous", "vous", "ils", "elles", "mais", "cette", "sont", "merci", "tres",
                "très", "chez", "tout", "tous", "faire", "bien", "votre", "notre", "beaucoup", "aussi"
        });

        addWords("spa", new String[] {
                "el", "la", "los", "las", "un", "una", "unos", "unas", "del", "por", "para",
                "pero", "este", "esta", "estos", "estas", "como", "bien", "muy", "gracias", "hacer",
                "todos", "estoy", "está", "están", "estan", "hola", "amigo", "amiga", "es", "hasta"
        });

        addWords("ita", new String[] {
                "il", "gli", "un", "uno", "una", "del", "della", "dei", "delle", "per", "tuo",
                "senza", "questo", "questa", "anche", "molto", "sono", "grazie", "tutto", "tutti",
                "perche", "perché", "ciao", "bene", "quando", "cosa", "mille"
        });

        addWords("por", new String[] {
                "uma", "dos", "das", "com", "para", "por",
                "este", "esta", "estes", "estas", "muito", "mais", "obrigado", "obrigada", "voce", "você",
                "tudo", "todos", "estou", "está", "estao", "estão", "fazer", "bom", "nao", "não"
        });

        // Added 2026-10-29. Left out: words English, Hinglish or another list
        // also writes ("met", "heel", "hoe", "is", "op"... "ben" and "dan" are
        // Turkish and Indonesian here, "var" is code, "anda" Spanish).
        addWords("nld", new String[] {
                "het", "een", "van", "niet", "ik", "wij", "jullie", "zijn", "voor", "maar", "ook",
                "nog", "wel", "dank", "bedankt", "goed", "graag", "alstublieft", "deze", "waar", "wat",
                "waarom", "bent", "hebben", "heeft", "naar", "uit", "dat", "zo", "goedemorgen", "fijn"
        });

        addWords("pol", new String[] {
                "nie", "jest", "się", "że", "jak", "ale", "czy", "dla", "przez", "jestem", "dziękuję",
                "proszę", "bardzo", "też", "już", "mam", "może", "który", "która", "dzień", "dobry",
                "cześć", "jesteś", "gdzie", "kiedy", "dlaczego", "teraz", "tylko", "jeszcze", "wiem"
        });

        addWords("tur", new String[] {
                "ve", "bir", "bu", "için", "ile", "çok", "teşekkür", "ederim", "teşekkürler", "merhaba",
                "nasıl", "nasılsın", "değil", "ben", "sen", "yok", "ama", "gibi", "daha", "evet",
                "hayır", "şey", "olarak", "şimdi", "neden", "nerede", "güzel", "iyi", "lütfen", "misin"
        });

        addWords("ind", new String[] {
                "dan", "yang", "ini", "itu", "dengan", "untuk", "tidak", "saya", "kamu", "terima",
                "kasih", "apa", "akan", "sudah", "bisa", "juga", "dari", "selamat", "pagi", "bagaimana",
                "kabar", "baik", "sekarang", "kenapa", "tolong", "sangat", "tahu", "mana"
        });

        // Kurmanji, added 2026-10-31 (a Kurdish tester). Left out: "di" (Italian,
        // Indonesian), "em" (Portuguese), "tu"/"ne"/"na"/"te" (Hinglish), "an",
        // "me", "min", "tune", "were", "here" (English), "wir" (German), "ser" (Spanish).
        addWords("kur", new String[] {
                "û", "ê", "jî", "ji", "bi", "li", "ku", "ev", "ew", "ez", "hûn", "çi", "çima", "çawa",
                "kî", "kengê", "erê", "belê", "heye", "hene", "nîne", "bû", "dibe", "nabe", "dike",
                "dikim", "dikin", "dikarim", "dixwazim", "wê", "wî", "vê", "vî", "gelek", "pir", "spas",
                "silav", "rojbaş", "baş", "xweş", "xwe", "hev", "niha", "lê", "heta", "piştî", "berî",
                "tiştek", "tişt", "kurdî", "zimanê", "gelo", "bila", "bê", "dema", "çend"
        });
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** Whether {@code language} is one this detector finds (never English). */
    public static boolean detects(String language) {
        return language != null && !ENGLISH.equals(language) && STOPWORDS.containsKey(language);
    }

    private static void addWords(String lang, String[] words) {
        Set<String> set = new HashSet<>(words.length * 2);
        Collections.addAll(set, words);
        STOPWORDS.put(lang, set);
    }

    /**
     * The language of the Latin-script text {@code run} when it is clearly
     * Spanish, French, German, Italian or Portuguese, else null (also while
     * detection is off).
     *
     * @param run the text of the Latin run
     * @param speakingLanguage the language the run is read in now; never returned
     * @return the ISO 639-2 code (e.g. "spa", "fra", "deu", "ita", "por"), or null
     */
    public static String detect(String run, String speakingLanguage) {
        if (!sEnabled || run == null || run.length() < 8) {
            return null;
        }

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
                    if (entry.getValue().contains(word)) {
                        scores.merge(entry.getKey(), 1, Integer::sum);
                    }
                }
            }
        }

        final int english = scores.getOrDefault(ENGLISH, 0);
        String bestLang = null;
        int bestScore = 0;
        int secondScore = 0;

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            final String lang = entry.getKey();
            if (lang.equals(ENGLISH) || lang.equals(speakingLanguage)) {
                continue;
            }
            final int score = entry.getValue();
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestLang = lang;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        // A strict winner with 2+ hits, a quarter of the words, and well
        // ahead of English (names like "El Paso and La Jolla" stay English).
        if (bestScore >= 2 && bestScore > secondScore
                && bestScore * 4 >= wordCount && bestScore > 2 * english) {
            return bestLang;
        }
        return null;
    }
}
