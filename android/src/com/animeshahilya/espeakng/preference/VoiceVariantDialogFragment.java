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

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import com.animeshahilya.espeakng.R;

/**
 * Dialog for {@link VoiceVariantPreference}: category/variant pickers with
 * the cascade, presented as M3 exposed dropdowns (outlined fields with a
 * floating label) instead of framework Spinners so the control matches the
 * rest of the settings UI and TalkBack announces it as a dropdown.
 * Working indices live here so cancelling truly discards the browsed
 * selection.
 */
public class VoiceVariantDialogFragment extends ButtonDialogFragment {
    private MaterialAutoCompleteTextView mCategory;
    private MaterialAutoCompleteTextView mVariant;

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
        mCategory = root.findViewById(R.id.category);
        mVariant = root.findViewById(R.id.variant);

        bindDialog();

        return buildDialog(root);
    }

    private void bindDialog() {
        final int category = mCategoryIndex;
        final int variant = mVariantIndex;

        Integer[] categories = VoiceVariantPreference.getCategories();
        String[] categoryNames = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            categoryNames[i] = getString(categories[i]);
        }
        mCategory.setAdapter(spinnerAdapter(categoryNames));
        int safeCategory = Math.max(0, Math.min(category, categoryNames.length - 1));
        mCategoryIndex = safeCategory;
        // setText(..., false): filter=false so the popup never auto-opens
        // while the dialog is still being built.
        mCategory.setText(categoryNames[safeCategory], false);

        // Selecting a category rebuilds the variant list and resets the
        // variant to its first entry - same cascade the Spinner had, minus
        // the OnItemSelectedListener initialization dance (no listener fires
        // for setText, so the initial variant is filled in explicitly below).
        mCategory.setOnItemClickListener((parent, view, position, id) -> {
            mCategoryIndex = position;
            rebuildVariants(position, 0);
        });

        mVariant.setOnItemClickListener((parent, view, position, id) -> {
            mVariantIndex = position;
        });

        rebuildVariants(safeCategory, variant);
    }

    /** Fills the variant dropdown for {@code categoryIndex} and selects {@code preferredVariant} (clamped). */
    private void rebuildVariants(int categoryIndex, int preferredVariant) {
        VoiceVariantPreference.VariantData[] variants =
                VoiceVariantPreference.getVariants(categoryIndex);
        String[] variantNames = new String[variants.length];
        for (int i = 0; i < variants.length; i++) {
            variantNames[i] = variants[i].getDisplayName(getContext());
        }
        mVariant.setAdapter(spinnerAdapter(variantNames));
        int safeVariant = Math.max(0, Math.min(preferredVariant, variantNames.length - 1));
        mVariantIndex = safeVariant;
        mVariant.setText(variantNames[safeVariant], false);
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
