/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.text.LanguageRuns;
import com.animeshahilya.espeakng.Voice;

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
 * (see {@link PiperDownloads}), so anything listed here is complete. Once
 * the model's optimized copy (model.&lt;runtime&gt;.enc.ort ...) has loaded,
 * model.onnx itself is deleted and {@code source} (its MD5) stands for it.
 *
 * <p>Language choice is per language, not per voice in Android's voice list:
 * eSpeak's voices stay exactly as they are (NVDA parity), and a language the
 * user switched to a natural voice is spoken by it whichever eSpeak voice or
 * variant of that language the screen reader asked for.
 */
public final class PiperVoiceStore {
    private static final String TAG = "PiperVoiceStore";

    /**
     * Master switch: off speaks everything with eSpeak, choices kept. Off by
     * default - natural voices are opt-in; the settings page persists the
     * switch once shown, so anyone who downloaded a voice keeps their choice.
     */
    public static final String PREF_ENABLED = "piper_enabled";
    /** Per language: "piper_voice_" + ISO 639-2 code -> voice key, absent = eSpeak. */
    public static final String PREF_VOICE_PREFIX = "piper_voice_";
    /** Keep single letters (character navigation, spelling) on eSpeak. */
    public static final String PREF_ESPEAK_FOR_CHARACTERS = "piper_espeak_for_characters";
    /** Natural-voice speed relative to the eSpeak rate, percent. */
    public static final String PREF_SPEED = "piper_speed";
    /** Speaker of multi-speaker voices: "piper_speaker_" + key -> id. */
    public static final String PREF_SPEAKER_PREFIX = "piper_speaker_";
    /** A voice's own speed, percent, over {@link #PREF_SPEED}: "piper_speed_" + key. */
    public static final String PREF_VOICE_SPEED_PREFIX = "piper_speed_";
    /** Speaking style for every natural voice, see {@link #styleScales}. */
    public static final String PREF_STYLE = "piper_style";
    public static final String STYLE_STEADY = "steady";
    public static final String STYLE_NATURAL = "natural";
    public static final String STYLE_LIVELY = "lively";
    /** Recently used voice keys, newest first, comma-separated (device state). */
    public static final String PREF_RECENT = "piper_recent";
    public static final int RECENT_MAX = 3;

    public static final String MODEL_FILE = "model.onnx";
    public static final String CONFIG_FILE = "model.onnx.json";
    /**
     * Content-addressed model store: voices sharing one upstream file (all 20
     * Rasa voices share {@code vits-rasa-13-piper-model.onnx}) keep a single
     * copy here, and each voice directory links it as {@link #MODEL_FILE}.
     * Without this, every Rasa voice costs its own ~62 MB download and copy.
     */
    public static final String SHARED_DIR = "shared";

    /** A voice on disk. */
    public static final class Installed {
        public final String key;
        public final File dir;
        public final PiperVoiceConfig config;

        public Installed(String key, File dir, PiperVoiceConfig config) {
            this.key = key;
            this.dir = dir;
            this.config = config;
        }

        public File model() {
            return new File(dir, MODEL_FILE);
        }

        /** Space on the phone: model, config and PiperModel's optimized copy. */
        public long sizeBytes() {
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
        public String languageKey() {
            return PiperVoiceStore.languageKey(config.languageFamily);
        }
    }

    /**
     * Disk actually used by all installed voices, counting linked shared
     * models once (20 Rasa voices share one inode). Falls back to the plain
     * sum where inode data is unavailable.
     */
    public static long diskBytes(Context storageContext) {
        long bytes = 0;
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (Installed v : list(storageContext)) {
            final File[] files = v.dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                try {
                    final android.system.StructStat st =
                            android.system.Os.stat(f.getAbsolutePath());
                    if (seen.add(st.st_dev + ":" + st.st_ino)) {
                        bytes += st.st_size;
                    }
                } catch (Exception e) {
                    bytes += f.length();
                }
            }
        }
        return bytes;
    }

    private static final Object LOCK = new Object();
    /** Parsed configs by key; the list is re-scanned, the JSON isn't re-parsed. */
    private static final Map<String, Installed> sCache = new HashMap<>();
    private static volatile List<Installed> sList;

    private PiperVoiceStore() {
    }

    public static File root(Context storageContext) {
        return new File(storageContext.getFilesDir(), "piper");
    }

    public static File voicesDir(Context storageContext) {
        return new File(root(storageContext), "voices");
    }

