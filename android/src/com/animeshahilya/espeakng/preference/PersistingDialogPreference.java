/*
 * Copyright (C) 2026
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

import androidx.preference.DialogPreference;

/**
 * Base for the app's DialogPreference subclasses that persist through a
 * SharedPreferences.Editor directly, bypassing Preference's own
 * persistX()/getPersistedX() methods (SeekBarPreference, which has several
 * independent parameters per dialog; SpeakPunctuationPreference and
 * VoiceVariantPreference, whose stored values aren't a single primitive).
 * Each had its own copy of "am I allowed to persist, and is there a prefs
 * store to persist to" before its own put() calls; {@link #beginEdit()}
 * below is that guard in one place.
 */
abstract class PersistingDialogPreference extends DialogPreference {
    PersistingDialogPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    PersistingDialogPreference(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
    }

    PersistingDialogPreference(Context context) {
        this(context, null);
    }

    /**
     * shouldPersist() + getSharedPreferences() null-check + edit(): the guard
     * every subclass needs before writing its own keys, wherever this
     * preference isn't backed by a real prefs store (e.g. isPersistent(false),
     * or no SharedPreferencesProvider attached yet). Returns null in that case,
     * matching each subclass's original early-return; callers must check for it
     * before using the editor.
     */
    protected final SharedPreferences.Editor beginEdit() {
        if (!shouldPersist()) {
            return null;
        }
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) {
            return null;
        }
        return prefs.edit();
    }
}
