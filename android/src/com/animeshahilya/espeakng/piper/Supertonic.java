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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * ~180 MB, so two.
     */
    static final int[][] BUCKETS = {{96, 144}, {192, 288}};
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
        return textIds(preprocess(text, lang), indexer);
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
    /** Per bucket; null where its NPU graph is not available. */
    private final OrtSession[] npuFlow;
    private final OrtSession[] npuVocoder;
    private final File dir;
    private final int threads;
    private OrtSession cpuFlow;
    private OrtSession cpuVocoder;
    /** Why the NPU is not used, or null. */
    final String npuProblem;

    private Supertonic(File dir, int threads, JSONObject cfg, int[] indexer, OrtSession durations,
                       OrtSession encoder, OrtSession[] npuFlow, OrtSession[] npuVocoder, String npuProblem)
            throws JSONException {
        this.dir = dir;
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
        this.npuProblem = npuProblem;
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
                if (!PiperModel.hasNpuRuntime()) {
                    problem = "no NPU runtime";
                } else {
                    for (int b = 0; b < BUCKETS.length; b++) {
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
            return new Supertonic(dir, threads, cfg, indexer, dp, te, flow, voc, problem);
        } catch (IOException | JSONException e) {
            throw new OrtException("Supertonic files missing in " + dir + ": " + e);
        }
    }

    private static OrtSession cpuSession(File model, int threads) throws OrtException {
        try (OrtSession.SessionOptions o = PiperModel.options(threads)) {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return env().createSession(model.getAbsolutePath(), o);
        }
    }

    /**
     * A bucket's NPU graph: lengths pinned (ONNX Runtime then folds the
     * shape arithmetic before QNN sees the graph), INT8 weights widened
     * (session.disable_quant_qdq), all on the NPU or nothing. Compiled once
     * into the shared store, next to the shared model.
     */
    private static OrtSession npuSession(File model, int[] bucket, String what) throws OrtException, IOException {
        final File compiled = PiperModel.sharedDerivedFile(model,
                ".st-" + what + bucket[0] + ".ctx.onnx");
        if (compiled == null) {
            throw new IOException("no shared store for " + model);
        }
        try (OrtSession.SessionOptions o = PiperModel.options(1)) {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            o.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            o.addConfigEntry("session.disable_quant_qdq", "1");
            final Map<String, String> qnn = new HashMap<>();
            qnn.put("backend_path", PiperModel.npuBackend());
            qnn.put("enable_htp_fp16_precision", "1");
            qnn.put("htp_performance_mode", "burst");
            PiperModel.addNpu(o, qnn);
            if (compiled.isFile()) {
                try {
                    return env().createSession(compiled.getAbsolutePath(), o);
                } catch (OrtException e) {
                    //noinspection ResultOfMethodCallIgnored
                    compiled.delete(); // stale (runtime update) or damaged: compiled again below
                }
            }
            o.setSymbolicDimensionValue("batch_size", 1);
            o.setSymbolicDimensionValue("latent_length", bucket[0]);
            o.setSymbolicDimensionValue("text_length", bucket[1]);
            o.addConfigEntry("ep.context_enable", "1");
            o.addConfigEntry("ep.context_embed_mode", "1");
            o.addConfigEntry("ep.context_file_path", compiled.getAbsolutePath());
            final OrtSession s = env().createSession(model.getAbsolutePath(), o);
            android.util.Log.i("PiperVoices", "Supertonic " + what + " " + bucket[0] + " compiled for the NPU");
            return s;
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

    /** True when every bucket has its NPU graph. */
    boolean onNpu() {
        for (OrtSession s : npuFlow) {
            if (s == null) {
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
        for (int b = 0; b < BUCKETS.length; b++) {
            if (npuFlow[b] != null && BUCKETS[b][0] >= frames && BUCKETS[b][1] >= textLen) {
                bucket = b;
                break;
            }
        }
        final int lenL = bucket >= 0 ? BUCKETS[bucket][0] : frames;
        final int lenT = bucket >= 0 ? BUCKETS[bucket][1] : textLen;
        final OrtSession flow = bucket >= 0 ? npuFlow[bucket] : cpuFlow();
        final OrtSession vocoder = bucket >= 0 ? npuVocoder[bucket] : cpuVocoder();

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
