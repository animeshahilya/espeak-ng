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

package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;

/**
 * Troubleshooting &amp; logs: 1-tap activity log collection. Gathers device
 * info, app version, preference snapshot, and recent logcat output into a
 * single shareable text blob (no file permissions needed — shared via
 * ACTION_SEND EXTRA_TEXT).
 */
public final class LogExporter {
    private static final String TAG = "LogExporter";

    private LogExporter() {
    }

    // versionCode was deprecated in API 28 in favor of getLongVersionCode().
    @SuppressWarnings("deprecation")
    private static long versionCodeOf(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.getLongVersionCode();
        }
        return pi.versionCode;
    }

    private static final String[] EXIT_REASONS = {"unknown", "exited", "signaled",
            "low memory", "crash", "native crash", "ANR", "init failure", "permission change",
            "excessive resources", "user requested", "user stopped", "dependency died", "other",
            "frozen", "package state change", "package updated"};

    /** ApplicationExitInfo.REASON_* by value (0-16). */
    private static String exitReason(int reason) {
        return reason >= 0 && reason < EXIT_REASONS.length ? EXIT_REASONS[reason] : "reason " + reason;
    }

    public static String collect(Context context) {
        StringBuilder sb = new StringBuilder(32768);
        sb.append("=== eSpeak NG Advanced — activity log ===\n");
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("App: ").append(pi.packageName)
                    .append(" v").append(pi.versionName)
                    .append(" (").append(versionCodeOf(pi)).append(")\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("App: unknown\n");
        }
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" SDK=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Locale: ").append(Locale.getDefault()).append('\n');
        sb.append("Time: ").append(new java.util.Date()).append("\n\n");

        try {
            Context storage = EspeakApp.requireStorageContext(context);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);
            sb.append("--- Preferences ---\n");
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (ReadingHistory.PREF_HISTORY_ITEMS.equals(e.getKey())) {
                    // What the phone read aloud (messages, names, codes): never
                    // in a log people share for troubleshooting.
                    sb.append(e.getKey()).append("=(not included)\n");
                    continue;
                }
                sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append('\n');
            }
            sb.append('\n');
        } catch (Throwable t) {
            sb.append("Prefs unavailable: ").append(t).append('\n');
        }

        try {
            int ruleCount = UserDictionaryManager.getInstance(context).getRules().size();
            sb.append("User dictionary rules: ").append(ruleCount).append("\n\n");
        } catch (Throwable t) {
            sb.append("Dictionary unavailable: ").append(t).append("\n\n");
        }

        // logcat below only reaches back to this process's start, so a
        // crash that restarted the speech service is only visible here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                sb.append("--- Recent process exits ---\n");
                for (android.app.ApplicationExitInfo e : context.getSystemService(
                        android.app.ActivityManager.class)
                        .getHistoricalProcessExitReasons(null, 0, 10)) {
                    sb.append(new java.util.Date(e.getTimestamp())).append(' ')
                            .append(e.getProcessName()).append(' ').append(exitReason(e.getReason()))
                            .append(" status=").append(e.getStatus())
                            .append(' ').append(e.getDescription()).append('\n');
                }
                sb.append('\n');
            } catch (Throwable t) {
                sb.append("Exit reasons unavailable: ").append(t).append("\n\n");
            }
        }

        sb.append("--- logcat (last ~500 lines, eSpeak + AndroidRuntime) ---\n");
        BufferedReader br = null;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-v", "brief", "*:W"});
            br = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            // Keep only the tail to bound size.
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>(512);
            String line;
            while ((line = br.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > 500) tail.removeFirst();
            }
            for (String l : tail) sb.append(l).append('\n');
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
        } catch (Throwable t) {
            sb.append("logcat unavailable: ").append(t).append('\n');
            Log.w(TAG, "logcat failed", t);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
            // A Process has three streams regardless of whether the caller uses
            // them - stdin (getOutputStream()) is never written to here, but it's
            // still an open file descriptor until explicitly closed or the
            // Process object is finalized by the GC, which isn't bounded. Same
            // for stderr: not draining it risks the child process blocking on a
            // full pipe if logcat ever writes enough there (unlikely for "-d"
            // but cheap to close either way). Every export call otherwise leaks
            // two native fds until GC gets around to it.
            if (p != null) {
                try {
                    p.getOutputStream().close();
                } catch (Exception ignored) {
                }
                try {
                    p.getErrorStream().close();
                } catch (Exception ignored) {
                }
            }
        }
        // Bound share size for chooser targets.
        if (sb.length() > 120000) {
            return sb.substring(0, 120000) + "\n...[truncated]...\n";
        }
        return sb.toString();
    }
}
