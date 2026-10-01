/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Getting Piper voices onto the device: the voice catalog from the official
 * rhasspy/piper-voices repository, and downloads through the system
 * DownloadManager (resumes over flaky networks, survives the settings screen
 * closing, and shows its own accessible progress notification).
 *
 * <p>This is the only network access in the app, and it only ever happens
 * when the user asks for a voice list or a voice. Speech itself stays
 * offline.
 *
 * <p>Install is two-phase so a half-downloaded voice can never be picked:
 * the small config is fetched first (and checked - voices needing a
 * phonemizer other than eSpeak are refused before 60 MB are spent), the
 * model lands in app-specific external storage, and only after its MD5
 * matches the catalog are both moved into the device-protected voice
 * directory {@link PiperVoiceStore} lists.
 */
final class PiperDownloads {
    private static final String TAG = "PiperDownloads";

    /** Pinned to the repository's main branch: new voices appear without an app update. */
    static final String REPO_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main/";
    static final String CATALOG_URL = REPO_BASE + "voices.json";
    static final String SAMPLES_BASE = "https://rhasspy.github.io/piper-samples/samples/";
    /** SYSPIN + AI4Bharat Rasa character voices, md5-pinned in the extra list. */
    static final String RESPIN_SYSPIN_RELEASES =
            "https://github.com/animeshahilya/sherpa-onnx-respin-syspin/releases/download/";
    /**
     * Community voices for languages Piper's own catalog lacks (Tamil,
     * Sinhala), in voices.json's format plus base_url, source and license.
     * Bundled, and each base_url pinned to a commit, so the checksums here
     * always match; only this list may point somewhere other than REPO_BASE.
     */
    static final String EXTRA_CATALOG_ASSET = "piper/extra_voices.json";
    /** Refetch the catalog after a week; a manual refresh is always possible. */
    static final long CATALOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /** Sent (to this package only) when a voice is installed or removed. */
    static final String ACTION_VOICES_CHANGED = "com.animeshahilya.espeakng.PIPER_VOICES_CHANGED";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_ASSIGNED_LANGUAGE = "assigned";

    /** download id -> voice key, and voice key -> download id. */
    private static final String PREF_DL_ID_PREFIX = "piper_dl_id_";
    private static final String PREF_DL_KEY_PREFIX = "piper_dl_key_";

    private PiperDownloads() {
    }

    /** Download bookkeeping, excluded from settings backups. */
    static boolean isDeviceLocalPref(String key) {
        return key != null && (key.startsWith(PREF_DL_ID_PREFIX) || key.startsWith(PREF_DL_KEY_PREFIX)
                // Crash strikes and suspensions describe this phone, not the user's choices.
                || key.startsWith("piper_crash_strikes_") || key.startsWith(PiperCrashGuard.PREF_SUSPENDED)
                // Which voices this phone used lately (startup preloading).
                || key.equals(PiperVoiceStore.PREF_RECENT));
    }

    /** One entry of voices.json. */
    static final class CatalogVoice {
        String key;
        String name;
        String family;
        String code;
        String region;
        String nameNative;
        String nameEnglish;
        String country;
        String quality;
        int numSpeakers;
        /**
         * Needs Enhanced-class compute whatever its size (SYSPIN/Rasa: HiFi-GAN
         * decoders 7-15x a Piper medium's work). Only the bundled list sets it.
         */
        boolean heavy;
        String modelPath;
        long modelSize;
        String modelMd5;
        String configPath;
        long configSize;
        String configMd5;
        /** Where modelPath/configPath live: REPO_BASE, or a bundled extra's pinned repo. */
        String baseUrl = REPO_BASE;
        /** Community voices only: who made it and its license, shown before download. */
        String source;
        String license;

        String displayName() {
            return PiperVoiceConfig.titleCase(name);
        }

