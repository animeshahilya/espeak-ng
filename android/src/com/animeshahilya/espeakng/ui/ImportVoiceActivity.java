/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.ui;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * A voice file opened or shared from another app (a chat, the browser's
 * downloads, a file manager) - "Import as natural voice" in their Open with
 * and Share lists. Asks first, then imports it as Natural voices -> Import a
 * voice file does ({@link PickedFiles#importNaturalVoice}), over the app it
 * came from. The files are readable only while this activity lives (the
 * sender's grant), so it stays until the import is done.
 */
public class ImportVoiceActivity extends AppCompatActivity {
    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final List<Uri> uris = urisOf(getIntent());
        // Another app could name this app's own files (its FileProvider); only theirs are imported.
        uris.removeIf(u -> u.getAuthority() == null || u.getAuthority().startsWith(getPackageName()));
        if (uris.isEmpty()) {
            finish();
            return;
        }
        final StringBuilder names = new StringBuilder();
        for (Uri uri : uris) {
            names.append(names.length() > 0 ? "\n" : "").append(PickedFiles.displayName(this, uri));
        }
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.piper_import_title)
                .setMessage(names)
                .setPositiveButton(R.string.piper_import_action, (d, w) -> start(uris))
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish()));
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        super.onDestroy();
    }

    private void start(List<Uri> uris) {
        // Not cancelable: leaving would end the grant to read the files mid-import.
        show(new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.piper_import_started)
                .setCancelable(false));
        PickedFiles.importNaturalVoice(this, uris, null, this::done, this::finish);
    }

    private void done(String message, boolean imported) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish());
        if (imported) {
            builder.setNeutralButton(R.string.piper_import_open_settings, (d, w) -> {
                startActivity(new Intent(this, TtsSettingsActivity.class));
                finish();
            });
        }
        show(builder);
    }

    private void show(MaterialAlertDialogBuilder builder) {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = builder.show();
        SettingsDialogs.markAlertTitleHeading(mDialog);
    }

    /** The files in an Open with (VIEW) or Share (SEND, SEND_MULTIPLE) intent. */
    static List<Uri> urisOf(Intent intent) {
        final List<Uri> uris = new ArrayList<>();
        if (intent == null) {
            return uris;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                uris.add(uri);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            final ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                uris.addAll(list);
            }
        }
        if (uris.isEmpty() && intent.getClipData() != null) { // senders that fill only the clip
            final ClipData clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            }
        }
        // Only content:// - a file:// path from another app would be read with this app's rights.
        uris.removeIf(u -> !"content".equals(u.getScheme()));
        return uris;
    }
}
