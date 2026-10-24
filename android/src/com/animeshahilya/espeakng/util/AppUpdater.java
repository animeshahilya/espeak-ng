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
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;

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
 * About -> Check for updates, shows the release notes, downloads the APK the
 * running build is (standard or Snapdragon, {@link BuildConfig#SNAPDRAGON})
 * with the system DownloadManager, checks its SHA-256 and hands it to the
 * package installer - which always asks the user, as Android requires for an
 * app outside a store. Never from the speech service: checks only happen
 * while the settings screen is open.
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
    private static final long DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1000;

    private static final String PREFS = "app_update";
    private static final String KEY_LAST_CHECKED = "last_checked";
    private static final String KEY_SKIPPED = "skipped_version_code";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_VERSION = "download_version_code";
    /** Inside getExternalFilesDir: no storage permission on any Android version; shared via FileProvider. */
    private static final String DIR = "updates";

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
            cleanUpInstalled(activity);
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
            cleanUpInstalled(activity);
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
     * The release the user chose Update for while the app could not install
     * apps: going to Android's setting closes every dialog, so coming back
     * would leave nothing to tap (found on a Galaxy S25 Ultra). {@link #onResume}
     * carries on once the permission is there. Process memory only: if Android
     * drops the process meanwhile, the next check offers it again.
     */
    private static volatile Release sAwaitingPermission;

    /** The settings screen came back to the front: an update waiting for the permission goes on. */
    public static void onResume(Context activity) {
        final Release r = sAwaitingPermission;
        if (r != null && activity.getPackageManager().canRequestPackageInstalls()) {
            sAwaitingPermission = null;
            startUpdate(activity, r);
        }
    }

    private static void startUpdate(Context activity, Release r) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            sAwaitingPermission = r;
            show(new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_permission_body)
                    .setPositiveButton(R.string.update_action_open_settings, (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            message(activity, activity.getString(R.string.update_permission_missing));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        final File ready = verifiedDownload(activity, r);
        if (ready != null) {
            showReady(activity, ready);
            return;
        }
        download(activity, r);
    }

    private static File apkFile(Context context, Release r) {
        return new File(context.getExternalFilesDir(DIR), "espeak-ng-" + r.versionCode + ".apk");
    }

    /** A copy of this release downloaded earlier and still intact, or null. */
    private static File verifiedDownload(Context context, Release r) {
        final File f = apkFile(context, r);
        return f.isFile() && r.sha256.equals(sha256(f)) ? f : null;
    }

    private static void download(Context activity, Release r) {
        final DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        final File target = apkFile(activity, r);
        removeOldDownloads(activity);
        final long id;
        try {
            id = dm.enqueue(new DownloadManager.Request(Uri.parse(r.apkUrl))
                    .setTitle(activity.getString(R.string.update_download_title, r.versionName))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(target)));
        } catch (RuntimeException e) {
            message(activity, activity.getString(R.string.update_download_failed));
            return;
        }
        prefs(activity).edit().putLong(KEY_DOWNLOAD_ID, id).putInt(KEY_DOWNLOAD_VERSION, r.versionCode).apply();

        final AlertDialog progress = show(new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.update_downloading_title, r.versionName))
                .setMessage(activity.getString(R.string.update_progress_unknown))
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    dm.remove(id);
                    prefs(activity).edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION).apply();
                }));
        final TextView text = progress.findViewById(android.R.id.message);
        if (text != null) {
            // TalkBack hears the percentage as it changes, without moving focus.
            ViewCompat.setAccessibilityLiveRegion(text, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        EspeakApp.runAsync(() -> {
            final long deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS;
            int lastTens = -1;
            String error = null;
            while (true) {
                int status;
                long done;
                long total;
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c == null || !c.moveToFirst()) {
                        status = -1; // removed: cancelled
                        done = total = 0;
                    } else {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    }
                }
                if (status == -1) {
                    return;
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    break;
                }
                if (status == DownloadManager.STATUS_FAILED || System.currentTimeMillis() > deadline) {
                    dm.remove(id);
                    error = activity.getString(R.string.update_download_failed);
                    break;
                }
                // Every 10%: often enough to see it move, rarely enough not to chatter.
                final int tens = total > 0 ? (int) (done * 10 / total) : -1;
                if (tens != lastTens) {
                    lastTens = tens;
                    final String line = total > 0
                            ? activity.getString(R.string.update_progress, tens * 10)
                            : activity.getString(R.string.update_progress_unknown);
                    onMain(activity, () -> progress.setMessage(line));
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (error == null && !r.sha256.equals(sha256(target))) {
                dm.remove(id);
                error = activity.getString(R.string.update_verify_failed);
            }
            final String failure = error;
            onMain(activity, () -> {
                progress.dismiss();
                if (failure != null) {
                    message(activity, failure);
                } else {
                    showReady(activity, target);
                }
            });
        });
    }

    /** A checkpoint before the installer, so it never appears out of nowhere. */
    private static void showReady(Context activity, File apk) {
        show(new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_ready_body)
                .setPositiveButton(R.string.update_action_install, (d, w) -> install(activity, apk))
                .setNegativeButton(R.string.update_action_not_now, null));
    }

    private static void install(Context activity, File apk) {
        try {
            final Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            message(activity, activity.getString(R.string.update_install_failed));
        }
    }

    /** Once the update (or a newer build) is installed, its APK goes. */
    private static void cleanUpInstalled(Context context) {
        if (prefs(context).getInt(KEY_DOWNLOAD_VERSION, 0) <= currentVersionCode(context)) {
            removeOldDownloads(context);
        }
    }

    /** At most one update APK on the phone: earlier ones go before a new download. */
    private static void removeOldDownloads(Context context) {
        final SharedPreferences prefs = prefs(context);
        final long id = prefs.getLong(KEY_DOWNLOAD_ID, -1);
        if (id >= 0) {
            final DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            try {
                dm.remove(id);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION).apply();
        final File dir = context.getExternalFilesDir(DIR);
        final File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static String sha256(File f) {
        try {
            return FileUtils.hash(f, "SHA-256");
        } catch (IOException e) {
            return null;
        }
    }
}
