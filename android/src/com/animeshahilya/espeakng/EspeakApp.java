/*
 * Copyright (C) 2022 Beka Gozalishvili
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

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

public class EspeakApp extends Application {

    private static final String TAG = EspeakApp.class.getSimpleName();

    /**
     * Written to device-protected storage the first time
     * {@link #migrateLegacyPreferences} runs with the credential-encrypted file
     * readable, so that file is never empty again after the first unlocked
     * start and a stray credential-encrypted file can never be adopted.
     */
    public static final String PREF_PREFERENCES_MIGRATED = "espeak_preferences_migrated";

    @SuppressLint("StaticFieldLeak")
    private static Context storageContext;

    /**
     * Natural-voice events, logged whichever component loaded the voice
     * (the TTS service, the settings screen's test, the download receiver).
     */
    /** Phrase cache counts since install (device-local numbers, no text). */
    static final String PREF_CACHE_HITS = "piper_cache_hits_total";
    static final String PREF_CACHE_CHUNKS = "piper_cache_chunks_total";
    private static long sReportedHits;
    private static long sReportedChunks;

    private static final PiperEngine.Listener PIPER_LOG = new PiperEngine.Listener() {
        private static final String TAG = "PiperVoices";

        @Override
        public void onLoaded(String key, long millis, String npu) {
            Log.i(TAG, "Natural voice " + key + " loaded in " + millis + " ms"
                    + (npu != null ? ", decoder " + npu : ""));
        }

        @Override
        public void onLoadFailed(String key, Throwable error) {
            Log.e(TAG, "Natural voice " + key + " failed to load; using eSpeak", error);
        }

        @Override
        public void onPhraseCache(long hits, long misses, long fromStorage, double hitMs, double missMs,
                                  long bytes, int entries) {
            // Numbers only, never text: the cache holds what was spoken.
            long totalHits = hits;
            long totalChunks = hits + misses;
            final Context storage = storageContext;
            if (storage != null) {
                // Across service restarts: add what this process counted since its last report.
                final android.content.SharedPreferences prefs =
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(storage);
                synchronized (this) {
                    totalHits = prefs.getLong(PREF_CACHE_HITS, 0) + hits - sReportedHits;
                    totalChunks = prefs.getLong(PREF_CACHE_CHUNKS, 0) + hits + misses - sReportedChunks;
                    sReportedHits = hits;
                    sReportedChunks = hits + misses;
                    prefs.edit().putLong(PREF_CACHE_HITS, totalHits).putLong(PREF_CACHE_CHUNKS, totalChunks)
                            .apply();
                }
            }
            Log.i(TAG, String.format(java.util.Locale.ROOT,
                    "Phrase cache: %d of %d chunks from cache (%.0f%%; %d read back from storage),"
                            + " first audio %.0f ms vs %.0f ms"
                            + " rendered; %d entries, %.1f MB; since install %d of %d (%.0f%%)",
                    hits, hits + misses, 100.0 * hits / Math.max(1, hits + misses), fromStorage, hitMs, missMs,
                    entries, bytes / 1048576.0, totalHits, totalChunks,
                    100.0 * totalHits / Math.max(1, totalChunks)));
        }

        @Override
        public void onMissingPhonemes(String key, java.util.List<String> phonemes) {
            // Phonemes this fork's rules produce that the voice has no id
            // for; they are skipped. Logged once per voice per process.
            Log.w(TAG, "Natural voice " + key + " has no ids for phonemes " + phonemes);
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate() {
        super.onCreate();
        // Debug only: log (never crash on) main-thread disk/network use and
        // leaked closables. This engine's whole design is "heavy work off the
        // synth and UI threads" (worker extraction, lazy maps, apply() not
        // commit()); a future disk read sneaking onto either thread shows up
        // here first instead of as a user-visible stutter or ANR.
        if (BuildConfig.DEBUG) {
            try {
                android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .build());
                android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        .build());
            } catch (Throwable ignored) {
            }
        }
        // DynamicColors intentionally not applied: wallpaper-derived palettes
        // clash with the fixed brand-blue roles on AppTheme.Base (e.g. a pink
        // action bar against blue chips/fields). Ship one consistent palette.
        final Context appContext = getApplicationContext();
        EspeakApp.storageContext = appContext.createDeviceProtectedStorageContext();
        NvdaEmoji.init(appContext);
        final android.content.res.AssetManager assets = appContext.getAssets();
        NvdaSymbolProcessor.setDataSource(name -> assets.open("symbols/" + name));
        NvdaCharacterDescriptions.setDataSource(name -> assets.open("chardesc/" + name));
        HinglishReader.init(appContext);
        migrateLegacyPreferences(appContext, EspeakApp.storageContext);
        PiperEngine.get().setListener(PIPER_LOG);
        // Phrases survive the speech service restarting (device-protected, not backed up).
        PiperEngine.get().setPhraseCacheDir(new java.io.File(PiperVoiceStore.root(EspeakApp.storageContext),
                "phrase-cache"));
        // Before any voice can load: a voice that crashed the last process
        // twice in a row is suspended instead of crash-looping TalkBack.
        // Tolerant: a wrongly typed setting must not crash every process start.
        final android.content.SharedPreferences settings = TolerantPreferences.of(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(EspeakApp.storageContext));
        final PiperCrashGuard crashGuard = new PiperCrashGuard(EspeakApp.storageContext, settings);
        crashGuard.checkPreviousExit(appContext);
        PiperEngine.get().setNativeGuard(crashGuard);
        PiperEngine.get().setMaxLoaded(PiperDevice.voicesKeptLoaded(PiperDevice.tier(appContext)));
        if (!appContext.getSystemService(UserManager.class).isUserUnlocked()) {
            // Started at boot, for a direct-boot-aware screen reader. The
            // credential-encrypted file cannot be read yet, so run the
            // migration again when the user unlocks instead of leaving it
            // to the next process restart, which may be days away.
            // ACTION_USER_UNLOCKED is a protected system broadcast only this
            // app's own process acts on: register not-exported on API 33+,
            // same pattern as TtsService's languages-updated receiver. The
            // 3-arg overload requires Tiramisu (minSdk 26), so fall back to
            // the unflagged overload below it (unexported by default there).
            BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    appContext.unregisterReceiver(this);
                    migrateLegacyPreferences(appContext, EspeakApp.storageContext);
                }
            };
            IntentFilter unlockFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(unlockReceiver, unlockFilter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(unlockReceiver, unlockFilter);
            }
        }
    }

    /**
     * Adopts the preferences of an install that predates device-protected
     * storage (f7f66429, 2022) and still keeps them in the credential-encrypted
     * file. Runs at process start, before any component reads the settings,
     * and again on unlock when the process started before the user unlocked.
     *
     * Everything in this app reads and writes the device-protected file, and
     * {@link #PREF_PREFERENCES_MIGRATED} lands there the first time the
     * credential-encrypted file is readable, so once the device-protected file
     * holds anything it is the source of truth and there is nothing left to
     * migrate. A credential-encrypted file that shows up after that point can
     * only be a stray write through a plain Context, and
     * moveSharedPreferencesFrom() would copy it over the user's settings: that
     * is how #2536 wiped every setting on each screen reader restart. Such a
     * file is discarded instead, which turns that class of bug into a setting
     * that does not take effect -- visible, and harmless.
     */
    public static void migrateLegacyPreferences(Context appContext, Context storageContext) {
        // AndroidX made getDefaultSharedPreferencesName() private; both
        // frameworks compute it as packageName + "_preferences", so spell it
        // out (the migration must find the exact legacy file).
        final String name = appContext.getPackageName() + "_preferences";
        if (!storageContext.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty()) {
            appContext.deleteSharedPreferences(name);
            return;
        }
        if (!appContext.getSystemService(UserManager.class).isUserUnlocked()) {
            // Credential-encrypted storage is not readable before the first
            // unlock; onCreate() retries this on ACTION_USER_UNLOCKED.
            return;
        }
        storageContext.moveSharedPreferencesFrom(appContext, name);
        // The move evicts the cached instance, so fetch the file anew.
        storageContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_PREFERENCES_MIGRATED, true).apply();
    }


    public static Context getStorageContext() {
        return EspeakApp.storageContext;
    }

    /**
     * Non-null device-protected storage context, falling back to a plain
     * context when the Application hasn't run onCreate() yet (e.g. a test
     * or a provider started before it). Centralizes the fallback that used
     * to be re-implemented at every call site.
     */
    public static Context requireStorageContext(Context fallback) {
        if (EspeakApp.storageContext != null) {
            return EspeakApp.storageContext;
        }
        if (fallback != null) {
            if (!fallback.isDeviceProtectedStorage()) {
                return fallback.createDeviceProtectedStorageContext();
            }
            return fallback;
        }
        return null;
    }

    private static final java.util.concurrent.ExecutorService BACKGROUND_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "espeak-bg-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Shared background executor for one-off and lightweight async app tasks. */
    public static java.util.concurrent.ExecutorService getBackgroundExecutor() {
        return BACKGROUND_EXECUTOR;
    }

    /** Dispatches a task to the shared background worker pool. */
    public static void runAsync(Runnable task) {
        BACKGROUND_EXECUTOR.execute(task);
    }
}
