package com.animeshahilya.espeakng;

/**
 * Streaming 16-bit mono resampler (windowed sinc, polyphase). A request has
 * one sample rate, so a natural voice at another rate (the 24 kHz Rasa
 * voices inside eSpeak's 22050 Hz output, or the reverse) is converted
 * rather than left unspoken. Android-free for JVM tests.
 */
final class PcmResampler {
    /** Taps on each side of the output position. */
    private static final int HALF = 16;

    private final int up;
    private final int down;
    /** coef[phase][tap], see {@link #process}. */
    private final float[][] coef;

    private short[] buf = new short[0];
    private int buffered;
    /** Absolute input index of buf[0]. */
    private long bufStart;
    private long inputTotal;
    /** Next output sample's absolute index. */
    private long next;

    PcmResampler(int fromRate, int toRate) {
        final int g = gcd(fromRate, toRate);
        up = toRate / g;
        down = fromRate / g;
        // Cut-off at the lower Nyquist, a little under it for the window's skirt.
        final double cutoff = Math.min(1.0, (double) up / down) * 0.94;
        coef = new float[up][2 * HALF];
        for (int p = 0; p < up; p++) {
            for (int i = 0; i < 2 * HALF; i++) {
                final double x = (double) p / up + HALF - 1 - i; // input distance from the output point
                final double w = 0.42 + 0.5 * Math.cos(Math.PI * x / HALF) + 0.08 * Math.cos(2 * Math.PI * x / HALF);
                final double s = x == 0 ? 1.0 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
                coef[p][i] = Math.abs(x) >= HALF ? 0f : (float) (cutoff * s * w);
            }
        }
    }

    /** Feeds input; returns the output samples whose whole window has arrived. */
    short[] process(short[] in) {
        return process(in, in != null ? in.length : 0);
    }

    /** Feeds {@code count} samples from {@code in}. */
    short[] process(short[] in, int count) {
        if (in == null || count <= 0) {
            return new short[0];
        }
        append(in, count);
        return produce(false);
    }

    /** Ends the stream: the remaining output, as if silence followed. */
    short[] flush() {
        return produce(true);
    }

    private void append(short[] in, int count) {
        if (buffered + count > buf.length) {
            final short[] grown = new short[Math.max(buf.length * 2, buffered + count)];
            System.arraycopy(buf, 0, grown, 0, buffered);
            buf = grown;
        }
        System.arraycopy(in, 0, buf, buffered, count);
        buffered += count;
        inputTotal += count;
    }

    private short[] produce(boolean end) {
        // Output k sits at input position k * down / up.
        final long last = end
                ? (inputTotal * up + down - 1) / down          // every output the input covers
                : outputsBefore(inputTotal - HALF);            // only those with all taps present
        final int count = (int) Math.max(0, last - next);
        final short[] out = new short[count];
        for (int o = 0; o < count; o++, next++) {
            final long num = next * down;
            final long base = num / up;
            final float[] c = coef[(int) (num % up)];
            final int startJ = (int) (base - HALF + 1 - bufStart);
            double acc = 0;
            if (startJ >= 0 && startJ + (2 * HALF) <= buffered) {
                for (int i = 0; i < 2 * HALF; i += 4) {
                    acc += buf[startJ + i] * c[i]
                            + buf[startJ + i + 1] * c[i + 1]
                            + buf[startJ + i + 2] * c[i + 2]
                            + buf[startJ + i + 3] * c[i + 3];
                }
            } else {
                for (int i = 0; i < 2 * HALF; i++) {
                    final int j = startJ + i;
                    if (j >= 0 && j < buffered) {
                        acc += buf[j] * c[i];
                    }
                }
            }
            out[o] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(acc)));
        }
        // Keep what the next output's window still reads.
        final long keepFrom = Math.max(bufStart, next * down / up - HALF + 1);
        final int drop = (int) Math.min(buffered, keepFrom - bufStart);
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, buffered - drop);
            buffered -= drop;
            bufStart += drop;
        }
        return out;
    }

    /** Outputs whose position is below input index {@code limit}. */
    private long outputsBefore(long limit) {
        return limit <= 0 ? 0 : (limit * up + down - 1) / down;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * A natural voice's output converted to the request's rate: audio
     * resampled, word frames scaled. Call {@link #finish} after the run.
     */
    static final class Output implements PiperEngine.Output {
        private final PiperEngine.Output target;
        private final PcmResampler resampler;
        private final double scale;
        private long written;
        private short[] inBuf = new short[512];

        Output(PiperEngine.Output target, int fromRate, int toRate) {
            this.target = target;
            this.resampler = new PcmResampler(fromRate, toRate);
            this.scale = (double) toRate / fromRate;
        }

        @Override
        public void word(int position, int length, int frame) {
            target.word(position, length, (int) Math.round(frame * scale));
        }

        @Override
        public boolean audio(byte[] pcm) {
            if (pcm == null || pcm.length == 0) {
                return !target.stopped();
            }
            final int inLen = pcm.length / 2;
            short[] in = inBuf;
            if (in.length < inLen) {
                in = new short[inLen];
                inBuf = in;
            }
            for (int i = 0; i < inLen; i++) {
                in[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            }
            final short[] out = resampler.process(in, inLen);
            if (out.length == 0) {
                return !target.stopped();
            }
            written += out.length;
            return target.audio(bytes(out));
        }

        @Override
        public boolean stopped() {
            return target.stopped();
        }

        /** Delivers the tail; returns the frames written at the request's rate. */
        int finish() {
            final short[] tail = resampler.flush();
            if (tail.length > 0 && !target.stopped()) {
                written += tail.length;
                target.audio(bytes(tail));
            }
            return (int) written;
        }

        private static byte[] bytes(short[] s) {
            final byte[] b = new byte[s.length * 2];
            for (int i = 0; i < s.length; i++) {
                b[2 * i] = (byte) s[i];
                b[2 * i + 1] = (byte) (s[i] >> 8);
            }
            return b;
        }
    }
}
