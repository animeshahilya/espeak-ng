package com.animeshahilya.espeakng;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ENGLISH = Pattern.compile(
            "(?i)^(and|but|or|because|which|who|whose|where|when|while|although|though"
                    + "|unless|until|since|so|if|whereas)$");
    private static final Pattern HINDI = Pattern.compile(
            "^(और|लेकिन|परन्तु|परंतु|किन्तु|किंतु|मगर|कि|क्योंकि|जो|जब|जहाँ|जहां|तो|अगर|यदि|ताकि|जबकि)$");
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final String PAUSE_CHARS = ",.;:!?।॥";
    private static final char NEWLINE = 10;

    public static boolean supports(String languageTag) {
        return phraseWords(languageTag) != null;
    }

    private static Pattern phraseWords(String languageTag) {
        if (languageTag == null) {
            return null;
        }
        String lang = languageTag.toLowerCase(Locale.ROOT);
        if (lang.startsWith("en")) {
            return ENGLISH;
        }
        if (lang.startsWith("hi")) {
            return HINDI;
        }
        return null;
    }

    public static String process(String text, String languageTag) {
        Pattern phrase = phraseWords(languageTag);
        if (phrase == null || text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = WORD.matcher(text);
        List<int[]> words = new ArrayList<>();
        while (m.find()) {
            words.add(new int[] {m.start(), m.end()});
        }
        StringBuilder out = null;
        int since = 0;       // words since the last pause
        int shift = 0;       // characters added so far
        for (int k = 0; k < words.size(); k++) {
            int[] w = words.get(k);
            String word = text.substring(w[0], w[1]);
            if (k > 0 && text.indexOf(NEWLINE, words.get(k - 1)[1]) >= 0
                    && text.indexOf(NEWLINE, words.get(k - 1)[1]) < w[0]) {
                since = 0;   // a line break is a pause already
            }
            if (since >= MIN_WORDS_BEFORE && phrase.matcher(word).matches()
                    && wordsUntilPause(text, words, k) >= MIN_WORDS_AFTER + 1) {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                // after the previous word, not before the space: "x, and"
                int at = words.get(k - 1)[1] + shift;
                out.insert(at, ',');
                shift++;
                since = 0;
            }
            since++;
            if (PAUSE_CHARS.indexOf(text.charAt(w[1] - 1)) >= 0) {
                since = 0;
            }
        }
        return out == null ? text : out.toString();
    }

    /** Words from k up to and including the one that ends in punctuation. */
    private static int wordsUntilPause(String text, List<int[]> words, int k) {
        int n = 0;
        for (int j = k; j < words.size(); j++) {
            n++;
            if (PAUSE_CHARS.indexOf(text.charAt(words.get(j)[1] - 1)) >= 0) {
                break;
            }
        }
        return n;
    }
}
