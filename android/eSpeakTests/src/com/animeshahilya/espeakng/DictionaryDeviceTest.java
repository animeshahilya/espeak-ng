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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class DictionaryDeviceTest {
    private static final String TAG = "DictionaryDeviceTest";
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
        // This class drives SpeechSynthesis directly (no TTS bind), so make
        // sure the voice data is extracted first regardless of test order -
        // otherwise enumeration/synthesis see an empty data dir.
        CheckVoiceData.ensureVoiceData(mContext);
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

        // Full Indian formatting on Devanagari digits: ₹१,५०,००० -> 1 lakh 50000 rupees
        String res = TtsService.preprocessIndianText("कुल राशि ₹१,५०,००० प्राप्त हुई।");
        assertThat(res, containsString("1 lakh 50000 rupees"));
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
        String output = mgr.applyRules(input, "en");

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
        assertThat(mgr.applyRules("Visit AIIMS today", "en"), containsString("All India Institute of Medical Sciences"));
    }

    @Test
    public void testUserDictionaryLanguageScoping() {
        UserDictionaryManager mgr = UserDictionaryManager.getInstance(mContext);
        mgr.clearRules();

        // Unscoped rule applies everywhere.
        mgr.addRule(new UserDictionary("OTP", "one time password", false, false, true, ""));
        // Rule scoped to the base language "en" should also match variants like "en-in".
        mgr.addRule(new UserDictionary("read", "reed", false, false, true, "en"));
        // Rule scoped to a specific variant should not leak into the base language or a sibling variant.
        mgr.addRule(new UserDictionary("schedule", "shed-yool", false, false, true, "en-us"));

        assertThat(mgr.applyRules("Please read the OTP", "en"), is("Please reed the one time password"));
        assertThat(mgr.applyRules("Please read the OTP", "en-in"), is("Please reed the one time password"));
        assertThat(mgr.applyRules("Please read the OTP", "hi"), is("Please read the one time password"));

        assertThat(mgr.applyRules("Check the schedule", "en-us"), is("Check the shed-yool"));
        assertThat(mgr.applyRules("Check the schedule", "en-in"), is("Check the schedule"));
        assertThat(mgr.applyRules("Check the schedule", "en"), is("Check the schedule"));

        mgr.clearRules();
    }

    /**
     * Regression test for a real word-boundary misalignment bug, exercised
     * through the full public TextToSpeech path (the same one TalkBack
     * uses). Before TextOffsetMap existed, a length-changing preprocessing
     * step earlier in the utterance (here, a user-dictionary rule expanding
     * "cat" to "hippopotamus") shifted every rangeStart() position reported
     * afterward with no compensation - e.g. the word "and" was reported as
     * the range covering "un " (part of "run") in the original text. See
     * TextOffsetMap's class doc and TtsService.chainOffset().
     */
    @Test
    public void testWordBoundaryOffsetsAfterDictionaryReplacement() throws Exception {
        Context storageContext = EspeakApp.getStorageContext();
        UserDictionaryManager mgr = UserDictionaryManager.getInstance(storageContext);
        mgr.clearRules();
        mgr.addRule(new UserDictionary("cat", "hippopotamus", false, false, true, ""));

        android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(storageContext);
        prefs.edit().putBoolean(VoiceSettings.PREF_USER_DICTIONARY, true).commit();

        final String text = "cat and dog run fast";
        final List<int[]> ranges = java.util.Collections.synchronizedList(new java.util.ArrayList<int[]>());
        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final android.speech.tts.TextToSpeech[] holder = new android.speech.tts.TextToSpeech[1];

        holder[0] = new android.speech.tts.TextToSpeech(mContext.getApplicationContext(),
                status -> initLatch.countDown(), "com.animeshahilya.espeakng");
        assertThat("engine failed to init", initLatch.await(15, TimeUnit.SECONDS), is(true));
        android.speech.tts.TextToSpeech tts = holder[0];
        tts.setLanguage(java.util.Locale.US);
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override
            public void onDone(String utteranceId) {
                doneLatch.countDown();
            }

            // onError(String) is abstract in UtteranceProgressListener, so it must
            // be implemented even though it is deprecated; the two-arg overload
            // (added in API 23) delegates to it by default.
            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                doneLatch.countDown();
            }

            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                ranges.add(new int[] {start, end});
            }
        });

        int result = tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "wb_diag");
        assertThat("speak() rejected the request", result, is(android.speech.tts.TextToSpeech.SUCCESS));
        assertThat("synthesis did not finish", doneLatch.await(20, TimeUnit.SECONDS), is(true));
        tts.shutdown();
        mgr.clearRules();

        Log.i(TAG, "wordBoundaryOffsets: original=\"" + text + "\" (len=" + text.length()
                + "), " + ranges.size() + " onRangeStart events");
        List<String> reportedWords = new java.util.ArrayList<>();
        for (int[] r : ranges) {
            int start = r[0], end = r[1];
            String slice = (start >= 0 && end <= text.length() && start < end)
                    ? text.substring(start, end) : "<out of bounds>";
            Log.i(TAG, "  range [" + start + "," + end + ") -> \"" + slice + "\"");
            reportedWords.add(slice);
        }

        // Every word after the replaced "cat" must still be reported as
        // exactly itself against the ORIGINAL text, not shifted by the
        // length "hippopotamus" added.
        assertThat(reportedWords, hasItems("cat", "and", "dog", "run", "fast"));
    }

    @Test
    public void testRegexCapturingGroupsSupported() {
        UserDictionary rule = new UserDictionary("item-(\\d+)", "number-$1", false, true, false);
        String result = rule.apply("item-42");
        assertThat(result, is("number-42"));

        UserDictionary wordRule = new UserDictionary("([a-z]+)", "word", false, true, false);
        assertThat(wordRule.apply("hello"), is("word"));
    }

    @Test
    public void testNestedQuantifierReDoSBlocked() {
        // Catastrophic nested quantifiers must be rejected
        UserDictionary redosRule = new UserDictionary("(a+)+", "b", false, true, false);
        assertThat(redosRule.apply("aaaaaaaaaaaaaaaa"), is("aaaaaaaaaaaaaaaa"));

        UserDictionary redosRule2 = new UserDictionary("(a*)*", "b", false, true, false);
        assertThat(redosRule2.apply("aaaaaaaaaaaaaaaa"), is("aaaaaaaaaaaaaaaa"));
    }

    @Test
    public void testAtomicSaveAndReload() {
        UserDictionaryManager mgr = UserDictionaryManager.getInstance(mContext);
        mgr.clearRules();
        UserDictionary rule = new UserDictionary("testPattern", "testReplacement", true, false, true);
        mgr.addRule(rule);

        List<UserDictionary> rules = mgr.getRules();
        assertThat(rules.size(), is(1));
        assertThat(rules.get(0).getPattern(), is("testPattern"));
        assertThat(rules.get(0).getReplacement(), is("testReplacement"));

        mgr.clearRules();
    }

}
