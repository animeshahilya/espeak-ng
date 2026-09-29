package com.animeshahilya.espeakng;

import android.content.Context;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Model-loading and ONNX Runtime benchmark for Piper voices, and a phoneme
 * coverage check. Not pass/fail: read "PiperBench" from logcat. Skipped
 * without models. Put KEY.onnx + KEY.onnx.json in the app's files/piper-bench
 * (voice configs alone in files/piper-cfg for the coverage check) - adb push
 * to /data/local/tmp, then "run-as com.animeshahilya.espeakng cp" them in; a
 * folder adb creates in the external files dir is not readable by the app.
 * Measure with the phone cool: repeat runs drift 30%+ once it heats up.
 */
@RunWith(AndroidJUnit4.class)
public class PiperBenchDeviceTest {
    private static final String TAG = "PiperBench";

    private static final String EN = "The quick brown fox jumps over the lazy dog. "
            + "Natural voices should start speaking quickly, and keep going without gaps, "
            + "even when a screen reader reads a long paragraph like this one.";
    private static final String HI = "नमस्ते, आज मौसम बहुत अच्छा है। "
            + "हम सब मिलकर बाज़ार जाएँगे और ताज़ी सब्ज़ियाँ ख़रीदेंगे, फिर घर लौटकर खाना बनाएँगे।";

    interface Tuning {
        void apply(OrtSession.SessionOptions o) throws Exception;
    }

    @Test
    public void benchmark() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File dir = new File(ctx.getFilesDir(), "piper-bench");
        copyInstalledVoices(ctx, dir);
        final File[] models = dir.listFiles((d, n) -> n.endsWith(".onnx") && !n.contains(".opt."));
        Assume.assumeTrue("no models in " + dir, models != null && models.length > 0);

        CheckVoiceData.ensureVoiceData(ctx);
        final SpeechSynthesis engine = new SpeechSynthesis(ctx, new SpeechSynthesis.SynthReadyCallback() {
            @Override public void onSynthDataReady(byte[] audioData) { }
            @Override public void onSynthDataComplete() { }
            @Override public void onSynthWordBoundary(int a, int b, int c) { }
        });

