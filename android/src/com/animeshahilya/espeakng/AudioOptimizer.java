/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng;

/**
 * "Audio Optimizer" - a speech-tailored two-band tone shaper plus a gentle loudness leveler and
 * clip guard, ported from the same DSP chain built and tuned by ear for
 * com.animeshahilya.eloquencerevived's AudioOptimizer.kt. Same math, same constants, same
 * reasoning - translated to Java for this app's plain byte[]-PCM JNI callback path rather than
 * Kotlin, since there's no other difference between the two apps' post-synthesis audio pipeline
 * that would call for different tuning.
 *
 * Presence band: a real bandpass (highpass then lowpass in series), not an open-ended highpass
 * shelf. A synthetic voice's own consonant clarity - what a "presence boost" is actually for in
 * vocal engineering - sits roughly 2.5-5kHz; rolling the excited band off above PRESENCE_HIGH_HZ
 * keeps the boost aimed at clarity instead of also exciting the 5-8kHz sibilance region (s/sh/ch/t).
 *
 * Warmth band: a one-pole lowpass around typical vocal fundamental/first-harmonic territory, for
 * body rather than clarity. Drive and blend match the richer, more bass-forward tuning
 * eloquence-revived's own AudioOptimizer settled on (WARMTH_DRIVE 6f, WARMTH_BLEND 0.20f) rather
 * than that class's own original, more conservative starting values.
 *
 * Leveler: a gentle, upward-only gain rider (0.85x-1.4x) that nudges quiet passages of an
 * utterance up toward a comfortable level - prosody makes some syllables quieter than others, and
 * evening that out adds fullness on the dynamics axis, not just the spectral one.
 *
 * Clip guard: a same-sample-snap/eased-release limiter just under full scale, a safety net for
 * the two boosts above stacking on an already-loud passage, not a loudness target.
 *
 * eSpeak's own formant/Klatt synthesis has a very different spectral character from
 * eloquence-revived's engine (openevv, an IBM Eloquence/ViaVoice rebuild) - this port carries the
 * same tuning over as a starting point, not a guarantee it's equally well-suited here; see
 * VoiceSettings.PREF_AUDIO_OPTIMIZER's own toggle for why this stays switchable rather than
 * unconditionally always-on.
 *
 * Stateful (each filter tracks its previous sample) - construct one instance per synthesis
 * request, not a shared/reused one: reusing an instance across utterances would carry stale
 * filter state (and a stale limiter/leveler gain) into the next one.
 */
final class AudioOptimizer {

    // Presence band: see this class's own doc comment for why 2.5-5kHz rather than an open-ended
    // shelf.
    private static final double PRESENCE_LOW_HZ = 2500.0;
    private static final double PRESENCE_HIGH_HZ = 5000.0;
    private static final float PRESENCE_DRIVE = 6f;
    private static final float PRESENCE_BLEND = 0.14f;

    // Warmth band: a one-pole lowpass near the vocal fundamental, for body.
    private static final double WARMTH_HZ = 180.0;
    private static final float WARMTH_DRIVE = 6f;
    private static final float WARMTH_BLEND = 0.20f;

    // Leveler: alphas tuned at a 44.1kHz reference rate, scaled to this engine's actual output
    // rate by scaledEnvelopeAlpha (a plain EMA time-constant scale, distinct from the exp()-derived
    // filter poles above).
    private static final int LEVELER_REFERENCE_RATE_HZ = 44100;
    private static final float LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE = 0.0005f;
    private static final float GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE = 0.0003f;
    private static final float LEVELER_TARGET_LEVEL = 9000f;
    private static final float LEVELER_MIN_GAIN = 0.85f;
    private static final float LEVELER_MAX_GAIN = 1.4f;

    // Just under full scale (32767) - a safety margin for the two boosts above stacking on an
    // already-loud passage, not a loudness target.
    private static final float CLIP_GUARD_THRESHOLD = 30000f;
    private static final float CLIP_GUARD_RELEASE_ALPHA = 0.01f;

    // Exact power-of-two reciprocal of the 16-bit full-scale divisor, to normalize a sample into
    // [-1, 1] before saturation without a hardware float division in the per-sample loop.
    private static final float INV_32768 = 1f / 32768f;

