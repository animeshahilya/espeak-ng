package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.piper.PiperDownloads;
import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Supertonic 3 end to end on a Snapdragon phone (the Snapdragon build; skipped
 * elsewhere): downloads a Hindi voice from the release, its companion files
 * into the shared store, compiles the NPU graphs, and speaks through the TTS
 * framework; then an English voice, which installs from the shared model
 * without a download. Speed is logged ("Supertonic" tag) and checked.
 * Voices this test installed are deleted again; settings are restored.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SupertonicDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    private static final String TAG = "Supertonic";
    private static final String HINDI = "hi_IN-supertonic_female_1-medium";
    private static final String ENGLISH = "en_US-supertonic_male_2-medium";
    private static final List<String> INSTALLED_HERE = new ArrayList<>();

    private static Context app() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Context storage() {
        return EspeakApp.requireStorageContext(app());
    }

    private static SharedPreferences prefs() {
        return EspeakApp.prefs(storage());
    }

    /** Installs a catalog voice through the app's own download path, if it is not there yet. */
    private static void install(String key) throws Exception {
        if (PiperVoiceStore.find(storage(), key) == null) {
            PiperDownloads.CatalogVoice voice = null;
            for (PiperDownloads.CatalogVoice v : PiperDownloads.bundledExtras(storage())) {
                if (v.key.equals(key)) {
                    voice = v;
                }
            }
            assertNotNull(key + " not in the catalog", voice);
            final long t0 = System.nanoTime();
            final long id = PiperDownloads.start(app(), storage(), voice);
            if (id >= 0) {
                // The app's own download receiver usually finishes the install first.
                for (int i = 0; i < 600 && PiperVoiceStore.find(storage(), key) == null; i++) {
                    Thread.sleep(1000);
                    PiperDownloads.complete(app(), storage(), id);
                    PiperVoiceStore.invalidate();
                }
                assertNotNull(key + " did not install", PiperVoiceStore.find(storage(), key));
            }
            Log.i(TAG, key + " installed in " + (System.nanoTime() - t0) / 1_000_000 + " ms ("
                    + (id >= 0 ? "downloaded" : "from the shared model") + ")");
            INSTALLED_HERE.add(key);
        }
        final long t0 = System.nanoTime();
        PiperDownloads.fetchSupertonicFiles(app(), storage());
        Log.i(TAG, key + " companion files ready in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertNotNull(PiperVoiceStore.find(storage(), key));
    }

    @AfterClass
    public static void removeTestVoices() {
        for (String key : INSTALLED_HERE) {
            PiperVoiceStore.delete(storage(), prefs(), key);
        }
    }

    private TextToSpeech tts() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        final TextToSpeech t = new TextToSpeech(app(), s -> {
            status[0] = s;
            ready.countDown();
        }, app().getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        assertEquals(TextToSpeech.SUCCESS, status[0]);
        return t;
    }

    /** {sample rate, samples, wall ms, 1 if a natural voice spoke}. */
    private long[] say(TextToSpeech t, String id, String text, Locale locale) throws Exception {
        assertTrue(t.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        final File out = new File(app().getExternalCacheDir(), id + ".wav");
        out.delete();
        final CountDownLatch done = new CountDownLatch(1);
        final String[] outcome = {null};
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) { }
            @Override public void onDone(String u) { outcome[0] = "done"; done.countDown(); }
            @Override public void onError(String u) { outcome[0] = "error"; done.countDown(); }
        });
        final long chunks = PiperEngine.renderedChunks();
        final long t0 = System.nanoTime();
        assertEquals(TextToSpeech.SUCCESS, t.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", done.await(120, TimeUnit.SECONDS));
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("done", outcome[0]);
        try (RandomAccessFile raf = new RandomAccessFile(out, "r")) {
            final byte[] h = new byte[44];
            raf.readFully(h);
            final int rate = (h[24] & 0xff) | (h[25] & 0xff) << 8 | (h[26] & 0xff) << 16;
            final long samples = (raf.length() - 44) / 2;
            final boolean natural = PiperEngine.renderedChunks() > chunks;
            Log.i(TAG, String.format(Locale.ROOT, "%s: %d Hz, %.2f s audio in %d ms (%.1fx), %s", id, rate,
                    samples / (double) rate, ms, samples / (double) rate * 1000 / Math.max(1, ms),
                    natural ? "Supertonic" : "eSpeak"));
            return new long[] {rate, samples, ms, natural ? 1 : 0};
        }
    }

    /** Speaks until the natural voice answers (the first load compiles its NPU graphs). */
    private void warm(TextToSpeech t, Locale locale, String text) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (say(t, "warm" + i, text, locale)[3] == 1) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("Supertonic never spoke");
    }

    /** Normal speech rate (the phone's own may be fast; KEEP restores it): speeds compare with the bench. */
    private static void normalRate() {
        prefs().edit().putString("espeak_rate", "175").putString(PiperVoiceStore.PREF_SPEED, "100").commit();
    }

    @Test
    public void a_hindiDownloadsAndSpeaks() throws Exception {
        Assume.assumeTrue("Supertonic runs only on the Snapdragon build with a modern NPU",
                PiperDownloads.supertonicCapable());
        prefs().edit().putBoolean(PiperDownloads.PREF_SUPERTONIC_BETA, true).commit();
        normalRate();
        install(HINDI);
        prefs().edit().putBoolean(PiperVoiceStore.PREF_ENABLED, true).commit();
        PiperVoiceStore.assign(prefs(), "hin", HINDI);
        final TextToSpeech t = tts();
        try {
            warm(t, new Locale("hi", "IN"), "नमस्ते।");
            final long[] s = say(t, "hi_sentence", "नमस्ते, आज मौसम बहुत अच्छा है। हम सब मिलकर बाज़ार जाएँगे "
                    + "और ताज़ी सब्ज़ियाँ ख़रीदेंगे, फिर घर लौटकर खाना बनाएँगे।", new Locale("hi", "IN"));
            assertEquals(1, s[3]);
            assertEquals(44100, s[0]);
            assertTrue("too short", s[1] > 44100 * 4L);
        } finally {
            t.shutdown();
        }
    }

    @Test
    public void b_secondVoiceInstallsFromTheSharedModel() throws Exception {
        Assume.assumeTrue(PiperDownloads.supertonicCapable());
        prefs().edit().putBoolean(PiperDownloads.PREF_SUPERTONIC_BETA, true).commit();
        normalRate();
        install(ENGLISH);
        prefs().edit().putBoolean(PiperVoiceStore.PREF_ENABLED, true).commit();
        PiperVoiceStore.assign(prefs(), "eng", ENGLISH);
        final TextToSpeech t = tts();
        try {
            warm(t, Locale.US, "Hello.");
            final long[] phrase = say(t, "en_phrase", "Settings, double tap to open.", Locale.US);
            assertEquals(1, phrase[3]);
            final StringBuilder para = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                para.append("Natural voices should start speaking quickly, and keep going without gaps, ")
                        .append("even when a screen reader reads a long paragraph like this one. ");
            }
            final long[] p = say(t, "en_paragraph", para.toString(), Locale.US);
            assertEquals(1, p[3]);
            final double realTime = p[1] / (double) p[0] * 1000 / p[2];
            assertTrue("only " + realTime + "x real time", realTime >= 4);
        } finally {
            t.shutdown();
        }
    }
}
