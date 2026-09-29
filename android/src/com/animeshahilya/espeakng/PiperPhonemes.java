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
     * This fork's IPA for sounds upstream eSpeak NG spelled differently,
     * mapped back to upstream's spelling for voices of the languages built
     * on the Hindi phoneme table (every published voice was trained on
     * upstream). Upstream never defined ड़/ढ़ there, so its phonemizer wrote
     * the raw "r."/"r.h" and the voices learned the flap from that; the
     * fork's "ɽ" has an id but no training behind it, and was heard as a
     * garbled or missing sound (Whisper: सड़क/भीड़ -> "साथ"/"भीग"). "ʱ" has no
     * id at all (NFKC made it "ɦ"). Longest first; eSpeak's own speech is
     * unaffected.
     */
    private static final String[][] INDIC_SPELLINGS = {
            {"ɽʱ", "r.h"}, // ढ़
            {"ɽ", "r."},   // ड़, ড়, ੜ, ଡ଼
            {"ɻ", "r."},   // Malayalam ഴ
            {"ʱ", "ʰ"},    // breathy stops (Nepali झ "dzʱ", upstream "ɟʰ")
            {"æ", "ɛ"},    // English loans (बैंक)
    };
    private static final String[][] NO_SPELLINGS = {};

    /** Upstream eSpeak NG voices whose phoneme table is built on hi_base (phsource/phonemes). */
    private static final java.util.Set<String> HI_BASE_LANGUAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("hi", "ne", "ur", "pa", "ta", "kn", "ml", "gu", "mr", "kok",
                    "te", "si", "bn", "as", "or", "bpy", "sd"));

    static String[][] trainedSpellings(String espeakVoice) {
        if (espeakVoice == null) {
            return NO_SPELLINGS;
        }
        final int dash = espeakVoice.indexOf('-');
        final String language = dash > 0 ? espeakVoice.substring(0, dash) : espeakVoice;
        return HI_BASE_LANGUAGES.contains(language) ? INDIC_SPELLINGS : NO_SPELLINGS;
    }

    /**
     * Splits IPA into Piper "phonemes": NFD code points (so "ç" is "c" plus
     * a combining cedilla, as in training), then the voice's vowel clusters
     * merged back into single symbols. Fork-only spellings are first put
     * back the way the voice learned them ({@link #trainedSpellings}).
     */
    static List<String> tokenize(String ipa, PiperVoiceConfig config) {
        for (String[] spelling : config.trainedSpellings) {
            ipa = ipa.replace(spelling[0], spelling[1]);
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

    /**
     * For "text" voices (phonemes are the letters themselves): split at
     * sentence punctuation so long input still streams sentence by sentence.
     */
    static List<Clause> textClauses(String text) {
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
                    clauses.add(new Clause(true, cpStart, cp, piece.trim() + " "));
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
