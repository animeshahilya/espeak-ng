/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.piper.VoiceImport;
import com.animeshahilya.espeakng.text.AsciiUtils;
import com.animeshahilya.espeakng.tts.CheckVoiceData;
import com.animeshahilya.espeakng.tts.ImportedVariants;
import com.animeshahilya.espeakng.tts.DownloadVoiceData;
import com.animeshahilya.espeakng.util.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Files the user picks for the settings screen (dictionaries, backups, voice profiles, voice data): read or written off the main thread, with the result reported on it.
 */
public final class PickedFiles {
    private static final String TAG = "PickedFiles";

    private PickedFiles() {
    }

    /** Unit of background work for {@link #runInBackground}. */
    public interface BackgroundWork<T> {
        T run();
    }

    /** Main-thread continuation for {@link #runInBackground}. */
    public interface BackgroundDone<T> {
        void done(T result);
    }

    /** Runs {@code work} on a worker thread, then {@code done} on the main thread. */
    private static <T> void runInBackground(final BackgroundWork<T> work, final BackgroundDone<T> done) {
        EspeakApp.runAsync(() -> {
            final T result = work.run();
            new Handler(Looper.getMainLooper()).post(() -> done.done(result));
        });
    }

    public interface ReadPicked<T> {
        T from(InputStream in) throws Exception;
    }

    public interface WritePicked {
        void to(OutputStream out) throws Exception;
    }

