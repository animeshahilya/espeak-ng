/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng;

import com.animeshahilya.espeakng.tts.CheckVoiceData;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * en-us eSpeak audit: suspect words from the Parakeet-scored corpus
 * re-rendered in carriers for reproducibility, plus homograph and dialect
 * words logged as phonemes (their spellings are identical, so ASR cannot
 * judge them - read the IPA in logcat tag {@code EnUsProbe} instead).
 *
 * <p>Fast (~30 utterances, no natural voices involved). Renders land in the
 * app's external cache under {@code enus_probe/} with {@code index.txt}.
 */
@RunWith(AndroidJUnit4.class)
public class EnUsProbeDeviceTest {
    private static final String TAG = "EnUsProbe";
    private static final String VOICE = "en-us";

    /** Stark corpus misses, retested for reproducibility. */
    private static final String[] RETESTS = {
            "s|I visited Dorset yesterday.",
            "s|Put it on the higher shelf.",
            "s|Cowley Road station is closed.",
            "s|Please close the door.",
            "s|The entirety of it is gone.",
            "s|Oak, birch and ash.",
            "s|She wore a garment.",
            "s|The cowboy rode away.",
            "s|Oak and wood.",
            "s|The button is here.",
            "s|Turn on Bluetooth.",
            "s|Add some herbs.",
    };

    /** Same spelling, different sounds: judged by eye from the IPA log. */
    private static final String[] PHONEMES = {
            "I read the book yesterday.",
            "I will read the letter.",
            "They live here.",
            "It was a live show.",
            "He plays bass.",
            "The bass swam away.",
            "Which route did you take?",
            "Put it in the vase.",
            "My aunt is here.",
            "Park in the garage.",
            "An advertisement.",
            "Check the schedule.",
            "The controversy continues.",
            "Pecan pie.",
            "Caramel.",
            "The entrance is here.",
            "Dorset.",
            "higher.",
            "Cowley.",
            "door.",
            "entirety.",
            "ash.",
            "garment.",
            "wood.",
            "herbs.",
            "missed.",
            "Google.",
            "limestone.",
            "Lyme.",
            "vetch.",
    };

    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Test
    public void probe() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = context.createDeviceProtectedStorageContext();
        CheckVoiceData.ensureVoiceData(storage);

        // Audio retests through the TTS service (full pipeline, en-us voice).
        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech tts = new TextToSpeech(context, s -> ready.countDown(),
                context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }

                @Override public void onDone(String id) {
                    done(id);
                }

                @Override public void onError(String id) {
                    done(id);
                }
            });
            assertTrue(tts.setLanguage(new Locale("en", "US")) >= TextToSpeech.LANG_AVAILABLE);
            final File dir = new File(context.getExternalCacheDir(), "enus_probe");
            deleteContents(dir);
            dir.mkdirs();
            say(tts, "Warming up the voice.", new File(dir, "warm.wav"), "warm");
            final StringBuilder index = new StringBuilder();
            for (int i = 0; i < RETESTS.length; i++) {
                final String id = String.format(Locale.ROOT, "%03d", i);
                final String text = RETESTS[i].substring(2);
                say(tts, text, new File(dir, id + ".wav"), id);
                index.append(id).append("\t").append(RETESTS[i].charAt(0)).append("\t").append(text)
                        .append("\n");
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, "index.txt"))) {
                out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            tts.shutdown();
        }

        // Phoneme log straight from the engine (deterministic, no ASR needed).
        final SpeechSynthesis engine =
                new SpeechSynthesis(storage, new SpeechSynthesis.SynthReadyCallback() {
                    @Override public void onSynthDataReady(byte[] audioData) { }

                    @Override public void onSynthDataComplete() { }

                    @Override
                    public void onSynthWordBoundary(int textPosition, int textLength,
                                                    int markerInFrames) { }
                });
        engine.setVoice(new Voice(VOICE, VOICE, 0, 0, new Locale("en", "US")),
                VoiceVariant.parseVoiceVariant("male"));
        for (String text : PHONEMES) {
            final String records = engine.phonemizeForPiper(VOICE, text);
            Log.i(TAG, text + " => " + records);
        }
    }

    private void say(TextToSpeech tts, String text, File out, String id) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, null, out, id));
        assertTrue(id + " timed out", latch.await(120, TimeUnit.SECONDS));
    }

    private void done(String id) {
        final CountDownLatch latch = mDone.get(id);
        if (latch != null) {
            latch.countDown();
        }
    }

    private static void deleteContents(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }
}
