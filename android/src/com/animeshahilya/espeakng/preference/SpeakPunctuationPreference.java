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
import android.os.Build;
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

    private static android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo createCollectionItemInfo(
            int index, boolean selected) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo(
                    index, 1, 0, 1, false, selected);
        }
        return createLegacyCollectionItemInfo(index, selected);
    }

    // obtain() was deprecated in API 33 when pooling was discontinued, but the
    // public constructor only exists from API 33, so pre-33 still needs this.
    @SuppressWarnings("deprecation")
    private static android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo createLegacyCollectionItemInfo(
            int index, boolean selected) {
        return android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo.obtain(
                index, 1, 0, 1, false, selected);
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

        final RadioButton[] buttons = new RadioButton[] { mNone, mSome, mMost, mAll, mCustom };
        final View.OnClickListener selectOption = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean selectCustom = (v == mCustom || v.getId() == R.id.custom_desc);
                mNone.setChecked(v == mNone || v.getId() == R.id.none_desc);
                mSome.setChecked(v == mSome || v.getId() == R.id.some_desc);
                mMost.setChecked(v == mMost || v.getId() == R.id.most_desc);
                mAll.setChecked(v == mAll || v.getId() == R.id.all_desc);
                mCustom.setChecked(selectCustom);
                mPunctuationCharacters.setEnabled(selectCustom);
                if (selectCustom && v != mPunctuationCharacters) {
                    mPunctuationCharacters.requestFocus();
                }
            }
        };

        for (int i = 0; i < buttons.length; i++) {
            final int index = i;
            final RadioButton rb = buttons[i];
            rb.setOnClickListener(selectOption);
            rb.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCollectionItemInfo(createCollectionItemInfo(index, rb.isChecked()));
                }
            });
        }

        View descNone = root.findViewById(R.id.none_desc);
        if (descNone != null) descNone.setOnClickListener(selectOption);
        View descSome = root.findViewById(R.id.some_desc);
        if (descSome != null) descSome.setOnClickListener(selectOption);
        View descMost = root.findViewById(R.id.most_desc);
        if (descMost != null) descMost.setOnClickListener(selectOption);
        View descAll = root.findViewById(R.id.all_desc);
        if (descAll != null) descAll.setOnClickListener(selectOption);
        View descCustom = root.findViewById(R.id.custom_desc);
        if (descCustom != null) descCustom.setOnClickListener(selectOption);

        mPunctuationCharacters.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && !mCustom.isChecked()) {
                    selectOption.onClick(mCustom);
                }
            }
        });

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
