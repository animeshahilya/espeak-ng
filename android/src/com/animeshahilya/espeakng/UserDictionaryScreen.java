/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

package com.animeshahilya.espeakng;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import java.util.ArrayList;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import android.view.ViewParent;
import java.util.List;
import java.util.Locale;

/**
 * The user dictionary editor: rule list with search and category filter,
 * add/edit/delete, import/export and share. Opened from the settings screen.
 */
final class UserDictionaryScreen {
    private UserDictionaryScreen() {
    }

    static void show(final Context context) {
        showDialog(context, "", "all");
    }

    /**
     * Adds a Material outlined text field with a floating label for TalkBack.
     * TextInputEditText (not plain EditText) so the enclosing TextInputLayout
     * owns accessibility: hint, error text and the field role are announced
     * together as one control.
     */
    private static EditText labeledInput(Context context, LinearLayout layout,
                                         String labelText, String hintText, String initial,
                                         int inputType) {
        float density = context.getResources().getDisplayMetrics().density;
        int minTouch = (int) (48 * density + 0.5f);

        // TextInputLayout carries the visible label (floating hint) and names
        // the field to accessibility services, replacing the old standalone
        // label TextView without losing the TalkBack binding. Outlined style
        // matches the search fields in the XML dialog layouts.
        final TextInputLayout field = new TextInputLayout(context, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        field.setHint(labelText);
        final EditText et = new TextInputEditText(context);
        et.setHint(hintText);
        // Never set contentDescription on EditText: TalkBack needs to read user-typed text!
        if (initial != null && !initial.isEmpty()) et.setText(initial);
        if (inputType != 0) et.setInputType(inputType);
        et.setMinimumHeight(minTouch);
        field.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density + 0.5f);
        field.setLayoutParams(lp);
        layout.addView(field);
        return et;
    }

    /**
     * Reports a field error through the wrapping TextInputLayout when there is
     * one (M3 error text under the outline, announced by TalkBack as part of
     * the field), falling back to the legacy EditText error popup otherwise.
     */
    private static void fieldError(EditText et, String message) {
        ViewParent parent = et.getParent();
        if (parent instanceof TextInputLayout) {
            ((TextInputLayout) parent).setError(message);
        } else {
            et.setError(message);
        }
    }

    /** Clears any TextInputLayout / EditText error on {@code et}. */
    private static void fieldErrorClear(EditText et) {
        ViewParent parent = et.getParent();
        if (parent instanceof TextInputLayout) {
            ((TextInputLayout) parent).setError(null);
        } else {
            et.setError(null);
        }
    }

    private static class RuleViewHolder {
        TextView index;
        TextView pattern;
        TextView arrow;
        TextView replacement;
        TextView chipCategory;
        TextView chipMode;
        TextView chipLang;
        TextView chipPhoneme;
    }

    private static String filterValueFor(String[] filterValues, int pos) {
        return (pos >= 0 && pos < filterValues.length) ? filterValues[pos] : "all";
    }

    private static void showDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();

        final View dialogView = View.inflate(context, R.layout.user_dictionary_dialog, null);
        final EditText etSearch = dialogView.findViewById(R.id.dict_search);
        final MaterialAutoCompleteTextView spFilter = dialogView.findViewById(R.id.dict_filter_spinner);
        final ListView lvRules = dialogView.findViewById(R.id.dict_rules_list);
        final TextView tvEmpty = dialogView.findViewById(R.id.dict_empty_view);

        final String[] filterNames = new String[] {
                context.getString(R.string.dict_filter_all),
                context.getString(R.string.dict_filter_main),
                context.getString(R.string.dict_filter_root),
                context.getString(R.string.dict_filter_abbrev),
                context.getString(R.string.dict_filter_character) };
        final String[] filterValues = new String[] {
                "all", UserDictionary.CATEGORY_MAIN,
                UserDictionary.CATEGORY_ROOT, UserDictionary.CATEGORY_ABBREV,
                UserDictionary.CATEGORY_CHARACTER };

