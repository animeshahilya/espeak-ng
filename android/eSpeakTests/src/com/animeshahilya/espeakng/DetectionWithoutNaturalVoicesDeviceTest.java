package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Language detection with eSpeak alone (natural voices off, the default):
 * "Recognise European languages" did nothing there before 2026-10-29, and
 * Marathi in Hindi text was read with Hindi rules. Checked through the
 * routing preview, with the voice that read each part.
 */
@RunWith(AndroidJUnit4.class)
public class DetectionWithoutNaturalVoicesDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    private static final String TAG = "DetectionNoNatural";

    private TextToSpeech mTts;
    private File mDir;
    private SharedPreferences mPrefs;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = context.getExternalCacheDir();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext());
        mPrefs.edit().putBoolean("piper_enabled", false).commit();
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        mTts = new TextToSpeech(context, s -> { status[0] = s; ready.countDown(); }, context.getPackageName());
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

    private Voice voiceFor(String language) {
        for (Voice v : mTts.getVoices()) {
            if (language.equals(v.getLocale().getLanguage()) && v.getName().indexOf('-') < 0) {
                return v;
            }
        }
        for (Voice v : mTts.getVoices()) {
            if (language.equals(v.getLocale().getLanguage())) {
                return v;
            }
        }
        return null;
    }

    /** Each part as language/reader (just the language when its own voice read it). */
    private List<String> plan(String id, String text, Voice voice) throws Exception {
        assertNotNull(voice);
        assertEquals(TextToSpeech.SUCCESS, mTts.setVoice(voice));
        final Bundle show = new Bundle();
        show.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
        TtsService.takeShownPlan();
        final File out = new File(mDir, id + ".wav");
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, show, out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        final List<TtsService.PlanPart> parts = TtsService.takeShownPlan();
        assertNotNull(id + ": no plan", parts);
        final List<String> got = new ArrayList<>();
        for (TtsService.PlanPart p : parts) {
            Log.i(TAG, id + ": " + p.language + " read by " + p.readerLanguage + ": " + p.text);
            got.add(p.language.equals(p.readerLanguage) ? p.language : p.language + "/" + p.readerLanguage);
        }
        return got;
    }

    @Test
    public void europeanLanguages() throws Exception {
        final Voice english = voiceFor("en");
        final String text = "See you soon. Muchas gracias por todo, mi amigo. Ik ben niet thuis, maar ik bel straks.";
        mPrefs.edit().putBoolean("espeak_detect_latin_languages", false).commit();
        assertEquals("[eng]", plan("latin_off", text, english).toString());
        mPrefs.edit().putBoolean("espeak_detect_latin_languages", true).commit();
        assertEquals("[eng, spa, nld]", plan("latin_on", text, english).toString());
    }

    @Test
    public void marathiInHindiText() throws Exception {
        final Voice hindi = voiceFor("hi");
        final String text = "आज बहुत गर्मी है। मी उद्या पुण्याला जाणार आहे।";
        mPrefs.edit().putBoolean("espeak_detect_script_languages", false).commit();
        assertEquals("[hin]", plan("dev_off", text, hindi).toString());
        mPrefs.edit().putBoolean("espeak_detect_script_languages", true).commit();
        assertEquals("[hin, mar]", plan("dev_on", text, hindi).toString());
    }

    @Test
    public void skinTonesSkipped() throws Exception {
        final String thumbs = "Great 👍🏽";
        assertEquals(TextToSpeech.SUCCESS, mTts.setVoice(voiceFor("en")));
        mPrefs.edit().putString("espeak_emoji_processing", "announce").commit();
        final String withTone = textOf("tone_on", thumbs);
        mPrefs.edit().putString("espeak_emoji_processing", "no_skin_tone").commit();
        final String without = textOf("tone_skip", thumbs);
        assertTrue(withTone, withTone.contains("skin tone"));
        assertTrue(without, without.contains("thumbs up") && !without.contains("skin tone"));
    }

    private String textOf(String id, String text) throws Exception {
        final Bundle show = new Bundle();
        show.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
        TtsService.takeShownPlan();
        final File out = new File(mDir, id + ".wav");
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, show, out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        final StringBuilder b = new StringBuilder();
        for (TtsService.PlanPart p : TtsService.takeShownPlan()) {
            b.append(p.text).append(' ');
        }
        Log.i(TAG, id + ": " + b);
        return b.toString();
    }
}
