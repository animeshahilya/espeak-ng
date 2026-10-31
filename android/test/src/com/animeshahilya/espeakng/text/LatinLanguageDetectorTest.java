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
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LatinLanguageDetectorTest {

    @Before
    public void enable() {
        LatinLanguageDetector.setEnabled(true);
    }

    @After
    public void disable() {
        LatinLanguageDetector.setEnabled(false);
    }

    @Test
    public void offByDefault() {
        LatinLanguageDetector.setEnabled(false);
        assertNull(LatinLanguageDetector.detect("Das ist ein sehr gutes Buch.", "eng"));
    }

    @Test
    public void englishAndHinglishThatShareWordsStayUndetected() {
        assertNull(LatinLanguageDetector.detect("Do as I say, not as I do", "eng"));
        assertNull(LatinLanguageDetector.detect("I live on Sur Avenue, plus a con", "eng"));
        assertNull(LatinLanguageDetector.detect("El Paso and La Jolla", "eng"));
        assertNull(LatinLanguageDetector.detect("The da Vinci code has a boa", "eng"));
        assertNull(LatinLanguageDetector.detect("mujhe do minute de do, le lo yaar", "eng"));
    }

    @Test
    public void detectsPortuguesePhrases() {
        assertEquals("por", LatinLanguageDetector.detect("Muito obrigado por tudo, você é bom", "eng"));
    }

    @Test
    public void detectsGermanPhrases() {
        assertEquals("deu", LatinLanguageDetector.detect("Guten Morgen, wie geht es dir und dem Kind?", "eng"));
        assertEquals("deu", LatinLanguageDetector.detect("Das ist ein sehr gutes Buch.", "eng"));
    }

    @Test
    public void detectsFrenchPhrases() {
        assertEquals("fra", LatinLanguageDetector.detect("Merci beaucoup pour votre aide avec le projet.", "eng"));
        assertEquals("fra", LatinLanguageDetector.detect("C'est la vie avec les amis.", "eng"));
    }

    @Test
    public void detectsSpanishPhrases() {
        assertEquals("spa", LatinLanguageDetector.detect("Muchas gracias por todo amigo, hasta luego.", "eng"));
        assertEquals("spa", LatinLanguageDetector.detect("Hola amigo, como estas hoy con la familia?", "eng"));
    }

    @Test
    public void detectsItalianPhrases() {
        assertEquals("ita", LatinLanguageDetector.detect("Grazie mille per il tuo aiuto con questo lavoro.", "eng"));
    }

    @Test
    public void singleWordsReturnNull() {
        assertNull(LatinLanguageDetector.detect("Hotel", "eng"));
        assertNull(LatinLanguageDetector.detect("Danke", "eng"));
        assertNull(LatinLanguageDetector.detect("Merci", "eng"));
        assertNull(LatinLanguageDetector.detect("Hola", "eng"));
    }

    @Test
    public void englishPhrasesReturnNull() {
        assertNull(LatinLanguageDetector.detect("The quick brown fox jumps over the lazy dog.", "eng"));
        assertNull(LatinLanguageDetector.detect("Please click the button to continue.", "eng"));
    }

    // Written after the 2026-10-29 lists, never tuned on.
    private static final String[][] NEW_LANGUAGES = {
            {"nld", "Ik ben niet thuis, maar ik bel straks."},
            {"nld", "Dank je wel voor je hulp, het was fijn."},
            {"nld", "Waar is het station van de stad?"},
            {"pol", "Dziękuję bardzo za pomoc."},
            {"pol", "Nie wiem, gdzie jest dworzec."},
            {"pol", "Jestem w domu, ale nie mam czasu."},
            {"tur", "Çok teşekkür ederim, çok güzel."},
            {"tur", "Bu kitap benim için çok önemli."},
            {"tur", "Nasılsın, iyi misin?"},
            {"ind", "Terima kasih atas bantuan kamu."},
            {"ind", "Saya tidak tahu di mana stasiunnya."},
            {"ind", "Apa kabar, semua baik di sana?"},
    };

    @Test
    public void dutchPolishTurkishIndonesian() {
        for (String[] c : NEW_LANGUAGES) {
            assertEquals(c[1], c[0], LatinLanguageDetector.detect(c[1], "eng"));
        }
    }

    @Test
    public void englishWithTheirLookalikesStaysEnglish() {
        // Words left out on purpose, and ones the new lists do have.
        final String[] english = {
                "I met Ada at the ale house, she broke a heel.",
                "Var x is a variable, set it to zero.",
                "Dan and Ben went to the market at nine.",
                "The data is in the box, ok?",
                "Ale and wine are on the menu tonight.",
                "Bu Jin and Apa Sherpa climbed Everest."};
        for (String e : english) {
            assertNull(e, LatinLanguageDetector.detect(e, "eng"));
        }
        // The older five still win their own sentences.
        assertEquals("spa", LatinLanguageDetector.detect("Muchas gracias por todo, mi amigo.", "eng"));
        assertEquals("deu", LatinLanguageDetector.detect("Das ist ein sehr gutes Buch.", "eng"));
        assertEquals("fra", LatinLanguageDetector.detect("Merci beaucoup pour votre aide.", "eng"));
        assertEquals("ita", LatinLanguageDetector.detect("Grazie mille, sono molto contento.", "eng"));
        assertEquals("por", LatinLanguageDetector.detect("Muito obrigado, estou bem.", "eng"));
    }

    // Written after the list, never tuned on.
    @Test
    public void detectsKurmanji() {
        for (String k : new String[] {
                "Ez ji te pir hez dikim.",
                "Spas, ez baş im, tu çawa yî?",
                "Em ê sibê biçin bajêr û li wir bimînin.",
                "Zimanê kurdî gelek xweş e."}) {
            assertEquals(k, "kur", LatinLanguageDetector.detect(k, "eng"));
        }
        // Turkish shares ç and ş but keeps its own sentences.
        assertEquals("tur", LatinLanguageDetector.detect("Bu kitap benim için çok önemli.", "eng"));
        assertEquals("ita", LatinLanguageDetector.detect("Grazie mille, sono molto contento di te.", "eng"));
        final StringBuilder b = new StringBuilder();
        for (LanguageRuns.Run r : LanguageRuns.split("Thank you. Spas, ez baş im. See you soon.", "eng")) {
            b.append(r.language).append(' ');
        }
        assertEquals("eng kur eng", b.toString().trim());
    }

    @Test
    public void eachSentenceOfEnglishTextIsJudged() {
        // Judged whole, English and Spanish outweighed each other.
        final StringBuilder b = new StringBuilder();
        for (LanguageRuns.Run r : LanguageRuns.split("See you soon. Muchas gracias por todo, mi amigo. Bye now.",
                "eng")) {
            b.append(r.language).append(' ');
        }
        assertEquals("eng spa eng", b.toString().trim());
    }
}
