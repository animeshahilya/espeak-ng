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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;


import com.animeshahilya.espeakng.R;

/**
 * Dialog for {@link VoiceVariantPreference}: category/variant spinners with
 * the cascade. Wiring moved verbatim from the framework
 * {@code onCreateDialogView}/{@code onBindDialogView}/{@code onClick}
 * overrides, which have no AndroidX equivalents. Working indices live here
 * so cancelling truly discards the browsed selection.
 */
public class VoiceVariantDialogFragment extends ButtonDialogFragment {
    private Spinner mCategory;
    private Spinner mVariant;

    private int mCategoryIndex = 0;
    private int mVariantIndex = 0;

    public static VoiceVariantDialogFragment newInstance(String key) {
        return withKey(new VoiceVariantDialogFragment(), key);
    }

    private VoiceVariantPreference getVariantPreference() {
        return (VoiceVariantPreference) getPreference();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog stale = staleDialogUnless(VoiceVariantPreference.class);
        if (stale != null) return stale;
        VoiceVariantPreference preference = getVariantPreference();
        int[] selected = preference.getSelectedIndices();
        mCategoryIndex = selected[0];
        mVariantIndex = selected[1];

        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.voice_variant_preference, null);
        mCategory = (Spinner) root.findViewById(R.id.category);
        mVariant = (Spinner) root.findViewById(R.id.variant);

        bindDialog();

        return buildDialog(root);
    }

    private void bindDialog() {
        // Cache the indices so they don't get overwritten by the OnItemSelectedListener handlers.
        final int category = mCategoryIndex;
        final int variant = mVariantIndex;

        Integer[] categories = VoiceVariantPreference.getCategories();
        String[] categoryNames = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            categoryNames[i] = getString(categories[i]);
        }
        mCategory.setAdapter(spinnerAdapter(categoryNames));
        mCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean mInitializing = true;

            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
                mCategoryIndex = position;
                VoiceVariantPreference.VariantData[] variants =
                        VoiceVariantPreference.getVariants(position);
                String[] variantNames = new String[variants.length];
                for (int i = 0; i < variants.length; i++) {
                    variantNames[i] = variants[i].getDisplayName(getContext());
                }
                mVariant.setAdapter(spinnerAdapter(variantNames));
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

    private ArrayAdapter<String> spinnerAdapter(String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
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
