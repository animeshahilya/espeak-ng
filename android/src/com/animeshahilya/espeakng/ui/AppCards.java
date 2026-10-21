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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.view.accessibility.AccessibilityManager;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speech by app (Settings -> Advanced -> Speech control): for each app that
 * asks this engine to speak - TalkBack, a book reader, Maps - a speed, pitch
 * and volume as 50-200% of the main settings, a punctuation level and
 * "eSpeak only", like the language cards. Stored as
 * {@code app_<package>_<field>}; unset is the main setting, so speech is
 * unchanged until a user sets one (NVDA parity). The app comes from
 * {@code SynthesisRequest.getCallerUid()}.
 */
public final class AppCards {
    public static final String PREF_PREFIX = "app_";
    public static final String SPEED = LanguageCards.SPEED;
    public static final String PITCH = LanguageCards.PITCH;
    public static final String VOLUME = LanguageCards.VOLUME;
    /** VoiceSettings.PUNCTUATION_PRESET_* (not custom); "" = the main setting. */
    public static final String PUNCTUATION = "punct";
    /** {@link #VOICES_ESPEAK}, or "" for the usual voices. */
    public static final String VOICES = "voices";
    public static final String VOICES_ESPEAK = "espeak";
    /** The apps that have asked to speak, for the settings list (no UI row). */
    public static final String PREF_SEEN = "espeak_apps_seen";
    private static final int MAX_SEEN = 40;
    private static final String KEY_CATEGORY = "category_app_cards";
    private static final String KEY_SCREEN = "sub_app_cards";

    /** uid -> package ("" for none), process-wide: a uid keeps its package. */
    private static final Map<Integer, String> sPackages = new ConcurrentHashMap<>();
    /** Packages already in {@link #PREF_SEEN} in this process. */
    private static final Set<String> sNoted = ConcurrentHashMap.newKeySet();

    private AppCards() {
    }

    public static String key(String packageName, String field) {
        return PREF_PREFIX + packageName + "_" + field;
    }

