package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.piper.VoiceImport;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Imports voice archives pushed to the phone (instrumentation argument
 * "archives": comma-separated file names in the app's external files folder
 * ("a.onnx+config.json" for one voice's loose files),
 * e.g. vits-piper-en_US-amy-low.tar.bz2 from sherpa-onnx's tts-models
 * release) through {@link VoiceImport}, as Import a voice file does, then
 * speaks a sentence with each through the TTS framework. The render stays in
 * the external cache folder ("import-&lt;key&gt;.wav") for a listen. Imported
 * voices are deleted again unless "keep" is "true"; settings are restored.
 * Skipped without the argument.
 */
@RunWith(AndroidJUnit4.class)
public class VoiceImportDeviceTest {
    @org.junit.ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings(
                    com.animeshahilya.espeakng.ui.LanguageSettings.PREF_SUPPORTED_LANGUAGES);

    private static final String TAG = "VoiceImportTest";

    @Test
    public void importAndSpeak() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        final String archives = args.getString("archives");
        Assume.assumeTrue("no archives argument", archives != null && !archives.isEmpty());
        final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = EspeakApp.requireStorageContext(app);
        final SharedPreferences prefs = EspeakApp.prefs(storage);
        prefs.edit().putBoolean(PiperVoiceStore.PREF_ENABLED, true)
                .putString("espeak_rate", "175").putString(PiperVoiceStore.PREF_SPEED, "100").commit();
        for (String name : archives.split(",")) {
            // "a.onnx+config.json": one voice's loose files, picked together.
            final java.util.List<Uri> uris = new java.util.ArrayList<>();
            final java.util.List<String> names = java.util.Arrays.asList(name.split("\\+"));
            for (String part : names) {
                final File file = new File(app.getExternalFilesDir(null), part);
                assertTrue(file + " missing", file.isFile());
                uris.add(Uri.fromFile(file));
            }
            final long t0 = System.nanoTime();
            final PiperVoiceStore.Installed v = VoiceImport.install(app, storage, uris, names, null);
            Log.i(TAG, name + " -> " + v.key + " (" + v.config.phonemeType + ", " + v.config.sampleRate
                    + " Hz) in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            assertTrue(new File(v.dir, PiperVoiceStore.IMPORTED_FILE).isFile());
            final String previous = PiperVoiceStore.assignedKey(prefs, v.languageKey());
            PiperVoiceStore.assign(prefs, v.languageKey(), v.key);
            try {
                // With its region: plain "en" would be read by the US voice.
                final String code = v.config.languageCode;
                final Locale locale = code != null && code.contains("_")
                        ? new Locale(v.config.languageFamily, code.substring(code.indexOf('_') + 1))
                        : new Locale(v.config.languageFamily);
                final String text = "bn".equals(v.config.languageFamily)
                        ? "আমি বাংলায় কথা বলি। আজ আবহাওয়া খুব ভালো, তাই আমরা বাজারে যাব।"
                        : "hi".equals(v.config.languageFamily)
                        ? "आज मौसम बहुत अच्छा है, इसलिए हम बाज़ार जाएँगे। रेलगाड़ी 10 बजे पहुँचेगी।"
                        : "The quick brown fox jumps over the lazy dog, and then it runs back home.";
                final long[] r = speak(app, v.key, text, locale);
                assertEquals(v.key + " was read by eSpeak", 1, r[2]);
                assertTrue(v.key + " too short: " + r[1], r[1] > r[0] * 2L);
            } finally {
                PiperVoiceStore.assign(prefs, v.languageKey(), previous);
                if (!"true".equals(args.getString("keep"))) {
                    assertTrue(PiperVoiceStore.delete(storage, prefs, v.key));
                }
            }
        }
    }

    /** {sample rate, samples, 1 if a natural voice spoke}; the first tries wait for the voice to load. */
    private static long[] speak(Context app, String key, String text, Locale locale) throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech t = new TextToSpeech(app, s -> ready.countDown(), app.getPackageName());
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            assertTrue(t.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
            for (int attempt = 0; attempt < 30; attempt++) {
                final File out = new File(app.getExternalCacheDir(), "import-" + key + ".wav");
                final CountDownLatch done = new CountDownLatch(1);
                t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String u) { }
                    @Override public void onDone(String u) { done.countDown(); }
                    @Override public void onError(String u) { done.countDown(); }
                });
                final long chunks = PiperEngine.renderedChunks();
                final long t0 = System.nanoTime();
                final Bundle params = new Bundle();
                params.putBoolean(TtsService.PARAM_SHOW_PLAN, true); // records who read it
                assertEquals(TextToSpeech.SUCCESS, t.synthesizeToFile(text, params, out, "u" + attempt));
                assertTrue(done.await(120, TimeUnit.SECONDS));
                final long ms = (System.nanoTime() - t0) / 1_000_000;
                final java.util.List<TtsService.PlanPart> plan = TtsService.takeShownPlan();
                if (PiperEngine.renderedChunks() > chunks) {
                    assertNotNull(plan);
                    for (TtsService.PlanPart part : plan) {
                        assertEquals("read by another voice: " + part.text, key, part.naturalKey);
                    }
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "r")) {
                        final byte[] h = new byte[44];
                        raf.readFully(h);
                        final int rate = (h[24] & 0xff) | (h[25] & 0xff) << 8 | (h[26] & 0xff) << 16;
                        final long samples = (raf.length() - 44) / 2;
                        Log.i(TAG, String.format(Locale.ROOT, "%s: %.2f s audio in %d ms", key,
                                samples / (double) rate, ms));
                        return new long[] {rate, samples, 1};
                    }
                }
                Thread.sleep(2000); // eSpeak read it while the voice loads
            }
            return new long[] {0, 0, 0};
        } finally {
            t.shutdown();
        }
    }
}
