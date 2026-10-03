/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the native phonemizer's clause records into model input: chunks of
 * phonemes to synthesize one at a time, each mapped to Piper's phoneme ids.
 *
 * <p>Pure Java (no Android, no ONNX), so all of it is unit-tested off-device;
 * the id encoding follows piper's own {@code phonemes_to_ids()} exactly: BOS,
 * PAD, then every phoneme followed by PAD, then EOS; unknown phonemes are
 * dropped.
 */
final class PiperPhonemes {
    private PiperPhonemes() {
    }

    static final char RECORD_SEP = '\u001e';
    static final char FIELD_SEP = '\u001f';

    /**
     * Phoneme budget for the first chunk of an utterance. Piper renders a
     * whole chunk before any of it can play, so a screen reader's
     * time-to-first-sound is the time to synthesize this chunk: a long first
     * sentence is split at its first comma/semicolon past this length.
     */
    static final int FIRST_CHUNK_PHONEMES = 60;
    /** Later chunks play while the next one renders; bigger ones sound smoother. */
    static final int CHUNK_PHONEMES = 220;

    /** One eSpeak clause, as piperPhonemizer.c reports it. */
    static final class Clause {
        final boolean endsSentence;
        /** Code point span of the clause in the text that was phonemized. */
        final int start;
        final int end;
        final String ipa;

        Clause(boolean endsSentence, int start, int end, String ipa) {
            this.endsSentence = endsSentence;
            this.start = start;
            this.end = end;
            this.ipa = ipa;
        }
    }

    /** What gets synthesized in one model run. */
    static final class Chunk {
        /** Code point span in the phonemized text, for word-boundary reports. */
        final int start;
        final int end;
        final String ipa;
        /** True when the chunk ends a sentence (gets the sentence pause). */
        final boolean endsSentence;

        Chunk(int start, int end, String ipa, boolean endsSentence) {
            this.start = start;
            this.end = end;
            this.ipa = ipa;
            this.endsSentence = endsSentence;
        }
    }

    static List<Clause> parseRecords(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Clause> clauses = new ArrayList<>();
        int pos = 0;
        while (pos < raw.length()) {
            int recordEnd = raw.indexOf(RECORD_SEP, pos);
            if (recordEnd < 0) {
                recordEnd = raw.length();
            }
            final Clause clause = parseRecord(raw, pos, recordEnd);
            if (clause != null) {
                clauses.add(clause);
            }
            pos = recordEnd + 1;
        }
        return clauses;
    }

