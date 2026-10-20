/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance URL and email isolation for multilingual speech.
 *
 * <p>Protects web links, domains, and email addresses from being fragmented
 * by script-based language segmentation (e.g. slashes, dots, and numbers in
 * URLs being incorrectly parsed as separate punctuation or number runs).
 */
public final class SpeechLinks {
    private SpeechLinks() {
    }

    /**
     * Matches http/https links, www domains, and email addresses without
     * capturing trailing sentence-ending punctuation or unmatched brackets.
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_@./-])(?:https?://|www\\.)[^\\s\\p{Z}<>\"'«»“”‘’]+"
            + "|(?<![\\p{L}\\p{N}_@./-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[a-z]{2,}\\b");

    /** A matched character span representing a link or email. */
    public static final class Range {
        public final int start;
        public final int end;

        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return "[" + start + ", " + end + "]";
        }
    }

    /**
     * Finds all web links, URLs, and email addresses in {@code text}.
     * Returns an empty list if no link markers exist, avoiding allocations.
     */
    public static List<Range> find(String text) {
        if (text == null || text.length() < 5) {
            return Collections.emptyList();
        }
        // Fast-path rejection check: avoids regex execution for text without link markers
        if (text.indexOf("://") < 0 && text.indexOf("www.") < 0 && text.indexOf('@') < 0) {
            return Collections.emptyList();
        }

        List<Range> ranges = null;
        final Matcher m = LINK_PATTERN.matcher(text);
        while (m.find()) {
            final int start = m.start();
            final int rawEnd = m.end();
            final int trimmedEnd = trimEnd(text, start, rawEnd);
            if (trimmedEnd > start) {
                if (ranges == null) {
                    ranges = new ArrayList<>(2);
                }
                ranges.add(new Range(start, trimmedEnd));
            }
        }
        return ranges != null ? ranges : Collections.emptyList();
    }

    /**
     * Checks if the character index {@code index} falls within any link range in {@code ranges}.
     * Assumes {@code ranges} is ordered by start position.
     */
    public static boolean isInLink(List<Range> ranges, int index) {
        if (ranges == null || ranges.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ranges.size(); i++) {
            final Range r = ranges.get(i);
            if (index >= r.start && index < r.end) {
                return true;
            }
            if (index < r.start) {
                break;
            }
        }
        return false;
    }

    /**
     * Trims sentence-ending punctuation and balances closing parentheses/brackets
     * that might have been greedily captured at the end of a URL in running prose.
     * Example: "(see https://example.com/page)." -> trims trailing ")."
     */
    private static int trimEnd(String text, int start, int end) {
        int parens = 0;
        int brackets = 0;
        int braces = 0;

        for (int i = start; i < end; i++) {
            final char c = text.charAt(i);
            if (c == '(') parens++;
            else if (c == ')') parens--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
            else if (c == '{') braces++;
            else if (c == '}') braces--;
        }

        while (end > start) {
            final char c = text.charAt(end - 1);
            if (".,;:!?…".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && parens < 0) {
                end--;
                parens++;
            } else if (c == ']' && brackets < 0) {
                end--;
                brackets++;
            } else if (c == '}' && braces < 0) {
                end--;
                braces++;
            } else {
                break;
            }
        }
        return end;
    }
}
