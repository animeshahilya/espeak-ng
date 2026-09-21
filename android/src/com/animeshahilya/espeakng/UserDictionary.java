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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents a single pronunciation replacement rule (matching NVDA speech dictionary semantics).
 */
public class UserDictionary {
    /** Dictionary buckets ("My Words"): Main, Root, Abbreviation, Character. */
    public static final String CATEGORY_MAIN = "main";
    public static final String CATEGORY_ROOT = "root";
    public static final String CATEGORY_ABBREV = "abbrev";
    /**
     * Matched only when the entire spoken utterance is exactly this rule's
     * pattern (spelling mode / character-by-character navigation, the same
     * signal TtsService.isSingleCharacterUtterance already detects) - never
     * as a substring or whole word inside running text. See
     * UserDictionaryManager#applyCharacterRule. Unlike the other three
     * categories, this one changes matching semantics, not just grouping:
     * a Main-category rule for "s" would corrupt every word containing an
     * s, but a Character-category rule for "s" only fires when "s" is
     * being read on its own - the exact gap the other categories can't
     * safely fill for single-character overrides (e.g. a screen reader
     * mispronouncing an individual letter or symbol).
     */
    public static final String CATEGORY_CHARACTER = "character";

    private String mPattern;
    private String mReplacement;
    private boolean mCaseSensitive;
    private boolean mIsRegex;
    private boolean mWholeWord;
    private String mLanguage;
    private String mCategory;
    private String mPhonemes;

    private Pattern mCompiledPattern = null;
    private String mPreparedReplacement = "";

    public UserDictionary(String pattern, String replacement, boolean caseSensitive, boolean isRegex, boolean wholeWord) {
        this(pattern, replacement, caseSensitive, isRegex, wholeWord, "");
    }

    /**
     * @param language BCP-47-ish language tag ("en", "en-in", "hi") the rule
     *                 applies to, or "" to apply to every language (the
     *                 default, and how rules saved before this field existed
     *                 are read back).
     */
    public UserDictionary(String pattern, String replacement, boolean caseSensitive, boolean isRegex, boolean wholeWord, String language) {
        this(pattern, replacement, caseSensitive, isRegex, wholeWord, language, CATEGORY_MAIN);
    }

    public UserDictionary(String pattern, String replacement, boolean caseSensitive, boolean isRegex, boolean wholeWord, String language, String category) {
        this(pattern, replacement, caseSensitive, isRegex, wholeWord, language, category, "");
    }

    /**
     * @param phonemes Optional espeak Kirshenbaum phoneme string (e.g.
     *                 {@code "k V n 'i:v @ l"}) that, when non-empty, is
     *                 spoken instead of respelling {@code replacement}
     *                 through the normal text pipeline - bypasses espeak's
     *                 own grapheme-to-phoneme conversion entirely for this
     *                 match. "" (the default, and how rules saved before
     *                 this field existed are read back) means no override:
     *                 fall back to plain {@code replacement} text.
     */
    public UserDictionary(String pattern, String replacement, boolean caseSensitive, boolean isRegex, boolean wholeWord, String language, String category, String phonemes) {
        mPattern = pattern != null ? pattern : "";
        mReplacement = replacement != null ? replacement : "";
        mCaseSensitive = caseSensitive;
        mIsRegex = isRegex;
        mWholeWord = wholeWord;
        mLanguage = language != null ? language.trim().toLowerCase(java.util.Locale.ROOT) : "";
        mCategory = normalizeCategory(category);
        mPhonemes = phonemes != null ? phonemes.trim() : "";
        compile();
    }

    public String getPattern() {
        return mPattern;
    }

    public void setPattern(String pattern) {
        mPattern = pattern != null ? pattern : "";
        compile();
    }

    public String getReplacement() {
        return mReplacement;
    }

    public void setReplacement(String replacement) {
        mReplacement = replacement != null ? replacement : "";
        compile();
    }

    public boolean isCaseSensitive() {
        return mCaseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        mCaseSensitive = caseSensitive;
        compile();
    }

    public boolean isRegex() {
        return mIsRegex;
    }

    public void setRegex(boolean regex) {
        mIsRegex = regex;
        compile();
    }

    public boolean isWholeWord() {
        return mWholeWord;
    }

    public void setWholeWord(boolean wholeWord) {
        mWholeWord = wholeWord;
        compile();
    }

    /** "" means the rule applies to every language. */
    public String getLanguage() {
        return mLanguage;
    }

    public String getCategory() {
        return mCategory;
    }

    public void setCategory(String category) {
        mCategory = normalizeCategory(category);
        // compile()'s wholeWord handling now depends on the category (see
        // there for why) - unused today (every category change in the app
        // goes through the constructor, building a fresh instance), but this
        // setter exists alongside the others that all recompile, and leaving
        // it stale would be a trap for whenever something does call it.
        compile();
    }

