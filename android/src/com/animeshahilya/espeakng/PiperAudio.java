/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.util.Arrays;
import java.util.List;

/**
 * Post-processing of Piper's float output into the 16-bit PCM the Android
 * TTS framework takes. Pure Java; unit-tested off-device.
 */
final class PiperAudio {
    private PiperAudio() {
    }

    /** Below this (after peak normalization) a sample counts as silence. */
    static final float SILENCE_THRESHOLD = 0.012f;
    /** Kept on each side of trimmed speech so onsets/releases aren't clipped. */
    static final int TRIM_MARGIN_MS = 12;

    /** Result of {@link #process}: PCM plus how much leading audio was cut. */
    static final class Pcm {
        final short[] samples;
        /** Samples removed from the front; word-boundary estimates shift by this. */
        final int trimmedLead;
        /** Samples of the model output that remain, before any time stretch. */
        final int speechSamples;

        Pcm(short[] samples, int trimmedLead, int speechSamples) {
            this.samples = samples;
            this.trimmedLead = trimmedLead;
            this.speechSamples = speechSamples;
        }
    }

    /**
     * Piper's own output handling (peak-normalize, then volume, then clip),
     * plus trimming the silence VITS pads every chunk with. For a screen
     * reader that pad is pure latency - ~50-150 ms before the first word of
     * each utterance - and between chunks the app inserts its own pause,
     * scaled by the user's reading-pace setting, instead.
     *
     * @param volume linear gain after normalization (1 = Piper's default)
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trim) {
        if (audio == null || audio.length == 0) {
            return new Pcm(new short[0], 0, 0);
        }
        float peak = 0f;
        for (float v : audio) {
            final float a = Math.abs(v);
            if (a > peak) {
                peak = a;
            }
        }
        if (peak < 1e-8f) {
            return new Pcm(new short[0], 0, 0);
        }
        final float scale = 1f / peak;

        int from = 0;
        int to = audio.length;
        if (trim) {
            final float threshold = SILENCE_THRESHOLD * peak;
            while (from < to && Math.abs(audio[from]) < threshold) {
                from++;
            }
            while (to > from && Math.abs(audio[to - 1]) < threshold) {
                to--;
            }
            final int margin = sampleRate * TRIM_MARGIN_MS / 1000;
            from = Math.max(0, from - margin);
            to = Math.min(audio.length, to + margin);
        }

        final float gain = scale * volume * 32767f;
        final short[] out = new short[to - from];
        for (int i = from; i < to; i++) {
            float s = audio[i] * gain;
            if (s > 32767f) {
                s = 32767f;
            } else if (s < -32768f) {
                s = -32768f;
            }
            out[i - from] = (short) s;
        }
        return new Pcm(out, from, to - from);
    }

    static byte[] toBytes(short[] samples, int count) {
        final byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            final short s = samples[i];
            bytes[2 * i] = (byte) (s & 0xff);
            bytes[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return bytes;
    }

    /**
     * Pause after a chunk, in samples. Piper's default is 0.2 s after a
     * sentence; clause cuts made for latency get a short comma-sized gap.
     * Both follow the reading-pace setting (pauseScale, percent) and shrink
     * with speed like eSpeak's own pauses do.
     */
    static int pauseSamples(int sampleRate, boolean sentence, int pauseScalePercent, float speed) {
        final float base = sentence ? 0.20f : 0.08f;
        final float scaled = base * Math.max(0, pauseScalePercent) / 100f / Math.max(1f, speed);
        return Math.round(sampleRate * scaled);
    }

    /**
     * Word starts within a chunk, as frame offsets. Piper reports no timing,
     * so each word is placed in proportion to where its first letter sits in
     * the chunk's text - good enough for TalkBack's highlight to follow
     * along, which is all the framework uses these for.
     *
     * @param words     code point [start, end) pairs of the words, relative to the chunk
     * @param textLength chunk length in code points
     * @param frames    audio frames the chunk produced
     * @return one frame offset per word
     */
    static int[] estimateWordFrames(int[] words, int textLength, int frames) {
        final int count = words.length / 2;
        final int[] out = new int[count];
        if (textLength <= 0) {
            return out;
        }
        for (int w = 0; w < count; w++) {
            final long pos = words[2 * w];
            out[w] = (int) Math.min(frames, pos * frames / textLength);
        }
        return out;
    }

    /**
     * Exact word starts from the model's own durations (Piper alignments):
     * the samples before each word's first phoneme id, moved by the silence
     * trim and scaled by any time-stretch, as frame offsets into the PCM
     * actually delivered.
     *
     * @param wordStartIds id index of each phoneme word (PiperPhonemes#toIds)
     * @param durations    frames per id from the model, or null
     * @return null when there are no durations, or the phoneme words don't
     *         pair up one to one with the text's words (a number read as
     *         several words, say); the caller estimates instead
     */
    static int[] alignedWordFrames(List<Integer> wordStartIds, float[] durations,
                                   int hopLength, int textWords, int trimmedLead,
                                   int speechSamples, int outSamples) {
        if (durations == null || wordStartIds == null || wordStartIds.size() != textWords
                || speechSamples <= 0) {
            return null;
        }
        final int[] out = new int[textWords];
        final double scale = outSamples / (double) speechSamples;
        double samples = 0;
        int id = 0;
        for (int w = 0; w < textWords; w++) {
            final int target = wordStartIds.get(w);
            if (target > durations.length) {
                return null;
            }
            while (id < target) {
                samples += durations[id++] * hopLength;
            }
            final double inSpeech = Math.max(0, Math.min(speechSamples, samples - trimmedLead));
            out[w] = (int) Math.min(outSamples, Math.round(inSpeech * scale));
        }
        return out;
    }

    /** Code point [start, end) pairs of whitespace-separated words in text. */
    static int[] findWords(String text) {
        final int cps = text.codePointCount(0, text.length());
        int[] spans = new int[16];
        int n = 0;
        int cp = 0;
        int wordStart = -1;
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            final boolean space = Character.isWhitespace(c) || Character.isSpaceChar(c);
            if (!space && wordStart < 0) {
                wordStart = cp;
            } else if (space && wordStart >= 0) {
                if (n + 2 > spans.length) {
                    spans = Arrays.copyOf(spans, spans.length * 2);
                }
                spans[n++] = wordStart;
                spans[n++] = cp;
                wordStart = -1;
            }
            cp++;
        }
        if (wordStart >= 0) {
            if (n + 2 > spans.length) {
                spans = Arrays.copyOf(spans, spans.length + 2);
            }
            spans[n++] = wordStart;
            spans[n++] = cps;
        }
        return Arrays.copyOf(spans, n);
    }
}
