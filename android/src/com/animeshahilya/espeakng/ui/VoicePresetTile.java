/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng.ui;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;

/**
 * The Voice preset Quick Settings tile: each tap switches to the next
 * {@link VoicePresets preset} and shows its name (as the tile's state, so
 * TalkBack says it). With no presets yet, a tap opens the settings.
 */
public class VoicePresetTile extends TileService {
    @Override
    public void onStartListening() {
        show(VoicePresets.current(EspeakApp.prefs(this)));
    }

    @Override
    public void onClick() {
        final SharedPreferences prefs = EspeakApp.prefs(this);
        if (VoicePresets.names(prefs).isEmpty()) {
            openSettings();
            return;
        }
        show(VoicePresets.next(prefs));
    }

    private void show(String name) {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean none = VoicePresets.names(EspeakApp.prefs(this)).isEmpty();
        final String state = none ? getString(R.string.preset_tile_none)
                : name != null ? name : getString(R.string.preset_tile_own);
        tile.setState(none ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(state);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.setStateDescription(state);
        }
        tile.updateTile();
    }

    // The Intent form is the only one below Android 14.
    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openSettings() {
        final Intent intent = new Intent(this, TtsSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
