/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Natural-voice audio for chunks a screen reader repeats ("Button",
 * "Double-tap to activate"): the model's raw output, so a repeat skips the
 * model run (tens to hundreds of ms) and only gets the cheap post-processing
 * (level, trim, speed, pitch) - which is why volume, rate and pitch changes
 * keep working on cached audio.
 *
 * <p>Keyed on what the model reads - the voice's model file, speaker, model
 * speed, style and the chunk's phoneme ids - so a dictionary edit, a voice
 * update or another style simply misses; nothing has to invalidate it.
 * Admission on the second sighting and short chunks only, so one-off text
 * (messages, articles) is never kept. Memory only, least recently used out.
 * Pure Java: unit-tested on the JVM.
 */
final class PiperPhraseCache {
    /** What one model run is a function of (its random noise aside). */
    static final class Key {
        final String voice;
        final long[] ids;
        final int speaker;
        final float lengthScale;
        final float noise;
        final float noiseW;
        private final int hash;

        Key(String voice, long[] ids, int speaker, float lengthScale, float noise, float noiseW) {
            this.voice = voice;
            this.ids = ids;
            this.speaker = speaker;
            this.lengthScale = lengthScale;
            this.noise = noise;
            this.noiseW = noiseW;
            int h = voice.hashCode();
            h = 31 * h + Arrays.hashCode(ids);
            h = 31 * h + speaker;
            h = 31 * h + Float.floatToIntBits(lengthScale);
            h = 31 * h + Float.floatToIntBits(noise);
            hash = 31 * h + Float.floatToIntBits(noiseW);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            final Key k = (Key) o;
            return hash == k.hash && speaker == k.speaker && lengthScale == k.lengthScale
                    && noise == k.noise && noiseW == k.noiseW && voice.equals(k.voice)
                    && Arrays.equals(ids, k.ids);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** A cached model run: audio in about [-1, 1] and frames per id (or null). */
    static final class Entry {
        final float[] audio;
        final float[] durations;

        Entry(float[] audio, float[] durations) {
            this.audio = audio;
            this.durations = durations;
        }

        long bytes() {
            return 4L * audio.length + (durations == null ? 0 : 4L * durations.length) + 64;
        }
    }

    private final long maxBytes;
    private final int maxSamplesPerEntry;
    private final int admitAfter;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    /** How often recent chunks were seen; bounded, access-ordered. */
    private final Map<Key, Integer> seen;
    private long bytes;
    private long hits;
    private long misses;

    /**
     * @param maxBytes           memory budget for the audio
     * @param maxSamplesPerEntry longer chunks are not kept (reading, not UI)
     * @param admitAfter         sightings before a chunk is kept (2: on its first repeat)
     * @param seenCapacity       recent chunks whose sightings are counted
     */
    PiperPhraseCache(long maxBytes, int maxSamplesPerEntry, int admitAfter, final int seenCapacity) {
        this.maxBytes = maxBytes;
        this.maxSamplesPerEntry = maxSamplesPerEntry;
        this.admitAfter = admitAfter;
        this.seen = new LinkedHashMap<Key, Integer>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Integer> eldest) {
                return size() > seenCapacity;
            }
        };
    }

    /** A copy of the cached run for {@code key} (the caller's processing may alter it), or null. */
    synchronized Entry get(Key key) {
        final Entry e = entries.get(key);
        if (e == null) {
            misses++;
            return null;
        }
        hits++;
        return new Entry(e.audio.clone(), e.durations == null ? null : e.durations.clone());
    }

    /** A model run just made for {@code key}: counted, and kept once it has repeated. */
    synchronized void offer(Key key, float[] audio, float[] durations) {
        final Integer n = seen.get(key);
        final int count = n == null ? 1 : n + 1;
        seen.put(key, count);
        if (count < admitAfter || audio == null || audio.length > maxSamplesPerEntry
                || entries.containsKey(key)) {
            return;
        }
        final Entry e = new Entry(audio.clone(), durations == null ? null : durations.clone());
        entries.put(key, e);
        bytes += e.bytes();
        final Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().getValue().bytes();
            it.remove();
        }
    }

    /** Drops a voice's audio (unloaded, deleted or updated). */
    synchronized void forget(String voicePrefix) {
        final Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Key, Entry> e = it.next();
            if (e.getKey().voice.startsWith(voicePrefix)) {
                bytes -= e.getValue().bytes();
                it.remove();
            }
        }
        seen.keySet().removeIf(k -> k.voice.startsWith(voicePrefix));
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized int size() {
        return entries.size();
    }
}
