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
    private String mPattern;
    private String mReplacement;
    private boolean mCaseSensitive;
    private boolean mIsRegex;
    private boolean mWholeWord;

    private Pattern mCompiledPattern = null;

    public UserDictionary(String pattern, String replacement, boolean caseSensitive, boolean isRegex, boolean wholeWord) {
        mPattern = pattern != null ? pattern : "";
        mReplacement = replacement != null ? replacement : "";
        mCaseSensitive = caseSensitive;
        mIsRegex = isRegex;
        mWholeWord = wholeWord;
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
    }

    public String apply(String text) {
        if (text == null || text.isEmpty() || mCompiledPattern == null) {
            return text;
        }
        try {
            return mCompiledPattern.matcher(text).replaceAll(mReplacement);
        } catch (Throwable t) {
            return text;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("pattern", mPattern);
        obj.put("replacement", mReplacement);
        obj.put("caseSensitive", mCaseSensitive);
        obj.put("isRegex", mIsRegex);
        obj.put("wholeWord", mWholeWord);
        return obj;
    }

    public static UserDictionary fromJson(JSONObject obj) {
        if (obj == null) return null;
        String pattern = obj.optString("pattern", "");
        String replacement = obj.optString("replacement", "");
        boolean caseSensitive = obj.optBoolean("caseSensitive", false);
        boolean isRegex = obj.optBoolean("isRegex", false);
        boolean wholeWord = obj.optBoolean("wholeWord", true);
        return new UserDictionary(pattern, replacement, caseSensitive, isRegex, wholeWord);
    }
}
