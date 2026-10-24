package com.animeshahilya.espeakng.tts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RequestSettingsTest {
    /** The app's default (40) and eSpeak's (50, carried over by older installs) keep the voice's pitch. */
    @Test
    public void naturalPitchKeepsTheVoicesOwnBetweenTheTwoDefaults() {
        for (int p = 40; p <= 50; p++) {
            assertEquals("pitch " + p, 1f, RequestSettings.naturalPitch(p), 0f);
        }
        assertEquals(Math.pow(2, -0.4), RequestSettings.naturalPitch(0), 1e-6);
        assertEquals(Math.pow(2, 0.5), RequestSettings.naturalPitch(100), 1e-6);
        assertEquals(Math.pow(2, 0.01), RequestSettings.naturalPitch(51), 1e-6);
    }
}
