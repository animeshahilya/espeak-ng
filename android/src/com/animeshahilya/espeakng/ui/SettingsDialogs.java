/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.animeshahilya.espeakng.BuildConfig;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The settings screen's dialogs: About, what's new, and the TalkBack heading every dialog title gets.
 */
public final class SettingsDialogs {
    private static final String TAG = "SettingsDialogs";

    private SettingsDialogs() {
    }

    /**
     * Marks an AlertDialog's title as a heading (API 28+) so TalkBack users
     * can jump to it with heading navigation, matching the preference
     * categories and the about dialog's own headings. No-op when the dialog
     * has no title view yet (called before show()).
     */
    public static void markAlertTitleHeading(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            View title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (title != null) {
                androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
            }
        } catch (Exception ignored) {
        }
    }


    public static void showAboutDialog(final Context context) {
        String versionName;
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = BuildConfig.VERSION_NAME;
        }

        View aboutView = LayoutInflater.from(context).inflate(R.layout.dialog_about, null);
        TextView tvVersion = aboutView.findViewById(R.id.about_version);
        if (tvVersion != null) {
            tvVersion.setText(context.getString(R.string.about_version_format, versionName));
        }

        // One TextView for every highlight: six separate views were six
        // TalkBack stops for what is visually one block. Joined here so each
        // bullet stays an independently translated string resource.
        TextView tvFeatures = aboutView.findViewById(R.id.about_features);
        if (tvFeatures != null) {
            final CharSequence[] lines = {
                    context.getText(R.string.about_feature_offline),
                    context.getText(R.string.about_feature_languages),
                    context.getText(R.string.about_feature_indian),
                    context.getText(R.string.about_feature_dsp),
                    context.getText(R.string.about_feature_dict),
                    context.getText(R.string.about_feature_locks),
            };
            tvFeatures.setText(android.text.TextUtils.join("\n\n", lines));
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(aboutView)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        openOnClick(context, aboutView.findViewById(R.id.btn_about_feedback), FEEDBACK_GROUP_URL);
        openOnClick(context, aboutView.findViewById(R.id.btn_about_github),
                "https://github.com/animeshahilya/espeak-ng");

        View aboutTitle = aboutView.findViewById(R.id.about_title_text);
        if (aboutTitle != null) {
            androidx.core.view.ViewCompat.setAccessibilityHeading(aboutTitle, true);
        }
        View featuresHeader = aboutView.findViewById(R.id.about_features_header);
        if (featuresHeader != null) {
            androidx.core.view.ViewCompat.setAccessibilityHeading(featuresHeader, true);
        }

        dialog.show();
        markAlertTitleHeading(dialog);
    }

    private static final String FEEDBACK_GROUP_URL =
            "https://chat.whatsapp.com/HKVrT3hFRsjER9mE2Jogax";

    private static void openOnClick(final Context context, View button, final String url) {
        if (button == null) return;
        button.setOnClickListener(v -> {
            // resolveActivity() returns null on API 30+ without a <queries>
            // manifest entry, which made these buttons silently do nothing.
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                // no browser or WhatsApp installed; nothing to open
            }
        });
    }

    /**
     * One-time "what's new" after an update: the stored version trails the
     * package version on first launch of a new APK. Shown once the engine
     * tree exists (so it never blocks the loading path), skipped on failure
     * to keep the screen usable.
     */
    public static void maybeShowWhatsNew(final Context context) {
        try {
            final int current;
            try {
                current = (int) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                        context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
            } catch (PackageManager.NameNotFoundException e) {
                return;
            }
            final SharedPreferences prefs = TtsSettingsActivity.getPrefs();
            if (prefs.getInt(VoiceSettings.PREF_WHATS_NEW_SEEN, 0) >= current) {
                return;
            }
            if (TtsSettingsActivity.isGone(context)) {
                return;
            }
            final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.whats_new_title)
                    .setMessage(R.string.whats_new_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOnDismissListener(d ->
                    prefs.edit().putInt(VoiceSettings.PREF_WHATS_NEW_SEEN, current).apply());
            dialog.show();
            markAlertTitleHeading(dialog);
        } catch (Throwable t) {
            Log.w(TAG, "What's-new skipped", t);
        }
    }
}
