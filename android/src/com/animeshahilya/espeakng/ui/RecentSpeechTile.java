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
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.animeshahilya.espeakng.EspeakApp;

/**
 * The Recent speech Quick Settings tile. Adding it is the opt-in: only then
 * does {@link RecentSpeech} keep anything, and removing it forgets all of it.
 * A tap opens {@link RecentSpeechActivity}, after unlock on a locked phone.
 */
public class RecentSpeechTile extends TileService {
    @Override
    public void onTileAdded() {
        RecentSpeech.setOn(EspeakApp.prefs(this), true);
    }

    @Override
    public void onTileRemoved() {
        RecentSpeech.setOn(EspeakApp.prefs(this), false);
    }

    @Override
    public void onStartListening() {
        // The panel just opened: what the screen reader says next is
        // about the panel, not what the user wants to hear again.
        RecentSpeech.markPanelOpened(System.currentTimeMillis());
        if (!EspeakApp.prefs(this).getBoolean(RecentSpeech.PREF_ON, false)) {
            RecentSpeech.setOn(EspeakApp.prefs(this), true); // added before the app's data was cleared
        }
        final Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::open);
        } else {
            open();
        }
    }

    // The Intent form is the only one below Android 14.
    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void open() {
        final Intent intent = new Intent(this, RecentSpeechActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
