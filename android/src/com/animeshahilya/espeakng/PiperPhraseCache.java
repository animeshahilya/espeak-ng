/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
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
 * Short chunks only (reading stays out), kept from their first sighting:
 * replaying a TalkBack trace of real Pixel screens, that raised the ceiling
 * from 46% to 64% of chunks; it is memory only (gone with the process), which
 * is what makes that acceptable. Least recently used out.
 *
 * <p>Optionally a second tier on storage ({@link #setDisk}), so phrases
 * survive Android restarting the speech service: written only once seen
 * twice (it persists), read back into memory on a memory miss, each file
 * carrying its full key (checked on read), oldest out past the cap.
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
    }

    /**
     * As kept: 16-bit samples, half the memory of floats (twice the phrases in
     * the budget), far finer than the model's own noise floor.
     */
    private static final class Stored {
        final short[] audio;
        final float scale;
        final float[] durations;

        Stored(float[] audio, float[] durations) {
            float peak = 1e-9f;
            for (float x : audio) {
                peak = Math.max(peak, Math.abs(x));
            }
            scale = peak / 32767f;
            this.audio = new short[audio.length];
            for (int i = 0; i < audio.length; i++) {
                this.audio[i] = (short) Math.round(audio[i] / scale);
            }
            this.durations = durations == null ? null : durations.clone();
        }

        Stored(short[] audio, float scale, float[] durations) {
            this.audio = audio;
            this.scale = scale;
            this.durations = durations;
        }

        Entry toEntry() {
            final float[] out = new float[audio.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = audio[i] * scale;
            }
            return new Entry(out, durations == null ? null : durations.clone());
        }

        long bytes() {
            return 2L * audio.length + (durations == null ? 0 : 4L * durations.length) + 64;
        }
    }

    private final long maxBytes;
    private final int maxSamplesPerEntry;
    private final int admitAfter;
    private final Map<Key, Stored> entries = new LinkedHashMap<>(64, 0.75f, true);
    /** How often recent chunks were seen; bounded, access-ordered. */
    private final Map<Key, Integer> seen;
    private long bytes;
    private long hits;
    private long misses;
    private File diskDir;
    private long diskMaxBytes;
    private int diskAdmitAfter;
    private int diskWrites;
    private long diskHits;
    private static final int MAGIC = 0x50435031; // "PCP1"

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

    /**
     * Keeps phrases on storage too: in {@code dir}, at most {@code maxBytes},
     * written once seen {@code admitAfter} times.
     */
    synchronized void setDisk(File dir, long maxBytes, int admitAfter) {
        diskDir = dir;
        diskMaxBytes = maxBytes;
        diskAdmitAfter = admitAfter;
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    /** A copy of the cached run for {@code key} (the caller's processing may alter it), or null. */
    synchronized Entry get(Key key) {
        Stored e = entries.get(key);
        if (e == null && diskDir != null) {
            e = readDisk(key);
            if (e != null) {
                diskHits++;
                put(key, e);
            }
        }
        if (e == null) {
            misses++;
            return null;
        }
        hits++;
        // A hit is a sighting too: once heard often enough, it goes to storage.
        if (diskDir != null) {
            final Integer n = seen.get(key);
            final int count = n == null ? 1 : n + 1;
            seen.put(key, count);
            if (count >= diskAdmitAfter) {
                writeDisk(key, e);
            }
        }
        return e.toEntry();
    }

    /** A model run just made for {@code key}: counted, and kept once it has repeated. */
    synchronized void offer(Key key, float[] audio, float[] durations) {
        final Integer n = seen.get(key);
        final int count = n == null ? 1 : n + 1;
        seen.put(key, count);
        if (count < admitAfter || audio == null || audio.length > maxSamplesPerEntry) {
            return;
        }
        Stored e = entries.get(key);
        if (e == null) {
            e = new Stored(audio, durations);
            put(key, e);
        }
        if (diskDir != null && count >= diskAdmitAfter) {
            writeDisk(key, e);
        }
    }

    private void put(Key key, Stored e) {
        if (entries.put(key, e) == null) {
            bytes += e.bytes();
        }
        final Iterator<Map.Entry<Key, Stored>> it = entries.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().getValue().bytes();
            it.remove();
        }
    }

    /** "<voice key>-<hash of the whole key>.pcm": a voice's files share its key's prefix. */
    private File diskFile(Key key) {
        final String voiceKey = key.voice.indexOf('|') > 0 ? key.voice.substring(0, key.voice.indexOf('|'))
                : key.voice;
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(key.voice.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (long id : key.ids) {
                for (int b = 0; b < 8; b++) {
                    md.update((byte) (id >>> (8 * b)));
                }
            }
            md.update((key.speaker + ":" + key.lengthScale + ":" + key.noise + ":" + key.noiseW)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            final byte[] d = md.digest();
            for (int i = 0; i < 10; i++) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", d[i]));
            }
            return new File(diskDir, voiceKey.replaceAll("[^A-Za-z0-9_.\\-]", "_") + "-" + hex + ".pcm");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Stored readDisk(Key key) {
        final File f = diskFile(key);
        if (!f.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || !key.voice.equals(in.readUTF()) || in.readInt() != key.speaker
                    || in.readFloat() != key.lengthScale || in.readFloat() != key.noise
                    || in.readFloat() != key.noiseW) {
                return null;
            }
            final long[] ids = new long[in.readInt()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = in.readLong();
            }
            if (!Arrays.equals(ids, key.ids)) {
                return null; // a hash collision: never play another phrase
            }
            final int nd = in.readInt();
            final float[] durations = nd < 0 ? null : new float[nd];
            for (int i = 0; i < nd; i++) {
                durations[i] = in.readFloat();
            }
            final float scale = in.readFloat();
            final short[] audio = new short[in.readInt()];
            for (int i = 0; i < audio.length; i++) {
                audio[i] = in.readShort();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis()); // recently used: kept longer
            return new Stored(audio, scale, durations);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // damaged
            return null;
        }
    }

    private void writeDisk(Key key, Stored e) {
        final File f = diskFile(key);
        if (f.isFile()) {
            return;
        }
        final File tmp = new File(diskDir, f.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeUTF(key.voice);
            out.writeInt(key.speaker);
            out.writeFloat(key.lengthScale);
            out.writeFloat(key.noise);
            out.writeFloat(key.noiseW);
            out.writeInt(key.ids.length);
            for (long id : key.ids) {
                out.writeLong(id);
            }
            out.writeInt(e.durations == null ? -1 : e.durations.length);
            if (e.durations != null) {
                for (float d : e.durations) {
                    out.writeFloat(d);
                }
            }
            out.writeFloat(e.scale);
            out.writeInt(e.audio.length);
            for (short s : e.audio) {
                out.writeShort(s);
            }
        } catch (IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (++diskWrites % 20 == 1) {
            trimDisk();
        }
    }

    /** Oldest files out until the folder is under its cap. */
    private void trimDisk() {
        final File[] files = diskDir.listFiles((d, n) -> n.endsWith(".pcm"));
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= diskMaxBytes) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= diskMaxBytes) {
                break;
            }
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** A deleted voice: its phrases leave storage too. */
    synchronized void deleteVoice(String voiceKey) {
        forget(voiceKey + "|");
        if (diskDir == null) {
            return;
        }
        final String prefix = voiceKey.replaceAll("[^A-Za-z0-9_.\\-]", "_") + "-";
        final File[] files = diskDir.listFiles((d, n) -> n.startsWith(prefix));
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    synchronized long diskHits() {
        return diskHits;
    }

    /** Drops a voice's audio (unloaded, deleted or updated). */
    synchronized void forget(String voicePrefix) {
        final Iterator<Map.Entry<Key, Stored>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Key, Stored> e = it.next();
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
