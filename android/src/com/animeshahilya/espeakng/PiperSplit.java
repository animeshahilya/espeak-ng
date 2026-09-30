/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a Piper (VITS) model into an encoder and a decoder, so the decoder
 * can run over pieces of the latent and speech starts after the first piece
 * (piper1-gpl PR #302, the "fast"/RT voices of NVDA's Sonata). The encoder
 * (text encoder, duration predictor, flow) sees the whole chunk, so prosody
 * is unchanged; the decoder (HiFi-GAN) is convolutional with a finite
 * receptive field, so pieces decoded with {@link PiperModel#DECODE_OVERLAP}
 * frames of context on each side join exactly.
 *
 * <p>The cut is where upstream's split makes it: the tensors that "/dec/"
 * nodes read from nodes outside the decoder - the masked latent (input of
 * the decoder's first Conv) and, for multi-speaker voices, the speaker
 * embedding. The encoder also outputs the durations tensor (as
 * {@link PiperAlignment} exposes it). Each half keeps only the nodes and
 * weights its outputs need. Streams the protobuf like PiperAlignment, so a
 * model never has to fit in the Java heap. Pure Java; unit-tested.
 */
final class PiperSplit {
    private PiperSplit() {
    }

    private static final int GRAPH_NODE = 1;
    private static final int GRAPH_INITIALIZER = 5;
    private static final int GRAPH_INPUT = 11;
    private static final int GRAPH_OUTPUT = 12;
    private static final int GRAPH_VALUE_INFO = 13;
    private static final int NODE_INPUT = 1;
    private static final int NODE_OUTPUT = 2;
    private static final int NODE_NAME = 3;
    private static final int NODE_OP_TYPE = 4;
    private static final int TENSOR_NAME = 8;
    private static final int VALUE_INFO_NAME = 1;
    private static final int VALUE_INFO_TYPE = 2;
    private static final int TYPE_TENSOR = 1;
    private static final int TENSOR_TYPE_ELEM = 1;
    private static final int ELEM_FLOAT = 1;
    private static final int WIRE_LEN = 2;

    private static final int MODEL_GRAPH = 7;

    static final String DECODER_PREFIX = "/dec/";
    static final String AUDIO_OUTPUT = "output";

    /** One field of the GraphProto: its byte span, and for nodes/initializers what they name. */
    private static final class Entry {
        final int field;
        final int start;
        final int end;
        final List<String> inputs = new ArrayList<>(2);
        final List<String> outputs = new ArrayList<>(1);
        String name = "";
        String opType = "";

        Entry(int field, int start, int end) {
            this.field = field;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Writes the two halves.
     *
     * @return false (nothing usable written) for a graph without Piper's
     *         decoder
     */
    static boolean split(File in, File encoderOut, File decoderOut) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(in, "r");
             FileChannel channel = raf.getChannel()) {
            final MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            final PiperAlignment.Graph graph = PiperAlignment.findGraph(buf);
            if (graph == null) {
                return false;
            }
            final List<Entry> entries = entries(buf, graph);
            final Map<String, Entry> producers = new HashMap<>();
            for (Entry e : entries) {
                if (e.field == GRAPH_NODE) {
                    for (String o : e.outputs) {
                        producers.put(o, e);
                    }
                }
            }
            final List<String> boundary = boundary(entries, producers);
            if (boundary == null || !producers.containsKey(AUDIO_OUTPUT)) {
                return false;
            }
            String durations = null;
            for (Entry e : entries) {
                if (e.field == GRAPH_NODE && "Ceil".equals(e.opType) && e.outputs.size() == 1) {
                    durations = durations == null ? e.outputs.get(0) : "";
                }
            }
            final List<String> encoderOutputs = new ArrayList<>(boundary);
            if (durations != null && !durations.isEmpty()) {
                encoderOutputs.add(durations);
            }

            final Set<Entry> encoderNodes = needed(encoderOutputs, producers, Collections.emptySet());
            final Set<Entry> decoderNodes = needed(Collections.singletonList(AUDIO_OUTPUT), producers,
                    new HashSet<>(boundary));
            final List<byte[]> encoderExtra = new ArrayList<>();
            for (String name : encoderOutputs) {
                encoderExtra.add(PiperAlignment.outputEntry(name));
            }
            final List<byte[]> decoderExtra = new ArrayList<>();
            for (String name : boundary) {
                decoderExtra.add(floatInput(name));
            }
            decoderExtra.add(PiperAlignment.outputEntry(AUDIO_OUTPUT));

            write(channel, graph, entries, encoderNodes, true, encoderExtra, encoderOut);
            write(channel, graph, entries, decoderNodes, false, decoderExtra, decoderOut);
            return true;
        }
    }

    private static List<Entry> entries(ByteBuffer buf, PiperAlignment.Graph graph) {
        final List<Entry> out = new ArrayList<>();
        final int[] pos = {graph.start};
        final int end = graph.start + graph.length;
        while (pos[0] < end) {
            final int start = pos[0];
            final long tag = PiperAlignment.readVarint(buf, pos);
            final int field = (int) (tag >>> 3);
            final int wire = (int) (tag & 7);
            if (wire == WIRE_LEN && (field == GRAPH_NODE || field == GRAPH_INITIALIZER)) {
                final int length = (int) PiperAlignment.readVarint(buf, pos);
                final Entry e = new Entry(field, start, pos[0] + length);
                if (field == GRAPH_NODE) {
                    readNode(buf, pos[0], e);
                } else {
                    e.name = string(buf, pos[0], e.end, TENSOR_NAME);
                }
                pos[0] = e.end;
                out.add(e);
            } else {
                PiperAlignment.skip(buf, pos, wire);
                out.add(new Entry(field, start, pos[0]));
            }
        }
        return out;
    }

    private static void readNode(ByteBuffer buf, int from, Entry e) {
        final int[] pos = {from};
        while (pos[0] < e.end) {
            final long tag = PiperAlignment.readVarint(buf, pos);
            final int field = (int) (tag >>> 3);
            final int wire = (int) (tag & 7);
            if (wire == WIRE_LEN && field >= NODE_INPUT && field <= NODE_OP_TYPE) {
                final String s = PiperAlignment.readString(buf, pos);
                if (field == NODE_INPUT) {
                    e.inputs.add(s);
                } else if (field == NODE_OUTPUT) {
                    e.outputs.add(s);
                } else if (field == NODE_NAME) {
                    e.name = s;
                } else {
                    e.opType = s;
                }
            } else {
                PiperAlignment.skip(buf, pos, wire);
            }
        }
    }

    private static String string(ByteBuffer buf, int from, int to, int wanted) {
        final int[] pos = {from};
        while (pos[0] < to) {
            final long tag = PiperAlignment.readVarint(buf, pos);
            if ((int) (tag >>> 3) == wanted && (int) (tag & 7) == WIRE_LEN) {
                return PiperAlignment.readString(buf, pos);
            }
            PiperAlignment.skip(buf, pos, (int) (tag & 7));
        }
        return "";
    }

    /** The latent first, then any conditioning; null if this isn't Piper's decoder. */
    private static List<String> boundary(List<Entry> entries, Map<String, Entry> producers) {
        String latent = null;
        final Set<String> crossing = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.field != GRAPH_NODE || !e.name.startsWith(DECODER_PREFIX)) {
                continue;
            }
            if (latent == null && "Conv".equals(e.opType) && !e.inputs.isEmpty()) {
                latent = e.inputs.get(0);
            }
            for (String i : e.inputs) {
                final Entry p = producers.get(i);
                if (p != null && !p.name.startsWith(DECODER_PREFIX)) {
                    crossing.add(i);
                }
            }
        }
        if (latent == null || !crossing.remove(latent)) {
            return null;
        }
        final List<String> out = new ArrayList<>();
        out.add(latent);
        final List<String> rest = new ArrayList<>(crossing);
        Collections.sort(rest);
        out.addAll(rest);
        return out;
    }

    /** Nodes the given tensors are computed from, not looking past {@code stop}. */
    private static Set<Entry> needed(List<String> targets, Map<String, Entry> producers,
                                     Set<String> stop) {
        final Set<Entry> nodes = new HashSet<>();
        final Set<String> seen = new HashSet<>();
        final ArrayDeque<String> todo = new ArrayDeque<>(targets);
        while (!todo.isEmpty()) {
            final String t = todo.pop();
            if (stop.contains(t) || !seen.add(t)) {
                continue;
            }
            final Entry p = producers.get(t);
            if (p != null && nodes.add(p)) {
                for (String i : p.inputs) {
                    if (!i.isEmpty()) {
                        todo.push(i);
                    }
                }
            }
        }
        return nodes;
    }

    /** GraphProto.input entry: a float tensor of any shape. */
    static byte[] floatInput(String name) {
        final byte[] utf8 = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] elem = {(byte) (TENSOR_TYPE_ELEM << 3), ELEM_FLOAT};
        final byte[] tensorType = PiperAlignment.concat(PiperAlignment.tagAndLength(TYPE_TENSOR, elem.length), elem);
        final byte[] info = PiperAlignment.concat(
                PiperAlignment.concat(PiperAlignment.tagAndLength(VALUE_INFO_NAME, utf8.length), utf8),
                PiperAlignment.concat(PiperAlignment.tagAndLength(VALUE_INFO_TYPE, tensorType.length), tensorType));
        return PiperAlignment.concat(PiperAlignment.tagAndLength(GRAPH_INPUT, info.length), info);
    }

    /**
     * The model with its graph rebuilt: the kept nodes, the initializers they
     * read, the other graph fields as they were (the original inputs only if
     * {@code keepInputs}), no outputs or shape hints but {@code extra}.
     */
    private static void write(FileChannel src, PiperAlignment.Graph graph, List<Entry> entries,
                              Set<Entry> nodes, boolean keepInputs, List<byte[]> extra, File out)
            throws IOException {
        final Set<String> read = new HashSet<>();
        for (Entry n : nodes) {
            read.addAll(n.inputs);
        }
        final List<Entry> kept = new ArrayList<>();
        long length = 0;
        for (Entry e : entries) {
            final boolean keep;
            switch (e.field) {
                case GRAPH_NODE:
                    keep = nodes.contains(e);
                    break;
                case GRAPH_INITIALIZER:
                    keep = read.contains(e.name);
                    break;
                case GRAPH_INPUT:
                    keep = keepInputs;
                    break;
                case GRAPH_OUTPUT:
                case GRAPH_VALUE_INFO:
                    keep = false;
                    break;
                default:
                    keep = true;
            }
            if (keep) {
                kept.add(e);
                length += e.end - e.start;
            }
        }
        for (byte[] b : extra) {
            length += b.length;
        }
        try (FileOutputStream fos = new FileOutputStream(out);
             FileChannel dst = fos.getChannel()) {
            PiperAlignment.transfer(src, 0, graph.tagStart, dst);
            dst.write(ByteBuffer.wrap(PiperAlignment.tagAndLength(MODEL_GRAPH, length)));
            for (Entry e : kept) {
                PiperAlignment.transfer(src, e.start, e.end - e.start, dst);
            }
            for (byte[] b : extra) {
                dst.write(ByteBuffer.wrap(b));
            }
            final long end = graph.start + graph.length;
            PiperAlignment.transfer(src, end, src.size() - end, dst);
            fos.getFD().sync();
        }
    }
}
