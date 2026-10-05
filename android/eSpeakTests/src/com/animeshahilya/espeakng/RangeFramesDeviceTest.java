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

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Word ranges carry frames from the start of the request (rangeStart's
 * contract). Text longer than one synthesis unit (800 characters) used to
 * restart them at 0 for each unit, so a screen reader highlighted every
 * later word too early.
 */
@RunWith(AndroidJUnit4.class)
public class RangeFramesDeviceTest {

    @Test
    public void frameMarkersKeepRisingAcrossUnits() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final StringBuilder text = new StringBuilder();
        while (text.length() < 2000) {
            text.append("The quick brown fox jumps over the lazy dog near the river bank. ");
        }
        final CountDownLatch init = new CountDownLatch(1);
        final TextToSpeech tts = new TextToSpeech(context, s -> init.countDown(), context.getPackageName());
        assertTrue(init.await(30, TimeUnit.SECONDS));
        tts.setLanguage(Locale.US);
        final List<int[]> ranges = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { done.countDown(); }
            @Override @SuppressWarnings("deprecation") public void onError(String id) { done.countDown(); }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                synchronized (ranges) {
                    ranges.add(new int[] {start, frame});
                }
            }
        });
        tts.synthesizeToFile(text.toString(), null, new File(context.getCacheDir(), "ranges.wav"), "ranges");
        assertTrue("synthesis did not finish", done.await(60, TimeUnit.SECONDS));
        tts.shutdown();

        synchronized (ranges) {
            assertTrue("too few ranges: " + ranges.size(), ranges.size() > 300);
            // Both values only rise through the text: checking both also
            // holds where file synthesis hands the frame over as the first
            // argument (seen on Android 17).
            int lastA = -1;
            int lastB = -1;
            for (int[] r : ranges) {
                assertTrue("went back: " + r[0] + "," + r[1] + " after " + lastA + "," + lastB,
                        r[0] >= lastA && r[1] >= lastB);
                lastA = r[0];
                lastB = r[1];
            }
        }
    }
}
