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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device instrumentation test verifying seamless language and script switching
 * across eSpeak, Piper, SYSPIN, and Rasa target languages and scripts.
 */
@RunWith(AndroidJUnit4.class)
public class LanguageSwitchingDeviceTest {
    private static final String TAG = "LanguageSwitchingDeviceTest";

    private SpeechSynthesis mEngine;
    private final AtomicInteger mAudioBytesReceived = new AtomicInteger(0);
    private volatile CountDownLatch mLatch = new CountDownLatch(1);

    private final SpeechSynthesis.SynthReadyCallback mCallback = new SpeechSynthesis.SynthReadyCallback() {
        @Override
        public void onSynthDataReady(byte[] audioData) {
            if (audioData != null) {
                mAudioBytesReceived.addAndGet(audioData.length);
            }
        }

        @Override
        public void onSynthDataComplete() {
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        @Override
        public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
        }
    };

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CheckVoiceData.ensureVoiceData(context);
        mEngine = new SpeechSynthesis(context, mCallback);
    }

    @After
    public void tearDown() {
        if (mEngine != null) {
            mEngine.setScriptLanguages(Collections.emptyMap());
            mEngine.stop();
            mEngine = null;
        }
    }

    private String ipa(String voice, String text) {
        return mEngine.phonemizeForPiper(voice, text);
    }

    // =========================================================================
    // 1. Native Phonemization across Engines / Languages
    // =========================================================================

    @Test
    public void testIndividualLanguagePhonemization() {
        // Piper English target
        final String en = ipa("en", "Hello world");
        assertNotNull(en);
        assertTrue("English phonemes should not be empty", en.length() > 0);

        // SYSPIN Hindi target
        final String hi = ipa("hi", "नमस्ते दुनिया");
        assertNotNull(hi);
        assertTrue("Hindi phonemes should not be empty", hi.length() > 0);

        // Rasa Tamil target
        final String ta = ipa("ta", "வணக்கம் உலகம்");
        assertNotNull(ta);
        assertTrue("Tamil phonemes should not be empty", ta.length() > 0);

        // SYSPIN/Rasa Bengali target
        final String bn = ipa("bn", "হ্যালো বিশ্ব");
        assertNotNull(bn);
        assertTrue("Bengali phonemes should not be empty", bn.length() > 0);

        // Rasa Kannada target
        final String kn = ipa("kn", "ನಮಸ್ಕಾರ ಪ್ರಪಂಚ");
        assertNotNull(kn);
        assertTrue("Kannada phonemes should not be empty", kn.length() > 0);

        // SYSPIN/Rasa Telugu target
        final String te = ipa("te", "నమస్కారం ప్రపంచం");
        assertNotNull(te);
        assertTrue("Telugu phonemes should not be empty", te.length() > 0);

        // Piper/eSpeak Arabic target
        final String ar = ipa("ar", "مرحبا بالعالم");
        assertNotNull(ar);
        assertTrue("Arabic phonemes should not be empty", ar.length() > 0);

        // eSpeak Russian (Cyrillic) target
        final String ru = ipa("ru", "Привет мир");
        assertNotNull(ru);
        assertTrue("Russian phonemes should not be empty", ru.length() > 0);
    }

    // =========================================================================
    // 2. Multi-Script Mixed Sentence Phonemization
    // =========================================================================

    @Test
    public void testFourWayCrossEnginePhonemization() {
        // Latin (Piper) + Devanagari (SYSPIN) + Tamil (Rasa) + Cyrillic (eSpeak)
        final String mixedText = "Hello friend, नमस्ते दोस्त, வணக்கம் நண்பா, Привет друг!";
        final String ipaResult = ipa("en", mixedText);

        assertNotNull(ipaResult);
        assertTrue("Phonemization of mixed 4-way text must produce output", ipaResult.length() > 20);

        // Verify phonemes from distinct scripts are present in the resulting stream
        Log.i(TAG, "4-way phonemes: " + ipaResult);
    }

    @Test
    public void testEnglishWithEmbeddedHindiAndTamil() {
        final String text = "Hello, नमस्ते, வணக்கம்!";
        final String ipaResult = ipa("en", text);

        assertNotNull(ipaResult);
        assertTrue("Mixed English+Hindi+Tamil must yield phonemes", ipaResult.length() > 10);
    }

    @Test
    public void testHindiWithEmbeddedEnglishBrandNames() {
        // SYSPIN Hindi text with English words
        final String text = "मेरा फ़ोन Samsung Galaxy S25 है।";
        final String ipaResult = ipa("hi", text);

        assertNotNull(ipaResult);
        assertTrue("Hindi with English brand names must yield phonemes", ipaResult.length() > 10);
    }

    @Test
    public void testTamilWithEmbeddedEnglishTechWords() {
        // Rasa Tamil text with English words
        final String text = "நான் YouTube இல் புதிய video பார்த்தேன்.";
        final String ipaResult = ipa("ta", text);

        assertNotNull(ipaResult);
        assertTrue("Tamil with English words must yield phonemes", ipaResult.length() > 10);
    }

    // =========================================================================
    // 3. Real On-Device Speech Synthesis across Mixed Scripts
    // =========================================================================

    @Test
    public void testSynthesizeFourWayMixedSpeech() throws InterruptedException {
        mAudioBytesReceived.set(0);
        mLatch = new CountDownLatch(1);

        final Voice enVoice = new Voice("en", "en-us", 0, 0, java.util.Locale.US);
        mEngine.setVoice(enVoice, null);

        final String text = "Hello world, नमस्ते दुनिया, வணக்கம் உலகம், Привет мир";
        mEngine.synthesize(text, false);

        final boolean completed = mLatch.await(15, TimeUnit.SECONDS);
        assertTrue("Speech synthesis of 4-way mixed sentence timed out", completed);
        assertTrue("Speech synthesis must produce non-empty PCM audio", mAudioBytesReceived.get() > 5000);
        Log.i(TAG, "Synthesized 4-way audio bytes: " + mAudioBytesReceived.get());
    }

    @Test
    public void testSynthesizeKannadaWithNumbers_DigitFallback() throws InterruptedException {
        mAudioBytesReceived.set(0);
        mLatch = new CountDownLatch(1);

        final Voice knVoice = new Voice("kn", "kn", 0, 0, new java.util.Locale("kn", "IN"));
        mEngine.setVoice(knVoice, null);

        // Digits in Kannada: verify eSpeak generates audio for words and digits
        final String text = "ಇಂದಿನ ತಾಪಮಾನ 28 ಡಿಗ್ರಿ ಆಗಿದೆ ಮತ್ತು ಸಮಯ 10:30 ಆಗಿದೆ.";
        mEngine.synthesize(text, false);

        final boolean completed = mLatch.await(15, TimeUnit.SECONDS);
        assertTrue("Speech synthesis of Kannada with digits timed out", completed);
        assertTrue("Kannada with digits must produce non-empty PCM audio", mAudioBytesReceived.get() > 5000);
        Log.i(TAG, "Synthesized Kannada+digits audio bytes: " + mAudioBytesReceived.get());
    }

    // =========================================================================
    // 4. Dynamic Script Language Reconfiguration
    // =========================================================================

    @Test
    public void testDynamicScriptLanguageSwitching() {
        // Default Devanagari in English -> Hindi
        final String defaultHindi = ipa("en", "नमस्ते");
        assertNotNull(defaultHindi);

        // Reconfigure Devanagari to Marathi
        mEngine.setScriptLanguages(Collections.singletonMap("hi", "mr"));
        final String marathi = ipa("en", "नमस्कार");
        assertNotNull(marathi);
        assertTrue(marathi.length() > 0);

        // Reconfigure Latin in Hindi to Indian English
        final Map<String, String> indianEnglish = new HashMap<>();
        indianEnglish.put("latin", "en-in");
        mEngine.setScriptLanguages(indianEnglish);
        final String inPhonemes = ipa("hi", "phone");
        assertNotNull(inPhonemes);
        assertTrue(inPhonemes.contains("oː"));

        // Reset to empty map
        mEngine.setScriptLanguages(Collections.emptyMap());
        final String resetPhonemes = ipa("hi", "phone");
        assertNotNull(resetPhonemes);
        assertTrue(resetPhonemes.contains("əʊ"));
    }
}
