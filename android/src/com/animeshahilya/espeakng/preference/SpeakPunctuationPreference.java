/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
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

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.VoiceSettings;

public class SpeakPunctuationPreference extends DialogPreference {
    private VoiceSettings mSettings;

    public SpeakPunctuationPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setLayoutResource(R.layout.information_view);
    }

    public SpeakPunctuationPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpeakPunctuationPreference(Context context) {
        this(context, null);
    }

    public void setVoiceSettings(VoiceSettings settings) {
        mSettings = settings;
        onDataChanged(presetFor(mSettings.getPunctuationLevel(), mSettings.getPunctuationCharacters()),
                mSettings.getPunctuationCharacters());
    }

    VoiceSettings getVoiceSettings() {
        return mSettings;
    }

    /**
     * Works out which of the five UI presets a stored (level, characters)
     * pair corresponds to. "Some" and "Most" are recognised by their
     * character list matching one of the two built-in presets exactly;
     * anything else at PUNCT_SOME is a user-entered "Custom" list.
     */
    static String presetFor(int level, String characters) {
        switch (level) {
            case SpeechSynthesis.PUNCT_ALL:
                return VoiceSettings.PUNCTUATION_PRESET_ALL;
            case SpeechSynthesis.PUNCT_SOME:
                if (characters == null || characters.isEmpty()) {
                    return VoiceSettings.PUNCTUATION_PRESET_NONE;
                } else if (characters.equals(VoiceSettings.PUNCTUATION_CHARS_SOME)) {
                    return VoiceSettings.PUNCTUATION_PRESET_SOME;
                } else if (characters.equals(VoiceSettings.PUNCTUATION_CHARS_MOST)) {
                    return VoiceSettings.PUNCTUATION_PRESET_MOST;
                } else {
                    return VoiceSettings.PUNCTUATION_PRESET_CUSTOM;
                }
            case SpeechSynthesis.PUNCT_NONE:
            default:
                return VoiceSettings.PUNCTUATION_PRESET_NONE;
        }
    }

    void onDataChanged(String preset, String characters) {
        switch (preset) {
            case VoiceSettings.PUNCTUATION_PRESET_SOME:
                callChangeListener(getContext().getText(R.string.punctuation_some));
                break;
            case VoiceSettings.PUNCTUATION_PRESET_MOST:
                callChangeListener(getContext().getText(R.string.punctuation_most));
                break;
            case VoiceSettings.PUNCTUATION_PRESET_ALL:
                callChangeListener(getContext().getText(R.string.punctuation_all));
                break;
            case VoiceSettings.PUNCTUATION_PRESET_CUSTOM:
                if (characters == null || characters.isEmpty()) {
                    callChangeListener(getContext().getText(R.string.punctuation_none));
                } else {
                    callChangeListener(String.format(getContext().getText(R.string.punctuation_custom_fmt).toString(), characters));
                }
                break;
            case VoiceSettings.PUNCTUATION_PRESET_NONE:
            default:
                callChangeListener(getContext().getText(R.string.punctuation_none));
                break;
        }
    }

    /** Persist a confirmed dialog selection, mirroring the old dialog-OK path. */
    void persistPunctuation(int level, String characters) {
        if (!shouldPersist()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(VoiceSettings.PREF_PUNCTUATION_CHARACTERS, characters);
        editor.putString(VoiceSettings.PREF_PUNCTUATION_LEVEL, Integer.toString(level));
        editor.commit();
    }
}
