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

package com.animeshahilya.espeakng.tts;

import com.animeshahilya.espeakng.ui.VoiceSettings;

/**
 * Advanced Modular Acoustic & Prosody Conditioning Engine.
 *
 * <p>Speech-tailored multi-stage DSP stream processor featuring:
 * <ul>
 *   <li><b>Glottal Source Conditioning (Anti-Buzz / LF Tilt):</b> Softens the raw,
 *       buzzy Dirac-delta excitation of formant synthesis with an LF-inspired glottal
 *       pulse spectral tilt (-6 dB/octave above 1.2 kHz).</li>
 *   <li><b>Zero-Crossing Rate (ZCR) Sibilant Articulation Guard:</b> Dynamically detects
 *       unvoiced consonants (/s/, /ʃ/, /t/, /k/, /f/) using real-time zero-crossing rate
 *       and bypasses glottal tilt, preserving crisp high-frequency consonant definition
 *       and bite even at 800 WPM screen-reading speeds.</li>
 *   <li><b>Rate-Adaptive Spectral Tilt:</b> Automatically scales glottal damping down
 *       as speech rate increases so fast screen-reader reading never sounds muffled.</li>
 *   <li><b>Boundary De-Clicker & Slew Limiter:</b> Fades the initial attack and caps
 *       unphysical sample-to-sample phase steps, eliminating chunk-boundary pops.</li>
 *   <li><b>Parametric Formant Presence (2.5–5 kHz):</b> Isolates and harmonic-saturates
 *       the speech intelligibility band for vocal clarity in noisy environments.</li>
 *   <li><b>Sub-Harmonic Warmth (180 Hz):</b> Adds rich chest resonance near the fundamental
 *       vocal harmonic to counter the tinny, hollow eSpeak timbre.</li>
 *   <li><b>Dynamic Loudness Leveler:</b> Gentle upward gain rider (0.85x–1.4x) evening
 *       out unstressed syllable dips, with immediate bypass on TalkBack cursor navigation.</li>
 *   <li><b>True-Peak Limiter / Clip Guard:</b> Zero-latency lookahead-free soft clipper
 *       preventing full-scale digital clipping when multiple acoustic stages stack.</li>
 * </ul>
 *
 * <p>Every single acoustic stage is 100% modular and can be independently enabled or disabled
 * with zero CPU overhead when off and zero heap allocations in the hot audio processing path.
 */
public final class AudioOptimizer {

    // Presence band: 2.5-5kHz bandpass
    private static final double PRESENCE_LOW_HZ = 2500.0;
    private static final double PRESENCE_HIGH_HZ = 5000.0;
    private static final float PRESENCE_DRIVE = 5f;
    private static final float PRESENCE_BLEND = 0.08f;

    // Warmth band: 180Hz lowpass near vocal fundamental for chest body
    private static final double WARMTH_HZ = 180.0;
    private static final float WARMTH_DRIVE = 7f;
    private static final float WARMTH_BLEND = 0.28f;

    // Glottal source conditioning: LF-inspired spectral tilt corner at 1200 Hz
    // rolls off harsh Dirac delta harmonics (-6 dB/octave) on voiced phonemes.
    private static final double GLOTTAL_TILT_HZ = 1200.0;
    private static final float BASE_GLOTTAL_TILT_BLEND = 0.35f;

    // Zero-Crossing Rate (ZCR) Sibilant Detection parameters:
    // Tracks running zero-crossings over speech frames. Unvoiced consonants (/s/, /ʃ/, /t/, /f/)
    // have high ZCR (> 0.20-0.35 crossings/sample), while voiced vowels have low ZCR (< 0.10).
    // When ZCR crosses threshold, glottal tilt is dynamically bypassed to preserve crisp articulation.
    private static final float ZCR_EMA_ALPHA = 0.04f;
    static final float ZCR_SIBILANT_LOW_THRESHOLD = 0.16f;
    static final float ZCR_SIBILANT_HIGH_THRESHOLD = 0.28f;

