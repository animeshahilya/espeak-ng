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

public class KeyboardLanguageTest {

    @Test
    public void normalizesLocaleTags() {
        assertEquals("hin", KeyboardLanguage.normalizeToIso3("hi"));
        assertEquals("hin", KeyboardLanguage.normalizeToIso3("hi-IN"));
        assertEquals("hin", KeyboardLanguage.normalizeToIso3("hi_IN"));
        assertEquals("eng", KeyboardLanguage.normalizeToIso3("en-US"));
        assertEquals("rus", KeyboardLanguage.normalizeToIso3("ru-RU"));
        assertEquals("ukr", KeyboardLanguage.normalizeToIso3("uk-UA"));
        assertEquals("tam", KeyboardLanguage.normalizeToIso3("ta-IN"));
        assertEquals("spa", KeyboardLanguage.normalizeToIso3("es-ES"));
        assertEquals("fra", KeyboardLanguage.normalizeToIso3("fr-FR"));
        assertEquals("deu", KeyboardLanguage.normalizeToIso3("de-DE"));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertNull(KeyboardLanguage.normalizeToIso3(null));
        assertNull(KeyboardLanguage.normalizeToIso3(""));
    }
}
