/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
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
final class PiperVoiceConfig {
    static final String PHONEME_TYPE_ESPEAK = "espeak";
    static final String PHONEME_TYPE_TEXT = "text";

    static final String PAD = "_";
    static final String BOS = "^";
    static final String EOS = "$";

    /** Catalog key, e.g. "hi_IN-priyamvada-medium". */
    final String key;
    final int sampleRate;
    final String quality;
    final String dataset;
    final String espeakVoice;
    final String phonemeType;
    /** Language as Piper names it: family "hi", code "hi_IN". */
    final String languageFamily;
    final String languageCode;
    final String languageNameNative;
    final String languageNameEnglish;
    final float noiseScale;
    final float lengthScale;
    final float noiseW;
    final int numSpeakers;
    final int defaultSpeakerId;
    /** Audio samples per model frame: phoneme durations come in frames. */
    final int hopLength;
    final Map<String, int[]> phonemeIdMap;
    /** Multi-code-point "phonemes" merged before the id lookup, longest first. */
    final List<String[]> vowelClusters;
    final int maxClusterLength;

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
        hopLength = b.hopLength;
        phonemeIdMap = Collections.unmodifiableMap(b.phonemeIdMap);
        vowelClusters = Collections.unmodifiableList(b.vowelClusters);
        int max = 0;
        for (String[] c : b.vowelClusters) {
            max = Math.max(max, c.length);
        }
        maxClusterLength = max;
    }

    /**
     * Whether this app can phonemize for the voice. Newer Piper voices for
     * Chinese, Japanese, Thai and Hebrew bring their own phonemizers
     * (pinyin, OpenJTalk, ...) that have nothing to do with eSpeak; those
     * are refused before the model is downloaded.
     */
    boolean isSupported() {
        return (PHONEME_TYPE_ESPEAK.equals(phonemeType) || PHONEME_TYPE_TEXT.equals(phonemeType))
                && sampleRate > 0
                && phonemeIdMap.containsKey(PAD)
                && (PHONEME_TYPE_TEXT.equals(phonemeType) || espeakVoice != null);
    }

    boolean usesEspeak() {
        return PHONEME_TYPE_ESPEAK.equals(phonemeType);
    }

    /**
     * "Priyamvada", from the catalog key "hi_IN-priyamvada-medium" - the
     * name the catalog lists it by. Community configs can carry a generic
     * dataset ("data"), so the dataset is only the fallback.
     */
    String displayName() {
        final String[] parts = key.split("-");
        if (parts.length >= 3 && !parts[1].isEmpty()) {
            return titleCase(parts[1]);
        }
        return titleCase(dataset != null && !dataset.isEmpty() ? dataset : key);
    }

    /** "Libritts R" from "libritts_r": the one name style for voices everywhere. */
    static String titleCase(String name) {
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

    static PiperVoiceConfig parse(String key, String json) throws JSONException {
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

        final JSONObject language = root.optJSONObject("language");
        if (language != null) {
            b.languageFamily = language.optString("family", null);
            b.languageCode = language.optString("code", null);
            b.languageNameNative = language.optString("name_native", null);
            b.languageNameEnglish = language.optString("name_english", null);
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
        return new PiperVoiceConfig(b);
    }

    private static final class Builder {
        String key;
        int sampleRate;
        String quality;
        String dataset;
        String espeakVoice;
        String phonemeType;
        String languageFamily;
        String languageCode;
        String languageNameNative;
        String languageNameEnglish;
        float noiseScale;
        float lengthScale;
        float noiseW;
        int numSpeakers;
        int defaultSpeakerId;
        int hopLength;
        final Map<String, int[]> phonemeIdMap = new HashMap<>();
        final List<String[]> vowelClusters = new ArrayList<>();
    }
}