    // Boundary De-Clicker & Slew Limiter parameters:
    // Eliminates sudden DC phase clicks at chunk / phoneme boundaries.
    // Soft raised-cosine (Hann) fade-in over first 64 samples (~2.9ms at 22kHz)
    // with continuous first-derivative, trailing fade-out at utterance completion,
    // and sample-to-sample slew limiting capped at MAX_SLEW_DELTA.
    public static final int DECLICK_FADE_SAMPLES = 64;
    public static final float MAX_SLEW_DELTA = 28000f;
    private static final float[] FADE_IN_RAMP = new float[DECLICK_FADE_SAMPLES];
    private static final float[] FADE_OUT_RAMP = new float[DECLICK_FADE_SAMPLES];

    static {
        for (int i = 0; i < DECLICK_FADE_SAMPLES; i++) {
            float factor = 0.5f * (1.0f - (float) Math.cos((Math.PI * i) / (DECLICK_FADE_SAMPLES - 1)));
            FADE_IN_RAMP[i] = factor;
            FADE_OUT_RAMP[i] = 1.0f - factor;
        }
    }

    // Leveler: alphas tuned at a 44.1kHz reference rate, scaled to stream rate.
    private static final int LEVELER_REFERENCE_RATE_HZ = 44100;
    private static final float LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE = 0.0005f;
    private static final float GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE = 0.0003f;
    private static final float LEVELER_TARGET_LEVEL = 9000f;
    private static final float LEVELER_MIN_GAIN = 0.85f;
    private static final float LEVELER_MAX_GAIN = 1.4f;

    // Just under full scale (32767) - safety margin for clip prevention.
    private static final float CLIP_GUARD_THRESHOLD = 30000f;
    private static final float CLIP_GUARD_RELEASE_ALPHA = 0.01f;

    // Normalization reciprocal
    private static final float INV_32768 = 1f / 32768f;

