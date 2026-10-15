package com.animeshahilya.espeakng.piper;

import com.animeshahilya.espeakng.piper.PiperAlignment;
import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.piper.PiperSplit;

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

    /** INT8 (Compact) Rasa lists its speaker "cond" Conv before conv_pre: still cut at the latent. */
    @Test
    public void theLatentIsWhatConvPreReadsEvenWhenAnotherConvComesFirst() throws Exception {
        final File in = tmp.newFile("model.onnx");
        final File enc = tmp.newFile("enc.onnx");
        final File dec = tmp.newFile("dec.onnx");
        final byte[] g = node("/emb_g/Gather", "Gather", "/g", "sid", "emb.w");
        final byte[] cond = node("/dec/cond/Conv", "Conv", "/dec/c", "/g", "cond.w");
        final byte[] add = node("/dec/Add", "Add", "/dec/x", "/dec/a", "/dec/c");
        final byte[] tanh = node("/dec/Tanh", "Tanh", "output", "/dec/x");
        Files.write(in.toPath(), model(cat(ENC, CEIL, MASK, g, cond, CONV, add, tanh, ENC_W,
                initializer("emb.w"), initializer("cond.w"), DEC_W, INPUT, field(11, str(1, "sid")),
                field(12, str(1, "output")))));

        assertTrue(PiperSplit.split(in, enc, dec));
        assertArrayEquals(model(cat(cond, CONV, add, tanh, initializer("cond.w"), DEC_W,
                        PiperSplit.floatInput("/Mul_7_output_0"), PiperSplit.floatInput("/g"),
                        PiperAlignment.outputEntry("output"))),
                Files.readAllBytes(dec.toPath()));
    }

    /**
     * Compact quantizes the latent on its way into the decoder: the pair goes
     * with the decoder, so the boundary keeps the Standard name the NPU
     * decoders read.
     */
    @Test
    public void aCompactLatentsQuantizePairGoesWithTheDecoder() throws Exception {
        final File in = tmp.newFile("model.onnx");
        final File enc = tmp.newFile("enc.onnx");
        final File dec = tmp.newFile("dec.onnx");
        final byte[] q = node("/Mul_7_output_0_QuantizeLinear", "QuantizeLinear",
                "/Mul_7_output_0_QuantizeLinear_Output", "/Mul_7_output_0", "s", "z");
        final byte[] dq = node("/Mul_7_output_0_DequantizeLinear", "DequantizeLinear",
                "/Mul_7_output_0_DequantizeLinear_Output", "/Mul_7_output_0_QuantizeLinear_Output", "s", "z");
        final byte[] conv = node("/dec/conv_pre/Conv", "Conv", "/dec/a",
                "/Mul_7_output_0_DequantizeLinear_Output", "dec.w");
        Files.write(in.toPath(), model(cat(ENC, CEIL, MASK, q, dq, conv, TANH, ENC_W, DEC_W,
                initializer("s"), initializer("z"), INPUT, field(12, str(1, "output")))));

        assertTrue(PiperSplit.split(in, enc, dec));
        assertArrayEquals(model(cat(ENC, CEIL, MASK, ENC_W, INPUT,
                        PiperAlignment.outputEntry("/Mul_7_output_0"),
                        PiperAlignment.outputEntry("/Ceil_output_0"))),
                Files.readAllBytes(enc.toPath()));
        assertArrayEquals(model(cat(q, dq, conv, TANH, DEC_W, initializer("s"), initializer("z"),
                        PiperSplit.floatInput("/Mul_7_output_0"), PiperAlignment.outputEntry("output"))),
                Files.readAllBytes(dec.toPath()));
    }

    /**
     * Compact Rasa's quantized speaker vector also feeds the encoder's flow:
     * both halves get the pair, and the decoder reads the Standard name.
     */
    @Test
    public void aQuantizePairTheEncoderAlsoReadsIsCopiedIntoTheDecoder() throws Exception {
        final File in = tmp.newFile("model.onnx");
        final File enc = tmp.newFile("enc.onnx");
        final File dec = tmp.newFile("dec.onnx");
        final byte[] g = node("/Add", "Add", "/Add_output_0", "sid", "emb.w");
        final byte[] q = node("/q", "QuantizeLinear", "/q_out", "/Add_output_0", "s", "z");
        final byte[] dq = node("/dq", "DequantizeLinear", "/dq_out", "/q_out", "s", "z");
        final byte[] flow = node("/flow/cond/Conv", "Conv", "/h", "input", "/dq_out");
        final byte[] cond = node("/dec/cond/Conv", "Conv", "/dec/c", "/dq_out", "cond.w");
        final byte[] add = node("/dec/Add", "Add", "/dec/x", "/dec/a", "/dec/c");
        final byte[] tanh = node("/dec/Tanh", "Tanh", "output", "/dec/x");
        Files.write(in.toPath(), model(cat(g, q, dq, flow, CEIL, MASK, CONV, cond, add, tanh,
                initializer("emb.w"), initializer("s"), initializer("z"), initializer("cond.w"), DEC_W,
                INPUT, field(11, str(1, "sid")), field(12, str(1, "output")))));

        assertTrue(PiperSplit.split(in, enc, dec));
        assertArrayEquals(model(cat(g, q, dq, flow, CEIL, MASK, initializer("emb.w"),
                        initializer("s"), initializer("z"), INPUT, field(11, str(1, "sid")),
                        PiperAlignment.outputEntry("/Mul_7_output_0"),
                        PiperAlignment.outputEntry("/Add_output_0"),
                        PiperAlignment.outputEntry("/Ceil_output_0"))),
                Files.readAllBytes(enc.toPath()));
        assertArrayEquals(model(cat(q, dq, CONV, cond, add, tanh, initializer("s"), initializer("z"),
                        initializer("cond.w"), DEC_W, PiperSplit.floatInput("/Mul_7_output_0"),
                        PiperSplit.floatInput("/Add_output_0"), PiperAlignment.outputEntry("output"))),
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

    @Test
    public void growingPiecesStartEarlyAndNeverLeaveAGap() {
        // 100 ids of 2 frames (200 frames), a word every 5 ids (10 frames).
        final float[] durations = new float[100];
        Arrays.fill(durations, 2f);
        final List<Integer> words = new java.util.ArrayList<>();
        for (int id = 0; id < 100; id += 5) {
            words.add(id);
        }
        for (double speed : new double[] {1.5, 3, 10}) {
            final int[] ends = PiperEngine.pieceEnds(words, durations, 200, speed, 8);
            assertTrue(speed + "x: split", ends.length > 0);
            if (speed >= 10) { // slower: a later first cut, so the context decoding keeps up
                assertEquals(speed + "x: first piece is the first word past 8 frames", 1, ends[0]);
            }
            // Each piece decodes (with 12 frames of context each side) while
            // the ones before it play.
            final double s = speed * PiperEngine.DECODE_SPEED_MARGIN;
            double decoding = 0;
            for (int i = 0; i < ends.length; i++) {
                final int from = ends[i] * 10;
                final int to = i + 1 < ends.length ? ends[i + 1] * 10 : 200;
                decoding += (to - from + (i + 1 < ends.length ? 24 : 12)) / s;
                assertTrue(speed + "x: piece " + (i + 2) + " in time", decoding <= from + 1e-9);
                if (i > 0 && i + 1 < ends.length) { // the last piece is whatever is left
                    assertTrue(speed + "x: pieces grow", to - from >= ends[i] * 10 - ends[i - 1] * 10);
                }
            }
        }
        // A decoder too slow for it (with the margin), or a chunk too short: no split.
        assertEquals(0, PiperEngine.pieceEnds(words, durations, 200, 1.2, 8).length);
        assertEquals(0, PiperEngine.pieceEnds(Arrays.asList(0, 5), durations, 14, 10, 8).length);
    }
}
