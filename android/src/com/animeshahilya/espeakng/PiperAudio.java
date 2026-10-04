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

    /**
     * Speech level every natural voice is brought to: the RMS of its
     * speaking 10 ms frames, in dBFS. Peak normalization (Piper's own) left
     * voices wherever their peak-to-average ratio put them - measured on a
     * Pixel 8, Hindi Priyamvada spoke at -17 dB and English LibriVox at
     * -22 dB, so English words inside Hindi text came out ~10 dB quieter
     * and speech recognition missed them.
     */
    static final float TARGET_SPEECH_DBFS = -20f;
    /** Frames within this of the loudest one are speech, for the level. */
    static final float SPEECH_RANGE_DB = 30f;
    /** Frames this far below the speech level are silence, for trimming. */
    static final float SILENCE_BELOW_SPEECH_DB = 30f;
    static final int FRAME_MS = 10;
    /** Kept on each side of trimmed speech so onsets/releases aren't clipped. */
    static final int TRIM_MARGIN_MS = 20;
    /**
     * Hissy voices ({@link PiperVoiceConfig#hissy}) breathe ~250 ms of noise
     * before the first word, 20-30 dB under speech: their start is trimmed
     * up to here instead, keeping a longer margin.
     */
    static final float QUIET_START_BELOW_SPEECH_DB = 18f;
    static final int QUIET_START_MARGIN_MS = 30;
    /** Ramps at trimmed ends, so a cut into low noise does not click. */
    static final int EDGE_FADE_MS = 5;
    static final int QUIET_START_FADE_MS = 10;

    private static final double TARGET_SPEECH_LINEAR = Math.pow(10, TARGET_SPEECH_DBFS / 20.0);
    private static final double SPEECH_FLOOR_FACTOR = Math.pow(10, -SPEECH_RANGE_DB / 10.0);
    private static final double SILENCE_FACTOR = Math.pow(10, -SILENCE_BELOW_SPEECH_DB / 10.0);
    private static final double QUIET_START_FACTOR = Math.pow(10, -QUIET_START_BELOW_SPEECH_DB / 10.0);

    /** A 5 ms ramp, so audio cut mid-signal (a lead-in) starts without a click. */
    static void fadeIn(float[] audio, int sampleRate) {
        if (audio == null || audio.length == 0 || sampleRate <= 0) {
            return;
        }
        final int n = Math.min(audio.length, sampleRate / 200);
        for (int i = 0; i < n; i++) {
            audio[i] *= (float) i / n;
        }
    }
    /** A linear ramp over the first (or last) {@code n} samples. */
    private static void fade(short[] pcm, int n, boolean in) {
        n = Math.min(n, pcm.length);
        for (int i = 0; i < n; i++) {
            final int at = in ? i : pcm.length - 1 - i;
            pcm[at] = (short) (pcm[at] * i / n);
        }
    }

    /**
     * An 8 dB high-shelf cut above 6 kHz (RBJ biquad) for hissy voices:
     * measured 2026-10-03 it brings their treble to the clean voices' level
     * and Whisper reads them no worse. Stateful, so the pieces of one chunk
     * filter as one signal: one instance per chunk.
     */
    static final class TrebleCut {
        static final double CORNER_HZ = 6000;
        static final double GAIN_DB = -8;
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        TrebleCut(int sampleRate) {
            sampleRate = Math.max(8000, sampleRate);
            final double a = Math.pow(10, GAIN_DB / 40);
            final double w = 2 * Math.PI * Math.min(CORNER_HZ, 0.45 * sampleRate) / sampleRate;
            final double cos = Math.cos(w);
            final double s = 2 * Math.sqrt(a) * Math.sin(w) / (2 * Math.sqrt(0.5));
            final double a0 = (a + 1) - (a - 1) * cos + s;
            b0 = a * ((a + 1) + (a - 1) * cos + s) / a0;
            b1 = -2 * a * ((a - 1) + (a + 1) * cos) / a0;
            b2 = a * ((a + 1) + (a - 1) * cos - s) / a0;
            a1 = 2 * ((a - 1) - (a + 1) * cos) / a0;
            a2 = ((a + 1) - (a - 1) * cos - s) / a0;
        }

        /** The filtered copy; the input (possibly the phrase cache's) stays as it is. */
        float[] apply(float[] in) {
            final float[] out = new float[in.length];
            for (int i = 0; i < in.length; i++) {
                final double y = b0 * in[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = in[i];
                y2 = y1;
                y1 = y;
                out[i] = (float) y;
            }
            return out;
        }
    }

    /** Above this the soft limiter bends samples down instead of clipping them. */
    static final float LIMITER_KNEE = 0.7f;
    private static final double LIMITER_ROOM = 1.0 - LIMITER_KNEE;
    private static final double INV_LIMITER_ROOM = 1.0 / (1.0 - LIMITER_KNEE);

    /**
     * Longest pause kept inside a chunk at normal reading pace. VITS voices
     * make their own pauses at commas; Hindi Priyamvada's run to ~700 ms
     * (eSpeak's are 150-190 ms), and until they were capped the reading-pace
     * setting could not shorten them.
     */
    static final int MAX_PAUSE_MS = 250;
    /** Never shorter: stop closures inside words are silent for up to ~100 ms. */
    static final int MIN_PAUSE_CAP_MS = 120;

    /** Result of {@link #process}: PCM plus what was cut from the model's audio. */
    static final class Pcm {
        final short[] samples;
        /** Samples removed from the front; word-boundary estimates shift by this. */
        final int trimmedLead;
        /** Samples of the model output that remain, before any time stretch. */
        final int speechSamples;
        /** Pauses shortened inside: (model sample index, samples removed) pairs, in order. */
        final int[] cuts;

        Pcm(short[] samples, int trimmedLead, int speechSamples) {
            this(samples, trimmedLead, speechSamples, new int[0]);
        }

        Pcm(short[] samples, int trimmedLead, int speechSamples, int[] cuts) {
            this.samples = samples;
            this.trimmedLead = trimmedLead;
            this.speechSamples = speechSamples;
            this.cuts = cuts;
        }
    }

    /** Where model sample {@code s} lands in the output, before any time stretch. */
    static double toOutput(double s, int trimmedLead, int[] cuts) {
        double p = s - trimmedLead;
        if (cuts == null) {
            return p;
        }
        for (int i = 0; i + 1 < cuts.length && s > cuts[i]; i += 2) {
            p -= Math.min(cuts[i + 1], s - cuts[i]);
        }
        return p;
    }

    /** Pause cap for a reading pace (percent) and speed; see {@link #MAX_PAUSE_MS}. */
    static int maxPauseMs(int pauseScalePercent, float speed) {
        return Math.max(MIN_PAUSE_CAP_MS,
                Math.round(MAX_PAUSE_MS * Math.max(0, pauseScalePercent) / 100f / Math.max(1f, speed)));
    }

    /**
     * Brings a chunk to {@link #TARGET_SPEECH_DBFS} (then volume, then a
     * soft limiter), and trims the silence VITS pads every chunk with. For a
     * screen reader that pad is pure latency - ~50-150 ms before the first
     * word of each utterance - and between chunks the app inserts its own
     * pause, scaled by the user's reading-pace setting, instead. Trimming
     * follows a 10 ms loudness envelope, not single samples: one noisy sample
     * in a breath used to stop it, leaving 200-600 ms of near-silence at every
     * switch between two languages' voices.
     *
     * @param volume linear gain after normalization (1 = the target level)
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trim) {
        return process(audio, volume, sampleRate, trim, 0);
    }

    /**
     * As {@link #process(float[], float, int, boolean)}, also shortening
     * pauses inside the chunk to {@code maxPauseMs} (0 = keep them); the
     * middle of a long pause is cut, so its fade-out and fade-in stay.
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trim, int maxPauseMs) {
        return process(audio, volume, sampleRate, trim, trim, maxPauseMs, 0);
    }

    /**
     * One piece of a chunk rendered in pieces (PiperEngine): silence is
     * trimmed only at the chunk's own ends, and a later piece takes the
     * level of the whole chunk ({@link #speechPower} of all its audio so
     * far), which is the level the chunk would get rendered whole.
     *
     * @param level speech power to set the level from, 0 = this audio's
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trimLead,
                       boolean trimTail, int maxPauseMs, double level) {
        return process(audio, volume, sampleRate, trimLead, trimTail, maxPauseMs, level, false);
    }

    /** @param quietStart trim a hissy voice's noisy lead-in too (QUIET_START_BELOW_SPEECH_DB) */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trimLead,
                       boolean trimTail, int maxPauseMs, double level, boolean quietStart) {
        if (audio == null || audio.length == 0) {
            return new Pcm(new short[0], 0, 0);
        }
        final int frame = frameSize(sampleRate);
        final double[] power = framePowers(audio, frame);
        final int frames = power.length;
        final double speech = level > 0 ? level : speech(power);
        if (speech <= 0) {
            return new Pcm(new short[0], 0, 0);
        }

        int from = 0;
        int to = audio.length;
        int[] cuts = new int[0];
        if (trimLead || trimTail) {
            final double silence = speech * SILENCE_FACTOR;
            int first = 0;
            while (trimLead && first < frames && power[first] < silence) {
                first++;
            }
            int start = first;
            if (trimLead && quietStart) {
                final double voiced = speech * QUIET_START_FACTOR;
                while (start < frames && power[start] < voiced) {
                    start++;
                }
            }
            int last = frames - 1;
            while (trimTail && last > first && power[last] < silence) {
                last--;
            }
            final int margin = sampleRate * TRIM_MARGIN_MS / 1000;
            from = !trimLead ? 0 : start > first
                    ? Math.max(0, start * frame - sampleRate * QUIET_START_MARGIN_MS / 1000)
                    : Math.max(0, first * frame - margin);
            to = trimTail ? Math.min(audio.length, (last + 1) * frame + margin) : audio.length;
            if (maxPauseMs > 0 && start <= last) {
                cuts = pauseCuts(power, silence, start, last, frame, sampleRate * maxPauseMs / 1000);
            }
        }

        if (to <= from) {
            return new Pcm(new short[0], 0, 0);
        }

        int removed = 0;
        for (int i = 0; i + 1 < cuts.length; i += 2) {
            final int cutStart = Math.max(from, cuts[i]);
            final int cutEnd = Math.min(to, cuts[i] + cuts[i + 1]);
            if (cutEnd > cutStart) {
                removed += (cutEnd - cutStart);
            }
        }
        final int outLen = to - from - removed;
        if (outLen <= 0) {
            return new Pcm(new short[0], 0, 0);
        }
        final double gain = TARGET_SPEECH_LINEAR / Math.sqrt(speech) * volume;
        final short[] out = new short[outLen];
        int o = 0;
        int c = 0;
        for (int i = from; i < to && o < outLen; i++) {
            if (c + 1 < cuts.length && i >= cuts[c]) {
                final int cutEnd = cuts[c] + cuts[c + 1];
                if (i < cutEnd) {
                    i = cutEnd - 1;
                    c += 2;
                    continue;
                }
                c += 2;
            }
            out[o++] = (short) Math.round(limit(audio[i] * gain) * 32767);
        }
        final short[] finalOut = (o == outLen) ? out : Arrays.copyOf(out, o);
        if (from > 0 && finalOut.length > 0) {
            fade(finalOut, sampleRate * (quietStart ? QUIET_START_FADE_MS : EDGE_FADE_MS) / 1000, true);
        }
        if (to < audio.length && finalOut.length > 0) {
            fade(finalOut, sampleRate * EDGE_FADE_MS / 1000, false);
        }
        return new Pcm(finalOut, from, finalOut.length, cuts);
    }

    /** Mean power of the speaking 10 ms frames: what the level is set from; 0 for silence. */
    static double speechPower(float[] audio, int sampleRate) {
        return audio == null || audio.length == 0 ? 0 : speech(framePowers(audio, frameSize(sampleRate)));
    }

    private static int frameSize(int sampleRate) {
        return Math.max(1, sampleRate * FRAME_MS / 1000);
    }

    private static double[] framePowers(float[] audio, int frame) {
        final double[] power = new double[(audio.length + frame - 1) / frame];
        for (int f = 0; f < power.length; f++) {
            final int end = Math.min(audio.length, (f + 1) * frame);
            double sum = 0;
            for (int i = f * frame; i < end; i++) {
                sum += audio[i] * (double) audio[i];
            }
            power[f] = sum / (end - f * frame);
        }
        return power;
    }

    /** Frames within {@link #SPEECH_RANGE_DB} of the loudest are speech. */
    private static double speech(double[] power) {
        double loudest = 0;
        for (double p : power) {
            if (p > loudest) loudest = p;
        }
        if (loudest < 1e-16) {
            return 0;
        }
        final double speechFloor = loudest * SPEECH_FLOOR_FACTOR;
        double speech = 0;
        int speaking = 0;
        for (double p : power) {
            if (p >= speechFloor) {
                speech += p;
                speaking++;
            }
        }
        return speech / speaking;
    }

    /** (start, length) of the middle of each silent run longer than {@code keep} samples. */
    private static int[] pauseCuts(double[] power, double silence, int first, int last, int frame,
                                   int keep) {
        int[] cuts = new int[8];
        int n = 0;
        int f = first;
        while (f <= last) {
            if (power[f] >= silence) {
                f++;
                continue;
            }
            int end = f;
            while (end <= last && power[end] < silence) {
                end++;
            }
            final int length = (end - f) * frame;
            if (length > keep) {
                if (n + 2 > cuts.length) {
                    cuts = Arrays.copyOf(cuts, cuts.length * 2);
                }
                cuts[n++] = f * frame + keep / 2;
                cuts[n++] = length - keep;
            }
            f = end;
        }
        return Arrays.copyOf(cuts, n);
    }

    /** Soft limiter: linear to the knee, then bends toward (never past) full scale. */
    static double limit(double s) {
        final double a = Math.abs(s);
        if (a <= LIMITER_KNEE) {
            return s;
        }
        return Math.signum(s) * (LIMITER_KNEE + LIMITER_ROOM * Math.tanh((a - LIMITER_KNEE) * INV_LIMITER_ROOM));
    }

    static byte[] toBytes(short[] samples, int count) {
        final byte[] bytes = new byte[count * 2];
        int b = 0;
        for (int i = 0; i < count; i++) {
            final short s = samples[i];
            bytes[b++] = (byte) (s & 0xff);
            bytes[b++] = (byte) ((s >> 8) & 0xff);
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
        return alignedWordFrames(wordStartIds, durations, hopLength, textWords, trimmedLead,
                new int[0], speechSamples, outSamples);
    }

    /** As above, for a chunk whose long pauses were shortened ({@link Pcm#cuts}). */
    static int[] alignedWordFrames(List<Integer> wordStartIds, float[] durations,
                                   int hopLength, int textWords, int trimmedLead, int[] cuts,
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
            final double inSpeech = Math.max(0, Math.min(speechSamples,
                    toOutput(samples, trimmedLead, cuts)));
            out[w] = (int) Math.min(outSamples, Math.round(inSpeech * scale));
            if (w > 0 && out[w] < out[w - 1]) {
                out[w] = out[w - 1];
            }
        }
        return out;
    }

    /** Code point [start, end) pairs of whitespace-separated words in text. */
    static int[] findWords(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        final int len = text.length();
        int[] spans = new int[16];
        int n = 0;
        int cp = 0;
        int wordStart = -1;
        for (int i = 0; i < len; ) {
            final char ch = text.charAt(i);
            final int c;
            final int charCount;
            if (ch < 0xD800 || ch > 0xDFFF) {
                c = ch;
                charCount = 1;
            } else {
                c = text.codePointAt(i);
                charCount = Character.charCount(c);
            }
            i += charCount;
            final boolean space = (c <= ' ') ? (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f')
                    : (Character.isWhitespace(c) || Character.isSpaceChar(c));
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
            spans[n++] = cp;
        }
        return Arrays.copyOf(spans, n);
    }
}
