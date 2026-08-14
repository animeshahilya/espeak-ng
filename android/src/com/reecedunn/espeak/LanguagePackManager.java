/*
 * Copyright (C) 2026
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

package com.reecedunn.espeak;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and installs the "extra languages" pack: everything outside the
 * English/Indic/Dravidian set bundled directly in the APK (see
 * android/build.gradle's splitLanguageData task). The pack is built by
 * ./gradlew createExtraDataArchive and published as a GitHub Release asset
 * on this fork; nothing about the split or the download source is upstream
 * espeak-ng behaviour.
 *
 * Reuses exactly the same on-disk layout and reload path as the base voice
 * data and the existing "import a dictionary" feature: extracted files land
 * in CheckVoiceData.getDataPath()'s parent, and BROADCAST_LANGUAGES_UPDATED
 * tells a running TtsService to pick up the new voices without a restart.
 */
public class LanguagePackManager {
    private static final String TAG = "LanguagePackManager";

    private static final String DOWNLOAD_URL =
            "https://github.com/animeshahilya/espeak-ng/releases/latest/download/espeakdata-extra.zip";

    private static final String PREF_EXTRA_LANGUAGES_INSTALLED = "espeak_extra_languages_installed";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;

    public interface Callback {
        /** Called repeatedly on the main thread; percent is -1 if the server didn't report a size. */
        void onProgress(int percent);

        /** Called once on the main thread when the download finishes, either way. */
        void onComplete(boolean success, String errorMessage);
    }

    public static boolean isInstalled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_EXTRA_LANGUAGES_INSTALLED, false);
    }

    public static void download(final Context context, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                String errorMessage = null;
                File tempFile = null;
                try {
                    tempFile = File.createTempFile("espeakdata-extra", ".zip", appContext.getCacheDir());
                    downloadToFile(DOWNLOAD_URL, tempFile, mainHandler, callback);
                    FileUtils.extractZip(
                            new java.io.FileInputStream(tempFile),
                            CheckVoiceData.getDataPath(appContext).getParentFile());
                    PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                            .putBoolean(PREF_EXTRA_LANGUAGES_INSTALLED, true)
                            .apply();
                    success = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to download/install the language pack", e);
                    errorMessage = e.getMessage();
                } finally {
                    if (tempFile != null) {
                        tempFile.delete();
                    }
                }

                if (success) {
                    appContext.sendBroadcast(new Intent(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED));
                }

                final boolean finalSuccess = success;
                final String finalError = errorMessage;
                if (callback != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onComplete(finalSuccess, finalError);
                        }
                    });
                }
            }
        }, "language-pack-download").start();
    }

    private static void downloadToFile(String urlString, File outFile, Handler mainHandler, Callback callback)
            throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        try {
            final int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP status " + status + " downloading language pack");
            }
            final int contentLength = connection.getContentLength();

            final InputStream in = connection.getInputStream();
            try {
                final OutputStream out = new FileOutputStream(outFile);
                try {
                    final byte[] buffer = new byte[16384];
                    long totalRead = 0;
                    int lastPercent = -1;
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        if (contentLength > 0 && callback != null) {
                            final int percent = (int) Math.min(100, totalRead * 100 / contentLength);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int finalPercent = percent;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        callback.onProgress(finalPercent);
                                    }
                                });
                            }
                        }
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } finally {
            connection.disconnect();
        }
    }
}