    /** Content-addressed store dir ({@code files/piper/shared}). */
    public static File sharedDir(Context storageContext) {
        return new File(root(storageContext), SHARED_DIR);
    }

    /** File holding the shared copy of a model, keyed by its catalog MD5. */
    public static File sharedModelFile(Context storageContext, String modelMd5) {
        return new File(sharedDir(storageContext), sharedFileName(modelMd5));
    }

    /** Pure filename mapping, unit-testable without a Context. */
    public static String sharedFileName(String modelMd5) {
        return modelMd5 == null ? "unknown.onnx" : modelMd5.toLowerCase(Locale.ROOT) + ".onnx";
    }

    /** Installed voices, sorted by language then name. Cached until {@link #invalidate()}. */
    public static List<Installed> list(Context storageContext) {
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

    public static Installed find(Context storageContext, String key) {
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

    public static void invalidate() {
        synchronized (LOCK) {
            sList = null;
        }
    }

    private static Installed load(File dir) {
        final File json = new File(dir, CONFIG_FILE);
        // The model itself is deleted once its optimized copy loads
        // (PiperModel.dropOriginal); its "source" MD5 record stays.
        if (!dir.isDirectory() || !json.isFile()
                || !(new File(dir, MODEL_FILE).isFile() || new File(dir, PiperDownloads.SOURCE_FILE).isFile())) {
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

    public static boolean delete(Context storageContext, SharedPreferences prefs, String key) {
        PiperEngine.get().unload(key);
        PiperEngine.get().deleteCachedPhrases(key);
        final File dir = new File(voicesDir(storageContext), key);
        PiperDownloads.deleteRecursively(dir); // model, config and its optimized copy
        final boolean ok = !dir.exists();
        // Before the collection below: a cached list still holds this voice,
        // which kept its shared file alive after the last user was deleted.
        invalidate();
        // A removed voice's shared bytes stay while another voice uses them.
        PiperDownloads.collectSharedGarbage(storageContext, null);
        // Languages it spoke go back to eSpeak rather than to a missing voice.
        final SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(PREF_VOICE_PREFIX) && key.equals(e.getValue())) {
                editor.remove(e.getKey());
            }
        }
        editor.remove(PREF_SPEAKER_PREFIX + key);
        editor.remove(PREF_VOICE_SPEED_PREFIX + key);
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
    public static String languageKey(Locale locale) {
        if (locale == null) {
            return "";
        }
        final String iso3 = Voice.iso3Language(locale);
        return iso3.isEmpty() ? locale.getLanguage() : iso3;
    }

    @SuppressWarnings("deprecation")
    public static String languageKey(String family) {
        if (family == null) {
            return "";
        }
        // Piper spells Chinese "zh" but eSpeak's voice is "cmn": both are zho.
        return languageKey(new Locale(family.toLowerCase(Locale.ROOT)));
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    /** Voice key chosen for a language, or null for eSpeak. */
    public static String assignedKey(SharedPreferences prefs, String languageKey) {
        final String key = prefs.getString(PREF_VOICE_PREFIX + languageKey, null);
        return key == null || key.isEmpty() ? null : key;
    }

    public static void assign(SharedPreferences prefs, String languageKey, String voiceKey) {
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
    public static Installed resolve(Context storageContext, SharedPreferences prefs, Voice espeakVoice) {
        return espeakVoice == null || espeakVoice.isScriptVariant() ? null
                : assignedFor(storageContext, prefs, languageKey(espeakVoice.locale));
    }

    /** As {@link #resolve}, for a language key (a Hindi run inside English text). */
    public static Installed assignedFor(Context storageContext, SharedPreferences prefs, String languageKey) {
        if (!isEnabled(prefs)) {
            return null;
        }
        final String key = assignedKey(prefs, languageKey);
        return key == null || PiperCrashGuard.isSuspended(prefs, key) ? null
                : find(storageContext, key);
    }

    public static boolean espeakForCharacters(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ESPEAK_FOR_CHARACTERS, true);
    }

    /** Speed multiplier from {@link #PREF_SPEED}, 0.5-2.0. */
    public static float speedFactor(SharedPreferences prefs) {
        return percent(prefs, PREF_SPEED, 100) / 100f;
    }

    /** As {@link #speedFactor(SharedPreferences)}, unless this voice has its own speed. */
    public static float speedFactor(SharedPreferences prefs, String key) {
        final int own = percent(prefs, PREF_VOICE_SPEED_PREFIX + key, 0);
        return own > 0 ? own / 100f : speedFactor(prefs);
    }

    /** A stored percent, 50-200, or {@code fallback} when unset or unreadable. */
    private static int percent(SharedPreferences prefs, String pref, int fallback) {
        try {
            final String value = prefs.getString(pref, null);
            if (value == null || value.isEmpty()) {
                return fallback;
            }
            return Math.max(50, Math.min(200, Integer.parseInt(value)));
        } catch (NumberFormatException | ClassCastException e) {
            return fallback;
        }
    }

    /**
     * Speaking style: {noise_scale, noise_w} multipliers of the voice's own
     * values. Lower noise_scale gives flatter, steadier audio with fewer
     * glitches; lower noise_w gives more even phoneme lengths (rhythm).
     */
    public static float[] styleScales(SharedPreferences prefs) {
        final String style = prefs.getString(PREF_STYLE, STYLE_NATURAL);
        if (STYLE_STEADY.equals(style)) {
            return new float[] {0.5f, 0.5f};
        }
        if (STYLE_LIVELY.equals(style)) {
            return new float[] {1.3f, 1.25f};
        }
        return new float[] {1f, 1f};
    }

    /** Most recently used voice keys, newest first (see {@link #noteUsed}). */
    public static List<String> recent(SharedPreferences prefs) {
        final String value = prefs.getString(PREF_RECENT, "");
        final List<String> keys = new ArrayList<>();
        for (String k : value.split(",")) {
            if (!k.isEmpty()) {
                keys.add(k);
            }
        }
        return keys;
    }

    /**
     * Remembers the natural voices a request used, newest first, so the next
     * speech service start can preload them - otherwise, after Android restarts the
     * speech service, the first Hindi words in English text are read by eSpeak
     * while the Hindi voice loads. Writes only when the order changes.
     */
    public static void noteUsed(SharedPreferences prefs, List<String> used) {
        if (used.isEmpty()) {
            return;
        }
        final List<String> keys = new ArrayList<>(used);
        for (String k : recent(prefs)) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        final String value = String.join(",", keys.subList(0, Math.min(RECENT_MAX, keys.size())));
        if (!value.equals(prefs.getString(PREF_RECENT, ""))) {
            prefs.edit().putString(PREF_RECENT, value).apply();
        }
    }

    public static int speakerId(SharedPreferences prefs, String key) {
        try {
            return Integer.parseInt(prefs.getString(PREF_SPEAKER_PREFIX + key, "-1"));
        } catch (NumberFormatException | ClassCastException e) {
            return -1;
        }
    }

    public static String readText(File file) throws IOException {
        if (file.length() > 4 * 1024 * 1024) {
            throw new IOException("File too large: " + file);
        }
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final java.util.regex.Pattern VOICE_KEY_LOCALE =
            java.util.regex.Pattern.compile("^([a-z]{2,3})_([A-Z]{2})-");

    /**
     * Like SherpaVoices, a language only a natural voice speaks (Chhattisgarhi,
     * Bhojpuri, Dogri...) becomes a TTS voice of its own, so screen readers,
     * apps and the system language list can pick it. eSpeak reads it with
     * the rules of its script's language (Hindi for Devanagari) until the
     * natural voice has loaded. One per language that has a natural voice
     * chosen and no eSpeak voice.
     */
    public static List<Voice> naturalOnlyVoices(SharedPreferences prefs, List<Voice> espeakVoices) {
        final List<Voice> out = new ArrayList<>();
        if (!isEnabled(prefs)) {
            return out;
        }
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith(PREF_VOICE_PREFIX) || !(e.getValue() instanceof String)) {
                continue;
            }
            final String language = e.getKey().substring(PREF_VOICE_PREFIX.length());
            final String standInLanguage = LanguageRuns.standIn(language);
            final java.util.regex.Matcher m = VOICE_KEY_LOCALE.matcher((String) e.getValue());
            if (standInLanguage == null || !m.find()) {
                continue;
            }
            Voice standIn = null;
            boolean espeakHasIt = false;
            for (Voice v : espeakVoices) {
                final String key = languageKey(v.locale);
                espeakHasIt |= key.equals(language);
                if (standIn == null && key.equals(standInLanguage)) {
                    standIn = v;
                }
            }
            if (!espeakHasIt && standIn != null) {
                @SuppressWarnings("deprecation")
                final Locale locale = new Locale(m.group(1), m.group(2));
                out.add(Voice.naturalOnly(m.group(1), locale, standIn));
            }
        }
        return out;
    }
}