        final Map<String, Tuning> tunings = new java.util.LinkedHashMap<>();
        for (File onnx : models) {
            final String key = onnx.getName().replace(".onnx", "");
            final Config config = new Config(new JSONObject(new String(
                    Files.readAllBytes(new File(onnx.getPath() + ".json").toPath()), StandardCharsets.UTF_8)));
            final String text = config.espeakVoice.startsWith("hi") ? HI : EN;
            final List<long[]> inputs = new ArrayList<>();
            final List<String> missing = new ArrayList<>();
            for (String record : engine.phonemizeForPiper(config.espeakVoice, text).split("")) {
                final String[] f = record.split("");
                if (f.length == 4) {
                    inputs.add(config.ids(f[3], missing));
                }
            }
            Log.i(TAG, key + " clauses=" + inputs.size() + " missing=" + missing);

            // Load strategies: the app's optimized .onnx today, vs the same
            // saved in ORT format and run memory-mapped (weights in place).
            final File opt = new File(dir, key + ".opt.onnx");
            final File ort = new File(dir, key + ".opt.ort");
            opt.delete();
            ort.delete();
            run(key, "write-ort", o -> {
                base(o, 4);
                o.setOptimizedModelFilePath(ort.getAbsolutePath());
                o.addConfigEntry("session.save_model_format", "ORT");
            }, onnx.getAbsolutePath(), null, config, inputs, true);
            for (int round = 0; round < 3; round++) {
                // The app today: ORT format, memory-mapped, weights used in
                // place, arena shrunk after every run.
                Thread.sleep(8000); // let the SoC cool between measurements
                run(key, "app", o -> {
                    base(o, 4);
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    o.addConfigEntry("session.use_ort_model_bytes_directly", "1");
                    o.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
                }, null, ort, config, inputs, true);
                Thread.sleep(8000);
                run(key, "no-shrink", o -> {
                    base(o, 4);
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    o.addConfigEntry("session.use_ort_model_bytes_directly", "1");
                    o.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
                }, null, ort, config, inputs, false);
                // Mapped file, but initializers copied: ORT may pre-pack them.
                Thread.sleep(8000);
                run(key, "mmap-copy", o -> {
                    base(o, 4);
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    o.addConfigEntry("session.use_ort_model_bytes_directly", "1");
                }, null, ort, config, inputs, true);
                // Loaded into memory from the path (no mapping at all).
                Thread.sleep(8000);
                run(key, "in-memory", o -> {
                    base(o, 4);
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                }, ort.getAbsolutePath(), null, config, inputs, true);
            }
        }
    }

    /**
     * With nothing pushed, copies up to two of the phone's downloaded voices
     * (device-protected files/piper/voices/KEY/model.onnx{,.json}; read
     * only) into the bench folder, preferring Hindi and English.
     */
    private static void copyInstalledVoices(Context ctx, File dir) throws Exception {
        final File[] existing = dir.listFiles((d, n) -> n.endsWith(".onnx") && !n.contains(".opt."));
        if (existing != null && existing.length > 0) {
            return;
        }
        final File voices = new File(ctx.createDeviceProtectedStorageContext().getFilesDir(),
                "piper/voices");
        final File[] keys = voices.listFiles(File::isDirectory);
        if (keys == null) {
            return;
        }
        java.util.Arrays.sort(keys, (a, b) -> rank(a.getName()) - rank(b.getName()));
        dir.mkdirs();
        int copied = 0;
        for (File k : keys) {
            final File model = new File(k, "model.onnx");
            final File json = new File(k, "model.onnx.json");
            if (copied < 2 && model.isFile() && json.isFile()) {
                Files.copy(model.toPath(), new File(dir, k.getName() + ".onnx").toPath());
                Files.copy(json.toPath(), new File(dir, k.getName() + ".onnx.json").toPath());
                Log.i(TAG, "copied installed voice " + k.getName());
                copied++;
            }
        }
    }

    private static int rank(String key) {
        return key.startsWith("hi_") ? 0 : key.startsWith("en_") ? 1 : 2;
    }

    /** The few .onnx.json fields inference needs, and Piper's phonemes_to_ids(). */
    private static final class Config {
        final String espeakVoice;
        final int sampleRate;
        final float noiseScale;
        final float lengthScale;
        final float noiseW;
        final Map<String, Integer> map = new HashMap<>();

        Config(JSONObject j) throws Exception {
            espeakVoice = j.getJSONObject("espeak").getString("voice");
            sampleRate = j.getJSONObject("audio").getInt("sample_rate");
            final JSONObject inf = j.getJSONObject("inference");
            noiseScale = (float) inf.getDouble("noise_scale");
            lengthScale = (float) inf.getDouble("length_scale");
            noiseW = (float) inf.getDouble("noise_w");
            final JSONObject m = j.getJSONObject("phoneme_id_map");
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                final String k = it.next();
                map.put(k, m.getJSONArray(k).getInt(0));
            }
        }

        long[] ids(String ipa, List<String> missing) {
            final String nfd = java.text.Normalizer.normalize(ipa, java.text.Normalizer.Form.NFD);
            final List<Long> out = new ArrayList<>();
            out.add((long) map.get("^"));
            out.add((long) map.get("_"));
            for (int i = 0; i < nfd.length(); ) {
                final int cp = nfd.codePointAt(i);
                final String ph = new String(Character.toChars(cp));
                i += Character.charCount(cp);
                final Integer id = map.get(ph);
                if (id == null) {
                    if (!missing.contains(ph)) {
                        missing.add(ph);
                    }
                    continue;
                }
                out.add((long) id);
                out.add((long) map.get("_"));
            }
            out.add((long) map.get("$"));
            final long[] a = new long[out.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = out.get(i);
            }
            return a;
        }
    }

    private static final Map<String, String> CORPUS = new HashMap<>();
    static {
        CORPUS.put("en-us", EN + " Yes, it's 3:45 PM; I'll email Dr. Rao about the 2nd invoice (₹1,250). "
                + "Priyanka's thought: judge the measure, zoo, vision, church, sing, pure, boy, cow, hair.");
        CORPUS.put("en-gb-x-rp", CORPUS.get("en-us"));
        CORPUS.put("hi", HI + " मैंने कल office में meeting के बाद email भेजा। क्या आप WhatsApp पर हैं? "
                + "ऋषि, क्षमा, ज्ञान, श्रद्धा, ड़, ढ़, फ़ोन, ज़रूर, ग़लत, ख़ुश, 25 रुपये, 3:30 बजे।");
        CORPUS.put("ur", "آپ کیسے ہیں؟ میں نے کل دفتر میں meeting کے بعد email بھیجا۔ خوش، غلط، ژالہ، 25 روپے۔");
        CORPUS.put("ne", "नमस्ते, तपाईंलाई कस्तो छ? मैले हिजो office मा email पठाएँ। ऋषि, क्षमा, ज्ञान, २५ रुपैयाँ।");
        CORPUS.put("te", "నమస్కారం, మీరు ఎలా ఉన్నారు? నేను నిన్న office లో email పంపాను. ఋషి, క్షమ, జ్ఞానం, 25 రూపాయలు.");
        CORPUS.put("ml", "നമസ്കാരം, സുഖമാണോ? ഞാൻ ഇന്നലെ office ൽ email അയച്ചു. ഋഷി, ക്ഷമ, ജ്ഞാനം, ഴ, റ്റ, 25 രൂപ.");
        CORPUS.put("bn", "নমস্কার, আপনি কেমন আছেন? আমি গতকাল office এ email পাঠিয়েছি। ঋষি, ক্ষমা, জ্ঞান, ২৫ টাকা।");
    }

    /** Which phonemes this fork's eSpeak produces that each voice has no id for. */
    @Test
    public void phonemeCoverage() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File[] configs = new File(ctx.getFilesDir(), "piper-cfg").listFiles((d, n) -> n.endsWith(".json"));
        Assume.assumeTrue(configs != null && configs.length > 0);
        CheckVoiceData.ensureVoiceData(ctx);
        final SpeechSynthesis engine = new SpeechSynthesis(ctx, new SpeechSynthesis.SynthReadyCallback() {
            @Override public void onSynthDataReady(byte[] audioData) { }
            @Override public void onSynthDataComplete() { }
            @Override public void onSynthWordBoundary(int a, int b, int c) { }
        });
        java.util.Arrays.sort(configs);
        for (File f : configs) {
            final Config config = new Config(new JSONObject(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            final String text = CORPUS.get(config.espeakVoice);
            if (text == null) {
                continue;
            }
            final String raw = engine.phonemizeForPiper(config.espeakVoice, text);
            final List<String> missing = new ArrayList<>();
            final StringBuilder ipa = new StringBuilder();
            for (String record : raw.split("")) {
                final String[] parts = record.split("");
                if (parts.length == 4) {
                    config.ids(parts[3], missing);
                    ipa.append(parts[3]);
                }
            }
            final StringBuilder named = new StringBuilder();
            for (String m : missing) {
                named.append(m).append(String.format(Locale.ROOT, "(U+%04X) ", m.codePointAt(0)));
            }
            Log.i(TAG, "COVERAGE " + f.getName() + " [" + config.espeakVoice + "] missing: " + named);
            Log.i(TAG, "IPA " + config.espeakVoice + ": " + ipa);
        }
    }

    private static void base(OrtSession.SessionOptions o, int threads) throws Exception {
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        o.setIntraOpNumThreads(threads);
        o.setInterOpNumThreads(1);
        o.setMemoryPatternOptimization(false);
    }

    private static void run(String key, String name, Tuning tuning, String path, File mapped,
                            Config config, List<long[]> inputs, boolean shrink) throws Exception {
        System.gc();
        final long heap0 = Debug.getNativeHeapAllocatedSize();
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        final long t0 = System.nanoTime();
        OrtSession session;
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            tuning.apply(o);
            if (mapped != null) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mapped, "r")) {
                    session = env.createSession(raf.getChannel().map(
                            java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length()), o);
                }
            } else {
                session = env.createSession(path, o);
            }
        } catch (Exception e) {
            Log.w(TAG, key + " " + name + " FAILED to load: " + e);
            return;
        }
        final long loadMs = (System.nanoTime() - t0) / 1_000_000;
        try {
            runTimed(key, name, session, env, config, inputs, shrink, loadMs, heap0);
        } catch (Exception e) {
            Log.w(TAG, key + " " + name + " FAILED to run after load=" + loadMs + "ms: " + e.getMessage());
        } finally {
            session.close();
        }
    }

    private static void runTimed(String key, String name, OrtSession session, OrtEnvironment env,
                                 Config config, List<long[]> inputs, boolean shrink, long loadMs,
                                 long heap0) throws Exception {
        {
            // First run on the first clause = time to first sound after load.
            final long f0 = System.nanoTime();
            infer(session, env, inputs.get(0), config, shrink);
            final long firstMs = (System.nanoTime() - f0) / 1_000_000;
            final long f1 = System.nanoTime();
            final int firstAgainSamples = infer(session, env, inputs.get(0), config, shrink);
            final long firstWarmMs = (System.nanoTime() - f1) / 1_000_000;
            long inferNs = 0;
            long samples = 0;
            for (int round = 0; round < 3; round++) {
                for (long[] ids : inputs) {
                    final long s = System.nanoTime();
                    samples += infer(session, env, ids, config, shrink);
                    inferNs += System.nanoTime() - s;
                }
            }
            final double audioMs = samples * 1000.0 / config.sampleRate;
            final long heap = (Debug.getNativeHeapAllocatedSize() - heap0) / (1024 * 1024);
            Log.i(TAG, String.format(Locale.ROOT,
                    "%s %-12s load=%5dms first=%4dms firstWarm=%4dms (%.2fs audio) RTF=%.3f (x%.1f) heap=+%dMB",
                    key, name, loadMs, firstMs, firstWarmMs, firstAgainSamples / (double) config.sampleRate,
                    inferNs / 1e6 / audioMs, audioMs / (inferNs / 1e6), heap));
        }
    }

    private static int infer(OrtSession session, OrtEnvironment env, long[] ids,
                             Config config, boolean shrink) throws Exception {
        final Map<String, OnnxTensor> in = new HashMap<>();
        try {
            in.put("input", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length}));
            in.put("input_lengths", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{ids.length}), new long[]{1}));
            in.put("scales", OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{
                    config.noiseScale, config.lengthScale, config.noiseW}), new long[]{3}));
            if (session.getInputNames().contains("sid")) {
                in.put("sid", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{0}), new long[]{1}));
            }
            try (OrtSession.RunOptions ro = new OrtSession.RunOptions()) {
                if (shrink) {
                    ro.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0");
                }
                try (OrtSession.Result r = session.run(in, ro)) {
                return ((OnnxTensor) r.get(0)).getFloatBuffer().remaining();
                }
            }
        } finally {
            for (OnnxTensor t : in.values()) {
                t.close();
            }
        }
    }
}
