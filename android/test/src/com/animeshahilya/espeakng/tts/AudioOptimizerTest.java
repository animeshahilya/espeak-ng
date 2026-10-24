package com.animeshahilya.espeakng.tts;

import com.animeshahilya.espeakng.tts.AudioOptimizer;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioOptimizerTest {

    @Test
    public void testFastTanhAccuracy() {
        // Test accuracy across the primary operating range [-5.0, 5.0]
        for (float x = -5.0f; x <= 5.0f; x += 0.1f) {
            float expected = (float) Math.tanh(x);
            float actual = AudioOptimizer.fastTanh(x);
            assertEquals("Mismatch at x=" + x, expected, actual, 0.0002f);
        }
    }

    @Test
    public void testFastTanhClamping() {
        assertEquals(1.0f, AudioOptimizer.fastTanh(6.0f), 0.0f);
        assertEquals(1.0f, AudioOptimizer.fastTanh(10.0f), 0.0f);
        assertEquals(-1.0f, AudioOptimizer.fastTanh(-6.0f), 0.0f);
        assertEquals(-1.0f, AudioOptimizer.fastTanh(-10.0f), 0.0f);
        assertEquals(0.0f, AudioOptimizer.fastTanh(0.0f), 0.00001f);
    }

    @Test
    public void testFilterAlphas() {
        int sampleRate = 22050;
        float lowpassAlpha = AudioOptimizer.onePoleAlpha(180.0, sampleRate);
        assertTrue("Alpha must be between 0 and 1", lowpassAlpha > 0f && lowpassAlpha < 1f);

        float highpassPole = AudioOptimizer.onePoleHighpassPole(2500.0, sampleRate);
        assertTrue("Highpass pole must be between 0 and 1", highpassPole > 0f && highpassPole < 1f);

        float scaledAlpha = AudioOptimizer.scaledEnvelopeAlpha(0.0005f, 22050, 44100);
        assertTrue("Scaled alpha must be positive", scaledAlpha > 0f && scaledAlpha <= 1f);
    }

    @Test
    public void testLimiterGainForSample() {
        float threshold = 30000f;
        // Below threshold: unity gain
        assertEquals(1.0f, AudioOptimizer.limiterGainForSample(15000f, threshold), 0.0001f);
        assertEquals(1.0f, AudioOptimizer.limiterGainForSample(30000f, threshold), 0.0001f);

        // Above threshold: gain reduces proportionally
        assertEquals(0.5f, AudioOptimizer.limiterGainForSample(60000f, threshold), 0.0001f);
    }

    @Test
    public void testSibilantBypassFactor() {
        // Below lower threshold (0.16): voiced phoneme, 0% bypass (full glottal tilt)
        assertEquals(0.0f, AudioOptimizer.sibilantBypassFactor(0.10f), 0.0001f);
        assertEquals(0.0f, AudioOptimizer.sibilantBypassFactor(0.16f), 0.0001f);

        // Above upper threshold (0.28): unvoiced fricative/sibilant, 100% bypass
        assertEquals(1.0f, AudioOptimizer.sibilantBypassFactor(0.28f), 0.0001f);
        assertEquals(1.0f, AudioOptimizer.sibilantBypassFactor(0.40f), 0.0001f);

        // Midpoint: smooth ramp
        float mid = AudioOptimizer.sibilantBypassFactor(0.22f);
        assertEquals(0.5f, mid, 0.01f);
    }

    @Test
    public void testAllModulesDisabledPassesThroughUnaltered() {
        int sampleRate = 22050;
        // Construct optimizer with every single module disabled
        AudioOptimizer optimizer = new AudioOptimizer(sampleRate, "custom",
                false, false, false, false, false, false, false, 175, false);

        byte[] pcmData = new byte[100];
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = (short) (i * 200 - 10000);
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        byte[] original = pcmData.clone();

        optimizer.process(pcmData, pcmData.length);

        // Must remain exactly identical byte-for-byte
        assertArrayEquals("Output must be bit-identical when all stages are disabled", original, pcmData);
    }

    @Test
    public void testDeclickerFadeIn() {
        int sampleRate = 22050;
        // Only de-clicker enabled
        AudioOptimizer optimizer = new AudioOptimizer(sampleRate, "custom",
                false, false, false, false, true, false, false, 175, false);

        byte[] pcmData = new byte[160];
        // Fill with constant DC value 10000
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = 10000;
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }

        optimizer.process(pcmData, pcmData.length);

        // First sample (sample index 0) should have faded in at 0
        short firstSample = (short) (((pcmData[1] & 0xFF) << 8) | (pcmData[0] & 0xFF));
        assertEquals("First sample must be 0 after de-clicker soft attack fade-in", 0, firstSample);

        // Sample past fade-in window (sample index 65) should reach full amplitude (fade-in window is 64 samples)
        int idx = 65 * 2;
        short lateSample = (short) (((pcmData[idx + 1] & 0xFF) << 8) | (pcmData[idx] & 0xFF));
        assertEquals("Sample past fade-in window should be at full amplitude", 10000, lateSample);
    }

    @Test
    public void testDeclickerTrailingFadeOut() {
        int sampleRate = 22050;
        AudioOptimizer optimizer = new AudioOptimizer(sampleRate, "custom",
                false, false, false, false, true, false, false, 175, false);

        // Initially no samples processed, tail is quiet: returns null
        assertNull(optimizer.generateTrailingFade());

        // Process a block that ends on non-zero DC amplitude
        byte[] pcmData = new byte[200];
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = 12000;
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        optimizer.process(pcmData, pcmData.length);
        assertEquals(12000f, optimizer.getPrevOutputSample(), 1.0f);

        // generateTrailingFade should emit smooth fade-out decaying to 0
        byte[] tail = optimizer.generateTrailingFade();
        assertNotNull(tail);
        assertEquals(AudioOptimizer.DECLICK_FADE_SAMPLES * 2, tail.length);

        // First sample in tail is near last rendered sample
        short firstTail = (short) (((tail[1] & 0xFF) << 8) | (tail[0] & 0xFF));
        assertTrue("First tail sample must be close to last rendered sample", firstTail > 11000 && firstTail <= 12000);

        // Last sample in tail must reach 0 (smooth Hann decay)
        int lastIdx = (AudioOptimizer.DECLICK_FADE_SAMPLES - 1) * 2;
        short lastTail = (short) (((tail[lastIdx + 1] & 0xFF) << 8) | (tail[lastIdx] & 0xFF));
        assertEquals("Final tail sample must decay to 0 to prevent pop", 0, lastTail);

        // After fade-out, prevOutputSample is reset to 0
        assertEquals(0f, optimizer.getPrevOutputSample(), 0.0f);
        assertNull("Subsequent call should return null since tail is now quiet", optimizer.generateTrailingFade());
    }

    @Test
    public void testDeclickerSlewRateLimiter() {
        int sampleRate = 22050;
        // Only declicker enabled
        AudioOptimizer optimizer = new AudioOptimizer(sampleRate, "custom",
                false, false, false, false, true, false, false, 175, false);

        // 2 samples: first sample 0, second sample extreme pop to 32000
        byte[] pcmData = new byte[4];
        pcmData[0] = 0;
        pcmData[1] = 0;
        short spike = 32000;
        pcmData[2] = (byte) (spike & 0xFF);
        pcmData[3] = (byte) ((spike >> 8) & 0xFF);

        optimizer.process(pcmData, pcmData.length);

        short outSpike = (short) (((pcmData[3] & 0xFF) << 8) | (pcmData[2] & 0xFF));
        assertTrue("Slew rate limiter must cap spike step to at most MAX_SLEW_DELTA",
                outSpike <= AudioOptimizer.MAX_SLEW_DELTA);
    }

    @Test
    public void testProcessPcmBuffer() {
        AudioOptimizer optimizer = new AudioOptimizer(22050);
        byte[] pcmData = new byte[100];
        // Fill with alternating short values
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = (short) (Math.sin(i * 0.1) * 15000);
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }

        optimizer.process(pcmData, pcmData.length);

        // Verify all 16-bit values stay within [-32768, 32767]
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = (short) (((pcmData[i + 1] & 0xFF) << 8) | (pcmData[i] & 0xFF));
            assertTrue(val >= -32768 && val <= 32767);
        }
    }

    @Test
    public void testProcessSliceLeavesBytesOutsideSliceUntouched() {
        AudioOptimizer optimizer = new AudioOptimizer(22050);
        byte[] pcmData = new byte[100];
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = (short) (Math.sin(i * 0.1) * 15000);
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        byte[] prefix = java.util.Arrays.copyOfRange(pcmData, 0, 20);
        byte[] suffix = java.util.Arrays.copyOfRange(pcmData, 60, pcmData.length);

        optimizer.process(pcmData, 20, 40);

        assertArrayEquals(prefix, java.util.Arrays.copyOfRange(pcmData, 0, 20));
        assertArrayEquals(suffix, java.util.Arrays.copyOfRange(pcmData, 60, pcmData.length));
    }

    @Test
    public void testResetClearsInternalState() {
        int sampleRate = 22050;
        AudioOptimizer optimizer = new AudioOptimizer(sampleRate, "custom",
                false, false, false, false, true, false, false, 175, false);

        byte[] pcmData = new byte[160];
        for (int i = 0; i < pcmData.length; i += 2) {
            short val = 10000;
            pcmData[i] = (byte) (val & 0xFF);
            pcmData[i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        optimizer.process(pcmData, pcmData.length);
        assertEquals(10000f, optimizer.getPrevOutputSample(), 1.0f);

        // Reset should clear previous output sample and restart attack fade
        optimizer.reset();
        assertEquals(0f, optimizer.getPrevOutputSample(), 0.0f);
        assertNull(optimizer.generateTrailingFade());

        // Process a new buffer: first sample should be faded in from 0 again
        byte[] nextPcm = new byte[160];
        for (int i = 0; i < nextPcm.length; i += 2) {
            short val = 10000;
            nextPcm[i] = (byte) (val & 0xFF);
            nextPcm[i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        optimizer.process(nextPcm, nextPcm.length);
        short firstSample = (short) (((nextPcm[1] & 0xFF) << 8) | (nextPcm[0] & 0xFF));
        assertEquals(0, firstSample);
    }

    @Test
    public void testSample16HelpersAndClamping() {
        assertEquals(0, AudioOptimizer.clamp16(0));
        assertEquals(32767, AudioOptimizer.clamp16(40000));
        assertEquals(-32768, AudioOptimizer.clamp16(-40000));
        assertEquals(15000, AudioOptimizer.clamp16(15000));
        assertEquals(-15000, AudioOptimizer.clamp16(-15000));

        byte[] buf = new byte[4];
        AudioOptimizer.putSample16(buf, 0, 12345);
        assertEquals((short) 12345, AudioOptimizer.getSample16(buf, 0));

        AudioOptimizer.putSample16(buf, 2, -23456);
        assertEquals((short) -23456, AudioOptimizer.getSample16(buf, 2));

        // Over-range clamping on putSample16
        AudioOptimizer.putSample16(buf, 0, 50000);
        assertEquals(32767, AudioOptimizer.getSample16(buf, 0));

        AudioOptimizer.putSample16(buf, 2, -50000);
        assertEquals(-32768, AudioOptimizer.getSample16(buf, 2));
    }
}
