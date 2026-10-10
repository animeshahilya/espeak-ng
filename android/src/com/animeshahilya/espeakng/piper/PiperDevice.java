/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.piper;
import com.animeshahilya.espeakng.Voice;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * How much this phone can give natural voices, from what Android itself
 * reports: the Media Performance Class (declared by flagships since Android
 * 12), total memory and the low-RAM flag. Inference threads are sized
 * separately, from the cores' own capacities (PiperEngine.threadsFor).
 */
public final class PiperDevice {
    enum Tier { LOW, MID, HIGH }

    private static volatile Tier sTier;

    private PiperDevice() {
    }

    public static Tier tier(Context context) {
        Tier tier = sTier;
        if (tier == null) {
            final ActivityManager am = context.getSystemService(ActivityManager.class);
            final ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mem);
            tier = tierFor(performanceClass(), mem.totalMem, am.isLowRamDevice());
            sTier = tier;
        }
        return tier;
    }

    static int performanceClass() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Build.VERSION.MEDIA_PERFORMANCE_CLASS : 0;
    }

    enum Fit { RECOMMENDED, NEUTRAL, SLOW }

    /** Piper's full "high" models are about 114 MB; lighter ones run like a Standard voice. */
    static final long HEAVY_MODEL_BYTES = 90_000_000L;

    /**
     * Whether an Enhanced voice suits this phone. A heavy model does about
     * 5x a Standard voice's work per second of speech: 1.8x real time on a
     * Pixel 8 (performance class 34), so a phone without a flagship-class
     * processor - however much memory it has - falls behind and pauses.
     */
    static Fit enhancedFit(Tier tier, int performanceClass, long modelBytes) {
        final boolean fastCpu = performanceClass >= Build.VERSION_CODES.TIRAMISU;
        if (tier == Tier.LOW || (modelBytes >= HEAVY_MODEL_BYTES && !fastCpu)) {
            return Fit.SLOW;
        }
        return fastCpu || tier == Tier.HIGH ? Fit.RECOMMENDED : Fit.NEUTRAL;
    }

    /**
     * Whether a heavy voice (SYSPIN/Rasa Standard, Piper Enhanced) keeps up
     * here. Measured whole-model on CPU: Pixel 8 (Tensor G3, little cores)
     * 1.2-1.9x real time, so long text pauses; Galaxy S25 Ultra (8 Elite, no
     * little cores, 6 threads) ~4x; its NPU (Snapdragon build) ~22x.
     */
    static Fit heavyFit(boolean npu, int performanceClass, boolean noLittleCores) {
        if (npu) {
            return Fit.RECOMMENDED;
        }
        return performanceClass >= Build.VERSION_CODES.TIRAMISU && noLittleCores ? Fit.NEUTRAL : Fit.SLOW;
    }

    static Fit heavyFit() {
        return heavyFit(PiperModel.hasNpuRuntime(), performanceClass(), PiperEngine.noLittleCores());
    }

    /** An 8 GB phone reports ~7.5 GB, a 4 GB one ~3.6 GB. */
    static Tier tierFor(int mediaPerformanceClass, long totalMemBytes, boolean lowRam) {
        final long gb = 1024L * 1024 * 1024;
        if (lowRam || totalMemBytes < 3 * gb) {
            return Tier.LOW;
        }
        if (mediaPerformanceClass >= Build.VERSION_CODES.S || totalMemBytes >= 7 * gb) {
            return Tier.HIGH;
        }
        return Tier.MID;
    }

    /**
     * Voices kept loaded for instant language switches. Memory-mapped
     * weights make each ~15 MB of private memory, so a flagship can hold
     * three (say Hindi, English and a regional language); a low-RAM phone
     * one, since Android reclaims its TTS service there first.
     */
    public static int voicesKeptLoaded(Tier tier) {
        switch (tier) {
            case HIGH: return 3;
            case LOW: return 1;
            default: return 2;
        }
    }
}


