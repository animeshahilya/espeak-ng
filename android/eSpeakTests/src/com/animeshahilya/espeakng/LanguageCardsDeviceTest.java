package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.ui.LanguageCards;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Per-language speed (Settings -> Mixed-language text -> Speech by language),
 * measured by the length of what the TTS service writes: a Hindi card makes
 * Hindi slower or faster, on eSpeak and on a natural voice, and leaves
 * English exactly as it was.
 */
@RunWith(AndroidJUnit4.class)
public class LanguageCardsDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    private static final String TAG = "LanguageCards";
    private static final String HINDI = "आज मौसम बहुत अच्छा है और हम सब बाज़ार जा रहे हैं।";
    private static final String ENGLISH = "The weather is lovely today and we are all going to the market.";
    private static final String HINDI_SPEED = LanguageCards.key("hin", LanguageCards.SPEED);

    private TextToSpeech mTts;
    private File mDir;
    private SharedPreferences mPrefs;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = context.getExternalCacheDir();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext());
        mPrefs.edit().remove(HINDI_SPEED).commit();
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        mTts = new TextToSpeech(context, s -> {
            status[0] = s;
            ready.countDown();
        }, context.getPackageName());
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

    /** Seconds of audio the service writes for {@code text}. */
    private double seconds(String id, String text, Locale locale) throws Exception {
        mTts.setLanguage(locale);
        mTts.setSpeechRate(1f);
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        try (RandomAccessFile raf = new RandomAccessFile(out, "r")) {
            final byte[] header = new byte[44];
            raf.readFully(header);
            final int rate = (header[24] & 0xff) | (header[25] & 0xff) << 8 | (header[26] & 0xff) << 16;
            final double s = (raf.length() - 44) / 2.0 / rate;
            Log.i(TAG, String.format(Locale.ROOT, "%s %.2fs", id, s));
            return s;
        }
    }

    @Test
    public void espeakHindiFollowsItsCardAndEnglishDoesNot() throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", false).commit();
        final double hindi = seconds("hi_plain", HINDI, new Locale("hi"));
        final double english = seconds("en_plain", ENGLISH, Locale.US);

        mPrefs.edit().putString(HINDI_SPEED, "50").commit();
        final double slower = seconds("hi_slow", HINDI, new Locale("hi"));
        final double englishAfter = seconds("en_after", ENGLISH, Locale.US);
        assertTrue("Hindi at 50%: " + slower + " vs " + hindi, slower > hindi * 1.5);
        // eSpeak is deterministic in rate: English is untouched to the sample.
        assertEquals("English unchanged", english, englishAfter, 0.001);

        mPrefs.edit().putString(HINDI_SPEED, "").commit();
        assertEquals("cleared: as before", hindi, seconds("hi_again", HINDI, new Locale("hi")), 0.001);
    }

    @Test
    public void naturalVoiceFollowsItsLanguageCard() throws Exception {
        Assume.assumeTrue("no Hindi natural voice chosen", !mPrefs.getString("piper_voice_hin", "").isEmpty());
        mPrefs.edit().putBoolean("piper_enabled", true).commit();
        // The first requests can go to eSpeak while the voice loads.
        boolean natural = false;
        for (int i = 0; i < 6 && !natural; i++) {
            final long before = PiperEngine.renderedChunks();
            seconds("hi_warm" + i, HINDI, new Locale("hi"));
            natural = PiperEngine.renderedChunks() > before;
            if (!natural) {
                Thread.sleep(1500);
            }
        }
        Assume.assumeTrue("the Hindi natural voice did not load", natural);
        final double plain = seconds("hi_nat_plain", HINDI, new Locale("hi"));
        mPrefs.edit().putString(HINDI_SPEED, "150").commit();
        final double faster = seconds("hi_nat_fast", HINDI, new Locale("hi"));
        assertTrue("Hindi natural voice at 150%: " + faster + " vs " + plain, faster < plain * 0.8);
    }
}
