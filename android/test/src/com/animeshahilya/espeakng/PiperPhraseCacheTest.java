package com.animeshahilya.espeakng;

import org.junit.Test;

import static org.junit.Assert.*;

/** The natural-voice phrase cache: what it keeps, what it misses, what it drops. */
public class PiperPhraseCacheTest {
    private static PiperPhraseCache.Key key(String voice, long... ids) {
        return new PiperPhraseCache.Key(voice, ids, 0, 1f, 1f, 1f);
    }

    @Test
    public void keepsAChunkOnlyOnceItRepeats() {
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 2, 100);
        final PiperPhraseCache.Key k = key("hi|1:1", 5, 6, 7);
        assertNull(c.get(k));
        c.offer(k, new float[] {0.1f, 0.2f}, new float[] {1, 1, 1});
        assertNull("one sighting: one-off text is not kept", c.get(k));
        c.offer(k, new float[] {0.1f, 0.2f}, new float[] {1, 1, 1});
        final PiperPhraseCache.Entry e = c.get(k);
        assertNotNull(e);
        assertArrayEquals(new float[] {0.1f, 0.2f}, e.audio, 1e-4f); // 16-bit as kept
        assertEquals(1, c.hits());
    }

    @Test
    public void givesACopyAndKeepsWhatWasMade() {
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        final PiperPhraseCache.Key k = key("hi|1:1", 5);
        final float[] audio = {0.5f};
        c.offer(k, audio, null);
        audio[0] = 9f; // the caller's later processing must not reach the cache
        final PiperPhraseCache.Entry e = c.get(k);
        e.audio[0] = 7f;
        assertEquals(0.5f, c.get(k).audio[0], 1e-4f);
    }

    @Test
    public void anotherStyleVoiceOrModelFileMisses() {
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.offer(key("hi|100:5", 5, 6), new float[] {0.1f}, null);
        assertNotNull(c.get(key("hi|100:5", 5, 6)));
        assertNull("updated model file", c.get(key("hi|120:9", 5, 6)));
        assertNull("other phonemes (a dictionary edit)", c.get(key("hi|100:5", 5, 7)));
        assertNull("other style", c.get(new PiperPhraseCache.Key("hi|100:5", new long[] {5, 6}, 0, 1f, 0.5f, 0.5f)));
    }

    @Test
    public void longChunksAreNotKept() {
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 3, 1, 100);
        c.offer(key("hi|1:1", 1), new float[4], null);
        assertNull(c.get(key("hi|1:1", 1)));
    }

    @Test
    public void staysWithinItsBudgetDroppingTheLeastRecentlyUsed() {
        // Each entry is 2 * 200 + 64 bytes: two fit in 1000.
        final PiperPhraseCache c = new PiperPhraseCache(1000, 1000, 1, 100);
        c.offer(key("v|1:1", 1), new float[200], null);
        c.offer(key("v|1:1", 2), new float[200], null);
        assertNotNull(c.get(key("v|1:1", 1))); // 1 is now the most recent
        c.offer(key("v|1:1", 3), new float[200], null);
        assertNotNull(c.get(key("v|1:1", 1)));
        assertNull(c.get(key("v|1:1", 2)));
        assertNotNull(c.get(key("v|1:1", 3)));
        assertTrue(c.bytes() <= 1000);
    }

    @Test
    public void forgetsAnUnloadedVoice() {
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.offer(key("hi_IN-kavya-medium|1:1", 1), new float[] {0.1f}, null);
        c.offer(key("ta_IN-kaveri-medium|1:1", 1), new float[] {0.1f}, null);
        c.forget("hi_IN-kavya-medium|");
        assertNull(c.get(key("hi_IN-kavya-medium|1:1", 1)));
        assertNotNull(c.get(key("ta_IN-kaveri-medium|1:1", 1)));
        assertEquals(1, c.size());
    }
}
