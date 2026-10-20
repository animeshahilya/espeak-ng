package com.animeshahilya.espeakng.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackupRestoreHelperTest {
    @Test
    public void restoresChoicesNotAppState() {
        // The user's choices, on the settings screen or not.
        for (String k : new String[] {"espeak_rate_boost", "espeak_favorite_voices", "piper_voice_hin",
                "lang_hin_speed", "espeak_script_cyr"}) {
            assertTrue(k, BackupRestoreHelper.isSetting(k));
        }
        // This app's own state: a shared backup must not reach it.
        for (String k : new String[] {"espeak_reading_history_items", "espeak_preferences_migrated",
                "espeak_whats_new_seen", "piper_dl_id_42", "piper_suspended_x", "piper_recent",
                "piper_crash_strikes_x", null}) {
            assertFalse(String.valueOf(k), BackupRestoreHelper.isSetting(k));
        }
    }
}
