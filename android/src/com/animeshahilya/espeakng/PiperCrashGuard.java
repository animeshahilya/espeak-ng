/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps one bad natural voice from crash-looping the screen reader. A native
 * crash inside ONNX Runtime kills the whole process, Android restarts the
 * TTS service, the voice loads again and crashes again - TalkBack never
 * speaks. So the voices whose native work is in progress are written to a
 * file, and at the next start a native crash with a voice in that file is a
 * strike against it; two within {@link #WINDOW_MS} suspend the voice (eSpeak
 * speaks its language) until it is chosen again in Natural voices. Not "two
 * in a row": a crash that needs time to happen (a buffer freed by garbage
 * collection) lets several inferences succeed first in every process.
 */
final class PiperCrashGuard implements PiperEngine.NativeGuard {
    private static final String TAG = "PiperCrashGuard";
    /** "piper_crash_strikes_" + voice key: time of the voice's last native crash. */
    private static final String PREF_STRIKES = "piper_crash_strikes_";
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    /** "piper_suspended_" + voice key: set after repeated crashes, read by PiperVoiceStore. */
    static final String PREF_SUSPENDED = "piper_suspended_";

    private final File mFile;
    private final SharedPreferences mPrefs;
    /** Voice key -> native calls in progress (the loader and renderer can overlap). */
    private final Map<String, Integer> mInFlight = new LinkedHashMap<>();

    PiperCrashGuard(Context storageContext, SharedPreferences prefs) {
        mFile = new File(PiperVoiceStore.root(storageContext), "native_in_flight");
        mPrefs = prefs;
    }

    @Override
    public synchronized void enter(String key) {
        final Integer n = mInFlight.get(key);
        mInFlight.put(key, n == null ? 1 : n + 1);
        write();
    }

    @Override
    public synchronized void exit(String key) {
        final Integer n = mInFlight.get(key);
        if (n == null || n <= 1) {
            mInFlight.remove(key);
        } else {
            mInFlight.put(key, n - 1);
        }
        write();
    }

    /** No fsync: a process crash keeps the page cache, which is all this has to survive. */
    private void write() {
        try {
            if (mInFlight.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                mFile.delete();
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            mFile.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(mFile)) {
                out.write(String.join("\n", mInFlight.keySet()).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot record native work", e);
        }
    }

    /**
     * At process start, before any voice loads: charges the voices that were
     * mid-inference when the previous process died of a native crash.
     */
    void checkPreviousExit(Context appContext) {
        if (!mFile.isFile()) {
            return;
        }
        final String[] keys;
        try {
            keys = PiperVoiceStore.readText(mFile).split("\n");
        } catch (IOException e) {
            return;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }
        if (!diedOfNativeCrash(appContext)) {
            return; // killed while speaking (memory, force stop): not the voice's fault
        }
        final long now = System.currentTimeMillis();
        final SharedPreferences.Editor editor = mPrefs.edit();
        for (String key : keys) {
            if (key.isEmpty()) {
                continue;
            }
            // TolerantPreferences (EspeakApp): an older format reads as 0.
            final long last = mPrefs.getLong(PREF_STRIKES + key, 0);
            if (last > 0 && now - last < WINDOW_MS) {
                Log.e(TAG, "Natural voice " + key
                        + " crashed twice within 10 minutes; suspended, eSpeak speaks its language");
                editor.putBoolean(PREF_SUSPENDED + key, true).remove(PREF_STRIKES + key);
            } else {
                editor.putLong(PREF_STRIKES + key, now);
            }
        }
        // Synchronous: the voice that crashed may be about to load again.
        editor.commit();
    }

    /** API 30+ says why the last process ended; before that, assume the worst. */
    private static boolean diedOfNativeCrash(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        try {
            final List<ApplicationExitInfo> exits = context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(null, 0, 1);
            return !exits.isEmpty()
                    && exits.get(0).getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isSuspended(SharedPreferences prefs, String key) {
        return prefs.getBoolean(PREF_SUSPENDED + key, false);
    }

    /** Chosen again by the user: give it a fresh start. */
    static void clear(SharedPreferences prefs, String key) {
        prefs.edit().remove(PREF_SUSPENDED + key).remove(PREF_STRIKES + key).apply();
    }
}
