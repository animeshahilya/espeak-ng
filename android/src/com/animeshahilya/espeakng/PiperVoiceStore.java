/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Downloaded Piper voices on disk, and which one (if any) speaks each
 * language.
 *
 * <p>Layout, in device-protected storage so a natural voice works before the
 * first unlock just like eSpeak does (Direct Boot):
 * <pre>
 *   files/piper/voices/&lt;key&gt;/model.onnx
 *   files/piper/voices/&lt;key&gt;/model.onnx.json
 * </pre>
 * A voice directory only exists once both files are verified and moved in
 * (see {@link PiperDownloads}), so anything listed here is complete.
 *
 * <p>Language choice is per language, not per voice in Android's voice list:
 * eSpeak's voices stay exactly as they are (NVDA parity), and a language the
 * user switched to a natural voice is spoken by it whichever eSpeak voice or
 * variant of that language the screen reader asked for.
 */
final class PiperVoiceStore {
    private static final String TAG = "PiperVoiceStore";

    /**
     * Master switch: off speaks everything with eSpeak, choices kept. Off by
     * default - natural voices are opt-in; the settings page persists the
     * switch once shown, so anyone who downloaded a voice keeps their choice.
     */
    static final String PREF_ENABLED = "piper_enabled";
    /** Per language: "piper_voice_" + ISO 639-2 code -> voice key, absent = eSpeak. */
    static final String PREF_VOICE_PREFIX = "piper_voice_";
    /** Keep single letters (character navigation, spelling) on eSpeak. */
    static final String PREF_ESPEAK_FOR_CHARACTERS = "piper_espeak_for_characters";
    /** Natural-voice speed relative to the eSpeak rate, percent. */
    static final String PREF_SPEED = "piper_speed";
    /** Offer models to NNAPI; see PiperEngine#setAcceleration. */
    static final String PREF_ACCELERATION = "piper_nnapi";
    /** Speaker of multi-speaker voices: "piper_speaker_" + key -> id. */
    static final String PREF_SPEAKER_PREFIX = "piper_speaker_";

    static final String MODEL_FILE = "model.onnx";
    static final String CONFIG_FILE = "model.onnx.json";

    /** A voice on disk. */
    static final class Installed {
        final String key;
        final File dir;
        final PiperVoiceConfig config;

        Installed(String key, File dir, PiperVoiceConfig config) {
            this.key = key;
            this.dir = dir;
            this.config = config;
        }

        File model() {
            return new File(dir, MODEL_FILE);
        }

