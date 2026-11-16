package com.animeshahilya.espeakng.piper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Phonemes for Lithuanian voices ({@code "phoneme_type": "lithuanian"}): a
 * port of piper1-gpl's phonemize_lithuanian.py (GPL-3.0, like this app).
 * eSpeak NG's IPA is kept, word by word, and the stress moves to the
 * syllable a stress dictionary names, marked with one of Lithuanian's three
 * pitch accents: ˈ (tvirtapradė), ˌ (tvirtagalė), ˋ (trumpinė, id 166 in
 * these voices' maps). eSpeak places Lithuanian stress wrongly in about half
 * of the words and has no pitch accents; the voices were trained on this.
 *
 * Data (android/data/lithuanian/, from piper1-gpl): lt_kirciai.tsv, the
 * stress dictionary built from the LIEPA corpus annotation (Vilnius
 * University) and svogunas/g2p-lt-lexicon (Arunas Smaliukas), both CC BY 4.0;
 * lt_raides.tsv, letter names eSpeak gets wrong; lt_kreipiniai.tsv, nouns used
 * as direct address. LithuanianPhonemesTest checks the port against piper's
 * own output (tools/lithuanian_fixture.py). Android-free.
 */
final class LithuanianPhonemes {
    static final char ACUTE = 'ˈ';
    static final char CIRCUMFLEX = 'ˌ';
    static final char GRAVE = 'ˋ';
    private static final String STRESS_MARKS = "ˈˌˋ";
    private static final String IPA_VOWELS = "aeiouɑɐɔɛɪʊæøɘəɜ";
    private static final char LENGTH = 'ː';
    private static final String CONSONANT_MODIFIERS = "ʲʷʰ̩";
    private static final Pattern WORD_CLEAN =
            Pattern.compile("[^a-zA-ZąčęėįšųūžĄČĘĖĮŠŲŪŽ0-9]");
    private static final String KEEP_PUNCT = ".,!?:;";
    private static final String PLAIN = "aceeisuuzACEEISUUZ";
    private static final String MARKED = "ąčęėįšųūžĄČĘĖĮŠŲŪŽ";
    private static final String VOCATIVE_OPENERS = ",:-–—";
    private static final String VOCATIVE_CLOSERS = ",.!?…";

    /** word -> {vowel group index, mark} */
    private final Map<String, int[]> dictionary;
    /** letter -> IPA, and the next-word prefixes that make it an abbreviation */
    private final Map<String, String> letters;
    private final Map<String, String[]> letterPrefixes;
    private final Set<String> vocatives;

    LithuanianPhonemes(Map<String, int[]> dictionary, Map<String, String> letters,
                       Map<String, String[]> letterPrefixes, Set<String> vocatives) {
        this.dictionary = dictionary;
        this.letters = letters;
        this.letterPrefixes = letterPrefixes;
        this.vocatives = vocatives;
    }

    private static volatile LithuanianPhonemes sShared;
    /** eSpeak's IPA per word, for the life of the process (piper caches it the same way). */
    private static final Map<String, String> sWordCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 250_000;

    /** The bundled data (android/data/lithuanian), loaded once; null if unreadable. */
    static LithuanianPhonemes shared() {
        LithuanianPhonemes lt = sShared;
        if (lt == null) {
            synchronized (LithuanianPhonemes.class) {
                lt = sShared;
                if (lt == null) {
                    try (BufferedReader k = com.animeshahilya.espeakng.text.BundledData.reader("lithuanian/lt_kirciai.tsv");
                         BufferedReader r = com.animeshahilya.espeakng.text.BundledData.reader("lithuanian/lt_raides.tsv");
                         BufferedReader v = com.animeshahilya.espeakng.text.BundledData.reader("lithuanian/lt_kreipiniai.tsv")) {
                        lt = load(k, r, v);
                        sShared = lt;
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("PiperVoices", "Lithuanian stress data unavailable", e);
                        return null;
                    }
                }
            }
        }
        return lt;
    }

    /**
     * eSpeak's clauses re-phonemized from their own text, word by word: the
     * spans (word timing, chunking) stay eSpeak's, the IPA becomes piper's
     * accented one. {@code espeakRecords} phonemizes one word the usual way.
     */
    List<PiperPhonemes.Clause> clauses(List<PiperPhonemes.Clause> espeak, String text,
                                       UnaryOperator<String> espeakRecords) {
        final UnaryOperator<String> wordIpa = w -> {
            String ipa = sWordCache.get(w);
            if (ipa == null) {
                final StringBuilder b = new StringBuilder();
                for (PiperPhonemes.Clause c : PiperPhonemes.parseRecords(espeakRecords.apply(w))) {
                    b.append(c.ipa);
                }
                // A lone word gets eSpeak's clause-end punctuation: not the word's own.
                ipa = b.toString().replaceAll("[\\s.,!?:;]+$", "").trim();
                if (sWordCache.size() >= CACHE_LIMIT) {
                    sWordCache.clear();
                }
                sWordCache.put(w, ipa);
            }
            return ipa;
        };
        final List<PiperPhonemes.Clause> out = new ArrayList<>(espeak.size());
        final int length = text.codePointCount(0, text.length());
        for (PiperPhonemes.Clause c : espeak) {
            final int from = text.offsetByCodePoints(0, Math.min(c.start, length));
            final int to = text.offsetByCodePoints(0, Math.min(Math.max(c.end, c.start), length));
            out.add(new PiperPhonemes.Clause(c.endsSentence, c.start, c.end, sentence(text.substring(from, to), wordIpa)));
        }
        return out;
    }

    /** The data a voice's files name, read from its three TSVs. */
    static LithuanianPhonemes load(BufferedReader kirciai, BufferedReader raides, BufferedReader kreipiniai)
            throws IOException {
        final Map<String, int[]> dictionary = new HashMap<>(250_000);
        for (String line; (line = kirciai.readLine()) != null; ) {
            if (line.startsWith("#")) {
                continue;
            }
            final String[] p = line.split("\t", -1);
            if ((p.length != 3 && p.length != 4) || p[2].length() != 1 || STRESS_MARKS.indexOf(p[2].charAt(0)) < 0) {
                continue;
            }
            try {
                dictionary.put(p[0], new int[] {Integer.parseInt(p.length == 4 ? p[3] : p[1]), p[2].charAt(0)});
            } catch (NumberFormatException ignored) {
                // a line that does not fit is skipped, as piper skips it
            }
        }
        final Map<String, String> letters = new HashMap<>();
        final Map<String, String[]> prefixes = new HashMap<>();
        for (String line; (line = raides.readLine()) != null; ) {
            if (line.startsWith("#")) {
                continue;
            }
            final String[] p = line.split("\t", -1);
            if (p.length < 2 || p[1].isEmpty()) {
                continue;
            }
            final String letter = p[0].toLowerCase(Locale.ROOT);
            letters.put(letter, p[1]);
            final List<String> pre = new ArrayList<>();
            if (p.length > 2) {
                for (String s : p[2].split(",")) {
                    if (!s.isEmpty()) {
                        pre.add(s);
                    }
                }
            }
            prefixes.put(letter, pre.toArray(new String[0]));
        }
        final Set<String> vocatives = new HashSet<>();
        for (String line; (line = kreipiniai.readLine()) != null; ) {
            if (line.startsWith("#")) {
                continue;
            }
            final String word = line.split("\t", -1)[0].trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                vocatives.add(stripDiacritics(word));
            }
        }
        return new LithuanianPhonemes(dictionary, letters, prefixes, vocatives);
    }

    /**
     * A clause or sentence of text -> IPA, as piper's phonemize_sentence:
     * each word through {@code espeakWord} (eSpeak's IPA for that word alone)
     * then accented; the punctuation a word carries is kept.
     */
    String sentence(String text, UnaryOperator<String> espeakWord) {
        final List<String> pieces = new ArrayList<>();
        final String[] tokens = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
        final String[] words = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            words[i] = WORD_CLEAN.matcher(tokens[i]).replaceAll("");
        }
        for (int i = 0; i < tokens.length; i++) {
            final StringBuilder punct = new StringBuilder();
            for (char c : tokens[i].toCharArray()) {
                if (KEEP_PUNCT.indexOf(c) >= 0) {
                    punct.append(c);
                }
            }
            if (words[i].isEmpty()) {
                if (punct.length() > 0 && !pieces.isEmpty()) {
                    pieces.set(pieces.size() - 1, pieces.get(pieces.size() - 1) + punct);
                }
                continue;
            }
            final String override = letterIpa(words[i], i + 1 < words.length ? words[i + 1] : "");
            String ipa = override != null ? override : word(words[i], espeakWord);
            if (override == null && isVocative(words, tokens, i)) {
                ipa = vocativeAccent(ipa);
            }
            pieces.add(ipa + punct);
        }
        return String.join(" ", pieces);
    }

    /** One word (letters only) -> IPA with its pitch accent. */
    String word(String word, UnaryOperator<String> espeakWord) {
        // ʂ never occurs in the training data; ɭ (eSpeak's soft l) is kept on purpose.
        String ipa = espeakWord.apply(word).trim().replace('ʂ', 's');
        final int[] entry = dictionary.get(word.toLowerCase(Locale.ROOT));
        if (entry == null) {
            // eSpeak's own stress; a monosyllable it left unmarked gets one, as its CLI does
            if (!hasStress(ipa)) {
                final List<Integer> groups = vowelGroups(ipa);
                if (!groups.isEmpty()) {
                    final int p = groups.get(0);
                    ipa = ipa.substring(0, p) + ACUTE + ipa.substring(p);
                }
            }
            return ipa;
        }
        return placeAccent(ipa, entry[0], (char) entry[1]);
    }

    String letterIpa(String word, String nextWord) {
        final String key = word.toLowerCase(Locale.ROOT);
        final String ipa = letters.get(key);
        if (ipa == null) {
            return null;
        }
        final String next = nextWord.toLowerCase(Locale.ROOT);
        for (String prefix : letterPrefixes.get(key)) {
            if (next.startsWith(prefix)) {
                return null;
            }
        }
        return ipa;
    }

    /** A listed noun fenced off by commas or the sentence edge: "Labas, mama." */
    private boolean isVocative(String[] words, String[] tokens, int i) {
        if (vocatives.isEmpty() || !vocatives.contains(stripDiacritics(words[i].toLowerCase(Locale.ROOT)))) {
            return false;
        }
        final boolean opened = i == 0 || endsWithAny(tokens[i - 1], VOCATIVE_OPENERS);
        final boolean closed = i == tokens.length - 1 || endsWithAny(tokens[i], VOCATIVE_CLOSERS);
        return opened && closed;
    }

    /** First syllable accented, a single short vowel doubled in length: mama -> ˈmaːːma. */
    static String vocativeAccent(String ipa) {
        final String accented = placeAccent(ipa, 0, ACUTE);
        final List<Integer> groups = vowelGroups(accented);
        if (groups.isEmpty()) {
            return accented;
        }
        final int start = groups.get(0);
        int end = start;
        while (end + 1 < accented.length()
                && (IPA_VOWELS.indexOf(accented.charAt(end + 1)) >= 0 || accented.charAt(end + 1) == LENGTH)) {
            end++;
        }
        if (end != start) {
            return accented; // diphthong or already long
        }
        return accented.substring(0, end + 1) + LENGTH + LENGTH + accented.substring(end + 1);
    }

    /** Start of every vowel group (adjacent vowels and ː form one). */
    static List<Integer> vowelGroups(String ipa) {
        final List<Integer> groups = new ArrayList<>();
        for (int i = 0; i < ipa.length(); i++) {
            if (IPA_VOWELS.indexOf(ipa.charAt(i)) >= 0) {
                final int start = i;
                while (i + 1 < ipa.length()
                        && (IPA_VOWELS.indexOf(ipa.charAt(i + 1)) >= 0 || ipa.charAt(i + 1) == LENGTH)) {
                    i++;
                }
                groups.add(start);
            }
        }
        return groups;
    }

    /**
     * eSpeak's stress removed and {@code mark} put before the syllable of
     * vowel group {@code group}: V-CV, VC-CV, a word-initial cluster stays
     * in the first syllable.
     */
    static String placeAccent(String ipa, int group, char mark) {
        final StringBuilder b = new StringBuilder(ipa.length());
        for (int i = 0; i < ipa.length(); i++) {
            if (STRESS_MARKS.indexOf(ipa.charAt(i)) < 0) {
                b.append(ipa.charAt(i));
            }
        }
        final String clean = b.toString();
        final List<Integer> groups = vowelGroups(clean);
        if (group < 0 || group >= groups.size()) {
            return ipa;
        }
        int i = groups.get(group) - 1;
        while (i >= 0 && CONSONANT_MODIFIERS.indexOf(clean.charAt(i)) >= 0) {
            i--;
        }
        if (i >= 0 && IPA_VOWELS.indexOf(clean.charAt(i)) < 0 && clean.charAt(i) != ' '
                && clean.charAt(i) != LENGTH) {
            i--; // one consonant
        }
        int boundary = i + 1;
        int j = i;
        while (j >= 0 && IPA_VOWELS.indexOf(clean.charAt(j)) < 0 && clean.charAt(j) != ' ') {
            j--;
        }
        if (j < 0 || clean.charAt(j) == ' ') {
            boundary = j + 1; // word start
        }
        return clean.substring(0, boundary) + mark + clean.substring(boundary);
    }

    private static boolean hasStress(String ipa) {
        for (int i = 0; i < ipa.length(); i++) {
            if (STRESS_MARKS.indexOf(ipa.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String token, String chars) {
        final String t = token.replaceAll("\\s+$", "");
        return !t.isEmpty() && chars.indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static String stripDiacritics(String s) {
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final int k = MARKED.indexOf(s.charAt(i));
            b.append(k >= 0 ? PLAIN.charAt(k) : s.charAt(i));
        }
        return b.toString();
    }
}
