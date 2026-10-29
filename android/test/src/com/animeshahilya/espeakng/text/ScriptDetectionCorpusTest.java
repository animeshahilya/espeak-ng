/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Detect the language of each sentence" on real sentences: a Kurdish tester
 * (2026-10-29) set Arabic script to Central Kurdish and then heard Arabic
 * read as Kurdish. Every case is a whole sentence routed as the engine does.
 */
public class ScriptDetectionCorpusTest {
    private static final String[] SORANI = {
            "ئەمڕۆ کەشوهەوا زۆر خۆشە.", "ناوم ئەحمەدە و لە هەولێر دەژیم.", "سوپاس بۆ یارمەتییەکەت.",
            "سڵاو، چۆنی؟", "من کوردم.", "باشم.", "ئەم کتێبە زۆر باشە.", "کوردستان وڵاتێکی جوانە.",
            "دەمەوێت بچم بۆ بازاڕ.", "ئایا تۆ قسە بە کوردی دەکەیت؟", "کاتژمێر چەندە؟",
            "من خوێندکارم لە زانکۆی سلێمانی.", "ئەو پەیامەی ناردت گەیشت.", "تکایە دەرگاکە دابخە.",
            "نامەیەکی نوێت هەیە.", "بەڵێ.", "نەخێر.", "سەعات دە."};
    private static final String[] ARABIC = {
            "الحمد لله رب العالمين.", "كيف حالك اليوم؟", "مرحبا بكم في مدينة أربيل.",
            "قال رسول الله صلى الله عليه وسلم.", "هذا كتاب جميل.", "أنا أحب اللغة العربية.",
            "شكرا جزيلا.", "السلام عليكم ورحمة الله وبركاته.", "بسم الله الرحمن الرحيم.", "ما اسمك؟",
            "ذهبت إلى المدرسة.", "لديك رسالة جديدة.", "من فضلك أغلق الباب.", "صباح الخير."};
    /** No letter only Arabic uses: the chosen language reads it. */
    private static final String AMBIGUOUS_ARABIC = "نعم.";
    private static final String[] PERSIAN = {
            "این یک کتاب است.", "من ایرانی هستم.", "امروز هوا خیلی خوب است.", "از کمک شما ممنونم."};
    private static final String[] URDU = {"یہ ایک کتاب ہے۔", "آپ کیسے ہیں؟", "میں ٹھیک ہوں۔"};

    private static Map<UnicodeScript, String> arabicScriptAs(String language) {
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        chosen.put(UnicodeScript.ARABIC, language);
        return chosen;
    }

    /** The languages of the non-blank runs. */
    private static String route(String text, String own, Map<UnicodeScript, String> chosen, boolean detect) {
        final List<String> out = new ArrayList<>();
        for (LanguageRuns.Run r : LanguageRuns.split(text, own, chosen, null, l -> null, false, detect)) {
            if (!r.text.trim().isEmpty()) {
                out.add(r.language);
            }
        }
        return String.join(" ", out);
    }

    private static void assertAll(String expected, String[] sentences, String own,
                                  Map<UnicodeScript, String> chosen) {
        for (String s : sentences) {
            assertEquals(own + " " + chosen + ": " + s, expected, route(s, own, chosen, true));
        }
    }

    @Test
    public void testersCaseArabicScriptSetToSorani() {
        // English, Arabic or Kurdish voice, Arabic script set to Central Kurdish.
        for (String own : new String[] {"eng", "ara", "ckb"}) {
            final Map<UnicodeScript, String> ckb = arabicScriptAs("ckb");
            assertAll("ckb", SORANI, own, ckb);
            assertAll("ara", ARABIC, own, ckb);
            assertAll("fas", PERSIAN, own, ckb);
            assertAll("urd", URDU, own, ckb);
            assertEquals("ckb", route(AMBIGUOUS_ARABIC, own, ckb, true));
        }
    }

    @Test
    public void nothingChosen() {
        for (String own : new String[] {"eng", "ara"}) {
            assertAll("ara", ARABIC, own, Collections.emptyMap());
            assertAll("fas", PERSIAN, own, Collections.emptyMap());
            assertAll("urd", URDU, own, Collections.emptyMap());
        }
        // Without a choice, Sorani needs its own letters (ە ۆ ێ ڵ ڕ): "من کوردم."
        // could be Persian and "باشم." anything. 16 of 18 have them.
        int sorani = 0;
        for (String s : SORANI) {
            sorani += route(s, "eng", Collections.emptyMap(), true).equals("ckb") ? 1 : 0;
        }
        assertEquals(16, sorani);
        // A Kurdish voice reads its own unmarked sentences.
        assertAll("ckb", SORANI, "ckb", Collections.emptyMap());
        assertAll("ara", ARABIC, "ckb", Collections.emptyMap());
    }

    @Test
    public void detectionOffKeepsTheChoice() {
        // As before the setting: a chosen script reads every sentence in its language.
        assertEquals("ckb", route("الحمد لله رب العالمين.", "eng", arabicScriptAs("ckb"), false));
        assertEquals("ckb", route("ئەمڕۆ کەشوهەوا زۆر خۆشە.", "eng", arabicScriptAs("ckb"), false));
    }

    @Test
    public void mixedText() {
        final String text = "New message: ئەمڕۆ کەشوهەوا زۆر خۆشە. الحمد لله رب العالمين. سوپاس بۆ یارمەتییەکەت.";
        assertEquals("eng ckb ara ckb", route(text, "eng", arabicScriptAs("ckb"), true));
        assertEquals("ckb ara ckb", route(text.substring(13), "ckb", Collections.emptyMap(), true));
        // eSpeak alone: only the Arabic-script parts get their own voice.
        final StringBuilder b = new StringBuilder();
        for (LanguageRuns.Run r : LanguageRuns.detectedRuns(text, "eng", arabicScriptAs("ckb"))) {
            b.append(r.language).append(' ');
        }
        assertEquals("eng ckb ara ckb", b.toString().trim());
    }

    @Test
    public void espeakSwitchesOnlyWhereItReadsTheLanguage() {
        // Arabic script set to Sorani: eSpeak would read Arabic as Sorani itself.
        assertEquals(false, LanguageRuns.espeakReadsItself("ara", "eng",
                ScriptLanguages.runLanguages(Collections.singletonMap("ar", "ckb"))));
        assertEquals(true, LanguageRuns.espeakReadsItself("ckb", "eng",
                ScriptLanguages.runLanguages(Collections.singletonMap("ar", "ckb"))));
        assertEquals(true, LanguageRuns.espeakReadsItself("ara", "eng", Collections.emptyMap()));
        // In its own script eSpeak never switches: the Arabic voice spelled Sorani out.
        assertEquals(false, LanguageRuns.espeakReadsItself("ckb", "ara",
                ScriptLanguages.runLanguages(Collections.singletonMap("ar", "ckb"))));
    }
}
