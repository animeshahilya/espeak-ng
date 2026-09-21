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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;

import androidx.preference.PreferenceDialogFragmentCompat;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.ResourceIdListAdapter;
import com.animeshahilya.espeakng.preference.VoiceVariantPreference.VariantDataListAdapter;

/**
 * Dialog for {@link VoiceVariantPreference}: category/variant spinners with
 * the cascade. Wiring moved verbatim from the framework
 * {@code onCreateDialogView}/{@code onBindDialogView}/{@code onClick}
 * overrides, which have no AndroidX equivalents. Working indices live here
 * so cancelling truly discards the browsed selection.
 */
public class VoiceVariantDialogFragment extends PreferenceDialogFragmentCompat {
    private Spinner mCategory;
    private Spinner mVariant;

    private int mCategoryIndex = 0;
    private int mVariantIndex = 0;

    public static VoiceVariantDialogFragment newInstance(String key) {
        VoiceVariantDialogFragment fragment = new VoiceVariantDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private VoiceVariantPreference getVariantPreference() {
        return (VoiceVariantPreference) getPreference();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        VoiceVariantPreference preference = getVariantPreference();
        int[] selected = preference.getSelectedIndices();
        mCategoryIndex = selected[0];
        mVariantIndex = selected[1];

        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.voice_variant_preference, null);
        mCategory = (Spinner) root.findViewById(R.id.category);
        mVariant = (Spinner) root.findViewById(R.id.variant);

        bindDialog();

        // Null listeners: the real handlers are wired in onStart() to mirror
        // the old onClick path exactly (see below).
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(root)
                .setTitle(preference.getDialogTitle())
                .setIcon(preference.getDialogIcon());
        return builder.create();
    }

    private void bindDialog() {
        // Cache the indices so they don't get overwritten by the OnItemSelectedListener handlers.
        final int category = mCategoryIndex;
        final int variant = mVariantIndex;

        mCategory.setAdapter(new ResourceIdListAdapter((Activity) getContext(),
                VoiceVariantPreference.getCategories()));
        mCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean mInitializing = true;

            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                mCategoryIndex = position;
                mVariant.setAdapter(new VariantDataListAdapter((Activity) getContext(),
                        VoiceVariantPreference.getVariants(position)));
                if (mInitializing) {
                    int safeVariant = Math.max(0, Math.min(variant,
                            VoiceVariantPreference.getVariants(position).length - 1));
                    mVariantIndex = safeVariant;
                    mVariant.setSelection(safeVariant);
                    mInitializing = false;
                } else {
                    mVariantIndex = 0;
                    mVariant.setSelection(0);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        mVariant.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                mVariantIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });

        mCategory.setSelection(category);
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        if (positive != null) {
            positive.setOnClickListener(v -> onDialogClosed(true));
        }

        if (negative != null) {
            negative.setOnClickListener(v -> dialog.cancel());
        }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (!positiveResult) {
            return;
        }
        VoiceVariantPreference preference = getVariantPreference();
        if (mCategoryIndex < 0 || mCategoryIndex >= VoiceVariantPreference.getCategoryCount()) {
            mCategoryIndex = 0;
        }
        if (mVariantIndex < 0
                || mVariantIndex >= VoiceVariantPreference.getVariants(mCategoryIndex).length) {
            mVariantIndex = 0;
        }
        preference.applySelection(mCategoryIndex, mVariantIndex);
        preference.persistVariant();
        dismiss();
    }
}
