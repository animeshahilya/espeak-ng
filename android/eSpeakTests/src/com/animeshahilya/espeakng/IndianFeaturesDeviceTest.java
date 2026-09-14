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
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class IndianFeaturesDeviceTest {
    private static final String TAG = "IndianFeaturesDeviceTest";
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
        Context context = ApplicationProvider.getApplicationContext();
        mEngine = new SpeechSynthesis(context, mCallback);
    }

    @After
    public void tearDown() {
        if (mEngine != null) {
            mEngine.stop();
            mEngine = null;
        }
    }

    @Test
    public void testExpandProgrammingSymbols() {
        assertThat(TtsService.expandProgrammingSymbols("if (x != y)"), containsString("not equal"));
        assertThat(TtsService.expandProgrammingSymbols("x ≠ y"), containsString("not equal"));
        assertThat(TtsService.expandProgrammingSymbols("if (a == b)"), containsString("double equals"));
        assertThat(TtsService.expandProgrammingSymbols("while (i <= 10)"), containsString("less than or equal to"));
        assertThat(TtsService.expandProgrammingSymbols("i ≤ 10"), containsString("less than or equal to"));
        assertThat(TtsService.expandProgrammingSymbols("if (score >= 50)"), containsString("greater than or equal to"));
        assertThat(TtsService.expandProgrammingSymbols("score ≥ 50"), containsString("greater than or equal to"));
        assertThat(TtsService.expandProgrammingSymbols("(x) => x * 2"), containsString("implies"));
        assertThat(TtsService.expandProgrammingSymbols("A ⇒ B"), containsString("implies"));
        assertThat(TtsService.expandProgrammingSymbols("ptr->value"), containsString("arrow"));
        assertThat(TtsService.expandProgrammingSymbols("go → right"), containsString("arrow"));
        assertThat(TtsService.expandProgrammingSymbols("go ← left"), containsString("left arrow"));
        assertThat(TtsService.expandProgrammingSymbols("go ↑ up"), containsString("up arrow"));
        assertThat(TtsService.expandProgrammingSymbols("go ↓ down"), containsString("down arrow"));
        assertThat(TtsService.expandProgrammingSymbols("a && b"), containsString("double ampersand"));
        assertThat(TtsService.expandProgrammingSymbols("c || d"), containsString("double pipe"));
        assertThat(TtsService.expandProgrammingSymbols("/* comment */"), containsString("comment start"));
        assertThat(TtsService.expandProgrammingSymbols("Wait..."), containsString("dot dot dot"));
        assertThat(TtsService.expandProgrammingSymbols("Wait…"), containsString("dot dot dot"));
        assertThat(TtsService.expandProgrammingSymbols("± 5"), containsString("plus or minus"));
        assertThat(TtsService.expandProgrammingSymbols("5 × 10"), containsString("times"));
        assertThat(TtsService.expandProgrammingSymbols("10 ÷ 2"), containsString("divided by"));
        assertThat(TtsService.expandProgrammingSymbols("x ≈ y"), containsString("almost equal to"));
        assertThat(TtsService.expandProgrammingSymbols("Done ✓"), containsString("check"));
        assertThat(TtsService.expandProgrammingSymbols("• Item"), containsString("bullet"));
        assertThat(TtsService.expandProgrammingSymbols("30° C"), containsString("30 degrees"));
    }

    @Test
    public void testPreprocessIndianText_CurrencyAndCommas() {
        // Indian Rupee currency normalization
        String res1 = TtsService.preprocessIndianText("Paid ₹1,50,000 for service");
        assertThat(res1, containsString("150000 rupees"));

        String res2 = TtsService.preprocessIndianText("Total is Rs. 25,000 only");
        assertThat(res2, containsString("25000 rupees"));

        // Indian comma grouping normalization
        String res3 = TtsService.preprocessIndianText("Population count 12,34,567 registered");
        assertThat(res3, containsString("1234567"));
    }

    @Test
    public void testPreprocessIndianText_DandaAndBankingSlash() {
        // Danda spacing
        String res1 = TtsService.preprocessIndianText("नमस्ते।आप कैसे हैं?");
        assertThat(res1, is("नमस्ते। आप कैसे हैं?"));

        // Banking transaction slash separation
        String res2 = TtsService.preprocessIndianText("Transaction UPI/423891028341/PAYTM completed");
        assertThat(res2, containsString("UPI / 423891028341 / PAYTM"));
    }

    @Test
    public void testPreprocessIndianText_ShorthandQuantities() {
        assertThat(TtsService.preprocessIndianText("Salary is 50k"), containsString("50 thousand"));
        assertThat(TtsService.preprocessIndianText("Budget of 5L allocated"), containsString("5 lakh"));
        assertThat(TtsService.preprocessIndianText("Turnover 2cr expected"), containsString("2 crore"));
    }

    @Test
    public void testSmartOtpAndPinReading() {
        // OTP/PIN numbers should be digit-separated
        assertThat(TtsService.spaceSeparateSmartCodes("Your OTP is 4829"), is("Your OTP is 4 8 2 9"));
        assertThat(TtsService.spaceSeparateSmartCodes("Verification code: 940218"), containsString("9 4 0 2 1 8"));
        assertThat(TtsService.spaceSeparateSmartCodes("Security PIN 1234"), containsString("1 2 3 4"));

        // Non-security numbers should NOT be split digit-by-digit
        assertThat(TtsService.spaceSeparateSmartCodes("The year 2024 is great"), is("The year 2024 is great"));
    }

    @Test
    public void testLiveSynthesisIndicLanguages() throws InterruptedException {
        List<Voice> availableVoices = mEngine.getAvailableVoices();
        Map<String, Voice> voiceMap = new HashMap<String, Voice>();
        for (Voice v : availableVoices) {
            voiceMap.put(v.name, v);
        }

        String[][] testCases = new String[][]{
                {"en-in", "This is an Indian English test. Payment ₹1,00,000 received."},
                {"hi", "यह हिंदी भाषा का परीक्षण है। ₹1,50,000 का भुगतान।"},
                {"mr", "नमस्कार, हे मराठी भाषेचे परीक्षण आहे."},
                {"pa", "ਸਤਿ ਸ਼੍ਰੀ ਅਕਾਲ, ਇਹ ਪੰਜਾਬੀ ਭਾਸ਼ਾ ਦੀ ਪਰਖ ਹੈ।"},
                {"bn", "নমস্কার, এটি বাংলা ভাষার পরীক্ষা।"},
                {"gu", "નમસ્તે, આ ગુજરાતી ભાષાની ચકાસણી છે."},
                {"ta", "வணக்கம், இது தமிழ் மொழி சோதனை."},
                {"te", "నమస్కారం, ఇది తెలుగు భాష పరీక్ష."},
                {"kn", "ನಮಸ್ಕಾರ, ಇದು ಕನ್ನಡ ಭಾಷೆಯ ಪರೀಕ್ಷೆ."},
                {"ml", "നമസ്കാരം, ഇത് മലയാളം ഭാഷാ പരീക്ഷണം ആണ്."}
        };

        VoiceVariant defaultVariant = VoiceVariant.parseVoiceVariant(VoiceVariant.MALE);

        for (String[] tc : testCases) {
            final String lang = tc[0];
            final String text = tc[1];
            Voice voice = voiceMap.get(lang);
            assertThat("Voice for " + lang + " should be available in bundled core APK", voice, is(notNullValue()));

            mEngine.setVoice(voice, defaultVariant);
            mAudioBytesReceived.set(0);
            mLatch = new CountDownLatch(1);

            mEngine.synthesize(text, false);
            boolean done = mLatch.await(5, TimeUnit.SECONDS);

            assertThat("Synthesis for " + lang + " timed out", done, is(true));
            assertThat("Audio bytes for " + lang + " should be > 0", mAudioBytesReceived.get(), greaterThan(0));
            Log.i(TAG, "Successfully synthesized " + lang + ": " + mAudioBytesReceived.get() + " audio bytes generated");
        }
    }

    @Test
    public void testEmojiSynthesisRichDescriptions() throws InterruptedException {
        List<Voice> availableVoices = mEngine.getAvailableVoices();
        Map<String, Voice> voiceMap = new HashMap<String, Voice>();
        for (Voice v : availableVoices) {
            voiceMap.put(v.name, v);
        }

        VoiceVariant defaultVariant = VoiceVariant.parseVoiceVariant(VoiceVariant.MALE);

        // 1. Test rich modern emojis in English: smiling face with hearts, diya lamp, hindu temple, pink heart, phoenix, heart hands
        Voice enVoice = voiceMap.get("en");
        if (enVoice == null) enVoice = voiceMap.get("en-in");
        assertThat("English voice should be available", enVoice, is(notNullValue()));
        mEngine.setVoice(enVoice, defaultVariant);

        String[] enEmojiTests = new String[]{
                "🥰", "🪔", "🛕", "🩷", "🐦‍🔥", "🫶", "🫡"
        };
        for (String emoji : enEmojiTests) {
            mAudioBytesReceived.set(0);
            mLatch = new CountDownLatch(1);
            mEngine.synthesize("Emoji test: " + emoji, false);
            boolean done = mLatch.await(5, TimeUnit.SECONDS);
            assertThat("Synthesis for English emoji " + emoji + " timed out", done, is(true));
            assertThat("Audio bytes for English emoji " + emoji + " should be > 0", mAudioBytesReceived.get(), greaterThan(0));
            Log.i(TAG, "English emoji " + emoji + " synthesized: " + mAudioBytesReceived.get() + " bytes");
        }

        // 2. Test rich modern emojis in Hindi: प्यार में डूबा चेहरा, दीपक, हिंदू मंदिर, अभिवादन, गुलाबी दिल, गिलहरी
        Voice hiVoice = voiceMap.get("hi");
        assertThat("Hindi voice should be available", hiVoice, is(notNullValue()));
        mEngine.setVoice(hiVoice, defaultVariant);

        String[] hiEmojiTests = new String[]{
                "🥰", "🙏", "🪔", "🛕", "🩷", "🐿", "🐸", "🥺"
        };
        for (String emoji : hiEmojiTests) {
            mAudioBytesReceived.set(0);
            mLatch = new CountDownLatch(1);
            mEngine.synthesize("इमोजी परीक्षण: " + emoji, false);
            boolean done = mLatch.await(5, TimeUnit.SECONDS);
            assertThat("Synthesis for Hindi emoji " + emoji + " timed out", done, is(true));
            assertThat("Audio bytes for Hindi emoji " + emoji + " should be > 0", mAudioBytesReceived.get(), greaterThan(0));
            Log.i(TAG, "Hindi emoji " + emoji + " synthesized: " + mAudioBytesReceived.get() + " bytes");
        }
    }

    @Test
    public void testReadingStyleAndSymbols() throws InterruptedException {
        List<Voice> availableVoices = mEngine.getAvailableVoices();
        Voice enVoice = null;
        for (Voice v : availableVoices) {
            if ("en".equals(v.name) || "en-in".equals(v.name)) {
                enVoice = v;
                break;
            }
        }
        assertThat("English voice should be available", enVoice, is(notNullValue()));

        VoiceVariant maxVariant = VoiceVariant.parseVoiceVariant(VoiceSettings.DEFAULT_VARIANT);
        mEngine.setVoice(enVoice, maxVariant);

        String[] testPhrases = new String[]{
                "Testing symbols: x != y, a <= b, and A => B.",
                "Moving right: go -> door, or follow the arrow →.",
                "Math check: 5 × 10 = 50, 10 ÷ 2 = 5, ± 3 error margin.",
                "Reading ellipsis: waiting... done…",
                "Testing bracket protection: [[phoneme]] and [brackets]."
        };

        for (String phrase : testPhrases) {
            String expanded = TtsService.expandProgrammingSymbols(phrase);
            mAudioBytesReceived.set(0);
            mLatch = new CountDownLatch(1);
            mEngine.synthesize(expanded, false);
            boolean done = mLatch.await(5, TimeUnit.SECONDS);
            assertThat("Synthesis for phrase timed out: " + phrase, done, is(true));
            assertThat("Audio bytes should be > 0 for phrase: " + phrase, mAudioBytesReceived.get(), greaterThan(0));
            Log.i(TAG, "Phrase synthesized successfully: " + mAudioBytesReceived.get() + " bytes");
        }
    }
}
