package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.piper.PiperPhraseCache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

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

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static int files(File dir) {
        final String[] names = dir.list((d, n) -> n.endsWith(".pcm"));
        return names == null ? 0 : names.length;
    }

    @Test
    public void onStorageOnlyOnceHeardTwiceAndReadBackAfterARestart() throws Exception {
        final File dir = tmp.newFolder("phrase-cache");
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.setDisk(dir, 1 << 20, 2);
        final PiperPhraseCache.Key k = key("hi_IN-kavya-medium|10:20", 5, 6, 7);
        c.offer(k, new float[] {0.25f, -0.5f}, new float[] {1, 2, 3});
        assertEquals("heard once: memory only", 0, files(dir));
        c.offer(k, new float[] {0.25f, -0.5f}, new float[] {1, 2, 3});
        assertEquals(1, files(dir));

        // A new process: empty memory, same folder.
        final PiperPhraseCache after = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        after.setDisk(dir, 1 << 20, 2);
        final PiperPhraseCache.Entry e = after.get(k);
        assertNotNull(e);
        assertArrayEquals(new float[] {0.25f, -0.5f}, e.audio, 1e-4f);
        assertArrayEquals(new float[] {1, 2, 3}, e.durations, 0f);
        assertEquals(1, after.diskHits());
        assertNull("updated model file", after.get(key("hi_IN-kavya-medium|11:30", 5, 6, 7)));
    }

    @Test
    public void deletingAVoiceDeletesItsStoredPhrases() throws Exception {
        final File dir = tmp.newFolder("phrase-cache");
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.setDisk(dir, 1 << 20, 1);
        c.offer(key("hi_IN-kavya-medium|1:1", 1), new float[] {0.1f}, null);
        c.offer(key("ta_IN-kaveri-medium|1:1", 1), new float[] {0.1f}, null);
        assertEquals(2, files(dir));
        c.deleteVoice("hi_IN-kavya-medium");
        assertEquals(1, files(dir));
        assertNull(c.get(key("hi_IN-kavya-medium|1:1", 1)));
        assertNotNull(c.get(key("ta_IN-kaveri-medium|1:1", 1)));
    }

    @Test
    public void storageStaysUnderItsCap() throws Exception {
        final File dir = tmp.newFolder("phrase-cache");
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 100_000, 1, 1000);
        c.setDisk(dir, 50_000, 1); // each file ~20 KB: at most two stay
        for (int i = 0; i < 25; i++) {
            c.offer(key("v|1:1", i), new float[10_000], null);
        }
        long total = 0;
        for (File f : dir.listFiles((d, n) -> n.endsWith(".pcm"))) {
            total += f.length();
        }
        // Trimmed every 20 writes: at most 19 files past the cap in between.
        assertTrue(total + " bytes", total <= 50_000 + 19 * 20_100);
        assertTrue(total + " bytes", files(dir) < 25);
    }

    /** Kept in memory from its first sighting, a phrase's repeats are hits: they count too. */
    @Test
    public void hitsCountTowardsStorage() throws Exception {
        final File dir = tmp.newFolder("phrase-cache");
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.setDisk(dir, 1 << 20, 2);
        final PiperPhraseCache.Key k = key("v|1:1", 9);
        c.offer(k, new float[] {0.3f}, null); // first sighting: memory
        assertEquals(0, files(dir));
        assertNotNull(c.get(k));              // second sighting, a hit
        assertEquals(1, files(dir));
    }

    @Test
    public void corruptCacheFileIsIgnoredAndDeleted() throws Exception {
        final File dir = tmp.newFolder("phrase-cache");
        final PiperPhraseCache c = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        c.setDisk(dir, 1 << 20, 1);
        final PiperPhraseCache.Key k = key("v|1:1", 42);
        c.offer(k, new float[] {0.5f}, null);
        assertEquals(1, files(dir));

        // Overwrite the cache file with corrupt contents (e.g. invalid large array length)
        final File[] list = dir.listFiles((d, n) -> n.endsWith(".pcm"));
        assertNotNull(list);
        assertEquals(1, list.length);
        final File cacheFile = list[0];
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.FileOutputStream(cacheFile))) {
            out.writeInt(0x50495045); // MAGIC
            out.writeUTF("v|1:1");
            out.writeInt(0);
            out.writeFloat(1f);
            out.writeFloat(1f);
            out.writeFloat(1f);
            out.writeInt(999_999_999); // Excessive nIds
        }

        // New cache reads from disk and gracefully discards corrupt file
        final PiperPhraseCache after = new PiperPhraseCache(1 << 20, 1000, 1, 100);
        after.setDisk(dir, 1 << 20, 1);
        assertNull(after.get(k));
        assertEquals(0, files(dir));
    }
}