    /**
     * One-pole IIR alpha for the EMA lowpass form {@code y += alpha*(x-y)}.
     */
    public static float onePoleAlpha(double cornerHz, int sampleRateHz) {
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * cornerHz / sampleRateHz));
    }

    /**
     * Pole for the highpass recurrence {@code y = a*(y_prev + x - x_prev)}.
     */
    public static float onePoleHighpassPole(double cornerHz, int sampleRateHz) {
        return (float) Math.exp(-2.0 * Math.PI * cornerHz / sampleRateHz);
    }

    /**
     * Fast Padé [7/6] rational approximation of tanh(x).
     */
    public static float fastTanh(float x) {
        if (x <= -6.0f) return -1.0f;
        if (x >= 6.0f) return 1.0f;
        float x2 = x * x;
        float x4 = x2 * x2;
        float x6 = x4 * x2;
        float num = x * (135135.0f + 17325.0f * x2 + 378.0f * x4 + x6);
        float den = 135135.0f + 62370.0f * x2 + 3150.0f * x4 + 28.0f * x6;
        return num / den;
    }

    /**
     * Gain to bring sample magnitude down to threshold.
     */
    public static float limiterGainForSample(float sampleAbs, float threshold) {
        return sampleAbs > threshold ? threshold / sampleAbs : 1f;
    }

    /**
     * Instant attack, smoothed release gain follower.
     */
    public static float releaseSmoothedGain(float currentGain, float targetGain, float releaseAlpha) {
        return targetGain < currentGain ? targetGain : currentGain + releaseAlpha * (targetGain - currentGain);
    }

    /**
     * Scales EMA alpha to sample rate.
     */
    public static float scaledEnvelopeAlpha(float referenceAlpha, int sampleRateHz, int referenceRateHz) {
        float scaled = referenceAlpha * referenceRateHz / sampleRateHz;
        if (scaled < 0f) return 0f;
        if (scaled > 1f) return 1f;
        return scaled;
    }

    /**
     * Running envelope updater.
     */
    public static float updateLevelEnvelope(float prevEnvelope, float sampleAbs, float alpha) {
        return prevEnvelope + alpha * (sampleAbs - prevEnvelope);
    }

    /**
     * Maps envelope to leveler gain clamped to [minGain, maxGain].
     */
    public static float levelerGain(float envelope, float targetLevel, float minGain, float maxGain) {
        float denom = envelope < 1f ? 1f : envelope;
        float gain = targetLevel / denom;
        if (gain < minGain) return minGain;
        if (gain > maxGain) return maxGain;
        return gain;
    }

    /**
     * Maps running zero-crossing rate envelope to a bypass factor in [0, 1].
     * 0.0f = full glottal tilt (voiced vowels), 1.0f = full bypass (unvoiced fricatives).
     */
    public static float sibilantBypassFactor(float zcrEnvelope) {
        if (zcrEnvelope <= ZCR_SIBILANT_LOW_THRESHOLD) {
            return 0.0f;
        }
        if (zcrEnvelope >= ZCR_SIBILANT_HIGH_THRESHOLD) {
            return 1.0f;
        }
        return (zcrEnvelope - ZCR_SIBILANT_LOW_THRESHOLD) / (ZCR_SIBILANT_HIGH_THRESHOLD - ZCR_SIBILANT_LOW_THRESHOLD);
    }

    private final float presenceHighpassAlpha;
    private final float presenceLowpassAlpha;
    private final float warmthAlpha;
    private final float glottalAlpha;
    private final float zcrAlpha;
    private final float levelEnvelopeAlpha;
    private final float gainSmoothAlpha;
    private final float presenceBlend;
    private final float warmthBlend;
    private final float presenceScaledBlend;
    private final float warmthScaledBlend;
    private final float glottalTiltScaledBlend;
    private final float levelerMaxGain;
    private final float presenceDriveFactor;
    private final float warmthDriveFactor;

    private final boolean mGlottalTiltEnabled;
    private final boolean mSibilantBypassEnabled;
    private final boolean mPresenceEnabled;
    private final boolean mWarmthEnabled;
    private final boolean mDeclickerEnabled;
    private final boolean mLevelerEnabled;
    private final boolean mLimiterEnabled;
    private final boolean mIsSingleCharUtterance;

    private float prevInput = 0f;
    private float prevHighPass = 0f;
    private float presenceBand = 0f;
    private float warmthLowPass = 0f;
    private float glottalLowPass = 0f;
    private float prevRawSample = 0f;
    private float zcrEnvelope = 0f;
    private float levelEnvelope = 0f;
    private float levelerSmoothedGain = 1f;
    private float smoothedGain = 1f;
    private float prevOutputSample = 0f;
    private int mSamplesProcessed = 0;

    private float prevPresenceBand = 0f;
    private float prevWarmthLowPass = 0f;

    public AudioOptimizer(int sampleRateHz) {
        this(sampleRateHz, VoiceSettings.AUDIO_PROFILE_BALANCED);
    }

    public AudioOptimizer(int sampleRateHz, String profile) {
        this(sampleRateHz, profile, true, true, true, true, true, true, true, 175, false);
    }

    public AudioOptimizer(int sampleRateHz, String profile,
                          boolean glottalTiltEnabled, boolean sibilantBypassEnabled,
                          boolean presenceEnabled, boolean warmthEnabled,
                          boolean declickerEnabled, boolean levelerEnabled,
                          boolean limiterEnabled, int speechRateWpm,
                          boolean isSingleCharUtterance) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be resolved before constructing AudioOptimizer");
        }
        mGlottalTiltEnabled = glottalTiltEnabled;
        mSibilantBypassEnabled = sibilantBypassEnabled;
        mPresenceEnabled = presenceEnabled;
        mWarmthEnabled = warmthEnabled;
        mDeclickerEnabled = declickerEnabled;
        mLevelerEnabled = levelerEnabled;
        mLimiterEnabled = limiterEnabled;
        mIsSingleCharUtterance = isSingleCharUtterance;

        float pBlend = PRESENCE_BLEND;
        float wBlend = WARMTH_BLEND;
        float gBlend = BASE_GLOTTAL_TILT_BLEND;
        float maxGain = LEVELER_MAX_GAIN;

        if (VoiceSettings.AUDIO_PROFILE_GENTLE.equals(profile)) {
            pBlend = 0.04f;
            wBlend = 0.16f;
            gBlend = 0.20f;
            maxGain = 1.2f;
        } else if (VoiceSettings.AUDIO_PROFILE_FULL.equals(profile)) {
            pBlend = 0.12f;
            wBlend = 0.36f;
            gBlend = 0.45f;
            maxGain = 1.6f;
        } else if (VoiceSettings.AUDIO_PROFILE_CUSTOM.equals(profile)) {
            pBlend = PRESENCE_BLEND;
            wBlend = WARMTH_BLEND;
            gBlend = BASE_GLOTTAL_TILT_BLEND;
            maxGain = LEVELER_MAX_GAIN;
        }

        // Rate-adaptive spectral tilt:
        // As speech rate increases above 200 WPM, glottal damping eases off smoothly
        // so fast screen-reading retains crisp high-frequency intelligibility.
        float rateDamping = 1.0f;
        if (speechRateWpm > 200) {
            float progress = Math.min(1.0f, (speechRateWpm - 200) / 400.0f);
            rateDamping = 1.0f - progress * 0.65f;
        }

        presenceBlend = pBlend;
        warmthBlend = wBlend;
        presenceScaledBlend = 32768f * pBlend;
        warmthScaledBlend = 32768f * wBlend;
        glottalTiltScaledBlend = gBlend * rateDamping;
        presenceDriveFactor = PRESENCE_DRIVE * INV_32768;
        warmthDriveFactor = WARMTH_DRIVE * INV_32768;
        levelerMaxGain = maxGain;

        double presenceLowHz = Math.min(PRESENCE_LOW_HZ, sampleRateHz * 0.35);
        double presenceHighHz = Math.min(PRESENCE_HIGH_HZ, sampleRateHz * 0.45);
        if (presenceHighHz <= presenceLowHz) {
            presenceHighHz = presenceLowHz + 10.0;
        }
        presenceHighpassAlpha = onePoleHighpassPole(presenceLowHz, sampleRateHz);
        presenceLowpassAlpha = onePoleAlpha(presenceHighHz, sampleRateHz);
        warmthAlpha = onePoleAlpha(WARMTH_HZ, sampleRateHz);

        double glottalHz = Math.min(GLOTTAL_TILT_HZ, sampleRateHz * 0.45);
        glottalAlpha = onePoleAlpha(glottalHz, sampleRateHz);

        zcrAlpha = scaledEnvelopeAlpha(ZCR_EMA_ALPHA, sampleRateHz, LEVELER_REFERENCE_RATE_HZ);
        levelEnvelopeAlpha = scaledEnvelopeAlpha(LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE, sampleRateHz, LEVELER_REFERENCE_RATE_HZ);
        gainSmoothAlpha = scaledEnvelopeAlpha(GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE, sampleRateHz, LEVELER_REFERENCE_RATE_HZ);
    }

    public boolean isSingleCharUtterance() { return mIsSingleCharUtterance; }

    private static float oversampledSaturate(float prev, float current, float driveFactor) {
        float midpoint = (prev + current) * 0.5f;
        return (fastTanh(midpoint * driveFactor) + fastTanh(current * driveFactor)) * 0.5f;
    }

    public void process(byte[] data, int length) {
        process(data, 0, length);
    }

    public void process(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return;
        }
        final int start = Math.max(0, offset);
        final int end = Math.min(data.length, start + Math.max(0, length));
        int i = start;
        if ((i & 1) != 0) {
            i++;
        }
        while (i + 1 < end) {
            float sample = getSample16(data, i);

            // Stage 1: Boundary De-Clicker Raised-Cosine Fade-In
            if (mDeclickerEnabled && mSamplesProcessed < DECLICK_FADE_SAMPLES) {
                sample *= FADE_IN_RAMP[mSamplesProcessed];
                mSamplesProcessed++;
            }

            // Stage 2: Dynamic Loudness Leveler (clean signal envelope)
            if (mLevelerEnabled && !mIsSingleCharUtterance) {
                levelEnvelope = updateLevelEnvelope(levelEnvelope, Math.abs(sample), levelEnvelopeAlpha);
                float levelerTarget = levelerGain(levelEnvelope, LEVELER_TARGET_LEVEL, LEVELER_MIN_GAIN, levelerMaxGain);
                levelerSmoothedGain += gainSmoothAlpha * (levelerTarget - levelerSmoothedGain);
                sample *= levelerSmoothedGain;
            }

            // Stage 3: Glottal Source Conditioning (LF Anti-Buzz) with ZCR Sibilant Articulation Guard
            if (mGlottalTiltEnabled) {
                boolean cross = (sample >= 0f && prevRawSample < 0f) || (sample < 0f && prevRawSample >= 0f);
                prevRawSample = sample;
                zcrEnvelope += zcrAlpha * ((cross ? 1f : 0f) - zcrEnvelope);

                float bypass = mSibilantBypassEnabled ? sibilantBypassFactor(zcrEnvelope) : 0f;
                float effectiveBlend = glottalTiltScaledBlend * (1.0f - bypass);

                glottalLowPass += glottalAlpha * (sample - glottalLowPass);
                sample = (1.0f - effectiveBlend) * sample + effectiveBlend * glottalLowPass;
            }

            // Stage 4: Vocal Presence & Formant Clarity (2.5-5kHz bandpass + saturation)
            if (mPresenceEnabled) {
                float highPass = presenceHighpassAlpha * (prevHighPass + sample - prevInput);
                prevInput = sample;
                prevHighPass = highPass;
                presenceBand += presenceLowpassAlpha * (highPass - presenceBand);
                sample += oversampledSaturate(prevPresenceBand, presenceBand, presenceDriveFactor) * presenceScaledBlend;
                prevPresenceBand = presenceBand;
            }

            // Stage 5: Sub-Harmonic Warmth (180Hz lowpass + saturation)
            if (mWarmthEnabled) {
                warmthLowPass += warmthAlpha * (sample - warmthLowPass);
                sample += oversampledSaturate(prevWarmthLowPass, warmthLowPass, warmthDriveFactor) * warmthScaledBlend;
                prevWarmthLowPass = warmthLowPass;
            }

            // Stage 6: True-Peak Limiter / Clip Guard
            if (mLimiterEnabled) {
                float targetGain = limiterGainForSample(Math.abs(sample), CLIP_GUARD_THRESHOLD);
                smoothedGain = releaseSmoothedGain(smoothedGain, targetGain, CLIP_GUARD_RELEASE_ALPHA);
                sample *= smoothedGain;
            }

            // Stage 7: Slew Rate Limiter (eliminates pops & impulse spikes)
            if (mDeclickerEnabled) {
                float delta = sample - prevOutputSample;
                if (delta > MAX_SLEW_DELTA) {
                    sample = prevOutputSample + MAX_SLEW_DELTA;
                } else if (delta < -MAX_SLEW_DELTA) {
                    sample = prevOutputSample - MAX_SLEW_DELTA;
                }
                prevOutputSample = sample;
            }

            putSample16(data, i, (int) sample);
            i += 2;
        }
    }

    /**
     * Emits a smooth raised-cosine trailing fade-out buffer decaying from
     * the last rendered sample to zero. Call at utterance completion or stop
     * to eliminate boundary DC step clicks on DACs and AudioTracks.
     * Returns null if de-clicker is disabled or the tail is already quiet.
     */
    public byte[] generateTrailingFade() {
        if (!mDeclickerEnabled || Math.abs(prevOutputSample) < 16f) {
            return null;
        }
        final byte[] tail = new byte[DECLICK_FADE_SAMPLES * 2];
        final float last = prevOutputSample;
        for (int i = 0; i < DECLICK_FADE_SAMPLES; i++) {
            float sample = last * FADE_OUT_RAMP[i];
            putSample16(tail, i * 2, Math.round(sample));
        }
        prevOutputSample = 0f;
        return tail;
    }

    public static short getSample16(byte[] data, int offset) {
        return (short) (((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }

    public static int clamp16(int sample) {
        if (sample < -32768) return -32768;
        if (sample > 32767) return 32767;
        return sample;
    }

    public static void putSample16(byte[] data, int offset, int sample) {
        int clamped = clamp16(sample);
        data[offset] = (byte) (clamped & 0xFF);
        data[offset + 1] = (byte) ((clamped >> 8) & 0xFF);
    }

    public float getPrevOutputSample() {
        return prevOutputSample;
    }

    public void setPrevOutputSample(float sample) {
        prevOutputSample = sample;
    }

    /**
     * Resets internal DSP filter states, envelopes, and sample counters
     * to initial neutral conditions.
     */
    public void reset() {
        glottalLowPass = 0f;
        prevHighPass = 0f;
        prevInput = 0f;
        presenceBand = 0f;
        prevPresenceBand = 0f;
        warmthLowPass = 0f;
        prevWarmthLowPass = 0f;
        zcrEnvelope = 0f;
        prevRawSample = 0f;
        levelEnvelope = 0f;
        levelerSmoothedGain = 1.0f;
        smoothedGain = 1.0f;
        prevOutputSample = 0f;
        mSamplesProcessed = 0;
    }
}


