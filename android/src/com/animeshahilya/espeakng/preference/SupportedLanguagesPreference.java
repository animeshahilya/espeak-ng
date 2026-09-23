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

package com.animeshahilya.espeakng.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.preference.MultiSelectListPreference;

import com.animeshahilya.espeakng.LanguageSettings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Multi-select preference whose dialog (real-time search filtering,
 * explicit Select All / Deselect All buttons with TalkBack announcements,
 * empty-selection guard) lives in {@link SupportedLanguagesDialogFragment}.
 * This class owns the key, the entries/values source, and the "all
 * languages" persistence rule below.
 */
public class SupportedLanguagesPreference extends MultiSelectListPreference {
    public SupportedLanguagesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setKey(LanguageSettings.PREF_SUPPORTED_LANGUAGES);
        setPersistent(true);
    }

    public SupportedLanguagesPreference(Context context) {
        this(context, null);
    }

    // "All languages" is stored as the absence of the key, so that TtsService
    // exposes every voice, including ones added by a later update.
    @Override
    public boolean persistStringSet(Set<String> values) {
        if (!shouldPersist()) return false;
        int total = getDistinctValueCount();

        SharedPreferences.Editor editor = getSharedPreferences().edit();

        if (values == null || (total > 0 && values.size() >= total)) {
            // Treat as "all": remove the preference key so TtsService exposes everything.
            editor.remove(getKey());
            editor.apply();
            return true;
        }

        Set<String> copy = new HashSet<String>(values);
        editor.putStringSet(getKey(), copy);
        editor.apply();
        return true;
    }

    /**
     * Number of distinct languages. Some voices share a language value
     * (voice.toString()), so this is less than the number of entries, and
     * selecting every value must still count as "all".
     */
    public int getDistinctValueCount() {
        CharSequence[] values = getEntryValues();
        return values == null ? 0 : new HashSet<CharSequence>(Arrays.asList(values)).size();
    }

    @Override
    public Set<String> getPersistedStringSet(Set<String> defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        Set<String> stored = getSharedPreferences().getStringSet(getKey(), defaultReturnValue);
        return (stored == null) ? defaultReturnValue : new HashSet<String>(stored);
    }
}
