package com.animeshahilya.espeakng;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Arabic vowel marks (tashkeel) for natural voices trained on vowelled
 * text: a Java port of piper-phonemize's tashkeel.cpp (libtashkeel, MIT),
 * the step Piper itself runs before eSpeak for Arabic. Unvowelled Arabic
 * leaves eSpeak to guess the short vowels; Whisper large-v3 on Kareem's
 * whole corpus: 16.6% -> 9.7% character errors with the marks.
 *
 * The model file is fetched beside the voice that uses it
 * ({@link PiperDownloads#fetchVoiceExtras}); without it, text goes through
 * unchanged.
 */
final class Tashkeel {
    static final String MODEL_FILE = "tashkeel.ort";
    /** rhasspy/piper-phonemize (archived, MIT) at its last commit. */
    static final String MODEL_URL = "https://raw.githubusercontent.com/rhasspy/piper-phonemize/"
            + "ba3cc06c5248215928821f1393b2b854a936991a/etc/libtashkeel_model.ort";
    static final String MODEL_MD5 = "f0f21a44d226cd86430ddeaecea13125";

    /** The model reads a fixed window of characters. */
    private static final int MAX_CHARS = 315;
    private static final int PAD_ID = 0;
    private static final int UNK_ID = 1;
    private static final int INVALID_ID = 8;

    private static final Map<Integer, Integer> INPUT = new HashMap<>();
    private static final String[] OUTPUT = new String[28];

    static {
        final int[] vocab = {
                0x0009, 8, 0x0020, 28, 0x00a0, 84, 0x00ab, 74, 0x00ad, 40, 0x00b0, 5, 0x00b4, 110,
                0x00bb, 30, 0x03ad, 69, 0x03af, 112, 0x03b1, 47, 0x03b3, 80, 0x03b5, 7, 0x03b8, 51,
                0x03b9, 36, 0x03ba, 35, 0x03bc, 54, 0x03bd, 63, 0x03bf, 114, 0x03c0, 116, 0x03c1, 26,
                0x03c3, 27, 0x03c4, 78, 0x03c5, 20, 0x03c7, 14, 0x03c8, 12, 0x03c9, 89, 0x03cc, 77,
                0x03ce, 103, 0x05d5, 64, 0x061b, 17, 0x061f, 101, 0x0621, 120, 0x0622, 15, 0x0623, 73,
                0x0624, 50, 0x0625, 119, 0x0626, 56, 0x0627, 68, 0x0628, 118, 0x0629, 107, 0x062a, 22,
                0x062b, 71, 0x062c, 59, 0x062d, 86, 0x062e, 19, 0x062f, 104, 0x0630, 97, 0x0631, 65,
                0x0632, 92, 0x0633, 82, 0x0634, 18, 0x0635, 75, 0x0636, 111, 0x0637, 93, 0x0638, 11,
                0x0639, 95, 0x063a, 24, 0x0640, 9, 0x0641, 46, 0x0642, 38, 0x0643, 72, 0x0644, 29,
                0x0645, 48, 0x0646, 81, 0x0647, 49, 0x0648, 6, 0x0649, 39, 0x064a, 70, 0x066a, 91,
                0x0670, 45, 0x0671, 67, 0x06cc, 105, 0x06d2, 37, 0x06f5, 109, 0x06f7, 106, 0x06f8, 10,
                0x200b, 52, 0x200d, 31, 0x200e, 117, 0x200f, 60, 0x2013, 42, 0x2018, 34, 0x2019, 41,
                0x201c, 55, 0x201d, 85, 0x2022, 62, 0x2026, 23, 0x202b, 94, 0x202c, 108, 0x2030, 115,
                0xfb90, 53, 0xfd3e, 44, 0xfd3f, 25, 0xfe81, 16, 0xfe82, 96, 0xfe83, 87, 0xfe84, 61,
                0xfe87, 57, 0xfe88, 58, 0xfe8b, 100, 0xfe8c, 90, 0xfe91, 32, 0xfe92, 113, 0xfe94, 76,
                0xfed3, 33, 0xfedb, 13, 0xfedf, 99, 0xfee0, 66, 0xfee3, 43, 0xfee7, 102, 0xfef4, 88,
                0xfef5, 83, 0xfef7, 98, 0xfef9, 21, 0xfefb, 79};
        for (int i = 0; i < vocab.length; i += 2) {
            INPUT.put(vocab[i], vocab[i + 1]);
        }
        final String[] out = {
                "4", "ـ", "5", "َ", "6", "ُّ", "7", "َّ",
                "9", "ِّ", "10", "ّ", "11", "ّْ", "12", "ٍّ",
                "13", "ِّ", "14", "ٍّ", "15", "ٌّ", "16", "َّ",
                "17", "ُ", "18", "ٌّ", "19", "ًّ", "20", "ْ",
                "21", "ٍ", "22", "ِ", "23", "ُّ", "24", "ًّ",
                "25", "ٌ", "26", "ً", "27", "ّّ"};
        for (int i = 0; i < out.length; i += 2) {
            OUTPUT[Integer.parseInt(out[i])] = out[i + 1];
        }
    }

    private static final Map<String, OrtSession> sSessions = new HashMap<>();

    private Tashkeel() {
    }

    /** True if {@code text} already carries vowel marks (the writer's own: keep them). */
    static boolean isVowelled(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c >= 'ً' && c <= 'ْ') {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code text} with vowel marks added, and for each code point of the
     * result the code point of {@code text} it belongs to (a mark belongs to
     * its letter), so word ranges stay on the caller's text.
     *
     * @return null when there is no model beside {@code voiceDir}, the text
     *         is already vowelled, or the model fails
     */
    static Result apply(File voiceDir, String text) {
        if (isVowelled(text)) {
            return null;
        }
        final File model = new File(voiceDir, MODEL_FILE);
        if (!model.isFile()) {
            return null;
        }
        try {
            final OrtSession session = session(model);
            final int[] cps = text.codePoints().toArray();
            final StringBuilder out = new StringBuilder(text.length() * 2);
            final int[] map = new int[cps.length * 3 + 1];
            int n = 0;
            for (int start = 0; start < cps.length; ) {
                final int end = windowEnd(cps, start);
                final String[] marks = run(session, cps, start, end);
                for (int i = start; i < end; i++) {
                    out.appendCodePoint(cps[i]);
                    map[n++] = i;
                    final String m = marks[i - start];
                    if (m != null) {
                        out.append(m);
                        for (int k = 0; k < m.length(); k++) {
                            map[n++] = i;
                        }
                    }
                }
                start = end;
            }
            map[n] = cps.length;
            return new Result(out.toString(), java.util.Arrays.copyOf(map, n + 1));
        } catch (OrtException | RuntimeException e) {
            android.util.Log.w("PiperVoices", "Tashkeel failed; reading unvowelled", e);
            return null;
        }
    }

    /** A window of at most MAX_CHARS ending after a space where possible. */
    static int windowEnd(int[] cps, int start) {
        final int limit = Math.min(cps.length, start + MAX_CHARS);
        if (limit == cps.length) {
            return limit;
        }
        for (int i = limit; i > start + MAX_CHARS / 2; i--) {
            if (cps[i - 1] == ' ' || cps[i - 1] == '\n') {
                return i;
            }
        }
        return limit;
    }

    private static String[] run(OrtSession session, int[] cps, int start, int end) throws OrtException {
        final float[] ids = new float[MAX_CHARS];
        for (int i = start; i < end; i++) {
            final Integer id = INPUT.get(cps[i]);
            ids[i - start] = id != null ? id : UNK_ID;
        }
        for (int i = end - start; i < MAX_CHARS; i++) {
            ids[i] = PAD_ID;
        }
        final OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(ids), new long[]{1, MAX_CHARS});
             OrtSession.Result result = session.run(java.util.Collections.singletonMap("embedding_7_input", input))) {
            final float[][] probs = ((float[][][]) result.get(0).getValue())[0];
            final String[] marks = new String[end - start];
            for (int i = 0; i < marks.length && i < probs.length; i++) {
                int best = 0;
                for (int j = 1; j < probs[i].length; j++) {
                    if (probs[i][j] > probs[i][best]) {
                        best = j;
                    }
                }
                if (best != UNK_ID && best != INVALID_ID && best < OUTPUT.length) {
                    marks[i] = OUTPUT[best];
                }
            }
            return marks;
        }
    }

    private static OrtSession session(File model) throws OrtException {
        synchronized (sSessions) {
            OrtSession s = sSessions.get(model.getAbsolutePath());
            if (s == null) {
                try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    s = OrtEnvironment.getEnvironment().createSession(model.getAbsolutePath(), options);
                }
                sSessions.put(model.getAbsolutePath(), s);
            }
            return s;
        }
    }

    /** Releases the model of a voice that is unloaded or deleted. */
    static void close(File voiceDir) {
        synchronized (sSessions) {
            final OrtSession s = sSessions.remove(new File(voiceDir, MODEL_FILE).getAbsolutePath());
            if (s != null) {
                try {
                    s.close();
                } catch (OrtException ignored) {
                    // Closing frees native memory; nothing to recover.
                }
            }
        }
    }

    static final class Result {
        /** The vowelled text. */
        final String text;
        /** Code point of the vowelled text -> code point of the original; one extra entry for the end. */
        final int[] toOriginal;

        Result(String text, int[] toOriginal) {
            this.text = text;
            this.toOriginal = toOriginal;
        }
    }
}
