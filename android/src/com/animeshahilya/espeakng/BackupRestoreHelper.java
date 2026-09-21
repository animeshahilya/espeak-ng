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
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backup &amp; restore: exports voice tunings, preferences, and dictionaries
 * into a single JSON file for seamless restoration via standard Android
 * storage (SAF). The file looks like:
 * {@code {"version":1,"prefs":{...},"dictionary":[...]}}.
 */
public final class BackupRestoreHelper {
    private static final String TAG = "BackupRestore";
    private static final int BACKUP_VERSION = 1;

    private BackupRestoreHelper() {
    }

    public static void exportToStream(Context context, OutputStream os) throws Exception {
        Context storage = EspeakApp.requireStorageContext(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
        JSONObject root = new JSONObject();
        root.put("version", BACKUP_VERSION);
        root.put("package", context.getPackageName());
        root.put("exportedAt", System.currentTimeMillis());

        JSONObject prefsJson = new JSONObject();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Boolean || v instanceof Integer
                    || v instanceof Long || v instanceof Float) {
                prefsJson.put(e.getKey(), v);
            } else if (v instanceof java.util.Set) {
                JSONArray arr = new JSONArray();
                for (Object o : (java.util.Set<?>) v) arr.put(String.valueOf(o));
                prefsJson.put(e.getKey(), arr);
            }
        }
        root.put("prefs", prefsJson);

        JSONArray dict = new JSONArray();
        List<UserDictionary> rules = UserDictionaryManager.getInstance(context).getRules();
        for (UserDictionary r : rules) dict.put(r.toJson());
        root.put("dictionary", dict);

        try (Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write(root.toString(2));
            w.flush();
        }
    }

    public static int importFromStream(Context context, InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder(65536);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8), 32768)) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 50 * 1024 * 1024) {
                    throw new IllegalArgumentException("Backup file too large (>50MB)");
                }
            }
        }
        JSONObject root = new JSONObject(sb.toString());
        int version = root.optInt("version", 1);
        if (version > BACKUP_VERSION) {
            Log.w(TAG, "Backup from newer version " + version + ", attempting anyway");
        }

        int restoredRules = 0;
        JSONArray dict = root.optJSONArray("dictionary");
        if (dict != null) {
            List<UserDictionary> rules = new ArrayList<>(dict.length());
            for (int i = 0; i < dict.length(); i++) {
                JSONObject obj = dict.optJSONObject(i);
                if (obj == null) continue;
                UserDictionary r = UserDictionary.fromJson(obj);
                if (r != null && !r.getPattern().isEmpty()) rules.add(r);
            }
            UserDictionaryManager.getInstance(context).replaceAll(rules);
            restoredRules = rules.size();
        }

        JSONObject prefsJson = root.optJSONObject("prefs");
        if (prefsJson != null) {
            Context storage = EspeakApp.requireStorageContext(context);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
            Map<String, ?> existingPrefs = prefs.getAll();
            SharedPreferences.Editor ed = prefs.edit();
            java.util.Iterator<String> keys = prefsJson.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = prefsJson.opt(k);
                Object cur = existingPrefs.get(k);
                if (cur instanceof Boolean || (cur == null && v instanceof Boolean)) {
                    ed.putBoolean(k, v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)));
                } else if (cur instanceof Integer || (cur == null && v instanceof Integer)) {
                    try {
                        ed.putInt(k, v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v)));
                    } catch (NumberFormatException nfe) {
                        ed.putString(k, String.valueOf(v));
                    }
                } else if (cur instanceof Long || (cur == null && v instanceof Long)) {
                    try {
                        ed.putLong(k, v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v)));
                    } catch (NumberFormatException nfe) {
                        ed.putString(k, String.valueOf(v));
                    }
                } else if (cur instanceof Float || (cur == null && (v instanceof Double || v instanceof Float))) {
                    try {
                        ed.putFloat(k, v instanceof Number ? ((Number) v).floatValue() : Float.parseFloat(String.valueOf(v)));
                    } catch (NumberFormatException nfe) {
                        ed.putString(k, String.valueOf(v));
                    }
                } else if (v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    java.util.Set<String> set = new java.util.HashSet<>();
                    for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
                    ed.putStringSet(k, set);
                } else {
                    ed.putString(k, String.valueOf(v));
                }
            }
            ed.commit();
        }
        // Explicit package scoping, matching DownloadVoiceData and the
        // voice-import sender: only this app's TtsService should act on the
        // reload broadcast, and targetSdk 34+ blocks implicit sends to
        // registered receivers anyway.
        context.sendBroadcast(new android.content.Intent(
                DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED)
                .setPackage(context.getPackageName()));
        return restoredRules;
    }
}
