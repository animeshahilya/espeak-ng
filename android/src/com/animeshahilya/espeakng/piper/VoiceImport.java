/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.util.FileUtils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A natural voice the user picked instead of downloading from the catalog:
 * a sherpa-onnx voice archive (vits-piper-*.tar.bz2, vits-coqui-*.tar.bz2),
 * a .zip or .tar.gz of one, or the files themselves (the .onnx with its
 * .onnx.json or tokens.txt). Two kinds load like the catalog's voices:
 * Piper voices (eSpeak phonemes) and Coqui VITS voices that read letters
 * (like SYSPIN). Without a Piper config, one is written from tokens.txt and
 * the model's sherpa-onnx metadata, the way the SYSPIN/Rasa configs were
 * (sherpa-onnx-respin-syspin build_piper_configs.py). Other sherpa-onnx
 * models (MMS, Kokoro, Matcha...) take other inputs and are refused.
 *
 * <p>An imported voice is an ordinary installed voice under the key
 * "&lt;language&gt;-&lt;name&gt;-imported", which no catalog lists: update
 * checks and restores pass it by, and it keeps its original model
 * ({@link PiperVoiceStore#IMPORTED_FILE}), since it could never be
 * downloaded again. No NPU files exist for it; it runs on the CPU.
 */
public final class VoiceImport {
    private static final String TAG = "VoiceImport";
    /** Quality and key suffix of an imported voice. */
    public static final String QUALITY = "imported";
    /** Larger than any VITS voice (Piper high ~120 MB, SYSPIN ~115). */
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final String TOKENS = "tokens.txt";
    private static final String JSON_SUFFIX = ".onnx.json";
    private static final String ONNX_SUFFIX = ".onnx";

    /** Why an import failed, as a message for the user. */
    public static final class ImportException extends Exception {
        public final int messageRes;

        ImportException(int messageRes, String detail) {
            super(detail);
            this.messageRes = messageRes;
        }
    }

    private VoiceImport() {
    }

    /**
     * Installs the voice in {@code uris} (one archive, or its files).
     * Blocking: off the main thread.
     *
     * @param language the language the user said the voice speaks (ISO 639,
     *                 "hi"), used only where its files do not say; null to
     *                 refuse such a voice with {@code piper_import_language},
     *                 so the caller can ask (as VoxSherpa's import does)
     * @return the installed voice
     */
    public static synchronized PiperVoiceStore.Installed install(Context appContext, Context storageContext,
                                                                 List<Uri> uris, List<String> names,
                                                                 String language)
            throws ImportException { // synchronized: one staging folder
        final File staging = new File(PiperVoiceStore.root(storageContext), "import");
        FileUtils.deleteRecursively(staging);
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();
        try {
            final long[] budget = {MAX_BYTES};
            for (int i = 0; i < uris.size(); i++) {
                try (InputStream in = appContext.getContentResolver().openInputStream(uris.get(i))) {
                    if (in != null) {
                        unpack(in, names.get(i), staging, budget);
                    }
                }
            }
            final File model = pickModel(staging);
            if (model == null) {
                throw new ImportException(R.string.piper_import_no_model, "no .onnx");
            }
            final String picked = names.size() == 1 ? names.get(0) : model.getName();
            Hint hint = Hint.of(model.getName(), picked);
            if (language != null && hint.family == null) {
                hint = hint.withFamily(language);
            }
            File json = new File(staging, model.getName() + ".json");
            final File[] configs = staging.listFiles((d, n) -> n.endsWith(JSON_SUFFIX));
            if (!json.isFile() && configs != null && configs.length == 1) {
                json = configs[0]; // picked files a browser saved under other names
            }
            final File tokens = new File(staging, TOKENS);
            if (!json.isFile() && !tokens.isFile()) {
                throw new ImportException(R.string.piper_import_no_config, "no config or tokens");
            }
            final Map<String, String> meta = checkModel(model);
            final JSONObject config = json.isFile()
                    ? new JSONObject(FileUtils.readText(json))
                    : configFromTokens(FileUtils.readText(tokens), meta);
            fillLanguage(config, hint);
            final JSONObject audio = config.optJSONObject("audio") != null
                    ? config.getJSONObject("audio") : new JSONObject();
            audio.put("quality", QUALITY);
            config.put("audio", audio);

            final String code = config.getJSONObject("language").getString("code");
            final File voices = PiperVoiceStore.voicesDir(storageContext);
            String key = code + "-" + hint.slug + "-" + QUALITY;
            for (int n = 2; new File(voices, key).exists(); n++) {
                key = code + "-" + hint.slug + "_" + n + "-" + QUALITY;
            }
            final String text = config.toString(1);
            if (!PiperVoiceConfig.parse(key, text).isSupported()) {
                throw new ImportException(R.string.piper_import_unsupported, "config not supported");
            }
            final File[] files = staging.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.equals(model)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            if (!model.renameTo(new File(staging, PiperVoiceStore.MODEL_FILE))) {
                throw new IOException("Cannot rename " + model);
            }
            FileUtils.write(new File(staging, PiperVoiceStore.CONFIG_FILE), text);
            FileUtils.write(new File(staging, PiperVoiceStore.IMPORTED_FILE), picked);
            final File dest = new File(voices, key);
            //noinspection ResultOfMethodCallIgnored
            voices.mkdirs();
            if (!staging.renameTo(dest)) {
                throw new IOException("Cannot move " + staging + " to " + dest);
            }
            PiperDownloads.announceInstalled(appContext, storageContext, key);
            final PiperVoiceStore.Installed installed = PiperVoiceStore.find(storageContext, key);
            if (installed == null) {
                FileUtils.deleteRecursively(dest);
                PiperVoiceStore.invalidate();
                throw new ImportException(R.string.piper_import_unsupported, "not listed after install");
            }
            // Its optimized copy now, not at its first use (as after a download).
            EspeakApp.runAsync(() -> PiperEngine.get().prepare(installed.key, installed.model(), installed.config));
            Log.i(TAG, "Imported " + key + " from " + picked);
            return installed;
        } catch (JSONException | IOException | RuntimeException e) {
            Log.w(TAG, "Import failed", e);
            throw new ImportException(R.string.piper_import_failed, String.valueOf(e));
        } finally {
            FileUtils.deleteRecursively(staging);
        }
    }

    /**
     * Keeps the voice's files from one picked file, by what its bytes are
     * (names from content providers are unreliable): bzip2/gzip tar, zip,
     * plain tar, or a single file.
     */
    static void unpack(InputStream raw, String name, File staging, long[] budget) throws IOException {
        final BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024);
        in.mark(512);
        final byte[] head = new byte[263];
        int n = 0;
        int r;
        while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
            n += r;
        }
        in.reset();
        if (n >= 3 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h') {
            unpackTar(new com.animeshahilya.espeakng.util.Bzip2InputStream(in), staging, budget);
        } else if (n >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
            unpackTar(new GZIPInputStream(in, 64 * 1024), staging, budget);
        } else if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            final ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.isDirectory()) {
                    keep(zip, e.getName(), staging, budget);
                }
            }
        } else if (n == head.length && new String(head, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
            unpackTar(in, staging, budget);
        } else {
            keep(in, name, staging, budget);
        }
    }

    /**
     * A tar archive's regular files (ustar, with GNU long names, as
     * sherpa-onnx packs them); links, folders and PAX records are skipped.
     */
    static void unpackTar(InputStream in, File staging, long[] budget) throws IOException {
        final byte[] header = new byte[512];
        String longName = null;
        while (readBlock(in, header) && header[0] != 0) { // two zero blocks end it
            final String sizeField = text(header, 124, 12).trim();
            final long size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
            final char type = (char) header[156];
            String name = text(header, 0, 100);
            if ("ustar".equals(text(header, 257, 5)) && header[345] != 0) {
                name = text(header, 345, 155) + "/" + name;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            final Entry body = new Entry(in, size);
            if (type == 'L') {
                final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[512];
                for (int r; (r = body.read(buf)) > 0; ) {
                    bytes.write(buf, 0, r);
                }
                longName = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\0", "");
            } else if (type == '0' || type == 0) {
                keep(body, name, staging, budget);
            }
            body.skipRest();
            skipFully(in, (512 - size % 512) % 512);
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int n = 0;
        for (int r; n < block.length && (r = in.read(block, n, block.length - n)) > 0; ) {
            n += r;
        }
        if (n != 0 && n != block.length) {
            throw new java.io.EOFException("tar archive ends inside a header");
        }
        return n == block.length;
    }

    /** A NUL-terminated header field. */
    private static String text(byte[] header, int off, int len) {
        int end = off;
        while (end < off + len && header[end] != 0) {
            end++;
        }
        return new String(header, off, end - off, StandardCharsets.UTF_8);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        final byte[] buf = new byte[8192];
        while (n > 0) {
            final int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r <= 0) {
                throw new java.io.EOFException("tar archive ends inside an entry");
            }
            n -= r;
        }
    }

    /** One tar entry's bytes: reads stop at its end; the archive stream stays open. */
    private static final class Entry extends InputStream {
        private final InputStream in;
        private long left;

        Entry(InputStream in, long size) {
            this.in = in;
            this.left = size;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) <= 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            final int r = in.read(b, off, (int) Math.min(len, left));
            if (r <= 0) {
                throw new java.io.EOFException("tar archive ends inside an entry");
            }
            left -= r;
            return r;
        }

        void skipRest() throws IOException {
            skipFully(in, left);
            left = 0;
        }

        @Override
        public void close() {
            // The archive stream belongs to the caller.
        }
    }

    /** Writes {@code in} to the staging folder if its name is a voice file; skips it otherwise. */
    private static void keep(InputStream in, String path, File staging, long[] budget) throws IOException {
        final String name = path == null ? "" : path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        if (!(name.endsWith(ONNX_SUFFIX) || name.endsWith(JSON_SUFFIX) || name.equals(TOKENS))
                || name.startsWith(".")) {
            return; // espeak-ng-data, scripts, model cards: not needed
        }
        try (OutputStream out = new FileOutputStream(new File(staging, name))) {
            final byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                budget[0] -= r;
                if (budget[0] < 0) {
                    throw new IOException("Import larger than " + MAX_BYTES + " bytes");
                }
                out.write(buf, 0, r);
            }
        }
    }

    /** The voice's model: the .onnx with a config beside it, else the largest. */
    static File pickModel(File staging) {
        final File[] models = staging.listFiles((d, n) -> n.endsWith(ONNX_SUFFIX));
        if (models == null || models.length == 0) {
            return null;
        }
        File best = null;
        for (File m : models) {
            if (new File(staging, m.getName() + ".json").isFile()) {
                return m;
            }
            if (best == null || m.length() > best.length()) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Opens the model once, as the app will: refuses what this app's ONNX
     * Runtime cannot run (a missing operator) or a model with other inputs
     * than Piper's. Returns its metadata (sherpa-onnx writes the voice's
     * facts there).
     */
    private static Map<String, String> checkModel(File model) throws ImportException {
        try (ai.onnxruntime.OrtSession.SessionOptions options = new ai.onnxruntime.OrtSession.SessionOptions()) {
            options.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (ai.onnxruntime.OrtSession s = PiperModel.env().createSession(model.getAbsolutePath(), options)) {
                if (!s.getInputNames().containsAll(java.util.Arrays.asList("input", "input_lengths", "scales"))) {
                    throw new ImportException(R.string.piper_import_unsupported, "inputs " + s.getInputNames());
                }
                return s.getMetadata().getCustomMetadata();
            }
        } catch (ai.onnxruntime.OrtException e) {
            throw new ImportException(R.string.piper_import_unsupported, String.valueOf(e));
        }
    }

    /**
     * A Piper config from sherpa-onnx's tokens.txt ("symbol id" lines) and
     * the model's metadata. Piper voices keep their ids (they include "_",
     * "^" and "$"); Coqui letter voices map those three to the blank, which
     * reproduces the add_blank input they were trained on.
     */
    static JSONObject configFromTokens(String tokensText, Map<String, String> meta)
            throws JSONException, ImportException {
        if (meta.containsKey("model_type") && !"vits".equals(meta.get("model_type"))) {
            throw new ImportException(R.string.piper_import_unsupported, "model_type " + meta.get("model_type"));
        }
        final int sampleRate = intOf(meta, "sample_rate", 0);
        if (sampleRate <= 0) {
            throw new ImportException(R.string.piper_import_unsupported, "no sample_rate");
        }
        final JSONObject ids = new JSONObject();
        for (String line : tokensText.split("\n")) {
            line = line.replace("\r", ""); // CRLF tokens.txt (SYSPIN's) would end every symbol in \r
            final int space = line.lastIndexOf(' ');
            if (space < 0 || line.trim().isEmpty()) {
                continue;
            }
            final String symbol = space == 0 ? " " : line.substring(0, space);
            try {
                ids.put(symbol, new JSONArray().put(Integer.parseInt(line.substring(space + 1).trim())));
            } catch (NumberFormatException e) {
                throw new ImportException(R.string.piper_import_failed, "tokens.txt line: " + line);
            }
        }
        final JSONObject config = new JSONObject();
        final String voice = meta.get("voice");
        final String comment = meta.get("comment");
        if ("piper".equals(comment) || "1".equals(meta.get("has_espeak"))) {
            if (voice == null || voice.isEmpty() || !ids.has(PiperVoiceConfig.PAD)
                    || !ids.has(PiperVoiceConfig.BOS) || !ids.has(PiperVoiceConfig.EOS)) {
                throw new ImportException(R.string.piper_import_unsupported, "piper without voice or pad");
            }
            config.put("phoneme_type", PiperVoiceConfig.PHONEME_TYPE_ESPEAK);
            config.put("espeak", new JSONObject().put("voice", voice));
        } else if ("characters".equals(meta.get("frontend")) && intOf(meta, "add_blank", 0) == 1) {
            final JSONArray blank = new JSONArray().put(intOf(meta, "blank_id", 0));
            final JSONObject letters = new JSONObject();
            for (java.util.Iterator<String> it = ids.keys(); it.hasNext(); ) {
                final String s = it.next();
                if (!(s.startsWith("<") && s.endsWith(">") && s.length() > 2)) {
                    letters.put(s, ids.get(s));
                }
            }
            letters.put(PiperVoiceConfig.PAD, blank).put(PiperVoiceConfig.BOS, blank)
                    .put(PiperVoiceConfig.EOS, blank);
            config.put("phoneme_type", PiperVoiceConfig.PHONEME_TYPE_TEXT);
            config.put("phoneme_id_map", letters);
        } else {
            throw new ImportException(R.string.piper_import_unsupported, "frontend " + meta.get("frontend"));
        }
        if (!config.has("phoneme_id_map")) {
            config.put("phoneme_id_map", ids);
        }
        config.put("audio", new JSONObject().put("sample_rate", sampleRate));
        config.put("num_speakers", Math.max(1, intOf(meta, "n_speakers", 1)));
        config.put("inference", new JSONObject().put("noise_scale", 0.667).put("length_scale", 1.0)
                .put("noise_w", 0.8));
        final String family = familyOf(meta.get("language"));
        if (family != null) {
            config.put("language", new JSONObject().put("family", family));
        }
        return config;
    }

    /**
     * Makes sure the config names its language as the app keys voices
     * ("code" with region where known, "family"): from the config, its
     * eSpeak voice, or the file name.
     */
    static void fillLanguage(JSONObject config, Hint hint) throws JSONException, ImportException {
        JSONObject language = config.optJSONObject("language");
        if (language == null) {
            language = new JSONObject();
            config.put("language", language);
        }
        String family = language.optString("family", "");
        if (family.isEmpty()) {
            final JSONObject espeak = config.optJSONObject("espeak");
            final String voice = espeak != null ? espeak.optString("voice", "") : "";
            family = !voice.isEmpty() ? voice.split("-")[0] : hint.family;
        }
        if (family == null || !family.matches("[a-z]{2,3}")) {
            throw new ImportException(R.string.piper_import_language, "language unknown");
        }
        String code = language.optString("code", "");
        if (!code.matches("[a-z]{2,3}_[A-Z]{2}")) {
            code = hint.region != null && family.equals(hint.family) ? family + "_" + hint.region : family;
        }
        language.put("family", family).put("code", code);
    }

    /** "Bengali" -> "bn" (sherpa-onnx's metadata names languages in English); null if unknown. */
    static String familyOf(String englishName) {
        if (englishName == null || englishName.trim().isEmpty()) {
            return null;
        }
        final String name = englishName.trim();
        if (name.matches("[a-z]{2,3}")) {
            return name;
        }
        for (String iso : Locale.getISOLanguages()) {
            if (new Locale(iso).getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(name)) {
                return iso;
            }
        }
        return null;
    }

    private static int intOf(Map<String, String> meta, String key, int fallback) {
        try {
            return Integer.parseInt(meta.get(key).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** What a file name says: "vits-piper-en_US-amy-low.tar.bz2" -> en, US, "amy". */
    static final class Hint {
        final String original;
        final String family;
        final String region;
        final String slug;

        private Hint(String original, String family, String region, String slug) {
            this.original = original;
            this.family = family;
            this.region = region;
            this.slug = slug;
        }

        private static final Pattern EXTENSION = Pattern.compile(
                "(?i)(\\.tar)?\\.(bz2|gz|tgz|zip|tar|onnx|json)$");
        private static final Pattern PREFIX = Pattern.compile("^(vits|piper|coqui|mimic3|mms|sherpa-onnx)[-_]");
        private static final Pattern LANG = Pattern.compile("^([a-z]{2,3})(?:_([A-Z]{2}))?[-_](.+)$");
        private static final Pattern QUALITY_SUFFIX = Pattern.compile("[-_](x_low|low|medium|high|int8|fp16)$");

        static Hint of(String fileName) {
            final String original = fileName == null ? "" : fileName;
            String s = original;
            for (String prev = null; !s.equals(prev); ) {
                prev = s;
                s = EXTENSION.matcher(s).replaceAll("");
            }
            for (String prev = null; !s.equals(prev); ) {
                prev = s;
                s = PREFIX.matcher(s).replaceAll("");
            }
            String family = null;
            String region = null;
            final Matcher m = LANG.matcher(s);
            // A language the platform knows: in "amy-low.onnx", "amy" is the voice's name.
            if (m.matches() && !new Locale(m.group(1)).getDisplayLanguage(Locale.ENGLISH).equals(m.group(1))) {
                family = m.group(1);
                region = m.group(2);
                s = m.group(3);
            }
            for (String prev = null; !s.equals(prev); ) {
                prev = s;
                s = QUALITY_SUFFIX.matcher(s).replaceAll("");
            }
            String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (slug.isEmpty() || "model".equals(slug)) {
                slug = "voice";
            }
            return new Hint(original, family, region, slug);
        }

        /**
         * The model's own name first: Piper names it "en_GB-jenny_dioco-medium.onnx"
         * whatever the archive is called (a MultiVoice export is
         * "MultiVoice-Sherpa-ONNX-vits-piper-....zip"); the picked file's name
         * when the model's says no language ("model.onnx" in Coqui archives).
         */
        static Hint of(String modelName, String picked) {
            final Hint model = of(modelName);
            return model.family != null ? model : of(picked);
        }

        /** The same file, in the language the user chose for it. */
        Hint withFamily(String family) {
            return new Hint(original, family, null, slug);
        }
    }
}
