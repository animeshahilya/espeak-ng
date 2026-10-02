package com.animeshahilya.espeakng;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PcmResamplerTest {

    private static short[] tone(double hz, int rate, int n, double amp) {
        final short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return s;
    }

    private static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        final short[] out = new short[n];
        int at = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** Amplitude of {@code hz} in {@code s} (single-bin DFT), skipping the edges. */
    private static double level(short[] s, double hz, int rate) {
        double re = 0, im = 0;
        int n = 0;
        for (int i = 200; i < s.length - 200; i++, n++) {
            re += s[i] * Math.cos(2 * Math.PI * hz * i / rate);
            im += s[i] * Math.sin(2 * Math.PI * hz * i / rate);
        }
        return 2 * Math.hypot(re, im) / n;
    }

    private static short[] all(PcmResampler r, short[] in) {
        return concat(r.process(in), r.flush());
    }

    @Test
    public void keepsPitchAndDuration_24kTo22050() {
        final short[] in = tone(440, 24000, 24000, 10000);
        final short[] out = all(new PcmResampler(24000, 22050), in);
        assertEquals(22050, out.length);
        assertEquals(10000, level(out, 440, 22050), 150);
        // Nothing left at the old frequency's mirror.
        assertTrue(level(out, 440 * 24000.0 / 22050, 22050) < 300);
    }

    @Test
    public void keepsPitchAndDuration_22050To24k() {
        final short[] in = tone(1000, 22050, 22050, 8000);
        final short[] out = all(new PcmResampler(22050, 24000), in);
        assertEquals(24000, out.length);
        assertEquals(8000, level(out, 1000, 24000), 120);
    }

    @Test
    public void removesContentAboveTheNewNyquist() {
        // 11.5 kHz exists at 24 kHz but not at 22050 Hz (Nyquist 11025): it
        // must be filtered, not folded down to 10.55 kHz.
        final short[] in = tone(11500, 24000, 24000, 10000);
        final short[] out = all(new PcmResampler(24000, 22050), in);
        assertTrue(level(out, 22050 - 11500, 22050) < 500);
    }

    @Test
    public void chunkedEqualsWhole() {
        final short[] in = concat(tone(300, 24000, 7000, 9000), tone(2500, 24000, 5000, 6000));
        final short[] whole = all(new PcmResampler(24000, 22050), in);
        final PcmResampler r = new PcmResampler(24000, 22050);
        final List<short[]> parts = new ArrayList<>();
        int at = 0;
        for (int size : new int[] {1, 31, 500, 4096, 3}) {
            final short[] piece = new short[Math.min(size, in.length - at)];
            System.arraycopy(in, at, piece, 0, piece.length);
            at += piece.length;
            parts.add(r.process(piece));
        }
        final short[] rest = new short[in.length - at];
        System.arraycopy(in, at, rest, 0, rest.length);
        parts.add(r.process(rest));
        parts.add(r.flush());
        assertArrayEquals(whole, concat(parts.toArray(new short[0][])));
    }

    @Test
    public void outputScalesWordFramesAndCountsFrames() {
        final List<Integer> frames = new ArrayList<>();
        final int[] samples = {0};
        final PiperEngine.Output sink = new PiperEngine.Output() {
            @Override public void word(int position, int length, int frame) { frames.add(frame); }
            @Override public boolean audio(byte[] pcm) { samples[0] += pcm.length / 2; return true; }
        };
        final PcmResampler.Output out = new PcmResampler.Output(sink, 24000, 22050);
        out.word(1, 3, 24000);
        final short[] s = tone(200, 24000, 4800, 5000);
        final byte[] pcm = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            pcm[2 * i] = (byte) s[i];
            pcm[2 * i + 1] = (byte) (s[i] >> 8);
        }
        out.audio(pcm);
        assertEquals(4410, out.finish());
        assertEquals(4410, samples[0]);
        assertEquals(Integer.valueOf(22050), frames.get(0));
    }
}
