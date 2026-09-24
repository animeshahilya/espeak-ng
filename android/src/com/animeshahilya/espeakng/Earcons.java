package com.animeshahilya.espeakng;

/**
 * Punctuation sounds (opt-in): brackets and the quote mark play a short tone instead
 * of being named, like NVDA's "Earcons and Speech Rules" add-on. Only a
 * symbol the punctuation level would have spoken becomes a sound, so the
 * setting changes how punctuation is announced, never whether.
 *
 * The text pipeline swaps each such symbol for one private-use character
 * (same length, so word-boundary offsets are unaffected); TtsService splits
 * the text there and writes the tone between the pieces of speech.
 */
public final class Earcons {
    private Earcons() {}

    private static final char FIRST = '';
    // Opening brackets rise, closing ones fall; the bracket kind sets the
    // pitch (round middle, square high, curly low). Quotes are a click pair.
    private static final String SYMBOLS = "()[]{}\"";
    private static final int[][] TONES = {
            // start Hz, end Hz, milliseconds
            {500, 800, 70}, {800, 500, 70},
            {900, 1300, 70}, {1300, 900, 70},
            {300, 480, 70}, {480, 300, 70},
            {1500, 1500, 18},
    };

    /** The marker for a symbol that has a sound, or 0. */
    public static char markerFor(char symbol) {
        int i = SYMBOLS.indexOf(symbol);
        if (i < 0) {
            return 0;
        }
        return (char) (FIRST + Math.min(i, TONES.length - 1));
    }

    public static boolean isMarker(char c) {
        return c >= FIRST && c < FIRST + TONES.length;
    }

    /** Text with every sounding symbol the level announces replaced by its marker. */
    public static String mark(String text, int level, String customChars) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char m = markerFor(c);
            if (m != 0 && NvdaSymbolProcessor.isAnnounced(String.valueOf(c), level, customChars)) {
                if (sb == null) {
                    sb = new StringBuilder(text);
                }
                sb.setCharAt(i, m);
            }
        }
        return sb == null ? text : sb.toString();
    }

    /**
     * 16-bit little-endian PCM for a marker: a short sine sweep with soft
     * edges and a little silence after it, so it never runs into the next
     * word. The quote click plays twice.
     */
    public static byte[] pcm(char marker, int sampleRate, int channels, int volumePercent) {
        int[] t = TONES[marker - FIRST];
        int repeats = t[2] < 40 ? 2 : 1;
        int toneFrames = sampleRate * t[2] / 1000;
        int gapFrames = sampleRate * 40 / 1000;
        int frames = repeats * (toneFrames + gapFrames);
        byte[] out = new byte[frames * channels * 2];
        double amp = 9000.0 * Math.max(0, Math.min(200, volumePercent)) / 100.0;
        int fade = Math.max(1, sampleRate * 5 / 1000);
        int pos = 0;
        for (int r = 0; r < repeats; r++) {
            double phase = 0;
            for (int f = 0; f < toneFrames; f++) {
                double hz = t[0] + (t[1] - t[0]) * (double) f / toneFrames;
                phase += 2 * Math.PI * hz / sampleRate;
                double env = Math.min(1.0, Math.min(f, toneFrames - 1 - f) / (double) fade);
                short s = (short) Math.round(Math.sin(phase) * amp * env);
                for (int c = 0; c < channels; c++) {
                    out[pos++] = (byte) s;
                    out[pos++] = (byte) (s >> 8);
                }
            }
            pos += gapFrames * channels * 2;
        }
        return out;
    }
}
