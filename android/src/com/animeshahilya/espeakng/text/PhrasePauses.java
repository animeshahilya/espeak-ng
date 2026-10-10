package com.animeshahilya.espeakng.text;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Phrase pauses (opt-in, like Eloquence's "phrase prediction"): a long
 * stretch of words with no punctuation gets a short pause before a word
 * that usually starts a new phrase ("and", "because", "which"; Hindi "और",
 * "लेकिन", "कि"). eSpeak otherwise only pauses at punctuation, so long
 * unpunctuated sentences run together.
 *
 * The pause is a comma, added after the symbol pass so it is never
 * announced as "comma".
 */
public final class PhrasePauses {
    private PhrasePauses() {}

    /** Words since the last pause before a phrase word may get one. */
    private static final int MIN_WORDS_BEFORE = 4;
    /** And at least this many words must follow it in the same stretch. */
    private static final int MIN_WORDS_AFTER = 3;

    private static final int KIND_NONE = 0;
    private static final int KIND_EN = 1;
    private static final int KIND_HI = 2;

    private static final Set<String> ENGLISH = new HashSet<>(Arrays.asList(
            "and", "but", "or", "because", "which", "who", "whose", "where", "when", "while",
            "although", "though", "unless", "until", "since", "so", "if", "whereas"
    ));
    private static final Set<String> HINDI = new HashSet<>(Arrays.asList(
            "और", "लेकिन", "परन्तु", "परंतु", "किन्तु", "किंतु", "मगर", "कि", "क्योंकि",
            "जो", "जब", "जहाँ", "जहां", "तो", "अगर", "यदि", "ताकि", "जबकि"
    ));

    private static final String PAUSE_CHARS = ",.;:!?।॥";
    private static final char NEWLINE = 10;

    public static boolean supports(String languageTag) {
        return phraseKind(languageTag) != KIND_NONE;
    }

    private static int phraseKind(String languageTag) {
        if (languageTag == null || languageTag.length() < 2) {
            return KIND_NONE;
        }
        String base = AsciiUtils.baseLanguage(languageTag);
        if ("en".equals(base)) {
            return KIND_EN;
        }
        if ("hi".equals(base)) {
            return KIND_HI;
        }
        return KIND_NONE;
    }

    private static boolean isPhraseWord(int kind, String word) {
        if (kind == KIND_EN) {
            return ENGLISH.contains(AsciiUtils.toAsciiLowerCase(word));
        } else if (kind == KIND_HI) {
            return HINDI.contains(word);
        }
        return false;
    }

    public static String process(String text, String languageTag) {
        final int kind = phraseKind(languageTag);
        if (kind == KIND_NONE || text == null || text.isEmpty()) {
            return text;
        }

        final int len = text.length();
        int[] wordBounds = new int[64];
        int wordCount = 0;
        int i = 0;
        while (i < len) {
            while (i < len && AsciiUtils.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= len) break;
            int start = i;
            while (i < len && !AsciiUtils.isWhitespace(text.charAt(i))) {
                i++;
            }
            int end = i;
            if (wordCount * 2 + 2 > wordBounds.length) {
                wordBounds = java.util.Arrays.copyOf(wordBounds, wordBounds.length * 2);
            }
            wordBounds[wordCount * 2] = start;
            wordBounds[wordCount * 2 + 1] = end;
            wordCount++;
        }

        if (wordCount < MIN_WORDS_BEFORE + MIN_WORDS_AFTER + 1) {
            return text;
        }

        StringBuilder out = null;
        int since = 0;       // words since the last pause
        int shift = 0;       // characters added so far
        for (int k = 0; k < wordCount; k++) {
            int wStart = wordBounds[k * 2];
            int wEnd = wordBounds[k * 2 + 1];
            String word = text.substring(wStart, wEnd);
            if (k > 0) {
                int prevEnd = wordBounds[(k - 1) * 2 + 1];
                int nl = text.indexOf(NEWLINE, prevEnd);
                if (nl >= 0 && nl < wStart) {
                    since = 0;   // a line break is a pause already
                }
            }
            if (since >= MIN_WORDS_BEFORE && isPhraseWord(kind, word)
                    && wordsUntilPause(text, wordBounds, wordCount, k) >= MIN_WORDS_AFTER + 1) {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                // after the previous word, not before the space: "x, and"
                int at = wordBounds[(k - 1) * 2 + 1] + shift;
                out.insert(at, ',');
                shift++;
                since = 0;
            }
            since++;
            if (PAUSE_CHARS.indexOf(text.charAt(wEnd - 1)) >= 0) {
                since = 0;
            }
        }
        return out == null ? text : out.toString();
    }

    /** Words from k up to and including the one that ends in punctuation. */
    private static int wordsUntilPause(String text, int[] wordBounds, int wordCount, int k) {
        int n = 0;
        for (int j = k; j < wordCount; j++) {
            n++;
            if (PAUSE_CHARS.indexOf(text.charAt(wordBounds[j * 2 + 1] - 1)) >= 0) {
                break;
            }
            if (n >= MIN_WORDS_AFTER + 1) {
                break;
            }
        }
        return n;
    }
}

