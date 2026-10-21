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

import android.content.SharedPreferences;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Recent speech: the last few things other apps had this engine say, to hear
 * again slowly, spelled out, or to copy (a code, a name, an address TalkBack
 * read once). NVDA users ask for its speech viewer for the same reason.
 *
 * <p>It keeps short text too (codes, names) - that is its point - so it is
 * held tightly: only while the Quick Settings tile is added
 * ({@link #PREF_ON}, set by {@link RecentSpeechTile}), in memory only (gone
 * with the process, never written or backed up), the last
 * {@link #MAX_ITEMS} for {@link #KEEP_MS}, and shown only after unlock.
 */
public final class RecentSpeech {
    /** True while the tile is added; device-local, no settings row. */
    public static final String PREF_ON = "espeak_recent_speech_tile";
    static final int MAX_ITEMS = 15;
    static final int MAX_CHARS = 2000;
    static final long KEEP_MS = 5 * 60_000L;
    /** What was said this soon before the panel opened still counts as before it. */
    private static final long PANEL_GRACE_MS = 1500;

    private static final ArrayDeque<Item> sItems = new ArrayDeque<>();
    private static volatile long sPanelOpenedAt;

    private RecentSpeech() {
    }

    public static final class Item {
        public final String text;
        public final long at;

        Item(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    /** Keeps one request's text while the tile is added. Never throws (synthesis path). */
    public static void record(SharedPreferences prefs, String text) {
        try {
            if (text == null || !prefs.getBoolean(PREF_ON, false) || text.startsWith("<speak")) {
                return;
            }
            add(text, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // speech goes on
        }
    }

    static void add(String text, long now) {
        String t = text.trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.length() > MAX_CHARS) {
            int end = MAX_CHARS;
            if (Character.isHighSurrogate(t.charAt(end - 1))) {
                end--;
            }
            t = t.substring(0, end);
        }
        final String kept = t;
        synchronized (sItems) {
            // A screen reader repeats itself (focus, then the same text on
            // a change): one entry, moved to the top.
            sItems.removeIf(item -> item.text.equals(kept));
            sItems.addFirst(new Item(kept, now));
            while (sItems.size() > MAX_ITEMS) {
                sItems.removeLast();
            }
        }
    }

    /**
     * Newest first, without anything older than {@link #KEEP_MS}. When the
     * Quick Settings panel opened within that time, what the screen reader
     * said about the panel itself (after it opened) is left out.
     */
    public static List<Item> items(long now) {
        final long panel = sPanelOpenedAt;
        if (panel > 0 && now - panel < KEEP_MS) {
            final List<Item> before = collect(now, panel + PANEL_GRACE_MS);
            if (!before.isEmpty()) {
                return before;
            }
        }
        return collect(now, Long.MAX_VALUE);
    }

    /** Items newer than {@link #KEEP_MS} and said before {@code before}, newest first. */
    private static List<Item> collect(long now, long before) {
        final List<Item> out = new ArrayList<>();
        synchronized (sItems) {
            for (Item item : sItems) {
                if (now - item.at > KEEP_MS) {
                    break; // newest first: the rest are older still
                }
                if (item.at < before) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    static void markPanelOpened(long now) {
        sPanelOpenedAt = now;
    }

    /** The tile was added or removed; removing it forgets everything. */
    static void setOn(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).apply();
        if (!on) {
            clear();
        }
    }

    public static void clear() {
        synchronized (sItems) {
            sItems.clear();
        }
        sPanelOpenedAt = 0;
    }
}
