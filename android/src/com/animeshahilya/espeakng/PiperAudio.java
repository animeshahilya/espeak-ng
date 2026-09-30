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
    /** Above this the soft limiter bends samples down instead of clipping them. */
    static final float LIMITER_KNEE = 0.7f;

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
            final double silence = speech * Math.pow(10, -SILENCE_BELOW_SPEECH_DB / 10);
            int first = 0;
            while (trimLead && first < frames && power[first] < silence) {
                first++;
            }
            int last = frames - 1;
            while (trimTail && last > first && power[last] < silence) {
                last--;
            }
            final int margin = sampleRate * TRIM_MARGIN_MS / 1000;
            from = trimLead ? Math.max(0, first * frame - margin) : 0;
            to = trimTail ? Math.min(audio.length, (last + 1) * frame + margin) : audio.length;
            if (maxPauseMs > 0) {
                cuts = pauseCuts(power, silence, first, last, frame, sampleRate * maxPauseMs / 1000);
            }
        }

        int removed = 0;
        for (int i = 1; i < cuts.length; i += 2) {
            removed += cuts[i];
        }
        final double gain = Math.pow(10, TARGET_SPEECH_DBFS / 20) / Math.sqrt(speech) * volume;
        final short[] out = new short[to - from - removed];
        int o = 0;
        int c = 0;
        for (int i = from; i < to; i++) {
            if (c < cuts.length && i == cuts[c]) {
                i += cuts[c + 1] - 1;
                c += 2;
                continue;
            }
            out[o++] = (short) Math.round(limit(audio[i] * gain) * 32767);
        }
        return new Pcm(out, from, out.length, cuts);
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
            loudest = Math.max(loudest, p);
        }
        if (loudest < 1e-16) {
            return 0;
        }
        final double speechFloor = loudest * Math.pow(10, -SPEECH_RANGE_DB / 10);
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
        final double room = 1 - LIMITER_KNEE;
        return Math.signum(s) * (LIMITER_KNEE + room * Math.tanh((a - LIMITER_KNEE) / room));
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
