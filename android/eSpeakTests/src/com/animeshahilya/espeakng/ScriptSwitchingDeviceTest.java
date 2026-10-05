package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * With natural-voice switching on, eSpeak reads a run in a script it would
 * spell letter by letter (Ukrainian inside English) with that language's
 * own voice. Measured by length: spelled, the same words take far longer.
 * Needs no natural voice downloaded; skipped when one is chosen for
 * Ukrainian or Russian (it would speak the run instead).
 */
@RunWith(AndroidJUnit4.class)
public class ScriptSwitchingDeviceTest {
    private static final String TAG = "ScriptSwitching";
    private static final String TEXT = "Привіт, як справи? Дякую, добре. Сьогодні гарна погода.";

    private TextToSpeech mTts;
    private File mDir;
    private SharedPreferences mPrefs;
    private boolean mWasEnabled;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = context.getExternalCacheDir();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext());
        Assume.assumeTrue("a natural voice is chosen for Cyrillic",
                mPrefs.getString("piper_voice_ukr", "").isEmpty()
                        && mPrefs.getString("piper_voice_rus", "").isEmpty());
        mWasEnabled = mPrefs.getBoolean("piper_enabled", false);
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        mTts = new TextToSpeech(context, s -> { status[0] = s; ready.countDown(); },
                context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        assertEquals(TextToSpeech.SUCCESS, status[0]);
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finish(id); }
            @Override public void onError(String id) { finish(id); }
        });
    }

    @After
    public void tearDown() {
        if (mPrefs != null) {
            mPrefs.edit().putBoolean("piper_enabled", mWasEnabled).commit();
        }
        if (mTts != null) {
            mTts.shutdown();
        }
    }

    private void finish(String id) {
        final CountDownLatch latch = mDone.get(id);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Seconds of audio for {@link #TEXT} in {@code locale}. */
    private double seconds(String id, Locale locale) throws Exception {
        assertTrue(mTts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(TEXT, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        // 16-bit mono at eSpeak's 22050 Hz after the 44-byte header.
        final double s = (out.length() - 44) / 2.0 / 22050;
        Log.i(TAG, String.format(Locale.ROOT, "%s %.2fs", id, s));
        return s;
    }

    @Test
    public void cyrillicRunIsReadAsWordsNotLetters() throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", false).commit();
        final double spelled = seconds("off", Locale.US);
        final double ukrainian = seconds("uk", new Locale("uk"));
        mPrefs.edit().putBoolean("piper_enabled", true).commit();
        final double read = seconds("on", Locale.US);
        final String got = String.format(Locale.ROOT, "English voice %.2fs, Ukrainian voice %.2fs, spelled %.2fs",
                read, ukrainian, spelled);
        // Read by the Ukrainian voice: as long as that voice alone, and well
        // short of the English voice naming every letter.
        assertTrue(got, Math.abs(read - ukrainian) < 0.2 * ukrainian);
        assertTrue(got, read < spelled * 0.7);
    }
}
