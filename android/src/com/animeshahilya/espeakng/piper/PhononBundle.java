/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.util.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The folder Phonon's runtime loads, put together from what is installed:
 * each Phonon voice folder holds its voice file (model.onnx) and links to the
 * shared metadata, tokenizer and BOS data (PiperDownloads.fetchVoiceExtras);
 * the chip's compiled model is in {@code piper/phonon/}
 * (PiperDownloads.fetchPhononNpu). The runtime reads every voice its
 * metadata lists, so the metadata written here lists the installed ones only.
 */
final class PhononBundle {
    /** Under the piper root: the compiled model and the assembled bundle. */
    static final String DIR = "phonon";

    private PhononBundle() {
    }

    /** {@code piper/phonon/} beside {@code piper/voices/}. */
    static File dir(File voicesDir) {
        return new File(voicesDir.getParentFile(), DIR);
    }

    /** The compiled model for this chip, as fetched ("phonon-31m-en-sm8750.bin"). */
    static File compiledModel(File voicesDir) {
        final String target = NpuFiles.target();
        return target == null ? null
                : new File(dir(voicesDir), "phonon-31m-en-" + target.toLowerCase(java.util.Locale.ROOT) + ".bin");
    }

    /** Builds the bundle from the installed Phonon voices and loads it (~1 s). */
    static Phonon load(File voicesDir) throws IOException {
        final File compiled = compiledModel(voicesDir);
        if (compiled == null || !compiled.isFile()) {
            throw new IOException("its NPU model has not downloaded yet");
        }
        final File bundle = new File(dir(voicesDir), "bundle");
        FileUtils.deleteRecursively(bundle);
        final File voices = new File(bundle, "voices");
        if (!voices.mkdirs()) {
            throw new IOException("cannot create " + voices);
        }
        JSONObject meta = null;
        final JSONArray kept = new JSONArray();
        final File[] dirs = voicesDir.listFiles(File::isDirectory);
        try {
            for (File d : dirs != null ? dirs : new File[0]) {
                final PiperVoiceConfig config = config(d);
                final File voiceFile = new File(d, PiperVoiceStore.MODEL_FILE);
                if (config == null || !config.isPhonon() || !voiceFile.isFile()) {
                    continue;
                }
                if (meta == null) {
                    meta = new JSONObject(FileUtils.readText(new File(d, "metadata.json")));
                    link(new File(d, "tokenizer.json"), new File(bundle, "tokenizer.json"));
                    link(new File(d, "bos.bin"), new File(bundle, "bos.bin"));
                }
                final JSONObject entry = voiceEntry(meta, config.phononVoice);
                if (entry == null) {
                    continue; // not in this bundle's metadata
                }
                final String name = d.getName() + ".bin";
                link(voiceFile, new File(voices, name));
                kept.put(new JSONObject(entry.toString()).put("file", "voices/" + name));
            }
            if (meta == null || kept.length() == 0) {
                throw new IOException("no Phonon voice installed");
            }
            link(compiled, new File(bundle, compiled.getName()));
            meta.put("voices", kept);
            meta.getJSONObject("runtime").put("context_binaries", new JSONObject().put("phone",
                    new JSONObject().put("file", compiled.getName())
                            .put("soc_models", new JSONArray().put(NpuFiles.target()))));
            FileUtils.write(new File(bundle, "metadata.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad Phonon metadata: " + e.getMessage());
        }
        return Phonon.load(bundle, NpuFiles.target(), com.animeshahilya.espeakng.EspeakApp.getStorageContext()
                .getApplicationInfo().nativeLibraryDir);
    }

    private static PiperVoiceConfig config(File voiceDir) {
        try {
            return PiperVoiceConfig.parse(voiceDir.getName(),
                    FileUtils.readText(new File(voiceDir, PiperVoiceStore.CONFIG_FILE)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static JSONObject voiceEntry(JSONObject meta, String name) throws JSONException {
        final JSONArray all = meta.getJSONArray("voices");
        for (int i = 0; i < all.length(); i++) {
            if (all.getJSONObject(i).getString("name").equals(name)) {
                return all.getJSONObject(i);
            }
        }
        return null;
    }

    private static void link(File from, File to) throws IOException {
        if (!PiperDownloads.linkOrCopy(from, to)) {
            throw new IOException("missing " + from.getName());
        }
    }
}