        /** Space on the phone: model, config and PiperModel's optimized copy. */
        long sizeBytes() {
            long bytes = 0;
            final File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    bytes += f.length();
                }
            }
            return bytes;
        }

        /** Language key of the voice, comparable with {@link #languageKey(Locale)}. */
        String languageKey() {
            return PiperVoiceStore.languageKey(config.languageFamily);
        }
    }

    private static final Object LOCK = new Object();
    /** Parsed configs by key; the list is re-scanned, the JSON isn't re-parsed. */
    private static final Map<String, Installed> sCache = new HashMap<>();
    private static volatile List<Installed> sList;

    private PiperVoiceStore() {
    }

    static File root(Context storageContext) {
        return new File(storageContext.getFilesDir(), "piper");
    }

    static File voicesDir(Context storageContext) {
        return new File(root(storageContext), "voices");
    }

    /** Installed voices, sorted by language then name. Cached until {@link #invalidate()}. */
    static List<Installed> list(Context storageContext) {
        final List<Installed> cached = sList;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (sList != null) {
                return sList;
            }
            final List<Installed> out = new ArrayList<>();
            final File[] dirs = voicesDir(storageContext).listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    final Installed voice = load(dir);
                    if (voice != null) {
                        out.add(voice);
                    }
                }
            }
            Collections.sort(out, (a, b) -> {
                final int byLang = String.valueOf(a.config.languageCode)
                        .compareTo(String.valueOf(b.config.languageCode));
                return byLang != 0 ? byLang : a.key.compareTo(b.key);
            });
            sList = Collections.unmodifiableList(out);
            return sList;
        }
    }

    static Installed find(Context storageContext, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (Installed v : list(storageContext)) {
            if (v.key.equals(key)) {
                return v;
            }
        }
        return null;
    }

    /** Installed voices for one language (see {@link #languageKey(Locale)}). */
    static List<Installed> forLanguage(Context storageContext, String languageKey) {
        final List<Installed> out = new ArrayList<>();
        for (Installed v : list(storageContext)) {
            if (v.languageKey().equals(languageKey)) {
                out.add(v);
            }
        }
        return out;
    }

    static void invalidate() {
        synchronized (LOCK) {
            sList = null;
        }
    }

    private static Installed load(File dir) {
        final File model = new File(dir, MODEL_FILE);
        final File json = new File(dir, CONFIG_FILE);
        if (!dir.isDirectory() || !model.isFile() || !json.isFile()) {
            return null;
        }
        final String key = dir.getName();
        synchronized (LOCK) {
            final Installed cached = sCache.get(key);
            if (cached != null && cached.dir.equals(dir)) {
                return cached;
            }
        }
        try {
            final PiperVoiceConfig config = PiperVoiceConfig.parse(key, readText(json));
            if (!config.isSupported()) {
                return null;
            }
            final Installed voice = new Installed(key, dir, config);
            synchronized (LOCK) {
                sCache.put(key, voice);
            }
            return voice;
        } catch (Exception e) {
            Log.w(TAG, "Skipping unreadable voice " + key, e);
            return null;
        }
    }

    static boolean delete(Context storageContext, SharedPreferences prefs, String key) {
        PiperEngine.get().unload(key);
        final File dir = new File(voicesDir(storageContext), key);
        PiperDownloads.deleteRecursively(dir); // model, config and its optimized copy
        final boolean ok = !dir.exists();
        // Languages it spoke go back to eSpeak rather than to a missing voice.
        final SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(PREF_VOICE_PREFIX) && key.equals(e.getValue())) {
                editor.remove(e.getKey());
            }
        }
        editor.remove(PREF_SPEAKER_PREFIX + key);
        editor.remove(PiperCrashGuard.PREF_SUSPENDED + key);
        editor.apply();
        synchronized (LOCK) {
            sCache.remove(key);
        }
        invalidate();
        return ok;
    }

    /**
     * Language identity shared by eSpeak voices and Piper voices: the ISO
     * 639-2 code. Comparing two-letter codes breaks on Java's legacy aliases
     * (Indonesian "in" vs Piper's "id", "iw"/"he") - which way they fold
     * depends on the Android version - but both map to the same 3-letter code.
     */
    static String languageKey(Locale locale) {
        if (locale == null) {
            return "";
        }
        try {
            final String iso3 = locale.getISO3Language();
            if (iso3 != null && !iso3.isEmpty()) {
                return iso3;
            }
        } catch (MissingResourceException ignored) {
            // Fall through to the 2-letter code.
        }
        return locale.getLanguage();
    }

    @SuppressWarnings("deprecation")
    static String languageKey(String family) {
        if (family == null) {
            return "";
        }
        // Piper spells Chinese "zh" but eSpeak's voice is "cmn": both are zho.
        return languageKey(new Locale(family.toLowerCase(Locale.ROOT)));
    }

    static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    /** Voice key chosen for a language, or null for eSpeak. */
    static String assignedKey(SharedPreferences prefs, String languageKey) {
        final String key = prefs.getString(PREF_VOICE_PREFIX + languageKey, null);
        return key == null || key.isEmpty() ? null : key;
    }

    static void assign(SharedPreferences prefs, String languageKey, String voiceKey) {
        final SharedPreferences.Editor e = prefs.edit();
        if (voiceKey == null || voiceKey.isEmpty()) {
            e.remove(PREF_VOICE_PREFIX + languageKey);
        } else {
            e.putString(PREF_VOICE_PREFIX + languageKey, voiceKey);
        }
        e.apply();
    }

    /**
     * The natural voice that should speak for an eSpeak voice right now, or
     * null for eSpeak (switch off, language not assigned, or the assigned
     * voice was deleted).
     */
    static Installed resolve(Context storageContext, SharedPreferences prefs, Voice espeakVoice) {
        if (espeakVoice == null || !isEnabled(prefs)) {
            return null;
        }
        final String key = assignedKey(prefs, languageKey(espeakVoice.locale));
        return key == null || PiperCrashGuard.isSuspended(prefs, key) ? null
                : find(storageContext, key);
    }

    static boolean acceleration(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ACCELERATION, false);
    }

    static boolean espeakForCharacters(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ESPEAK_FOR_CHARACTERS, true);
    }

    /** Speed multiplier from {@link #PREF_SPEED}, 0.5-2.0. */
    static float speedFactor(SharedPreferences prefs) {
        int percent;
        try {
            percent = Integer.parseInt(prefs.getString(PREF_SPEED, "100"));
        } catch (NumberFormatException | ClassCastException e) {
            percent = 100;
        }
        return Math.max(50, Math.min(200, percent)) / 100f;
    }

    static int speakerId(SharedPreferences prefs, String key) {
        try {
            return Integer.parseInt(prefs.getString(PREF_SPEAKER_PREFIX + key, "-1"));
        } catch (NumberFormatException | ClassCastException e) {
            return -1;
        }
    }

    static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            final byte[] data = new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)];
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            return new String(data, 0, off, StandardCharsets.UTF_8);
        }
    }
}
