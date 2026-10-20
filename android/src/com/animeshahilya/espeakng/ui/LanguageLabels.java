/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.util.Log;
import com.animeshahilya.espeakng.BuildConfig;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.tts.CheckVoiceData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;

/**
 * How the settings screen names voices and languages: the system language's name with the voice data's English name, read once from the lang/ files.
 */
public final class LanguageLabels {
    private static final String TAG = "LanguageLabels";

    private LanguageLabels() {
    }

    private static final java.util.HashMap<String, LangInfo> sLangInfo = new java.util.HashMap<String, LangInfo>();

    public static String getSupportedLanguagesSummary(Context context, Set<String> selected, int total) {
        int enabled = (selected == null || selected.isEmpty()) ? total : selected.size();
        if (enabled >= total) {
            return context.getString(R.string.espeak_supported_languages_all);
        }
        return context.getResources().getQuantityString(R.plurals.espeak_supported_languages_summary, total, enabled, total);
    }

    /**
     * Localized voice label: the name in the system's own language first
     * (e.g. "हिन्दी" on a Hindi system), followed by the English name from
     * the voice data in parentheses. That is the Android-native answer to
     * upstream #2515's self-designation registry proposal - Locale already
     * localizes every language for free, with no registry to maintain.
     * On an English system the voice data's own name is used alone: the
     * Locale name would only repeat it in other words ("Abkhazian (Abkhaz)",
     * "Armenian (Armenian (East Armenia))"), which TalkBack then reads twice.
     * Also falls back to the English name when both agree, and to the raw
     * voice name when neither is available. Keeping the English name
     * always present also keeps regional variants that share a localized
     * name ("English (India)" vs "English (Singapore)") distinguishable.
     */
    public static String getVoiceLabel(Voice voice) {
        LangInfo info = lookupLangInfo(voice);
        final String english = info != null ? info.displayName : voice.name;
        if ("en".equals(Locale.getDefault().getLanguage())) {
            return english;
        }
        final String localized;
        try {
            localized = voice.locale.getDisplayName(Locale.getDefault());
        } catch (Exception e) {
            return english;
        }
        if (localized == null || localized.isEmpty() || localized.equalsIgnoreCase(english)) {
            return english;
        }
        return localized + " (" + english + ")";
    }

    private static class LangInfo {
        final String displayName;
        LangInfo(String displayName) {
            this.displayName = displayName;
        }
    }

    private static LangInfo lookupLangInfo(Voice voice) {
        ensureLangInfoLoaded();
        String key1 = voice.name;
        String key2 = null;
        if (voice.identifier != null) {
            int slash = voice.identifier.lastIndexOf('/');
            key2 = (slash >= 0 && slash < voice.identifier.length() - 1) ? voice.identifier.substring(slash + 1) : voice.identifier;
        }
        // Same monitor as ensureLangInfoLoaded() and the import-thread clear():
        // HashMap is not thread-safe, so unsynchronized reads could observe
        // a half-updated map.
        synchronized (LanguageLabels.class) {
            LangInfo info = sLangInfo.get(key1);
            if (info == null && key2 != null) {
                info = sLangInfo.get(key2);
            }
            return info;
        }
    }

    // Synchronized because createPreferences() warms this from a worker thread
    // while lookupLangInfo() may reach it from the main thread. Readers always
    // come through here first, so they block until a build in progress
    // finishes rather than observing a half-populated map.
    public static synchronized void ensureLangInfoLoaded() {
        Context storage = EspeakApp.getStorageContext();
        if (!sLangInfo.isEmpty() || storage == null) return;
        File root = new File(CheckVoiceData.getDataPath(storage), "lang");
        if (!root.exists()) return;
        Stack<File> stack = new Stack<File>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory()) {
                    stack.push(f);
                } else {
                    LangInfo info = parseLangFile(f);
                    if (info != null) {
                        sLangInfo.put(f.getName(), info);
                    }
                }
            }
        }
    }

    /** The voice data changed (a voice was imported): names are read again. */
    public static synchronized void forgetLangInfo() {
        sLangInfo.clear();
    }

    private static LangInfo parseLangFile(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String name = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("name")) {
                    name = line.substring("name".length()).trim();
                    break;
                }
            }
            if (name == null) return null;
            return new LangInfo(name);
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Failed parsing lang file " + file.getName() + ": " + e.getMessage());
            }
            return null;
        }
    }
}
