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
    /** Dictionary buckets ("My Words"): Main, Root, Abbreviation. */
    public static final String CATEGORY_MAIN = "main";
    public static final String CATEGORY_ROOT = "root";
    public static final String CATEGORY_ABBREV = "abbrev";

    private String mPattern;
    private String mReplacement;
    private boolean mCaseSensitive;
    private boolean mIsRegex;
    private boolean mWholeWord;
    private String mLanguage;
    private String mCategory;

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
        mPattern = pattern != null ? pattern : "";
        mReplacement = replacement != null ? replacement : "";
        mCaseSensitive = caseSensitive;
        mIsRegex = isRegex;
        mWholeWord = wholeWord;
        mLanguage = language != null ? language.trim().toLowerCase(java.util.Locale.ROOT) : "";
        mCategory = normalizeCategory(category);
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
    }

    public static String normalizeCategory(String category) {
        if (category == null) return CATEGORY_MAIN;
        String c = category.trim().toLowerCase(java.util.Locale.ROOT);
        if (CATEGORY_ROOT.equals(c)) return CATEGORY_ROOT;
        // "abbreviation" is accepted as a legacy alias of "abbrev".
        if (CATEGORY_ABBREV.equals(c) || "abbreviation".equals(c)) return CATEGORY_ABBREV;
        return CATEGORY_MAIN;
    }

    public static String categoryLabel(String category) {
        if (CATEGORY_ROOT.equals(category)) return "Root";
        if (CATEGORY_ABBREV.equals(category)) return "Abbrev";
        return "Main";
    }

    public void setLanguage(String language) {
        mLanguage = language != null ? language.trim().toLowerCase(java.util.Locale.ROOT) : "";
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

    private void compile() {
        if (mPattern.isEmpty()) {
            mCompiledPattern = null;
            return;
        }

        try {
            int flags = 0;
            if (!mCaseSensitive) {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }

            String regexStr;
            if (mIsRegex) {
                regexStr = mPattern;
            } else if (mWholeWord) {
                regexStr = "\\b" + Pattern.quote(mPattern) + "\\b";
            } else {
                regexStr = Pattern.quote(mPattern);
            }

            mCompiledPattern = Pattern.compile(regexStr, flags);
        } catch (PatternSyntaxException e) {
            mCompiledPattern = null;
        }
        mPreparedReplacement = mIsRegex
                ? mReplacement
                : java.util.regex.Matcher.quoteReplacement(mReplacement != null ? mReplacement : "");
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

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("pattern", mPattern);
        obj.put("replacement", mReplacement);
        obj.put("caseSensitive", mCaseSensitive);
        obj.put("isRegex", mIsRegex);
        obj.put("wholeWord", mWholeWord);
        obj.put("language", mLanguage);
        obj.put("category", mCategory);
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
        return new UserDictionary(pattern, replacement, caseSensitive, isRegex, wholeWord, language, category);
    }
}