    private static Clause parseRecord(String raw, int from, int to) {
        final int f1 = raw.indexOf(FIELD_SEP, from);
        if (f1 < 0 || f1 >= to) {
            return null;
        }
        final int f2 = raw.indexOf(FIELD_SEP, f1 + 1);
        if (f2 < 0 || f2 >= to) {
            return null;
        }
        final int f3 = raw.indexOf(FIELD_SEP, f2 + 1);
        if (f3 < 0 || f3 >= to) {
            return null;
        }
        try {
            final boolean sentence = raw.charAt(from) == 'S';
            final int start = Integer.parseInt(raw.substring(f1 + 1, f2));
            final int end = Integer.parseInt(raw.substring(f2 + 1, f3));
            return new Clause(sentence, start, Math.max(start, end), raw.substring(f3 + 1, to));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Pulls each clause boundary back to where the clause really ended.
     * eSpeak's clause reader looks one character ahead ("Hello, t|his"), and
     * the decoder position it leaves behind counts that character as read, so
     * the reported end lands inside the next clause's first word. Walking
     * back over word characters puts it after the space or punctuation again,
     * which is what word highlighting needs.
     */
    static List<Clause> alignToText(List<Clause> clauses, String text) {
        final int cps = text.codePointCount(0, text.length());
        final List<Clause> out = new ArrayList<>(clauses.size());
        int prevEnd = 0;
        for (int i = 0; i < clauses.size(); i++) {
            final Clause c = clauses.get(i);
            // Clauses tile the text: each starts where the previous one ended.
            int start = i == 0 ? Math.min(c.start, cps) : prevEnd;
            int end = Math.max(start, Math.min(c.end, cps));
            if (i + 1 < clauses.size() && end < cps && end > start) {
                int idx = text.offsetByCodePoints(0, end);
                int back = end;
                while (back > start) {
                    final int before = text.codePointBefore(idx);
                    if (!isWordChar(before)) {
                        break;
                    }
                    idx -= Character.charCount(before);
                    back--;
                }
                if (back > start) {
                    end = back;
                }
            }
            out.add(new Clause(c.endsSentence, start, end, c.ipa));
            prevEnd = end;
        }
        return out;
    }

    private static boolean isWordChar(int cp) {
        if (Character.isLetterOrDigit(cp)) {
            return true;
        }
        final int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * Groups clauses into synthesis chunks: whole sentences, except that a
     * sentence running past the budget is cut at a clause boundary (never
     * mid-clause, so prosody only breaks where eSpeak already heard a comma).
     */
    static List<Chunk> chunk(List<Clause> clauses, int firstBudget, int budget) {
        final List<Chunk> chunks = new ArrayList<>();
        final StringBuilder ipa = new StringBuilder();
        int start = -1;
        int end = 0;
        int length = 0;
        for (Clause clause : clauses) {
            if (start < 0) {
                start = clause.start;
            }
            ipa.append(clause.ipa);
            end = clause.end;
            length += clause.ipa.codePointCount(0, clause.ipa.length());
            final int limit = chunks.isEmpty() ? firstBudget : budget;
            if (clause.endsSentence || length >= limit) {
                addChunk(chunks, start, end, ipa, clause.endsSentence);
                ipa.setLength(0);
                start = -1;
                length = 0;
            }
        }
        if (start >= 0) {
            addChunk(chunks, start, end, ipa, true);
        }
        return chunks;
    }

    private static void addChunk(List<Chunk> chunks, int start, int end, StringBuilder ipa,
                                 boolean endsSentence) {
        final String text = ipa.toString().trim();
        if (!text.isEmpty()) {
            chunks.add(new Chunk(start, end, text, endsSentence));
        }
    }

    /**
     * IPA respellings that put today's eSpeak output back the way a voice
     * was trained. Every published voice learned from the eSpeak NG of its
     * day: piper 1.0-1.2 voices from rhasspy's 2023 snapshot, 1.3+ from
     * upstream. A symbol the voice never heard still has an id (Piper's id
     * map is shared by all voices), so it is not skipped - it is garbled.
     * Two sources: this fork's own changes (ड़/ढ़ became "ɽ"/"ɽʱ" where
     * upstream wrote the raw text "r."/"r.h"; Nepali च/ज became "ts"/"dz"),
     * and upstream's changes since 2023 (Swedish retroflexes, Ukrainian в,
     * Portuguese nasals and r). Measured with android/tools/piper_spellings.py
     * as words spelled exactly as the voice was trained, of 1500 per
     * language; only rules that raise that count are kept. Pronunciation
     * improvements (schwa deletion, vowel length) are left alone: they are
     * sequences of symbols the voice knows. Whisper on Hindi: ड़/ढ़ words
     * recognised 0/54 -> 34/54.
     */
    static final class Spelling {
        final Pattern pattern;
        final String replacement;

        Spelling(String regex, String replacement) {
            this.pattern = Pattern.compile(regex);
            this.replacement = Matcher.quoteReplacement(replacement);
        }
    }

    /** Word end: no letter, mark or length follows. */
    private static final String END = "(?![\\p{L}\\p{M}ː])";
    /** Before a consonant or a word boundary, past a stress mark. */
    private static final String BEFORE_CONSONANT = "(?=[ˈˌ]?[^\\p{L}\\p{M}ːˈˌ ]|[ˈˌ]?"
            + "[bcdfɡhjklmnpqrstvwxzʃʒθðŋɲɟʎʁɾɹʋβɣçʝɕʑʈɖɳɭɻɽ])";

    private static final Spelling[] NO_SPELLINGS = {};
    /** ड़/ढ़ and breathy stops in the languages built on the Hindi phoneme table. */
    private static final String[] FLAP = {"ɽʱ", "r.h", "ɽ", "r.", "ʱ", "ʰ"};
    /** Portuguese nasal vowels before a consonant, as the 2023 eSpeak wrote them. */
    private static final String[] PT_NASALS = {"ẽn", "eɪŋ", "ẽm", "eɪm", "ĩn", "iŋ", "ũn", "ũŋ",
            "ɐ̃n" + BEFORE_CONSONANT, "ɐ̃ŋ"};
    /** Portuguese r after a consonant, and the schwa the 2023 eSpeak put after a coda r. */
    private static String[] ptR(String clusterR) {
        return new String[] {"(?<=[ptkbdɡfv])ɾ", clusterR, "ɾ(?=[ptkbdɡfvszʃʒmnl])", "ɾə"};
    }

    /** Key: eSpeak voice, plus ":old" for voices trained on the 2023 eSpeak. */
    private static final Map<String, Spelling[]> SPELLINGS = new java.util.HashMap<>();

    private static void put(String key, String[]... parts) {
        final List<Spelling> all = new ArrayList<>();
        for (String[] part : parts) {
            for (int i = 0; i + 1 < part.length; i += 2) {
                all.add(new Spelling(part[i], part[i + 1]));
            }
        }
        SPELLINGS.put(key, all.toArray(new Spelling[0]));
    }

    static {
        for (String lang : new String[] {"pa", "gu", "mr", "or", "as", "sd", "bn", "kn", "ta",
                "si", "kok", "bpy"}) {
            put(lang, FLAP);
        }
        put("hi", FLAP, new String[] {"æ", "ɛ"});                    // बैंक
        put("ur", FLAP, new String[] {"ɾ", "r", "ɑ", "a", "ɳ", "n"});
        put("ml", new String[] {"ɻ", "r."}, FLAP);                    // ഴ
        put("ne", new String[] {"ɽʱ", "ɖʰ", "ɽ", "ɖ", "dzʱ", "ɟʰ", "dz", "ɟ", "tsʰ", "cʰ",
                "ts", "c", "ʱ", "ʰ"});
        put("te", new String[] {"ŋ", "n", "ɲ", "n"});                  // పంక్తి
        put("sv:old", new String[] {"ɧ", "sx", "ɳ", "rn", "ɭ", "rl", "ɖ", "rd", "ʈ", "t"});
        put("uk:old", new String[] {"w(?=[ptkfsʃxʧ])", "f", "[ʋw]", "β", "ʲ", "j"});
        put("ca:old", new String[] {"ʃ", "ɕ", "ʒ", "ʑ", "ɱ", "n", "ə" + END, "ɐ",
                "(?<!ˈ[^\\s\\p{M}aeiouɛɔəɐ]{0,3})u", "ʊ", "i(?=[ˈˌ]?[aeoɔɛɐə])", "j"});
        put("de:old", new String[] {"ʏ", "y", "ʊɐ", "??"});
        put("pt-br:old", new String[] {"ʎ", "lj", "ɐ" + END, "æ", "ɾ" + END, "r", "ɪ" + END, "y",
                "õn", "oŋ"}, PT_NASALS, ptR("r"));
        put("pt:old", new String[] {"ɾ" + END, "ɹ", "õn", "uŋ"}, PT_NASALS, ptR("ɹ"));
    }

    /**
     * @param oldEspeak the voice was trained with piper 1.0-1.2 (or does not
     *                  say), i.e. on rhasspy's 2023 eSpeak NG
     */
    static Spelling[] trainedSpellings(String espeakVoice, boolean oldEspeak) {
        if (espeakVoice == null) {
            return NO_SPELLINGS;
        }
        final String voice = espeakVoice.toLowerCase(Locale.ROOT);
        Spelling[] s = oldEspeak ? SPELLINGS.get(voice + ":old") : null;
        if (s == null) {
            s = SPELLINGS.get(voice);
        }
        if (s == null && voice.indexOf('-') > 0) {
            s = SPELLINGS.get(voice.substring(0, voice.indexOf('-')));
        }
        return s != null ? s : NO_SPELLINGS;
    }

    /**
     * Splits IPA into Piper "phonemes": NFD code points (so "ç" is "c" plus
     * a combining cedilla, as in training), then the voice's vowel clusters
     * merged back into single symbols. Fork-only spellings are first put
     * back the way the voice learned them ({@link #trainedSpellings}).
     */
    static List<String> tokenize(String ipa, PiperVoiceConfig config) {
        if (!config.usesEspeak()) {
            return textTokens(englishChunkEnd(ipa, config), config);
        }
        if (config.trainedSpellings.length > 0) {
            ipa = Normalizer.normalize(ipa, Normalizer.Form.NFC);
            for (Spelling spelling : config.trainedSpellings) {
                ipa = spelling.pattern.matcher(ipa).replaceAll(spelling.replacement);
            }
        }
        final String nfd = Normalizer.normalize(ipa, Normalizer.Form.NFD);
        final List<String> phones = new ArrayList<>(nfd.length());
        for (int i = 0; i < nfd.length(); ) {
            final int cp = nfd.codePointAt(i);
            final int n = Character.charCount(cp);
            phones.add(nfd.substring(i, i + n));
            i += n;
        }
        if (config.vowelClusters.isEmpty()) {
            return phones;
        }
        final List<String> merged = new ArrayList<>(phones.size());
        int i = 0;
        outer:
        while (i < phones.size()) {
            for (String[] cluster : config.vowelClusters) {
                if (i + cluster.length > phones.size()) {
                    continue;
                }
                boolean match = true;
                for (int j = 0; j < cluster.length; j++) {
                    if (!cluster[j].equals(phones.get(i + j))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    final StringBuilder sb = new StringBuilder();
                    for (String part : cluster) {
                        sb.append(part);
                    }
                    merged.add(sb.toString());
                    i += cluster.length;
                    continue outer;
                }
            }
            merged.add(phones.get(i));
            i++;
        }
        return merged;
    }

    /**
     * Piper's phonemes_to_ids(). Phonemes the voice has no id for are skipped
     * and, when {@code missing} is given, collected so the caller can log them
     * once instead of per utterance.
     *
     * @param wordStarts if not null, receives the id index where each word
     *                   (a space-separated run with a letter in it) starts,
     *                   for timing words with the model's durations
     */
    static long[] toIds(List<String> phonemes, PiperVoiceConfig config, List<String> missing,
                        List<Integer> wordStarts) {
        final int[] pad = config.phonemeIdMap.get(PiperVoiceConfig.PAD);
        final int[] bos = config.phonemeIdMap.get(PiperVoiceConfig.BOS);
        final int[] eos = config.phonemeIdMap.get(PiperVoiceConfig.EOS);
        final LongList ids = new LongList(phonemes.size() * 2 + 4);
        ids.addAll(bos);
        ids.addAll(pad);
        boolean inWord = false;
        for (String phoneme : phonemes) {
            int[] mapped = config.phonemeIdMap.get(phoneme);
            if (mapped == null) {
                // A modifier letter the voice was trained without (this fork's
                // Sinhala prenasal "ⁿ" in a voice from an older eSpeak):
                // its plain letter is closer than dropping the sound.
                mapped = config.phonemeIdMap.get(
                        java.text.Normalizer.normalize(phoneme, java.text.Normalizer.Form.NFKC));
            }
            if (mapped == null) {
                if (missing != null && !missing.contains(phoneme)) {
                    missing.add(phoneme);
                }
                continue;
            }
            if (" ".equals(phoneme)) {
                inWord = false;
            } else if (!inWord && wordStarts != null && !isPunctuation(phoneme)) {
                wordStarts.add(ids.size);
                inWord = true;
            }
            ids.addAll(mapped);
            ids.addAll(pad);
        }
        ids.addAll(eos);
        return ids.toArray();
    }

    static long[] toIds(List<String> phonemes, PiperVoiceConfig config, List<String> missing) {
        return toIds(phonemes, config, missing, null);
    }

    private static boolean isPunctuation(String phoneme) {
        switch (Character.getType(phoneme.codePointAt(0))) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }

    private static final java.util.regex.Pattern FINAL_STOP = java.util.regex.Pattern.compile("\\.\\s*$");

    /**
     * English text voices (SYSPIN Priya, Rahul) add a syllable after a
     * chunk-final full stop: "missed call on phone." came out "...phoned".
     * Measured 2026-10-03 (Whisper, short phrases): ending with a comma
     * instead, Priya 10/24 phrases misheard -> 0/24, Rahul 6/18 -> 3/18. For
     * the Indic voices the comma was no better, so their full stop stays.
     */
    static String englishChunkEnd(String text, PiperVoiceConfig config) {
        return "en".equals(config.languageFamily) ? FINAL_STOP.matcher(text).replaceFirst(",") : text;
    }

    /**
     * A "text" voice's letters, lower-cased: each composed letter as is when
     * the voice has it (character VITS voices trained on NFC script, so
     * Bengali "ো" stays one letter), else its NFD parts (Piper's own text
     * voices map decomposed letters).
     */
    private static List<String> textTokens(String text, PiperVoiceConfig config) {
        final String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>(nfc.length());
        for (int i = 0; i < nfc.length(); ) {
            final int n = Character.charCount(nfc.codePointAt(i));
            final String letter = nfc.substring(i, i + n);
            i += n;
            if (config.phonemeIdMap.containsKey(letter)) {
                out.add(letter);
                continue;
            }
            final String nfd = Normalizer.normalize(letter, Normalizer.Form.NFD);
            for (int j = 0; j < nfd.length(); ) {
                final int m = Character.charCount(nfd.codePointAt(j));
                out.add(nfd.substring(j, j + m));
                j += m;
            }
        }
        return out;
    }

    /**
     * For "text" voices (phonemes are the letters themselves): split at
     * sentence punctuation so long input still streams sentence by sentence.
     */
    static List<Clause> textClauses(String text) {
        return textClauses(text, null);
    }

    /** @param spell rewrites each sentence before it is read (numbers into words), or null */
    static List<Clause> textClauses(String text, java.util.function.UnaryOperator<String> spell) {
        final List<Clause> clauses = new ArrayList<>();
        int start = 0;
        int cpStart = 0;
        int cp = 0;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            cp++;
            final boolean sentenceEnd = c == '.' || c == '!' || c == '?' || c == '\n'
                    || c == '।' || c == '。';
            if (sentenceEnd || i >= text.length()) {
                final String piece = text.substring(start, i);
                if (!piece.trim().isEmpty()) {
                    final String said = spell != null ? spell.apply(piece.trim()) : piece.trim();
                    clauses.add(new Clause(true, cpStart, cp, said + " "));
                }
                start = i;
                cpStart = cp;
            }
        }
        return clauses;
    }

    /** Minimal growable long[], avoiding a boxed List<Long> per phoneme. */
    private static final class LongList {
        private long[] data;
        private int size;

        LongList(int capacity) {
            data = new long[Math.max(capacity, 8)];
        }

        void addAll(int[] values) {
            if (values == null) {
                return;
            }
            if (size + values.length > data.length) {
                long[] grown = new long[Math.max(data.length * 2, size + values.length)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            for (int v : values) {
                data[size++] = v;
            }
        }

        long[] toArray() {
            final long[] out = new long[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
