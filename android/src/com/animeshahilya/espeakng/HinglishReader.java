package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in (beta): reads Hindi typed in Latin letters ("aap kaise ho") with
 * Hindi pronunciation. Recognised words are rewritten in Devanagari, and
 * eSpeak's own script detection then speaks them with the Hindi voice.
 *
 * The word list (assets/hinglish/hi.tsv, built by tools/build_hinglish.py
 * from Google's Dakshina romanization lexicon, CC BY-SA 4.0) maps each
 * Latin spelling to its most attested Devanagari word, flagged when the
 * spelling is also an English word ("main", "to", "ho").
 *
 * Decided per sentence: it is read as Hindi when it has at least two
 * Hindi-only words ("kaise"), or one plus another word, and no more words
 * outside the list (English, names) than Hindi-only ones. A lone word is
 * never enough, so an English label like "Emoji" stays English. In a Hindi
 * sentence every listed word is converted; other sentences are untouched.
 * Scored on Dakshina's 5,000 held-out test sentences: 78.5% of Hindi words
 * sound right, 4.9% wrong; 0.32% of 145,000 words of English prose changed.
 */
public final class HinglishReader {
    private static final String TAG = "HinglishReader";
    private static final String FILE = "hinglish/hi.tsv";

    private static volatile AssetManager sAssets;
    private static volatile Map<String, String> sHindi;
    private static volatile Map<String, String> sShared;

    private HinglishReader() {
    }

    /** Called once from {@link EspeakApp#onCreate()}. */
    public static void init(Context context) {
        sAssets = context.getApplicationContext().getAssets();
    }

    /** Loads the word list on first use, so installs with the setting off never pay for it. */
    private static void load() {
        if (sHindi != null) {
            return;
        }
        synchronized (HinglishReader.class) {
            if (sHindi != null) {
                return;
            }
            final Map<String, String> hindi = new HashMap<String, String>(32768);
            final Map<String, String> shared = new HashMap<String, String>(4096);
            if (sAssets != null) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        sAssets.open(FILE), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        final int a = line.indexOf('\t');
                        final int b = line.indexOf('\t', a + 1);
                        if (a <= 0 || b <= a) {
                            continue;
                        }
                        final String roman = line.substring(0, a);
                        final String deva = line.substring(a + 1, b);
                        (line.charAt(b + 1) == '1' ? shared : hindi).put(roman, deva);
                    }
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Hinglish word list unavailable", e);
                }
            }
            sShared = shared;
            sHindi = hindi;
        }
    }

    public static String process(String text) {
        if (text == null || text.isEmpty() || !hasAsciiLetter(text)) {
            return text;
        }
        load();
        final Map<String, String> hindi = sHindi;
        final Map<String, String> shared = sShared;
        if (hindi.isEmpty()) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length() + 16);
        int start = 0;
        final int len = text.length();
        while (start < len) {
            int end = start;
            while (end < len && !isSentenceEnd(text.charAt(end))) {
                end++;
            }
            if (end < len) {
                end++; // keep the terminator with its sentence
            }
            convertSentence(text, start, end, hindi, shared, out);
            start = end;
        }
        return out.toString();
    }

    private static void convertSentence(String text, int from, int to, Map<String, String> hindi,
                                        Map<String, String> shared, StringBuilder out) {
        // First pass: how Hindi is this sentence? Hindi-only words vote for,
        // words not in the list at all (English, names, brands) vote against.
        int hindiWords = 0;
        int otherWords = 0;
        int words = 0;
        for (int i = from; i < to; ) {
            if (!isLatinLetter(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < to && isLatinLetter(text.charAt(j))) j++;
            final String word = text.substring(i, j);
            if (!isAcronym(word)) {
                words++;
                final String key = word.toLowerCase(java.util.Locale.ROOT);
                if (hindi.containsKey(key)) {
                    hindiWords++;
                } else if (!shared.containsKey(key)) {
                    otherWords++;
                }
            }
            i = j;
        }
        final boolean mostlyHindi = (hindiWords >= 2 || (hindiWords == 1 && words >= 2))
                && hindiWords >= otherWords;
        if (!mostlyHindi) {
            out.append(text, from, to);
            return;
        }

        for (int i = from; i < to; ) {
            final char c = text.charAt(i);
            if (!isLatinLetter(c)) {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < to && isLatinLetter(text.charAt(j))) j++;
            final String word = text.substring(i, j);
            String deva = null;
            if (!isAcronym(word) && !touchesDigitOrSymbol(text, i, j)) {
                final String key = word.toLowerCase(java.util.Locale.ROOT);
                deva = hindi.get(key);
                if (deva == null) {
                    deva = shared.get(key);
                }
            }
            out.append(deva != null ? deva : word);
            i = j;
        }
    }

    /** "OTP", "UPI": spelled-out acronyms stay English. */
    private static boolean isAcronym(String word) {
        if (word.length() < 2) {
            return false;
        }
        for (int k = 0; k < word.length(); k++) {
            if (!Character.isUpperCase(word.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    /** Part of "abc123", "user_name" or "a@b.com": not a word to translate. */
    private static boolean touchesDigitOrSymbol(String text, int start, int end) {
        final char before = start > 0 ? text.charAt(start - 1) : ' ';
        final char after = end < text.length() ? text.charAt(end) : ' ';
        return isGlue(before) || isGlue(after);
    }

    private static boolean isGlue(char c) {
        return (c >= '0' && c <= '9') || c == '_' || c == '@' || c == '/' || c == '\\'
                || c == '#' || c == '=' || c == '&';
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n' || c == '।';
    }

    private static boolean hasAsciiLetter(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            if (isLatinLetter(text.charAt(i))) return true;
        }
        return false;
    }
}
