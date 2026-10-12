package com.animeshahilya.espeakng.piper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PiperCompactPolicyTest {

    private static PiperDownloads.CatalogVoice voice(String key, String quality) {
        final PiperDownloads.CatalogVoice v = new PiperDownloads.CatalogVoice();
        v.key = key;
        v.quality = quality;
        return v;
    }

    /** Installed Compact copies move to their Standard key (SYSPIN/Rasa and Piper "high"). */
    @Test
    public void aCompactVoiceMergesOnlyWhenItsKeyIsNoLongerListed() {
        final List<PiperDownloads.CatalogVoice> catalog = new ArrayList<>();
        catalog.add(voice("hi_IN-kavya-medium", "medium"));
        catalog.add(voice("de_DE-thorsten-high", "high"));
        assertEquals("hi_IN-kavya-medium",
                PiperDownloads.mergedTwin("hi_IN-kavya-compact", catalog).key);
        assertEquals("de_DE-thorsten-high",
                PiperDownloads.mergedTwin("de_DE-thorsten-compact", catalog).key);
        assertNull(PiperDownloads.mergedTwin("mr_IN-mrunal-compact", catalog));
        catalog.add(voice("de_DE-thorsten-compact", "compact"));
        assertNull(PiperDownloads.mergedTwin("de_DE-thorsten-compact", catalog));
    }
}
