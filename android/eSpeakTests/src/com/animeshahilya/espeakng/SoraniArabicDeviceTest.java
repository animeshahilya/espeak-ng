package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A Kurdish tester (2026-10-29) set Arabic script to Central Kurdish and then
 * heard Arabic read as Kurdish. With "Detect the language of each sentence"
 * each sentence goes to its own eSpeak voice, natural voices on or off.
 * Checked through the routing preview (who read each part) and by length.
 */
@RunWith(AndroidJUnit4.class)
public class SoraniArabicDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    private static final String TAG = "SoraniArabic";
    private static final String SORANI_1 = "ئەمڕۆ کەشوهەوا زۆر خۆشە.";
    private static final String ARABIC = "الحمد لله رب العالمين.";
    private static final String SORANI_2 = "سوپاس بۆ یارمەتییەکەت.";
    private static final String MIXED = SORANI_1 + " " + ARABIC + " " + SORANI_2;

    private TextToSpeech mTts;
    private File mDir;
    private SharedPreferences mPrefs;
    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDir = context.getExternalCacheDir();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext());
        // No natural voice for these languages: eSpeak reads every part.
        mPrefs.edit().remove("piper_voice_ara").remove("piper_voice_ckb").remove("piper_voice_fas")
                .putString("espeak_script_ar", "ckb").commit();
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

    /** Seconds of audio for {@code text} read by {@code voice}. */
    private double seconds(String id, String text, Voice voice, Bundle params) throws Exception {
        assertNotNull(voice);
        assertEquals(TextToSpeech.SUCCESS, mTts.setVoice(voice));
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, params, out, id));
        assertTrue(id + " timed out", latch.await(60, TimeUnit.SECONDS));
        final double s = (out.length() - 44) / 2.0 / 22050;
        Log.i(TAG, String.format(Locale.ROOT, "%s %.2fs", id, s));
        return s;
    }

    /** The reader language of each part, from the routing preview. */
    private List<String> plan(String id, String text, Voice voice) throws Exception {
        final Bundle show = new Bundle();
        show.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
        TtsService.takeShownPlan();
        seconds(id, text, voice, show);
        final List<TtsService.PlanPart> parts = TtsService.takeShownPlan();
        assertNotNull(id + ": no plan recorded", parts);
        final List<String> out = new ArrayList<>();
        for (TtsService.PlanPart p : parts) {
            assertNull(id + ": eSpeak reads it", p.naturalKey);
            out.add(p.language);
            Log.i(TAG, id + " part " + p.language + ": " + p.text);
        }
        return out;
    }

    private void checkEveryVoice(boolean natural) throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", natural).commit();
        final Voice english = voiceFor("en");
        final Voice sorani = voiceFor("ckb");
        assertNotNull("no Central Kurdish voice", sorani);
        final String n = natural ? "on" : "off";

        mPrefs.edit().putBoolean("espeak_detect_script_languages", false).commit();
        // The tester's report: Arabic script set to Kurdish reads it all as Kurdish.
        final List<String> before = plan("before_" + n, MIXED, english);
        assertTrue("before: " + before, !before.contains("ara"));

        mPrefs.edit().putBoolean("espeak_detect_script_languages", true).commit();
        assertEquals("[ckb, ara, ckb]", plan("en_" + n, MIXED, english).toString());
        assertEquals("[ckb, ara, ckb]", plan("ckb_" + n, MIXED, sorani).toString());
        // Arabic voice: its own text stays its own, Kurdish goes to Kurdish.
        final Voice arabic = voiceFor("ar");
        assertEquals("[ckb, ara, ckb]", plan("ar_" + n, MIXED, arabic).toString());
        // One sentence alone is one unit, in the other language's voice.
        assertEquals("[ckb]", plan("ar_one_" + n, SORANI_1, arabic).toString());
        assertEquals("[ara]", plan("ckb_one_" + n, ARABIC, sorani).toString());
        assertEquals("[ara]", plan("en_one_" + n, ARABIC, english).toString());
    }

    @Test
    public void naturalVoicesOff() throws Exception {
        checkEveryVoice(false);
    }

    @Test
    public void naturalVoicesOn() throws Exception {
        checkEveryVoice(true);
    }

    /**
     * Pixel 8, 2026-10-29, seconds: with one fixed choice, one language was
     * garbled whatever the tester picked. English voice, Arabic script as
     * eSpeak's default: Arabic 1.48, Sorani 15.69 (letters named); set to
     * Kurdish: Arabic 4.73, Sorani 1.38. The Arabic voice: Sorani 10.11.
     * Each language's own voice: Arabic 1.61, Sorani 1.34.
     */
    @Test
    public void eachSentenceSoundsLikeItsOwnVoice() throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", false).putBoolean("espeak_detect_script_languages", false)
                .commit();
        final Voice english = voiceFor("en");
        final Voice arabic = voiceFor("ar");
        final Voice sorani = voiceFor("ckb");
        // Warm-up: eSpeak's lengths drift with the utterances before them.
        seconds("warm", ARABIC, arabic, new Bundle());
        final double arabicAlone = seconds("ar", ARABIC, arabic, new Bundle());
        final double soraniAlone = seconds("ckb", SORANI_1, sorani, new Bundle());
        final double arabicLocked = seconds("ar_locked", ARABIC, english, new Bundle());
        final double soraniInArabic = seconds("ckb_in_ar", SORANI_1, arabic, new Bundle());
        mPrefs.edit().putBoolean("espeak_detect_script_languages", true).commit();
        final double arabicDetected = seconds("ar_detected", ARABIC, english, new Bundle());
        final double soraniDetected = seconds("ckb_detected", SORANI_1, english, new Bundle());
        final double soraniInArabicDetected = seconds("ckb_in_ar_detected", SORANI_1, arabic, new Bundle());
        final double arabicInSoraniDetected = seconds("ar_in_ckb_detected", ARABIC, sorani, new Bundle());
        final String got = String.format(Locale.ROOT,
                "Arabic: own voice %.2fs, English+Kurdish choice %.2fs -> detected %.2fs; "
                        + "Sorani: own voice %.2fs, English %.2fs, Arabic voice %.2fs -> %.2fs; "
                        + "Arabic in the Kurdish voice %.2fs",
                arabicAlone, arabicLocked, arabicDetected, soraniAlone, soraniDetected, soraniInArabic,
                soraniInArabicDetected, arabicInSoraniDetected);
        Log.i(TAG, got);
        assertTrue(got, Math.abs(arabicDetected - arabicAlone) < 0.15 * arabicAlone);
        assertTrue(got, Math.abs(soraniDetected - soraniAlone) < 0.15 * soraniAlone);
        assertTrue(got, Math.abs(soraniInArabicDetected - soraniAlone) < 0.15 * soraniAlone);
        assertTrue(got, Math.abs(arabicInSoraniDetected - arabicAlone) < 0.15 * arabicAlone);
        assertTrue(got, arabicLocked > 2 * arabicAlone && soraniInArabic > 3 * soraniAlone);
    }
}
