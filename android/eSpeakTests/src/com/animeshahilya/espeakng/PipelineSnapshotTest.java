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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * NVDA-parity guard: writes, for a fixed corpus and a spread of voices, the
 * text the app hands eSpeak (default settings) and a hash of the audio eSpeak
 * produces for it. Diff two runs to prove a change leaves non-Indian voices
 * alone. The engine's noise generator carries state from one utterance to
 * the next, so an audio hash is only comparable while every earlier line of
 * the run is unchanged too: compare the text column for app-side changes,
 * and the hashes for engine/native changes (identical input, identical run):
 *
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.animeshahilya.espeakng.PipelineSnapshotTest
 *   adb logcat -d -v raw -s PipelineSnapshot > snapshot.tsv
 *
 * (Clear logcat with adb logcat -c first. connectedAndroidTest uninstalls
 * the app afterwards, so the snapshot goes to logcat, not a file.)
 */
@RunWith(AndroidJUnit4.class)
public class PipelineSnapshotTest {
    private static final String[] VOICES = {
            "en-gb", "en-us", "de", "fr-fr", "es", "ru", "ar", "cmn",
            "en-in", "hi", "mr", "bn", "ta", "te", "gu", "pa", "ur", "ne"
    };

    private static final String[] CORPUS = {
            "The quick brown fox jumps over the lazy dog.",
            "Meet me at 10:30 on 12/05/2024, it costs $45.99.",
            "Salary is 50k and the flat is 2 l of water away.",
            "Budget of 5L, turnover 2cr, 3 lakh and 4 crore.",
            "Paid ₹1,50,000 via UPI/423891028341/PAYTM, Rs. 500/- and Re. 1.",
            "Population 12,34,567 versus 1,234,567.",
            "Digits २०२४ and ১২৩ in text.",
            "नमस्ते।आप कैसे हैं? कुल ₹१,५०,००० है।",
            "if (x != y) { return a && b; } // done",
            "Visit https://www.example.com/docs/guide?ref=1 now.",
            "Wait... really?! 100% sure — yes.",
            "Hello 😀 and 🇮🇳 flag, thumbs 👍🏽, 😂😂😂😂😂.",
            "The year 1999 and the number 3.14159.",
    };

    @Test
    public void writeSnapshot() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        CheckVoiceData.ensureVoiceData(context);

        final CountDownLatch[] latch = new CountDownLatch[1];
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final SpeechSynthesis engine = new SpeechSynthesis(context, new SpeechSynthesis.SynthReadyCallback() {
            @Override
            public void onSynthDataReady(byte[] audioData) {
                if (audioData != null) {
                    synchronized (digest) {
                        digest.update(audioData);
                    }
                }
            }

            @Override
            public void onSynthDataComplete() {
                latch[0].countDown();
            }

            @Override
            public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
            }
        });

        // A private, cleared preferences file: every setting at its default.
        final SharedPreferences prefs = context.getSharedPreferences("pipeline-snapshot", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        final VoiceSettings settings = new VoiceSettings(prefs, engine);

        final Map<String, Voice> voices = new HashMap<String, Voice>();
        for (Voice v : engine.getAvailableVoices()) {
            voices.put(v.name, v);
        }
        final VoiceVariant variant = VoiceVariant.parseVoiceVariant(VoiceVariant.MALE);

        for (String name : VOICES) {
            final Voice voice = voices.get(name);
            assertThat("voice " + name, voice, is(notNullValue()));
            engine.setVoice(voice, variant);
            for (String input : CORPUS) {
                final String text = TextPreprocessor.process(
                        input, voice, settings, false, null, null).text;
                synchronized (digest) {
                    digest.reset();
                }
                latch[0] = new CountDownLatch(1);
                engine.synthesize(text, false);
                assertThat("synthesis timed out: " + name, latch[0].await(20, TimeUnit.SECONDS), is(true));
                final StringBuilder hex = new StringBuilder();
                synchronized (digest) {
                    for (byte b : digest.digest()) {
                        hex.append(String.format("%02x", b));
                    }
                }
                Log.i("PipelineSnapshot", name + "\t" + input + "\t" + text + "\t" + hex.substring(0, 16));
            }
        }
        engine.stop();
    }
}