    public static String normalizeCategory(String category) {
        if (category == null) return CATEGORY_MAIN;
        String c = category.trim().toLowerCase(java.util.Locale.ROOT);
        if (CATEGORY_ROOT.equals(c)) return CATEGORY_ROOT;
        // "abbreviation" is accepted as a legacy alias of "abbrev".
        if (CATEGORY_ABBREV.equals(c) || "abbreviation".equals(c)) return CATEGORY_ABBREV;
        if (CATEGORY_CHARACTER.equals(c)) return CATEGORY_CHARACTER;
        return CATEGORY_MAIN;
    }

    public static String categoryLabel(String category) {
        if (CATEGORY_ROOT.equals(category)) return "Root";
        if (CATEGORY_ABBREV.equals(category)) return "Abbrev";
        if (CATEGORY_CHARACTER.equals(category)) return "Character";
        return "Main";
    }

    public void setLanguage(String language) {
        mLanguage = language != null ? language.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** "" means this rule has no phoneme override and speaks {@link #getReplacement()} normally. */
    public String getPhonemes() {
        return mPhonemes;
    }

    public void setPhonemes(String phonemes) {
        mPhonemes = phonemes != null ? phonemes.trim() : "";
        compile();
    }

    public boolean hasPhonemeOverride() {
        return isPhonemeStringUsable(mPhonemes);
    }

    /**
     * "]]" inside the phoneme string would end phoneme mode early and leak
     * the rest of it into ordinary text parsing - reject rather than silently
     * truncate or mis-speak. "[[" is harmless (a literal second phoneme-mode
     * entry nested inside one that's already open is a no-op to espeak) but
     * excluded too, since a user pasting one clearly didn't mean to include it.
     */
    private static boolean isPhonemeStringUsable(String phonemes) {
        return phonemes != null && !phonemes.isEmpty()
                && !phonemes.contains("]]") && !phonemes.contains("[[");
    }

    /**
     * Whether this rule should apply when synthesizing in requestLanguage
     * (e.g. "en-in", "hi"). A rule scoped to a base language ("en") also
     * matches its variants ("en-in", "en-us"); an unscoped rule ("")
     * matches every language.
     */
    public boolean appliesToLanguage(String requestLanguage) {
        if (mLanguage.isEmpty()) return true;
        if (requestLanguage == null) return false;
        String request = requestLanguage.trim().toLowerCase(java.util.Locale.ROOT);
        return request.equals(mLanguage) || request.startsWith(mLanguage + "-");
    }

    /**
     * True when this rule has a valid, compiled pattern ready to match.
     * False if the pattern was empty, exceeded limits, failed regex syntax,
     * or triggered catastrophic backtracking guards.
     */
    public boolean isValid() {
        return mCompiledPattern != null;
    }

    private void compile() {
        if (mPattern.isEmpty()) {
            mCompiledPattern = null;
            return;
        }

        // Reject patterns that could cause catastrophic backtracking (ReDoS).
        // Nested quantifiers like (a+)+ or (a*)* cause exponential backtracking.
        // Also enforce a reasonable length limit.
        if (mIsRegex) {
            if (mPattern.length() > 200) {
                mCompiledPattern = null;
                return;
            }
            if (hasNestedQuantifier(mPattern)) {
                mCompiledPattern = null;
                return;
            }
        }

        try {
            // Plain \b only recognizes ASCII [a-zA-Z0-9_] as "word" characters -
            // Devanagari, Gujarati, or any other non-Latin script text never
            // triggers a boundary at all, so a wholeWord rule for a Hindi/
            // Gujarati/etc. word would silently never match anywhere in the
            // text. java.util.regex.Pattern.UNICODE_CHARACTER_CLASS fixes that
            // on a desktop JVM, but Android's regex engine (ICU-backed, not
            // OpenJDK's) throws IllegalArgumentException("UNICODE_CHARACTER_CLASS
            // flag not supported") for it unconditionally at runtime - it compiles
            // fine, so javac/java-based verification during development couldn't
            // have caught it, but it crashed every synthesis request on-device.
            // \p{L}/\p{N} Unicode property escapes below need no such flag on
            // either engine, so they're used directly instead of \b/\w.
            int flags = 0;
            if (!mCaseSensitive) {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }

            String regexStr;
            if (mIsRegex) {
                regexStr = mPattern;
            } else if (mWholeWord && !CATEGORY_CHARACTER.equals(mCategory)) {
                // \b is only ever exercised through applyRules() (continuous-text
                // substring matching), which already excludes CATEGORY_CHARACTER
                // rules entirely - they're only reached via
                // UserDictionaryManager#applyCharacterRule, which confirms an
                // exact whole-string match itself before calling apply() here.
                // \b around a symbol/punctuation pattern (no \w character
                // anywhere, e.g. "#") never finds a boundary to match at all, so
                // honoring wholeWord for this category would silently turn every
                // symbol-only Character rule into a no-op - the checkbox default
                // (true) has no safe meaning for this category, so it's ignored.
                regexStr = "(?<![\\p{L}\\p{N}])" + Pattern.quote(mPattern) + "(?![\\p{L}\\p{N}])";
            } else {
                regexStr = Pattern.quote(mPattern);
            }

            mCompiledPattern = Pattern.compile(regexStr, flags);
        } catch (PatternSyntaxException e) {
            mCompiledPattern = null;
        }
        if (isPhonemeStringUsable(mPhonemes)) {
            // Always literal/quoted, even for a regex pattern: unlike plain-text
            // replacement, a phoneme override is one fixed pronunciation for
            // whatever matched, not a template with regex backreferences.
            mPreparedReplacement = "[[" + java.util.regex.Matcher.quoteReplacement(mPhonemes) + "]]";
        } else {
            mPreparedReplacement = mIsRegex
                    ? mReplacement
                    : java.util.regex.Matcher.quoteReplacement(mReplacement != null ? mReplacement : "");
        }
    }

    public String apply(String text) {
        if (text == null || text.isEmpty() || mCompiledPattern == null) {
            return text;
        }
        // Fast-path bypass for literal (non-regex) rules: avoid matcher allocation
        // and regex engine state machine if text cannot possibly match.
        if (!mIsRegex) {
            if (text.length() < mPattern.length()) {
                return text;
            }
            if (mCaseSensitive) {
                if (!text.contains(mPattern)) {
                    return text;
                }
            } else if (!containsIgnoreCase(text, mPattern)) {
                return text;
            }
        }
        try {
            return mCompiledPattern.matcher(text).replaceAll(mPreparedReplacement);
        } catch (Throwable t) {
            return text;
        }
    }

    private static boolean containsIgnoreCase(String source, String target) {
        final int targetLen = target.length();
        if (targetLen == 0) return true;
        final int max = source.length() - targetLen;
        for (int i = 0; i <= max; i++) {
            if (source.regionMatches(true, i, target, 0, targetLen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isQuantifierChar(char c) {
        return c == '*' || c == '+' || c == '?' || c == '{';
    }

    /**
     * Detects nested quantifiers that cause catastrophic backtracking.
     * Looks for patterns like (a+)+, (a*)*, (a?)*, ((a)+)+, etc.
     * Accurately distinguishes between a quantified group like (\d+) or ([a-z]+)
     * (valid, safe) and a quantified group that is ITSELF quantified like (\d+)+ (unsafe).
     */
    private static boolean hasNestedQuantifier(String pattern) {
        int depth = 0;
        boolean inCharClass = false;
        boolean[] groupHasQuantifier = new boolean[64];

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                i++; // skip escaped char
                continue;
            }
            if (c == '[') {
                inCharClass = true;
            } else if (c == ']' && inCharClass) {
                inCharClass = false;
            } else if (!inCharClass) {
                if (c == '(') {
                    depth++;
                    if (depth < groupHasQuantifier.length) {
                        groupHasQuantifier[depth] = false;
                    }
                } else if (c == ')') {
                    boolean innerQuantified = (depth < groupHasQuantifier.length) && groupHasQuantifier[depth];
                    if (depth > 0) {
                        depth--;
                    }
                    if (i + 1 < pattern.length() && isQuantifierChar(pattern.charAt(i + 1))) {
                        if (innerQuantified) {
                            return true;
                        }
                        if (depth > 0 && depth < groupHasQuantifier.length) {
                            groupHasQuantifier[depth] = true;
                        }
                    } else if (innerQuantified && depth > 0 && depth < groupHasQuantifier.length) {
                        groupHasQuantifier[depth] = true;
                    }
                } else if (isQuantifierChar(c)) {
                    if (depth > 0 && depth < groupHasQuantifier.length) {
                        groupHasQuantifier[depth] = true;
                    }
                }
            }
        }
        return false;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("pattern", mPattern);
        obj.put("replacement", mReplacement);
        obj.put("caseSensitive", mCaseSensitive);
        obj.put("isRegex", mIsRegex);
        obj.put("wholeWord", mWholeWord);
        obj.put("language", mLanguage);
        obj.put("category", mCategory);
        // Omitted entirely rather than written as "" when unused, so a dictionary
        // exported before this field existed round-trips byte-for-byte identical.
        if (!mPhonemes.isEmpty()) {
            obj.put("phonemes", mPhonemes);
        }
        return obj;
    }

    public static UserDictionary fromJson(JSONObject obj) {
        if (obj == null) return null;
        String pattern = obj.optString("pattern", "");
        String replacement = obj.optString("replacement", "");
        boolean caseSensitive = obj.optBoolean("caseSensitive", false);
        boolean isRegex = obj.optBoolean("isRegex", false);
        boolean wholeWord = obj.optBoolean("wholeWord", true);
        String language = obj.optString("language", "");
        String category = obj.optString("category", CATEGORY_MAIN);
        String phonemes = obj.optString("phonemes", "");
        return new UserDictionary(pattern, replacement, caseSensitive, isRegex, wholeWord, language, category, phonemes);
    }
}
