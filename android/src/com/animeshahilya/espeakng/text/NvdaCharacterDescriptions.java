/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;
import com.animeshahilya.espeakng.Voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NVDA's characterDescriptions.dic: words that tell a character apart
 * ("b" -> "Bravo", German "b" -> "Berlin", Russian "б" -> "Борис", and for
 * Chinese, Japanese and Korean example words for thousands of characters).
 * Looked up like NVDA's getCharacterDescription: the voice's locale, its
 * base language, then English; the character lower-cased. Files come from
 * assets/chardesc/ (tools/update_nvda_symbols.py); without them (JVM
 * tests) nothing is found.
 */
public final class NvdaCharacterDescriptions {
    private NvdaCharacterDescriptions() {
    }

    private static final Map<String, String[]> MISSING = Collections.emptyMap();
    private static final Map<String, Map<String, String[]>> FILES =
            new ConcurrentHashMap<String, Map<String, String[]>>();
    private static volatile NvdaSymbolProcessor.DataSource sData;

    public static void setDataSource(NvdaSymbolProcessor.DataSource data) {
        sData = data;
        FILES.clear();
    }

    private static Map<String, String[]> file(String locale) {
        Map<String, String[]> entries = FILES.get(locale);
        if (entries != null) {
            return entries;
        }
        final NvdaSymbolProcessor.DataSource data = sData;
        entries = MISSING;
        if (data != null) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    data.open(locale + ".dic"), StandardCharsets.UTF_8))) {
                entries = new HashMap<String, String[]>();
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                    if (line.trim().isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    final int tab = line.indexOf('\t');
                    if (tab > 0) {
                        entries.put(line.substring(0, tab), line.substring(tab + 1).split("\t"));
                    }
                }
            } catch (IOException e) {
                entries = MISSING;
            }
        }
        FILES.put(locale, entries);
        return entries;
    }

    /** The descriptions for one character, or null when none is known. */
    static String[] get(String languageTag, String character) {
        if (character == null || character.isEmpty()) {
            return null;
        }
        final String key = character.toLowerCase(Locale.ROOT);
        for (String locale : NvdaSymbolProcessor.localeCandidates(languageTag)) {
            final Map<String, String[]> entries = file(locale);
            if (entries != MISSING) {
                // NVDA uses the first locale that has a file, then English.
                final String[] found = entries.get(key);
                if (found != null) {
                    return found;
                }
                break;
            }
        }
        return file("en").get(key);
    }
}


