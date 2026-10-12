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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
    private static final double EPSILON = 0.025;

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
        return seconds(id, text, locale, new Bundle());
    }

    private double seconds(String id, String text, Locale locale, Bundle params) throws Exception {
        mTts.setLanguage(locale);
        mTts.setSpeechRate(1f);
        final File out = new File(mDir, id + ".wav");
        out.delete();
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, mTts.synthesizeToFile(text, params, out, id));
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

    /**
     * eSpeak's audio generator carries state from one utterance to the next:
     * the first after the service starts comes out ~15 ms longer, and later
     * ones can differ by about as much. One throwaway utterance first, and
     * lengths compared within {@link #EPSILON} (a cue is 100 ms or more).
     */
    private void warmUp() throws Exception {
        seconds("warm_up", ENGLISH, Locale.US);
    }

    @Test
    public void espeakHindiFollowsItsCardAndEnglishDoesNot() throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", false).commit();
        warmUp();
        final double hindi = seconds("hi_plain", HINDI, new Locale("hi"));
        final double english = seconds("en_plain", ENGLISH, Locale.US);

        mPrefs.edit().putString(HINDI_SPEED, "50").commit();
        final double slower = seconds("hi_slow", HINDI, new Locale("hi"));
        final double englishAfter = seconds("en_after", ENGLISH, Locale.US);
        assertTrue("Hindi at 50%: " + slower + " vs " + hindi, slower > hindi * 1.5);
        assertEquals("English unchanged", english, englishAfter, EPSILON);

        mPrefs.edit().putString(HINDI_SPEED, "").commit();
        assertEquals("cleared: as before", hindi, seconds("hi_again", HINDI, new Locale("hi")), EPSILON);
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

    /**
     * A card's switch cue plays where speech switches into its language:
     * Ukrainian inside English (always its own eSpeak unit), so the length
     * grows by exactly the cue. No English or Ukrainian natural voice for it.
     */
    @Test
    public void switchCuePlaysWhereSpeechSwitchesIntoTheLanguage() throws Exception {
        Assume.assumeTrue("a natural voice is chosen for Ukrainian", mPrefs.getString("piper_voice_ukr", "").isEmpty());
        final String english = mPrefs.getString("piper_voice_eng", "");
        mPrefs.edit().putBoolean("piper_enabled", true).putBoolean("piper_switch_languages", true)
                .remove("piper_voice_eng").commit();
        try {
            checkSwitchCues();
        } finally {
            // Other tests of this class need it (the class rule restores only at the end).
            mPrefs.edit().putString("piper_voice_eng", english).commit();
        }
    }

    private void checkSwitchCues() throws Exception {
        final String text = "Hello there. Привіт, як справи? Thank you very much.";
        final String ukrainianCue = LanguageCards.key("ukr", LanguageCards.SWITCH_CUE);
        final String englishCue = LanguageCards.key("eng", LanguageCards.SWITCH_CUE);
        warmUp();
        final double plain = seconds("cue_plain", text, Locale.US);
        mPrefs.edit().putString(ukrainianCue, "pause").commit();
        assertEquals("pause into Ukrainian", plain + 0.25, seconds("cue_pause", text, Locale.US), EPSILON);
        mPrefs.edit().putString(ukrainianCue, "tone").commit();
        assertEquals("tone into Ukrainian", plain + 0.10, seconds("cue_tone", text, Locale.US), EPSILON);
        mPrefs.edit().putString(englishCue, "pause").commit();
        assertEquals("and a pause back into English", plain + 0.35, seconds("cue_both", text, Locale.US), EPSILON);
        mPrefs.edit().remove(ukrainianCue).remove(englishCue).commit();
        assertEquals("cleared: as before", plain, seconds("cue_none", text, Locale.US), EPSILON);
    }

    /** Natural-voice chunks spoken for {@code text}: how many pieces natural voices read. */
    private long naturalChunks(String id, String text, Locale locale) throws Exception {
        final long before = PiperEngine.renderedChunks();
        seconds(id, text, locale);
        return PiperEngine.renderedChunks() - before;
    }

    /**
     * "Voice when inside other text": English inside Hindi, Hindi and English
     * natural voices loaded. eSpeak for the English word leaves one natural
     * piece fewer; the surrounding voice reads the sentence as one piece.
     */
    @Test
    public void voiceWhenMixedPicksWhoReadsTheEnglishWord() throws Exception {
        Assume.assumeTrue("needs Hindi and English natural voices",
                !mPrefs.getString("piper_voice_hin", "").isEmpty()
                        && !mPrefs.getString("piper_voice_eng", "").isEmpty());
        mPrefs.edit().putBoolean("piper_enabled", true).putBoolean("piper_switch_languages", true).commit();
        final String text = "आज मेरी meeting कल सुबह दस बजे है।";
        final String englishMixed = LanguageCards.key("eng", LanguageCards.WHEN_MIXED);
        // Both voices loaded (the first requests can go to eSpeak meanwhile).
        for (int i = 0; i < 6; i++) {
            if (naturalChunks("mix_warm" + i, text, new Locale("hi")) >= 3) {
                break;
            }
            seconds("en_warm" + i, ENGLISH, Locale.US);
            Thread.sleep(1500);
        }
        final long usual = naturalChunks("mix_usual", text, new Locale("hi"));
        Assume.assumeTrue("both natural voices did not load: " + usual, usual >= 3);
        mPrefs.edit().putString(englishMixed, "espeak").commit();
        final long espeak = naturalChunks("mix_espeak", text, new Locale("hi"));
        mPrefs.edit().putString(englishMixed, "surrounding").commit();
        final long surrounding = naturalChunks("mix_surrounding", text, new Locale("hi"));
        mPrefs.edit().remove(englishMixed).commit();
        Log.i(TAG, "natural chunks: usual " + usual + ", eSpeak " + espeak + ", surrounding " + surrounding);
        assertEquals("eSpeak reads the English word", usual - 1, espeak);
        assertTrue("one voice, fewer pieces: " + surrounding, surrounding >= 1 && surrounding < usual);
    }

    /** A language card's numbers choice: Hindi text reads its time in English words when set. */
    @Test
    public void numbersFollowTheLanguagesCard() throws Exception {
        mPrefs.edit().putBoolean("piper_enabled", false).commit();
        final String text = "बैठक सुबह 10:30 बजे है।";
        final String hindiNumbers = LanguageCards.key("hin", LanguageCards.NUMBERS);
        warmUp();
        final double plain = seconds("num_plain", text, new Locale("hi"));
        mPrefs.edit().putString(hindiNumbers, "en").commit();
        final double english = seconds("num_english", text, new Locale("hi"));
        mPrefs.edit().remove(hindiNumbers).commit();
        final double cleared = seconds("num_cleared", text, new Locale("hi"));
        assertTrue("English number words: " + english + " vs " + plain, Math.abs(english - plain) > 0.1);
        assertEquals("cleared: as before", plain, cleared, EPSILON);
    }

    /** A language card's sensitivity: one English word in Hindi stays with the Hindi voice at "phrases". */
    @Test
    public void switchAtFollowsTheLanguagesCard() throws Exception {
        Assume.assumeTrue("needs Hindi and English natural voices",
                !mPrefs.getString("piper_voice_hin", "").isEmpty()
                        && !mPrefs.getString("piper_voice_eng", "").isEmpty());
        mPrefs.edit().putBoolean("piper_enabled", true).putBoolean("piper_switch_languages", true).commit();
        final String text = "आज मेरी meeting कल सुबह दस बजे है।";
        final String englishSwitch = LanguageCards.key("eng", LanguageCards.SWITCH_AT);
        for (int i = 0; i < 6; i++) {
            if (naturalChunks("sw_warm" + i, text, new Locale("hi")) >= 3) {
                break;
            }
            seconds("sw_en_warm" + i, ENGLISH, Locale.US);
            Thread.sleep(1500);
        }
        final long words = naturalChunks("sw_words", text, new Locale("hi"));
        Assume.assumeTrue("both natural voices did not load: " + words, words >= 3);
        mPrefs.edit().putString(englishSwitch, "phrases").commit();
        final long phrases = naturalChunks("sw_phrases", text, new Locale("hi"));
        mPrefs.edit().remove(englishSwitch).commit();
        Log.i(TAG, "natural chunks: words " + words + ", phrases " + phrases);
        assertEquals("the lone English word stays with Hindi", 1, phrases);
    }

    /**
     * The routing preview (Try your own text) shows the plan speech used:
     * Hindi, the English word, Hindi - and the English word's eSpeak reader
     * once its card says so. Requests without the param record nothing.
     */
    @Test
    public void routingPreviewShowsWhoReadEachPart() throws Exception {
        final String hindiKey = mPrefs.getString("piper_voice_hin", "");
        final String englishKey = mPrefs.getString("piper_voice_eng", "");
        Assume.assumeTrue("needs Hindi and English natural voices", !hindiKey.isEmpty() && !englishKey.isEmpty());
        mPrefs.edit().putBoolean("piper_enabled", true).putBoolean("piper_switch_languages", true).commit();
        final String text = "आज मेरी meeting कल सुबह दस बजे है।";
        final String englishMixed = LanguageCards.key("eng", LanguageCards.WHEN_MIXED);
        for (int i = 0; i < 6; i++) {
            if (naturalChunks("plan_warm" + i, text, new Locale("hi")) >= 3) {
                break;
            }
            seconds("plan_en_warm" + i, ENGLISH, Locale.US);
            Thread.sleep(1500);
        }
        TtsService.takeShownPlan();
        seconds("plan_plain", text, new Locale("hi"));
        assertNull("only a preview request records", TtsService.takeShownPlan());

        final Bundle show = new Bundle();
        show.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
        seconds("plan_usual", text, new Locale("hi"), show);
        final List<TtsService.PlanPart> usual = TtsService.takeShownPlan();
        Assume.assumeTrue("both natural voices did not load", usual != null && usual.size() == 3
                && usual.get(1).naturalKey != null);
        assertPart(usual.get(0), "hin", hindiKey, "आज मेरी");
        assertPart(usual.get(1), "eng", englishKey, "meeting");
        assertPart(usual.get(2), "hin", hindiKey, "कल सुबह दस बजे है।");

        mPrefs.edit().putString(englishMixed, "espeak").commit();
        seconds("plan_espeak", text, new Locale("hi"), show);
        mPrefs.edit().remove(englishMixed).commit();
        final List<TtsService.PlanPart> espeak = TtsService.takeShownPlan();
        assertEquals(3, espeak.size());
        assertPart(espeak.get(1), "eng", null, "meeting");
    }

    private static void assertPart(TtsService.PlanPart part, String language, String naturalKey, String text) {
        assertEquals(language, part.language);
        assertEquals(naturalKey, part.naturalKey);
        assertEquals(text, part.text);
    }
}
