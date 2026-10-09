/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified, zero-allocation ASCII and character utilities.
 * Consolidates character classification, fast ASCII lowercasing,
 * language tag normalization, regex match rewriting and
 * singular/plural word selection across the TTS pipeline.
 */
public final class AsciiUtils {

    private AsciiUtils() {
    }

    public static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static boolean hasAsciiLetter(CharSequence text) {
        if (text == null) return false;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasDigit(CharSequence text) {
        if (text == null) return false;
        final int len = text.length();
        for (int i = 0; i < len; ) {
            final char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
            if (c > '9') {
                final int cp = Character.codePointAt(text, i);
                if (Character.isDigit(cp)) {
                    return true;
                }
                i += Character.charCount(cp);
            } else {
                i++;
            }
        }
        return false;
    }

    public static String toAsciiLowerCase(String s) {
        if (s == null) return null;
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < len; j++) {
                    char ch = chars[j];
                    if (ch >= 'A' && ch <= 'Z') {
                        chars[j] = (char) (ch + 32);
                    }
                }
                return new String(chars);
            }
        }
        return s;
    }

    public static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    /**
     * Extracts the primary base language code before '-' or '_'.
     * Maps Piper's "cmn" to "zh".
     */
    public static String baseLanguage(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return "";
        int sep = -1;
        for (int i = 0, n = languageTag.length(); i < n; i++) {
            char c = languageTag.charAt(i);
            if (c == '-' || c == '_') {
                sep = i;
                break;
            }
        }
        String base = sep > 0 ? toAsciiLowerCase(languageTag.substring(0, sep).trim())
                              : toAsciiLowerCase(languageTag.trim());
        if ("cmn".equals(base)) return "zh";
        return base;
    }

    /** Rewrites one regex match into its replacement text. */
    public interface MatchRewrite {
        String rewrite(Matcher matcher);
    }

    /**
     * Replaces every match of {@code pattern} in {@code text} with
     * {@code rewrite}'s output, returning {@code text} unchanged (same
     * instance) when nothing matches. The replacement is always quoted, so
     * match-derived text can never be misread as {@code $}-group syntax.
     */
    public static String replaceMatches(Pattern pattern, String text, MatchRewrite rewrite) {
        if (text == null) return null;
        final Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        final StringBuffer sb = new StringBuffer(text.length() + 32);
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(rewrite.rewrite(m)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Singular only for exactly "1" (digits as read, never parsed, so
     * "01" stays plural like the engine reads it).
     */
    public static String singularOrPlural(String digits, String singular, String plural) {
        return "1".equals(digits) ? singular : plural;
    }

    /** Singular only for a count of exactly 1. */
    public static String singularOrPlural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /**
     * Normalizes a language tag for NVDA locale file lookups:
     * lowercases and replaces '-' with '_', with zero allocation if already normalized.
     */
    public static String normalizeLocaleTag(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return "";
        final int len = languageTag.length();
        boolean needsConversion = false;
        for (int i = 0; i < len; i++) {
            char c = languageTag.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c == '-') {
                needsConversion = true;
                break;
            }
        }
        if (!needsConversion) return languageTag;
        char[] chars = languageTag.toCharArray();
        for (int i = 0; i < len; i++) {
            char c = chars[i];
            if (c >= 'A' && c <= 'Z') {
                chars[i] = (char) (c + 32);
            } else if (c == '-') {
                chars[i] = '_';
            }
        }
        return new String(chars);
    }
}
