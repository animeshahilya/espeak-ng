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

package com.reecedunn.espeak;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile to toggle 3x Rate Boost directly from the Android status shade.
 */
public class TtsRateBoostTileService extends TileService {
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;

    @Override
    public void onStartListening() {
        super.onStartListening();
        Context storageContext = EspeakApp.getStorageContext();
        if (storageContext == null) {
            storageContext = getApplicationContext();
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        if (mPrefListener == null) {
            mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (VoiceSettings.PREF_RATE_BOOST.equals(key)) {
                        updateTile();
                    }
                }
            };
        }
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        if (mPrefListener != null) {
            Context storageContext = EspeakApp.getStorageContext();
            if (storageContext == null) {
                storageContext = getApplicationContext();
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
            prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Context storageContext = EspeakApp.getStorageContext();
        if (storageContext == null) {
            storageContext = getApplicationContext();
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        boolean current = prefs.getBoolean(VoiceSettings.PREF_RATE_BOOST, false);
        boolean next = !current;
        prefs.edit().putBoolean(VoiceSettings.PREF_RATE_BOOST, next).apply();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        Context storageContext = EspeakApp.getStorageContext();
        if (storageContext == null) {
            storageContext = getApplicationContext();
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        boolean boosted = prefs.getBoolean(VoiceSettings.PREF_RATE_BOOST, false);
        tile.setState(boosted ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.quick_settings_rate_boost));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(boosted ? "3x Active" : "Normal");
        }
        tile.updateTile();
    }
}
