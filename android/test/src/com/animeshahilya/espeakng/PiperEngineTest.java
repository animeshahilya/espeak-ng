package com.animeshahilya.espeakng;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PiperEngineTest {
    @Test
    public void threadsFollowFastCores() {
        // Pixel 8: 4 little, 4 mid, 1 big.
        assertEquals(4, PiperEngine.threadsFor(new int[] {182, 182, 182, 182, 725, 725, 725, 725, 1024}, 9));
        // Typical 2+6 mid-range phone: only the two big cores.
        assertEquals(2, PiperEngine.threadsFor(new int[] {400, 400, 400, 400, 400, 400, 1024, 1024}, 8));
        // All cores alike: capped at 4.
        assertEquals(6, PiperEngine.threadsFor(new int[] {1024, 1024, 1024, 1024, 1024, 1024}, 6));
        // Galaxy S25 Ultra (Snapdragon 8 Elite): no little cores, 6 measured fastest.
        assertEquals(6, PiperEngine.threadsFor(new int[] {765, 765, 765, 765, 765, 765, 1024, 1024}, 8));
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

    @Test
    public void enhancedVoiceFit() {
        final long heavy = 114_199_011L; // es_AR-daniela-high
        final long light = 63_000_000L;  // es_MX-claude-high
        // Pixel 8: keeps up with a heavy model.
        assertEquals(PiperDevice.Fit.RECOMMENDED, PiperDevice.enhancedFit(PiperDevice.Tier.HIGH, 34, heavy));
        // 8 GB but no flagship processor: memory alone does not make it fast.
        assertEquals(PiperDevice.Fit.SLOW, PiperDevice.enhancedFit(PiperDevice.Tier.HIGH, 0, heavy));
        assertEquals(PiperDevice.Fit.RECOMMENDED, PiperDevice.enhancedFit(PiperDevice.Tier.HIGH, 0, light));
        assertEquals(PiperDevice.Fit.NEUTRAL, PiperDevice.enhancedFit(PiperDevice.Tier.MID, 0, light));
        assertEquals(PiperDevice.Fit.SLOW, PiperDevice.enhancedFit(PiperDevice.Tier.LOW, 34, light));
    }

    @Test
    public void heavyVoicesNeedTheNpuOrAnAllBigCoreChip() {
        // Pixel 8: performance class 34 but little cores, SYSPIN/Rasa 1.2-1.9x real time.
        assertEquals(PiperDevice.Fit.SLOW, PiperDevice.heavyFit(false, 34, false));
        // Galaxy S25 Ultra CPU: no little cores, ~4x with 6 threads.
        assertEquals(PiperDevice.Fit.NEUTRAL, PiperDevice.heavyFit(false, 35, true));
        // Its NPU (Snapdragon build): ~22x.
        assertEquals(PiperDevice.Fit.RECOMMENDED, PiperDevice.heavyFit(true, 35, true));
        assertEquals(PiperDevice.Fit.SLOW, PiperDevice.heavyFit(false, 0, true));
    }

    @Test
    public void compactKeyReplacesTheQuality() {
        assertEquals("hi_IN-kavya-compact", PiperDownloads.compactKey("hi_IN-kavya-medium"));
        assertEquals("en_US-ljspeech-compact", PiperDownloads.compactKey("en_US-ljspeech-high"));
        assertTrue(PiperDownloads.isOffered("compact"));
    }
}
