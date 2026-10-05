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
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.TtsSettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dialog for {@link SupportedLanguagesPreference}: real-time search
 * filtering, explicit Select All / Deselect All buttons with TalkBack
 * announcements, and an empty-selection guard. Logic moved verbatim from
 * the framework {@code onPrepareDialogBuilder}/{@code showDialog}
 * overrides, which have no AndroidX equivalents.
 */
public class SupportedLanguagesDialogFragment extends ButtonDialogFragment {
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
    private androidx.core.view.AccessibilityDelegateCompat mItemDelegate;
    private final List<LangEntry> mAllEntries = new ArrayList<>();

    public static SupportedLanguagesDialogFragment newInstance(String key) {
        return withKey(new SupportedLanguagesDialogFragment(), key);
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
        Dialog stale = staleDialogUnless(SupportedLanguagesPreference.class);
        if (stale != null) return stale;
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
                R.layout.item_language,
                new ArrayList<>(mAllEntries)) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                LangEntry item = getItem(position);
                if (item != null) {
                    mListView.setItemChecked(position, mCurrentSelected.contains(item.value));
                    // Update checked state for the custom selector drawable
                    view.setActivated(mCurrentSelected.contains(item.value));
                    androidx.core.view.ViewCompat.setAccessibilityDelegate(view, mItemDelegate);
                }
                return view;
            }
        };
        mListView.setAdapter(mAdapter);
        // A row with its own delegate doesn't get ListView's, which is what
        // makes a row clickable for TalkBack and runs its actions; so this one
        // does both, plus naming the touch-and-hold sample.
        mItemDelegate = new androidx.core.view.AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClickable(true);
                info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
                info.addAction(new androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        getContext().getString(R.string.piper_play_sample)));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                final int position = mListView.getPositionForView(host);
                if (position != ListView.INVALID_POSITION) {
                    if (action == androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) {
                        return mListView.performItemClick(host, position, mAdapter.getItemId(position));
                    }
                    if (action == androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
                        final LangEntry item = mAdapter.getItem(position);
                        if (item != null) {
                            playLanguageSample(preference, item);
                            return true;
                        }
                    }
                }
                return super.performAccessibilityAction(host, action, args);
            }
        };

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

        // Touch-and-hold speaks the engine's own sample text for that
        // language, through that language -- not the current settings voice,
        // which would make every sample sound identical. TalkBack users
        // discover it through the hint row above the list.
        final SupportedLanguagesPreference sampledPreference = preference;
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            LangEntry item = mAdapter.getItem(position);
            if (item != null && getContext() != null) {
                // Haptic first: the sample itself arrives after TTS init, so
                // the buzz is the immediate "your hold registered" feedback,
                // matching the rotary-encoder detent pattern elsewhere.
                try {
                    view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                } catch (Exception ignored) {
                }
                playLanguageSample(sampledPreference, item);
                return true;
            }
            return false;
        });

        syncListViewCheckedState();

        final TextView countView = mDialogView.findViewById(R.id.languages_count);
        final View emptyView = mDialogView.findViewById(R.id.languages_empty_view);
        if (countView != null && getContext() != null) {
            countView.setText(getContext().getResources().getQuantityString(R.plurals.languages_count_all, mAllEntries.size(), mAllEntries.size()));
        }
        // The count badge is a live region: update it once typing pauses,
        // not per keystroke, so "h-i-n-d-i" does not chatter five times.
        final Handler countHandler = new Handler(Looper.getMainLooper());
        final Runnable[] pendingCount = new Runnable[1];

        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mAdapter.getFilter().filter(s, new Filter.FilterListener() {
                        @Override
                        public void onFilterComplete(int count) {
                            syncListViewCheckedState();
                            if (countView == null || getContext() == null) {
                                return;
                            }
                            if (pendingCount[0] != null) {
                                countHandler.removeCallbacks(pendingCount[0]);
                            }
                            final int matchCount = count;
                            pendingCount[0] = new Runnable() {
                                @Override public void run() {
                                    if (getContext() == null) {
                                        return;
                                    }
                                    countView.setText(getContext().getResources().getQuantityString(
                                            R.plurals.languages_count, mAllEntries.size(),
                                            matchCount, mAllEntries.size()));
                                    if (emptyView != null) {
                                        emptyView.setVisibility(matchCount == 0 ? View.VISIBLE : View.GONE);
                                        mListView.setVisibility(matchCount == 0 ? View.GONE : View.VISIBLE);
                                    }
                                }
                            };
                            countHandler.postDelayed(pendingCount[0], 300);
                        }
                    });
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        Button buttonSelectAll = mDialogView.findViewById(R.id.button_select_all);
        Button buttonDeselectAll = mDialogView.findViewById(R.id.button_deselect_all);

        // Bulk selection must reach TalkBack through the live count region
        // (languages_count): the checkboxes change en masse without individual
        // state announcements, and the old announceForAccessibility-only path
        // left the visible count stale. Selection summary uses the same plural
        // the preference row itself shows.
        if (buttonSelectAll != null) {
            buttonSelectAll.setOnClickListener(v -> {
                setAll(true);
                if (countView != null && getContext() != null) {
                    countView.setText(getContext().getResources().getQuantityString(
                            R.plurals.espeak_supported_languages_summary,
                            mAllEntries.size(), mAllEntries.size(), mAllEntries.size()));
                }
            });
        }
        if (buttonDeselectAll != null) {
            buttonDeselectAll.setOnClickListener(v -> {
                setAll(false);
                if (countView != null && getContext() != null) {
                    countView.setText(getContext().getResources().getQuantityString(
                            R.plurals.espeak_supported_languages_summary,
                            mAllEntries.size(), 0, mAllEntries.size()));
                }
            });
        }

        return buildDialog(mDialogView);
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

    /**
     * Speaks the engine's sample text for one language, in that language.
     * Falls back to the language label when no locale is mapped (e.g. a
     * voice added by an update before the map was rebuilt).
     */
    private void playLanguageSample(SupportedLanguagesPreference preference, LangEntry item) {
        if (getContext() == null) {
            return;
        }
        Locale locale = preference != null ? preference.getLocaleFor(item.value) : null;
        final String label = item.label != null ? item.label.toString() : item.value;
        final String sample;
        try {
            sample = (locale != null)
                    ? SpeechSynthesis.getSampleText(getContext(), locale)
                    : label;
        } catch (Exception e) {
            return;
        }
        Toast.makeText(getContext(),
                getContext().getString(R.string.language_sample_playing, label),
                Toast.LENGTH_SHORT).show();
        TtsSettingsActivity.speakPreviewInLanguage(getContext(), sample,
                locale != null ? locale : Locale.getDefault(), "language_sample");
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
