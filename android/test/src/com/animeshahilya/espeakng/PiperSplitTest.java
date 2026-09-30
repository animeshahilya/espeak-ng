package com.animeshahilya.espeakng;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** The encoder/decoder split of a Piper model, on a hand-built one. */
public class PiperSplitTest {
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

    /** NodeProto: inputs, one output, name, op_type. */
    private static byte[] node(String name, String op, String output, String... inputs) {
        final ByteArrayOutputStream n = new ByteArrayOutputStream();
        for (String i : inputs) {
            n.writeBytes(str(1, i));
        }
        n.writeBytes(str(2, output));
        n.writeBytes(str(3, name));
        n.writeBytes(str(4, op));
        return field(1, n.toByteArray());
    }

    /** TensorProto initializer: dims, then its name, then raw data. */
    private static byte[] initializer(String name) {
        return field(5, cat(new byte[] {0x08, 0x02}, str(8, name), field(9, new byte[] {1, 2, 3, 4})));
    }

    private static final byte[] ENC = node("/enc_p/MatMul", "MatMul", "/h", "input", "enc.w");
    private static final byte[] CEIL = node("/Ceil", "Ceil", "/Ceil_output_0", "/h");
    private static final byte[] MASK = node("/Mul_7", "Mul", "/Mul_7_output_0", "/h", "/Ceil_output_0");
    private static final byte[] CONV = node("/dec/conv_pre/Conv", "Conv", "/dec/a", "/Mul_7_output_0", "dec.w");
    private static final byte[] TANH = node("/dec/Tanh", "Tanh", "output", "/dec/a");
    private static final byte[] ENC_W = initializer("enc.w");
    private static final byte[] DEC_W = initializer("dec.w");
    private static final byte[] INPUT = field(11, str(1, "input"));

    /** ModelProto: ir_version, graph, then an opset_import after the graph. */
    private static byte[] model(byte[] graph) {
        return cat(new byte[] {0x08, 0x08}, field(7, graph), field(8, cat(str(1, ""), new byte[] {0x10, 0x0f})));
    }

    @Test
    public void splitsAtTheDecodersInputKeepingEachHalfsOwnWeights() throws Exception {
        final File in = tmp.newFile("model.onnx");
        final File enc = tmp.newFile("enc.onnx");
        final File dec = tmp.newFile("dec.onnx");
        final byte[] shapeHint = field(13, str(1, "/h"));
        Files.write(in.toPath(), model(cat(ENC, CEIL, MASK, CONV, TANH, ENC_W, DEC_W, INPUT,
                field(12, str(1, "output")), shapeHint)));

        assertTrue(PiperSplit.split(in, enc, dec));
        // Encoder: everything up to the masked latent, which it outputs with
        // the durations; the decoder's weight and the old output are gone.
        assertArrayEquals(model(cat(ENC, CEIL, MASK, ENC_W, INPUT,
                        PiperAlignment.outputEntry("/Mul_7_output_0"),
                        PiperAlignment.outputEntry("/Ceil_output_0"))),
                Files.readAllBytes(enc.toPath()));
        // Decoder: the latent as a float input, its own nodes and weight.
        assertArrayEquals(model(cat(CONV, TANH, DEC_W, PiperSplit.floatInput("/Mul_7_output_0"),
                        PiperAlignment.outputEntry("output"))),
                Files.readAllBytes(dec.toPath()));
    }

    @Test
    public void aModelWithoutPipersDecoderIsNotSplit() throws Exception {
        final File in = tmp.newFile("model.onnx");
        Files.write(in.toPath(), model(cat(ENC, CEIL, node("/Tanh", "Tanh", "output", "/h"), ENC_W,
                INPUT, field(12, str(1, "output")))));
        assertFalse(PiperSplit.split(in, tmp.newFile("e.onnx"), tmp.newFile("d.onnx")));
    }

    @Test
    public void firstPieceEndsAtAWordStartThatLeavesTimeForTheRest() {
        // 40 ids of 2 frames each (80 frames); words start at ids 2, 10, 20, 30.
        final float[] durations = new float[40];
        Arrays.fill(durations, 2f);
        final List<Integer> words = Arrays.asList(2, 10, 20, 30);
        // Fast decoder: the shortest first piece allowed (5 frames), so word 1.
        assertEquals(1, PiperEngine.firstPieceEnd(words, durations, 80, 1000, 5));
        // Real time: (80 + 12) / (1 + 0.7) = 55 frames first, so at word 3 (60).
        assertEquals(3, PiperEngine.firstPieceEnd(words, durations, 80, 1, 5));
        // Slower than that, or unmeasured: no cut would leave time, so none.
        assertEquals(-1, PiperEngine.firstPieceEnd(words, durations, 80, 0.3, 5));
        assertEquals(-1, PiperEngine.firstPieceEnd(words, durations, 80, 0, 5));
        // A cut that leaves less than the decoder's overlap isn't worth it.
        assertEquals(-1, PiperEngine.firstPieceEnd(Arrays.asList(2, 38), durations, 80, 1000, 5));
    }
}
