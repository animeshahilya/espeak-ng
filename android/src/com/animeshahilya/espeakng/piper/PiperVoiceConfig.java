/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;
import com.animeshahilya.espeakng.text.Tashkeel;
import com.animeshahilya.espeakng.Voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A Piper voice's {@code .onnx.json}: what the phonemizer and the model need
 * to agree on (phoneme id map, eSpeak voice, sample rate, inference scales).
 * Pure Java plus org.json, so the unit tests exercise it off-device.
 */
public final class PiperVoiceConfig {
    static final String PHONEME_TYPE_ESPEAK = "espeak";
    static final String PHONEME_TYPE_TEXT = "text";
    /** Supertone Supertonic 3: plain text through its own tokenizer ({@link Supertonic}). */
    static final String PHONEME_TYPE_SUPERTONIC = "supertonic";
    /** Gradium Phonon 31m: plain English text, normalized and tokenized by its runtime ({@link Phonon}). */
    static final String PHONEME_TYPE_PHONON = "phonon";
    /** A Phonon voice's shared files beside its own (model.onnx): the bundle's metadata, tokenizer, BOS data. */
    static final List<String> PHONON_FILES = Arrays.asList("metadata.json", "tokenizer.json", "bos.bin");

    /**
     * English language names from catalogs and configs, for languages the
     * platform has no name for (Chhattisgarhi, "hne", on Android 16).
     */
    private static final Map<String, String> ENGLISH_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    static void rememberName(String family, String english) {
        if (family != null && english != null && !english.isEmpty() && !english.equals(family)) {
            ENGLISH_NAMES.put(family, english);
        }
    }

    static String englishName(String family) {
        return family != null ? ENGLISH_NAMES.get(family) : null;
    }

    /**
     * Voices trained on hissy recordings (measured 2026-10-03: ~10 dB more
     * treble than the other voices; the two SYSPIN ones also breathe ~250 ms
     * of noise before each first word). Their treble is cut and that lead-in
     * trimmed ({@link PiperAudio.TrebleCut}); each judged better by ear.
     * Their Compact versions share the recordings. Kannada Spoorthi (Rasa)
     * added 2026-10-24 after a user heard "an annoying sound": 6-11 kHz at
     * -16 dB of speech, like Hetal and Priya (Chetan -24, Vikram -25).
     */
    private static final java.util.Set<String> HISSY = new java.util.HashSet<>(Arrays.asList(
            "gu_IN-hetal-medium", "gu_IN-hetal-compact", "en_IN-priya-medium", "en_IN-priya-compact",
            "en_US-ljspeech-high", "en_US-ljspeech-compact", "kn_IN-spoorthi-medium"));

    /**
     * Voices that garble a request with no closing punctuation (a label, a
     * few words): their chunks get a final '.' ({@link PiperPhonemes#chunk}).
     * Whisper large-v3, 2026-10-06, two renders: Rapunzelina's short phrases
     * 71% -> 30% character errors, whole corpus 38.6% -> 29.8%. Per voice,
     * not for all: across the kept voices the average did not move and some
     * got worse (zh huayan's lone words 22% -> 85%). The English catalog
     * voices, 2026-10-07, Parakeet, each lone word its own call, two renders
     * (68 words, 17 screen-reader phrases): lone words right HFC Female
     * 108 -> 121 of 136, Amy 102 -> 115, Rahul 46 -> 56 (his '.' becomes ','
     * in {@link PiperPhonemes#englishChunkEnd}); phrases unchanged.
     */
    private static final java.util.Set<String> FINAL_STOP = new java.util.HashSet<>(Arrays.asList(
            "el_GR-rapunzelina-medium", "en_US-hfc_female-medium", "en_US-amy-medium",
            "en_IN-rahul-medium", "en_IN-rahul-compact"));

    /**
     * Text voices that collapse on a long sentence: past ~110 letters the
     * audio stops growing and the rest is mumbled or dropped. Their long
     * sentences are read in pieces of about {@link PiperPhonemes#SHORT_CHUNK}
     * letters, split between words. 2026-10-07, Parakeet, 14 sentences and a
     * paragraph: Rahul 37.7% -> 5.9% character errors (the paragraph 94% ->
     * 3%), Compact 37.0% -> 6.6%. The other text voices (SYSPIN, Rasa) keep
     * their length on long sentences, Priya included, and are left alone.
     */
    private static final java.util.Set<String> SHORT_CHUNKS = new java.util.HashSet<>(Arrays.asList(
            "en_IN-rahul-medium", "en_IN-rahul-compact"));