    /**
     * One-pole IIR alpha for the EMA lowpass form {@code y += alpha*(x-y)} at a corner of
     * {@code cornerHz} and the stream's actual {@code sampleRateHz}. Only correct for *this*
     * recurrence - see {@link #onePoleHighpassPole} for the different (complementary) coefficient
     * the highpass form below needs.
     */
    static float onePoleAlpha(double cornerHz, int sampleRateHz) {
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * cornerHz / sampleRateHz));
    }

    /**
     * The pole for the highpass recurrence {@code y = a*(y_prev + x - x_prev)} at a corner of
     * {@code cornerHz} and {@code sampleRateHz} - a *different* coefficient than
     * {@link #onePoleAlpha} despite both being "a one-pole filter's alpha", because this
     * recurrence's pole sits at {@code exp(-2*pi*fc/fs)} rather than at its complement (the EMA
     * lowpass form {@link #onePoleAlpha} is derived for). Using {@link #onePoleAlpha}'s value here
     * instead inverts the coefficient: at a low sample rate and this band's corner, it would
     * produce an almost-runaway filter that colors the treble band far too aggressively.
     */
    static float onePoleHighpassPole(double cornerHz, int sampleRateHz) {
        return (float) Math.exp(-2.0 * Math.PI * cornerHz / sampleRateHz);
    }

    /**
     * Odd-symmetric soft-clip via tanh, generating the harmonic content each band blends back in.
     * {@code normalizedInput} is expected in roughly [-1, 1].
     */
    static float harmonicSaturate(float normalizedInput, float drive) {
        return (float) Math.tanh(normalizedInput * drive);
    }

    /**
     * {@link #harmonicSaturate} evaluated at a cheap 2x oversample and decimated back down by
     * averaging, instead of once per output sample - tanh is a nonlinearity, and any nonlinearity
     * fed a signal with energy near this stream's own Nyquist frequency generates harmonics
     * *above* Nyquist that alias back down into the audible band as inharmonic noise. {@code
     * prevInput} is the same stage's own filtered value one sample ago; averaging the midpoint
     * estimate's saturated result with the current sample's is a cheap two-tap boxcar decimation
     * that pulls down that imaging energy for near-zero extra cost. Provably identical to a single
     * {@link #harmonicSaturate} call whenever the signal isn't actually changing sample to sample.
     */
    static float oversampledHarmonicSaturate(float prevInput, float currentInput, float drive) {
        float midpoint = (prevInput + currentInput) * 0.5f;
        return (harmonicSaturate(midpoint, drive) + harmonicSaturate(currentInput, drive)) * 0.5f;
    }

    /** The gain that would bring {@code sampleAbs} down to {@code threshold} - 1f (no reduction) if already under it. */
    static float limiterGainForSample(float sampleAbs, float threshold) {
        return sampleAbs > threshold ? threshold / sampleAbs : 1f;
    }

    /**
     * One step of gain smoothing: snaps down immediately whenever {@code targetGain} is more
     * restrictive than {@code currentGain} (a real peak must never be delayed), eases back up at
     * {@code releaseAlpha} pace otherwise.
     */
    static float releaseSmoothedGain(float currentGain, float targetGain, float releaseAlpha) {
        return targetGain < currentGain ? targetGain : currentGain + releaseAlpha * (targetGain - currentGain);
    }

    /**
     * Scales a plain EMA alpha (as opposed to the exp()-derived filter poles above) tuned for
     * {@code referenceRateHz} to the equivalent alpha at {@code sampleRateHz}, preserving the same
     * real-world time constant - an EMA's window in samples is roughly {@code 1/alpha}, so its
     * window in *seconds* is {@code 1/(alpha*fs)}; holding that product constant across a rate
     * change means alpha must scale by {@code referenceRateHz/sampleRateHz}.
     */
    static float scaledEnvelopeAlpha(float referenceAlpha, int sampleRateHz, int referenceRateHz) {
        float scaled = referenceAlpha * referenceRateHz / sampleRateHz;
        if (scaled < 0f) return 0f;
        if (scaled > 1f) return 1f;
        return scaled;
    }

    /** One step of the leveler's envelope follower - an exponential moving average of the absolute sample value. */
    static float updateLevelEnvelope(float prevEnvelope, float sampleAbs, float alpha) {
        return prevEnvelope + alpha * (sampleAbs - prevEnvelope);
    }

    /** Maps a measured loudness envelope to the gain that would pull it toward {@code targetLevel}, clamped to [minGain, maxGain]. */
    static float levelerGain(float envelope, float targetLevel, float minGain, float maxGain) {
        float denom = envelope < 1f ? 1f : envelope;
        float gain = targetLevel / denom;
        if (gain < minGain) return minGain;
        if (gain > maxGain) return maxGain;
        return gain;
    }

    private final float presenceHighpassAlpha;
    private final float presenceLowpassAlpha;
    private final float warmthAlpha;
    private final float levelEnvelopeAlpha;
    private final float gainSmoothAlpha;

    private float prevInput = 0f;
    private float prevHighPass = 0f;
    private float presenceBand = 0f;
    private float warmthLowPass = 0f;
    private float levelEnvelope = 0f;
    private float levelerSmoothedGain = 1f;
    private float smoothedGain = 1f;

    // One sample of history per saturated band, for oversampledHarmonicSaturate's own
    // interpolation. Distinct from presenceBand/warmthLowPass themselves (which already hold the
    // *current* sample by the time saturation runs) - these trail one sample behind.
    private float prevPresenceBand = 0f;
    private float prevWarmthLowPass = 0f;

    AudioOptimizer(int sampleRateHz) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be resolved before constructing AudioOptimizer");
        }
        presenceHighpassAlpha = onePoleHighpassPole(PRESENCE_LOW_HZ, sampleRateHz);
        presenceLowpassAlpha = onePoleAlpha(PRESENCE_HIGH_HZ, sampleRateHz);
        warmthAlpha = onePoleAlpha(WARMTH_HZ, sampleRateHz);
        levelEnvelopeAlpha = scaledEnvelopeAlpha(LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE, sampleRateHz, LEVELER_REFERENCE_RATE_HZ);
        gainSmoothAlpha = scaledEnvelopeAlpha(GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE, sampleRateHz, LEVELER_REFERENCE_RATE_HZ);
    }

    /**
     * Processes the first {@code length} bytes of {@code data} in place - 16-bit little-endian
     * mono PCM, the same format the native synthesizer's callback delivers and
     * {@link android.speech.tts.SynthesisCallback#audioAvailable}/{@link android.media.AudioTrack}
     * both expect back. An odd trailing byte (shouldn't normally happen for 16-bit PCM) is left
     * untouched. {@code length} is clamped to {@code data}'s actual size.
     */
    void process(byte[] data, int length) {
        final int end = Math.min(Math.max(length, 0), data.length);
        int i = 0;
        while (i + 1 < end) {
            float sample = (short) (((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF));

            // Leveler runs first, on the clean signal, so the tone-shaping stages below see a
            // consistently-leveled input rather than reacting to whatever level happened to come
            // out of a particular syllable.
            levelEnvelope = updateLevelEnvelope(levelEnvelope, Math.abs(sample), levelEnvelopeAlpha);
            float levelerTarget = levelerGain(levelEnvelope, LEVELER_TARGET_LEVEL, LEVELER_MIN_GAIN, LEVELER_MAX_GAIN);
            levelerSmoothedGain += gainSmoothAlpha * (levelerTarget - levelerSmoothedGain);
            sample *= levelerSmoothedGain;

            // Presence: highpass above PRESENCE_LOW_HZ, then lowpass that result below
            // PRESENCE_HIGH_HZ - the two in series isolate the 2.5-5kHz band alone.
            float highPass = presenceHighpassAlpha * (prevHighPass + sample - prevInput);
            prevInput = sample;
            prevHighPass = highPass;
            presenceBand += presenceLowpassAlpha * (highPass - presenceBand);
            sample += oversampledHarmonicSaturate(prevPresenceBand * INV_32768, presenceBand * INV_32768, PRESENCE_DRIVE) * 32768f * PRESENCE_BLEND;
            prevPresenceBand = presenceBand;

            // Warmth: a plain lowpass near the vocal fundamental, for body.
            warmthLowPass += warmthAlpha * (sample - warmthLowPass);
            sample += oversampledHarmonicSaturate(prevWarmthLowPass * INV_32768, warmthLowPass * INV_32768, WARMTH_DRIVE) * 32768f * WARMTH_BLEND;
            prevWarmthLowPass = warmthLowPass;

            float targetGain = limiterGainForSample(Math.abs(sample), CLIP_GUARD_THRESHOLD);
            smoothedGain = releaseSmoothedGain(smoothedGain, targetGain, CLIP_GUARD_RELEASE_ALPHA);
            sample *= smoothedGain;

            int out = (int) sample;
            if (out < -32768) out = -32768;
            if (out > 32767) out = 32767;
            data[i] = (byte) (out & 0xFF);
            data[i + 1] = (byte) ((out >> 8) & 0xFF);
            i += 2;
        }
    }
}
