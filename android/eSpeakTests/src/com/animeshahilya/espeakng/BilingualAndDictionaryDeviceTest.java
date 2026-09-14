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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class BilingualAndDictionaryDeviceTest {
    private static final String TAG = "BilingualAndDictTest";
    private Context mContext;
    private SpeechSynthesis mEngine;
    private final AtomicInteger mAudioBytesReceived = new AtomicInteger(0);
    private volatile CountDownLatch mLatch;

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
        mContext = ApplicationProvider.getApplicationContext();
        mEngine = new SpeechSynthesis(mContext, mCallback);
    }

    @After
    public void tearDown() {
        if (mEngine != null) {
            mEngine.stop();
            mEngine = null;
        }
    }

    @Test
    public void testNormalizeIndicDigitsAcrossScripts() {
        // Devanagari numerals
        assertThat(TtsService.normalizeIndicDigits("०१२३४५६७८९"), is("0123456789"));
        // Gurmukhi numerals
        assertThat(TtsService.normalizeIndicDigits("੦੧੨੩੪੫੬੭੮੯"), is("0123456789"));
        // Bengali numerals
        assertThat(TtsService.normalizeIndicDigits("০১২৩৪৫৬৭৮৯"), is("0123456789"));
        // Gujarati numerals
        assertThat(TtsService.normalizeIndicDigits("૦૧૨૩૪૫૬૭૮૯"), is("0123456789"));
        // Odia numerals
        assertThat(TtsService.normalizeIndicDigits("୦୧୨୩୪୫୬୭୮୯"), is("0123456789"));
        // Tamil numerals
        assertThat(TtsService.normalizeIndicDigits("௦௧௨௩௪௫௬௭௮௯"), is("0123456789"));
        // Telugu numerals
        assertThat(TtsService.normalizeIndicDigits("౦౧౨౩౪౫౬౭౮౯"), is("0123456789"));
        // Kannada numerals
        assertThat(TtsService.normalizeIndicDigits("೦೧೨೩೪೫೬೭೮೯"), is("0123456789"));
        // Malayalam numerals
        assertThat(TtsService.normalizeIndicDigits("൦൧൨൩൪൫൬൭൮൯"), is("0123456789"));

        // Full Indian formatting on Devanagari digits: ₹१,५०,००० -> 150000 rupees
        String res = TtsService.preprocessIndianText("कुल राशि ₹१,५०,००० प्राप्त हुई।");
        assertThat(res, containsString("150000 rupees"));
    }

    @Test
    public void testNatoPhoneticSpelling() {
        assertThat(TtsService.expandNatoSpelling("a"), is("a, Alpha"));
        assertThat(TtsService.expandNatoSpelling("B"), is("B, Bravo"));
        assertThat(TtsService.expandNatoSpelling("z"), is("z, Zulu"));
        // Full words unaffected
        assertThat(TtsService.expandNatoSpelling("cat"), is("cat"));
    }

    @Test
    public void testDevanagariDiacriticsExploration() {
        assertThat(TtsService.expandDevanagariDiacritic("\u093E"), is("आ की मात्रा"));
        assertThat(TtsService.expandDevanagariDiacritic("\u094D"), is("हलन्त"));
        assertThat(TtsService.expandDevanagariDiacritic("\u0902"), is("अनुस्वार"));
        // Normal text unaffected
        assertThat(TtsService.expandDevanagariDiacritic("नमस्ते"), is("नमस्ते"));
    }

    @Test
    public void testUserDictionaryRules() {
        UserDictionaryManager mgr = UserDictionaryManager.getInstance(mContext);
        mgr.clearRules();

        mgr.addRule(new UserDictionary("AIIMS", "All India Institute of Medical Sciences", false, false, true));
        mgr.addRule(new UserDictionary("UPI", "Unified Payments Interface", false, false, true));

        String input = "Doctor at AIIMS confirmed UPI transaction";
        String output = mgr.applyRules(input);

        assertThat(output, containsString("All India Institute of Medical Sciences"));
        assertThat(output, containsString("Unified Payments Interface"));

        // Test NVDA .dic import / export
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mgr.exportToStream(baos);
        String exported = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertThat(exported, containsString("AIIMS\tAll India Institute of Medical Sciences"));

        // Test re-importing from NVDA formatted stream
        mgr.clearRules();
        assertThat(mgr.getRules().size(), is(0));
        ByteArrayInputStream bais = new ByteArrayInputStream(exported.getBytes(StandardCharsets.UTF_8));
        int imported = mgr.importFromStream(bais);
        assertThat(imported, is(2));
        assertThat(mgr.applyRules("Visit AIIMS today"), containsString("All India Institute of Medical Sciences"));
    }

    @Test
    public void testScriptRunSegmentation() {
        String mixedText = "नमस्ते Alex, your OTP is 4829. कृपया ध्यान दें।";
        List<TtsService.ScriptSpan> spans = TtsService.splitByScriptRuns(mixedText);

        assertThat(spans.size(), greaterThanOrEqualTo(2));
        Log.i(TAG, "Spans generated: " + spans.size());
        for (TtsService.ScriptSpan span : spans) {
            Log.i(TAG, "Span [isLatin=" + span.isLatin + "]: '" + span.text + "'");
        }

        // First span should be Indic (Devanagari)
        assertThat(spans.get(0).isLatin, is(false));
        assertThat(spans.get(0).text, containsString("नमस्ते"));

        // Second span should be Latin
        assertThat(spans.get(1).isLatin, is(true));
        assertThat(spans.get(1).text, containsString("Alex"));
    }

    @Test
    public void testLiveBilingualDualVoiceSynthesisOnDevice() throws InterruptedException {
        List<Voice> voices = mEngine.getAvailableVoices();
        Map<String, Voice> map = new HashMap<>();
        for (Voice v : voices) {
            map.put(v.name, v);
        }

        Voice hindiVoice = map.get("hi");
        Voice englishVoice = map.get("en-in");

        assertThat("Hindi voice must be available in core", hindiVoice, is(notNullValue()));
        assertThat("Indian English voice must be available in core", englishVoice, is(notNullValue()));

        VoiceVariant defaultVariant = VoiceVariant.parseVoiceVariant(VoiceVariant.MALE);

        String mixedText = "नमस्ते Alex, your OTP is 4829. कृपया ध्यान दें।";
        List<TtsService.ScriptSpan> spans = TtsService.splitByScriptRuns(mixedText);

        mAudioBytesReceived.set(0);

        for (TtsService.ScriptSpan span : spans) {
            Voice chosenVoice = span.isLatin ? englishVoice : hindiVoice;
            mEngine.setVoice(chosenVoice, defaultVariant);

            mLatch = new CountDownLatch(1);
            mEngine.synthesize(span.text, false);
            boolean completed = mLatch.await(4, TimeUnit.SECONDS);
            assertThat("Segment synthesis timed out for: " + span.text, completed, is(true));
        }

        assertThat("Dual-voice synthesis generated audio bytes on device", mAudioBytesReceived.get(), greaterThan(0));
        Log.i(TAG, "Total dual-voice audio bytes generated on device: " + mAudioBytesReceived.get());
    }
}
