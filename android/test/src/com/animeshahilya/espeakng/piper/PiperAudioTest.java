package com.animeshahilya.espeakng.piper;

import org.junit.Test;

import static org.junit.Assert.*;

public class PiperAudioTest {

    private static double speechDb(short[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i] / 32768.0 * (samples[i] / 32768.0);
        }
        return 10 * Math.log10(sum / (to - from));
    }

    @Test
    public void bringsSpeechToTheTargetLevelAndTrimsSilence() {
        final float[] audio = new float[4000];
        for (int i = 1000; i < 2000; i++) {
            audio[i] = (float) Math.sin(i * 0.3) * 0.25f;
        }
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 1f, 16000, true);
        // 10 ms frames and a 20 ms margin on each side at 16 kHz.
        assertTrue(pcm.samples.length <= 1000 + 2 * 160 + 2 * 320);
        assertTrue(pcm.samples.length >= 1000);
        final int start = 1000 - pcm.trimmedLead;
        assertEquals(PiperAudio.TARGET_SPEECH_DBFS, speechDb(pcm.samples, start + 50, start + 950), 1.0);
    }

    /** A quiet voice and a loud one come out at the same level. */
    @Test
    public void voicesOfDifferentLevelsMatch() {
        final float[] quiet = new float[2205];
        final float[] loud = new float[2205];
        for (int i = 0; i < quiet.length; i++) {
            quiet[i] = (float) Math.sin(i * 0.2) * 0.05f;
            loud[i] = (float) Math.sin(i * 0.2) * 0.6f;
        }
        final short[] a = PiperAudio.process(quiet, 1f, 22050, false).samples;
        final short[] b = PiperAudio.process(loud, 1f, 22050, false).samples;
        assertEquals(speechDb(a, 0, a.length), speechDb(b, 0, b.length), 0.1);
    }

    /**
     * A chunk rendered in two pieces is trimmed only at its own ends, and the
     * second piece, levelled from the whole chunk, comes out exactly as that
     * part of the chunk rendered whole.
     */
    @Test
    public void piecesTrimOnlyTheChunksEndsAndTheRestMatchesWhole() {
        final float[] whole = new float[8820];
        for (int i = 2205; i < 6615; i++) {
            whole[i] = (float) Math.sin(i * 0.2) * (i < 4410 ? 0.4f : 0.2f); // quieter after the cut
        }
        final float[] head = java.util.Arrays.copyOfRange(whole, 0, 4410);
        final float[] tail = java.util.Arrays.copyOfRange(whole, 4410, 8820);
        final PiperAudio.Pcm a = PiperAudio.process(head, 1f, 22050, true, false, 0, 0);
        final PiperAudio.Pcm b = PiperAudio.process(tail, 1f, 22050, false, true, 0,
                PiperAudio.speechPower(whole, 22050));
        assertTrue("lead trimmed", a.trimmedLead > 1500);
        assertEquals("end of the first piece kept", head.length - a.trimmedLead, a.samples.length);
        assertEquals("start of the second piece kept", 0, b.trimmedLead);
        assertTrue("tail trimmed", b.samples.length < 2205 + 2 * 441 + 220);
        final short[] at = PiperAudio.process(whole, 1f, 22050, false, false, 0, 0).samples;
        for (int i = 0; i < 2000; i++) {
            assertEquals("sample " + i, at[4410 + i], b.samples[i]);
        }
    }

    /** Breath and noise around a word, well below speech, are trimmed too. */
    @Test
    public void trimsLowNoiseNotJustDigitalSilence() {
        final float[] audio = new float[22050];
        final java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) (r.nextGaussian() * 0.002); // about -54 dB
        }
        for (int i = 8000; i < 14000; i++) {
            audio[i] += (float) Math.sin(i * 0.2) * 0.3f;
        }
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 1f, 22050, true);
        assertTrue("lead " + pcm.trimmedLead, pcm.trimmedLead > 7000);
        assertTrue("length " + pcm.samples.length, pcm.samples.length < 6000 + 2 * 441 + 2 * 220);
    }

    @Test
    public void untrimmedKeepsLengthAndVolumeScales() {
        final float[] audio = {0f, 0.5f, -0.5f, 0f};
        final PiperAudio.Pcm full = PiperAudio.process(audio, 1f, 22050, false);
        final PiperAudio.Pcm half = PiperAudio.process(audio, 0.5f, 22050, false);
        assertEquals(4, half.samples.length);
        assertEquals(full.samples[1] / 2.0, half.samples[1], 1);
        assertEquals(-half.samples[1], half.samples[2]);
    }

    @Test
    public void silentOrEmptyInputGivesNoAudio() {
        assertEquals(0, PiperAudio.process(new float[100], 1f, 22050, true).samples.length);
        assertEquals(0, PiperAudio.process(null, 1f, 22050, true).samples.length);
    }

    @Test
    public void loudPeaksAreLimitedInsteadOfWrapping() {
        final float[] audio = new float[2205];
        audio[100] = 1f;
        audio[200] = -1f;
        for (int i = 300; i < audio.length; i++) {
            audio[i] = (float) Math.sin(i * 0.2) * 0.02f;
        }
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 2f, 22050, false);
        assertTrue(pcm.samples[100] > 30000 && pcm.samples[100] <= 32767);
        assertTrue(pcm.samples[200] < -30000);
        assertEquals(0.5, PiperAudio.limit(0.5), 0);
        assertTrue(PiperAudio.limit(5) < 1);
    }

    @Test
    public void littleEndianBytes() {
        assertArrayEquals(new byte[] {0x34, 0x12, (byte) 0xff, (byte) 0xff},
                PiperAudio.toBytes(new short[] {0x1234, -1}, 2));
    }

    @Test
    public void pausesFollowReadingPaceAndSpeed() {
        assertEquals(4410, PiperAudio.pauseSamples(22050, true, 100, 1f));
        assertEquals(2205, PiperAudio.pauseSamples(22050, true, 50, 1f));
        assertEquals(2205, PiperAudio.pauseSamples(22050, true, 100, 2f));
        assertTrue(PiperAudio.pauseSamples(22050, false, 100, 1f) < 4410);
    }

    @Test
    public void wordsAndProportionalTiming() {
        final int[] words = PiperAudio.findWords(" Hello  big world");
        assertArrayEquals(new int[] {1, 6, 8, 11, 12, 17}, words);
        final int[] frames = PiperAudio.estimateWordFrames(words, 17, 1700);
        assertArrayEquals(new int[] {100, 800, 1200}, frames);

        // Slice testing: first piece [0, 1) and second piece [1, 3)
        final int[] piece1 = PiperAudio.estimateWordFrames(words, 0, 1, 17, 800);
        assertArrayEquals(new int[] {100}, piece1);
        final int[] piece2 = PiperAudio.estimateWordFrames(words, 1, 3, 17, 900);
        assertArrayEquals(new int[] {0, 400}, piece2);

        // Edge cases
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(null, 17, 1700));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, 0, 1700));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, 17, 0));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, 2, 2, 17, 1700));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, 3, 1, 17, 1700));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, -2, -1, 17, 1700));
        assertArrayEquals(new int[0], PiperAudio.estimateWordFrames(words, 10, 20, 17, 1700));
    }

    @Test
    public void alignedWordsFollowTheModelsDurations() {
        // 6 ids of 2 frames each, hop 10: every id is 20 samples.
        final float[] durations = {2, 2, 2, 2, 2, 2};
        final java.util.List<Integer> starts = java.util.Arrays.asList(1, 4);
        // No trim, no stretch: words at ids 1 and 4 -> samples 20 and 80.
        assertArrayEquals(new int[] {20, 80},
                PiperAudio.alignedWordFrames(starts, durations, 10, 2, 0, 120, 120));
        // 15 samples trimmed from the front, then stretched to half length.
        assertArrayEquals(new int[] {2, 32},
                PiperAudio.alignedWordFrames(starts, durations, 10, 2, 15, 105, 52));
        // Word counts that don't pair up, or no durations: caller estimates.
        assertNull(PiperAudio.alignedWordFrames(starts, durations, 10, 3, 0, 120, 120));
        assertNull(PiperAudio.alignedWordFrames(starts, null, 10, 2, 0, 120, 120));
    }

    /** A 700 ms comma pause inside a chunk is cut to the cap; short gaps stay. */
    @Test
    public void longPausesAreCappedShortOnesKept() {
        final int rate = 10000;
        final float[] audio = new float[rate * 2];
        for (int i = 0; i < 5000; i++) {
            audio[i] = (float) Math.sin(i * 0.3) * 0.3f;          // 0.5 s speech
        }
        for (int i = 12000; i < 13000; i++) {
            audio[i] = (float) Math.sin(i * 0.3) * 0.3f;          // after a 700 ms pause
        }
        for (int i = 13800; i < 16000; i++) {
            audio[i] = (float) Math.sin(i * 0.3) * 0.3f;          // after an 80 ms gap
        }
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 1f, rate, true, 250);
        assertEquals(2, pcm.cuts.length);
        assertEquals(7000 - 2500, pcm.cuts[1]);
        assertEquals(16000 - 4500, pcm.speechSamples, 2 * 200 + 100);
        // A word starting after the pause moves back by the cut; one before it doesn't.
        assertEquals(12000 - 4500, PiperAudio.toOutput(12000, 0, pcm.cuts), 0);
        assertEquals(3000, PiperAudio.toOutput(3000, 0, pcm.cuts), 0);
        assertEquals(0, PiperAudio.process(audio, 1f, rate, true, 0).cuts.length);
    }

    @Test
    public void pauseCapFollowsReadingPace() {
        assertEquals(250, PiperAudio.maxPauseMs(100, 1f));
        assertEquals(125, PiperAudio.maxPauseMs(50, 1f));
        assertEquals(125, PiperAudio.maxPauseMs(100, 2f));
        assertEquals(PiperAudio.MIN_PAUSE_CAP_MS, PiperAudio.maxPauseMs(10, 3f));
    }

    @Test
    public void alignedWordsSkipCutPauses() {
        final java.util.List<Integer> starts = java.util.Arrays.asList(0, 2);
        final float[] durations = {5, 5, 5, 5};   // x10 samples: words at 0 and 100
        final int[] cuts = {60, 30};             // 30 samples removed at 60
        assertArrayEquals(new int[] {0, 70},
                PiperAudio.alignedWordFrames(starts, durations, 10, 2, 0, cuts, 170, 170));
    }

    private static double toneDb(float[] audio, int from) {
        double sum = 0;
        for (int i = from; i < audio.length; i++) {
            sum += audio[i] * (double) audio[i];
        }
        return 10 * Math.log10(sum / (audio.length - from));
    }

    private static float[] tone(double hz, int rate, int n) {
        final float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (0.3 * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return out;
    }

    /** Hiss band down by the shelf's 8 dB, speech band untouched. */
    @Test
    public void trebleCutLowersOnlyTheTreble() {
        final int rate = 22050;
        final float[] low = tone(500, rate, rate / 2);
        final float[] high = tone(10000, rate, rate / 2);
        assertEquals(toneDb(low, 1000),
                toneDb(new PiperAudio.TrebleCut(rate).apply(low), 1000), 0.3);
        assertEquals(toneDb(high, 1000) + PiperAudio.TrebleCut.GAIN_DB,
                toneDb(new PiperAudio.TrebleCut(rate).apply(high), 1000), 0.6);
    }

    /** A chunk filtered in two pieces by one filter equals the chunk filtered whole. */
    @Test
    public void trebleCutCarriesItsStateAcrossPieces() {
        final float[] whole = tone(7000, 22050, 4000);
        final float[] once = new PiperAudio.TrebleCut(22050).apply(whole);
        final PiperAudio.TrebleCut cut = new PiperAudio.TrebleCut(22050);
        final float[] a = cut.apply(java.util.Arrays.copyOfRange(whole, 0, 1500));
        final float[] b = cut.apply(java.util.Arrays.copyOfRange(whole, 1500, 4000));
        for (int i = 0; i < whole.length; i++) {
            assertEquals(once[i], i < 1500 ? a[i] : b[i - 1500], 1e-6);
        }
    }

    /**
     * A hissy voice's breathy lead-in (here 250 ms at -26 dB under speech) is
     * trimmed with quietStart, kept without it, and the cut starts from zero.
     */
    @Test
    public void quietStartTrimsTheNoisyLeadIn() {
        final int rate = 22050;
        final float[] audio = new float[rate];
        final java.util.Random noise = new java.util.Random(1);
        final int speechAt = 2205 + rate / 4;
        for (int i = 2205; i < speechAt; i++) {
            audio[i] = (float) (noise.nextGaussian() * 0.3 * Math.pow(10, -26 / 20.0) / Math.sqrt(2));
        }
        for (int i = speechAt; i < rate - 2205; i++) {
            audio[i] = (float) (0.3 * Math.sin(i * 0.2));
        }
        final PiperAudio.Pcm plain = PiperAudio.process(audio, 1f, rate, true, true, 0, 0, false);
        final PiperAudio.Pcm quiet = PiperAudio.process(audio, 1f, rate, true, true, 0, 0, true);
        assertTrue(plain.trimmedLead < 2205);
        final int margin = rate * PiperAudio.QUIET_START_MARGIN_MS / 1000;
        assertTrue(quiet.trimmedLead >= speechAt - margin - rate / 100);
        assertTrue(quiet.trimmedLead <= speechAt);
        assertEquals(0, quiet.samples[0]);
        assertEquals(0, plain.samples[0]);
    }

    @Test
    public void processHandlesExtremeTrimsAndNullSafely() {
        // Null or empty audio returns zero samples
        assertEquals(0, PiperAudio.process(null, 1f, 22050, true).samples.length);
        assertEquals(0, PiperAudio.process(new float[0], 1f, 22050, true).samples.length);

        // All-silence audio
        assertEquals(0, PiperAudio.process(new float[22050], 1f, 22050, true).samples.length);

        // fadeIn edge cases
        PiperAudio.fadeIn(null, 22050);
        PiperAudio.fadeIn(new float[0], 22050);
        float[] singleSample = new float[] {1.0f};
        PiperAudio.fadeIn(singleSample, 0);
        assertEquals(1.0f, singleSample[0], 1e-6);

        // toOutput with null cuts
        assertEquals(50.0, PiperAudio.toOutput(150.0, 100, null), 1e-6);

        // findWords with empty / null text
        assertEquals(0, PiperAudio.findWords(null).length);
        assertEquals(0, PiperAudio.findWords("").length);
        assertEquals(0, PiperAudio.findWords("   \t\n  ").length);
        int[] words = PiperAudio.findWords("hello world");
        assertEquals(4, words.length); // 2 words * 2 coords
    }

    @Test
    public void alignedWordFramesPreservesMonotonicity() {
        java.util.List<Integer> starts = java.util.Arrays.asList(0, 1, 2);
        float[] durations = new float[] {5f, 5f, 5f};
        int[] frames = PiperAudio.alignedWordFrames(starts, durations, 256, 3, 0, new int[0], 15 * 256, 15 * 256);
        assertNotNull(frames);
        assertEquals(3, frames.length);
        for (int i = 1; i < frames.length; i++) {
            assertTrue("frames must be non-decreasing", frames[i] >= frames[i - 1]);
        }
    }
}
