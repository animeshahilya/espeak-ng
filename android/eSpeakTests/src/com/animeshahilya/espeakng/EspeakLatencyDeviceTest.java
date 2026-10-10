/*
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

import com.animeshahilya.espeakng.text.TextPreprocessor;
import com.animeshahilya.espeakng.tts.CheckVoiceData;
import com.animeshahilya.espeakng.ui.VoiceSettings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Locale;

/**
 * eSpeak's screen-reader path, timed: TalkBack-like utterances through
 * {@link TextPreprocessor} and native synthesis, reporting preprocessing
 * time, time to first audio and total synthesis per utterance (median and
 * 95th percentile, logcat tag EspeakLatency). A benchmark, not a pass/fail
 * test: compare two builds on one phone.
 */
@RunWith(AndroidJUnit4.class)
public class EspeakLatencyDeviceTest {
    private static final String TAG = "EspeakLatency";

    private static final String[] TRACE = {
            "Settings, Button", "Double-tap to activate", "Back, Button", "Search", "Wi-Fi, On, Switch",
            "Bluetooth", "Battery 45%", "Notifications, 3 new", "Navigate up", "More options",
            "Messages", "Rahul: are you coming tonight? 😊", "Missed call from +91 98765 43210",
            "Your OTP is 482913. Do not share it with anyone.", "12:45 PM", "Monday, 4 October",
            "https://www.example.com/path?query=1", "Download complete", "Volume 60 percent",
            "आपका ऑर्डर भेज दिया गया है।", "मेरा फ़ोन Samsung है।", "नया संदेश आया है",
            "Paid ₹1,250.00 to Swiggy via UPI", "Email, edit box, Double-tap to edit",
            "Selected, Home, Tab 1 of 4", "Showing items 1 to 20 of 152", "Airplane mode, Off",
            "Hello, नमस्ते दोस्त, how are you?", "Loading…", "Swipe up to unlock",
            "a", "B", "5", "?", ".", "क", "@", "Space", "Delete", "New line",
    };

    private static final class Timer implements SpeechSynthesis.SynthReadyCallback {
        long start;
        long firstAudio;
        long done;

        @Override
        public void onSynthDataReady(byte[] audioData) {
            if (firstAudio == 0 && audioData != null && audioData.length > 0) {
                firstAudio = System.nanoTime();
            }
        }

        @Override
        public void onSynthDataComplete() {
            done = System.nanoTime();
        }

        @Override
        public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
        }
    }

    private static long pct(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))];
    }

    private static String summary(String what, long[] micros) {
        final long[] s = micros.clone();
        Arrays.sort(s);
        return String.format(Locale.ROOT, "%s: median %d us, p95 %d us, max %d us",
                what, pct(s, 0.5), pct(s, 0.95), s[s.length - 1]);
    }

    private void run(String voiceName, Locale locale) {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = EspeakApp.requireStorageContext(context);
        CheckVoiceData.ensureVoiceData(storage);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
        final Timer timer = new Timer();
        final SpeechSynthesis engine = new SpeechSynthesis(storage, timer);
        final Voice voice = new Voice(voiceName, voiceName, 0, 0, locale);
        engine.setVoice(voice, VoiceVariant.parseVoiceVariant("male"));
        final VoiceSettings settings = new VoiceSettings(prefs, engine);

        final int rounds = 5;
        final int n = TRACE.length * rounds;
        final long[] prep = new long[n];
        final long[] first = new long[n];
        final long[] total = new long[n];
        // One warm-up pass: dictionary and asset caches, JIT.
        for (String t : TRACE) {
            engine.synthesize(TextPreprocessor.process(t, voice, settings, false, null, storage).text, false);
        }
        int k = 0;
        for (int r = 0; r < rounds; r++) {
            for (String t : TRACE) {
                final long t0 = System.nanoTime();
                final String text = TextPreprocessor.process(t, voice, settings, false, null, storage).text;
                final long t1 = System.nanoTime();
                timer.firstAudio = 0;
                timer.start = t1;
                engine.synthesize(text, false);
                final long t2 = System.nanoTime();
                prep[k] = (t1 - t0) / 1000;
                first[k] = timer.firstAudio == 0 ? (t2 - t1) / 1000 : (timer.firstAudio - t1) / 1000;
                total[k] = (t2 - t1) / 1000;
                k++;
            }
        }
        Log.i(TAG, voiceName + " " + summary("preprocess", prep));
        Log.i(TAG, voiceName + " " + summary("first audio", first));
        Log.i(TAG, voiceName + " " + summary("synthesis", total));
    }

    /**
     * Fresh process (each instrumentation run starts one): bind to the
     * service, then the first utterance's first audio - what a screen reader
     * waits for after the TTS process was killed. Runs first (name order).
     */
    @Test
    public void aColdStart() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final java.util.concurrent.CountDownLatch init = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch audio = new java.util.concurrent.CountDownLatch(1);
        final long t0 = System.nanoTime();
        final android.speech.tts.TextToSpeech tts = new android.speech.tts.TextToSpeech(context,
                status -> init.countDown(), context.getPackageName());
        init.await(30, java.util.concurrent.TimeUnit.SECONDS);
        final long t1 = System.nanoTime();
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { }
            @Override public void onError(String id) { }
            @Override public void onAudioAvailable(String id, byte[] a) { audio.countDown(); }
        });
        final java.io.File out = new java.io.File(context.getCacheDir(), "cold.wav");
        tts.synthesizeToFile("Settings, Button", null, out, "cold");
        audio.await(30, java.util.concurrent.TimeUnit.SECONDS);
        final long t2 = System.nanoTime();
        Log.i(TAG, String.format(Locale.ROOT, "cold start: init %d ms, first audio %d ms after init",
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000));
        tts.shutdown();
    }

    @Test
    public void english() {
        run("en-us", new Locale("en", "US"));
    }

    @Test
    public void hindi() {
        run("hi", new Locale("hi", "IN"));
    }
}
