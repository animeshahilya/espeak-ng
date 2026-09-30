package com.animeshahilya.espeakng;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Time to first audio of the chosen natural voices, through the TTS API.
 * Not pass/fail: read "PiperLatency" from logcat. A long first sentence
 * makes a long first chunk, where rendering it in pieces matters. The
 * first run of each language is kept as WAV in the app's external files
 * (piper-latency/) to listen to. Measure with the phone cool.
 */
@RunWith(AndroidJUnit4.class)
public class PiperLatencyDeviceTest {
    private static final String TAG = "PiperLatency";
    private static final int RUNS = 5;

    private static final String EN = "The quick brown fox jumps over the lazy dog near the old "
            + "river bank before the sun goes down behind the tall green hills tonight. "
            + "Then it runs home.";
    private static final String HI = "आज सुबह हम सब मिलकर पुराने बाज़ार से ताज़ी सब्ज़ियाँ और "
            + "मीठे आम ख़रीदने के लिए बहुत दूर तक पैदल चलकर गए थे। फिर हम घर लौटे।";

    private TextToSpeech mTts;
    private File mDir;
    private final Map<String, Long> mFirstAudio = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> mDone = new ConcurrentHashMap<>();
    private final Map<String, String> mOutcome = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = ctx.getExternalFilesDir("piper-latency");
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        mTts = new TextToSpeech(ctx, s -> {
            status[0] = s;
            ready.countDown();
        }, ctx.getPackageName());
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        assertEquals(TextToSpeech.SUCCESS, status[0]);
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finish(id, "done"); }
            @Override public void onError(String id) { finish(id, "error"); }
            @Override public void onAudioAvailable(String id, byte[] audio) {
                mFirstAudio.putIfAbsent(id, System.nanoTime());
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

    /** {first-audio ms, wall ms, sample rate, samples, peak}. */
    private long[] synth(String id, String text) throws Exception {
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        final long start = System.nanoTime();
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        final long wall = (System.nanoTime() - start) / 1_000_000;
        assertEquals(id, "done", mOutcome.get(id));
        final Long first = mFirstAudio.get(id);
        final byte[] all = java.nio.file.Files.readAllBytes(out.toPath());
        final int rate = (all[24] & 0xff) | (all[25] & 0xff) << 8 | (all[26] & 0xff) << 16;
        int peak = 0;
        for (int i = 44; i + 1 < all.length; i += 2) {
            peak = Math.max(peak, Math.abs((short) ((all[i] & 0xff) | (all[i + 1] << 8))));
        }
        return new long[] {first == null ? -1 : (first - start) / 1_000_000, wall, rate,
                (all.length - 44) / 2, peak};
    }

    private void measure(String name, Locale locale, String text) throws Exception {
        assertTrue(mTts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        mTts.setSpeechRate(1f);
        // The first request goes to eSpeak while the model loads (~1 s);
        // natural voices are too quiet-peaked (-20 dBFS speech) to tell
        // apart by level, so just warm up well past the load. Check the
        // WAV lengths: eSpeak reads the Hindi text in ~8 s, Priyamvada ~10.
        synth(name + "_warm0", text);
        for (int i = 1; i < 4; i++) {
            Thread.sleep(2000);
            synth(name + "_warm" + i, text);
        }
        final List<Long> firsts = new ArrayList<>();
        final List<Long> walls = new ArrayList<>();
        long[] r = null;
        for (int i = 0; i < RUNS; i++) {
            Thread.sleep(4000); // let the SoC cool between measurements
            r = synth(i == 0 ? name : name + "_" + i, text);
            firsts.add(r[0]);
            walls.add(r[1]);
        }
        Collections.sort(firsts);
        Collections.sort(walls);
        Log.i(TAG, String.format(Locale.ROOT,
                "%s firstAudio median=%dms (min %d, max %d) wall median=%dms audio=%.2fs rate=%d peak=%d",
                name, firsts.get(RUNS / 2), firsts.get(0), firsts.get(RUNS - 1), walls.get(RUNS / 2),
                r[3] / (double) r[2], r[2], r[4]));
    }

    /**
     * Stops landing while the first chunk's second piece is still decoding
     * (a TalkBack swipe): the run must end cleanly and speech go on working.
     */
    @Test
    public void stopDuringFirstChunkPieces() throws Exception {
        assertTrue(mTts.setLanguage(new Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE);
        synth("stop_warm0", HI);
        Thread.sleep(2000);
        synth("stop_warm1", HI);
        final Bundle quiet = new Bundle();
        quiet.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f);
        for (int i = 0; i < 30; i++) {
            assertEquals(TextToSpeech.SUCCESS, mTts.speak(HI, TextToSpeech.QUEUE_FLUSH, quiet, "stop" + i));
            Thread.sleep(40 + (i % 6) * 60); // 40-340 ms: before, during and after the first piece
            assertEquals(TextToSpeech.SUCCESS, mTts.stop());
        }
        final long[] after = synth("stop_after", HI);
        assertTrue("speech still works", after[3] > after[2] * 5L);
        Log.i(TAG, "stops done, then " + after[3] / (double) after[2] + " s of audio");
    }

    @Test
    public void firstAudioLatency() throws Exception {
        measure("hi", new Locale("hi", "IN"), HI);
        measure("en", Locale.US, EN);
    }
}
