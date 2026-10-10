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

package com.animeshahilya.espeakng.ui;

import com.animeshahilya.espeakng.Voice;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in "what was just read" store: the most recent utterances kept on this
 * device so the user can re-hear them from the settings screen.
 *
 * <p>Privacy by construction, because a TTS engine routinely sees sensitive
 * text (one-time codes, messages, passwords read aloud):
 * <ul>
 * <li>off by default ({@link VoiceSettings#PREF_READING_HISTORY});
 * <li>only utterances of 20+ characters are kept, and only their first 48
 *     characters - short secrets (OTPs, PINs) never qualify;
 * <li>digit/punctuation-only text is skipped outright;
 * <li>SSML markup is never stored;
 * <li>everything lives in device-protected storage, same as the settings.
 * </ul>
 *
 * <p>Stored under an internal key (no settings-screen row reads it), as a
 * JSON array of {text, voice, at} objects, newest first, capped at 20.
 */
public final class ReadingHistory {
    private static final String TAG = "ReadingHistory";

    /** Internal key; no UI row. */
    public static final String PREF_HISTORY_ITEMS = "espeak_reading_history_items";

    public static final int MAX_ITEMS = 20;
    public static final int MIN_CHARS = 20;
    public static final int SNIPPET_CHARS = 48;

    private ReadingHistory() {
    }

    public static final class Entry {
        public final String text;
        public final String voice;
        public final long at;

        Entry(String text, String voice, long at) {
            this.text = text;
            this.voice = voice;
            this.at = at;
        }
    }

    /**
     * Records one utterance if history is enabled and the text qualifies.
     * Never throws: called on the synthesis path, where storage failures
     * must not disturb speech.
     */
    public static void record(SharedPreferences prefs, String text, String voiceName) {
        try {
            if (prefs == null || !prefs.getBoolean(VoiceSettings.PREF_READING_HISTORY, false)) {
                return;
            }
            if (text == null) return;
            String trimmed = text.trim();
            if (trimmed.codePointCount(0, trimmed.length()) < MIN_CHARS) return;
            if (looksLikeCode(trimmed)) return;
            int end = trimmed.offsetByCodePoints(0,
                    Math.min(SNIPPET_CHARS, trimmed.codePointCount(0, trimmed.length())));
            String snippet = trimmed.substring(0, end);
            List<Entry> items = get(prefs);
            items.add(0, new Entry(snippet, voiceName != null ? voiceName : "",
                    System.currentTimeMillis()));
            while (items.size() > MAX_ITEMS) {
                items.remove(items.size() - 1);
            }
            prefs.edit().putString(PREF_HISTORY_ITEMS, toJson(items)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "History record failed", t);
        }
    }

    /** True for digit/punctuation-only text (codes, amounts): never stored. */
    static boolean looksLikeCode(String text) {
        boolean hasLetter = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                hasLetter = true;
                break;
            }
            i += Character.charCount(cp);
        }
        return !hasLetter;
    }

    public static List<Entry> get(SharedPreferences prefs) {
        List<Entry> items = new ArrayList<Entry>();
        if (prefs == null) return items;
        String raw = prefs.getString(PREF_HISTORY_ITEMS, null);
        if (raw == null || raw.isEmpty()) return items;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS; i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String text = obj.optString("text", "");
                if (text.isEmpty()) continue;
                items.add(new Entry(text, obj.optString("voice", ""), obj.optLong("at", 0)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "History parse failed", t);
        }
        return items;
    }

    public static void clear(SharedPreferences prefs) {
        if (prefs == null) return;
        prefs.edit().remove(PREF_HISTORY_ITEMS).apply();
    }

    private static String toJson(List<Entry> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Entry e : items) {
            JSONObject obj = new JSONObject();
            obj.put("text", e.text);
            obj.put("voice", e.voice);
            obj.put("at", e.at);
            arr.put(obj);
        }
        return arr.toString();
    }
}
