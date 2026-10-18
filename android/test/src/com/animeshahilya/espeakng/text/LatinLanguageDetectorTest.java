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

import org.junit.Test;

public class LatinLanguageDetectorTest {

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
}
