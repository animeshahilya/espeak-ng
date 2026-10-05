/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives DownloadManager's completion broadcast and installs the voice.
 *
 * <p>Exported because the system download provider (another uid) sends it.
 * A forged broadcast can't do harm: {@link PiperDownloads#complete} only
 * acts on ids this app enqueued, re-reads the status from DownloadManager
 * itself, and installs nothing whose checksum doesn't match the catalog.
 */
public class PiperDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id < 0) {
            return;
        }
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        EspeakApp.runAsync(() -> {
            try {
                final Context storage = EspeakApp.requireStorageContext(app);
                final PiperDownloads.Result result = PiperDownloads.complete(app, storage, id);
                if (result == PiperDownloads.Result.NOT_OURS) {
                    return;
                }
                final int message = result == PiperDownloads.Result.INSTALLED
                        ? R.string.piper_download_done : R.string.piper_download_failed;
                PiperSettings.toast(app, app.getString(message));
            } finally {
                pending.finish();
            }
        });
    }
}
