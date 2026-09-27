/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * How much this phone can give natural voices, from what Android itself
 * reports: the Media Performance Class (declared by flagships since Android
 * 12), total memory and the low-RAM flag. Inference threads are sized
 * separately, from the cores' own capacities (PiperEngine.threadsFor).
 */
final class PiperDevice {
    enum Tier { LOW, MID, HIGH }

    private static volatile Tier sTier;

    private PiperDevice() {
    }

    static Tier tier(Context context) {
        Tier tier = sTier;
        if (tier == null) {
            final ActivityManager am = context.getSystemService(ActivityManager.class);
            final ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mem);
            final int performanceClass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? Build.VERSION.MEDIA_PERFORMANCE_CLASS : 0;
            tier = tierFor(performanceClass, mem.totalMem, am.isLowRamDevice());
            sTier = tier;
        }
        return tier;
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
    static int voicesKeptLoaded(Tier tier) {
        switch (tier) {
            case HIGH: return 3;
            case LOW: return 1;
            default: return 2;
        }
    }
}
