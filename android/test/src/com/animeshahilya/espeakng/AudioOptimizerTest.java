package com.animeshahilya.espeakng;

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
}
