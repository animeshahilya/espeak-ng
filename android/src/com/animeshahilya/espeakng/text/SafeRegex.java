/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

/**
 * User-dictionary regular expressions that cannot freeze speech.
 *
 * <p>Android's regex engine (ICU) backtracks and cannot be interrupted, so
 * one bad pattern stalls every utterance - "\w*\w*\w*\w*!" ran for minutes
 * on 3,000 characters - and a blind user cannot reach the settings to undo
 * it. Patterns run on RE2/J, which takes linear time whatever the pattern,
 * with \w \d \s widened to ICU's Unicode meaning so Indian scripts match as
 * before. What RE2/J cannot run (lookarounds, backreferences) or runs
 * differently (its \b is ASCII-only) stays on Android's engine, but only
 * when it is too simple to backtrack badly: one *, + or {n,m} at most, none
 * on a group, and up to three ?. Anything else is refused like an invalid
 * pattern.
 */
public final class SafeRegex {
    /** A compiled rule: every match replaced (Java replacement syntax: $1, ${name}, \$). */
    public interface Compiled {
        String replaceAll(String text, String replacement);
    }

    static final int MAX_PATTERN = 200;

    /** ICU's \w: letters, marks, decimal digits, connector punctuation, ZWNJ/ZWJ. */
    private static final String WORD = "\\p{L}\\p{M}\\p{Nd}\\p{Pc}\\x{200C}\\x{200D}";
    /** ICU's \s (White_Space). */
    private static final String SPACE = "\\s\\x{0B}\\x{85}\\p{Z}";

    private SafeRegex() {
    }

    /** @return null when the pattern is invalid, too long, or could backtrack badly */
    public static Compiled compile(String pattern, boolean caseInsensitive) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_PATTERN) {
            return null;
        }
        final String linear = toLinear(pattern);
        if (linear != null) {
            try {
                final com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(linear,
                        caseInsensitive ? com.google.re2j.Pattern.CASE_INSENSITIVE : 0);
                return (text, replacement) -> p.matcher(text).replaceAll(replacement);
            } catch (RuntimeException notRe2Syntax) {
                // lookaround or backreference: Android's engine, if simple enough
            }
        }
        if (!backtracksSafely(pattern)) {
            return null;
        }
        try {
            return of(java.util.regex.Pattern.compile(pattern, caseInsensitive
                    ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** A pattern the app built itself (quoted literal, fixed lookarounds): linear already. */
    public static Compiled of(java.util.regex.Pattern p) {
        return (text, replacement) -> p.matcher(text).replaceAll(replacement);
    }

    /**
     * The pattern in RE2 syntax with ICU's Unicode classes, or null when RE2/J
     * would read it differently: \b \B (ASCII-only there), \W \S inside [...].
     */
    static String toLinear(String p) {
        final StringBuilder out = new StringBuilder(p.length() + 64);
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            final char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                final char e = p.charAt(++i);
                if (e == 'Q') {
                    final int end = p.indexOf("\\E", i + 1);
                    final int stop = end < 0 ? p.length() : end + 2;
                    out.append(p, i - 1, stop);
                    i = stop - 1;
                    continue;
                }
                final String unicode = unicodeClass(e, inClass);
                if (unicode == null) {
                    return null;
                }
                out.append(unicode.isEmpty() ? p.substring(i - 1, i + 1) : unicode);
                continue;
            }
            out.append(c);
            if (!inClass && c == '[') {
                inClass = true;
                i = copyClassStart(p, i, out);
            } else if (inClass && c == ']') {
                inClass = false;
            }
        }
        return out.toString();
    }

    /** "" keeps the escape as written. */
    private static String unicodeClass(char e, boolean inClass) {
        switch (e) {
            case 'w': return inClass ? WORD : "[" + WORD + "]";
            case 'W': return inClass ? null : "[^" + WORD + "]";
            case 's': return inClass ? SPACE : "[" + SPACE + "]";
            case 'S': return inClass ? null : "[^" + SPACE + "]";
            case 'd': return "\\p{Nd}";
            case 'D': return "\\P{Nd}";
            case 'b':
            case 'B': return null;
            default: return "";
        }
    }

    /** After '[': a leading '^' and a ']' right after it are part of the class. */
    private static int copyClassStart(String p, int i, StringBuilder out) {
        if (i + 1 < p.length() && p.charAt(i + 1) == '^') {
            out.append(p.charAt(++i));
        }
        if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
            out.append(p.charAt(++i));
        }
        return i;
    }

    /**
     * True when a backtracking engine stays near-linear on it: one repeat
     * (*, +, {n,m}, {n,}) at most, none on a group, and three ? at most.
     */
    static boolean backtracksSafely(String p) {
        int repeats = 0;
        int optionals = 0;
        boolean afterGroup = false;
        boolean afterQuantifier = false;
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            final char c = p.charAt(i);
            if (c == '\\') {
                if (i + 1 < p.length() && p.charAt(i + 1) == 'Q') {
                    final int end = p.indexOf("\\E", i + 2);
                    i = end < 0 ? p.length() : end + 1;
                } else {
                    i++;
                }
                afterGroup = afterQuantifier = false;
                continue;
            }
            if (inClass) {
                inClass = c != ']';
                continue;
            }
            if (afterQuantifier && (c == '?' || c == '+')) {
                afterQuantifier = false; // lazy or possessive modifier
                continue;
            }
            int end = -1;
            if (c == '*' || c == '+' || (c == '{' && (end = repeatEnd(p, i)) > 0)) {
                if (afterGroup || ++repeats > 1) {
                    return false;
                }
                if (end > 0) {
                    i = end;
                }
                afterGroup = false;
                afterQuantifier = true;
                continue;
            }
            if (c == '{' && (end = exactEnd(p, i)) > 0) {
                i = end; // {n}: no choice to backtrack over
                afterGroup = false;
                afterQuantifier = true;
                continue;
            }
            if (c == '?' && !(i > 0 && p.charAt(i - 1) == '(')) {
                if (++optionals > 3) {
                    return false;
                }
                afterGroup = false;
                afterQuantifier = true;
                continue;
            }
            afterQuantifier = false;
            afterGroup = c == ')';
            if (c == '[') {
                inClass = true;
                i = copyClassStart(p, i, new StringBuilder());
            }
        }
        return true;
    }

    /** End of a {n,} or {n,m} (m > n) repeat at i, else -1. */
    private static int repeatEnd(String p, int i) {
        final java.util.regex.Matcher m = REPEAT.matcher(p).region(i, p.length());
        if (!m.lookingAt() || m.group(2) == null) {
            return -1;
        }
        final String max = m.group(3);
        return max.isEmpty() || Long.parseLong(max) > Long.parseLong(m.group(1)) ? m.end() - 1 : -1;
    }

    /** End of a {n} (or {n,n}) at i, else -1. */
    private static int exactEnd(String p, int i) {
        final java.util.regex.Matcher m = REPEAT.matcher(p).region(i, p.length());
        return m.lookingAt() ? m.end() - 1 : -1;
    }

    private static final java.util.regex.Pattern REPEAT =
            java.util.regex.Pattern.compile("\\{(\\d{1,6})(,(\\d{0,6}))?\\}");
}