    /**
     * Voices that rush a lone word or a short label when read faster than
     * normal: input of up to {@link PiperEngine#SHORT_INPUT_WORDS} words is
     * read at no more than normal speed ({@link PiperEngine#shortInputSpeed});
     * sentences keep the reading speed. 2026-10-07, Rahul at the user's
     * 1.27x, Parakeet, 3 renders: lone words right 76 -> 85 of 204, phrases
     * 24 -> 28 of 51; slower than normal (0.87x) gained nothing more.
     */
    private static final java.util.Set<String> NORMAL_SPEED_SHORT_INPUT = new java.util.HashSet<>(
            Arrays.asList("en_IN-rahul-medium", "en_IN-rahul-compact"));

    /** Arabic voices trained on vowelled text: see {@link Tashkeel}. */
    static final java.util.Set<String> TASHKEEL = new java.util.HashSet<>(Arrays.asList(
            "ar_JO-kareem-medium"));

    static final String PAD = "_";
    static final String BOS = "^";
    static final String EOS = "$";

    /** Catalog key, e.g. "hi_IN-priyamvada-medium". */
    public final String key;
    public final int sampleRate;
    public final String quality;
    public final String dataset;
    public final String espeakVoice;
    public final String phonemeType;
    /** Language as Piper names it: family "hi", code "hi_IN". */
    public final String languageFamily;
    public final String languageCode;
    public final String languageNameNative;
    public final String languageNameEnglish;
    public final float noiseScale;
    public final float lengthScale;
    public final float noiseW;
    public final int numSpeakers;
    public final int defaultSpeakerId;
    /** Speaker names by id from speaker_id_map ("p239"); null where unnamed. */
    public final String[] speakerNames;
    /** Audio samples per model frame: phoneme durations come in frames. */
    public final int hopLength;
    public final Map<String, int[]> phonemeIdMap;
    /** Multi-code-point "phonemes" merged before the id lookup, longest first. */
    public final List<String[]> vowelClusters;
    public final int maxClusterLength;
    /**
     * Trained with piper 1.0-1.2 (or the config does not say): on rhasspy's
     * 2023 eSpeak NG rather than a current upstream one.
     */
    public final boolean oldEspeak;
    /** Trained on hissy recordings: see {@link #HISSY}. */
    public final boolean hissy;
    /** Unpunctuated sentences end with '.': see {@link #FINAL_STOP}. */
    public final boolean finalStop;
    /** Long sentences read in short pieces: see {@link #SHORT_CHUNKS}. */
    public final boolean shortChunks;
    /** Short input at no more than normal speed: see {@link #NORMAL_SPEED_SHORT_INPUT}. */
    public final boolean normalSpeedShortInput;
    /** Arabic text gets vowel marks first: see {@link #TASHKEEL}. */
    public final boolean tashkeel;
    /** Today's IPA -> the spelling the voice was trained on (PiperPhonemes.trainedSpellings). */
    public final PiperPhonemes.Spelling[] trainedSpellings;
    /** Supertonic: its language tag ("hi"), style ("F1") and flow steps; null/0 for other voices. */
    public final String supertonicLang;
    public final String supertonicStyle;
    public final int supertonicSteps;
    /** Phonon: the bundle's name for this voice ("Easton"); null for other voices. */
    public final String phononVoice;
    /**
     * Supertonic and Phonon: the files that go beside model.onnx, by name in
     * the voice folder ("vocoder.onnx", "tokenizer.json"...), each {path,
     * size, md5} on the release the catalog points at. Fetched into the
     * shared store by PiperDownloads.fetchVoiceExtras.
     */
    public final Map<String, String[]> companionFiles;

    private PiperVoiceConfig(Builder b) {
        key = b.key;
        sampleRate = b.sampleRate;
        quality = b.quality;
        dataset = b.dataset;
        espeakVoice = b.espeakVoice;
        phonemeType = b.phonemeType;
        languageFamily = b.languageFamily;
        languageCode = b.languageCode;
        languageNameNative = b.languageNameNative;
        languageNameEnglish = b.languageNameEnglish;
        noiseScale = b.noiseScale;
        lengthScale = b.lengthScale;
        noiseW = b.noiseW;
        numSpeakers = b.numSpeakers;
        defaultSpeakerId = b.defaultSpeakerId;
        speakerNames = b.speakerNames;
        hopLength = b.hopLength;
        phonemeIdMap = Collections.unmodifiableMap(b.phonemeIdMap);
        vowelClusters = Collections.unmodifiableList(b.vowelClusters);
        int max = 0;
        for (String[] c : b.vowelClusters) {
            max = Math.max(max, c.length);
        }
        maxClusterLength = max;
        oldEspeak = b.oldEspeak;
        hissy = HISSY.contains(key);
        finalStop = FINAL_STOP.contains(key);
        shortChunks = SHORT_CHUNKS.contains(key);
        normalSpeedShortInput = NORMAL_SPEED_SHORT_INPUT.contains(key);
        tashkeel = TASHKEEL.contains(key);
        trainedSpellings = PiperPhonemes.trainedSpellings(usesEspeak() ? espeakVoice : null,
                oldEspeak);
        supertonicLang = b.supertonicLang;
        supertonicStyle = b.supertonicStyle;
        supertonicSteps = b.supertonicSteps;
        companionFiles = Collections.unmodifiableMap(b.companionFiles);
        phononVoice = b.phononVoice;
    }

