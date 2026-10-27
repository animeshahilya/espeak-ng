package com.animeshahilya.espeakng;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.piper.PiperDownloads;
import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Not a check: loads every installed natural voice once, as the service
 * would, and logs where each runs (the "PiperVoices" load line names the
 * decoder's and encoder's place, or why not the NPU). Run it on its own on a
 * Snapdragon phone after changing NPU code or files:
 * {@code am instrument -w -e class ...NpuAuditDeviceTest ...}, then
 * {@code logcat -s PiperVoices NpuAudit}. Each voice is unloaded after.
 * With {@code -e fetch true} it first fetches the NPU files voices lack, as
 * the service does at start.
 */
@RunWith(AndroidJUnit4.class)
public class NpuAuditDeviceTest {
    @Test
    public void loadEveryVoice() throws Exception {
        final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = EspeakApp.requireStorageContext(app);
        final List<PiperVoiceStore.Installed> voices = PiperVoiceStore.list(storage);
        Assume.assumeTrue("no natural voices installed", !voices.isEmpty());
        if ("true".equals(InstrumentationRegistry.getArguments().getString("fetch"))) {
            // As at service start: NPU files a voice lacks (new catalog entries).
            final long t0 = System.nanoTime();
            PiperDownloads.fetchNpuDecoders(app, storage);
            Log.i("NpuAudit", "NPU files fetched in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        for (PiperVoiceStore.Installed v : voices) {
            final long t0 = System.nanoTime();
            try {
                PiperEngine.get().loadNow(v.key, v.model(), v.config);
                Log.i("NpuAudit", v.key + " ready in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            } catch (Exception e) {
                Log.w("NpuAudit", v.key + " failed to load", e);
            } finally {
                PiperEngine.get().unload(v.key);
            }
        }
    }
}
