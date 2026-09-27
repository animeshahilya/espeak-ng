/*
 * Copyright (C) 2025
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

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LanguageSettings {
    public static final String PREF_SUPPORTED_LANGUAGES = "espeak_supported_languages";
    /**
     * Favorite voices, pinned to the top of the system voice list. Stored as
     * voice identifiers ({@link Voice#toString()}). A favorite for a voice
     * that is filtered out or gone after an update is ignored, never an error.
     */
    public static final String PREF_FAVORITE_VOICES = "espeak_favorite_voices";

    private LanguageSettings() {
    }

    public static Set<String> getFavoriteVoices(SharedPreferences preferences) {
        if (!preferences.contains(PREF_FAVORITE_VOICES)) {
            return new HashSet<String>();
        }
        final Set<String> stored = preferences.getStringSet(PREF_FAVORITE_VOICES, null);
        final Set<String> favorites = new HashSet<String>();
        if (stored != null) {
            for (String s : stored) {
                if (s != null && !s.isEmpty()) {
                    favorites.add(s);
                }
            }
        }
        return favorites;
    }

    public static Set<String> getSelectedLanguages(SharedPreferences preferences) {
        if (!preferences.contains(PREF_SUPPORTED_LANGUAGES)) {
            return null; // treat as "all"
        }
        final Set<String> stringSet = preferences.getStringSet(PREF_SUPPORTED_LANGUAGES, null);
        if (stringSet == null || stringSet.isEmpty()) {
            return null; // missing or empty means all
        }
        final Set<String> selected = new HashSet<String>();
        for (String s : stringSet) {
            if (s != null) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            return null;
        }
        return selected;
    }

    public static List<Voice> filterVoices(List<Voice> voices, SharedPreferences preferences) {
        final Set<String> selected = getSelectedLanguages(preferences);
        if (selected == null) {
            return voices;
        }

        final List<Voice> filtered = new ArrayList<Voice>();
        for (Voice voice : voices) {
            if (selected.contains(voice.toString())) {
                filtered.add(voice);
            }
        }
        return filtered;
    }
}
