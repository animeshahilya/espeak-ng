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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Guards the paragraph-break contract the synthesis pipeline relies on:
 * blank lines must survive every preprocessing step so the engine still
 * sees them and produces its longer paragraph pauses (readclause.c treats
 * a second newline as a paragraph, with still longer pauses for runs of
 * blank lines). A step that collapsed "\n\n" would silently flatten
 * paragraph prosody into sentence prosody.
 */
@RunWith(AndroidJUnit4.class)
public class TextPipelineDeviceTest {
    @Test
    public void testWatchdogSanitizerKeepsParagraphBreaks() {
        assertThat(TtsService.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", false),
                containsString("\n\n"));
        assertThat(TtsService.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", true),
                containsString("\n\n"));
    }

    @Test
    public void testChunkingKeepsParagraphBreaks() {
        final String text = "First paragraph with enough words to matter.\n\n"
                + "Second paragraph right after a blank line.\n\nThird one.";
        final List<String> chunks = TtsService.chunkForWatchdog(text);
        assertThat(chunks, is(not(empty())));
        final StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            assertThat(chunk, is(not("")));
            joined.append(chunk);
        }
        assertThat(joined.toString(), is(text));
        assertThat(joined.toString(), containsString("\n\n"));
    }

    @Test
    public void testScriptSplittingKeepsParagraphBreaks() {
        final String text = "Hello world.\n\nनमस्ते दुनिया।\n\nBack to English.";
        final List<TtsService.ScriptSpan> spans = TtsService.splitByScriptRuns(text);
        assertThat(spans.size(), greaterThanOrEqualTo(2));
        final StringBuilder joined = new StringBuilder();
        for (TtsService.ScriptSpan span : spans) {
            joined.append(span.text);
        }
        assertThat(joined.toString(), is(text));
        assertThat(joined.toString(), containsString("\n\n"));
    }

    @Test
    public void testScriptSplittingEdgeCasesPreserveLength() {
        final String[] testCases = {
            "123 numbers only",
            "   leading space Latin नमस्ते Indic",
            "Hello नमस्ते world दुनिया",
            "A\n\nB\n\nनमस्ते",
            "Mix 456 बीच 789 end"
        };
        for (String testCase : testCases) {
            final List<TtsService.ScriptSpan> spans = TtsService.splitByScriptRuns(testCase);
            final StringBuilder joined = new StringBuilder();
            for (TtsService.ScriptSpan span : spans) {
                joined.append(span.text);
            }
            assertThat("Failed for: " + testCase, joined.toString(), is(testCase));
        }
    }

    @Test
    public void testUserDictionaryFastPathBypass() {
        UserDictionary rule = new UserDictionary("quick", "slow", true, false, false);
        assertThat(rule.apply("The fast brown fox"), is("The fast brown fox"));
        assertThat(rule.apply("The quick brown fox"), is("The slow brown fox"));
        assertThat(rule.apply("hi"), is("hi")); // Shorter than pattern
    }

    @Test
    public void testFastTanhAccuracy() {
        for (float x = -4.0f; x <= 4.0f; x += 0.1f) {
            float expected = (float) Math.tanh(x);
            float actual = AudioOptimizer.fastTanh(x);
            assertThat("x=" + x, Math.abs(actual - expected), lessThan(0.002f));
        }
    }
}