    /**
     * The package of the app asking to speak, or null for none, for this
     * app itself (previews keep the main settings) and for an unknown uid.
     */
    public static String callerPackage(Context context, int uid) {
        if (uid < 0 || uid == Process.myUid()) {
            return null;
        }
        String name = sPackages.get(uid);
        if (name == null) {
            name = cardPackage(context.getPackageManager().getPackagesForUid(uid));
            sPackages.put(uid, name);
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * Apps sharing a uid share a card. Its order is arbitrary, so the
     * system's (Settings' "Play" comes from it; the first listed was Pixel
     * Camera Services) is "android", else the first package alphabetically.
     */
    static String cardPackage(String[] packages) {
        if (packages == null || packages.length == 0) {
            return "";
        }
        String first = packages[0];
        for (String p : packages) {
            if ("android".equals(p)) {
                return p;
            }
            if (p.compareTo(first) < 0) {
                first = p;
            }
        }
        return first;
    }

    /** True when the app is a running screen reader (an enabled spoken-feedback accessibility service). */
    public static boolean isScreenReader(Context context, String packageName) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        if (am == null || packageName == null) {
            return false;
        }
        for (AccessibilityServiceInfo info
                : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)) {
            if (info.getResolveInfo() != null
                    && packageName.equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    /** Remembers an app for the settings list; one write per app per process. */
    public static void noteCaller(SharedPreferences prefs, String packageName) {
        if (packageName == null || !sNoted.add(packageName)) {
            return;
        }
        final Set<String> seen = new TreeSet<>(prefs.getStringSet(PREF_SEEN, Collections.emptySet()));
        if (seen.size() < MAX_SEEN && seen.add(packageName)) {
            prefs.edit().putStringSet(PREF_SEEN, seen).apply();
        }
    }

    /** The app's speed, pitch or volume as a factor of the main one: 1 when unset or unreadable. */
    public static float factor(SharedPreferences prefs, String packageName, String field) {
        return LanguageCards.percentFactor(prefs, key(packageName, field));
    }

    /** The app's punctuation preset, "" for the main setting. */
    public static String punctuation(SharedPreferences prefs, String packageName) {
        return string(prefs, packageName, PUNCTUATION);
    }

    /** The app's voice choice: {@link #VOICES_ESPEAK} or "". */
    public static String voices(SharedPreferences prefs, String packageName) {
        return string(prefs, packageName, VOICES);
    }

    private static String string(SharedPreferences prefs, String packageName, String field) {
        try {
            final String v = prefs.getString(key(packageName, field), null);
            return v == null ? "" : v;
        } catch (ClassCastException e) {
            return "";
        }
    }

    /**
     * Fills the "Speech by app" page: one row per app that has spoken, each
     * opening its card. Refilled whenever the page opens, as apps keep
     * speaking while the settings screen stays open.
     */
    public static void configure(Context context, PreferenceScreen screen, PreferenceFragmentCompat fragment,
                                 SharedPreferences prefs) {
        final PreferenceCategory category = screen.findPreference(KEY_CATEGORY);
        final Preference page = screen.findPreference(KEY_SCREEN);
        if (category == null || page == null || !(fragment instanceof SettingsFragment)) {
            return;
        }
        fill(context, category, (SettingsFragment) fragment, prefs);
        page.setOnPreferenceClickListener(p -> {
            fill(context, category, (SettingsFragment) fragment, prefs);
            return false; // the page still opens
        });
    }

    private static void fill(Context context, PreferenceCategory category, SettingsFragment f,
                             SharedPreferences prefs) {
        category.removeAll();
        final Map<String, String> apps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final PackageManager pm = context.getPackageManager();
        for (String name : prefs.getStringSet(PREF_SEEN, Collections.emptySet())) {
            try {
                final ApplicationInfo info = pm.getApplicationInfo(name, 0);
                apps.put(pm.getApplicationLabel(info) + "\u0000" + name, name);
            } catch (PackageManager.NameNotFoundException e) {
                apps.put(name + "\u0000" + name, name); // uninstalled, or hidden from this app
            }
        }
        if (apps.isEmpty()) {
            final Preference none = new Preference(context);
            none.setKey("app_card_none");
            none.setPersistent(false);
            none.setSelectable(false);
            none.setSummary(R.string.app_card_none);
            none.setIconSpaceReserved(false);
            category.addPreference(none);
            return;
        }
        for (Map.Entry<String, String> e : apps.entrySet()) {
            final String label = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            final String name = e.getValue();
            final Preference row = new Preference(context);
            row.setKey("app_card_" + name);
            row.setPersistent(false);
            row.setTitle(label);
            row.setIconSpaceReserved(false);
            row.setSummaryProvider(p -> summary(context, prefs, name));
            row.setOnPreferenceClickListener(p -> {
                open(context, f, name, label);
                return true;
            });
            category.addPreference(row);
        }
    }

    /** What the card changes, or "Same as main settings". */
    static CharSequence summary(Context context, SharedPreferences prefs, String name) {
        final List<String> parts = new ArrayList<>();
        final int[] titles = {R.string.language_card_speed, R.string.language_card_pitch,
                R.string.language_card_volume};
        final String[] fields = {SPEED, PITCH, VOLUME};
        for (int i = 0; i < fields.length; i++) {
            final float f = factor(prefs, name, fields[i]);
            if (f != 1f) {
                parts.add(context.getString(R.string.app_card_part, context.getString(titles[i]),
                        context.getString(R.string.language_card_percent, Math.round(f * 100))));
            }
        }
        final String punct = punctuation(prefs, name);
        final String[] presets = context.getResources().getStringArray(R.array.app_card_punctuation_values);
        final String[] presetNames = context.getResources().getStringArray(R.array.app_card_punctuation_entries);
        for (int i = 0; i < presets.length; i++) {
            if (presets[i].equals(punct)) {
                parts.add(context.getString(R.string.app_card_part,
                        context.getString(R.string.app_card_punctuation), presetNames[i]));
            }
        }
        if (VOICES_ESPEAK.equals(voices(prefs, name))) {
            parts.add(context.getString(R.string.app_card_espeak_only));
        }
        return parts.isEmpty() ? context.getString(R.string.language_card_summary_same)
                : String.join(", ", parts);
    }

    /** The app's page: speed, pitch, volume, punctuation, voices. */
    private static void open(Context context, SettingsFragment f, String name, String label) {
        final PreferenceScreen page = f.getPreferenceManager().createPreferenceScreen(context);
        page.setTitle(label);
        LanguageCards.addProsody(context, page, field -> key(name, field));
        page.addPreference(LanguageCards.choice(context, key(name, PUNCTUATION), R.string.app_card_punctuation,
                R.array.app_card_punctuation_entries, R.array.app_card_punctuation_values, ""));
        page.addPreference(LanguageCards.choice(context, key(name, VOICES), R.string.app_card_voices,
                R.array.app_card_voices_entries, R.array.app_card_voices_values, ""));
        f.pushScreen(page);
    }
}
