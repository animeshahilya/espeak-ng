package com.animeshahilya.espeakng;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** The ONNX protobuf edit behind Piper alignments, on a hand-built model. */
public class PiperAlignmentTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] field(int number, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PiperAlignment.tagAndLength(number, payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] str(int number, String s) {
        return field(number, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] cat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** ModelProto: ir_version, graph{2 nodes, 1 output}, then an opset_import after the graph. */
    private static byte[] model(byte[] graphExtra) {
        final byte[] ceil = field(1, cat(str(1, "/w"), str(2, "/Ceil_output_0"), str(4, "Ceil")));
        final byte[] add = field(1, cat(str(2, "/Add_output_0"), str(4, "Add")));
        final byte[] out = field(12, str(1, "output"));
        final byte[] graph = field(7, cat(ceil, add, out, graphExtra));
        final byte[] irVersion = {0x08, 0x08};
        final byte[] opset = field(8, cat(str(1, ""), new byte[] {0x10, 0x0f}));
        return cat(irVersion, graph, opset);
    }

    @Test
    public void exposesTheCeilTensorOnce() throws Exception {
        final File in = tmp.newFile("model.onnx");
        final File out = tmp.newFile("patched.onnx");
        Files.write(in.toPath(), model(new byte[0]));

        assertTrue(PiperAlignment.addDurationOutput(in, out));
        // Byte-for-byte what a model built with the extra output looks like:
        // the output appended to the graph, everything after the graph kept.
        assertArrayEquals(model(PiperAlignment.outputEntry("/Ceil_output_0")),
                Files.readAllBytes(out.toPath()));

        // Already exposed: nothing to do.
        final File again = tmp.newFile("again.onnx");
        assertFalse(PiperAlignment.addDurationOutput(out, again));
    }

    @Test
    public void varintsMatchProtobuf() {
        assertArrayEquals(new byte[] {0x01}, PiperAlignment.varint(1));
        assertArrayEquals(new byte[] {(byte) 0xac, 0x02}, PiperAlignment.varint(300));
        // Graph lengths of real models need 4 bytes.
        assertEquals(4, PiperAlignment.varint(63_000_000).length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedVarintThrows() {
        // High bits set for all 10 bytes: exceeds 64 bits without termination
        final byte[] bad = new byte[] {
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };
        PiperAlignment.readVarint(java.nio.ByteBuffer.wrap(bad), new int[] {0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedStringLengthThrows() {
        // Tag saying length 100 on a 4-byte buffer
        final byte[] bad = new byte[] {100, 1, 2, 3};
        PiperAlignment.readString(java.nio.ByteBuffer.wrap(bad), new int[] {0});
    }
}