        String sampleUrl() {
            if (!REPO_BASE.equals(baseUrl)) {
                return null; // piper-samples only has Piper's own voices
            }
            final int slash = modelPath.lastIndexOf('/');
            return slash < 0 ? null : SAMPLES_BASE + modelPath.substring(0, slash) + "/speaker_0.mp3";
        }

        String languageKey() {
            return PiperVoiceStore.languageKey(family);
        }
    }

    /** Parses voices.json; skips malformed entries rather than failing the list. */
    static List<CatalogVoice> parseCatalog(String json) throws JSONException {
        return parseCatalog(json, false);
    }

    /** @param bundled the app's own extra list: the only one trusted with base_url */
    static List<CatalogVoice> parseCatalog(String json, boolean bundled) throws JSONException {
        final JSONObject root = new JSONObject(json);
        final List<CatalogVoice> out = new ArrayList<>();
        final Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final JSONObject v = root.optJSONObject(key);
            if (v == null) {
                continue;
            }
            final CatalogVoice c = new CatalogVoice();
            c.key = v.optString("key", key);
            c.name = v.optString("name", key);
            c.quality = v.optString("quality", "");
            if (!isOffered(c.quality)) {
                continue;
            }
            c.numSpeakers = v.optInt("num_speakers", 1);
            if (bundled) {
                c.baseUrl = v.optString("base_url", REPO_BASE);
                c.source = v.optString("source", null);
                c.license = v.optString("license", null);
                c.heavy = v.optBoolean("heavy", false);
                if (!(c.baseUrl.startsWith("https://huggingface.co/")
                        || c.baseUrl.equals(RESPIN_SYSPIN_RELEASES)) || !c.baseUrl.endsWith("/")) {
                    continue;
                }
            }
            final JSONObject lang = v.optJSONObject("language");
            if (lang == null) {
                continue;
            }
            c.family = lang.optString("family", "");
            c.code = lang.optString("code", "");
            c.region = lang.optString("region", "");
            c.nameNative = lang.optString("name_native", c.family);
            c.nameEnglish = lang.optString("name_english", c.family);
            PiperVoiceConfig.rememberName(c.family, c.nameEnglish);
            c.country = lang.optString("country_english", c.region);
            final JSONObject files = v.optJSONObject("files");
            if (files == null) {
                continue;
            }
            final Iterator<String> paths = files.keys();
            while (paths.hasNext()) {
                final String path = paths.next();
                final JSONObject f = files.optJSONObject(path);
                if (f == null) {
                    continue;
                }
                if (path.endsWith(".onnx")) {
                    c.modelPath = path;
                    c.modelSize = f.optLong("size_bytes", 0);
                    c.modelMd5 = f.optString("md5_digest", null);
                } else if (path.endsWith(".onnx.json")) {
                    c.configPath = path;
                    c.configSize = f.optLong("size_bytes", 0);
                    c.configMd5 = f.optString("md5_digest", null);
                }
            }
            if (c.modelPath == null || c.configPath == null || c.family.isEmpty()
                    || !isSafeKey(c.key) || !isSafePath(c.modelPath) || !isSafePath(c.configPath)) {
                continue;
            }
            out.add(c);
        }
        sort(out);
        return out;
    }

    private static void sort(List<CatalogVoice> out) {
        Collections.sort(out, (a, b) -> {
            int d = a.code.compareTo(b.code);
            if (d != 0) return d;
            d = a.name.compareTo(b.name);
            if (d != 0) return d;
            return Boolean.compare(isEnhanced(a.quality), isEnhanced(b.quality));
        });
    }

    /** Piper's catalog plus the bundled extras it does not have (by key). */
    private static List<CatalogVoice> withExtras(Context context, List<CatalogVoice> catalog) {
        final List<CatalogVoice> out = new ArrayList<>(catalog);
        final java.util.Set<String> keys = new java.util.HashSet<>();
        for (CatalogVoice v : catalog) {
            keys.add(v.key);
        }
        for (CatalogVoice v : bundledExtras(context)) {
            if (keys.add(v.key)) {
                out.add(v);
            }
        }
        sort(out);
        return out;
    }

    /** The app's own list (assets): needs no network, so also for Compact lookups. */
    static List<CatalogVoice> bundledExtras(Context context) {
        try (InputStream in = context.getAssets().open(EXTRA_CATALOG_ASSET)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return parseCatalog(buf.toString("UTF-8"), true);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Extra voices unavailable", e);
            return new ArrayList<>();
        }
    }

    /** "Priyamvada" from "hi_IN-priyamvada-medium" (catalog keys are lang-name-quality). */
    static String nameFromKey(String key) {
        final String[] parts = key.split("-");
        return PiperVoiceConfig.titleCase(parts.length >= 3 ? parts[1] : key);
    }

    /**
     * Two tiers are offered: Piper's "medium" as Standard and "high" as
     * Enhanced. "low"/"x_low" sound clearly worse, every catalog language
     * has a medium or high voice, and medium already runs 6-12x faster than
     * real time on a phone - there is no speed left to trade quality for.
     */
    static boolean isOffered(String quality) {
        return "medium".equals(quality) || isEnhanced(quality) || isCompact(quality);
    }

    /**
     * This project's own tier for heavy voices (SYSPIN, Rasa, Piper "high"):
     * the same voice with its decoder in INT8 (sherpa-onnx-respin-syspin's
     * build_compact.py). About twice as fast on a CPU, smaller, slightly noisier.
     */
    static boolean isCompact(String quality) {
        return "compact".equals(quality);
    }

    /** "hi_IN-kavya-medium" -> "hi_IN-kavya-compact": a voice's Compact version, by key. */
    static String compactKey(String key) {
        final int dash = key.lastIndexOf('-');
        return dash > 0 ? key.substring(0, dash) + "-compact" : key + "-compact";
    }

    static boolean isEnhanced(String quality) {
        return "high".equals(quality);
    }

    static boolean isSafeKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_.\\-]{1,128}") && !key.contains("..");
    }

    static boolean isSafePath(String path) {
        // "=": NavGurukul's Indian English file is named "...dataset=spicor-...".
        return path != null && path.matches("[A-Za-z0-9_./=\\-]{1,256}") && !path.contains("..");
    }

    private static File catalogFile(Context storageContext) {
        return new File(PiperVoiceStore.root(storageContext), "voices.json");
    }

    /**
     * The catalog: the cached copy when fresh, otherwise downloaded (falling
     * back to a stale cache when offline). Blocking - call off the main thread.
     */
    static List<CatalogVoice> loadCatalog(Context storageContext, boolean refresh)
            throws IOException, JSONException {
        final File cache = catalogFile(storageContext);
        final boolean fresh = cache.isFile()
                && System.currentTimeMillis() - cache.lastModified() < CATALOG_MAX_AGE_MS;
        if (!refresh && fresh) {
            try {
                return withExtras(storageContext, parseCatalog(PiperVoiceStore.readText(cache)));
            } catch (JSONException e) {
                Log.w(TAG, "Cached catalog unreadable; refetching", e);
            }
        }
        try {
            final byte[] data = fetch(CATALOG_URL, 8 * 1024 * 1024);
            final List<CatalogVoice> parsed = parseCatalog(new String(data, StandardCharsets.UTF_8));
            writeAtomically(cache, data);
            return withExtras(storageContext, parsed);
        } catch (IOException e) {
            if (cache.isFile()) {
                Log.w(TAG, "Catalog fetch failed; using cached copy", e);
                return withExtras(storageContext, parseCatalog(PiperVoiceStore.readText(cache)));
            }
            throw e;
        }
    }

    static byte[] fetch(String url, int maxBytes) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "eSpeakNG-Android/" + BuildConfig.VERSION_NAME);
        try {
            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > maxBytes) {
                        throw new IOException("Response too large: " + url);
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Thrown when a voice needs a phonemizer other than eSpeak. */
    static final class UnsupportedVoiceException extends Exception {
        UnsupportedVoiceException(String key) {
            super("Voice " + key + " does not use eSpeak phonemes");
        }
    }

    private static File stagingDir(Context storageContext, String key) {
        return new File(new File(PiperVoiceStore.root(storageContext), "staging"), key);
    }

    private static File downloadTarget(Context appContext, String key) {
        final File base = appContext.getExternalFilesDir("piper");
        return base == null ? null : new File(base, key + ".onnx.part");
    }

    /**
     * Starts downloading a voice. Blocking for the config fetch (a few KB);
     * the model itself downloads in the background.
     *
     * @return the DownloadManager id
     */
    static long start(Context appContext, Context storageContext, CatalogVoice voice)
            throws IOException, JSONException, UnsupportedVoiceException {
        return start(appContext, storageContext, voice, false);
    }

    /**
     * @param update a newer version of an installed voice: downloads like any
     *               other (mobile data too, no charging wait) and replaces the
     *               old files only once verified
     */
    static long start(Context appContext, Context storageContext, CatalogVoice voice, boolean update)
            throws IOException, JSONException, UnsupportedVoiceException {
        final byte[] configBytes = fetch(voice.baseUrl + voice.configPath, 1024 * 1024);
        if (voice.configMd5 != null && !voice.configMd5.equalsIgnoreCase(md5(configBytes))) {
            throw new IOException("Config checksum mismatch for " + voice.key);
        }
        final PiperVoiceConfig config = PiperVoiceConfig.parse(voice.key,
                new String(configBytes, StandardCharsets.UTF_8));
        if (!config.isSupported()) {
            throw new UnsupportedVoiceException(voice.key);
        }

        final File staging = stagingDir(storageContext, voice.key);
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Cannot create " + staging);
        }
        writeAtomically(new File(staging, PiperVoiceStore.CONFIG_FILE), configBytes);
        // Remember what the model must match, for install time.
        writeAtomically(new File(staging, "expected"), (voice.modelMd5 + "\n" + voice.modelSize)
                .getBytes(StandardCharsets.UTF_8));
        // Kept with the voice: which catalog version it is (checkForUpdates).
        writeAtomically(new File(staging, SOURCE_FILE), (voice.modelMd5 + "\n" + md5(configBytes))
                .getBytes(StandardCharsets.UTF_8));

        final File target = downloadTarget(appContext, voice.key);
        if (target == null) {
            throw new IOException("External app storage unavailable");
        }
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Could not remove stale " + target);
        }
        final DownloadManager dm = appContext.getSystemService(DownloadManager.class);
        final DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(voice.baseUrl + voice.modelPath))
                .setTitle(appContext.getString(R.string.piper_download_title, voice.displayName()))
                .setDescription(appContext.getString(R.string.piper_download_description,
                        voice.nameNative, voice.country))
                // Progress notification while downloading; completion is announced by
                // PiperDownloadReceiver once the voice is verified and usable, which
                // is later than the raw download finishing.
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target))
                .setAllowedOverRoaming(false);
        final long id = dm.enqueue(request);
        prefs(storageContext).edit()
                .putString(PREF_DL_ID_PREFIX + id, voice.key)
                .putLong(PREF_DL_KEY_PREFIX + voice.key, id)
                .apply();
        return id;
    }

    /** Progress of a voice's download, or null when none is running. */
    static final class Progress {
        final long downloaded;
        final long total;
        final int status;

        Progress(long downloaded, long total, int status) {
            this.downloaded = downloaded;
            this.total = total;
            this.status = status;
        }

        int percent() {
            return total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
        }
    }

    static Progress progress(Context appContext, Context storageContext, String key) {
        final long id = prefs(storageContext).getLong(PREF_DL_KEY_PREFIX + key, -1);
        if (id < 0) {
            return null;
        }
        final DownloadManager dm = appContext.getSystemService(DownloadManager.class);
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            return new Progress(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
        }
    }

    /** Keys with a download in flight. */
    static List<String> pendingKeys(Context storageContext) {
        final List<String> keys = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(storageContext).getAll().entrySet()) {
            if (e.getKey().startsWith(PREF_DL_KEY_PREFIX)) {
                keys.add(e.getKey().substring(PREF_DL_KEY_PREFIX.length()));
            }
        }
        return keys;
    }

    static void cancel(Context appContext, Context storageContext, String key) {
        final SharedPreferences prefs = prefs(storageContext);
        final long id = prefs.getLong(PREF_DL_KEY_PREFIX + key, -1);
        if (id >= 0) {
            appContext.getSystemService(DownloadManager.class).remove(id);
        }
        forget(prefs, id, key);
        deleteRecursively(stagingDir(storageContext, key));
        final File target = downloadTarget(appContext, key);
        if (target != null) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    /** Outcome of {@link #complete}, for the notification/toast. */
    enum Result { INSTALLED, FAILED, NOT_OURS }

    /**
     * Finishes a download: verifies the model against the catalog checksum
     * and moves it into place. Idempotent; blocking (hashes ~60 MB).
     * Synchronized: the completion broadcast and the settings screen's
     * reconcile() can finish the same download on two threads at once, and
     * both copied into one temp file and moved one staging folder.
     */
    static synchronized Result complete(Context appContext, Context storageContext, long id) {
        final SharedPreferences prefs = prefs(storageContext);
        final String key = prefs.getString(PREF_DL_ID_PREFIX + id, null);
        if (key == null) {
            return Result.NOT_OURS;
        }
        final DownloadManager dm = appContext.getSystemService(DownloadManager.class);
        int status = DownloadManager.STATUS_FAILED;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot query download " + id, e);
            return Result.NOT_OURS; // left pending: reconcile() retries
        }
        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_PAUSED) {
            return Result.NOT_OURS; // not finished yet (spurious or early broadcast)
        }
        final File target = downloadTarget(appContext, key);
        final File staging = stagingDir(storageContext, key);
        try {
            if (status != DownloadManager.STATUS_SUCCESSFUL || target == null || !target.isFile()) {
                throw new IOException("Download " + id + " ended with status " + status);
            }
            final String[] expected = PiperVoiceStore.readText(new File(staging, "expected"))
                    .trim().split("\n");
            final File model = new File(staging, PiperVoiceStore.MODEL_FILE);
            final String md5 = copyWithMd5(target, model);
            final boolean md5Known = expected.length > 0 && !"null".equals(expected[0])
                    && !expected[0].isEmpty();
            if (md5Known && !expected[0].equalsIgnoreCase(md5)) {
                throw new IOException("Model checksum mismatch for " + key);
            }
            //noinspection ResultOfMethodCallIgnored
            new File(staging, "expected").delete();
            final File dest = new File(PiperVoiceStore.voicesDir(storageContext), key);
            deleteRecursively(dest);
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            if (!staging.renameTo(dest)) {
                throw new IOException("Cannot move " + staging + " to " + dest);
            }
            PiperVoiceStore.invalidate();

            // A language's first natural voice starts speaking it right away:
            // the user just asked for it. Later ones wait to be chosen.
            String assigned = null;
            final PiperVoiceStore.Installed installed = PiperVoiceStore.find(storageContext, key);
            if (installed != null) {
                final String lang = installed.languageKey();
                final SharedPreferences settings = settingsPrefs(storageContext);
                if (PiperVoiceStore.assignedKey(settings, lang) == null) {
                    PiperVoiceStore.assign(settings, lang, key);
                    assigned = lang;
                }
            }
            broadcastChanged(appContext, key, assigned);
            // Snapdragon build: its NPU decoder follows (no-op elsewhere).
            new Thread(() -> fetchNpuDecoders(appContext, storageContext), "piper-npu-fetch").start();
            return Result.INSTALLED;
        } catch (IOException | RuntimeException e) {
            // Runtime too: this runs on bare threads, where anything uncaught
            // kills the process - and with it the speech service.
            Log.w(TAG, "Install of " + key + " failed", e);
            deleteRecursively(staging);
            return Result.FAILED;
        } finally {
            if (target != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            forget(prefs, id, key);
            try {
                dm.remove(id); // drops the completed-download entry (file already gone)
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot remove download " + id, e);
            }
        }
    }

    /** Finishes downloads whose completion broadcast was missed (process death). */
    static void reconcile(Context appContext, Context storageContext) {
        final SharedPreferences prefs = prefs(storageContext);
        for (String key : pendingKeys(storageContext)) {
            final long id = prefs.getLong(PREF_DL_KEY_PREFIX + key, -1);
            final Progress p;
            try {
                p = progress(appContext, storageContext, key);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot query download of " + key, e);
                continue;
            }
            if (p == null) {
                forget(prefs, id, key); // the system forgot it; so do we
                deleteRecursively(stagingDir(storageContext, key));
            } else if (p.status == DownloadManager.STATUS_SUCCESSFUL
                    || p.status == DownloadManager.STATUS_FAILED) {
                complete(appContext, storageContext, id);
            }
        }
    }

    /** voice key -> its INT8 NPU decoder (Snapdragon build), from sherpa-onnx-respin-syspin. */
    static final String NPU_DECODERS_ASSET = "piper/npu_decoders.json";

    /**
     * Snapdragon build: gives every installed voice that has one its INT8 NPU
     * decoder (~15 MB, any network), checksum-verified, then reloads the voice
     * so PiperModel picks it up. Cheap when all are present: run it at every
     * service start and after an install. Blocking; off the main thread.
     */
    static void fetchNpuDecoders(Context appContext, Context storageContext) {
        if (!PiperModel.hasNpuRuntime()) {
            return;
        }
        final JSONObject list;
        try (InputStream in = appContext.getAssets().open(NPU_DECODERS_ASSET)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            list = new JSONObject(buf.toString("UTF-8"));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "NPU decoder list unavailable", e);
            return;
        }
        final String base = list.optString("base_url", "");
        final JSONObject voices = list.optJSONObject("voices");
        if (voices == null || !RESPIN_SYSPIN_RELEASES.equals(base)) {
            return;
        }
        for (PiperVoiceStore.Installed v : PiperVoiceStore.list(storageContext)) {
            final JSONObject f = voices.optJSONObject(v.key);
            if (f == null || !isSafePath(f.optString("path", ""))) {
                continue;
            }
            final String md5 = f.optString("md5_digest", "");
            final File target = new File(v.dir, PiperModel.NPU_DECODER_FILE);
            final File stamp = new File(v.dir, PiperModel.NPU_DECODER_FILE + ".md5");
            try {
                if (target.isFile() && stamp.isFile()
                        && md5.equalsIgnoreCase(PiperVoiceStore.readText(stamp).trim())) {
                    continue;
                }
                final byte[] data = fetch(base + f.getString("path"), 64 * 1024 * 1024);
                if (!md5.equalsIgnoreCase(md5(data))) {
                    throw new IOException("NPU decoder checksum mismatch for " + v.key);
                }
                writeAtomically(target, data);
                writeAtomically(stamp, md5.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "NPU decoder for " + v.key + " installed");
                broadcastChanged(appContext, v.key, null);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "NPU decoder for " + v.key + " not fetched; tried again later", e);
            }
        }
    }

    /** Model and config MD5s an installed voice came from (one per line). */
    static final String SOURCE_FILE = "source";
    private static final String PREF_UPDATE_CHECKED = "piper_update_checked";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * Voice-file auto-update: at most daily, re-downloads every installed
     * voice whose catalog files changed (a fixed model, a corrected config).
     * It downloads at once, on any network; complete() swaps the voice
     * in only after its checksum matches, so the old one keeps speaking until
     * then. Blocking (catalog fetch, hashing older installs once): call off
     * the main and speech threads.
     *
     * @return keys whose update started
     */
    static List<String> checkForUpdates(Context appContext, Context storageContext) {
        final List<String> started = new ArrayList<>();
        final SharedPreferences prefs = prefs(storageContext);
        final long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_UPDATE_CHECKED, 0) < UPDATE_CHECK_INTERVAL_MS) {
            return started;
        }
        final List<PiperVoiceStore.Installed> installed = PiperVoiceStore.list(storageContext);
        if (installed.isEmpty()) {
            return started;
        }
        final List<CatalogVoice> catalog;
        try {
            catalog = loadCatalog(storageContext, false);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Update check: catalog unavailable", e);
            return started; // offline: tried again next start
        }
        prefs.edit().putLong(PREF_UPDATE_CHECKED, now).apply();
        final List<String> pending = pendingKeys(storageContext);
        for (PiperVoiceStore.Installed v : installed) {
            CatalogVoice entry = null;
            for (CatalogVoice c : catalog) {
                if (c.key.equals(v.key)) {
                    entry = c;
                    break;
                }
            }
            if (entry == null || entry.modelMd5 == null || entry.configMd5 == null
                    || pending.contains(v.key)) {
                continue; // no longer listed, unpinned, or already downloading
            }
            final String[] source = installedSource(v);
            if (source == null || (entry.modelMd5.equalsIgnoreCase(source[0])
                    && entry.configMd5.equalsIgnoreCase(source[1]))) {
                continue;
            }
            try {
                start(appContext, storageContext, entry, true);
                started.add(v.key);
                Log.i(TAG, "Update for " + v.key + " started");
            } catch (IOException | JSONException | UnsupportedVoiceException | RuntimeException e) {
                Log.w(TAG, "Update of " + v.key + " not started", e);
            }
        }
        return started;
    }

    /** {model MD5, config MD5} of an installed voice; hashed once for voices from before. */
    private static String[] installedSource(PiperVoiceStore.Installed v) {
        final File source = new File(v.dir, SOURCE_FILE);
        try {
            if (source.isFile()) {
                final String[] lines = PiperVoiceStore.readText(source).trim().split("\n");
                if (lines.length == 2) {
                    return lines;
                }
            }
            final File model = v.model();
            final String[] computed = {md5(model), md5(new File(v.dir, PiperVoiceStore.CONFIG_FILE))};
            writeAtomically(source, (computed[0] + "\n" + computed[1]).getBytes(StandardCharsets.UTF_8));
            return computed;
        } catch (IOException e) {
            Log.w(TAG, "Cannot hash " + v.key, e);
            return null;
        }
    }

    static void broadcastChanged(Context context, String key, String assignedLanguage) {
        final Intent intent = new Intent(ACTION_VOICES_CHANGED).setPackage(context.getPackageName());
        if (key != null) {
            intent.putExtra(EXTRA_KEY, key);
        }
        if (assignedLanguage != null) {
            intent.putExtra(EXTRA_ASSIGNED_LANGUAGE, assignedLanguage);
        }
        context.sendBroadcast(intent);
    }

    private static void forget(SharedPreferences prefs, long id, String key) {
        prefs.edit().remove(PREF_DL_ID_PREFIX + id).remove(PREF_DL_KEY_PREFIX + key).apply();
    }

    /** Bookkeeping lives with the other settings, in device-protected storage. */
    private static SharedPreferences prefs(Context storageContext) {
        return settingsPrefs(storageContext);
    }

    private static SharedPreferences settingsPrefs(Context storageContext) {
        return PreferenceManager.getDefaultSharedPreferences(storageContext);
    }

    /** MD5 of a file, streamed (models are 40-200 MB). */
    static String md5(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String copyWithMd5(File from, File to) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        final File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        if (!tmp.renameTo(to)) {
            throw new IOException("Cannot rename " + tmp);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static void writeAtomically(File file, byte[] data) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        final File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("Cannot write " + file);
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
