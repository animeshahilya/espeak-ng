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

package com.animeshahilya.espeakng.tts;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Voice variants users import (Backup and about -> Import): the small text
 * files NVDA's eSpeak users share ("Glenn", "Bobby"...). Before, such a file
 * landed at the top of the data folder, where eSpeak never looks. Each is
 * kept in {@code files/variants/} and copied into the data's
 * {@code voices/!v/}, again after every data extraction (an app update
 * deletes and re-extracts that tree). The picker lists them under
 * "Imported" ({@code VoiceVariantPreference}).
 */
public final class ImportedVariants {
    private static final String TAG = "ImportedVariants";
    public static final int MAX_BYTES = 16 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    /** A variant line: a keyword, then values (eSpeak's voice file format). */
    private static final Pattern LINE = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(\\s.*)?");
    /** Lines only a variant has; a file must have one. */
    private static final Pattern SOUND = Pattern.compile(
            "(formant|pitch|klatt|breath|breathw|flutter|tone|echo|roughness|voicing|consonants)(\\s.*)?");

    private static volatile List<String> sNames = Collections.emptyList();

    private ImportedVariants() {
    }

    private static File dir(Context storage) {
        return new File(storage.getFilesDir(), "variants");
    }

    /** The imported variants' names, sorted (as last read by {@link #refresh}). */
    public static List<String> names() {
        return sNames;
    }

    public static void refresh(Context storage) {
        final String[] files = dir(storage).list();
        final List<String> names = new ArrayList<>(files != null ? Arrays.asList(files) : Collections.emptyList());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        sNames = Collections.unmodifiableList(names);
    }

    /** True when {@code text} reads as an eSpeak voice variant file. */
    static boolean looksLikeVariant(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false; // binary (trim() below would hide it)
            }
        }
        boolean sound = false;
        int lines = 0;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            final int comment = line.indexOf("//");
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (!LINE.matcher(line).matches()) {
                return false;
            }
            sound |= SOUND.matcher(line).matches();
            lines++;
        }
        return sound && lines > 0;
    }

    /**
     * Imports {@code bytes} as a variant named after its file, when they are
     * one. Returns the name it was added under, or null when the file is not
     * a variant. A name the bundled data already uses gets "-2".
     */
    public static String add(Context storage, String fileName, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES || !looksLikeVariant(new String(bytes, StandardCharsets.UTF_8))) {
            return null;
        }
        String name = fileName == null ? "" : fileName.replaceAll("\\.[A-Za-z0-9]{1,4}$", "")
                .replaceAll("[^A-Za-z0-9_-]", "_");
        if (!NAME.matcher(name).matches()) {
            name = "imported";
        }
        final File bundled = new File(variantsIn(CheckVoiceData.getDataPath(storage)), name);
        if (bundled.exists() && !new File(dir(storage), name).exists()) {
            name = name + "-2";
        }
        final File target = new File(dir(storage), name);
        target.getParentFile().mkdirs();
        write(target, bytes);
        write(new File(variantsIn(CheckVoiceData.getDataPath(storage)), name), bytes);
        refresh(storage);
        return name;
    }

    /** Copies every imported variant into a freshly extracted data tree. */
    static void restore(Context storage, File dataPath) {
        final File[] files = dir(storage).listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                write(new File(variantsIn(dataPath), f.getName()), Files.readAllBytes(f.toPath()));
            } catch (IOException e) {
                Log.w(TAG, "Could not restore variant " + f.getName(), e);
            }
        }
    }

    private static File variantsIn(File dataPath) {
        return new File(new File(dataPath, "voices"), "!v");
    }

    private static void write(File file, byte[] bytes) throws IOException {
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
