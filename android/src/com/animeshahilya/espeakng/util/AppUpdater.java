/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.util;

import com.animeshahilya.espeakng.ui.SettingsDialogs;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import com.animeshahilya.espeakng.BuildConfig;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.animeshahilya.espeakng.piper.PiperDownloads;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * App updates for this sideloaded app, the way Tarang does them: a small
 * manifest ({@link #MANIFEST_URL}, latest.json in this public repo) names the
 * newest release; the settings screen checks it at most daily and from
 * About -> Check for updates, shows the release notes,
 * and Update opens the APK the running build is (standard or Snapdragon,
 * {@link BuildConfig#SNAPDRAGON}) in the browser; Android's installer takes
 * it from there. Never from the speech service: checks only happen while the
 * settings screen is open.
 */
public final class AppUpdater {
    private static final String TAG = "AppUpdater";

    /** Raw file, not the GitHub API: no rate limit, no JSON paging. */
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/animeshahilya/espeak-ng/android/latest.json";
    /** Release APKs live on GitHub only; a manifest naming another host is refused. */
    static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"));
    static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final String PREFS = "app_update";
    private static final String KEY_LAST_CHECKED = "last_checked";
    private static final String KEY_SKIPPED = "skipped_version_code";
    /** Left by 2.10's own downloads; see {@link #removeOldDownloads}. */
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_VERSION = "download_version_code";

    private AppUpdater() {
    }

    /** What latest.json says, for this build's kind of APK. */
    static final class Release {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String notes;

        Release(int versionCode, String versionName, String apkUrl, String sha256, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    /**
     * Parses latest.json for the standard or the Snapdragon APK; null when it
     * names no APK for this kind, or its URL or hash is unusable.
     */
    static Release parse(String json, boolean snapdragon) throws JSONException {
        final JSONObject o = new JSONObject(json);
        final String url = o.optString(snapdragon ? "snapdragonApkUrl" : "apkUrl", "");
        final String sha = o.optString(snapdragon ? "snapdragonSha256" : "sha256", "");
        if (!allowedUrl(url) || !sha.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        return new Release(o.getInt("versionCode"), o.optString("versionName", ""), url,
                sha.toLowerCase(Locale.ROOT), o.optString("notes", ""));
    }

    static boolean allowedUrl(String url) {
        try {
            final java.net.URI u = new java.net.URI(url);
            return "https".equals(u.getScheme()) && u.getHost() != null
                    && ALLOWED_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    static boolean isCheckDue(long lastChecked, long now) {
        return now - lastChecked >= CHECK_INTERVAL_MS || now < lastChecked;
    }

    static boolean isNewer(Release r, int currentVersionCode) {
        return r != null && r.versionCode > currentVersionCode;
    }

    private static SharedPreferences prefs(Context context) {
        return EspeakApp.requireStorageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int currentVersionCode(Context context) {
        try {
            return (int) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (Exception e) {
            return BuildConfig.VERSION_CODE;
        }
    }

    /** Fetches latest.json; the newer release for this build, or null. Network: call off the main thread. */
    static Release fetchNewer(Context context) throws IOException, JSONException {
        prefs(context).edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply();
        final byte[] body = PiperDownloads.fetch(MANIFEST_URL + "?t=" + System.currentTimeMillis() / 60000,
                64 * 1024);
        final Release r = parse(new String(body, StandardCharsets.UTF_8), BuildConfig.SNAPDRAGON);
        return isNewer(r, currentVersionCode(context)) ? r : null;
    }

    /** Settings opened: at most daily, quiet unless a newer version (not skipped) is out. */
    public static void checkIfDue(Context activity) {
        EspeakApp.runAsync(() -> {
            final SharedPreferences prefs = prefs(activity);
            if (!isCheckDue(prefs.getLong(KEY_LAST_CHECKED, 0), System.currentTimeMillis())) {
                return;
            }
            removeOldDownloads(activity);
            try {
                final Release r = fetchNewer(activity);
                if (r != null && prefs.getInt(KEY_SKIPPED, 0) != r.versionCode) {
                    onMain(activity, () -> showAvailable(activity, r));
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.i(TAG, "Update check failed: " + e);
            }
        });
    }

    /** About -> Check for updates: always asks the network and always answers. */
    public static void checkNow(Context activity) {
        EspeakApp.runAsync(() -> {
            removeOldDownloads(activity);
            Release r = null;
            boolean failed = false;
            try {
                r = fetchNewer(activity);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.i(TAG, "Update check failed: " + e);
                failed = true;
            }
            final Release found = r;
            final boolean error = failed;
            onMain(activity, () -> {
                if (found != null) {
                    showAvailable(activity, found);
                } else {
                    message(activity, error ? activity.getString(R.string.update_check_failed)
                            : activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME));
                }
            });
        });
    }

    private static void onMain(Context activity, Runnable r) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!TtsSettingsActivity.isGone(activity)) {
                r.run();
            }
        });
    }

    private static AlertDialog show(MaterialAlertDialogBuilder b) {
        final AlertDialog d = b.show();
        SettingsDialogs.markAlertTitleHeading(d);
        return d;
    }

    private static void message(Context activity, String text) {
        show(new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null));
    }

    private static void showAvailable(Context activity, Release r) {
        final String notes = r.notes.isEmpty() ? "" : "\n\n" + r.notes;
        show(new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.update_available_title, r.versionName))
                .setMessage(activity.getString(R.string.update_available_body, BuildConfig.VERSION_NAME) + notes)
                .setPositiveButton(R.string.update_action_update, (d, w) -> startUpdate(activity, r))
                .setNegativeButton(R.string.update_action_later, null)
                .setNeutralButton(R.string.update_action_skip, (d, w) ->
                        prefs(activity).edit().putInt(KEY_SKIPPED, r.versionCode).apply()));
    }

    /**
     * Opens the APK's GitHub link in the browser, which downloads it; opening
     * the download hands it to Android's installer. The app does not download
     * or install APKs itself (it did in 2.10, with REQUEST_INSTALL_PACKAGES):
     * a speech engine that hears what the screen reader says and can also
     * install apps is what Play Protect blocked as an unsafe app.
     */
    private static void startUpdate(Context activity, Release r) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            message(activity, activity.getString(R.string.update_no_browser));
        }
    }

    /** Update APKs 2.10 downloaded itself: gone once the app no longer does that. */
    private static void removeOldDownloads(Context context) {
        final SharedPreferences prefs = prefs(context);
        final long id = prefs.getLong(KEY_DOWNLOAD_ID, -1);
        if (id < 0) {
            return;
        }
        final DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            dm.remove(id);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION).apply();
        FileUtils.deleteRecursively(new File(context.getExternalFilesDir(null), "updates"));
    }
}
