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
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.DialogPreference;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.VoiceSettings;

public class SpeakPunctuationPreference extends DialogPreference {
    private RadioButton mNone;
    private RadioButton mSome;
    private RadioButton mMost;
    private RadioButton mAll;
    private RadioButton mCustom;
    private EditText mPunctuationCharacters;

    private VoiceSettings mSettings;

    public SpeakPunctuationPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setDialogLayoutResource(R.layout.speak_punctuation_preference);
        setLayoutResource(R.layout.information_view);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
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

    private String textOf(int resId) {
        CharSequence cs = getContext() != null ? getContext().getText(resId) : null;
        return cs != null ? cs.toString() : "";
    }

    /**
     * Works out which of the five UI presets a stored (level, characters)
     * pair corresponds to. "Some" and "Most" are recognised by their
     * character list matching one of the two built-in presets exactly;
     * anything else at PUNCT_SOME is a user-entered "Custom" list.
     */
    private static String presetFor(int level, String characters) {
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

    private void onDataChanged(String preset, String characters) {
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

    @Override
    protected View onCreateDialogView() {
        View root = super.onCreateDialogView();
        mNone = (RadioButton)root.findViewById(R.id.none);
        mSome = (RadioButton)root.findViewById(R.id.some);
        mMost = (RadioButton)root.findViewById(R.id.most);
        mAll = (RadioButton)root.findViewById(R.id.all);
        mCustom = (RadioButton)root.findViewById(R.id.custom);
        mPunctuationCharacters = (EditText)root.findViewById(R.id.punctuation_characters);

        // TalkBack: descriptions are importantForAccessibility=no in layout to
        // avoid 10 stops for 5 options. Fold each description into its
        // RadioButton's content description so one swipe announces the full
        // context ("Most. Everything in Some, plus...").
        mNone.setContentDescription(textOf(R.string.punctuation_none)
                + ". " + textOf(R.string.punctuation_none_desc));
        mSome.setContentDescription(textOf(R.string.punctuation_some)
                + ". " + textOf(R.string.punctuation_some_desc));
        mMost.setContentDescription(textOf(R.string.punctuation_most)
                + ". " + textOf(R.string.punctuation_most_desc));
        mAll.setContentDescription(textOf(R.string.punctuation_all)
                + ". " + textOf(R.string.punctuation_all_desc));
        mCustom.setContentDescription(textOf(R.string.punctuation_custom)
                + ". " + textOf(R.string.punctuation_custom_desc));

        // The five options aren't a real RadioGroup (each row carries its own
        // description text below it, so the RadioButtons aren't direct
        // children of one), so exclusivity is handled by hand here. The
        // custom character field is only meaningful - and only enabled -
        // when "Custom" is selected.
        View.OnClickListener selectOption = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mNone.setChecked(v == mNone);
                mSome.setChecked(v == mSome);
                mMost.setChecked(v == mMost);
                mAll.setChecked(v == mAll);
                mCustom.setChecked(v == mCustom);
                mPunctuationCharacters.setEnabled(v == mCustom);
            }
        };
        mNone.setOnClickListener(selectOption);
        mSome.setOnClickListener(selectOption);
        mMost.setOnClickListener(selectOption);
        mAll.setOnClickListener(selectOption);
        mCustom.setOnClickListener(selectOption);

        return root;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);

        String preset = presetFor(mSettings.getPunctuationLevel(), mSettings.getPunctuationCharacters());
        mNone.setChecked(VoiceSettings.PUNCTUATION_PRESET_NONE.equals(preset));
        mSome.setChecked(VoiceSettings.PUNCTUATION_PRESET_SOME.equals(preset));
        mMost.setChecked(VoiceSettings.PUNCTUATION_PRESET_MOST.equals(preset));
        mAll.setChecked(VoiceSettings.PUNCTUATION_PRESET_ALL.equals(preset));
        mCustom.setChecked(VoiceSettings.PUNCTUATION_PRESET_CUSTOM.equals(preset));

        boolean isCustom = VoiceSettings.PUNCTUATION_PRESET_CUSTOM.equals(preset);
        mPunctuationCharacters.setText(isCustom ? mSettings.getPunctuationCharacters() : "");
        mPunctuationCharacters.setEnabled(isCustom);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                int level;
                String characters;

                if (mSome.isChecked()) {
                    level = SpeechSynthesis.PUNCT_SOME;
                    characters = VoiceSettings.PUNCTUATION_CHARS_SOME;
                } else if (mMost.isChecked()) {
                    level = SpeechSynthesis.PUNCT_SOME;
                    characters = VoiceSettings.PUNCTUATION_CHARS_MOST;
                } else if (mAll.isChecked()) {
                    level = SpeechSynthesis.PUNCT_ALL;
                    characters = null;
                } else if (mCustom.isChecked()) {
                    Editable text = mPunctuationCharacters.getText();
                    characters = text != null ? text.toString() : null;
                    level = (characters == null || characters.isEmpty())
                            ? SpeechSynthesis.PUNCT_NONE : SpeechSynthesis.PUNCT_SOME;
                } else {
                    level = SpeechSynthesis.PUNCT_NONE;
                    characters = null;
                }

                onDataChanged(presetFor(level, characters), characters);

                if (shouldCommit()) {
                    SharedPreferences.Editor editor = getEditor();
                    if (editor != null) {
                        editor.putString(VoiceSettings.PREF_PUNCTUATION_CHARACTERS, characters);
                        editor.putString(VoiceSettings.PREF_PUNCTUATION_LEVEL, Integer.toString(level));
                        editor.commit();
                    }
                }
                break;
        }
        super.onClick(dialog, which);
    }
}