        android.widget.ArrayAdapter<String> filterAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, filterNames);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(filterAdapter);

        int sel = 0;
        for (int i = 0; i < filterValues.length; i++) {
            if (filterValues[i].equals(categoryFilter)) { sel = i; break; }
        }
        // Exposed dropdowns show the selection as text (no selected-item
        // position like a Spinner); track the index alongside the field.
        final int[] filterPos = { sel };
        spFilter.setText(filterNames[sel], false);

        final TextView tvCount = dialogView.findViewById(R.id.dict_rules_count);

        final List<Integer> viewToReal = new ArrayList<>();
        final List<UserDictionary> displayedRules = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        final BaseAdapter listAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return displayedRules.size();
            }

            @Override
            public Object getItem(int position) {
                return displayedRules.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RuleViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(context).inflate(R.layout.item_user_dictionary_rule, parent, false);
                    holder = new RuleViewHolder();
                    holder.index = convertView.findViewById(R.id.rule_index);
                    holder.pattern = convertView.findViewById(R.id.rule_pattern);
                    holder.arrow = convertView.findViewById(R.id.rule_arrow);
                    holder.replacement = convertView.findViewById(R.id.rule_replacement);
                    holder.chipCategory = convertView.findViewById(R.id.chip_category);
                    holder.chipMode = convertView.findViewById(R.id.chip_mode);
                    holder.chipLang = convertView.findViewById(R.id.chip_lang);
                    holder.chipPhoneme = convertView.findViewById(R.id.chip_phoneme);
                    convertView.setTag(holder);
                } else {
                    holder = (RuleViewHolder) convertView.getTag();
                }

                UserDictionary r = displayedRules.get(position);
                holder.index.setText(context.getString(R.string.dict_rule_index, position + 1));
                holder.pattern.setText(r.getPattern());
                holder.replacement.setText(r.getReplacement());
                holder.chipCategory.setText(UserDictionary.categoryLabelRes(r.getCategory()));
                final int modeRes = r.isRegex() ? R.string.dict_mode_regex
                        : (r.isWholeWord() ? R.string.dict_mode_word : R.string.dict_mode_substring);
                holder.chipMode.setText(modeRes);

                if (!r.getLanguage().isEmpty()) {
                    holder.chipLang.setVisibility(View.VISIBLE);
                    holder.chipLang.setText(r.getLanguage());
                } else {
                    holder.chipLang.setVisibility(View.GONE);
                }

                if (r.hasPhonemeOverride()) {
                    holder.chipPhoneme.setVisibility(View.VISIBLE);
                    holder.chipPhoneme.setText(context.getString(R.string.dict_rule_phonemes, r.getPhonemes()));
                } else {
                    holder.chipPhoneme.setVisibility(View.GONE);
                }

                String a11y = context.getString(R.string.dict_a11y_rule,
                        position + 1, r.getPattern(), r.getReplacement(),
                        context.getString(UserDictionary.categoryLabelRes(r.getCategory())),
                        context.getString(modeRes));
                if (!r.getLanguage().isEmpty()) {
                    a11y = context.getString(R.string.dict_a11y_rule_lang, a11y, r.getLanguage());
                }
                if (r.hasPhonemeOverride()) {
                    a11y = context.getString(R.string.dict_a11y_rule_phonemes, a11y, r.getPhonemes());
                }
                convertView.setContentDescription(a11y);

                return convertView;
            }
        };
        lvRules.setAdapter(listAdapter);

        final Runnable updateList = () -> {
            String q = etSearch.getText() != null ? etSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT) : "";
            String filter = filterValueFor(filterValues, filterPos[0]);

            viewToReal.clear();
            displayedRules.clear();
            labels.clear();
            for (int i = 0; i < rules.size(); i++) {
                UserDictionary r = rules.get(i);
                if (!"all".equals(filter) && !r.getCategory().equals(filter)) continue;
                if (!q.isEmpty() && !r.getPattern().toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !r.getReplacement().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                viewToReal.add(i);
                displayedRules.add(r);
                final int rowModeRes = r.isRegex() ? R.string.dict_mode_regex
                        : (r.isWholeWord() ? R.string.dict_mode_word : R.string.dict_mode_substring);
                labels.add(viewToReal.size() + ". [" + context.getString(UserDictionary.categoryLabelRes(r.getCategory())) + "] \""
                        + r.getPattern() + "\" → \"" + r.getReplacement() + "\""
                        + ((r.isRegex() || r.isWholeWord()) ? " [" + context.getString(rowModeRes) + "]" : "")
                        + (r.hasPhonemeOverride() ? " [" + context.getString(R.string.dict_label_phoneme_tag) + "]" : "")
                        + (r.getLanguage().isEmpty() ? "" : " [" + r.getLanguage() + "]"));
            }
            listAdapter.notifyDataSetChanged();
            if (tvCount != null) {
                if (viewToReal.size() == rules.size()) {
                    tvCount.setText(context.getResources().getQuantityString(R.plurals.dict_rules_count_all, rules.size(), rules.size()));
                } else {
                    tvCount.setText(context.getResources().getQuantityString(R.plurals.dict_rules_count, rules.size(), viewToReal.size(), rules.size()));
                }
            }
            if (displayedRules.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(rules.isEmpty()
                        ? context.getString(R.string.dict_empty)
                        : context.getString(R.string.dict_no_match));
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        };

        if (searchQuery != null && !searchQuery.isEmpty()) {
            etSearch.setText(searchQuery);
        }
        updateList.run();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateList.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        spFilter.setOnItemClickListener((parent, view, position, id) -> {
            filterPos[0] = position;
            updateList.run();
        });

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dict_title_count,
                        context.getString(R.string.setting_user_dictionary), rules.size()))
                .setView(dialogView)
                .setPositiveButton(R.string.dict_add_rule, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showAddRuleDialog(context, etSearch.getText().toString(),
                                filterValueFor(filterValues, filterPos[0]));
                    }
                })
                .setNeutralButton(R.string.dict_import_export, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showDictionaryImportExportDialog(context, etSearch.getText().toString(),
                                filterValueFor(filterValues, filterPos[0]));
                    }
                })
                .setNegativeButton(R.string.dict_back, null)
                .create();

        lvRules.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < viewToReal.size()) {
                    dialog.dismiss();
                    showRuleActionsDialog(context, etSearch.getText().toString(),
                            filterValueFor(filterValues, filterPos[0]),
                            viewToReal.get(position), labels.get(position));
                }
            }
        });

        dialog.show();
    }

    /** TalkBack-friendly rule actions as a list (Preview / Edit / Delete) + Cancel. */
    private static void showRuleActionsDialog(final Context context, final String searchQuery,
                                              final String categoryFilter, final int realIdx,
                                              final String label) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> current = mgr.getRules();
        if (realIdx < 0 || realIdx >= current.size()) {
            showDialog(context, searchQuery, categoryFilter);
            return;
        }
        final UserDictionary r = current.get(realIdx);
        final String[] actions = new String[] {
                context.getString(R.string.dict_action_preview),
                context.getString(R.string.dict_action_edit),
                context.getString(R.string.dict_action_delete) };
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dict_rule_title, r.getPattern()) + "\n" + label)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            previewText(context, r.getReplacement().isEmpty()
                                    ? r.getPattern() : r.getReplacement());
                            showDialog(context, searchQuery, categoryFilter);
                        } else if (which == 1) {
                            showEditRuleDialog(context, searchQuery, categoryFilter, realIdx);
                        } else {
                            new MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.dict_delete_confirm_title)
                                    .setMessage(context.getString(R.string.dict_delete_confirm_message, r.getPattern()))
                                    .setPositiveButton(R.string.dict_action_delete, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int w) {
                                            mgr.removeRule(realIdx);
                                            Toast.makeText(context, R.string.dict_rule_deleted,
                                                    Toast.LENGTH_SHORT).show();
                                            showDialog(context, searchQuery, categoryFilter);
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int w) {
                                            showDialog(context, searchQuery, categoryFilter);
                                        }
                                    })
                                    .show();
                        }
                    }
                })
                .setNegativeButton(R.string.dict_back, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showDialog(context, searchQuery, categoryFilter);
                    }
                })
                .show();
    }

    private static void showDictionaryImportExportDialog(final Context context, final String searchQuery, final String categoryFilter) {
        final CharSequence[] options = new CharSequence[] {
                context.getString(R.string.dict_share),
                context.getString(R.string.dict_export_file),
                context.getString(R.string.dict_import_file)
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dict_import_export_title)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            shareDictionary(context);
                        } else if (which == 1) {
                            if (context instanceof Activity) {
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("text/plain");
                                i.putExtra(Intent.EXTRA_TITLE, "espeak_dictionary.dic");
                                ((Activity) context).startActivityForResult(i, TtsSettingsActivity.REQUEST_CODE_EXPORT_DICT);
                            }
                        } else if (which == 2) {
                            if (context instanceof Activity) {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("*/*");
                                ((Activity) context).startActivityForResult(i, TtsSettingsActivity.REQUEST_CODE_IMPORT_DICT);
                            }
                        }
                    }
                })
                .setNegativeButton(R.string.dict_back, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showDialog(context, searchQuery, categoryFilter);
                    }
                })
                .show();
    }

    private static void shareDictionary(final Context context) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final List<UserDictionary> rules = mgr.getRules();
        if (rules == null || rules.isEmpty()) {
            Toast.makeText(context, R.string.dict_share_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, R.string.dict_share_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dir = new File(context.getCacheDir(), "shared_dictionaries");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File shareFile = new File(dir, "espeak_user_dictionary.json");
                try (FileOutputStream fos = new FileOutputStream(shareFile);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    JSONArray arr = new JSONArray();
                    for (UserDictionary r : rules) {
                        arr.put(r.toJson());
                    }
                    osw.write(arr.toString(2));
                    osw.flush();
                    fos.getFD().sync();
                }

                final Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", shareFile);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (TtsSettingsActivity.isGone(context)) return;
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/json");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.dict_share_subject));
                    shareIntent.putExtra(Intent.EXTRA_TEXT,
                            context.getResources().getQuantityString(
                                    R.plurals.dict_share_text,
                                    rules.size(), rules.size()));
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(Intent.createChooser(shareIntent,
                            context.getString(R.string.dict_share)));
                });
            } catch (Exception e) {
                Log.e(TtsSettingsActivity.TAG, "Failed to share user dictionary", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (TtsSettingsActivity.isGone(context)) return;
                    Toast.makeText(context, R.string.dict_share_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        }, "dict-share").start();
    }

    private static void previewText(final Context context, final String text) {
        TtsSettingsActivity.speakPreview(context, text, "dict_preview");
    }

    private static void showAddRuleDialog(final Context context) {
        showAddRuleDialog(context, "", "all");
    }

    private static void showAddRuleDialog(final Context context, final String searchQuery, final String categoryFilter) {
        showEditRuleDialog(context, searchQuery, categoryFilter, -1);
    }

    private static void showEditRuleDialog(final Context context, final String searchQuery,
                                           final String categoryFilter, final int editIndex) {
        final UserDictionaryManager mgr = UserDictionaryManager.getInstance(context);
        final UserDictionary existing = (editIndex >= 0 && editIndex < mgr.getRules().size())
                ? mgr.getRules().get(editIndex) : null;

        float density = context.getResources().getDisplayMetrics().density;
        int minTouch = (int) (48 * density + 0.5f);
        int padH = (int) (20 * density + 0.5f);
        int padV = (int) (12 * density + 0.5f);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, padV, padH, padV);

        final EditText etPattern = labeledInput(context, layout,
                context.getString(R.string.dict_label_pattern),
                context.getString(R.string.dict_hint_pattern),
                existing != null ? existing.getPattern() : null,
                android.text.InputType.TYPE_CLASS_TEXT);
        final EditText etReplacement = labeledInput(context, layout,
                context.getString(R.string.dict_label_replacement),
                context.getString(R.string.dict_hint_replacement),
                existing != null ? existing.getReplacement() : null,
                android.text.InputType.TYPE_CLASS_TEXT);
        final EditText etPhonemes = labeledInput(context, layout,
                context.getString(R.string.dict_label_phonemes),
                context.getString(R.string.dict_hint_phonemes),
                existing != null ? existing.getPhonemes() : null,
                android.text.InputType.TYPE_CLASS_TEXT);

        // Position <-> category string, in the same order as the dropdown's
        // own entries below (Main, Root, Abbrev, Character) - one array driving
        // both directions instead of two hand-written ternary chains.
        final String[] editCategoryValues = {UserDictionary.CATEGORY_MAIN, UserDictionary.CATEGORY_ROOT,
                UserDictionary.CATEGORY_ABBREV, UserDictionary.CATEGORY_CHARACTER};
        String preCat = existing != null ? existing.getCategory() : categoryFilter;
        int preCatIndex = java.util.Arrays.asList(editCategoryValues).indexOf(preCat);
        if (preCatIndex < 0) preCatIndex = 0;

        // Category picker as an M3 exposed dropdown, matching the voice-variant
        // and dictionary-filter fields: floating hint names it for TalkBack
        // (no separate label TextView + labelFor pair to keep in sync).
        final View categoryField = LayoutInflater.from(context)
                .inflate(R.layout.field_exposed_dropdown, layout, false);
        final TextInputLayout categoryLayout = categoryField.findViewById(R.id.dropdown_layout);
        categoryLayout.setHint(context.getString(R.string.dict_label_category));
        final MaterialAutoCompleteTextView spCategory = categoryField.findViewById(R.id.dropdown_field);
        android.widget.ArrayAdapter<String> catAdapter = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item,
                new String[]{context.getString(R.string.dict_filter_main),
                        context.getString(R.string.dict_filter_root),
                        context.getString(R.string.dict_filter_abbrev),
                        context.getString(R.string.dict_filter_character)});
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdapter);
        spCategory.setText(catAdapter.getItem(preCatIndex), false);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.bottomMargin = (int) (8 * density + 0.5f);
        categoryField.setLayoutParams(spLp);
        layout.addView(categoryField);

        final CheckBox cbWholeWord = new CheckBox(context);
        cbWholeWord.setText(R.string.dict_whole_word);
        cbWholeWord.setContentDescription(context.getString(R.string.dict_whole_word) + ". "
                + context.getString(R.string.dict_whole_word_summary));
        cbWholeWord.setChecked(existing != null ? existing.isWholeWord() : true);
        cbWholeWord.setMinimumHeight(minTouch);
        layout.addView(cbWholeWord);

        final CheckBox cbCaseSensitive = new CheckBox(context);
        cbCaseSensitive.setText(R.string.dict_case_sensitive);
        cbCaseSensitive.setContentDescription(context.getString(R.string.dict_case_sensitive));
        cbCaseSensitive.setChecked(existing != null && existing.isCaseSensitive());
        cbCaseSensitive.setMinimumHeight(minTouch);
        layout.addView(cbCaseSensitive);

        final CheckBox cbRegex = new CheckBox(context);
        cbRegex.setText(R.string.dict_regex);
        cbRegex.setContentDescription(context.getString(R.string.dict_regex) + ". "
                + context.getString(R.string.dict_regex_summary));
        cbRegex.setChecked(existing != null && existing.isRegex());
        cbRegex.setMinimumHeight(minTouch);
        layout.addView(cbRegex);

        final EditText etLanguage = labeledInput(context, layout,
                context.getString(R.string.dict_label_language),
                context.getString(R.string.dict_hint_language),
                existing != null ? existing.getLanguage() : null,
                android.text.InputType.TYPE_CLASS_TEXT);

        final String[] chosenCategory = new String[]{editCategoryValues[preCatIndex]};
        spCategory.setOnItemClickListener((parent, view, pos, id) -> {
            chosenCategory[0] = (pos >= 0 && pos < editCategoryValues.length)
                    ? editCategoryValues[pos] : UserDictionary.CATEGORY_MAIN;
            if (pos == 1) cbWholeWord.setChecked(false);
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(existing != null ? R.string.dict_edit_title : R.string.dict_add_title)
                .setView(scrollView)
                .setPositiveButton(R.string.dict_save, null)
                .setNeutralButton(R.string.dict_preview, null)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showDialog(context, searchQuery, categoryFilter);
                    }
                })
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pattern = etPattern.getText().toString().trim();
                String replacement = etReplacement.getText().toString().trim();
                String language = etLanguage.getText().toString().trim();
                String phonemes = etPhonemes.getText().toString().trim();

                if (pattern.isEmpty()) {
                    fieldError(etPattern, context.getString(R.string.dict_error_empty_pattern));
                    etPattern.requestFocus();
                    return;
                }

                fieldErrorClear(etPattern);
                fieldErrorClear(etPhonemes);

                if (cbRegex.isChecked()) {
                    try {
                        java.util.regex.Pattern.compile(pattern);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        fieldError(etPattern, context.getString(R.string.dict_error_invalid_regex));
                        etPattern.requestFocus();
                        return;
                    }
                }

                if (phonemes.contains("[[") || phonemes.contains("]]")) {
                    fieldError(etPhonemes, context.getString(R.string.dict_error_invalid_phonemes));
                    etPhonemes.requestFocus();
                    return;
                }

                UserDictionary rule = new UserDictionary(
                        pattern,
                        replacement,
                        cbCaseSensitive.isChecked(),
                        cbRegex.isChecked(),
                        cbWholeWord.isChecked(),
                        language,
                        chosenCategory[0],
                        phonemes
                );

                if (!rule.isValid()) {
                    fieldError(etPattern, context.getString(R.string.dict_error_invalid_regex));
                    etPattern.requestFocus();
                    return;
                }

                dialog.dismiss();

                if (existing != null) {
                    UserDictionaryManager.getInstance(context).setRule(editIndex, rule);
                    Toast.makeText(context, R.string.dict_rule_saved, Toast.LENGTH_SHORT).show();
                } else {
                    UserDictionaryManager.getInstance(context).addRule(rule);
                }

                // Live audio preview: hear the new pronunciation immediately.
                previewText(context, replacement.isEmpty() ? pattern : replacement);
                showDialog(context, searchQuery, categoryFilter);
            }
        });

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String replacement = etReplacement.getText().toString().trim();
                String pattern = etPattern.getText().toString().trim();
                previewText(context, replacement.isEmpty() ? pattern : replacement);
            }
        });
    }
}
