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
import com.animeshahilya.espeakng.piper.PiperVoiceStore;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Letter-reading natural voices (SYSPIN/Rasa) say numbers themselves: a time
 * and a lakh amount in their language's text stay with the voice, where
 * before only Hindi, Gujarati, Tamil and Nepali did and the rest handed the
 * digits to eSpeak. Runs for every such voice installed on the phone.
 */
@RunWith(AndroidJUnit4.class)
public class NumberWordsDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    /** "Time" in each language, so the run is plainly that language's. */
    private static final Map<String, String> WORD = new HashMap<>();

    static {
        WORD.put("bn", "সময়");
        WORD.put("gu", "સમય");
        WORD.put("hi", "समय");
        WORD.put("kn", "ಸಮಯ");
        WORD.put("ml", "സമയം");
        WORD.put("mr", "वेळ");
        WORD.put("ne", "समय");
        WORD.put("pa", "ਸਮਾਂ");
        WORD.put("ta", "நேரம்");
        WORD.put("te", "సమయం");
        for (String devanagari : new String[] {"bho", "brx", "doi", "hne", "mag", "mai", "sa"}) {
            WORD.put(devanagari, "समय");
        }
    }

    private TextToSpeech mTts;
    private Context mStorage;
    private SharedPreferences mPrefs;
    private File mDir;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mStorage = context.createDeviceProtectedStorageContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mStorage);
        mDir = context.getExternalCacheDir();
        mPrefs.edit().putBoolean("piper_enabled", true).commit();
        final CountDownLatch ready = new CountDownLatch(1);
        mTts = new TextToSpeech(context, s -> ready.countDown(), context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
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

    /** Seconds of audio in a WAV the service wrote. */
    private static double seconds(File wav) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(wav, "r")) {
            final byte[] header = new byte[44];
            raf.readFully(header);
            final int rate = (header[24] & 0xff) | (header[25] & 0xff) << 8 | (header[26] & 0xff) << 16;
            return (raf.length() - 44) / 2.0 / rate;
        }
    }

    private List<TtsService.PlanPart> plan(String id, String text, Locale locale) throws Exception {
        final Bundle show = new Bundle();
        show.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
        TtsService.takeShownPlan();
        mTts.setLanguage(locale);
        final File out = new File(mDir, id + ".wav");
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, show, out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        return TtsService.takeShownPlan();
    }

    private File renderTo(String id, String text, Locale locale) throws Exception {
        plan(id, text, locale);
        return new File(mDir, id + ".wav");
    }

    @Test
    public void textVoicesReadTheirOwnNumbers() throws Exception {
        int checked = 0;
        for (PiperVoiceStore.Installed voice : PiperVoiceStore.list(mStorage)) {
            final String family = voice.config.languageFamily;
            if (voice.config.usesEspeak() || voice.config.isSupertonic() || !WORD.containsKey(family)) {
                continue;
            }
            assertNotNull(family + " has number words", PiperEngine.numberWords(family));
            final String languageKey = PiperVoiceStore.languageKey(family);
            final String before = PiperVoiceStore.assignedKey(mPrefs, languageKey);
            PiperVoiceStore.assign(mPrefs, languageKey, voice.key);
            try {
                final Locale locale = new Locale(family, "IN");
                final String text = WORD.get(family) + " 10:30, 1,00,000";
                // The first requests start loading the voice; eSpeak reads meanwhile.
                List<TtsService.PlanPart> parts = null;
                for (int i = 0; i < 15; i++) {
                    parts = plan(voice.key + "_" + i, text, locale);
                    if (parts != null && !parts.isEmpty() && voice.key.equals(parts.get(0).naturalKey)) {
                        break;
                    }
                    Thread.sleep(1000);
                }
                assertNotNull(parts);
                Log.i("NumberWords", voice.key + ": " + parts.size() + " part(s)");
                Assume.assumeTrue(voice.key + " did not load", voice.key.equals(parts.get(0).naturalKey));
                for (TtsService.PlanPart part : parts) {
                    assertEquals(voice.key + " reads \"" + part.text + "\"", voice.key, part.naturalKey);
                }
                // The numbers are spoken, not dropped: they add speech to the word.
                final double word = seconds(renderTo(voice.key + "_word", WORD.get(family), locale));
                final double withNumbers = seconds(renderTo(voice.key + "_numbers", text, locale));
                Log.i("NumberWords", String.format(Locale.ROOT, "%s word %.2fs, with numbers %.2fs",
                        voice.key, word, withNumbers));
                assertTrue(voice.key + " numbers spoken", withNumbers > word + 0.6);
                checked++;
            } finally {
                PiperVoiceStore.assign(mPrefs, languageKey, before);
            }
        }
        Assume.assumeTrue("no letter-reading voice installed", checked > 0);
        Log.i("NumberWords", checked + " voice(s) checked");
    }
}
