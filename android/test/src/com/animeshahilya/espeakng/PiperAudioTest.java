package com.animeshahilya.espeakng;

import org.junit.Test;

import static org.junit.Assert.*;

public class PiperAudioTest {

    @Test
    public void normalizesToFullScaleAndTrimsSilence() {
        final float[] audio = new float[4000];
        for (int i = 1000; i < 2000; i++) {
            audio[i] = (float) Math.sin(i * 0.3) * 0.25f;
        }
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 1f, 16000, true);
        // 12 ms margin on each side at 16 kHz = 192 samples.
        assertTrue(pcm.samples.length < 1000 + 2 * 192 + 4);
        assertTrue(pcm.samples.length >= 1000);
        int peak = 0;
        for (short s : pcm.samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertTrue("peak normalized", peak > 32000);
    }

    @Test
    public void untrimmedKeepsLengthAndVolumeScales() {
        final float[] audio = {0f, 0.5f, -0.5f, 0f};
        final PiperAudio.Pcm pcm = PiperAudio.process(audio, 0.5f, 22050, false);
        assertEquals(4, pcm.samples.length);
        assertEquals(16383, pcm.samples[1], 1);
        assertEquals(-16383, pcm.samples[2], 1);
    }

    @Test
    public void silentOrEmptyInputGivesNoAudio() {
        assertEquals(0, PiperAudio.process(new float[100], 1f, 22050, true).samples.length);
        assertEquals(0, PiperAudio.process(null, 1f, 22050, true).samples.length);
    }

    @Test
    public void volumeAboveOneClipsInsteadOfWrapping() {
        final PiperAudio.Pcm pcm = PiperAudio.process(new float[] {1f, -1f}, 2f, 22050, false);
        assertEquals(32767, pcm.samples[0]);
        assertEquals(-32768, pcm.samples[1]);
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
    }
}
