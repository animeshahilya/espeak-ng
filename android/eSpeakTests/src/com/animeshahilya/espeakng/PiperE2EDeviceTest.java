package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end check of Piper natural voices through the real TTS framework.
 * Needs a downloaded English natural voice chosen for English; skipped
 * otherwise. Which engine spoke is read from the WAV sample rate.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PiperE2EDeviceTest {
    private static final String TAG = "PiperE2E";
    private static final int ESPEAK_RATE = 22050;

    private TextToSpeech mTts;
    private File mDir;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mOutcome = new ConcurrentHashMap<>();
    private final java.util.List<Integer> mRanges = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = context.getExternalCacheDir();
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        mTts = new TextToSpeech(context, s -> { status[0] = s; ready.countDown(); },
                context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        assertEquals(TextToSpeech.SUCCESS, status[0]);
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finish(id, "done"); }
            @Override public void onError(String id) { finish(id, "error"); }
            @Override public void onError(String id, int code) { finish(id, "error " + code); }
            @Override public void onStop(String id, boolean interrupted) { finish(id, "stopped"); }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                mRanges.add(frame);
            }
        });
    }

    @After
    public void tearDown() {
        if (mTts != null) {
            mTts.shutdown();
        }
    }

    private void finish(String id, String outcome) {
        mOutcome.putIfAbsent(id, outcome);
        final CountDownLatch latch = mDone.get(id);
        if (latch != null) {
            latch.countDown();
        }
    }

    private CountDownLatch expect(String id) {
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        return latch;
    }

    /** Synthesizes to a WAV and returns {sampleRate, sampleCount, peak, rmsX1000}. */
    private long[] toFile(String id, String text, Locale locale, float rate) throws Exception {
        assertTrue(mTts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        mTts.setSpeechRate(rate);
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = expect(id);
        final long start = System.nanoTime();
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        final long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(id, "done", mOutcome.get(id));
        final long[] stats = wavStats(out);
        Log.i(TAG, String.format(Locale.ROOT, "%s rate=%d samples=%d (%.2fs) peak=%d rms=%.1f wall=%dms",
                id, stats[0], stats[1], stats[1] / (double) stats[0], stats[2], stats[3] / 1000.0, ms));
        return stats;
    }

    private static long[] wavStats(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            final byte[] all = new byte[(int) raf.length()];
            raf.readFully(all);
            assertTrue("no WAV header", all.length > 44);
            final int rate = (all[24] & 0xff) | (all[25] & 0xff) << 8 | (all[26] & 0xff) << 16;
            long peak = 0;
            double sq = 0;
            final int n = (all.length - 44) / 2;
            for (int i = 0; i < n; i++) {
                final int s = (short) ((all[44 + 2 * i] & 0xff) | (all[45 + 2 * i] << 8));
                peak = Math.max(peak, Math.abs(s));
                sq += (double) s * s;
            }
            return new long[]{rate, n, peak, n == 0 ? 0 : (long) (Math.sqrt(sq / n) * 1000)};
        }
    }

    private int warmNaturalEnglish() throws Exception {
        // The first request can go to eSpeak while the model loads.
        for (int i = 0; i < 5; i++) {
            final long[] s = toFile("warm" + i, "Warming up.", Locale.US, 1f);
            if (s[0] != ESPEAK_RATE) {
                return (int) s[0];
            }
            Thread.sleep(2000);
        }
        return ESPEAK_RATE;
    }

    @Test
    public void a_englishSpeaksWithNaturalVoice() throws Exception {
        final int rate = warmNaturalEnglish();
        Assume.assumeTrue("no English natural voice installed/chosen", rate != ESPEAK_RATE);
        final long[] s = toFile("en_natural",
                "Hello! This is a natural voice test. It costs $42.50 on 27 September 2026, "
                        + "and the URL is example.com. Does it sound right?", Locale.US, 1f);
        assertEquals(rate, s[0]);
        assertTrue("too short", s[1] > rate * 3L);
        // Peak reaches full scale by design: PiperAudio peak-normalizes like Piper.
        assertTrue("silent", s[2] > 2000 && s[3] > 200_000);
    }

    @Test
    public void b_speechRateChangesDuration() throws Exception {
        Assume.assumeTrue(warmNaturalEnglish() != ESPEAK_RATE);
        final String text = "The quick brown fox jumps over the lazy dog, again and again.";
        final long normal = toFile("rate1", text, Locale.US, 1f)[1];
        final long fast = toFile("rate2", text, Locale.US, 2f)[1];
        assertTrue("fast " + fast + " vs normal " + normal, fast < normal * 0.8);
    }

    @Test
    public void c_singleCharacterAndSsmlStayWithEspeak() throws Exception {
        Assume.assumeTrue(warmNaturalEnglish() != ESPEAK_RATE);
        assertEquals(ESPEAK_RATE, toFile("char", "a", Locale.US, 1f)[0]);
        final long[] ssml = toFile("ssml",
                "<speak>Hello <break time=\"300ms\"/> world</speak>", Locale.US, 1f);
        assertEquals(ESPEAK_RATE, ssml[0]);
        assertTrue(ssml[2] > 1000);
    }

    @Test
    public void d_languageWithoutNaturalVoiceUsesEspeak() throws Exception {
        final long[] s = toFile("hi", "नमस्ते, आप कैसे हैं?", new Locale("hi", "IN"), 1f);
        assertEquals(ESPEAK_RATE, s[0]);
        assertTrue(s[2] > 1000);
    }

    @Test
    public void e_stopMidUtteranceThenSpeakAgain() throws Exception {
        Assume.assumeTrue(warmNaturalEnglish() != ESPEAK_RATE);
        final StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("This is sentence number ").append(i).append(" of a long paragraph. ");
        }
        final CountDownLatch first = expect("long");
        mTts.speak(longText, TextToSpeech.QUEUE_FLUSH, null, "long");
        Thread.sleep(1500);
        assertEquals(TextToSpeech.SUCCESS, mTts.stop());
        assertTrue("stop never reported", first.await(10, TimeUnit.SECONDS));
        Log.i(TAG, "long -> " + mOutcome.get("long"));
        final CountDownLatch second = expect("after");
        mTts.speak("Still working after stop.", TextToSpeech.QUEUE_FLUSH, null, "after");
        assertTrue(second.await(20, TimeUnit.SECONDS));
        assertEquals("done", mOutcome.get("after"));
    }

    @Test
    public void f_rapidFlushesStayResponsive() throws Exception {
        Assume.assumeTrue(warmNaturalEnglish() != ESPEAK_RATE);
        for (int i = 0; i < 30; i++) {
            mTts.speak("Item " + i + ", moving on quickly.", TextToSpeech.QUEUE_FLUSH, null, "r" + i);
            Thread.sleep(40);
        }
        final CountDownLatch last = expect("final");
        mTts.speak("Final item.", TextToSpeech.QUEUE_FLUSH, null, "final");
        assertTrue("final never finished", last.await(20, TimeUnit.SECONDS));
        assertEquals("done", mOutcome.get("final"));
    }

    @Test
    public void g_queuedUtterancesAllComplete() throws Exception {
        Assume.assumeTrue(warmNaturalEnglish() != ESPEAK_RATE);
        final CountDownLatch[] latches = new CountDownLatch[5];
        for (int i = 0; i < 5; i++) {
            latches[i] = expect("q" + i);
            mTts.speak("Queued " + i + ".", TextToSpeech.QUEUE_ADD, null, "q" + i);
        }
        for (int i = 0; i < 5; i++) {
            assertTrue("q" + i, latches[i].await(30, TimeUnit.SECONDS));
            assertEquals("done", mOutcome.get("q" + i));
        }
    }

    /**
     * Long reading must play without gaps between chunks: wall time of a
     * real speak() may exceed the audio's own length only by the time to
     * the first sound. Uses the Hindi natural voice when one is assigned.
     */
    @Test
    public void i_longReadingFlowsWithoutGaps() throws Exception {
        final Locale hindi = new Locale("hi", "IN");
        final android.content.SharedPreferences prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().createDeviceProtectedStorageContext());
        Assume.assumeTrue("no Hindi natural voice set", prefs.getBoolean("piper_enabled", true)
                && !prefs.getString("piper_voice_hin", "").isEmpty());
        // Until the voice is loaded eSpeak speaks (it may be cold): give it time.
        toFile("hwarm", "नमस्ते, आप कैसे हैं?", hindi, 1f);
        Thread.sleep(5000);
        final StringBuilder text = new StringBuilder();
        // Ten sentences: past TtsService's 800-character watchdog unit, so a
        // unit boundary is crossed too.
        for (int i = 0; i < 10; i++) {
            text.append("आज सुबह हम सब मिलकर पास के बाज़ार गए और वहाँ से ताज़ी सब्ज़ियाँ, फल और दूध ख़रीदकर ")
                    .append("धीरे धीरे बातें करते हुए घर वापस लौट आए, फिर सबने साथ बैठकर खाना बनाया। ");
        }
        final long[] file = toFile("hlong", text.toString(), hindi, 1f);
        final long audioMs = file[1] * 1000 / file[0];
        final CountDownLatch done = expect("hspeak");
        mRanges.clear();
        final long start = System.nanoTime();
        mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hspeak");
        assertTrue(done.await(300, TimeUnit.SECONDS));
        final long wallMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("done", mOutcome.get("hspeak"));
        // Gaps show as wall time beyond the audio's length. Word-range
        // callbacks can't time them: the framework delivers those with
        // jitter of its own. VITS durations vary run to run (about 1%), so
        // the file rendering of the same text is only a close reference.
        // Measured on a Pixel 8, 142 s of Hindi: +6.2 s before rendering
        // ahead (AudioFlinger counted 250835 underrun frames), -0.2 s after
        // (0 underruns).
        final long overheadMs = wallMs - audioMs;
        Log.i(TAG, "long reading: audio=" + audioMs + "ms wall=" + wallMs + "ms overhead="
                + overheadMs + "ms words=" + mRanges.size());
        assertTrue("too few word ranges: " + mRanges.size(), mRanges.size() > 50);
        assertTrue("gaps: " + overheadMs + " ms beyond the audio", overheadMs < 2500);
    }

    @Test
    public void h_voicesListed() {
        assertNotNull(mTts.getVoices());
        assertTrue(mTts.getVoices().size() > 50);
    }
}
