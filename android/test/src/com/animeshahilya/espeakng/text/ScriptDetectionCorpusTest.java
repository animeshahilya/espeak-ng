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
    /** No letter only Arabic uses, but an Arabic word (WORDS). */
    private static final String ARABIC_BY_WORD = "نعم.";
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
            assertEquals("ara", route(ARABIC_BY_WORD, own, ckb, true));
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
        // could be Persian; "باشم." is Sorani by its word. 17 of 18.
        int sorani = 0;
        for (String s : SORANI) {
            sorani += route(s, "eng", Collections.emptyMap(), true).equals("ckb") ? 1 : 0;
        }
        assertEquals(17, sorani);
        // A Kurdish voice reads its own unmarked sentences.
        assertAll("ckb", SORANI, "ckb", Collections.emptyMap());
        assertAll("ara", ARABIC, "ckb", Collections.emptyMap());
    }

    // Cyrillic without і ї є ґ or ы э ъ ё: told apart by common words (WORDS).
    // Tuning set: the words were written from general vocabulary, then
    // checked here; held-out: written after the word lists, never tuned on.
    private static final String[] UKRAINIAN_TUNING = {
            "Як справи?", "Дякую, все добре.", "Це моя книга.", "Що ти робиш?", "Де ти зараз?",
            "Ми чекаємо на тебе.", "Вона дуже гарна.", "Чому ти мовчиш?", "Будь ласка, зачекай.",
            "Сьогодні тепло.", "Вони вже прийшли.", "Скільки це коштує?", "Мене звати Олена.",
            "Добре, домовились.", "Я тебе кохаю.", "Котра година?", "Ходімо додому.", "Так, звичайно."};
    private static final String[] RUSSIAN_TUNING = {
            "Как дела?", "Спасибо, всё хорошо.", "Это моя книга.", "Что ты делаешь?", "Где ты сейчас?",
            "Мы ждём тебя.", "Она очень красивая.", "Почему ты молчишь?", "Пожалуйста, подожди.",
            "Сегодня тепло.", "Они уже пришли.", "Сколько это стоит?", "Меня зовут Елена.",
            "Хорошо, договорились.", "Я тебя люблю.", "Который час?", "Пойдём домой.", "Да, конечно."};
    private static final String[] UKRAINIAN_HELD_OUT = {
            "Нове повідомлення від мами.", "Натисніть двічі, щоб відкрити.", "Батарея майже розряджена.",
            "Ви впевнені, що хочете видалити цей файл?", "Завантаження завершено.",
            "Немає з'єднання з мережею.", "Я працюю вдома.", "Мій брат живе в Києві.", "Що нового?",
            "Добраніч!", "Ти де?", "Купи хліба, будь ласка.", "Ми поїдемо завтра вранці.",
            "Вибачте, я запізнюся.", "Вона читає книжку.", "Він не прийде.", "Як тебе звати?",
            "Дуже смачно!", "Зателефонуй мені пізніше.", "Погода сьогодні чудова."};
    private static final String[] RUSSIAN_HELD_OUT = {
            "Новое сообщение от мамы.", "Нажмите дважды, чтобы открыть.", "Батарея почти разряжена.",
            "Вы уверены, что хотите удалить этот файл?", "Загрузка завершена.", "Нет подключения к сети.",
            "Я работаю дома.", "Мой брат живёт в Киеве.", "Что нового?", "Спокойной ночи!", "Ты где?",
            "Купи хлеба, пожалуйста.", "Мы поедем завтра утром.", "Извините, я опоздаю.", "Она читает книгу.",
            "Он не придёт.", "Как тебя зовут?", "Очень вкусно!", "Позвони мне позже.", "Погода сегодня отличная."};

    private static int count(String expected, String[] sentences, String own) {
        int n = 0;
        for (String s : sentences) {
            n += route(s, own, Collections.emptyMap(), true).equals(expected) ? 1 : 0;
        }
        return n;
    }

    @Test
    public void ukrainianAndRussianByWords() {
        // 2026-10-29, letters only: Ukrainian 4/18 and 13/20 in English or
        // Russian text, Russian in Ukrainian text 9/18 and 7/20.
        for (String own : new String[] {"eng", "rus"}) {
            assertEquals(own, 17, count("ukr", UKRAINIAN_TUNING, own)); // "Де ти зараз?": shared words only
            assertEquals(own, 18, count("rus", RUSSIAN_TUNING, own));
            assertEquals(own, 16, count("ukr", UKRAINIAN_HELD_OUT, own));
            assertEquals(own, 20, count("rus", RUSSIAN_HELD_OUT, own)); // no Russian sentence lost
        }
        assertEquals(18, count("ukr", UKRAINIAN_TUNING, "ukr"));
        assertEquals(18, count("rus", RUSSIAN_TUNING, "ukr"));
        assertEquals(20, count("ukr", UKRAINIAN_HELD_OUT, "ukr")); // no Ukrainian sentence lost
        assertEquals(15, count("rus", RUSSIAN_HELD_OUT, "ukr"));
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
        for (LanguageRuns.Run r : LanguageRuns.detectedRuns(text, "eng", arabicScriptAs("ckb"), true)) {
            b.append(r.language).append(' ');
        }
        assertEquals("eng ckb ara ckb", b.toString().trim());
    }

    private static String languages(List<LanguageRuns.Run> runs) {
        final List<String> out = new ArrayList<>();
        for (LanguageRuns.Run r : runs) {
            if (!r.text.trim().isEmpty()) {
                out.add(r.language);
            }
        }
        return String.join(" ", out);
    }

    @Test
    public void espeakAloneFollowsEveryDetector() {
        // Marathi in Hindi text: eSpeak read it with Hindi rules; detection gives it its voice.
        final String marathi = "आज बहुत गर्मी है। मी उद्या पुण्याला जाणार आहे।";
        assertEquals("hin mar", languages(LanguageRuns.detectedRuns(marathi, "hin", Collections.emptyMap(), true)));
        assertEquals("hin", languages(LanguageRuns.detectedRuns(marathi, "hin", Collections.emptyMap(), false)));
        assertEquals("eng mar", languages(LanguageRuns.detectedRuns(
                "Meeting at ten. मी उद्या पुण्याला जाणार आहे।", "eng", Collections.emptyMap(), true)));
        // "Recognise European languages" did nothing without natural voices.
        final String spanish = "See you soon. Muchas gracias por todo, mi amigo.";
        try {
            LatinLanguageDetector.setEnabled(true);
            assertEquals("eng spa", languages(LanguageRuns.detectedRuns(spanish, "eng", Collections.emptyMap(), false)));
            LatinLanguageDetector.setEnabled(false);
            assertEquals("eng", languages(LanguageRuns.detectedRuns(spanish, "eng", Collections.emptyMap(), false)));
        } finally {
            LatinLanguageDetector.setEnabled(false);
        }
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
