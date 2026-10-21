/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.util.BackupRestoreHelper;

import org.json.JSONObject;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** A backup carries the user's settings and nothing of the app's own state, both ways. */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreDeviceTest {
    @ClassRule
    public static final com.animeshahilya.espeakng.test.KeepUserSettings KEEP =
            new com.animeshahilya.espeakng.test.KeepUserSettings();

    private static final String SETTING = "lang_zzz_speed"; // a card for no real language
    /** Reading history's snippets (feature removed): an old backup must not bring them back. */
    private static final String OLD_HISTORY = "espeak_reading_history_items";

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void roundTripKeepsSettingsOnly() throws Exception {
        final SharedPreferences prefs = EspeakApp.prefs(context());
        final boolean migrated = prefs.getBoolean(EspeakApp.PREF_PREFERENCES_MIGRATED, false);
        prefs.edit().putString(SETTING, "80")
                .putString(OLD_HISTORY, "[\"private\"]").commit();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupRestoreHelper.exportToStream(context(), out);
        final JSONObject exported = new JSONObject(out.toString("UTF-8")).getJSONObject("prefs");
        assertEquals("80", exported.optString(SETTING));
        assertFalse(exported.has(OLD_HISTORY));
        assertFalse(exported.has(EspeakApp.PREF_PREFERENCES_MIGRATED));

        // A shared file reaching for the app's own state: only the setting lands.
        prefs.edit().remove(SETTING).remove(OLD_HISTORY).commit();
        final String shared = new JSONObject().put("version", 1).put("prefs", new JSONObject()
                .put(SETTING, "120")
                .put(OLD_HISTORY, "[\"planted\"]")
                .put(EspeakApp.PREF_PREFERENCES_MIGRATED, !migrated)
                .put("piper_dl_id_99", "planted")).toString();
        BackupRestoreHelper.importFromStream(context(),
                new ByteArrayInputStream(shared.getBytes(StandardCharsets.UTF_8)));
        assertEquals("120", prefs.getString(SETTING, null));
        assertFalse(prefs.contains(OLD_HISTORY));
        assertFalse(prefs.contains("piper_dl_id_99"));
        assertEquals(migrated, prefs.getBoolean(EspeakApp.PREF_PREFERENCES_MIGRATED, false));
        assertTrue(prefs.edit().remove(SETTING).commit());
    }
}
