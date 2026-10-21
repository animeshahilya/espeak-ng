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

package com.animeshahilya.espeakng.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.util.TolerantPreferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Presets: named voice setups ("Fast navigation", "Relaxed reading") kept in
 * the app and switched in one tap - from Backup and about -> Presets, the
 * Voice preset Quick Settings tile ({@link VoicePresetTile}) or Tasker
 * ({@code tts.SettingsReceiver}, extra {@code preset}). Each holds what a
 * {@link VoiceProfile} file holds. A preset is "in use" when the settings
 * match it, so the label never goes stale after a manual change.
 */
public final class VoicePresets {
    private static final String TAG = "VoicePresets";
    /** JSON object, preset name -> its settings, in the order they were saved. */
    public static final String PREF_PRESETS = "espeak_presets";
    static final int MAX_NAME = 40;
    private static final String KEY_CATEGORY = "category_preset_list";
    private static final String KEY_SCREEN = "sub_presets";

    private VoicePresets() {
    }

    private static JSONObject all(SharedPreferences prefs) {
        try {
            final String raw = TolerantPreferences.of(prefs).getString(PREF_PRESETS, null);
            return raw != null ? new JSONObject(raw) : new JSONObject();
        } catch (Exception e) {
            Log.w(TAG, "Unreadable presets", e);
            return new JSONObject();
        }
    }

    /** The preset names, in the order they were saved. */
    public static List<String> names(SharedPreferences prefs) {
        final List<String> names = new ArrayList<>();
        for (Iterator<String> it = all(prefs).keys(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    /** Saves the current settings under {@code name} (replacing one of that name). */
    static void save(SharedPreferences prefs, String name) throws Exception {
        final JSONObject presets = all(prefs);
        presets.put(name, VoiceProfile.capture(prefs));
        prefs.edit().putString(PREF_PRESETS, presets.toString()).apply();
    }

    static void delete(SharedPreferences prefs, String name) {
        final JSONObject presets = all(prefs);
        presets.remove(name);
        prefs.edit().putString(PREF_PRESETS, presets.toString()).apply();
    }

    /** Switches to the preset; false when there is none of that name. */
    public static boolean use(SharedPreferences prefs, String name) {
        final JSONObject preset = name != null ? all(prefs).optJSONObject(name) : null;
        return preset != null && VoiceProfile.apply(prefs, preset);
    }

    /** The preset the settings match now, or null. */
    public static String current(SharedPreferences prefs) {
        final JSONObject presets = all(prefs);
        final JSONObject now;
        try {
            now = VoiceProfile.capture(prefs);
        } catch (Exception e) {
            return null;
        }
        for (Iterator<String> it = presets.keys(); it.hasNext(); ) {
            final String name = it.next();
            if (matches(now, presets.optJSONObject(name))) {
                return name;
            }
        }
        return null;
    }

    private static boolean matches(JSONObject now, JSONObject preset) {
        if (preset == null) {
            return false;
        }
        for (Iterator<String> it = preset.keys(); it.hasNext(); ) {
            final String key = it.next();
            final boolean defaultThen = preset.isNull(key);
            final boolean defaultNow = now.isNull(key);
            if (defaultThen != defaultNow
                    || (!defaultThen && !preset.optString(key).equals(now.optString(key)))) {
                return false;
            }
        }
        return true;
    }

    /** Switches to the preset after the current one (the first if none matches); its name, or null. */
    public static String next(SharedPreferences prefs) {
        final List<String> names = names(prefs);
        if (names.isEmpty()) {
            return null;
        }
        final int i = names.indexOf(current(prefs));
        final String name = names.get((i + 1) % names.size());
        return use(prefs, name) ? name : null;
    }

    /**
     * Fills Backup and about -> Presets: one row per preset, then "Save
     * current settings". Refilled when the page opens (the tile or Tasker
     * may have switched presets meanwhile).
     */
    public static void configure(Context context, PreferenceScreen screen, SharedPreferences prefs) {
        final Preference page = screen.findPreference(KEY_SCREEN);
        if (page != null) {
            page.setOnPreferenceClickListener(p -> {
                fill(context, screen, prefs);
                return false; // the page still opens
            });
        }
        fill(context, screen, prefs);
    }

    /** Refills the preset rows when {@code screen} holds them (nothing otherwise). */
    public static void refresh(Context context, PreferenceScreen screen, SharedPreferences prefs) {
        fill(context, screen, prefs);
    }

    private static void fill(Context context, PreferenceScreen screen, SharedPreferences prefs) {
        final PreferenceCategory category = screen.findPreference(KEY_CATEGORY);
        if (category == null) {
            return;
        }
        category.removeAll();
        final String current = current(prefs);
        for (String name : names(prefs)) {
            final Preference row = new Preference(context);
            row.setKey("preset_" + name);
            row.setPersistent(false);
            row.setTitle(name);
            row.setSummary(name.equals(current) ? context.getString(R.string.preset_in_use) : null);
            row.setIconSpaceReserved(false);
            row.setOnPreferenceClickListener(p -> {
                showActions(context, screen, prefs, name);
                return true;
            });
            category.addPreference(row);
        }
        final Preference add = new Preference(context);
        add.setKey("action_preset_save");
        add.setPersistent(false);
        add.setTitle(R.string.preset_save);
        add.setSummary(R.string.preset_save_summary);
        add.setIconSpaceReserved(false);
        add.setOnPreferenceClickListener(p -> {
            askName(context, screen, prefs);
            return true;
        });
        category.addPreference(add);
    }

    private static void showActions(Context context, PreferenceScreen screen, SharedPreferences prefs,
                                    String name) {
        final CharSequence[] actions = {context.getString(R.string.preset_use),
                context.getString(R.string.preset_replace), context.getString(R.string.preset_delete)};
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        if (use(prefs, name)) {
                            Toast.makeText(context, context.getString(R.string.preset_using, name),
                                    Toast.LENGTH_SHORT).show();
                            if (context instanceof Activity) {
                                ((Activity) context).recreate(); // every row shows the new values
                            }
                        }
                    } else if (which == 1) {
                        saveAndRefresh(context, screen, prefs, name);
                    } else {
                        delete(prefs, name);
                        fill(context, screen, prefs);
                    }
                })
                .setNegativeButton(R.string.dict_back, null)
                .create();
        dialog.setOnShowListener(d -> SettingsDialogs.markAlertTitleHeading(dialog));
        dialog.show();
    }

    private static void askName(Context context, PreferenceScreen screen, SharedPreferences prefs) {
        final float density = context.getResources().getDisplayMetrics().density;
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (24 * density + 0.5f);
        layout.setPadding(pad, (int) (8 * density + 0.5f), pad, 0);
        final EditText input = UserDictionaryScreen.labeledInput(context, layout,
                context.getString(R.string.preset_name), context.getString(R.string.preset_name_hint), "",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(MAX_NAME)});
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.preset_save)
                .setView(layout)
                .setPositiveButton(R.string.preset_save_button, (d, w) -> {
                    final String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        saveAndRefresh(context, screen, prefs, name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> SettingsDialogs.markAlertTitleHeading(dialog));
        dialog.show();
    }

    private static void saveAndRefresh(Context context, PreferenceScreen screen, SharedPreferences prefs,
                                       String name) {
        try {
            save(prefs, name);
            Toast.makeText(context, context.getString(R.string.preset_saved, name), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "Preset save failed", e);
        }
        fill(context, screen, prefs);
    }
}
