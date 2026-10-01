package com.animeshahilya.espeakng;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * The natural-voice phrase cache on a TalkBack-like session, in minutes
 * instead of a day of use: replays tools/talkback_trace.py's trace (pushed to
 * the app's external files dir as trace.txt) through the real TTS API into
 * this engine, rendering to a file so it runs faster than real time. Not
 * pass/fail: read "PiperVoices: Phrase cache" and "PhraseReplay" in logcat.
 * Skipped without a trace. Needs a natural voice for the language (default
 * en-IN; pass -e language hi-IN etc.).
 *
 * <p>Pixel 8, Priya Compact (CPU), 258 utterances of real Pixel screens: 60%
 * of chunks from the cache (ceiling 64%), 5 ms to first audio vs 540 ms.
 */
@RunWith(AndroidJUnit4.class)
public class PhraseCacheReplayDeviceTest {
    @Test
    public void replay() throws Exception {
        final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File dir = app.getExternalFilesDir(null);
        final File traceFile = new File(dir, "trace.txt");
        Assume.assumeTrue("no " + traceFile, traceFile.isFile());
        final List<String> trace = Files.readAllLines(traceFile.toPath(), StandardCharsets.UTF_8);
        final String language = InstrumentationRegistry.getArguments().getString("language", "en-IN");

        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech[] tts = new TextToSpeech[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                tts[0] = new TextToSpeech(app, status -> ready.countDown(), app.getPackageName()));
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        tts[0].setLanguage(Locale.forLanguageTag(language));
        final File out = new File(dir, "replay.wav");
        Thread.sleep(8000); // the natural voice loads
        final long t0 = System.nanoTime();
        int done = 0;
        for (int i = 0; i < trace.size(); i++) {
            final String text = trace.get(i).trim();
            if (text.isEmpty()) {
                continue;
            }
            final CountDownLatch finished = new CountDownLatch(1);
            tts[0].setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { finished.countDown(); }
                @Override public void onError(String id) { finished.countDown(); }
            });
            tts[0].synthesizeToFile(text, null, out, "u" + i);
            if (finished.await(30, TimeUnit.SECONDS)) {
                done++;
            }
        }
        Log.i("PhraseReplay", String.format(Locale.ROOT, "%d of %d utterances in %.1f s",
                done, trace.size(), (System.nanoTime() - t0) / 1e9));
        tts[0].shutdown();
        out.delete();
    }
}
