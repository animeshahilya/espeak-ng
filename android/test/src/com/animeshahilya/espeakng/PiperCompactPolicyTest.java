package com.animeshahilya.espeakng;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PiperCompactPolicyTest {

    private static PiperDownloads.CatalogVoice voice(String key, String quality, boolean heavy) {
        final PiperDownloads.CatalogVoice v = new PiperDownloads.CatalogVoice();
        v.key = key;
        v.quality = quality;
        v.heavy = heavy;
        return v;
    }

    @Test
    public void preferCompactOnlyForHeavyVoicesOnSlowPhones() {
        assertTrue(PiperDownloads.preferCompact(true, "medium", PiperDevice.Fit.SLOW));
        assertTrue(PiperDownloads.preferCompact(true, "high", PiperDevice.Fit.SLOW));
        assertFalse(PiperDownloads.preferCompact(true, "compact", PiperDevice.Fit.SLOW));
        assertFalse(PiperDownloads.preferCompact(true, "medium", PiperDevice.Fit.NEUTRAL));
        assertFalse(PiperDownloads.preferCompact(true, "medium", PiperDevice.Fit.RECOMMENDED));
        assertFalse(PiperDownloads.preferCompact(false, "medium", PiperDevice.Fit.SLOW));
    }

    @Test
    public void compactComesFirstOnSlowPhones() {
        final List<PiperDownloads.CatalogVoice> in = new ArrayList<>();
        in.add(voice("hi_IN-kavya-medium", "medium", true));
        in.add(voice("hi_IN-kavya-compact", "compact", true));
        final List<PiperDownloads.CatalogVoice> out =
                PiperDownloads.orderCatalogForPhone(in, PiperDevice.Fit.SLOW);
        assertEquals("hi_IN-kavya-compact", out.get(0).key);
        assertEquals("hi_IN-kavya-medium", out.get(1).key);
    }

    @Test
    public void orderUnchangedOnFastPhones() {
        final List<PiperDownloads.CatalogVoice> in = new ArrayList<>();
        in.add(voice("hi_IN-kavya-medium", "medium", true));
        in.add(voice("hi_IN-kavya-compact", "compact", true));
        final List<PiperDownloads.CatalogVoice> out =
                PiperDownloads.orderCatalogForPhone(in, PiperDevice.Fit.RECOMMENDED);
        assertEquals("hi_IN-kavya-medium", out.get(0).key);
        assertEquals("hi_IN-kavya-compact", out.get(1).key);
    }

    @Test
    public void orderKeepsUnpairedRows() {
        final List<PiperDownloads.CatalogVoice> in = new ArrayList<>();
        in.add(voice("hi_IN-kavya-medium", "medium", true));
        in.add(voice("en_US-amy-medium", "medium", false));
        final List<PiperDownloads.CatalogVoice> out =
                PiperDownloads.orderCatalogForPhone(in, PiperDevice.Fit.SLOW);
        assertEquals(2, out.size());
        assertEquals("hi_IN-kavya-medium", out.get(0).key);
        assertEquals("en_US-amy-medium", out.get(1).key);
    }
}
