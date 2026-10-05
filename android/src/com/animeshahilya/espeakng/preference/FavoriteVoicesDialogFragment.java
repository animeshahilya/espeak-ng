/*
 * Copyright (C) 2026 eSpeak NG contributors
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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceFragmentCompat;

import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.LanguageSettings;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Favorite voices: pins languages to the top of the system voice list.
 * A plain dialog (not a preference dialog) opened from the "Favorite
 * voices" action row. Candidates are the currently selected languages, so
 * a favorite can never name a voice the language filter hides; stale
 * favorites for removed voices are dropped on save, never an error.
 */
public class FavoriteVoicesDialogFragment extends DialogFragment {
    private static final String ARG_LABELS = "labels";
    private static final String ARG_VALUES = "values";

    private ListView mListView;
    private ArrayAdapter<String> mAdapter;
    private final List<String> mValues = new ArrayList<String>();

    /**
     * Opens the dialog for the languages currently offered by {@code voices}.
     * Each entry reads "label" and persists its voice identifier.
     */
    public static void show(Context context, List<com.animeshahilya.espeakng.Voice> voices) {
        if (!(context instanceof AppCompatActivity) || voices == null) {
            return;
        }
        final AppCompatActivity activity = (AppCompatActivity) context;
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<String> values = new ArrayList<String>();
        for (com.animeshahilya.espeakng.Voice voice : voices) {
            if (voice == null) continue;
            final String value = voice.toString();
            if (value == null || value.isEmpty()) continue;
            // One row per language: several voices can share an identifier,
            // and pinning is per language, not per engine voice object.
            if (values.contains(value)) continue;
            labels.add(voice.name != null ? voice.name : value);
            values.add(value);
        }
        final FavoriteVoicesDialogFragment dialog = new FavoriteVoicesDialogFragment();
        final Bundle args = new Bundle(2);
        args.putStringArrayList(ARG_LABELS, labels);
        args.putStringArrayList(ARG_VALUES, values);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), "favorites");
    }

    private static SharedPreferences prefsOf(Context context) {
        final Context storage = EspeakApp.requireStorageContext(context);
        return PreferenceManager.getDefaultSharedPreferences(storage);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Bundle args = getArguments();
        final ArrayList<String> labels = args != null
                ? args.getStringArrayList(ARG_LABELS) : null;
        final ArrayList<String> values = args != null
                ? args.getStringArrayList(ARG_VALUES) : null;
        mValues.clear();
        if (values != null) {
            mValues.addAll(values);
        }
        // Favorites for voices no longer offered get no row, so saving drops them.
        final Set<String> favorites = getContext() != null
                ? LanguageSettings.getFavoriteVoices(prefsOf(getContext())) : new HashSet<String>();

        final View root = LayoutInflater.from(getContext())
                .inflate(R.layout.favorite_voices_dialog, null);
        mListView = root.findViewById(R.id.favorite_voices_list);
        final ArrayList<String> rows = labels != null ? labels : new ArrayList<String>();
        mAdapter = new ArrayAdapter<String>(requireContext(), R.layout.item_language, rows);
        mListView.setAdapter(mAdapter);
        for (int i = 0; i < mValues.size() && i < rows.size(); i++) {
            mListView.setItemChecked(i, favorites.contains(mValues.get(i)));
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_favorite_voices)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, which) -> save())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> TtsSettingsActivity.markAlertTitleHeading(dialog));
        return dialog;
    }

    private void save() {
        if (getContext() == null) {
            return;
        }
        // Read from the list itself: it keeps its ticks across a rotation,
        // when this fragment's own state starts over from the saved favorites.
        final Set<String> checked = new HashSet<String>();
        for (int i = 0; i < mValues.size() && i < mListView.getCount(); i++) {
            if (mListView.isItemChecked(i)) {
                checked.add(mValues.get(i));
            }
        }
        prefsOf(getContext()).edit()
                .putStringSet(LanguageSettings.PREF_FAVORITE_VOICES, checked)
                .apply();
        // The engine reorders its voice list on this broadcast path: the
        // service rebuilds (and re-sorts) on the favorites key change.
        refreshActionSummary();
    }

    private void refreshActionSummary() {
        if (getActivity() == null) {
            return;
        }
        final Fragment f = getActivity().getSupportFragmentManager()
                .findFragmentById(android.R.id.content);
        if (!(f instanceof PreferenceFragmentCompat)) {
            return;
        }
        final PreferenceFragmentCompat fragment = (PreferenceFragmentCompat) f;
        if (fragment.getPreferenceScreen() != null && getContext() != null) {
            TtsSettingsActivity.refreshFavoritesSummary(
                    fragment.getPreferenceScreen(), prefsOf(getContext()));
        }
    }

}