    /**
     * Reads the file the user picked off the main thread; {@code done} gets
     * {@code failed} when it cannot be read, and is skipped once the screen is gone.
     */
    public static <T> void readPicked(Activity activity, Uri uri, String what, T failed, ReadPicked<T> read,
                                       BackgroundDone<T> done) {
        runInBackground(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                return in != null ? read.from(in) : failed;
            } catch (Exception e) {
                Log.e(TAG, what + " import failed", e);
                return failed;
            }
        }, result -> {
            if (!TtsSettingsActivity.isGone(activity)) {
                done.done(result);
            }
        });
    }

    /** Writes the file the user picked off the main thread, then says whether it worked. */
    public static void writePicked(Activity activity, Uri uri, String what, WritePicked write,
                                    int doneRes, int failedRes) {
        runInBackground(() -> {
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    return false;
                }
                write.to(out);
                return true;
            } catch (Exception e) {
                Log.e(TAG, what + " export failed", e);
                return false;
            }
        }, ok -> {
            if (!TtsSettingsActivity.isGone(activity)) {
                Toast.makeText(activity, ok ? doneRes : failedRes, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                // Ignore fallback
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result != null ? result : "imported_data";
    }

    /** Installs the natural voice the user picked (an archive, or its files); says how it went. */
    public static void importNaturalVoice(final Activity activity, final java.util.List<Uri> uris) {
        importNaturalVoice(activity, uris, null);
    }

    /**
     * As above, in {@code language} where the voice's files do not say. If
     * they do not and {@code language} is null, the user picks it (VoxSherpa
     * asks for every import; here only when it is needed).
     */
    private static void importNaturalVoice(final Activity activity, final java.util.List<Uri> uris,
                                           final String language) {
        final Context storage = EspeakApp.requireStorageContext(activity);
        final Context app = activity.getApplicationContext();
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (Uri uri : uris) {
            names.add(getFileNameFromUri(activity, uri));
        }
        Toast.makeText(activity, R.string.piper_import_started, Toast.LENGTH_LONG).show();
        runInBackground(() -> {
            try {
                final PiperVoiceStore.Installed v = VoiceImport.install(app, storage, uris, names, language);
                return activity.getString(R.string.piper_import_done, v.config.displayName(),
                        PiperSettings.languageName(v.config.languageFamily));
            } catch (VoiceImport.ImportException e) {
                Log.w(TAG, "Voice import refused: " + e.getMessage());
                return e.messageRes == R.string.piper_import_language && language == null
                        ? null : activity.getString(e.messageRes);
            }
        }, message -> {
            if (TtsSettingsActivity.isGone(activity)) {
                return;
            }
            if (message == null) {
                askLanguage(activity, chosen -> importNaturalVoice(activity, uris, chosen));
            } else {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Languages letter voices exist for that have no two-letter code (SYSPIN/Rasa). */
    private static final String[] THREE_LETTER_LANGUAGES = {"bho", "brx", "doi", "hne", "mag", "mai"};

    /** Every language the phone can name, by name; {@code then} gets the chosen ISO code. */
    private static void askLanguage(Activity activity, java.util.function.Consumer<String> then) {
        final java.util.Locale ui = java.util.Locale.getDefault();
        final java.util.List<String[]> languages = new java.util.ArrayList<>();
        final java.util.List<String> codes = new java.util.ArrayList<>(
                java.util.Arrays.asList(java.util.Locale.getISOLanguages()));
        codes.addAll(java.util.Arrays.asList(THREE_LETTER_LANGUAGES));
        for (String code : codes) {
            final String name = new java.util.Locale(code).getDisplayLanguage(ui);
            if (!name.isEmpty() && !name.equalsIgnoreCase(code)) {
                languages.add(new String[] {code, name});
            }
        }
        final java.text.Collator collator = java.text.Collator.getInstance(ui);
        languages.sort((a, b) -> collator.compare(a[1], b[1]));
        final String[] labels = new String[languages.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = languages.get(i)[1];
        }
        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.piper_import_language)
                        .setItems(labels, (d, which) -> then.accept(languages.get(which)[0]))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
        SettingsDialogs.markAlertTitleHeading(dialog);
    }

    /** Up to {@code max} bytes from the stream (fewer only at its end). */
    private static byte[] readUpTo(InputStream in, int max) throws java.io.IOException {
        final byte[] buf = new byte[max];
        int n = 0;
        int r;
        while (n < max && (r = in.read(buf, n, max - n)) > 0) {
            n += r;
        }
        return java.util.Arrays.copyOf(buf, n);
    }

    /** Voice data is ~30 MB in all; an import far past that is not voice data. */
    private static final long MAX_VOICE_IMPORT_BYTES = 200L * 1024 * 1024;

    public static void importVoiceUri(final Activity activity, final Uri uri) {
        final Context storage = EspeakApp.requireStorageContext(activity);
        final String[] variant = new String[1]; // set when the file was a voice variant
        runInBackground(new BackgroundWork<Boolean>() {
            @Override public Boolean run() {
                boolean success = false;
                String fileName = getFileNameFromUri(activity, uri);
                File targetDir = CheckVoiceData.getDataPath(storage);
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                // The extraction lock: the engine reads this tree, and a
                // data refresh may be rewriting it at the same moment.
                synchronized (CheckVoiceData.EXTRACT_LOCK) {
                try (InputStream inputStream = activity.getContentResolver().openInputStream(uri)) {
                    if (inputStream != null) {
                        if (AsciiUtils.endsWithIgnoreCase(fileName, ".zip")) {
                            FileUtils.extractZip(inputStream, targetDir, MAX_VOICE_IMPORT_BYTES);
                            success = true;
                        } else {
                            File outFile = new File(targetDir, fileName);
                            // Same containment guarantee FileUtils.extractZip
                            // enforces per entry: a crafted display name (or
                            // one reported by a content provider) must not be
                            // able to write outside the voice data directory.
                            if (outFile.getCanonicalPath()
                                    .startsWith(targetDir.getCanonicalPath() + File.separator)) {
                                // A small text file may be a voice variant (NVDA users
                                // share them); it goes where eSpeak finds variants.
                                final byte[] head = readUpTo(inputStream, ImportedVariants.MAX_BYTES + 1);
                                if (head.length <= ImportedVariants.MAX_BYTES) {
                                    variant[0] = ImportedVariants.add(storage, fileName, head);
                                }
                                if (variant[0] != null) {
                                    success = true;
                                } else {
                                    try (OutputStream fos = new FileOutputStream(outFile)) {
                                        fos.write(head);
                                        byte[] buffer = new byte[8192];
                                        int len;
                                        while ((len = inputStream.read(buffer)) > 0) {
                                            fos.write(buffer, 0, len);
                                        }
                                        success = true;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error importing voice data from URI", e);
                    success = false;
                }
                }
                return success;
            }
        }, new BackgroundDone<Boolean>() {
            @Override public void done(Boolean finalSuccess) {
                if (TtsSettingsActivity.isGone(activity)) return;
                if (finalSuccess) {
                    LanguageLabels.forgetLangInfo();
                    final Intent updated =
                            new Intent(DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED);
                    // Same scoping as DownloadVoiceData: only this
                    // app's TtsService should act on it.
                    updated.setPackage(activity.getPackageName());
                    activity.sendBroadcast(updated);
                    Toast.makeText(activity, variant[0] != null
                            ? activity.getString(R.string.import_variant_success, variant[0])
                            : activity.getString(R.string.import_voice_success), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.import_voice_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
