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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.preference.PreferenceDialogFragmentCompat;

import com.animeshahilya.espeakng.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog for {@link SupportedLanguagesPreference}: real-time search
 * filtering, explicit Select All / Deselect All buttons with TalkBack
 * announcements, and an empty-selection guard. Logic moved verbatim from
 * the framework {@code onPrepareDialogBuilder}/{@code showDialog}
 * overrides, which have no AndroidX equivalents.
 */
public class SupportedLanguagesDialogFragment extends PreferenceDialogFragmentCompat {
    public static class LangEntry {
        public final CharSequence label;
        public final String value;

        public LangEntry(CharSequence label, String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label != null ? label.toString() : "";
        }
    }

    private CharSequence[] mDialogEntryValues;
    private final Set<String> mCurrentSelected = new HashSet<String>();

    private View mDialogView;
    private ListView mListView;
    private ArrayAdapter<LangEntry> mAdapter;
    private final List<LangEntry> mAllEntries = new ArrayList<>();

    public static SupportedLanguagesDialogFragment newInstance(String key) {
        SupportedLanguagesDialogFragment fragment = new SupportedLanguagesDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private SupportedLanguagesPreference getSupportedPreference() {
        return (SupportedLanguagesPreference) getPreference();
    }

    // announceForAccessibility was deprecated in Android 16; these one-shot
    // polite announcements for explicit user actions are the non-disruptive
    // case (see SeekBarPreference.onClick).
    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        SupportedLanguagesPreference preference = getSupportedPreference();

        if (preference.getEntries() == null || preference.getEntryValues() == null) {
            throw new IllegalStateException("SupportedLanguagesPreference requires entries and entryValues.");
        }

        mDialogEntryValues = preference.getEntryValues();

        mCurrentSelected.clear();
        final Set<String> values = preference.getValues();
        if (values != null) {
            mCurrentSelected.addAll(values);
        }

        mAllEntries.clear();
        CharSequence[] entries = preference.getEntries();
        for (int i = 0; i < entries.length && i < mDialogEntryValues.length; i++) {
            mAllEntries.add(new LangEntry(entries[i], mDialogEntryValues[i].toString()));
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        mDialogView = inflater.inflate(R.layout.supported_languages_dialog, null);

        EditText searchInput = mDialogView.findViewById(R.id.languages_search);
        mListView = mDialogView.findViewById(R.id.supported_languages_list);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        mAdapter = new ArrayAdapter<LangEntry>(
                getContext(),
                android.R.layout.simple_list_item_multiple_choice,
                new ArrayList<>(mAllEntries)) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                LangEntry item = getItem(position);
                if (item != null) {
                    mListView.setItemChecked(position, mCurrentSelected.contains(item.value));
                }
                return view;
            }
        };
        mListView.setAdapter(mAdapter);

        mListView.setOnItemClickListener((parent, view, position, id) -> {
            LangEntry item = mAdapter.getItem(position);
            if (item != null) {
                if (mListView.isItemChecked(position)) {
                    mCurrentSelected.add(item.value);
                } else {
                    mCurrentSelected.remove(item.value);
                }
            }
        });

        syncListViewCheckedState();

        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mAdapter.getFilter().filter(s, new Filter.FilterListener() {
                        @Override
                        public void onFilterComplete(int count) {
                            syncListViewCheckedState();
                        }
                    });
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        Button buttonSelectAll = mDialogView.findViewById(R.id.button_select_all);
        Button buttonDeselectAll = mDialogView.findViewById(R.id.button_deselect_all);

        buttonSelectAll.setOnClickListener(v -> {
            setAll(true);
            v.announceForAccessibility(getContext().getString(R.string.languages_all_selected));
        });
        buttonDeselectAll.setOnClickListener(v -> {
            setAll(false);
            v.announceForAccessibility(getContext().getString(R.string.languages_all_deselected));
        });

        // Null listeners: the real handlers are wired in onStart() so the
        // empty-selection guard can keep the dialog open (an AlertDialog
        // button listener auto-dismisses).
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(mDialogView)
                .setTitle(preference.getDialogTitle())
                .setIcon(preference.getDialogIcon())
                .setCancelable(true);
        return builder.create();
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
        Set<String> selections = collectSelections();
        if (selections.isEmpty()) {
            Toast.makeText(getContext(), R.string.espeak_supported_languages_guard, Toast.LENGTH_SHORT).show();
            return;
        }
        SupportedLanguagesPreference preference = getSupportedPreference();
        if (preference.callChangeListener(selections)) {
            preference.setValues(selections);
        }
        dismiss();
    }

    private void syncListViewCheckedState() {
        if (mListView == null || mAdapter == null) return;
        for (int i = 0; i < mAdapter.getCount(); i++) {
            LangEntry item = mAdapter.getItem(i);
            if (item != null) {
                mListView.setItemChecked(i, mCurrentSelected.contains(item.value));
            }
        }
    }

    private void setAll(boolean checked) {
        if (checked) {
            for (LangEntry entry : mAllEntries) {
                mCurrentSelected.add(entry.value);
            }
        } else {
            mCurrentSelected.clear();
        }
        syncListViewCheckedState();
    }

    private Set<String> collectSelections() {
        return new HashSet<>(mCurrentSelected);
    }
}
