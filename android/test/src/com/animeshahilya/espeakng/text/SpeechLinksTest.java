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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SpeechLinksTest {

    @Test
    public void findsStandardUrls() {
        final String text = "Check out https://github.com/animeshahilya/espeak-ng for details.";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertEquals(1, links.size());
        assertEquals(10, links.get(0).start);
        assertEquals("https://github.com/animeshahilya/espeak-ng", text.substring(links.get(0).start, links.get(0).end));
    }

    @Test
    public void trimsTrailingPunctuationAndParentheses() {
        final String text = "Visit our site (https://example.com/download), please!";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertEquals(1, links.size());
        assertEquals("https://example.com/download", text.substring(links.get(0).start, links.get(0).end));
    }

    @Test
    public void findsWwwLinks() {
        final String text = "Go to www.google.com for searching.";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertEquals(1, links.size());
        assertEquals("www.google.com", text.substring(links.get(0).start, links.get(0).end));
    }

    @Test
    public void findsEmailAddresses() {
        final String text = "Contact alex@example.com anytime.";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertEquals(1, links.size());
        assertEquals("alex@example.com", text.substring(links.get(0).start, links.get(0).end));
    }

    @Test
    public void noLinksReturnsEmptyList() {
        final String text = "नमस्ते दोस्त, यह एक सामान्य पाठ है।";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertTrue(links.isEmpty());
        assertFalse(SpeechLinks.isInLink(links, 5));
        assertEquals(-1, SpeechLinks.linkEndAt(links, 5));
    }

    @Test
    public void multipleLinksIdentified() {
        final String text = "Both https://a.com and http://b.org/path are online.";
        final List<SpeechLinks.Range> links = SpeechLinks.find(text);
        assertEquals(2, links.size());
        assertEquals("https://a.com", text.substring(links.get(0).start, links.get(0).end));
        assertEquals("http://b.org/path", text.substring(links.get(1).start, links.get(1).end));
    }
}
