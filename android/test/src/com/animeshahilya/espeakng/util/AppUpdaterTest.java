package com.animeshahilya.espeakng.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class AppUpdaterTest {
    private static final String SHA = "628105c03f3b0a20a60f2e147d3bac718720e46cbdc305818fabf4e1fe9f52d5";
    private static final String SD_SHA = "647b55807f0f798352248f934c47e39b3b41400b5ecb0b2f59fa75e29ae5f2d8";
    private static final String BASE = "https://github.com/animeshahilya/espeak-ng/releases/download/v2.9/";

    private static String manifest(String apkUrl, String sha) {
        return "{\"versionCode\": 32, \"versionName\": \"2.9\", \"apkUrl\": \"" + apkUrl + "\", \"sha256\": \""
                + sha + "\", \"snapdragonApkUrl\": \"" + BASE + "espeak-ng-2.9-snapdragon.apk\", "
                + "\"snapdragonSha256\": \"" + SD_SHA + "\", \"notes\": \"Faster voices.\"}";
    }

    @Test
    public void eachBuildGetsItsOwnApk() throws Exception {
        final String json = manifest(BASE + "espeak-ng-2.9.apk", SHA);
        final AppUpdater.Release standard = AppUpdater.parse(json, "");
        assertEquals(32, standard.versionCode);
        assertEquals("2.9", standard.versionName);
        assertEquals(BASE + "espeak-ng-2.9.apk", standard.apkUrl);
        assertEquals(SHA, standard.sha256);
        assertEquals("Faster voices.", standard.notes);
        final AppUpdater.Release snapdragon = AppUpdater.parse(json, "snapdragon");
        assertEquals(BASE + "espeak-ng-2.9-snapdragon.apk", snapdragon.apkUrl);
        assertEquals(SD_SHA, snapdragon.sha256);
        // A lite (32-bit) phone is never offered an arm64 APK.
        assertNull("no lite APK listed", AppUpdater.parse(json, "lite"));
    }

    @Test
    public void untrustedOrUnverifiableReleasesAreRefused() throws Exception {
        assertNull(AppUpdater.parse(manifest("https://example.com/espeak.apk", SHA), ""));
        assertNull(AppUpdater.parse(manifest("http://github.com/x.apk", SHA), ""));
        assertNull(AppUpdater.parse(manifest(BASE + "espeak-ng-2.9.apk", ""), ""));
        assertNull(AppUpdater.parse(manifest(BASE + "espeak-ng-2.9.apk", "abc"), ""));
        // A manifest without a Snapdragon APK offers the Snapdragon build nothing.
        assertNull(AppUpdater.parse("{\"versionCode\": 33, \"apkUrl\": \"" + BASE + "a.apk\", \"sha256\": \""
                + SHA + "\"}", "snapdragon"));
        assertNotNull(AppUpdater.parse("{\"versionCode\": 33, \"apkUrl\": \"" + BASE + "a.apk\", \"sha256\": \""
                + SHA + "\"}", ""));
    }

    @Test
    public void newerAndDue() throws Exception {
        final AppUpdater.Release r = AppUpdater.parse(manifest(BASE + "espeak-ng-2.9.apk", SHA), "");
        assertTrue(AppUpdater.isNewer(r, 31));
        assertFalse(AppUpdater.isNewer(r, 32));
        assertFalse(AppUpdater.isNewer(null, 1));
        final long day = AppUpdater.CHECK_INTERVAL_MS;
        assertTrue(AppUpdater.isCheckDue(0, day));
        assertFalse(AppUpdater.isCheckDue(day, day + day / 2));
        assertTrue("a clock set back still checks", AppUpdater.isCheckDue(day * 3, day));
    }

    @Test
    public void shippedManifestParses() throws Exception {
        final String json = new String(Files.readAllBytes(new File("../latest.json").toPath()),
                StandardCharsets.UTF_8);
        assertNotNull("standard APK", AppUpdater.parse(json, ""));
        assertNotNull("Snapdragon APK", AppUpdater.parse(json, "snapdragon"));
    }
}