    public boolean isSupertonic() {
        return PHONEME_TYPE_SUPERTONIC.equals(phonemeType);
    }

    public boolean isPhonon() {
        return PHONEME_TYPE_PHONON.equals(phonemeType);
    }

    /**
     * Whether this app can phonemize for the voice. Newer Piper voices for
     * Chinese, Japanese, Thai and Hebrew bring their own phonemizers
     * (pinyin, OpenJTalk, ...) that have nothing to do with eSpeak; those
     * are refused before the model is downloaded.
     */
    public boolean isSupported() {
        if (isPhonon()) {
            return sampleRate > 0 && phononVoice != null && Phonon.available()
                    && companionFiles.keySet().containsAll(PHONON_FILES);
        }
        if (isSupertonic()) {
            return sampleRate > 0 && supertonicSteps > 0 && supertonicLang != null
                    && Supertonic.LANGUAGES.contains(supertonicLang)
                    && companionFiles.keySet().containsAll(Arrays.asList(Supertonic.DURATION_PREDICTOR,
                            Supertonic.TEXT_ENCODER, Supertonic.VOCODER, Supertonic.INDEXER,
                            Supertonic.TTS_JSON, Supertonic.STYLE));
        }
        return (PHONEME_TYPE_ESPEAK.equals(phonemeType) || PHONEME_TYPE_TEXT.equals(phonemeType))
                && sampleRate > 0
                && phonemeIdMap.containsKey(PAD)
                && (PHONEME_TYPE_TEXT.equals(phonemeType) || espeakVoice != null);
    }

    public boolean usesEspeak() {
        return PHONEME_TYPE_ESPEAK.equals(phonemeType);
    }

    /**
     * "Priyamvada", from the catalog key "hi_IN-priyamvada-medium" - the
     * name the catalog lists it by. Community configs can carry a generic
     * dataset ("data"), so the dataset is only the fallback.
     */
    public String displayName() {
        final String[] parts = key.split("-");
        if (parts.length >= 3 && !parts[1].isEmpty()) {
            return titleCase(parts[1]);
        }
        return titleCase(dataset != null && !dataset.isEmpty() ? dataset : key);
    }

