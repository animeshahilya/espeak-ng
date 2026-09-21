/*
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtils {
    public static String read(File file) throws IOException {
        try (final FileInputStream stream = new FileInputStream(file)) {
            return readByteArray(stream, (int) file.length()).toString(StandardCharsets.UTF_8.name());
        }
    }

    public static String read(InputStream stream) throws IOException {
        return readByteArray(stream, stream.available()).toString(StandardCharsets.UTF_8.name());
    }

    public static String read(InputStream stream, int length) throws IOException {
        return readByteArray(stream, length).toString(StandardCharsets.UTF_8.name());
    }

    private static ByteArrayOutputStream readByteArray(InputStream stream, int length) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream(Math.max(length, 0));
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = stream.read(buffer)) != -1)
        {
            content.write(buffer, 0, bytesRead);
        }
        return content;
    }

    public static void write(File outputFile, String contents) throws IOException {
        write(outputFile, contents.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(File outputFile, byte[] contents) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(contents, 0, contents.length);
        }
    }

    /**
     * Extracts every entry of a zip stream under outputDir, rejecting any
     * entry whose path would land outside it (zip-slip). Shared by
     * CheckVoiceData (the base voice data bundled in the APK) and
     * TtsSettingsActivity's voice-import feature so both extraction paths
     * get the same path-traversal protection instead of two
     * independently-maintained copies of it.
     */
    public static void extractZip(InputStream stream, File outputDir) throws IOException {
        final ZipInputStream zipStream = new ZipInputStream(new BufferedInputStream(stream, 65536));
        try {
            final String canonicalOutputDirPath = outputDir.getCanonicalPath() + File.separator;
            final byte[] buffer = new byte[65536];
            final Set<File> createdDirs = new HashSet<>();
            int bytesRead;
            ZipEntry entry;

            while ((entry = zipStream.getNextEntry()) != null) {
                final File file = new File(outputDir, entry.getName());
                if (!file.getCanonicalPath().startsWith(canonicalOutputDirPath)) {
                    throw new SecurityException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (createdDirs.add(file)) {
                        file.mkdirs();
                    }
                    continue;
                }
                final File parent = file.getParentFile();
                if (parent != null && createdDirs.add(parent)) {
                    parent.mkdirs();
                }
                try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
                    while ((bytesRead = zipStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                zipStream.closeEntry();
            }
        } finally {
            zipStream.close();
        }
    }

    public static void rmdir(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }

        final File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                rmdir(child);
            }

            if (!child.delete()) {
                // Best effort: a stale file here is retried on next launch.
            }
        }
    }
}
