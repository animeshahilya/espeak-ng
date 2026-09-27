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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Piper's phoneme alignments (piper1-gpl patch_voice_with_alignment.py),
 * without the onnx package: a VITS model computes how many frames each
 * phoneme id lasts in a Ceil node, and marking that tensor as a second graph
 * output makes ONNX Runtime return it. Samples per id = frames * hop_length,
 * which gives exact word timing instead of an estimate.
 *
 * <p>The edit is one appended ValueInfoProto in ModelProto.graph.output,
 * done by streaming the file with only the graph's length prefix rewritten,
 * so a 60+ MB model never has to fit in the Java heap. Pure Java; unit-tested.
 */
final class PiperAlignment {
    private PiperAlignment() {
    }

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_64BIT = 1;
    private static final int WIRE_LEN = 2;
    private static final int WIRE_32BIT = 5;
    private static final int MODEL_GRAPH = 7;
    private static final int GRAPH_NODE = 1;
    private static final int GRAPH_OUTPUT = 12;
    private static final int NODE_OUTPUT = 2;
    private static final int NODE_OP_TYPE = 4;
    private static final int VALUE_INFO_NAME = 1;

    /**
     * Writes {@code in} to {@code out} with the duration tensor added as an
     * output.
     *
     * @return false (nothing written) when the model has no single Ceil
     *         output to expose or already exposes it
     */
    static boolean addDurationOutput(File in, File out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(in, "r");
             FileChannel channel = raf.getChannel()) {
            final MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            final Graph graph = findGraph(buf);
            if (graph == null) {
                return false;
            }
            final String name = durationTensor(buf, graph);
            if (name == null) {
                return false;
            }
            final byte[] entry = outputEntry(name);
            try (FileOutputStream fos = new FileOutputStream(out);
                 FileChannel dst = fos.getChannel()) {
                transfer(channel, 0, graph.tagStart, dst);
                dst.write(ByteBuffer.wrap(tagAndLength(MODEL_GRAPH, graph.length + entry.length)));
                transfer(channel, graph.start, graph.length, dst);
                dst.write(ByteBuffer.wrap(entry));
                final long end = graph.start + graph.length;
                transfer(channel, end, channel.size() - end, dst);
                fos.getFD().sync();
            }
            return true;
        }
    }

    /** The graph message's span inside the ModelProto. */
    private static final class Graph {
        final int tagStart;
        final int start;
        final int length;

        Graph(int tagStart, int start, int length) {
            this.tagStart = tagStart;
            this.start = start;
            this.length = length;
        }
    }

    private static Graph findGraph(ByteBuffer buf) {
        final int[] pos = {0};
        final int limit = buf.limit();
        while (pos[0] < limit) {
            final int tagStart = pos[0];
            final long tag = readVarint(buf, pos);
            final int field = (int) (tag >>> 3);
            final int wire = (int) (tag & 7);
            if (field == MODEL_GRAPH && wire == WIRE_LEN) {
                final int length = (int) readVarint(buf, pos);
                return new Graph(tagStart, pos[0], length);
            }
            skip(buf, pos, wire);
        }
        return null;
    }

    /** The one Ceil output name, or null if absent, ambiguous or already an output. */
    private static String durationTensor(ByteBuffer buf, Graph graph) {
        final List<String> ceil = new ArrayList<>();
        final List<String> outputs = new ArrayList<>();
        final int[] pos = {graph.start};
        final int end = graph.start + graph.length;
        while (pos[0] < end) {
            final long tag = readVarint(buf, pos);
            final int field = (int) (tag >>> 3);
            final int wire = (int) (tag & 7);
            if (wire == WIRE_LEN && (field == GRAPH_NODE || field == GRAPH_OUTPUT)) {
                final int length = (int) readVarint(buf, pos);
                final int msgEnd = pos[0] + length;
                if (field == GRAPH_NODE) {
                    readNode(buf, pos[0], msgEnd, ceil);
                } else {
                    final String name = firstString(buf, pos[0], msgEnd, VALUE_INFO_NAME);
                    if (name != null) {
                        outputs.add(name);
                    }
                }
                pos[0] = msgEnd;
            } else {
                skip(buf, pos, wire);
            }
        }
        if (ceil.size() != 1 || outputs.contains(ceil.get(0))) {
            return null;
        }
        return ceil.get(0);
    }

    private static void readNode(ByteBuffer buf, int from, int to, List<String> ceilOutputs) {
        final List<String> nodeOutputs = new ArrayList<>(1);
        boolean isCeil = false;
        final int[] pos = {from};
        while (pos[0] < to) {
            final long tag = readVarint(buf, pos);
            final int field = (int) (tag >>> 3);
            final int wire = (int) (tag & 7);
            if (wire == WIRE_LEN && (field == NODE_OUTPUT || field == NODE_OP_TYPE)) {
                final String s = readString(buf, pos);
                if (field == NODE_OUTPUT) {
                    nodeOutputs.add(s);
                } else {
                    isCeil = "Ceil".equals(s);
                }
            } else {
                skip(buf, pos, wire);
            }
        }
        if (isCeil) {
            ceilOutputs.addAll(nodeOutputs);
        }
    }

    private static String firstString(ByteBuffer buf, int from, int to, int wanted) {
        final int[] pos = {from};
        while (pos[0] < to) {
            final long tag = readVarint(buf, pos);
            if ((int) (tag >>> 3) == wanted && (int) (tag & 7) == WIRE_LEN) {
                return readString(buf, pos);
            }
            skip(buf, pos, (int) (tag & 7));
        }
        return null;
    }

    /** GraphProto.output entry: a ValueInfoProto carrying only the name, as upstream adds it. */
    static byte[] outputEntry(String name) {
        final byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        final byte[] nameField = concat(tagAndLength(VALUE_INFO_NAME, utf8.length), utf8);
        return concat(tagAndLength(GRAPH_OUTPUT, nameField.length), nameField);
    }

    static byte[] tagAndLength(int field, long length) {
        final byte[] tag = varint(((long) field << 3) | WIRE_LEN);
        return concat(tag, varint(length));
    }

    static byte[] varint(long v) {
        final byte[] tmp = new byte[10];
        int n = 0;
        while ((v & ~0x7FL) != 0) {
            tmp[n++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        tmp[n++] = (byte) v;
        final byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long readVarint(ByteBuffer buf, int[] pos) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            final byte b = buf.get(pos[0]++);
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("Malformed varint");
    }

    private static String readString(ByteBuffer buf, int[] pos) {
        final int length = (int) readVarint(buf, pos);
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = buf.get(pos[0] + i);
        }
        pos[0] += length;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skip(ByteBuffer buf, int[] pos, int wire) {
        switch (wire) {
            case WIRE_VARINT:
                readVarint(buf, pos);
                break;
            case WIRE_64BIT:
                pos[0] += 8;
                break;
            case WIRE_LEN:
                final long length = readVarint(buf, pos);
                pos[0] += (int) length;
                break;
            case WIRE_32BIT:
                pos[0] += 4;
                break;
            default:
                throw new IllegalArgumentException("Unsupported wire type " + wire);
        }
    }

    private static void transfer(FileChannel src, long from, long count, FileChannel dst)
            throws IOException {
        long done = 0;
        while (done < count) {
            done += src.transferTo(from + done, count - done, dst);
        }
    }
}
