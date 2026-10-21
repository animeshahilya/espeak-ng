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

package com.animeshahilya.espeakng.tts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.ui.VoicePresets;
import com.animeshahilya.espeakng.ui.VoiceSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings from other apps (espeak-ng issue #2085: Tasker, MacroDroid,
 * adb): a broadcast {@link #ACTION} to this package, with any of these
 * extras (strings or numbers):
 * <ul>
 * <li>{@code rate} words per minute, {@code pitch} 0-100, {@code volume}
 *     0-200 - a number, or "+20" / "-20" to change the current value;
 * <li>{@code punctuation} none / some / most / all;
 * <li>{@code reading_mode} normal / spelling / code;
 * <li>{@code natural_voices} true / false;
 * <li>{@code preset} a preset's name (Backup and about -> Presets).
 * </ul>
 * Off unless the user turns on {@link VoiceSettings#PREF_ALLOW_OTHER_APPS}:
 * any app can send it. Values are clamped, unknown extras ignored.
 * Example: {@code adb shell am broadcast -a com.animeshahilya.espeakng.SET_SETTINGS
 * -p com.animeshahilya.espeakng --es rate +25}
 */
public class SettingsReceiver extends BroadcastReceiver {
    private static final String TAG = "SettingsReceiver";
    public static final String ACTION = "com.animeshahilya.espeakng.SET_SETTINGS";
    /** eSpeak's own defaults, for a relative change to a setting never saved. */
    private static final int DEFAULT_RATE = 175;
    private static final int DEFAULT_PITCH = 50;
    private static final int DEFAULT_VOLUME = 100;
    private static final int MIN_RATE = 80;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final SharedPreferences prefs = EspeakApp.prefs(context);
        if (!prefs.getBoolean(VoiceSettings.PREF_ALLOW_OTHER_APPS, false)) {
            Log.i(TAG, "Ignored: changes from other apps are off (Settings -> Advanced)");
            return;
        }
        final Map<String, String> changes = new HashMap<>();
        final Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                @SuppressWarnings("deprecation") // any type: Tasker sends strings, adb ints
                final Object value = extras.get(key);
                if (value != null) {
                    changes.put(key, String.valueOf(value));
                }
            }
        }
        Log.i(TAG, "Applied " + apply(prefs, changes) + " of " + changes.size() + " settings");
    }

    /** Writes each valid change; returns how many were applied. */
    static int apply(SharedPreferences prefs, Map<String, String> changes) {
        final SharedPreferences.Editor editor = prefs.edit();
        int applied = 0;
        for (Map.Entry<String, String> e : changes.entrySet()) {
            final String value = e.getValue().trim();
            switch (e.getKey()) {
                case "rate":
                    applied += number(editor, prefs, VoiceSettings.PREF_RATE, value, DEFAULT_RATE,
                            MIN_RATE, 450 * VoiceSettings.RATE_BOOST_MULTIPLIER_MAX);
                    break;
                case "pitch":
                    applied += number(editor, prefs, VoiceSettings.PREF_PITCH, value, DEFAULT_PITCH, 0, 100);
                    break;
                case "volume":
                    applied += number(editor, prefs, VoiceSettings.PREF_VOLUME, value, DEFAULT_VOLUME, 0, 200);
                    break;
                case "punctuation":
                    applied += punctuation(editor, value);
                    break;
                case "reading_mode":
                    if (VoiceSettings.READING_NORMAL.equals(value) || VoiceSettings.READING_SPELLING.equals(value)
                            || VoiceSettings.READING_CODE.equals(value)) {
                        editor.putString(VoiceSettings.PREF_READING_MODE, value);
                        applied++;
                    }
                    break;
                case "preset":
                    if (VoicePresets.use(prefs, value)) {
                        applied++;
                    }
                    break;
                case "natural_voices":
                    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                        editor.putBoolean(PiperVoiceStore.PREF_ENABLED, Boolean.parseBoolean(value));
                        applied++;
                    }
                    break;
                default:
                    break;
            }
        }
        editor.apply();
        return applied;
    }

    /** An absolute value, or "+n"/"-n" from the current one; clamped. 1 if applied. */
    private static int number(SharedPreferences.Editor editor, SharedPreferences prefs, String key,
                              String value, int fallback, int min, int max) {
        try {
            int v;
            if (value.startsWith("+") || value.startsWith("-")) {
                int current = fallback;
                try {
                    current = Integer.parseInt(prefs.getString(key, String.valueOf(fallback)));
                } catch (ClassCastException | NumberFormatException ignored) {
                    // a mistyped stored value counts as the default
                }
                v = current + Integer.parseInt(value.substring(value.startsWith("+") ? 1 : 0));
            } else {
                v = Integer.parseInt(value);
            }
            editor.putString(key, String.valueOf(Math.max(min, Math.min(max, v))));
            return 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The punctuation dialog's presets, stored as it stores them. 1 if applied. */
    private static int punctuation(SharedPreferences.Editor editor, String preset) {
        final int level;
        final String characters;
        switch (preset) {
            case VoiceSettings.PUNCTUATION_PRESET_NONE:
                level = SpeechSynthesis.PUNCT_NONE;
                characters = null;
                break;
            case VoiceSettings.PUNCTUATION_PRESET_SOME:
                level = SpeechSynthesis.PUNCT_SOME;
                characters = VoiceSettings.PUNCTUATION_CHARS_SOME;
                break;
            case VoiceSettings.PUNCTUATION_PRESET_MOST:
                level = SpeechSynthesis.PUNCT_SOME;
                characters = VoiceSettings.PUNCTUATION_CHARS_MOST;
                break;
            case VoiceSettings.PUNCTUATION_PRESET_ALL:
                level = SpeechSynthesis.PUNCT_ALL;
                characters = null;
                break;
            default:
                return 0;
        }
        editor.putString(VoiceSettings.PREF_PUNCTUATION_LEVEL, Integer.toString(level));
        editor.putString(VoiceSettings.PREF_PUNCTUATION_CHARACTERS, characters);
        return 1;
    }
}
