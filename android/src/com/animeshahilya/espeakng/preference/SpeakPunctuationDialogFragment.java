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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;

import com.google.android.material.textfield.TextInputLayout;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.VoiceSettings;

/**
 * Dialog for {@link SpeakPunctuationPreference}: five preset radios plus a
 * custom character field. Wiring moved verbatim from the framework
 * {@code onCreateDialogView}/{@code onBindDialogView}/{@code onClick}
 * overrides, which have no AndroidX equivalents.
 */
public class SpeakPunctuationDialogFragment extends ButtonDialogFragment {
    private RadioButton mNone;
    private RadioButton mSome;
    private RadioButton mMost;
    private RadioButton mAll;
    private RadioButton mCustom;
    private EditText mPunctuationCharacters;

    public static SpeakPunctuationDialogFragment newInstance(String key) {
        return withKey(new SpeakPunctuationDialogFragment(), key);
    }

    private SpeakPunctuationPreference getPunctuationPreference() {
        return (SpeakPunctuationPreference) getPreference();
    }

    private static String textOf(Context context, int resId) {
        CharSequence cs = context != null ? context.getText(resId) : null;
        return cs != null ? cs.toString() : "";
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
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog stale = staleDialogUnless(SpeakPunctuationPreference.class);
        if (stale != null) return stale;
        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.speak_punctuation_preference, null);
        mNone = (RadioButton) root.findViewById(R.id.none);
        mSome = (RadioButton) root.findViewById(R.id.some);
        mMost = (RadioButton) root.findViewById(R.id.most);
        mAll = (RadioButton) root.findViewById(R.id.all);
        mCustom = (RadioButton) root.findViewById(R.id.custom);
        mPunctuationCharacters = (EditText) root.findViewById(R.id.punctuation_characters);
        final TextInputLayout punctuationLayout = root.findViewById(R.id.punctuation_characters_layout);

        // TalkBack: descriptions are importantForAccessibility=no in layout to
        // avoid 10 stops for 5 options. Fold each description into its
        // RadioButton's content description so one swipe announces the full
        // context ("Most. Everything in Some, plus...").
        mNone.setContentDescription(textOf(getContext(), R.string.punctuation_none)
                + ". " + textOf(getContext(), R.string.punctuation_none_desc));
        mSome.setContentDescription(textOf(getContext(), R.string.punctuation_some)
                + ". " + textOf(getContext(), R.string.punctuation_some_desc));
        mMost.setContentDescription(textOf(getContext(), R.string.punctuation_most)
                + ". " + textOf(getContext(), R.string.punctuation_most_desc));
        mAll.setContentDescription(textOf(getContext(), R.string.punctuation_all)
                + ". " + textOf(getContext(), R.string.punctuation_all_desc));
        mCustom.setContentDescription(textOf(getContext(), R.string.punctuation_custom)
                + ". " + textOf(getContext(), R.string.punctuation_custom_desc));

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
                // Disable the whole outlined field, not just the inner EditText:
                // a live-looking outline around a dead input reads as broken.
                if (punctuationLayout != null) {
                    punctuationLayout.setEnabled(selectCustom);
                }
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

        SpeakPunctuationPreference preference = getPunctuationPreference();
        String preset = SpeakPunctuationPreference.presetFor(
                preference.getVoiceSettings().getPunctuationLevel(),
                preference.getVoiceSettings().getPunctuationCharacters());
        mNone.setChecked(VoiceSettings.PUNCTUATION_PRESET_NONE.equals(preset));
        mSome.setChecked(VoiceSettings.PUNCTUATION_PRESET_SOME.equals(preset));
        mMost.setChecked(VoiceSettings.PUNCTUATION_PRESET_MOST.equals(preset));
        mAll.setChecked(VoiceSettings.PUNCTUATION_PRESET_ALL.equals(preset));
        mCustom.setChecked(VoiceSettings.PUNCTUATION_PRESET_CUSTOM.equals(preset));

        boolean isCustom = VoiceSettings.PUNCTUATION_PRESET_CUSTOM.equals(preset);
        mPunctuationCharacters.setText(isCustom
                ? preference.getVoiceSettings().getPunctuationCharacters() : "");
        if (punctuationLayout != null) {
            punctuationLayout.setEnabled(isCustom);
        }
        mPunctuationCharacters.setEnabled(isCustom);

        return buildDialog(root);
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (!positiveResult) {
            return;
        }
        SpeakPunctuationPreference preference = getPunctuationPreference();
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

        preference.onDataChanged(
                SpeakPunctuationPreference.presetFor(level, characters), characters);
        preference.persistPunctuation(level, characters);
        dismiss();
    }
}
