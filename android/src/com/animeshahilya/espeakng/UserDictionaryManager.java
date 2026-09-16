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
    private final Context mContext;
    private final List<UserDictionary> mRules = new CopyOnWriteArrayList<>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private UserDictionaryManager(Context context) {
        mContext = context.getApplicationContext();
        load();
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
            save();
        }
    }

    public void setRule(int index, UserDictionary rule) {
        if (index >= 0 && index < mRules.size() && rule != null) {
            mRules.set(index, rule);
            save();
        }
    }

    public void removeRule(int index) {
        if (index >= 0 && index < mRules.size()) {
            mRules.remove(index);
            save();
        }
    }

    public void clearRules() {
        mRules.clear();
        save();
    }

    /**
     * Lock-free rule application for high-frequency TTS synthesis pipeline.
     *
     * @param language the language being synthesized (e.g. "en-in", "hi");
     *                 only rules unscoped or scoped to this language (or its
     *                 base language) are applied.
     */
    public String applyRules(String text, String language) {
        if (text == null || text.isEmpty() || mRules.isEmpty()) {
            return text;
        }
        String result = text;
        for (UserDictionary rule : mRules) {
            if (rule.appliesToLanguage(language)) {
                result = rule.apply(result);
            }
        }
        return result;
    }

    public synchronized void load() {
        mRules.clear();
        File file = new File(mContext.getFilesDir(), FILE_NAME);
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
                if (rule != null && !rule.getPattern().isEmpty()) {
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
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                saveAtomic(snapshot);
            }
        });
    }

    private synchronized void saveAtomic(List<UserDictionary> rules) {
        File file = new File(mContext.getFilesDir(), FILE_NAME);
        File tempFile = new File(mContext.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONArray();
            for (UserDictionary rule : rules) {
                arr.put(rule.toJson());
            }
            writer.write(arr.toString(2));
            writer.flush();
            fos.getFD().sync();
            if (!tempFile.renameTo(file)) {
                if (file.delete() && tempFile.renameTo(file)) {
                    // Succeeded on delete + rename
                } else {
                    Log.w(TAG, "Could not atomic rename temp user dictionary");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save user dictionary", e);
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
                        if (rule != null && !rule.getPattern().isEmpty()) {
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
                save();
                return added;
            }

            // NVDA .dic format, truly streaming line-by-line.
            List<UserDictionary> batch = new ArrayList<>(1024);
            String raw = firstLine;
            do {
                String l = raw.trim();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    String[] parts = raw.split("\t");
                    if (parts.length >= 2) {
                        String pattern = parts[0].trim();
                        String replacement = parts[1].trim();
                        boolean caseSensitive = false;
                        boolean isRegex = false;
                        boolean wholeWord = true;

                        if (parts.length >= 4) {
                            caseSensitive = "1".equals(parts[3].trim());
                        }
                        if (parts.length >= 5) {
                            isRegex = "1".equals(parts[4].trim());
                            if (isRegex) wholeWord = false;
                        }

                        if (!pattern.isEmpty()) {
                            batch.add(new UserDictionary(pattern, replacement, caseSensitive, isRegex, wholeWord));
                            if (batch.size() >= 2000) {
                                mRules.addAll(batch);
                                added += batch.size();
                                batch.clear();
                                if (progress != null) progress.onProgress(added);
                            }
                        }
                    } else if (parts.length == 1 && raw.contains("=")) {
                        // Tolerate simple "word=replacement" lists too.
                        int eq = raw.indexOf('=');
                        String pattern = raw.substring(0, eq).trim();
                        String replacement = raw.substring(eq + 1).trim();
                        if (!pattern.isEmpty()) {
                            batch.add(new UserDictionary(pattern, replacement, false, false, true));
                            if (batch.size() >= 2000) {
                                mRules.addAll(batch);
                                added += batch.size();
                                batch.clear();
                                if (progress != null) progress.onProgress(added);
                            }
                        }
                    }
                }
            } while ((raw = reader.readLine()) != null);
            if (!batch.isEmpty()) {
                mRules.addAll(batch);
                added += batch.size();
            }
            save();
        } catch (Exception e) {
            Log.e(TAG, "Failed to import dictionary", e);
        }
        return added;
    }

    /** Case-insensitive substring search over pattern/replacement for the UI list. */
    public List<Integer> searchIndices(String query) {
        List<Integer> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            for (int i = 0; i < mRules.size(); i++) out.add(i);
            return out;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < mRules.size(); i++) {
            UserDictionary r = mRules.get(i);
            if (r.getPattern().toLowerCase(java.util.Locale.ROOT).contains(q)
                    || r.getReplacement().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                out.add(i);
            }
        }
        return out;
    }

    public List<UserDictionary> getRulesByCategory(String category) {
        List<UserDictionary> out = new ArrayList<>();
        for (UserDictionary r : mRules) {
            if (r.getCategory().equals(category)) out.add(r);
        }
        return Collections.unmodifiableList(out);
    }

    /** Bulk replace, used by restore-from-backup. */
    public synchronized void replaceAll(List<UserDictionary> rules) {
        mRules.clear();
        if (rules != null) {
            for (UserDictionary r : rules) {
                if (r != null && !r.getPattern().isEmpty()) mRules.add(r);
            }
        }
        save();
    }

    /**
     * Exports rules in standard .dic tab-delimited format.
     */
    public synchronized void exportToStream(OutputStream os) {
        try (Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            writer.write("# Speech dictionary file exported from eSpeak NG Android\n");
            for (UserDictionary rule : mRules) {
                writer.write(rule.getPattern() + "\t" +
                        rule.getReplacement() + "\t" +
                        "eSpeakAndroid\t" +
                        (rule.isCaseSensitive() ? "1" : "0") + "\t" +
                        (rule.isRegex() ? "1" : "0") + "\n");
            }
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to export dictionary", e);
        }
    }
}
