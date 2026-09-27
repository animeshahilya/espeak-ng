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
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Named voice profiles: the small sharable slice of the settings (variant,
 * rate, pitch, pitch range, volume, punctuation) saved as a JSON file, so
 * users can trade NVDA-style voice setups. Full settings plus the dictionary
 * stay in backup/restore ({@link BackupRestoreHelper}).
 *
 * <p>Unknown keys are ignored on load and missing keys keep their current
 * values, so profiles degrade gracefully across app versions in both
 * directions. Numeric values are clamped through the same getters the engine
 * path uses, so a hand-edited file cannot push the engine out of range.
 */
public final class VoiceProfile {
    private static final String TAG = "VoiceProfile";
    private static final int PROFILE_VERSION = 1;

    private VoiceProfile() {
    }

    private static final String[] STRING_KEYS = {
            VoiceSettings.PREF_VARIANT,
            VoiceSettings.PREF_RATE,
            VoiceSettings.PREF_PITCH,
            VoiceSettings.PREF_PITCH_RANGE,
            VoiceSettings.PREF_VOLUME,
            VoiceSettings.PREF_PUNCTUATION_CHARACTERS,
    };

    private static final String[] INT_KEYS = {
            VoiceSettings.PREF_PUNCTUATION_LEVEL,
    };

    public static void saveToStream(Context context, OutputStream os) throws Exception {
        Context storage = EspeakApp.requireStorageContext(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
        JSONObject profile = new JSONObject();
        for (String key : STRING_KEYS) {
            String value = prefs.getString(key, null);
            if (value != null) {
                profile.put(key, value);
            }
        }
        for (String key : INT_KEYS) {
            if (prefs.contains(key)) {
                try {
                    profile.put(key, prefs.getInt(key, 0));
                } catch (ClassCastException e) {
                    String value = prefs.getString(key, null);
                    if (value != null) {
                        profile.put(key, value);
                    }
                }
            }
        }
        JSONObject root = new JSONObject();
        root.put("version", PROFILE_VERSION);
        root.put("package", context.getPackageName());
        root.put("exportedAt", System.currentTimeMillis());
        root.put("profile", profile);
        try (Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write(root.toString(2));
            w.flush();
        }
    }

    /**
     * Applies a profile file to the device-protected settings.
     *
     * @return true when at least one known key was applied.
     */
    public static boolean loadFromStream(Context context, InputStream is) {
        try {
            StringBuilder sb = new StringBuilder(8192);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8), 4096)) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 1024 * 1024) {
                        return false; // not a profile; refuse, don't parse
                    }
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject profile = root.optJSONObject("profile");
            if (profile == null) {
                return false;
            }
            Context storage = EspeakApp.requireStorageContext(context);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
            SharedPreferences.Editor ed = prefs.edit();
            boolean applied = false;
            for (String key : STRING_KEYS) {
                if (profile.has(key) && !profile.isNull(key)) {
                    ed.putString(key, profile.optString(key, ""));
                    applied = true;
                }
            }
            for (String key : INT_KEYS) {
                if (profile.has(key) && !profile.isNull(key)) {
                    try {
                        ed.putInt(key, profile.getInt(key));
                    } catch (Exception e) {
                        ed.putString(key, profile.optString(key, ""));
                    }
                    applied = true;
                }
            }
            if (applied) {
                ed.apply();
            }
            return applied;
        } catch (Exception e) {
            Log.w(TAG, "Profile load failed", e);
            return false;
        }
    }
}
