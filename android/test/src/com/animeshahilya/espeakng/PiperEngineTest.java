package com.animeshahilya.espeakng;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PiperEngineTest {
    @Test
    public void threadsFollowFastCores() {
        // Pixel 8: 4 little, 4 mid, 1 big.
        assertEquals(4, PiperEngine.threadsFor(new int[] {182, 182, 182, 182, 725, 725, 725, 725, 1024}, 9));
        // Typical 2+6 mid-range phone: only the two big cores.
        assertEquals(2, PiperEngine.threadsFor(new int[] {400, 400, 400, 400, 400, 400, 1024, 1024}, 8));
        // All cores alike: capped at 4.
        assertEquals(4, PiperEngine.threadsFor(new int[] {1024, 1024, 1024, 1024, 1024, 1024}, 6));
        // No capacities exposed: half the cores, capped.
        assertEquals(4, PiperEngine.threadsFor(new int[0], 8));
        assertEquals(1, PiperEngine.threadsFor(new int[0], 1));
    }

    @Test
    public void deviceTiers() {
        final long gb = 1024L * 1024 * 1024;
        // Pixel 8: performance class 34, ~7.5 GB reported.
        assertEquals(PiperDevice.Tier.HIGH, PiperDevice.tierFor(34, 7_680_000_000L, false));
        // No declared class but 8 GB: still high.
        assertEquals(PiperDevice.Tier.HIGH, PiperDevice.tierFor(0, 7 * gb + 1, false));
        // 4-6 GB mid-range.
        assertEquals(PiperDevice.Tier.MID, PiperDevice.tierFor(0, 5 * gb, false));
        // Android Go / low-RAM flag or under 3 GB.
        assertEquals(PiperDevice.Tier.LOW, PiperDevice.tierFor(0, 2 * gb, false));
        assertEquals(PiperDevice.Tier.LOW, PiperDevice.tierFor(34, 8 * gb, true));
        assertEquals(3, PiperDevice.voicesKeptLoaded(PiperDevice.Tier.HIGH));
        assertEquals(1, PiperDevice.voicesKeptLoaded(PiperDevice.Tier.LOW));
    }
}
