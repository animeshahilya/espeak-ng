/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import com.animeshahilya.espeakng.text.NvdaSymbolProcessor;
import com.animeshahilya.espeakng.text.TextPreprocessor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * NVDA's per-language symbol files on the device's own regex engine (ICU),
 * which the JVM tests do not exercise: every language builds with all its
 * patterns, keeps newlines, and Arabic gets Arabic names.
 */
@RunWith(AndroidJUnit4.class)
public class NvdaSymbolsDeviceTest {

    @Test
    public void everyLanguageCompilesOnIcu() throws Exception {
        final String[] files = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().list("symbols");
        assertNotNull(files);
        final List<String> problems = new ArrayList<>();
        int languages = 0;
        for (String file : files) {
            if (!file.endsWith(".dic") || file.endsWith(".cldr.dic")) {
                continue;
            }
            final String tag = file.substring(0, file.length() - 4);
            final List<String> skipped = NvdaSymbolProcessor.skippedPatterns(tag);
            if (!skipped.isEmpty()) {
                problems.add(tag + ": " + skipped);
            }
            final String out = NvdaSymbolProcessor.processText("a, b.\n\nc (d) -2 3.5 50%!",
                    NvdaSymbolProcessor.LEVEL_ALL, true, tag);
            if (!out.contains("\n\n")) {
                problems.add(tag + " lost a newline: " + out);
            }
            languages++;
        }
        assertTrue(languages > 50);
        assertEquals(new ArrayList<String>(), problems);
    }

    /** NVDA's characterDescriptions load from the APK: phonetic letters per language. */
    @Test
    public void phoneticLettersUseTheVoicesLanguage() {
        assertEquals("b, Berlin", TextPreprocessor.expandNatoSpelling("b", "de"));
        assertEquals("б, Борис", TextPreprocessor.expandNatoSpelling("б", "ru"));
        assertEquals("b, Bravo", TextPreprocessor.expandNatoSpelling("b", "en-us"));
    }

    @Test
    public void arabicGetsArabicNames() {
        final String out = NvdaSymbolProcessor.processText("مرحبا، كيف حالك؟",
                NvdaSymbolProcessor.LEVEL_ALL, true, "ar");
        assertTrue(out, out.contains("فاصلة"));
        assertTrue(out, out.contains("استفهام"));
    }
}
