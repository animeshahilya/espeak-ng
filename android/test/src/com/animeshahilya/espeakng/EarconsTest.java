package com.animeshahilya.espeakng;

import org.junit.Test;

import static org.junit.Assert.*;

public class EarconsTest {

    @Test
    public void nullAndEmptyTextHandledSafely() {
        assertNull(Earcons.mark(null, 100, null));
        assertNull(Earcons.mark(null, 100, null, "en"));
        assertEquals("", Earcons.mark("", 100, null));
        assertEquals("", Earcons.mark("", 100, null, "en"));
    }

    @Test
    public void markerDetection() {
        char roundOpen = Earcons.markerFor('(');
        assertTrue(roundOpen != 0);
        assertTrue(Earcons.isMarker(roundOpen));
        assertEquals(0, Earcons.markerFor('x'));
        assertFalse(Earcons.isMarker('x'));
    }

    @Test
    public void pcmGeneratesValidAudio() {
        char m = Earcons.markerFor('(');
        byte[] pcm = Earcons.pcm(m, 22050, 1, 100);
        assertTrue(pcm.length > 0);
        assertEquals(0, pcm.length % 2); // 16-bit aligned

        // Invalid parameters
        assertEquals(0, Earcons.pcm('a', 22050, 1, 100).length);
        assertEquals(0, Earcons.pcm(m, 0, 1, 100).length);
        assertEquals(0, Earcons.pcm(m, 22050, 0, 100).length);
    }
}
