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
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

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
        final long[] before = synth("stop_warm1", HI);
        final Bundle quiet = new Bundle();
        quiet.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f);
        for (int i = 0; i < 30; i++) {
            assertEquals(TextToSpeech.SUCCESS, mTts.speak(HI, TextToSpeech.QUEUE_FLUSH, quiet, "stop" + i));
            Thread.sleep(40 + (i % 6) * 60); // 40-340 ms: before, during and after the first piece
            assertEquals(TextToSpeech.SUCCESS, mTts.stop());
        }
        final long[] after = synth("stop_after", HI);
        // Compared with the same text before the stops, not a fixed length:
        // its duration depends on the phone's voice style and speed.
        assertTrue("speech still works: " + after[3] + " vs " + before[3] + " samples",
                after[3] > before[3] * 7 / 10);
        Log.i(TAG, "stops done, then " + after[3] / (double) after[2] + " s of audio");
    }

    private static final String[] KN_PHRASES = {"ಹೊಸ ಸಂದೇಶ ಬಂದಿದೆ", "ಬ್ಯಾಟರಿ ಶೇಕಡಾ ಎಂಬತ್ತು",
            "ವೈಫೈ ಸಂಪರ್ಕ ಕಡಿತಗೊಂಡಿದೆ", "ಮುಖಪುಟಕ್ಕೆ ಹಿಂತಿರುಗಿ", "ಕರೆ ಮಾಡಲು ಎರಡು ಬಾರಿ ತಟ್ಟಿ",
            "ನಿಮ್ಮ ಗುಪ್ತಪದ ನಮೂದಿಸಿ", "ಇಂದಿನ ಹವಾಮಾನ ಬಿಸಿಲು", "ಸಂಗೀತ ಕೇಳುತ್ತಿದ್ದೀರಿ"};
    private static final String[] KN_SENTENCES = {
            "ನಾಳೆ ಬೆಳಿಗ್ಗೆ ನಾವು ಎಲ್ಲರೂ ಸೇರಿ ಹಳೆಯ ಮಾರುಕಟ್ಟೆಗೆ ಹೋಗಿ ತಾಜಾ ತರಕಾರಿಗಳನ್ನು ಖರೀದಿಸುತ್ತೇವೆ.",
            "ನಮ್ಮ ಊರಿನ ಶಾಲೆಯಲ್ಲಿ ಈ ವರ್ಷ ಹೊಸ ಗ್ರಂಥಾಲಯವನ್ನು ಮಕ್ಕಳಿಗಾಗಿ ತೆರೆಯಲಾಗಿದೆ.",
            "ಮಳೆಗಾಲದಲ್ಲಿ ರಸ್ತೆಗಳು ಒದ್ದೆಯಾಗಿರುವುದರಿಂದ ವಾಹನಗಳನ್ನು ನಿಧಾನವಾಗಿ ಓಡಿಸಬೇಕು.",
            "ಅಜ್ಜಿ ಪ್ರತಿದಿನ ಸಂಜೆ ತೋಟದಲ್ಲಿ ಹೂವಿನ ಗಿಡಗಳಿಗೆ ನೀರು ಹಾಕುತ್ತಾರೆ.",
            "ರೈಲು ನಿಲ್ದಾಣದಲ್ಲಿ ಜನರು ಟಿಕೆಟ್ ಪಡೆಯಲು ಉದ್ದನೆಯ ಸಾಲಿನಲ್ಲಿ ನಿಂತಿದ್ದರು."};
    private static final String[] HI_PHRASES = {"नया संदेश आया है", "बैटरी अस्सी प्रतिशत",
            "वाईफ़ाई कनेक्शन टूट गया", "होम स्क्रीन पर वापस जाएँ", "कॉल करने के लिए दो बार टैप करें",
            "अपना पासवर्ड डालें", "आज का मौसम धूप वाला", "गाना चल रहा है"};
    private static final String[] HI_SENTENCES = {
            "कल सुबह हम सब मिलकर पुराने बाज़ार से ताज़ी सब्ज़ियाँ ख़रीदने के लिए जाएँगे।",
            "हमारे गाँव के स्कूल में इस साल बच्चों के लिए नया पुस्तकालय खोला गया है।",
            "बारिश के मौसम में सड़कें गीली रहती हैं इसलिए गाड़ी धीरे चलानी चाहिए।",
            "दादी हर शाम बगीचे में फूलों के पौधों को पानी देती हैं।",
            "रेलवे स्टेशन पर लोग टिकट लेने के लिए लंबी कतार में खड़े थे।"};

    /** Median first audio over texts said once each, so the phrase cache never answers. */
    private void measureFresh(String name, Locale locale, String[] texts) throws Exception {
        assertTrue(mTts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        mTts.setSpeechRate(1f);
        final List<Long> firsts = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            Thread.sleep(2000);
            firsts.add(synth(name + "_" + i, texts[i])[0]);
        }
        Collections.sort(firsts);
        Log.i(TAG, String.format(Locale.ROOT, "%s fresh firstAudio median=%dms (min %d, max %d) n=%d",
                name, firsts.get(firsts.size() / 2), firsts.get(0), firsts.get(firsts.size() - 1),
                firsts.size()));
    }

    /**
     * Texts the voice has not said before: short screen-reader phrases and
     * sentences, on the voices the phone has for Kannada (a Rasa voice,
     * slowest), Chhattisgarhi (SYSPIN) and Hindi. Warmed up first, so the
     * voice is loaded and its level known.
     */
    @Test
    public void freshTextFirstAudio() throws Exception {
        final Object[][] cases = {
                {"kn", new Locale("kn", "IN"), KN_PHRASES, KN_SENTENCES, "ಇದು ಪರೀಕ್ಷೆಗಾಗಿ ಒಂದು ವಾಕ್ಯ ಮಾತ್ರ."},
                {"hne", new Locale("hne", "IN"), HI_PHRASES, HI_SENTENCES, "यह केवल जाँच के लिए एक वाक्य है।"},
                {"hi", new Locale("hi", "IN"), HI_PHRASES, HI_SENTENCES, "यह केवल जाँच के लिए एक वाक्य है।"}};
        for (Object[] c : cases) {
            final String name = (String) c[0];
            final Locale locale = (Locale) c[1];
            if (mTts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                synth(name + "_warm" + i, (String) c[4]);
                Thread.sleep(1500);
            }
            measureFresh(name + "_phrase", locale, (String[]) c[2]);
            measureFresh(name + "_sentence", locale, (String[]) c[3]);
        }
    }

    private static final String[] EN_PHRASES = {"Settings", "Navigate up", "Double tap to activate",
            "Wi-Fi, on", "Battery 85 percent", "12 notifications", "Search", "More options", "Back",
            "Send message"};
    private static final String[] EN_SENTENCES = {
            "When the train pulled into the station, the passengers gathered their bags and stepped out.",
            "If you turn on the battery saver, the phone limits background activity to last longer.",
            "The museum opens at nine in the morning, and children under twelve enter free.",
            "Natural voices should start speaking quickly and keep going without any gaps.",
            "Guided tours start every hour, and the last one leaves at four in the afternoon."};

    /**
     * Supertonic (beta, Snapdragon NPU) on English text said once each,
     * through the installed Supertonic English voice (skipped without one),
     * plus the process's memory after. A long warm-up lets a newly added
     * NPU bucket compile first.
     */
    @Test
    public void supertonicFirstAudio() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = ctx.createDeviceProtectedStorageContext();
        String key = null;
        for (com.animeshahilya.espeakng.piper.PiperVoiceStore.Installed v
                : com.animeshahilya.espeakng.piper.PiperVoiceStore.list(storage)) {
            if (v.config.isSupertonic() && "en".equals(v.config.languageFamily)) {
                key = v.key;
            }
        }
        org.junit.Assume.assumeTrue("no Supertonic English voice", key != null);
        final android.content.SharedPreferences prefs =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(storage);
        prefs.edit().putBoolean("piper_enabled", true).putString("espeak_rate", "175").commit();
        com.animeshahilya.espeakng.piper.PiperVoiceStore.assign(prefs, "eng", key);
        Thread.sleep(500);
        assertTrue(mTts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE);
        for (int i = 0; i < 12; i++) {
            synth("st_warm" + i, "This sentence is only here to warm the voice up.");
            Thread.sleep(4000);
        }
        measureFresh("supertonic_phrase", Locale.US, EN_PHRASES);
        logPss("after phrases");
        measureFresh("supertonic_sentence", Locale.US, EN_SENTENCES);
        logPss("after sentences");
        // Long reading: later chunks need the largest bucket, loaded at first use.
        final long[] r = synth("supertonic_long", String.join(" ", EN_SENTENCES) + " " + EN);
        Log.i(TAG, String.format(Locale.ROOT, "supertonic long: first audio %d ms, wall %d ms for %.1f s of audio",
                r[0], r[1], r[3] / (double) r[2]));
        logPss("after long text");
    }

    private static void logPss(String when) {
        final android.os.Debug.MemoryInfo mem = new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(mem);
        Log.i(TAG, "supertonic process PSS " + when + " " + mem.getTotalPss() / 1024 + " MB");
    }

    @Test
    public void firstAudioLatency() throws Exception {
        measure("hi", new Locale("hi", "IN"), HI);
        measure("en", Locale.US, EN);
    }
}