    /** "Libritts R" from "libritts_r": the one name style for voices everywhere. */
    public static String titleCase(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(name.length());
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '_' || c == '-') {
                sb.append(' ');
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /** piper 1.3 moved from rhasspy's 2023 eSpeak NG to upstream's. */
    static boolean isOldPiper(String version) {
        if (version == null || version.isEmpty()) {
            return true;
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return true;
        }
        final int major = Integer.parseInt(m.group(1));
        return major < 1 || (major == 1 && Integer.parseInt(m.group(2)) < 3);
    }

    public static PiperVoiceConfig parse(String key, String json) throws JSONException {
        final JSONObject root = new JSONObject(json);
        final Builder b = new Builder();
        b.key = key;

        final JSONObject audio = root.optJSONObject("audio");
        b.sampleRate = audio != null ? audio.optInt("sample_rate", 22050) : 22050;
        b.quality = audio != null ? audio.optString("quality", "") : "";
        b.dataset = root.optString("dataset", "");

        final JSONObject espeak = root.optJSONObject("espeak");
        b.espeakVoice = espeak != null ? espeak.optString("voice", null) : null;
        b.phonemeType = root.optString("phoneme_type", PHONEME_TYPE_ESPEAK);
        b.oldEspeak = isOldPiper(root.optString("piper_version", ""));

        final JSONObject language = root.optJSONObject("language");
        if (language != null) {
            b.languageFamily = language.optString("family", null);
            b.languageCode = language.optString("code", null);
            b.languageNameNative = language.optString("name_native", null);
            b.languageNameEnglish = language.optString("name_english", null);
            rememberName(b.languageFamily, b.languageNameEnglish);
        }
        // Region from the catalog key ("ta_IN-...") when the config gives
        // only a language ("ta"), as community voices do.
        final java.util.regex.Matcher keyLang = java.util.regex.Pattern
                .compile("^([a-z]{2,3}_[A-Z]{2})-").matcher(key);
        if ((b.languageCode == null || b.languageCode.indexOf('_') < 0) && keyLang.find()) {
            b.languageCode = keyLang.group(1);
        }
        if (b.languageFamily == null && b.espeakVoice != null) {
            // Voices trained before Piper recorded "language": the eSpeak
            // voice is the only language hint ("en-us" -> "en").
            final int dash = b.espeakVoice.indexOf('-');
            b.languageFamily = dash > 0 ? b.espeakVoice.substring(0, dash) : b.espeakVoice;
        }

        final JSONObject inference = root.optJSONObject("inference");
        b.noiseScale = inference != null ? (float) inference.optDouble("noise_scale", 0.667) : 0.667f;
        b.lengthScale = inference != null ? (float) inference.optDouble("length_scale", 1.0) : 1.0f;
        b.noiseW = inference != null ? (float) inference.optDouble("noise_w", 0.8) : 0.8f;

        b.numSpeakers = root.optInt("num_speakers", 1);
        b.defaultSpeakerId = root.optInt("default_speaker_id", 0);
        b.speakerNames = new String[Math.max(1, b.numSpeakers)];
        final JSONObject speakers = root.optJSONObject("speaker_id_map");
        if (speakers != null) {
            final Iterator<String> names = speakers.keys();
            while (names.hasNext()) {
                final String name = names.next();
                final int id = speakers.optInt(name, -1);
                if (id >= 0 && id < b.speakerNames.length) {
                    b.speakerNames[id] = name;
                }
            }
        }
        b.hopLength = root.optInt("hop_length", 256);

        final JSONObject idMap = root.optJSONObject("phoneme_id_map");
        if (idMap != null) {
            final Iterator<String> keys = idMap.keys();
            while (keys.hasNext()) {
                final String phoneme = keys.next();
                final JSONArray ids = idMap.optJSONArray(phoneme);
                if (ids == null) {
                    continue;
                }
                final int[] values = new int[ids.length()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = ids.getInt(i);
                }
                b.phonemeIdMap.put(phoneme, values);
            }
        }

        final JSONArray clusters = root.optJSONArray("vowel_clusters");
        if (clusters != null) {
            for (int i = 0; i < clusters.length(); i++) {
                final JSONArray cluster = clusters.optJSONArray(i);
                if (cluster == null || cluster.length() < 2) {
                    continue;
                }
                final String[] parts = new String[cluster.length()];
                for (int j = 0; j < parts.length; j++) {
                    parts[j] = cluster.getString(j);
                }
                b.vowelClusters.add(parts);
            }
            // Longest first, so a greedy scan prefers the longest match.
            Collections.sort(b.vowelClusters, (x, y) -> y.length - x.length);
        }
        final JSONObject st = root.optJSONObject("supertonic");
        if (st != null) {
            b.supertonicLang = st.optString("lang", null);
            b.supertonicStyle = st.optString("style", null);
            b.supertonicSteps = st.optInt("steps", 8);
            putFiles(b, st.optJSONObject("files"));
        }
        final JSONObject phonon = root.optJSONObject("phonon");
        if (phonon != null) {
            b.phononVoice = phonon.optString("voice", null);
            putFiles(b, phonon.optJSONObject("files"));
        }
        return new PiperVoiceConfig(b);
    }

    /** A config's companion files: name in the voice folder -> {path, size, md5}. */
    private static void putFiles(Builder b, JSONObject files) {
        if (files == null) {
            return;
        }
        final Iterator<String> names = files.keys();
        while (names.hasNext()) {
            final String name = names.next();
            final JSONObject f = files.optJSONObject(name);
            if (f != null && name.matches("[a-z_]+\\.(onnx|json|bin)")) {
                b.companionFiles.put(name, new String[] {f.optString("path", ""),
                        String.valueOf(f.optLong("size_bytes", 0)), f.optString("md5_digest", "")});
            }
        }
    }

    private static final class Builder {
        String key;
        int sampleRate;
        String quality;
        String dataset;
        String espeakVoice;
        String phonemeType;
        boolean oldEspeak;
        String languageFamily;
        String languageCode;
        String languageNameNative;
        String languageNameEnglish;
        float noiseScale;
        float lengthScale;
        float noiseW;
        int numSpeakers;
        int defaultSpeakerId;
        String[] speakerNames;
        int hopLength;
        final Map<String, int[]> phonemeIdMap = new HashMap<>();
        final List<String[]> vowelClusters = new ArrayList<>();
        String supertonicLang;
        String supertonicStyle;
        int supertonicSteps;
        final Map<String, String[]> companionFiles = new HashMap<>();
        String phononVoice;
    }
}


