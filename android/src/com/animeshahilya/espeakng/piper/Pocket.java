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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Kyutai Pocket TTS English 18M (english_18m_refl_2026-10, CC BY 4.0) on the CPU: a
 * flow language model over Mimi codec latents, 12.5 frames a second of 24 kHz audio
 * (sherpa-onnx-respin-syspin build_pocket.py, release pocket-v1). Text goes in, as
 * Supertonic's and Phonon's do. Per chunk: the voice's KV cache (Kyutai's precomputed
 * state after its audio prompt, {@link Voice}) reads the text's tokens, then each step
 * makes one latent from noise (the "drifting" one-step sampler, inside the flow graph)
 * until the end-of-speech score passes the threshold, plus a few frames; Mimi's decoder
 * turns the chunk's latents into audio from a fresh state, as pocket-tts starts a fresh
 * decoder for every text chunk. Mirrors pocket-tts 4.0.1's generate_audio_stream; its
 * text preparation and SentencePiece tokenizer are ported ({@link #prepare},
 * {@link Tokenizer}), checked against pocket-tts' own output (PocketTest).
 */
final class Pocket implements Closeable {
    /** Companion files in the voice folder (model.onnx is the flow graph). */
    static final String DECODER = "decoder.onnx";
    static final String TOKENIZER = "tokenizer.json";
    static final String META = "pocket.json";
    static final String VOICE = "voice.bin";
    static final List<String> FILES = java.util.Arrays.asList(DECODER, TOKENIZER, META, VOICE);
    /** No rate input: libsonic does rate and pitch, as for Phonon. */
    static final float MAX_SPEED = 1f;

    final int sampleRate;
    final int frameSamples;
    final int layers;
    final int heads;
    final int headDim;
    final int latentDim;
    private final float temperature;
    private final float eosThreshold;
    private final int minFramesBeforeEos;
    private final float tokensPerSecond;
    private final float padSeconds;
    /** pocket.json's frames_after_eos, or -1: then the text's own guess (+2), as pocket-tts. */
    private final int framesAfterEos;
    private final Map<Integer, String> replace;
    private final boolean padShortInputs;
    private final boolean removeSemicolons;
    private final boolean appendTerminal;
    private final boolean capitalize;
    private final Tokenizer tokenizer;
    private final OrtSession flow;
    private final OrtSession decoder;

    private Pocket(JSONObject meta, Tokenizer tokenizer, OrtSession flow, OrtSession decoder) throws JSONException {
        sampleRate = meta.getInt("sample_rate");
        frameSamples = meta.getInt("frame_samples");
        layers = meta.getInt("layers");
        heads = meta.getInt("kv_heads");
        headDim = meta.getInt("head_dim");
        latentDim = meta.getInt("latent_dim");
        temperature = (float) meta.getDouble("temperature");
        eosThreshold = (float) meta.getDouble("eos_threshold");
        minFramesBeforeEos = meta.getInt("min_frames_before_eos");
        tokensPerSecond = (float) meta.getDouble("tokens_per_second");
        padSeconds = (float) meta.getDouble("gen_seconds_padding");
        framesAfterEos = meta.isNull("frames_after_eos") ? -1 : meta.getInt("frames_after_eos");
        replace = new HashMap<>();
        final JSONObject r = meta.optJSONObject("replace_characters");
        if (r != null) {
            for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) {
                final String k = it.next();
                if (k.codePointCount(0, k.length()) == 1) {
                    replace.put(k.codePointAt(0), r.getString(k));
                }
            }
        }
        padShortInputs = meta.optBoolean("pad_with_spaces_for_short_inputs", false);
        removeSemicolons = meta.optBoolean("remove_semicolons", false);
        appendTerminal = meta.optBoolean("append_terminal_punctuation", true);
        capitalize = meta.optBoolean("capitalize_first_letter", true);
        this.tokenizer = tokenizer;
        this.flow = flow;
        this.decoder = decoder;
    }

    /** Text preparation and tokens only (JVM tests): no sessions. */
    static Pocket forText(String metaJson, String tokenizerJson) throws JSONException {
        return new Pocket(new JSONObject(metaJson), Tokenizer.parse(tokenizerJson), null, null);
    }

    static Pocket load(File modelFile, int threads) throws OrtException {
        final File dir = modelFile.getParentFile();
        final JSONObject meta;
        final Tokenizer tokenizer;
        try {
            meta = new JSONObject(FileUtils.readText(new File(dir, META)));
            tokenizer = Tokenizer.parse(FileUtils.readText(new File(dir, TOKENIZER)));
        } catch (IOException | JSONException e) {
            throw new OrtException("Pocket files missing in " + dir + ": " + e);
        }
        OrtSession flow = null;
        try {
            flow = session(modelFile, threads);
            final OrtSession dec = session(new File(dir, DECODER), threads);
            return new Pocket(meta, tokenizer, flow, dec);
        } catch (OrtException | JSONException e) {
            PiperModel.closeQuietly(flow);
            throw e instanceof OrtException ? (OrtException) e : new OrtException(String.valueOf(e));
        }
    }

    private static OrtSession session(File model, int threads) throws OrtException {
        try (OrtSession.SessionOptions o = PiperModel.options(threads)) {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return env().createSession(model.getAbsolutePath(), o);
        }
    }

    private static OrtEnvironment env() {
        return OrtEnvironment.getEnvironment();
    }

    // ---- text ------------------------------------------------------------------------

    private static final String TERMINAL = ".!?…";
    private static final String WEAK = ",;:-–—";
    private static final String CLOSERS = "\"'”’)]»";
    private static final Pattern MARK_THEN_WEAK = Pattern.compile("([.!?…])\\s*[,;:]");

    /** pocket-tts' prepare_text_prompt: {prepared text, frames after EOS guess}. */
    Object[] prepare(String text) {
        String t = text.trim();
        if (!replace.isEmpty()) {
            final StringBuilder b = new StringBuilder(t.length());
            t.codePoints().forEach(c -> {
                final String r = replace.get(c);
                if (r != null) {
                    b.append(r);
                } else {
                    b.appendCodePoint(c);
                }
            });
            t = String.join(" ", pySplit(b.toString()));
            t = MARK_THEN_WEAK.matcher(t).replaceAll("$1");
        }
        if (t.isEmpty()) {
            return null;
        }
        t = t.replace('\n', ' ').replace('\r', ' ').replace("  ", " ");
        if (removeSemicolons) {
            t = t.replace(';', ',');
        }
        final int words = pySplit(t).size();
        final int guess = words <= 4 ? 3 : 1;
        if (capitalize) {
            final int first = t.codePointAt(0);
            if (!Character.isUpperCase(first)) {
                final int n = Character.charCount(first);
                t = t.substring(0, n).toUpperCase(java.util.Locale.ROOT) + t.substring(n);
            }
        }
        if (appendTerminal) {
            t = terminal(t);
        }
        if (padShortInputs && pySplit(t).size() < 5) {
            t = "        " + t;
        }
        return new Object[] {t, guess};
    }

    private static String terminal(String text) {
        int end = text.length();
        while (end > 0 && (CLOSERS.indexOf(text.charAt(end - 1)) >= 0 || text.charAt(end - 1) == ' ')) {
            end--;
        }
        final String core = text.substring(0, end);
        final String closers = text.substring(end).trim();
        if (core.isEmpty() || TERMINAL.indexOf(core.charAt(core.length() - 1)) >= 0) {
            return text;
        }
        if (WEAK.indexOf(core.charAt(core.length() - 1)) >= 0) {
            int e = core.length();
            while (e > 0 && (WEAK.indexOf(core.charAt(e - 1)) >= 0 || core.charAt(e - 1) == ' ')) {
                e--;
            }
            return core.substring(0, e) + "." + closers;
        }
        return text + ".";
    }

    /** Python's str.split(): runs of Unicode whitespace, no empty pieces. */
    static List<String> pySplit(String s) {
        final List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < s.length(); ) {
            final int c = s.codePointAt(i);
            final boolean space = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85
                    || (c >= 0x1c && c <= 0x1f);
            if (space) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
            i += Character.charCount(c);
        }
        if (start >= 0) {
            out.add(s.substring(start));
        }
        return out;
    }

    /**
     * A chunk's ids for the id pipeline: {frames after EOS, tokens...} (the first element
     * is the stop margin pocket-tts derives from the text's word count), or an empty array.
     */
    long[] ids(String text) {
        final Object[] p = prepare(text);
        if (p == null) {
            return new long[0];
        }
        final int[] tokens = tokenizer.encode((String) p[0]);
        final long[] out = new long[tokens.length + 1];
        out[0] = framesAfterEos >= 0 ? framesAfterEos : (Integer) p[1] + 2;
        for (int i = 0; i < tokens.length; i++) {
            out[i + 1] = tokens[i];
        }
        return out;
    }

    /** SentencePiece unigram, identity normalizer, dummy prefix, byte fallback (the 18M tokenizer). */
    static final class Tokenizer {
        private static final String SPACE = "▁";
        private static final float UNK_PENALTY = 10f;
        private final Map<String, Integer> pieces = new HashMap<>();
        private final float[] scores;
        private final int[] byteIds = new int[256];
        private final int unkId;
        private final float minScore;
        private final int maxLen;

        private Tokenizer(JSONArray list) throws JSONException {
            scores = new float[list.length()];
            int unk = 0;
            float min = Float.MAX_VALUE;
            int longest = 1;
            for (int id = 0; id < list.length(); id++) {
                final JSONArray p = list.getJSONArray(id);
                final String piece = p.getString(0);
                final int type = p.getInt(2);
                scores[id] = (float) p.getDouble(1);
                if (type == 1) { // normal
                    pieces.put(piece, id);
                    min = Math.min(min, scores[id]);
                    longest = Math.max(longest, piece.length());
                } else if (type == 2) {
                    unk = id;
                } else if (type == 6 && piece.length() == 6 && piece.startsWith("<0x")) {
                    byteIds[Integer.parseInt(piece.substring(3, 5), 16)] = id;
                }
            }
            unkId = unk;
            minScore = min;
            maxLen = longest;
        }

        static Tokenizer parse(String json) throws JSONException {
            return new Tokenizer(new JSONObject(json).getJSONArray("pieces"));
        }

        int[] encode(String text) {
            final String s = SPACE + text.replace(" ", SPACE);
            final int n = s.length();
            final float[] best = new float[n + 1];
            final int[] from = new int[n + 1];
            final int[] piece = new int[n + 1];
            java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
            best[0] = 0;
            for (int i = 0; i < n; ) {
                final int cpLen = Character.charCount(s.codePointAt(i));
                if (best[i] != Float.NEGATIVE_INFINITY) {
                    boolean single = false;
                    for (int len = 1; len <= maxLen && i + len <= n; len++) {
                        final Integer id = pieces.get(s.substring(i, i + len));
                        if (id == null) {
                            continue;
                        }
                        if (len == cpLen) {
                            single = true;
                        }
                        final float score = best[i] + scores[id];
                        if (score > best[i + len]) {
                            best[i + len] = score;
                            from[i + len] = i;
                            piece[i + len] = id;
                        }
                    }
                    if (!single) { // no piece for this character: unknown, then its UTF-8 bytes
                        final float score = best[i] + minScore - UNK_PENALTY;
                        if (score > best[i + cpLen]) {
                            best[i + cpLen] = score;
                            from[i + cpLen] = i;
                            piece[i + cpLen] = -1;
                        }
                    }
                }
                i += cpLen;
            }
            final List<Integer> rev = new ArrayList<>();
            for (int end = n; end > 0; end = from[end]) {
                if (piece[end] >= 0) {
                    rev.add(piece[end]);
                } else {
                    final byte[] bytes = s.substring(from[end], end).getBytes(StandardCharsets.UTF_8);
                    for (int b = bytes.length - 1; b >= 0; b--) {
                        final int id = byteIds[bytes[b] & 0xff];
                        rev.add(id != 0 ? id : unkId);
                    }
                }
            }
            final int[] out = new int[rev.size()];
            for (int k = 0; k < out.length; k++) {
                out[k] = rev.get(out.length - 1 - k);
            }
            return out;
        }
    }

    // ---- voices ----------------------------------------------------------------------

    /** A voice: the flow LM's KV cache after its audio prompt, [layers, 2, 1, positions, heads, headDim]. */
    static final class Voice {
        final float[] cache;
        final int positions;

        private Voice(float[] cache, int positions) {
            this.cache = cache;
            this.positions = positions;
        }

        /** voice.bin beside the voice's model.onnx: float16, little-endian. */
        static Voice load(File voiceDir, Pocket p) throws IOException {
            final File f = new File(voiceDir, VOICE);
            final long perPosition = 2L * p.layers * p.heads * p.headDim;
            if (f.length() == 0 || f.length() % (perPosition * 2) != 0) {
                throw new IOException("bad Pocket voice " + f + " (" + f.length() + " bytes)");
            }
            final int count = (int) (f.length() / 2);
            final ByteBuffer b = ByteBuffer.allocate((int) f.length()).order(ByteOrder.LITTLE_ENDIAN);
            try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                in.readFully(b.array());
            }
            final float[] cache = new float[count];
            for (int i = 0; i < count; i++) {
                cache[i] = halfToFloat(b.getShort(i * 2));
            }
            return new Voice(cache, (int) (count / perPosition));
        }
    }

    static float halfToFloat(short h) {
        final int bits = h & 0xffff;
        final int sign = (bits & 0x8000) << 16;
        int exp = (bits >>> 10) & 0x1f;
        int mant = bits & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            while ((mant & 0x400) == 0) { // subnormal: normalize
                mant <<= 1;
                exp--;
            }
            exp++;
            mant &= 0x3ff;
        } else if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
    }

    // ---- generation --------------------------------------------------------------------

    /**
     * One chunk ({@link #ids}) in the voice to audio at {@link #sampleRate}; noise from
     * {@code seed}, so the same chunk reads the same way each time (the phrase cache relies
     * on it). Null when cancelled between steps.
     */
    float[] synthesize(long[] ids, Voice voice, long seed, OrtSession.RunOptions run,
                       java.util.function.BooleanSupplier cancelled) throws OrtException {
        if (ids.length < 2) {
            return new float[0];
        }
        final int stopAfter = (int) ids[0];
        final long[] tokens = java.util.Arrays.copyOfRange(ids, 1, ids.length);
        final OrtEnvironment env = env();
        final long[] cacheShape = {layers, 2, 1, voice.positions, heads, headDim};
        final int maxLen = (int) Math.ceil((tokens.length / tokensPerSecond + padSeconds) * 12.5f);
        final List<float[]> latents = new ArrayList<>();
        final Random rng = new Random(seed);
        final float std = (float) Math.sqrt(temperature);
        OrtSession.Result previous = null;
        try (OnnxTensor past0 = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.cache), cacheShape);
             OnnxTensor text = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), new long[] {1, tokens.length});
             OnnxTensor noText = OnnxTensor.createTensor(env, LongBuffer.allocate(0), new long[] {1, 0});
             OnnxTensor noLatent = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[] {1, 0, latentDim});
             OnnxTensor zeroNoise = OnnxTensor.createTensor(env, FloatBuffer.allocate(latentDim), new long[] {1, latentDim})) {
            previous = step(text, noLatent, zeroNoise, past0, voice.positions, run);
            float[] latent = new float[latentDim];
            java.util.Arrays.fill(latent, Float.NaN);
            int eosStep = -1;
            for (int i = 0; i < maxLen; i++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                final float[] noise = new float[latentDim];
                for (int k = 0; k < latentDim; k++) {
                    noise[k] = (float) rng.nextGaussian() * std;
                }
                final OrtSession.Result r;
                final OnnxTensor past = (OnnxTensor) previous.get("present").get();
                final long positions = past.getInfo().getShape()[3];
                try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), new long[] {1, 1, latentDim});
                     OnnxTensor nz = OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), new long[] {1, latentDim})) {
                    r = step(noText, in, nz, past, positions, run);
                }
                previous.close();
                previous = r;
                final float eos = floats(r.get("eos").get())[0];
                if (eos > eosThreshold && eosStep < 0 && i >= minFramesBeforeEos) {
                    eosStep = i;
                }
                if (eosStep >= 0 && i >= eosStep + stopAfter) {
                    break;
                }
                latent = floats(r.get("next").get());
                latents.add(latent);
            }
        } finally {
            if (previous != null) {
                previous.close();
            }
        }
        if (latents.isEmpty() || cancelled.getAsBoolean()) {
            return latents.isEmpty() ? new float[0] : null;
        }
        final float[] all = new float[latents.size() * latentDim];
        for (int i = 0; i < latents.size(); i++) {
            System.arraycopy(latents.get(i), 0, all, i * latentDim, latentDim);
        }
        final float[] audio;
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(all), new long[] {1, latents.size(), latentDim});
             OrtSession.Result r = decoder.run(java.util.Collections.singletonMap("latents", in), run)) {
            audio = floats(r.get(0));
        }
        // A fresh Mimi state puts a small step in its first samples: pocket-tts fades 5 ms in.
        final int fade = Math.min(audio.length, sampleRate / 200);
        for (int i = 0; i < fade; i++) {
            audio[i] *= fade > 1 ? i / (float) (fade - 1) : 1f;
        }
        return audio;
    }

    private OrtSession.Result step(OnnxTensor tokens, OnnxTensor latent, OnnxTensor noise, OnnxTensor past,
                                   long positions, OrtSession.RunOptions run) throws OrtException {
        try (OnnxTensor offset = OnnxTensor.createTensor(env(), LongBuffer.wrap(new long[] {positions}), new long[] {1})) {
            final Map<String, OnnxTensor> in = new HashMap<>();
            in.put("tokens", tokens);
            in.put("latent", latent);
            in.put("noise", noise);
            in.put("past", past);
            in.put("offset", offset);
            return flow.run(in, run);
        }
    }

    private static float[] floats(OnnxValue value) {
        final FloatBuffer b = ((OnnxTensor) value).getFloatBuffer();
        final float[] out = new float[b.remaining()];
        b.get(out);
        return out;
    }

    @Override
    public void close() {
        PiperModel.closeQuietly(flow);
        PiperModel.closeQuietly(decoder);
    }
}
