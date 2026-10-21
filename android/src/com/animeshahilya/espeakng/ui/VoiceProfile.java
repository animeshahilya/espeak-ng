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
import com.animeshahilya.espeakng.util.BackupRestoreHelper;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.util.TolerantPreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;


import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Voice profiles: the small sharable slice of the settings (variant, rate,
 * rate boost, pitch, pitch range, volume, intonation, reading mode,
 * punctuation) saved as a JSON file, so users can trade NVDA-style voice
 * setups; {@link VoicePresets} keeps named ones in the app. Full settings plus the dictionary
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

    /**
     * What a profile or preset holds. All are stored as strings, as the
     * settings screen stores them: the punctuation level used to be read
     * back with putInt, which the settings then could not read, so loading
     * a profile silently reset punctuation to the default.
     */
    private static final String[] KEYS = {
            VoiceSettings.PREF_VARIANT,
            VoiceSettings.PREF_RATE,
            VoiceSettings.PREF_RATE_BOOST_LEVEL,
            VoiceSettings.PREF_PITCH,
            VoiceSettings.PREF_PITCH_RANGE,
            VoiceSettings.PREF_VOLUME,
            VoiceSettings.PREF_INTONATION_STYLE,
            VoiceSettings.PREF_READING_MODE,
            VoiceSettings.PREF_PUNCTUATION_LEVEL,
            VoiceSettings.PREF_PUNCTUATION_CHARACTERS,
    };

    /**
     * The current values of {@link #KEYS}; one never set is JSON null
     * ("the default"), so applying the profile puts the default back
     * instead of keeping whatever the setting holds by then.
     */
    static JSONObject capture(SharedPreferences prefs) throws Exception {
        final SharedPreferences tolerant = TolerantPreferences.of(prefs);
        final JSONObject profile = new JSONObject();
        for (String key : KEYS) {
            final String value = tolerant.getString(key, null);
            profile.put(key, value != null ? value : JSONObject.NULL);
        }
        return profile;
    }

    /**
     * Writes a profile's known keys (numbers from older files become strings,
     * null means the default); returns true when at least one was applied.
     * Keys a file lacks keep their current values, except punctuation
     * characters, which belong to the level beside them.
     */
    static boolean apply(SharedPreferences prefs, JSONObject profile) {
        final SharedPreferences.Editor ed = prefs.edit();
        boolean applied = false;
        for (String key : KEYS) {
            if (!profile.has(key)) {
                continue;
            }
            if (profile.isNull(key)) {
                ed.remove(key);
            } else {
                ed.putString(key, profile.optString(key, ""));
            }
            applied = true;
        }
        if (profile.has(VoiceSettings.PREF_PUNCTUATION_LEVEL)
                && !profile.has(VoiceSettings.PREF_PUNCTUATION_CHARACTERS)) {
            ed.remove(VoiceSettings.PREF_PUNCTUATION_CHARACTERS);
        }
        if (applied) {
            ed.apply();
        }
        return applied;
    }

    public static void saveToStream(Context context, OutputStream os) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", PROFILE_VERSION);
        root.put("package", context.getPackageName());
        root.put("exportedAt", System.currentTimeMillis());
        root.put("profile", capture(EspeakApp.prefs(context)));
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
            JSONObject profile = read(is);
            return profile != null && apply(EspeakApp.prefs(context), profile);
        } catch (Exception e) {
            Log.w(TAG, "Profile load failed", e);
            return false;
        }
    }

    /** A profile file's settings, or null when it is not one. */
    static JSONObject read(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder(8192);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8), 4096)) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 1024 * 1024) {
                    return null; // not a profile; refuse, don't parse
                }
            }
        }
        return new JSONObject(sb.toString()).optJSONObject("profile");
    }
}


