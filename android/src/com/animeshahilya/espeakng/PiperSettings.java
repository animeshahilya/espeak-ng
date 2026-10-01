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
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "Natural voices" settings page: which voice speaks each language,
 * downloading voices from the Piper catalog, and managing downloaded ones.
 *
 * <p>Everything is a plain list or dialog built from Material components, so
 * TalkBack reads each voice as one row ("Priyamvada, India, Standard, 63 MB,
 * downloaded") and each choice as a standard single-choice list.
 */
final class PiperSettings {
    private static final String TAG = "PiperSettings";
    static final String KEY_SCREEN = "sub_natural_voices";
    private static final String KEY_LANGUAGES = "category_piper_languages";
    private static final String KEY_DOWNLOAD = "action_piper_download";
    private static final String KEY_MANAGE = "action_piper_manage";
    private static final long PROGRESS_POLL_MS = 1500;

    private PiperSettings() {
    }

    /** Wires the page. Called from TtsSettingsActivity.buildPreferences (not on Wear). */
    static void configure(final Context context, final PreferenceScreen screen,
                          final PreferenceFragmentCompat fragment, final SharedPreferences prefs) {
        final Preference download = screen.findPreference(KEY_DOWNLOAD);
        final Preference manage = screen.findPreference(KEY_MANAGE);
        if (download == null || manage == null) {
            return;
        }
        download.setOnPreferenceClickListener(p -> {
            showCatalog(context, prefs, false);
            return true;
        });
        manage.setOnPreferenceClickListener(p -> {
            showManage(context, prefs);
            return true;
        });
        // The main page's row says when natural voices are off, so someone
        // who wants pure eSpeak can see it is without opening the page.
        final Preference row = screen.findPreference(KEY_SCREEN);
        final Preference enabled = screen.findPreference(PiperVoiceStore.PREF_ENABLED);
        if (row != null && enabled != null) {
            showEnabled(context, row, PiperVoiceStore.isEnabled(prefs));
            enabled.setOnPreferenceChangeListener((p, value) -> {
                showEnabled(context, row, Boolean.TRUE.equals(value));
                return true;
            });
        }
        final Preference acceleration = screen.findPreference(PiperVoiceStore.PREF_ACCELERATION);
        if (acceleration != null) {
            acceleration.setOnPreferenceChangeListener((p, value) -> {
                PiperEngine.get().setAcceleration(Boolean.TRUE.equals(value));
                return true;
            });
        }
        refresh(context, screen, prefs);

        // Live while the screen is visible: voices finishing their download
        // appear (and are announced) without leaving the page, and download
        // progress ticks along in the Download row's summary.
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (updateProgress(context, screen)) {
                    handler.postDelayed(this, PROGRESS_POLL_MS);
                }
            }
        };
        final BroadcastReceiver changed = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                refresh(context, screen, prefs);
                final String key = intent.getStringExtra(PiperDownloads.EXTRA_KEY);
                final PiperVoiceStore.Installed voice = key == null ? null
                        : PiperVoiceStore.find(storage(context), key);
                if (voice != null && fragment instanceof TtsSettingsActivity.PrefsEspeakFragment
                        && fragment.isAdded()) {
                    final String lang = intent.getStringExtra(PiperDownloads.EXTRA_ASSIGNED_LANGUAGE);
                    ((TtsSettingsActivity.PrefsEspeakFragment) fragment).announce(lang != null
                            ? context.getString(R.string.piper_voice_ready_assigned,
                                    voice.config.displayName(), languageName(voice.config.languageFamily))
                            : context.getString(R.string.piper_voice_ready, voice.config.displayName()));
                }
            }
        };
        if (fragment == null) {
            return;
        }
        fragment.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onResume(@NonNull LifecycleOwner owner) {
                ContextCompat.registerReceiver(context, changed,
                        new IntentFilter(PiperDownloads.ACTION_VOICES_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                // A completion broadcast can be missed if the process died
                // mid-download; finish those installs now.
                new Thread(() -> {
                    PiperDownloads.reconcile(context.getApplicationContext(), storage(context));
                    handler.post(() -> refresh(context, screen, prefs));
                }, "piper-reconcile").start();
                handler.post(poll);
            }

            @Override
            public void onPause(@NonNull LifecycleOwner owner) {
                handler.removeCallbacks(poll);
                try {
                    context.unregisterReceiver(changed);
                } catch (IllegalArgumentException ignored) {
                    // Not registered.
                }
            }
        });
    }

    /** A toast from any thread (downloads, deletes and installs finish off the main thread). */
    static void toast(Context app, CharSequence text) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }

    private static Context storage(Context context) {
        return EspeakApp.requireStorageContext(context.getApplicationContext());
    }

    /** Rebuilds the per-language rows and the Downloaded-voices summary. */
    static void refresh(Context context, PreferenceScreen screen, SharedPreferences prefs) {
        final PreferenceCategory languages = screen.findPreference(KEY_LANGUAGES);
        if (languages == null) {
            return;
        }
        PiperVoiceStore.invalidate();
        final List<PiperVoiceStore.Installed> installed = PiperVoiceStore.list(storage(context));

        // Group by language, in the order of the voices' own language names.
        final Map<String, List<PiperVoiceStore.Installed>> byLanguage = new LinkedHashMap<>();
        for (PiperVoiceStore.Installed v : installed) {
            List<PiperVoiceStore.Installed> list = byLanguage.get(v.languageKey());
            if (list == null) {
                list = new ArrayList<>();
                byLanguage.put(v.languageKey(), list);
            }
            list.add(v);
        }

        languages.removeAll();
        if (byLanguage.isEmpty()) {
            final Preference empty = new Preference(context);
            empty.setKey("piper_languages_empty");
            empty.setSummary(R.string.piper_languages_empty);
            empty.setSelectable(false);
            empty.setIconSpaceReserved(false);
            languages.addPreference(empty);
        }
        for (Map.Entry<String, List<PiperVoiceStore.Installed>> e : byLanguage.entrySet()) {
            final String lang = e.getKey();
            final List<PiperVoiceStore.Installed> voices = e.getValue();
            final CharSequence[] entries = new CharSequence[voices.size() + 1];
            final CharSequence[] values = new CharSequence[voices.size() + 1];
            entries[0] = context.getString(R.string.piper_language_espeak);
            values[0] = "";
            for (int i = 0; i < voices.size(); i++) {
                final PiperVoiceStore.Installed v = voices.get(i);
                entries[i + 1] = voiceLabel(context, v.config);
                values[i + 1] = v.key;
            }
            final ListPreference row = new ListPreference(context);
            row.setKey(PiperVoiceStore.PREF_VOICE_PREFIX + lang);
            row.setTitle(languageName(voices.get(0).config.languageFamily));
            row.setDialogTitle(row.getTitle());
            row.setEntries(entries);
            row.setEntryValues(values);
            row.setDefaultValue("");
            row.setIconSpaceReserved(false);
            row.setSummaryProvider(p -> {
                final ListPreference list = (ListPreference) p;
                final CharSequence entry = list.getEntry();
                if (entry == null || "".equals(list.getValue())) {
                    return context.getString(R.string.piper_language_summary_espeak);
                }
                if (PiperCrashGuard.isSuspended(prefs, list.getValue())) {
                    final PiperVoiceStore.Installed v =
                            PiperVoiceStore.find(storage(context), list.getValue());
                    return context.getString(R.string.piper_voice_suspended,
                            v != null ? v.config.displayName() : entry);
                }
                return entry;
            });
            row.setOnPreferenceChangeListener((p, value) -> {
                final PiperVoiceStore.Installed chosen =
                        PiperVoiceStore.find(storage(context), String.valueOf(value));
                if (chosen != null) {
                    // Chosen again after a suspension: the user wants to retry it.
                    if (PiperCrashGuard.isSuspended(prefs, chosen.key)) {
                        PiperCrashGuard.clear(prefs, chosen.key);
                        // Re-choosing the same voice leaves the value, and so
                        // the summary, unchanged: rebuild so it stops saying so.
                        new Handler(Looper.getMainLooper()).post(() -> refresh(context, screen, prefs));
                    }
                    // Load now, so the very next utterance already uses it.
                    PiperEngine.get().preload(chosen.key, chosen.model(), chosen.config);
                }
                return true;
            });
            languages.addPreference(row);
            // A stored key for a deleted voice would show no selection.
            final String assigned = PiperVoiceStore.assignedKey(prefs, lang);
            if (assigned != null && PiperVoiceStore.find(storage(context), assigned) == null) {
                row.setValue("");
            }
        }

        final Preference manage = screen.findPreference(KEY_MANAGE);
        if (manage != null) {
            long bytes = 0;
            for (PiperVoiceStore.Installed v : installed) {
                bytes += v.sizeBytes();
            }
            manage.setSummary(installed.isEmpty()
                    ? context.getString(R.string.piper_manage_summary_none)
                    : context.getResources().getQuantityString(R.plurals.piper_manage_summary,
                            installed.size(), installed.size(), Formatter.formatShortFileSize(context, bytes)));
        }
        updateProgress(context, screen);
    }

    /** @return true while any download is running (keep polling). */
    private static boolean updateProgress(Context context, PreferenceScreen screen) {
        final Preference download = screen.findPreference(KEY_DOWNLOAD);
        if (download == null) {
            return false;
        }
        final Context app = context.getApplicationContext();
        for (String key : PiperDownloads.pendingKeys(storage(context))) {
            final PiperDownloads.Progress p = PiperDownloads.progress(app, storage(context), key);
            if (p != null && p.status != DownloadManager.STATUS_SUCCESSFUL
                    && p.status != DownloadManager.STATUS_FAILED) {
                download.setSummary(context.getString(R.string.piper_downloading_summary,
                        PiperDownloads.nameFromKey(key), p.percent()));
                return true;
            }
        }
        download.setSummary(R.string.setting_piper_download_summary);
        return false;
    }

    private static void showEnabled(Context context, Preference row, boolean enabled) {
        row.setSummary(enabled ? R.string.screen_natural_voices_summary
                : R.string.screen_natural_voices_off);
    }

    /** "Hindi" in the phone's language, from Piper's "hi". */
    @SuppressWarnings("deprecation")
    static String languageName(String family) {
        if (family == null) {
            return "";
        }
        final Locale locale = new Locale(family);
        final String name = locale.getDisplayLanguage();
        if (name == null || name.isEmpty() || name.equals(family)) {
            final String known = PiperVoiceConfig.englishName(family);
            return known != null ? known : family;
        }
        return name;
    }

    private static String qualityLabel(Context context, String quality) {
        return context.getString(PiperDownloads.isEnhanced(quality) ? R.string.piper_quality_enhanced
                : PiperDownloads.isCompact(quality) ? R.string.piper_quality_compact
                : R.string.piper_quality_standard);
    }

    /** In the catalog, Enhanced also says whether this phone suits it (PiperDevice). */
    private static String catalogQualityLabel(Context context, PiperDownloads.CatalogVoice v) {
        // Heavy voices and their Compact versions: by what this phone can run.
        // Compact is recommended exactly where its original would pause: a
        // heavy SYSPIN/Rasa voice by heavyFit, a Piper Enhanced one by enhancedFit.
        if (PiperDownloads.isCompact(v.quality)) {
            final PiperDevice.Fit original = v.heavy ? PiperDevice.heavyFit()
                    : PiperDevice.enhancedFit(PiperDevice.tier(context), PiperDevice.performanceClass(),
                            PiperDevice.HEAVY_MODEL_BYTES);
            return context.getString(original == PiperDevice.Fit.SLOW
                    ? R.string.piper_quality_compact_recommended : R.string.piper_quality_compact);
        }
        if (v.heavy && PiperDevice.heavyFit() == PiperDevice.Fit.SLOW) {
            return context.getString(PiperDownloads.isEnhanced(v.quality)
                    ? R.string.piper_quality_enhanced_slow : R.string.piper_quality_standard_slow);
        }
        if (PiperDownloads.isEnhanced(v.quality)) {
            switch (PiperDevice.enhancedFit(PiperDevice.tier(context),
                    PiperDevice.performanceClass(), v.modelSize)) {
                case RECOMMENDED: return context.getString(R.string.piper_quality_enhanced_recommended);
                case SLOW: return context.getString(R.string.piper_quality_enhanced_slow);
                default: break;
            }
        }
        return qualityLabel(context, v.quality);
    }

    /** "Priyamvada, India, Standard" for an installed voice. */
    private static String voiceLabel(Context context, PiperVoiceConfig config) {
        String region = config.languageCode;
        final int us = region == null ? -1 : region.indexOf('_');
        if (us > 0) {
            region = new Locale("", region.substring(us + 1)).getDisplayCountry();
        }
        return context.getString(R.string.piper_voice_entry, config.displayName(),
                region == null ? "" : region, qualityLabel(context, config.quality));
    }

    // ---- Catalog: language list -> voice list -> confirm -> download ----

    private static void showCatalog(final Context context, final SharedPreferences prefs,
                                    final boolean refresh) {
        final AlertDialog loading = new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.piper_loading_catalog)
                .setCancelable(true)
                .show();
        new Thread(() -> {
            List<PiperDownloads.CatalogVoice> catalog = null;
            try {
                catalog = PiperDownloads.loadCatalog(storage(context), refresh);
            } catch (Exception e) {
                Log.w(TAG, "Catalog load failed", e);
            }
            final List<PiperDownloads.CatalogVoice> result = catalog;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (TtsSettingsActivity.isGone(context) || !loading.isShowing()) {
                    return; // cancelled while loading
                }
                loading.dismiss();
                if (result == null || result.isEmpty()) {
                    Toast.makeText(context, R.string.piper_catalog_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                showLanguages(context, prefs, result);
            });
        }, "piper-catalog").start();
    }

    private static void showLanguages(final Context context, final SharedPreferences prefs,
                                      final List<PiperDownloads.CatalogVoice> catalog) {
        final Map<String, List<PiperDownloads.CatalogVoice>> byLanguage = new LinkedHashMap<>();
        for (PiperDownloads.CatalogVoice v : catalog) {
            List<PiperDownloads.CatalogVoice> list = byLanguage.get(v.languageKey());
            if (list == null) {
                list = new ArrayList<>();
                byLanguage.put(v.languageKey(), list);
            }
            list.add(v);
        }
        final List<String> keys = new ArrayList<>(byLanguage.keySet());
        final String system = PiperVoiceStore.languageKey(Locale.getDefault());
        Collections.sort(keys, (a, b) -> {
            // The phone's own language first, the rest by name.
            if (a.equals(system) != b.equals(system)) {
                return a.equals(system) ? -1 : 1;
            }
            return languageName(byLanguage.get(a).get(0).family)
                    .compareToIgnoreCase(languageName(byLanguage.get(b).get(0).family));
        });
        final CharSequence[] rows = new CharSequence[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            final List<PiperDownloads.CatalogVoice> voices = byLanguage.get(keys.get(i));
            final PiperDownloads.CatalogVoice first = voices.get(0);
            final String name = languageName(first.family);
            final String label = name.equalsIgnoreCase(first.nameNative) ? name
                    : context.getString(R.string.piper_language_row, name, first.nameNative);
            rows[i] = label + ", " + context.getResources().getQuantityString(
                    R.plurals.piper_voice_count, voices.size(), voices.size());
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.piper_choose_language)
                .setItems(rows, (d, which) ->
                        showVoices(context, prefs, byLanguage.get(keys.get(which))))
                .setNeutralButton(R.string.piper_refresh, (d, w) -> showCatalog(context, prefs, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    private static void showVoices(final Context context, final SharedPreferences prefs,
                                   final List<PiperDownloads.CatalogVoice> voices) {
        final Context storage = storage(context);
        final List<String> pending = PiperDownloads.pendingKeys(storage);
        final CharSequence[] rows = new CharSequence[voices.size()];
        for (int i = 0; i < voices.size(); i++) {
            final PiperDownloads.CatalogVoice v = voices.get(i);
            String row = context.getString(R.string.piper_catalog_voice_row, v.displayName(),
                    v.country, catalogQualityLabel(context, v),
                    Formatter.formatShortFileSize(context, v.modelSize));
            if (v.numSpeakers > 1) {
                row += ", " + context.getResources().getQuantityString(
                        R.plurals.piper_speakers, v.numSpeakers, v.numSpeakers);
            }
            if (PiperVoiceStore.find(storage, v.key) != null) {
                row = context.getString(R.string.piper_catalog_voice_installed, row);
            } else if (pending.contains(v.key)) {
                row = context.getString(R.string.piper_catalog_voice_downloading, row);
            }
            rows[i] = row;
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.piper_choose_voice,
                        languageName(voices.get(0).family)))
                .setItems(rows, (d, which) -> {
                    final PiperDownloads.CatalogVoice v = voices.get(which);
                    final PiperVoiceStore.Installed installed = PiperVoiceStore.find(storage, v.key);
                    if (installed != null) {
                        showVoiceActions(context, prefs, installed);
                    } else if (pending.contains(v.key)) {
                        confirmCancel(context, v);
                    } else {
                        confirmDownload(context, v);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    private static void confirmDownload(final Context context, final PiperDownloads.CatalogVoice v) {
        final MediaPlayer[] player = {null};
        String message = context.getString(R.string.piper_confirm_message,
                languageName(v.family), v.country,
                Formatter.formatShortFileSize(context, v.modelSize));
        if (v.source != null) {
            // Community voice: its maker and license (CC BY needs the credit).
            message = context.getString(R.string.piper_confirm_community, message, v.source,
                    v.license != null ? v.license : "?");
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.piper_confirm_title, v.displayName()))
                .setMessage(message)
                .setPositiveButton(R.string.piper_confirm_download, (d, w) -> startDownload(context, v))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> releasePlayer(player));
        final boolean hasSample = v.sampleUrl() != null;
        if (hasSample) {
            builder.setNeutralButton(R.string.piper_play_sample, null);
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            // Neutral button plays without closing the dialog.
            final Button sample = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (hasSample && sample != null) {
                sample.setOnClickListener(b -> playSample(context, v, player));
            }
        });
        dialog.show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /** Streams the catalog's recorded sample of the voice (rhasspy/piper-samples). */
    private static void playSample(Context context, PiperDownloads.CatalogVoice v, MediaPlayer[] player) {
        releasePlayer(player);
        final String url = v.sampleUrl();
        if (url == null) {
            return;
        }
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((m, what, extra) -> {
                Toast.makeText(context, R.string.piper_sample_failed, Toast.LENGTH_SHORT).show();
                return true;
            });
            mp.prepareAsync();
            player[0] = mp;
        } catch (Exception e) {
            Log.w(TAG, "Sample playback failed", e);
            Toast.makeText(context, R.string.piper_sample_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void releasePlayer(MediaPlayer[] player) {
        if (player[0] != null) {
            try {
                player[0].release();
            } catch (Exception ignored) {
                // Already released.
            }
            player[0] = null;
        }
    }

    private static void startDownload(final Context context, final PiperDownloads.CatalogVoice v) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            int message;
            try {
                PiperDownloads.start(app, storage(context), v);
                message = 0;
            } catch (PiperDownloads.UnsupportedVoiceException e) {
                message = R.string.piper_download_unsupported;
            } catch (Exception e) {
                Log.w(TAG, "Download start failed", e);
                message = R.string.piper_download_start_failed;
            }
            toast(app, message == 0
                    ? app.getString(R.string.piper_download_started, v.displayName())
                    : app.getString(message));
        }, "piper-download").start();
    }

    private static void confirmCancel(final Context context, final PiperDownloads.CatalogVoice v) {
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(v.displayName())
                .setPositiveButton(R.string.piper_action_cancel_download, (d, w) -> new Thread(() ->
                        PiperDownloads.cancel(context.getApplicationContext(), storage(context), v.key),
                        "piper-cancel").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    // ---- Downloaded voices: test, delete ----

    private static void showManage(final Context context, final SharedPreferences prefs) {
        final List<PiperVoiceStore.Installed> installed = PiperVoiceStore.list(storage(context));
        if (installed.isEmpty()) {
            Toast.makeText(context, R.string.piper_manage_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] rows = new CharSequence[installed.size()];
        for (int i = 0; i < rows.length; i++) {
            final PiperVoiceStore.Installed v = installed.get(i);
            rows[i] = context.getString(R.string.piper_manage_row, voiceLabel(context, v.config),
                    languageName(v.config.languageFamily),
                    Formatter.formatShortFileSize(context, v.sizeBytes()));
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.piper_manage_title)
                .setItems(rows, (d, which) -> showVoiceActions(context, prefs, installed.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /** Below this many seconds of audio per second of decoding, long text pauses. */
    private static final double SLOW_SPEED = 2.0;

    /** The Compact version of an installed voice, when the app offers one and it isn't installed. */
    private static PiperDownloads.CatalogVoice compactVersion(Context context,
                                                              PiperVoiceStore.Installed voice) {
        if (PiperDownloads.isCompact(voice.config.quality)) {
            return null;
        }
        final String key = PiperDownloads.compactKey(voice.key);
        if (PiperVoiceStore.find(storage(context), key) != null) {
            return null;
        }
        for (PiperDownloads.CatalogVoice v : PiperDownloads.bundledExtras(context)) {
            if (v.key.equals(key)) {
                return v;
            }
        }
        return null;
    }

    private static void showVoiceActions(final Context context, final SharedPreferences prefs,
                                         final PiperVoiceStore.Installed voice) {
        final List<CharSequence> actions = new ArrayList<>();
        final List<Runnable> handlers = new ArrayList<>();
        actions.add(context.getString(R.string.piper_action_test));
        handlers.add(() -> testVoice(context, voice));
        actions.add(context.getString(R.string.piper_action_speed, speedLabel(context, prefs, voice.key)));
        handlers.add(() -> chooseSpeed(context, prefs, voice));
        if (voice.config.numSpeakers > 1) {
            actions.add(context.getString(R.string.piper_action_speaker,
                    speakerLabel(context, voice.config, PiperVoiceStore.speakerId(prefs, voice.key))));
            handlers.add(() -> chooseSpeaker(context, prefs, voice));
        }
        final PiperDownloads.CatalogVoice compact = compactVersion(context, voice);
        if (compact != null) {
            final PiperModel loaded = PiperEngine.get().getLoaded(voice.key);
            final double speed = loaded == null ? 0 : loaded.decodeSpeed();
            final String size = Formatter.formatShortFileSize(context, compact.modelSize);
            // The speed this phone measured, when it is too slow to keep up.
            actions.add(speed > 0 && speed < SLOW_SPEED
                    ? context.getString(R.string.piper_action_compact_measured, size, speed)
                    : context.getString(R.string.piper_action_compact, size));
            handlers.add(() -> confirmDownload(context, compact));
        }
        actions.add(context.getString(R.string.piper_action_delete));
        handlers.add(() -> confirmDelete(context, prefs, voice));
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(voiceLabel(context, voice.config))
                .setItems(actions.toArray(new CharSequence[0]), (d, which) -> handlers.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /**
     * "Speaker 3 (P239)", or the voice's default speaker when none was chosen.
     * Names that are only ids (Common Voice speakers are 128-digit hashes)
     * are left out: TalkBack would read every digit.
     */
    private static String speakerLabel(Context context, PiperVoiceConfig config, int id) {
        final int speaker = id >= 0 && id < config.numSpeakers ? id : config.defaultSpeakerId;
        final String name = speaker < config.speakerNames.length ? config.speakerNames[speaker] : null;
        return isReadableName(name, speaker)
                ? context.getString(R.string.piper_speaker_named, speaker + 1,
                        PiperVoiceConfig.titleCase(name))
                : context.getString(R.string.piper_speaker_number, speaker + 1);
    }

    static boolean isReadableName(String name, int id) {
        return name != null && !name.equals(String.valueOf(id)) && name.length() <= 32
                && !name.matches("[0-9a-fA-F]{12,}");
    }

    /**
     * Multi-speaker voices (VCTK, LibriTTS, Arctic...) hold up to hundreds of
     * voices in one download; this picks one. Choosing plays it at once.
     */
    private static void chooseSpeaker(final Context context, final SharedPreferences prefs,
                                      final PiperVoiceStore.Installed voice) {
        final PiperVoiceConfig config = voice.config;
        final CharSequence[] rows = new CharSequence[config.numSpeakers];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = speakerLabel(context, config, i);
        }
        final int current = PiperVoiceStore.speakerId(prefs, voice.key);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.piper_choose_speaker, config.displayName()))
                .setSingleChoiceItems(rows, current >= 0 && current < rows.length
                        ? current : config.defaultSpeakerId, (d, which) -> {
                    prefs.edit().putString(PiperVoiceStore.PREF_SPEAKER_PREFIX + voice.key,
                            String.valueOf(which)).apply();
                    testVoice(context, voice);
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    private static String speedLabel(Context context, SharedPreferences prefs, String key) {
        final String own = prefs.getString(PiperVoiceStore.PREF_VOICE_SPEED_PREFIX + key, "");
        if (own.isEmpty()) {
            return context.getString(R.string.piper_speed_shared);
        }
        final String[] values = context.getResources().getStringArray(R.array.piper_speed_values);
        final String[] entries = context.getResources().getStringArray(R.array.piper_speed_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(own)) {
                return entries[i];
            }
        }
        return own + "%";
    }

    /** This voice's own speed over the shared natural-voice speed; choosing plays it. */
    private static void chooseSpeed(final Context context, final SharedPreferences prefs,
                                    final PiperVoiceStore.Installed voice) {
        final String[] values = context.getResources().getStringArray(R.array.piper_speed_values);
        final String[] entries = context.getResources().getStringArray(R.array.piper_speed_entries);
        final CharSequence[] rows = new CharSequence[values.length + 1];
        rows[0] = context.getString(R.string.piper_speed_shared);
        System.arraycopy(entries, 0, rows, 1, entries.length);
        final String pref = PiperVoiceStore.PREF_VOICE_SPEED_PREFIX + voice.key;
        final int current = Arrays.asList(values).indexOf(prefs.getString(pref, "")) + 1;
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.piper_choose_speed, voice.config.displayName()))
                .setSingleChoiceItems(rows, current, (d, which) -> {
                    if (which == 0) {
                        prefs.edit().remove(pref).apply();
                    } else {
                        prefs.edit().putString(pref, values[which - 1]).apply();
                    }
                    testVoice(context, voice);
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    private static void confirmDelete(final Context context, final SharedPreferences prefs,
                                      final PiperVoiceStore.Installed voice) {
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.piper_delete_confirm, voice.config.displayName()))
                .setPositiveButton(R.string.piper_action_delete, (d, w) -> new Thread(() -> {
                    PiperVoiceStore.delete(storage(context), prefs, voice.key);
                    PiperDownloads.broadcastChanged(context.getApplicationContext(), null, null);
                    toast(context.getApplicationContext(),
                            context.getString(R.string.piper_deleted, voice.config.displayName()));
                }, "piper-delete").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /**
     * Speaks the sample sentence with this voice directly (loaded here if
     * needed), whether or not it is the voice chosen for its language - so a
     * voice can be heard before it is picked.
     */
    private static void testVoice(final Context context, final PiperVoiceStore.Installed voice) {
        final Context app = context.getApplicationContext();
        if (PiperEngine.get().getLoaded(voice.key) == null) {
            Toast.makeText(app, app.getString(R.string.piper_test_loading, voice.config.displayName()),
                    Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            try {
                final PiperModel model = PiperEngine.get().loadNow(voice.key, voice.model(), voice.config);
                final SpeechSynthesis espeak = new SpeechSynthesis(storage(context), null);
                final String text = SpeechSynthesis.getSampleText(app,
                        new Locale(voice.config.languageFamily));
                final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                // As it will speak: this voice's speaker and speed, the style.
                final SharedPreferences prefs = TolerantPreferences.of(
                        PreferenceManager.getDefaultSharedPreferences(storage(context)));
                final PiperEngine.Params params = new PiperEngine.Params();
                params.speakerId = PiperVoiceStore.speakerId(prefs, voice.key);
                params.speed = PiperVoiceStore.speedFactor(prefs, voice.key);
                final float[] style = PiperVoiceStore.styleScales(prefs);
                params.noiseScale = style[0];
                params.noiseW = style[1];
                PiperEngine.get().synthesize(model, text, espeak::phonemizeForPiper,
                        SpeechSynthesis::sonicStretch, params,
                        new PiperEngine.Output() {
                            @Override
                            public void word(int position, int length, int frame) {
                            }

                            @Override
                            public boolean audio(byte[] data) {
                                pcm.write(data, 0, data.length);
                                return true;
                            }
                        });
                play(pcm.toByteArray(), model.config.sampleRate);
            } catch (Throwable t) {
                Log.w(TAG, "Voice test failed", t);
                toast(app, app.getString(R.string.piper_test_failed, voice.config.displayName()));
            } finally {
                // Testing works with natural voices off; it must not leave a
                // model (100+ MB) in memory that nothing will speak with.
                if (!PiperVoiceStore.isEnabled(PreferenceManager.getDefaultSharedPreferences(
                        storage(context)))) {
                    PiperEngine.get().unload(voice.key);
                }
            }
        }, "piper-test").start();
    }

    /**
     * The voice test playing now. Held: an AudioTrack nothing references can
     * be collected mid-sample and cut off (its finalizer releases it), and a
     * second test must stop the first instead of talking over it.
     */
    private static AudioTrack sTestTrack;

    private static synchronized void play(byte[] pcm, int sampleRate) {
        if (pcm.length == 0) {
            return;
        }
        if (sTestTrack != null) {
            sTestTrack.release();
            sTestTrack = null;
        }
        final AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length)
                .build();
        track.write(pcm, 0, pcm.length);
        track.setNotificationMarkerPosition(pcm.length / 2);
        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack t) {
                synchronized (PiperSettings.class) {
                    t.release();
                    if (sTestTrack == t) {
                        sTestTrack = null;
                    }
                }
            }

            @Override
            public void onPeriodicNotification(AudioTrack t) {
            }
        }, new Handler(Looper.getMainLooper()));
        sTestTrack = track;
        track.play();
    }
}
