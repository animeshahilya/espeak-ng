/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.util.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Supertone's Supertonic 3 (OpenRAIL-M; release supertonic-v1 on
 * sherpa-onnx-respin-syspin, made by its build_supertonic.py): 31 languages,
 * ten styles, read from plain text - no phonemizer. Four models: the duration
 * predictor and text encoder run on the CPU; the flow-matching vector
 * estimator (8 steps) and the vocoder run on the Snapdragon NPU, one compiled
 * graph per size bucket ({@link #BUCKETS}), each compiled once and kept in
 * the shared store for every Supertonic voice. Without an NPU graph a bucket
 * runs on the CPU (~1.5x real time on a Pixel 8: the voices are offered only
 * on phones with the NPU).
 *
 * <p>Every voice is one language x one style over the same files (like the
 * AI4Bharat Rasa speakers): {@link PiperModel} shares this runtime across
 * them and keeps each voice's {@link Style}.
 */
final class Supertonic implements Closeable {
    /** Files in the voice folder (links into the shared store), besides model.onnx (the vector estimator). */
    static final String DURATION_PREDICTOR = "duration_predictor.onnx";
    static final String TEXT_ENCODER = "text_encoder.onnx";
    static final String VOCODER = "vocoder.onnx";
    static final String INDEXER = "unicode_indexer.json";
    static final String TTS_JSON = "tts.json";
    static final String STYLE = "style.json";

    /** Supertonic's own language tags (its helper's AVAILABLE_LANGS, without "na"). */
    static final java.util.Set<String> LANGUAGES = new java.util.HashSet<>(Arrays.asList(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr",
            "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"));

    /**
     * NPU size buckets: {latent frames, text ids}. One frame is 3072 samples
     * (70 ms) at 44.1 kHz, so 96 frames = 6.7 s (first chunks and screen
     * reader phrases) and 192 = 13.4 s (later chunks); text ids run about one
     * per frame plus the language tags. Galaxy S25 Ultra, per flow step: 96
     * frames 54 ms, 192 frames 111 ms. Each bucket is a compiled graph of
     * ~180 MB. The 32-frame bucket (2.2 s) is for screen-reader phrases: the
     * model pads speech with silence, so even "Back" is 20 frames and the
     * phrases of a TalkBack session are 20-35; in the 96 bucket they filled
     * a quarter of it and paid 6.7 s of flow (PC, INT8 duration model,
     * supertonic3-eval/harness/fill.py).
     */
    static final int[][] BUCKETS = {{32, 48}, {96, 144}, {192, 288}};
    /** The bucket only long text needs: loaded at its first use ({@link #npu}). */
    static final int LAZY_BUCKET = BUCKETS.length - 1;
    /** Chunk sizes in letters ({@link PiperPhonemes#chunk}) that fit the buckets at normal speed. */
    static final int FIRST_CHUNK = 60;
    static final int CHUNK = 160;
    /**
     * Korean and Japanese say about twice as much per letter (their helper
     * cuts at 120 instead of 300), and Hangul syllables become 2-3 jamo ids.
     */
    static final int FIRST_CHUNK_CJK = 30;
    static final int CHUNK_CJK = 80;

    static int firstChunk(String lang) {
        return isCjk(lang) ? FIRST_CHUNK_CJK : FIRST_CHUNK;
    }

    static int chunk(String lang) {
        return isCjk(lang) ? CHUNK_CJK : CHUNK;
    }

    private static boolean isCjk(String lang) {
        return "ko".equals(lang) || "ja".equals(lang);
    }

    /**
     * Fastest speed asked of the model; libsonic does the rest
     * (PiperEngine.maxModelSpeed). Flow matching squeezes speech far worse
     * than VITS: Parakeet word errors on long sentences and a paragraph (PC,
     * INT8 files, 2 styles x 2 draws): x1.0 0.9%, x1.25 2.2%, x1.5 11.0%.
     * At the old cap of 1.8 (a 450 wpm screen reader) words were lost.
     */
    static final float MAX_SPEED = 1.25f;
    /**
     * The model's own pace is 154 wpm (same measure) where the app's normal
     * rate is 175: without this Supertonic spoke 12% slower than the rate set.
     */
    static final float RATE_FACTOR = 175f / 154f;

    // --- Chunks: where the text is cut into model runs ---

    /** Words a '.' follows without ending the sentence (their helper's list, plus a few). */
    private static final java.util.Set<String> ABBREVIATIONS = new java.util.HashSet<>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "inc", "ltd", "co", "corp",
            "ave", "blvd", "no", "rs", "fig", "vol", "approx", "dept", "govt", "mt"));
    /** Closing marks that stay with the sentence they close. */
    private static final String CLOSERS = "\"')]}»”’」』";

    /**
     * The text cut into model runs, as Supertone's helper reads it: whole
     * sentences, several to a run up to the budget. Before, every '.' ended
     * a sentence ("3.5", "Dr.", "1.9.7" and "U.S." each became two or three
     * runs, each read as a sentence of its own) and a long sentence was cut
     * at whatever word reached the budget, with a full stop added there.
     *
     * <p>A sentence past the budget is cut at its commas, semicolons and
     * colons, at a word (with a comma, so the voice keeps a continuing tone)
     * only where a clause is itself too long, into pieces of even length.
     * The first run is cut short for quick first audio, at a clause if one
     * ends in time. Budgets grow with the model speed: a faster voice says
     * more letters in a bucket's frames, a slower one fewer (else a run
     * outgrew the largest bucket and fell back to the CPU).
     *
     * @param modelSpeed the speed asked of the model (1 = its own)
     */
    static List<PiperPhonemes.Chunk> chunks(String text, String lang, float modelSpeed) {
        final float grow = Math.max(0.5f, modelSpeed); // slower speech: fewer letters fit a bucket
        final int first = Math.min(MAX_FIRST_CHUNK, Math.round(firstChunk(lang) * grow));
        final int later = Math.min(MAX_CHUNK, Math.round(chunk(lang) * grow));
        final List<PiperPhonemes.Chunk> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        final int[] cps = text.codePoints().toArray();
        int curStart = -1;
        int curEnd = -1;
        for (int[] s : sentences(cps)) {
            if (out.isEmpty() && curStart >= 0 && s[1] - curStart <= first) {
                curEnd = s[1]; // short sentences share the first run too
                continue;
            }
            if (out.isEmpty() && curStart < 0 && s[1] - s[0] <= first) {
                curStart = s[0];
                curEnd = s[1];
                continue;
            }
            if (out.isEmpty() && curStart < 0) {
                final int head = firstPiece(cps, s[0], s[1], first);
                add(out, cps, s[0], head, head == s[1]);
                if (head < s[1]) {
                    final List<int[]> pieces = splitSentence(cps, skipSpaces(cps, head), s[1], later);
                    for (int i = 0; i < pieces.size() - 1; i++) {
                        add(out, cps, pieces.get(i)[0], pieces.get(i)[1], false);
                    }
                    curStart = pieces.get(pieces.size() - 1)[0];
                    curEnd = s[1];
                }
                continue;
            }
            if (!out.isEmpty() && curStart >= 0 && s[1] - curStart <= later) {
                curEnd = s[1];
                continue;
            }
            if (curStart >= 0) {
                add(out, cps, curStart, curEnd, true);
            }
            final List<int[]> pieces = splitSentence(cps, s[0], s[1], later);
            for (int i = 0; i < pieces.size() - 1; i++) {
                add(out, cps, pieces.get(i)[0], pieces.get(i)[1], false);
            }
            curStart = pieces.get(pieces.size() - 1)[0];
            curEnd = s[1];
        }
        if (curStart >= 0) {
            add(out, cps, curStart, curEnd, true);
        }
        return out;
    }

    /** Longest runs in letters: they must fit the largest bucket's text ids with the tags. */
    static final int MAX_FIRST_CHUNK = 130;
    static final int MAX_CHUNK = 270;

    /** A chunk of [from, to); a piece that ends mid-sentence gets a comma (not preprocess's '.'). */
    private static void add(List<PiperPhonemes.Chunk> out, int[] cps, int from, int to, boolean endsSentence) {
        while (to > from && isSpace(cps[to - 1])) {
            to--;
        }
        from = skipSpaces(cps, from);
        if (to <= from) {
            return;
        }
        String said = new String(cps, from, to - from);
        if (!endsSentence && !isClauseMark(cps[to - 1]) && !isSentenceMark(cps[to - 1])) {
            said += ",";
        }
        out.add(new PiperPhonemes.Chunk(from, to, said, endsSentence));
    }

    /** Sentence spans [start, end) in code points, each ending after its mark and closers. */
    static List<int[]> sentences(int[] cps) {
        final List<int[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < cps.length; i++) {
            final int c = cps[i];
            boolean end = c == '\n' || c == '।' || c == '॥' || c == '。' || c == '！' || c == '？' || c == '؟';
            if (!end && (c == '.' || c == '!' || c == '?' || c == '…')) {
                final int next = i + 1 < cps.length ? cps[i + 1] : ' ';
                end = (isSpace(next) || CLOSERS.indexOf(next) >= 0) && !(c == '.' && abbreviationBefore(cps, start, i));
            }
            if (end) {
                int j = i + 1;
                while (j < cps.length && CLOSERS.indexOf(cps[j]) >= 0) {
                    j++;
                }
                addSpan(out, cps, start, j);
                start = j;
                i = j - 1;
            }
        }
        addSpan(out, cps, start, cps.length);
        return out;
    }

    private static void addSpan(List<int[]> out, int[] cps, int from, int to) {
        from = skipSpaces(cps, from);
        if (from < to) {
            out.add(new int[] {from, to});
        }
    }

    /** "Dr." "Mr." "U.S." "J." - a '.' at {@code dot} that does not end the sentence. */
    private static boolean abbreviationBefore(int[] cps, int sentenceStart, int dot) {
        int w = dot;
        while (w > sentenceStart && (Character.isLetterOrDigit(cps[w - 1]) || cps[w - 1] == '.')) {
            w--;
        }
        final String word = new String(cps, w, dot - w);
        if (word.isEmpty()) {
            return false;
        }
        if (ABBREVIATIONS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        // One letter ("J.") or dotted initials ("U.S", "e.g"): letters only, each one long.
        for (String part : word.split("\\.", -1)) {
            if (part.codePointCount(0, part.length()) != 1 || !Character.isLetter(part.codePointAt(0))) {
                return false;
            }
        }
        return true;
    }

    /** Where the first run stops: whole clauses up to the budget, else the word nearest it. */
    private static int firstPiece(int[] cps, int from, int to, int budget) {
        if (to - from <= budget) {
            return to;
        }
        int end = from;
        for (int[] c : clauses(cps, from, to)) {
            if (c[1] - from > budget) {
                break;
            }
            end = c[1];
        }
        if (end > from) {
            return end;
        }
        return wordCut(cps, from, from + budget, to);
    }

    /** A sentence as runs of at most {@code budget} letters: whole clauses, long clauses cut evenly at words. */
    private static List<int[]> splitSentence(int[] cps, int from, int to, int budget) {
        final List<int[]> out = new ArrayList<>();
        if (to - from <= budget) {
            out.add(new int[] {from, to});
            return out;
        }
        int curStart = -1;
        int curEnd = -1;
        for (int[] c : clauses(cps, from, to)) {
            if (c[1] - c[0] > budget) {
                if (curStart >= 0) {
                    out.add(new int[] {curStart, curEnd});
                    curStart = -1;
                }
                final int parts = (c[1] - c[0] + budget - 1) / budget;
                int at = c[0];
                for (int p = 1; p < parts; p++) {
                    final int cut = wordCut(cps, at, c[0] + (c[1] - c[0]) * p / parts, c[1]);
                    out.add(new int[] {at, cut});
                    at = skipSpaces(cps, cut);
                }
                out.add(new int[] {at, c[1]});
            } else if (curStart >= 0 && c[1] - curStart > budget) {
                out.add(new int[] {curStart, curEnd});
                curStart = c[0];
                curEnd = c[1];
            } else {
                if (curStart < 0) {
                    curStart = c[0];
                }
                curEnd = c[1];
            }
        }
        if (curStart >= 0) {
            out.add(new int[] {curStart, curEnd});
        }
        return out;
    }

    /** Clause spans: cut after , ; : – — that a space follows. */
    private static List<int[]> clauses(int[] cps, int from, int to) {
        final List<int[]> out = new ArrayList<>();
        int start = from;
        for (int i = from; i < to; i++) {
            if (isClauseMark(cps[i]) && i + 1 < to && isSpace(cps[i + 1])) {
                out.add(new int[] {start, i + 1});
                start = skipSpaces(cps, i + 1);
            }
        }
        if (start < to) {
            out.add(new int[] {start, to});
        }
        return out;
    }

    /** The word boundary (a space) nearest {@code target}, strictly inside (from, to); else {@code to}. */
    private static int wordCut(int[] cps, int from, int target, int to) {
        for (int d = 0; d < to - from; d++) {
            final int before = target - d;
            if (before > from && before < to && isSpace(cps[before])) {
                return before;
            }
            final int after = target + d;
            if (after > from && after < to && isSpace(cps[after])) {
                return after;
            }
        }
        return to;
    }

    private static int skipSpaces(int[] cps, int i) {
        while (i < cps.length && isSpace(cps[i])) {
            i++;
        }
        return i;
    }

    private static boolean isSpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean isClauseMark(int c) {
        return c == ',' || c == ';' || c == ':' || c == '–' || c == '—' || c == '،' || c == '、' || c == '，';
    }

    private static boolean isSentenceMark(int c) {
        return c == '.' || c == '!' || c == '?' || c == '…' || c == '।' || c == '॥' || c == '。' || c == '؟'
                || c == '！' || c == '？' || CLOSERS.indexOf(c) >= 0;
    }

    // --- Text (a port of Supertone's py/helper.py UnicodeProcessor, pinned in build_supertonic.py) ---

    private static final Pattern EMOJI = Pattern.compile("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}"
            + "\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}"
            + "\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}"
            + "\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}]+");
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();
    static {
        final String[] pairs = {"–", "-", "‑", "-", "—", "-", "_", " ", "“", "\"", "”", "\"",
                "‘", "'", "’", "'", "´", "'", "`", "'", "[", " ", "]", " ", "|", " ", "/", " ",
                "#", " ", "→", " ", "←", " "};
        for (int i = 0; i < pairs.length; i += 2) {
            REPLACEMENTS.put(pairs[i], pairs[i + 1]);
        }
    }
    private static final Pattern SPECIAL = Pattern.compile("[♥☆♡©\\\\]");
    private static final String[][] EXPRESSIONS = {{"@", " at "}, {"e.g.,", "for example, "},
            {"i.e.,", "that is, "}};
    private static final String[][] SPACING = {{" ,", ","}, {" .", "."}, {" !", "!"}, {" ?", "?"},
            {" ;", ";"}, {" :", ":"}, {" '", "'"}};
    /**
     * Python's whitespace (str.isspace), spelled out: Android's ICU regex has
     * no UNICODE_CHARACTER_CLASS flag (the JVM test passed, the phone threw)
     * and the JDK's plain \s is ASCII only.
     */
    private static final Pattern SPACES = Pattern.compile("[\\t\\n\\x{0B}\\f\\r\\x{1C}-\\x{1F} \\x{85}\\x{A0}"
            + "\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+");
    private static final Pattern ENDS = Pattern.compile("[.!?;:,'\"')\\]}…。」』】〉》›»]$");

    /** The text as Supertonic reads it: normalized, tidied, a final stop, its language tags. */
    static String preprocess(String text, String lang) {
        String t = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFKD);
        t = EMOJI.matcher(t).replaceAll("");
        for (Map.Entry<String, String> e : REPLACEMENTS.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        t = SPECIAL.matcher(t).replaceAll("");
        for (String[] e : EXPRESSIONS) {
            t = t.replace(e[0], e[1]);
        }
        for (String[] e : SPACING) {
            t = t.replace(e[0], e[1]);
        }
        while (t.contains("\"\"")) {
            t = t.replace("\"\"", "\"");
        }
        while (t.contains("''")) {
            t = t.replace("''", "'");
        }
        while (t.contains("``")) {
            t = t.replace("``", "`");
        }
        t = SPACES.matcher(t).replaceAll(" ").trim();
        if (!ENDS.matcher(t).find()) {
            t += ".";
        }
        return "<" + lang + ">" + t + "</" + lang + ">";
    }

    /** Text ids for {@link #preprocess}ed text: one per code point (16-bit, as their uint16 array). */
    static long[] textIds(String processed, int[] indexer) {
        final long[] ids = new long[processed.codePointCount(0, processed.length())];
        int n = 0;
        for (int i = 0; i < processed.length(); ) {
            final int cp = processed.codePointAt(i);
            i += Character.charCount(cp);
            ids[n++] = indexer[cp & 0xFFFF];
        }
        return ids;
    }

    long[] textIds(String text, String lang) {
        return textIds(known(preprocess(text, lang), indexer), indexer);
    }

    /** Stand-ins for letters the table lacks but the languages use (Hindi). */
    private static final Map<Integer, String> REPAIRS = new HashMap<>();
    static {
        REPAIRS.put((int) 'ऋ', "रि");
        REPAIRS.put((int) 'ॠ', "री");
        REPAIRS.put((int) 'ऌ', "लृ");
        REPAIRS.put((int) 'ॐ', "ओम्");
        REPAIRS.put((int) '॥', "।");
        REPAIRS.put((int) '٫', ".");
        REPAIRS.put((int) '٬', ",");
        for (int d = 0; d <= 9; d++) {
            REPAIRS.put(0x0966 + d, String.valueOf(d)); // Devanagari digits
        }
    }

    /**
     * {@link #preprocess}ed text with only characters the model has an id
     * for: a few stand-ins ({@link #REPAIRS}), anything else becomes a space.
     * Their table maps a missing character to -1, which ONNX's Gather reads
     * as its last row - some unrelated sound in the middle of the speech
     * (Hindi ऋ, ॐ, ॥ and Devanagari digits, arrows, stars and check marks).
     */
    static String known(String processed, int[] indexer) {
        StringBuilder out = null;
        for (int i = 0; i < processed.length(); ) {
            final int cp = processed.codePointAt(i);
            final int n = Character.charCount(cp);
            if (indexer[cp & 0xFFFF] >= 0 && cp <= 0xFFFF) {
                if (out != null) {
                    out.appendCodePoint(cp);
                }
            } else {
                if (out == null) {
                    out = new StringBuilder(processed.length()).append(processed, 0, i);
                }
                final String repair = REPAIRS.get(cp);
                if (repair != null && isKnown(repair, indexer)) {
                    out.append(repair);
                } else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            }
            i += n;
        }
        return out == null ? processed : out.toString();
    }

    private static boolean isKnown(String s, int[] indexer) {
        for (int i = 0; i < s.length(); i++) {
            if (indexer[s.charAt(i)] < 0) {
                return false;
            }
        }
        return true;
    }

    static int[] readIndexer(String json) throws JSONException {
        final JSONArray a = new JSONArray(json);
        final int[] out = new int[0x10000];
        Arrays.fill(out, -1);
        for (int i = 0; i < Math.min(out.length, a.length()); i++) {
            out[i] = a.getInt(i);
        }
        return out;
    }

    /** A voice: its style vectors (style_ttl 1x50x256, style_dp 1x8x16). */
    static final class Style {
        final float[] ttl;
        final long[] ttlShape;
        final float[] dp;
        final long[] dpShape;

        Style(float[] ttl, long[] ttlShape, float[] dp, long[] dpShape) {
            this.ttl = ttl;
            this.ttlShape = ttlShape;
            this.dp = dp;
            this.dpShape = dpShape;
        }

        static Style parse(String json) throws JSONException {
            final JSONObject root = new JSONObject(json);
            final JSONObject ttl = root.getJSONObject("style_ttl");
            final JSONObject dp = root.getJSONObject("style_dp");
            return new Style(flat(ttl.getJSONArray("data")), dims(ttl.getJSONArray("dims")),
                    flat(dp.getJSONArray("data")), dims(dp.getJSONArray("dims")));
        }

        static Style load(File voiceDir) throws IOException {
            try {
                return parse(FileUtils.readText(new File(voiceDir, STYLE)));
            } catch (JSONException e) {
                throw new IOException("Bad Supertonic style in " + voiceDir, e);
            }
        }

        private static long[] dims(JSONArray a) throws JSONException {
            final long[] out = new long[a.length()];
            for (int i = 0; i < out.length; i++) {
                out[i] = a.getLong(i);
            }
            return out;
        }

        private static float[] flat(JSONArray nested) throws JSONException {
            final java.util.ArrayList<Float> values = new java.util.ArrayList<>();
            collect(nested, values);
            final float[] out = new float[values.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = values.get(i);
            }
            return out;
        }

        private static void collect(JSONArray a, java.util.List<Float> into) throws JSONException {
            for (int i = 0; i < a.length(); i++) {
                final Object o = a.get(i);
                if (o instanceof JSONArray) {
                    collect((JSONArray) o, into);
                } else {
                    into.add((float) a.getDouble(i));
                }
            }
        }
    }

    // --- Runtime ---

    final int sampleRate;
    /** Samples per latent frame (base_chunk_size x chunk_compress_factor). */
    final int frameSamples;
    /** Latent channels (latent_dim x chunk_compress_factor). */
    final int latentChannels;
    final int[] indexer;
    private final OrtSession durations;
    private final OrtSession encoder;
    /** Per bucket; null where its NPU graph is not available (or, for {@link #LAZY_BUCKET}, not loaded yet). */
    private final OrtSession[] npuFlow;
    private final OrtSession[] npuVocoder;
    private final File modelFile;
    private final File dir;
    /** {@link #LAZY_BUCKET} waits for its first use; false once loaded or failed. */
    private boolean largePending;
    private final int threads;
    private OrtSession cpuFlow;
    private OrtSession cpuVocoder;

    private Supertonic(File modelFile, int threads, JSONObject cfg, int[] indexer, OrtSession durations,
                       OrtSession encoder, OrtSession[] npuFlow, OrtSession[] npuVocoder, String npuProblem)
            throws JSONException {
        this.modelFile = modelFile;
        this.dir = modelFile.getParentFile();
        largePending = npuProblem == null && npuFlow[LAZY_BUCKET] == null;
        this.threads = threads;
        final JSONObject ae = cfg.getJSONObject("ae");
        final JSONObject ttl = cfg.getJSONObject("ttl");
        sampleRate = ae.getInt("sample_rate");
        frameSamples = ae.getInt("base_chunk_size") * ttl.getInt("chunk_compress_factor");
        latentChannels = ttl.getInt("latent_dim") * ttl.getInt("chunk_compress_factor");
        this.indexer = indexer;
        this.durations = durations;
        this.encoder = encoder;
        this.npuFlow = npuFlow;
        this.npuVocoder = npuVocoder;
    }

    /**
     * Loads the runtime from a voice folder. NPU graphs that were compiled
     * before load in well under a second; compiling one takes 5-20 s (once
     * per phone and runtime version), so the first load after a download is
     * slow - it runs on the loader thread, which PiperDownloads.prepare
     * starts right after the download.
     */
    static Supertonic load(File modelFile, int threads) throws OrtException {
        final File dir = modelFile.getParentFile();
        try {
            final JSONObject cfg = new JSONObject(FileUtils.readText(new File(dir, TTS_JSON)));
            final int[] indexer = readIndexer(FileUtils.readText(new File(dir, INDEXER)));
            OrtSession dp = null;
            OrtSession te = null;
            final OrtSession[] flow = new OrtSession[BUCKETS.length];
            final OrtSession[] voc = new OrtSession[BUCKETS.length];
            String problem = null;
            try {
                dp = cpuSession(new File(dir, DURATION_PREDICTOR), threads);
                te = cpuSession(new File(dir, TEXT_ENCODER), threads);
                if (!NpuFiles.hasNpuRuntime()) {
                    problem = "no NPU runtime";
                } else {
                    for (int b = 0; b < BUCKETS.length; b++) {
                        if (b == LAZY_BUCKET && compiled(modelFile, BUCKETS[b], "flow")
                                && compiled(new File(dir, VOCODER), BUCKETS[b], "voc")) {
                            continue; // loaded at its first use (largeOnNpu)
                        }
                        flow[b] = npuSession(modelFile, BUCKETS[b], "flow");
                        voc[b] = npuSession(new File(dir, VOCODER), BUCKETS[b], "voc");
                    }
                }
            } catch (OrtException | IOException | RuntimeException e) {
                problem = String.valueOf(e);
                for (int b = 0; b < BUCKETS.length; b++) {
                    PiperModel.closeQuietly(flow[b]);
                    PiperModel.closeQuietly(voc[b]);
                    flow[b] = null;
                    voc[b] = null;
                }
                if (dp == null || te == null) {
                    PiperModel.closeQuietly(dp);
                    PiperModel.closeQuietly(te);
                    throw e instanceof OrtException ? (OrtException) e : new OrtException(String.valueOf(e));
                }
            }
            if (problem != null) {
                android.util.Log.w("PiperVoices", "Supertonic on the CPU: " + problem);
            }
            return new Supertonic(modelFile, threads, cfg, indexer, dp, te, flow, voc, problem);
        } catch (IOException | JSONException e) {
            throw new OrtException("Supertonic files missing in " + dir + ": " + e);
        }
    }

    /** True when the bucket's graph is compiled in the shared store (loads in well under a second). */
    private static boolean compiled(File model, int[] bucket, String what) {
        final File f = ModelFiles.sharedDerivedFile(model, ".st-" + what + bucket[0] + ".ctx.onnx");
        return f != null && f.isFile();
    }

    private static OrtSession cpuSession(File model, int threads) throws OrtException {
        try (OrtSession.SessionOptions o = PiperModel.options(threads)) {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return env().createSession(model.getAbsolutePath(), o);
        }
    }

    /**
     * A bucket's NPU graph, compiled for this chip on the PC with its lengths
     * pinned and INT8 weights widened, all buckets of a stage sharing one
     * weights file (sherpa-onnx-respin-syspin build_supertonic_ctx.py), and
     * downloaded into the shared store (PiperDownloads.fetchSupertonicNpu).
     */
    private static OrtSession npuSession(File model, int[] bucket, String what) throws OrtException, IOException {
        final File compiled = ModelFiles.sharedDerivedFile(model,
                ".st-" + what + bucket[0] + ".ctx.onnx");
        if (compiled == null || !compiled.isFile()) {
            throw new IOException("no compiled NPU graph for " + what + bucket[0]);
        }
        try (OrtSession.SessionOptions o = PiperModel.options(1)) {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            o.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            o.addConfigEntry("session.disable_quant_qdq", "1");
            final Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", NpuFiles.npuBackend());
            qnn.put("enable_htp_fp16_precision", "1");
            qnn.put("htp_performance_mode", "burst");
            NpuFiles.addNpu(o, qnn);
            return env().createSession(compiled.getAbsolutePath(), o);
        }
    }

    private synchronized OrtSession cpuFlow() throws OrtException {
        if (cpuFlow == null) {
            cpuFlow = cpuSession(new File(dir, PiperVoiceStore.MODEL_FILE), threads);
        }
        return cpuFlow;
    }

    private synchronized OrtSession cpuVocoder() throws OrtException {
        if (cpuVocoder == null) {
            cpuVocoder = cpuSession(new File(dir, VOCODER), threads);
        }
        return cpuVocoder;
    }

    /**
     * A bucket's NPU {flow, vocoder}, or null. The largest loads at its first
     * use: screen-reader phrases and first chunks never need it, and each
     * bucket holds ~180 MB (Galaxy S25 Ultra: 742 MB with all three loaded,
     * 564 MB with two); loading a compiled one takes well under a second,
     * while the chunk before it plays.
     */
    private synchronized OrtSession[] npu(int b) {
        if (b == LAZY_BUCKET && largePending) {
            largePending = false;
            try {
                npuFlow[b] = npuSession(modelFile, BUCKETS[b], "flow");
                npuVocoder[b] = npuSession(new File(dir, VOCODER), BUCKETS[b], "voc");
            } catch (OrtException | IOException | RuntimeException e) {
                PiperModel.closeQuietly(npuFlow[b]);
                npuFlow[b] = null;
                npuVocoder[b] = null;
                android.util.Log.w("PiperVoices", "Supertonic bucket " + BUCKETS[b][0] + " on the CPU: " + e);
            }
        }
        return npuFlow[b] != null ? new OrtSession[] {npuFlow[b], npuVocoder[b]} : null;
    }

    /** True when every bucket has its NPU graph (the largest maybe not loaded yet). */
    synchronized boolean onNpu() {
        for (int b = 0; b < BUCKETS.length; b++) {
            if (npuFlow[b] == null && !(b == LAZY_BUCKET && largePending)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Text ids to audio, as Supertone's reference pipeline: duration, text
     * embedding, {@code steps} flow steps from seeded noise, vocoder. The
     * duration predictor and text encoder see the text unpadded; the flow
     * and vocoder run in the smallest bucket that fits, padded with masks.
     *
     * @param speed speech rate (1 = the voice's own)
     * @return audio in [-1, 1] at {@link #sampleRate}, or null when stopped
     */
    float[] synthesize(long[] ids, Style style, int steps, float speed, long seed,
                       OrtSession.RunOptions run) throws OrtException {
        final OrtEnvironment env = env();
        final int textLen = ids.length;
        final float[] ones = new float[textLen];
        Arrays.fill(ones, 1f);
        final float seconds;
        final float[] textEmb;
        final int embChannels;
        try (OnnxTensor idT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] {1, textLen});
             OnnxTensor maskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(ones), new long[] {1, 1, textLen});
             OnnxTensor dpT = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dp), style.dpShape);
             OnnxTensor ttlT = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl), style.ttlShape)) {
            final Map<String, OnnxTensor> in = new HashMap<>();
            in.put("text_ids", idT);
            in.put("style_dp", dpT);
            in.put("text_mask", maskT);
            try (OrtSession.Result r = durations.run(in, run)) {
                seconds = floats(r.get(0))[0] / Math.max(0.1f, speed);
            }
            in.remove("style_dp");
            in.put("style_ttl", ttlT);
            try (OrtSession.Result r = encoder.run(in, run)) {
                textEmb = floats(r.get(0));
            }
            embChannels = textEmb.length / textLen;
        }
        final int samples = Math.max(frameSamples, (int) (seconds * sampleRate));
        final int frames = (samples + frameSamples - 1) / frameSamples;

        int bucket = -1;
        OrtSession flow = null;
        OrtSession vocoder = null;
        for (int b = 0; b < BUCKETS.length && bucket < 0; b++) {
            if (BUCKETS[b][0] >= frames && BUCKETS[b][1] >= textLen) {
                final OrtSession[] npu = npu(b);
                if (npu != null) {
                    bucket = b;
                    flow = npu[0];
                    vocoder = npu[1];
                }
            }
        }
        final int lenL = bucket >= 0 ? BUCKETS[bucket][0] : frames;
        final int lenT = bucket >= 0 ? BUCKETS[bucket][1] : textLen;
        if (bucket < 0) {
            flow = cpuFlow();
            vocoder = cpuVocoder();
        }

        final float[] emb = new float[embChannels * lenT];
        for (int c = 0; c < embChannels; c++) {
            System.arraycopy(textEmb, c * textLen, emb, c * lenT, textLen);
        }
        final float[] textMask = new float[lenT];
        Arrays.fill(textMask, 0, textLen, 1f);
        final float[] latentMask = new float[lenL];
        Arrays.fill(latentMask, 0, frames, 1f);
        final Random random = new Random(seed);
        float[] x = new float[latentChannels * lenL];
        for (int c = 0; c < latentChannels; c++) {
            for (int t = 0; t < frames; t++) {
                x[c * lenL + t] = (float) random.nextGaussian();
            }
        }
        try (OnnxTensor embT = OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), new long[] {1, embChannels, lenT});
             OnnxTensor ttlT = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl), style.ttlShape);
             OnnxTensor tmT = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMask), new long[] {1, 1, lenT});
             OnnxTensor lmT = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask), new long[] {1, 1, lenL});
             OnnxTensor totalT = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {steps}), new long[] {1})) {
            for (int step = 0; step < steps; step++) {
                final Map<String, OnnxTensor> in = new HashMap<>();
                try (OnnxTensor xT = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[] {1, latentChannels, lenL});
                     OnnxTensor stepT = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] {step}), new long[] {1})) {
                    in.put("noisy_latent", xT);
                    in.put("text_emb", embT);
                    in.put("style_ttl", ttlT);
                    in.put("text_mask", tmT);
                    in.put("latent_mask", lmT);
                    in.put("current_step", stepT);
                    in.put("total_step", totalT);
                    try (OrtSession.Result r = flow.run(in, run)) {
                        x = floats(r.get(0));
                    }
                }
            }
        }
        // Frames past the speech are zero, as the unpadded reference's edge.
        for (int c = 0; c < latentChannels; c++) {
            Arrays.fill(x, c * lenL + frames, (c + 1) * lenL, 0f);
        }
        final float[] wav;
        try (OnnxTensor latT = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[] {1, latentChannels, lenL})) {
            final Map<String, OnnxTensor> in = new HashMap<>();
            in.put("latent", latT);
            try (OrtSession.Result r = vocoder.run(in, run)) {
                wav = floats(r.get(0));
            }
        }
        return wav == null ? null : Arrays.copyOf(wav, Math.min(wav.length, samples));
    }

    private static float[] floats(OnnxValue value) {
        final FloatBuffer b = ((OnnxTensor) value).getFloatBuffer();
        final float[] out = new float[b.remaining()];
        b.get(out);
        return out;
    }

    private static OrtEnvironment env() {
        return OrtEnvironment.getEnvironment();
    }

    @Override
    public synchronized void close() {
        PiperModel.closeQuietly(durations);
        PiperModel.closeQuietly(encoder);
        for (int b = 0; b < BUCKETS.length; b++) {
            PiperModel.closeQuietly(npuFlow[b]);
            PiperModel.closeQuietly(npuVocoder[b]);
        }
        PiperModel.closeQuietly(cpuFlow);
        PiperModel.closeQuietly(cpuVocoder);
    }
}
