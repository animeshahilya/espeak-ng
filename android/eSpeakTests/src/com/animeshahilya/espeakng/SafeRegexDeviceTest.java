/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.animeshahilya.espeakng.text.UserDictionary;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * User-dictionary regex rules on Android's own regex engine (ICU), which the
 * JVM tests never touch: Unicode classes and \b read Indian scripts, the
 * fallback still runs lookarounds, and no rule can stall speech.
 */
@RunWith(AndroidJUnit4.class)
public class SafeRegexDeviceTest {
    private static final String TAG = "SafeRegexDeviceTest";

    private static UserDictionary regex(String pattern, String replacement) {
        return new UserDictionary(pattern, replacement, false, true, false);
    }

    @Test
    public void unicodeRulesReadIndianScripts() {
        assertEquals("X X", regex("\\w+", "X").apply("नमस्ते दुनिया"));
        assertEquals("N", regex("\\d+", "N").apply("१२३"));
        assertEquals("a_b", regex("\\s", "_").apply("a b"));
        assertEquals("India है", regex("\\bभारत\\b", "India").apply("भारत है"));
        assertEquals("Doctor Rao", regex("\\bDr\\.?(?=\\s)", "Doctor").apply("Dr. Rao"));
        assertEquals("10 kilograms", regex("(\\d+)\\s*kg", "$1 kilograms").apply("10 kg"));
        assertEquals("aa", regex("(\\w)\\1", "$1$1").apply("aa"));
    }

    @Test(timeout = 20000)
    public void noRuleStallsSpeech() {
        final String text = "a".repeat(20000) + "!" + "x".repeat(20000) + "#";
        for (String p : new String[] {"\\w*\\w*\\w*\\w*#", "(a+)+$", "(a|aa)+$", "(.*a){12}$",
                "a?a?a?a?a?a?a?a?a?a?aaaaaaaaaa$"}) {
            final UserDictionary rule = regex(p, "b");
            assertTrue(p, rule.isValid());
            final long t = System.nanoTime();
            rule.apply(text);
            Log.i(TAG, p + ": " + (System.nanoTime() - t) / 1000 + " us on " + text.length() + " chars");
        }
        for (String p : new String[] {"(?=x)\\w*\\w*#", "\\b(\\w+\\s?)+$"}) {
            assertFalse(p, regex(p, "b").isValid());
        }
    }

    /** Typical rules on a TalkBack-sized utterance: RE2/J vs the platform engine. */
    @Test
    public void typicalRuleCost() {
        final String text = ("Dr. Rao paid ₹1,250 for 10 kg of rice on 12/10 - डॉ. राव ने चावल खरीदा। ").repeat(8);
        final String[] patterns = {"\\bDr\\.", "(\\d+)\\s*kg", "\\d+/\\d+", "राव", "[A-Z]{2,}"};
        for (String p : patterns) {
            final UserDictionary rule = regex(p, "x");
            final java.util.regex.Pattern platform = java.util.regex.Pattern.compile(p,
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
            for (int i = 0; i < 200; i++) { // warm up both
                rule.apply(text);
                platform.matcher(text).replaceAll("x");
            }
            long t = System.nanoTime();
            for (int i = 0; i < 1000; i++) rule.apply(text);
            final long ours = (System.nanoTime() - t) / 1000;
            t = System.nanoTime();
            for (int i = 0; i < 1000; i++) platform.matcher(text).replaceAll("x");
            final long icu = (System.nanoTime() - t) / 1000;
            Log.i(TAG, String.format("%-14s rule %5.1f us, platform %5.1f us per %d chars",
                    p, ours / 1000.0, icu / 1000.0, text.length()));
        }
    }
}
