package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.piper.PiperDownloads;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Not a check: prepares a test phone for the natural-voice device tests.
 * Installs the voices named in the instrumentation argument "voices"
 * (comma-separated catalog keys) through the app's own download path and
 * chooses each for its language. Run it on its own, e.g.
 * {@code am instrument -w -e class ...NaturalVoiceSetupDeviceTest -e voices en_US-amy-medium ...};
 * skipped without the argument, so the full suite never changes a phone.
 */
@RunWith(AndroidJUnit4.class)
public class NaturalVoiceSetupDeviceTest {
    @Test
    public void installAndChoose() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        final String voices = args.getString("voices");
        Assume.assumeTrue("no voices argument", voices != null && !voices.isEmpty());
        final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = EspeakApp.requireStorageContext(app);
        final SharedPreferences prefs = EspeakApp.prefs(storage);
        prefs.edit().putBoolean(PiperVoiceStore.PREF_ENABLED, true).commit();
        for (String key : voices.split(",")) {
            PiperDownloads.CatalogVoice voice = null;
            for (PiperDownloads.CatalogVoice v : PiperDownloads.loadCatalog(storage, false)) {
                if (v.key.equals(key)) {
                    voice = v;
                }
            }
            assertNotNull(key + " not in the catalog", voice);
            if (PiperVoiceStore.find(storage, key) == null) {
                final long id = PiperDownloads.start(app, storage, voice);
                for (int i = 0; i < 600 && PiperVoiceStore.find(storage, key) == null; i++) {
                    Thread.sleep(1000);
                    if (id >= 0) {
                        PiperDownloads.complete(app, storage, id);
                    }
                    PiperVoiceStore.invalidate();
                }
            }
            assertNotNull(key + " did not install", PiperVoiceStore.find(storage, key));
            PiperVoiceStore.assign(prefs, voice.languageKey(), key);
        }
    }
}
