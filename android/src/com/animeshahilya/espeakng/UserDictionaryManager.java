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

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UserDictionaryManager {
    private static final String TAG = "UserDictionaryManager";
    private static final String FILE_NAME = "user_dictionary.json";

    private static UserDictionaryManager sInstance;
    private final File mFilesDir;
    private final List<UserDictionary> mRules = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, List<UserDictionary>> mLanguageCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Cached getRulesByCategory() partitions: applyCharacterRule() runs on
    // every single-character utterance (TalkBack character navigation - the
    // highest-frequency synthesis type) and used to rescan all rules and
    // allocate two new lists per keystroke-read.
    private final java.util.concurrent.ConcurrentHashMap<String, List<UserDictionary>> mCategoryCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /**
     * Bumped by every edit. A cache entry is stored only if no edit came
     * while it was being built: otherwise an edit landing between the scan
     * and the put left a stale list cached (a restored dictionary that never
     * applied until the next edit).
     */
    private final java.util.concurrent.atomic.AtomicInteger mGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    private void invalidateCache() {
        mGeneration.incrementAndGet();
        mLanguageCache.clear();
        mCategoryCache.clear();
    }

    private UserDictionaryManager(Context context) {
        Context storage = EspeakApp.requireStorageContext(context.getApplicationContext());
        mFilesDir = storage.getFilesDir();
        migrateLegacyIfPresent(context.getApplicationContext());
        load();
    }

    private void migrateLegacyIfPresent(Context context) {
        try {
            File deFile = new File(mFilesDir, FILE_NAME);
            if (!deFile.exists()) {
                Context plainContext = context.getApplicationContext();
                File ceFile = new File(plainContext.getFilesDir(), FILE_NAME);
                if (ceFile.exists() && ceFile.canRead()) {
                    if (!ceFile.renameTo(deFile)) {
                        FileUtils.write(deFile, FileUtils.read(ceFile));
                        ceFile.delete();
                    }
                    Log.i(TAG, "Migrated user dictionary to device-protected storage");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not check/migrate legacy user dictionary", t);
        }
    }

    public static synchronized UserDictionaryManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UserDictionaryManager(context);
        }
        return sInstance;
    }

    public List<UserDictionary> getRules() {
        return Collections.unmodifiableList(new ArrayList<>(mRules));
    }

    public void addRule(UserDictionary rule) {
        if (rule != null) {
            mRules.add(rule);
            invalidateCache();
            save();
        }
    }

    public void setRule(int index, UserDictionary rule) {
        if (index >= 0 && index < mRules.size() && rule != null) {
            mRules.set(index, rule);
            invalidateCache();
            save();
        }
    }

    public void removeRule(int index) {
        if (index >= 0 && index < mRules.size()) {
            mRules.remove(index);
            invalidateCache();
            save();
        }
    }

    public void clearRules() {
        mRules.clear();
        invalidateCache();
        save();
    }

    /**
     * Lock-free rule application for high-frequency TTS synthesis pipeline.
     * Skips CATEGORY_CHARACTER rules - those are exact-whole-utterance-only
     * (see {@link #applyCharacterRule}) and would otherwise fire as an
     * ordinary substring/whole-word match here too, silently corrupting any
     * running text that happens to contain the character (e.g. a
     * Character-category rule fixing how "s" is spoken in isolation would
     * also rewrite every "s" inside every other word).
     * Caches partitioned rules per language tag to avoid full-list scanning on every call.
     *
     * @param language the language being synthesized (e.g. "en-in", "hi");
     *                 only rules unscoped or scoped to this language (or its
     *                 base language) are applied.
     */
    public String applyRules(String text, String language) {
        if (text == null || text.isEmpty() || mRules.isEmpty()) {
            return text;
        }
        final String langKey = language != null ? AsciiUtils.toAsciiLowerCase(language.trim()) : "";
        List<UserDictionary> matching = mLanguageCache.get(langKey);
        if (matching == null) {
            final int generation = mGeneration.get();
            final List<UserDictionary> filtered = new ArrayList<>();
            for (UserDictionary rule : mRules) {
                if (!UserDictionary.CATEGORY_CHARACTER.equals(rule.getCategory()) && rule.appliesToLanguage(langKey)) {
                    filtered.add(rule);
                }
            }
            matching = filtered;
            if (generation == mGeneration.get()) {
                mLanguageCache.put(langKey, matching);
            }
        }
        if (matching.isEmpty()) {
            return text;
        }
        String result = text;
        for (UserDictionary rule : matching) {
            result = rule.apply(result);
        }
        return result;
    }

    /**
     * Looks up a CATEGORY_CHARACTER rule whose pattern is exactly {@code text}
     * (not a substring or word-boundary match) for TtsService's single-
     * character-utterance path (spelling mode / TalkBack character-by-
     * character navigation - see isSingleCharacterUtterance). Returns the
     * rule's spoken form (respecting a phoneme override, same as any other
     * rule) via {@link UserDictionary#apply}, or {@code text} unchanged if no
     * Character rule matches. Matching is always literal string equality
     * here regardless of the rule's own isRegex() flag - a "match the exact
     * character being read" rule has no use for regex syntax, so a Character
     * rule with isRegex() set simply won't match via this exact-equality
     * check (its pattern string has to equal the character literally).
     */
    public String applyCharacterRule(String text, String language) {
        if (text == null || text.isEmpty() || mRules.isEmpty()) {
            return text;
        }
        // Trimmed, matching TtsService.isSingleCharacterUtterance's own check
        // (text.trim().length() == 1) and expandNatoSpelling/
        // expandDevanagariDiacritic's existing behavior for this same
        // single-character path - surrounding whitespace is incidental to
        // the character being spoken, not part of what a rule should match.
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return text;
        }
        for (UserDictionary rule : getRulesByCategory(UserDictionary.CATEGORY_CHARACTER)) {
            if (rule.appliesToLanguage(language) && patternEqualsText(rule, trimmed)) {
                return rule.apply(trimmed);
            }
        }
        return text;
    }

    private static boolean patternEqualsText(UserDictionary rule, String text) {
        String pattern = rule.getPattern();
        return rule.isCaseSensitive() ? pattern.equals(text) : pattern.equalsIgnoreCase(text);
    }


    public synchronized void load() {
        mRules.clear();
        invalidateCache();
        File file = new File(mFilesDir, FILE_NAME);
        if (!file.exists()) {
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(reader)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                UserDictionary rule = UserDictionary.fromJson(obj);
                if (rule != null && rule.isValid()) {
                    mRules.add(rule);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load user dictionary", e);
        }
    }

    /**
     * Asynchronously and atomically persists user dictionary rules without blocking
     * the UI or speech synthesis threads.
     */
    public void save() {
        final List<UserDictionary> snapshot = new ArrayList<>(mRules);
        mExecutor.execute(() -> {
            saveAtomic(snapshot);
        });
    }

    private synchronized void saveAtomic(List<UserDictionary> rules) {
        File file = new File(mFilesDir, FILE_NAME);
        File tempFile = new File(mFilesDir, FILE_NAME + ".tmp");
        boolean writeSucceeded = false;
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONArray();
            for (UserDictionary rule : rules) {
                arr.put(rule.toJson());
            }
            writer.write(arr.toString(2));
            writer.flush();
            fos.getFD().sync();
            writeSucceeded = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save user dictionary", e);
        }

        if (writeSucceeded) {
            if (!tempFile.renameTo(file)) {
                if (file.delete() && tempFile.renameTo(file)) {
                    // Succeeded on delete + rename
                } else {
                    Log.w(TAG, "Could not atomic rename temp user dictionary");
                    tempFile.delete();
                }
            }
        } else {
            tempFile.delete();
        }
    }

    /**
     * Imports rules from an NVDA .dic file stream or JSON file stream.
     * NVDA syntax: pattern\tsubstitution\tcomment\tcaseSensitive(0/1)\tregex(0/1)
     * Streaming: processes line-by-line without holding the whole file in
     * memory twice, so massive word lists (100k+ entries) import without
     * crashing. JSON arrays are still parsed as one document (org.json has
     * no streaming mode) but rules are validated incrementally and the raw
     * text buffer is released before saving.
     */
    public synchronized int importFromStream(InputStream is) {
        return importFromStream(is, null);
    }

    public interface ImportProgress {
        void onProgress(int addedSoFar);
    }

    public synchronized int importFromStream(InputStream is, ImportProgress progress) {
        int added = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 32768)) {
            String firstLine = reader.readLine();
            if (firstLine == null) return 0;
            String firstTrim = firstLine.trim();
            boolean looksJson = firstTrim.startsWith("[");
            if (looksJson) {
                // Reassemble for org.json, but stream into a single builder
                // (the old code kept both rawLines AND fullContent).
                StringBuilder sb = new StringBuilder(65536);
                sb.append(firstLine).append('\n');
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    // Guard: refuse absurd single files (>50MB) instead of OOMing.
                    if (sb.length() > 50 * 1024 * 1024) {
                        Log.w(TAG, "Dictionary import too large, truncating at 50MB");
                        break;
                    }
                }
                try {
                    JSONArray arr = new JSONArray(sb.toString());
                    sb = null; // release raw text before allocating rules
                    for (int i = 0; i < arr.length(); i++) {
                        UserDictionary rule = UserDictionary.fromJson(arr.optJSONObject(i));
                        if (rule != null && rule.isValid()) {
                            mRules.add(rule);
                            added++;
                            if (progress != null && (added % 1000) == 0) {
                                progress.onProgress(added);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse JSON dictionary", e);
                }
                invalidateCache();
                save();
                return added;
            }

            // NVDA .dic format, truly streaming line-by-line.
            List<UserDictionary> batch = new ArrayList<>(1024);
            String raw = firstLine;
            do {
                UserDictionary rule = parseDicLine(raw);
                if (rule != null && rule.isValid()) {
                    batch.add(rule);
                    if (batch.size() >= 2000) {
                        mRules.addAll(batch);
                        added += batch.size();
                        batch.clear();
                        if (progress != null) progress.onProgress(added);
                    }
                }
            } while ((raw = reader.readLine()) != null);
            if (!batch.isEmpty()) {
                mRules.addAll(batch);
                added += batch.size();
            }
            invalidateCache();
            save();
        } catch (Exception e) {
            Log.e(TAG, "Failed to import dictionary", e);
        }
        return added;
    }

    /** NVDA .dic match types (the fifth column). */
    private static final String DIC_ANYWHERE = "0";
    private static final String DIC_REGEX = "1";
    private static final String DIC_WORD = "2";
    /** Comment written on export; this app's extra fields follow it as JSON. */
    private static final String DIC_COMMENT = "eSpeakAndroid";

    /**
     * One NVDA .dic line (pattern, replacement, comment, case sensitive, match
     * type; tab separated) or a plain "word=replacement" line. Null for
     * blanks, comments and lines with no usable pattern. A comment this app
     * wrote carries the fields NVDA's format has no column for (section,
     * language, phonemes), so an export imports back unchanged.
     */
    static UserDictionary parseDicLine(String raw) {
        String l = raw.trim();
        if (l.isEmpty() || l.startsWith("#")) return null;
        String[] parts = raw.split("\t", -1);
        String pattern;
        String replacement;
        boolean caseSensitive = false;
        boolean isRegex = false;
        boolean wholeWord = true;
        JSONObject extras = null;
        if (parts.length >= 2) {
            pattern = parts[0].trim();
            replacement = parts[1].replace("\r", "").replace("\n", "");
            if (parts.length >= 3) {
                final String comment = parts[2].trim();
                final int brace = comment.indexOf('{');
                if (comment.startsWith(DIC_COMMENT) && brace > 0) {
                    try {
                        extras = new JSONObject(comment.substring(brace));
                    } catch (Exception ignored) {
                        // Someone else's comment that happens to start the same way.
                    }
                }
            }
            if (parts.length >= 4) caseSensitive = "1".equals(parts[3].trim());
            if (parts.length >= 5) {
                final String type = parts[4].trim();
                isRegex = DIC_REGEX.equals(type);
                wholeWord = DIC_WORD.equals(type);
            }
        } else if (raw.contains("=")) {
            // Tolerate simple "word=replacement" lists too.
            int eq = raw.indexOf('=');
            pattern = raw.substring(0, eq).trim();
            replacement = raw.substring(eq + 1).replace("\r", "").replace("\n", "");
        } else {
            return null;
        }
        if (pattern.isEmpty()) return null;
        return new UserDictionary(pattern, replacement, caseSensitive, isRegex, wholeWord,
                extras != null ? extras.optString("language", "") : "",
                extras != null ? extras.optString("category", UserDictionary.CATEGORY_MAIN)
                        : UserDictionary.CATEGORY_MAIN,
                extras != null ? extras.optString("phonemes", "") : "");
    }

    /** One rule as a .dic line; tabs and line breaks inside it would split the line. */
    static String toDicLine(UserDictionary rule) {
        String comment = DIC_COMMENT;
        try {
            final JSONObject extras = new JSONObject();
            if (!UserDictionary.CATEGORY_MAIN.equals(rule.getCategory())) {
                extras.put("category", rule.getCategory());
            }
            if (!rule.getLanguage().isEmpty()) {
                extras.put("language", rule.getLanguage());
            }
            if (!rule.getPhonemes().isEmpty()) {
                extras.put("phonemes", rule.getPhonemes());
            }
            if (extras.length() > 0) {
                comment += " " + extras;
            }
        } catch (org.json.JSONException ignored) {
            // Only strings go in; cannot happen.
        }
        return field(rule.getPattern()) + "\t" + field(rule.getReplacement()) + "\t"
                + field(comment) + "\t" + (rule.isCaseSensitive() ? "1" : "0") + "\t"
                + (rule.isRegex() ? DIC_REGEX : rule.isWholeWord() ? DIC_WORD : DIC_ANYWHERE) + "\n";
    }

    private static String field(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public List<UserDictionary> getRulesByCategory(String category) {
        if (category == null) {
            return Collections.emptyList();
        }
        List<UserDictionary> cached = mCategoryCache.get(category);
        if (cached != null) {
            return cached;
        }
        final int generation = mGeneration.get();
        List<UserDictionary> out = new ArrayList<>();
        for (UserDictionary r : mRules) {
            if (r.getCategory().equals(category)) out.add(r);
        }
        List<UserDictionary> result = Collections.unmodifiableList(out);
        if (generation == mGeneration.get()) {
            mCategoryCache.put(category, result);
        }
        return result;
    }

    /** Bulk replace, used by restore-from-backup. */
    public synchronized void replaceAll(List<UserDictionary> rules) {
        final List<UserDictionary> valid = new ArrayList<>();
        if (rules != null) {
            for (UserDictionary r : rules) {
                if (r != null && r.isValid()) valid.add(r);
            }
        }
        // Invalidated after the swap too, so no list cached mid-swap survives.
        invalidateCache();
        mRules.clear();
        mRules.addAll(valid);
        invalidateCache();
        save();
    }

    /**
     * Exports rules in NVDA's .dic tab-delimited format. Throws on a failed
     * write so the caller reports it instead of "exported".
     */
    public synchronized void exportToStream(OutputStream os) throws java.io.IOException {
        try (Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            writer.write("# Speech dictionary file exported from eSpeak NG Android\n");
            for (UserDictionary rule : mRules) {
                writer.write(toDicLine(rule));
            }
            writer.flush();
        }
    }
}
