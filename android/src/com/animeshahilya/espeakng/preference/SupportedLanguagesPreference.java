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

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ListView;
import android.widget.Toast;

import com.animeshahilya.espeakng.LanguageSettings;
import com.animeshahilya.espeakng.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-select preference with a custom dialog that includes real-time
 * search filtering and explicit Select All / Deselect All buttons with TalkBack announcements.
 */
public class SupportedLanguagesPreference extends MultiSelectListPreference {
    private CharSequence[] mDialogEntryValues;
    private final Set<String> mCurrentSelected = new HashSet<String>();

    private View mDialogView;
    private ListView mListView;
    private EditText mSearchInput;
    private Button mButtonSelectAll;
    private Button mButtonDeselectAll;
    private ArrayAdapter<LangEntry> mAdapter;
    private final List<LangEntry> mAllEntries = new ArrayList<>();
    private int mEntryCount = 0;

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

    public SupportedLanguagesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setKey(LanguageSettings.PREF_SUPPORTED_LANGUAGES);
        setPersistent(true);
    }

    public SupportedLanguagesPreference(Context context) {
        this(context, null);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, null);

        if (getEntries() == null || getEntryValues() == null) {
            throw new IllegalStateException("SupportedLanguagesPreference requires entries and entryValues.");
        }

        mDialogEntryValues = getEntryValues();
        mEntryCount = mDialogEntryValues.length;

        mCurrentSelected.clear();
        final Set<String> values = getValues();
        if (values != null) {
            mCurrentSelected.addAll(values);
        }

        mAllEntries.clear();
        CharSequence[] entries = getEntries();
        for (int i = 0; i < entries.length && i < mDialogEntryValues.length; i++) {
            mAllEntries.add(new LangEntry(entries[i], mDialogEntryValues[i].toString()));
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        mDialogView = inflater.inflate(R.layout.supported_languages_dialog, null);

        mSearchInput = mDialogView.findViewById(R.id.languages_search);
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

        if (mSearchInput != null) {
            mSearchInput.addTextChangedListener(new TextWatcher() {
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

        mButtonSelectAll = mDialogView.findViewById(R.id.button_select_all);
        mButtonDeselectAll = mDialogView.findViewById(R.id.button_deselect_all);

        mButtonSelectAll.setOnClickListener(v -> {
            setAll(true);
            v.announceForAccessibility(getContext().getString(R.string.languages_all_selected));
        });
        mButtonDeselectAll.setOnClickListener(v -> {
            setAll(false);
            v.announceForAccessibility(getContext().getString(R.string.languages_all_deselected));
        });

        builder.setView(mDialogView);
        builder.setTitle(getDialogTitle());
        builder.setIcon(getDialogIcon());
        builder.setCancelable(true);
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

    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);

        final AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        // Wire default dialog buttons.
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        if (positive != null) {
            positive.setOnClickListener(v -> {
                Set<String> selections = collectSelections();
                if (selections.isEmpty()) {
                    Toast.makeText(getContext(), R.string.espeak_supported_languages_guard, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (callChangeListener(selections)) {
                    setValues(selections);
                }
                dialog.dismiss();
            });
        }

        if (negative != null) {
            negative.setOnClickListener(v -> dialog.cancel());
        }
    }

    // "All languages" is stored as the absence of the key, so that TtsService
    // exposes every voice, including ones added by a later update.
    @Override
    public boolean persistStringSet(Set<String> values) {
        if (!shouldPersist()) return false;
        int total = mEntryCount > 0 ? mEntryCount :
                (getEntryValues() != null ? getEntryValues().length : 0);

        android.content.SharedPreferences.Editor editor = getSharedPreferences().edit();

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

    @Override
    public Set<String> getPersistedStringSet(Set<String> defaultReturnValue) {
        if (!shouldPersist()) return defaultReturnValue;
        Set<String> stored = getSharedPreferences().getStringSet(getKey(), defaultReturnValue);
        return (stored == null) ? defaultReturnValue : new HashSet<String>(stored);
    }
}
