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
import java.util.WeakHashMap;

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
    private static final String KEY_SNAPDRAGON = "action_piper_snapdragon";
    /** Where both builds are published; the Snapdragon one is espeak-snapdragon-*.apk. */
    static final String SNAPDRAGON_RELEASES = "https://github.com/animeshahilya/espeak-ng/releases/latest";

    /**
     * A Snapdragon with a modern NPU, running the standard build. Only the
     * 8-series from 8 Gen 1 (SM8450, 2022) on: QNN's HTP libraries start at
     * that generation's NPU, and older or mid-range chips are unmeasured. The
     * Snapdragon build itself still checks the speed (PiperModel.MIN_NPU_SPEED)
     * and keeps the CPU where the NPU isn't clearly faster.
     */
    static boolean offersSnapdragonBuild() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && "QTI".equalsIgnoreCase(android.os.Build.SOC_MANUFACTURER)
                && hasModernNpu(android.os.Build.SOC_MODEL)
                && !PiperModel.hasNpuRuntime();
    }

    /** SM8450 and later 8-series ("SM8450", "SM8550", "SM8650", "SM8750", ...). */
    static boolean hasModernNpu(String socModel) {
        if (socModel == null || !socModel.matches("SM8\\d{3}.*")) {
            return false;
        }
        return Integer.parseInt(socModel.substring(2, 6)) >= 8450;
    }
    private static final long PROGRESS_POLL_MS = 1500;
    private static final Handler POLL_HANDLER = new Handler(Looper.getMainLooper());
    /** The visible screen's progress poll; null while the screen is paused. */
    private static Runnable sPoll;

    /** A download just started from a page: tick its progress (the poll stops when idle). */
    private static void startPolling() {
        if (sPoll != null) {
            POLL_HANDLER.post(sPoll);
        }
    }

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
        if (fragment instanceof TtsSettingsActivity.PrefsEspeakFragment) {
            final TtsSettingsActivity.PrefsEspeakFragment f = (TtsSettingsActivity.PrefsEspeakFragment) fragment;
            download.setOnPreferenceClickListener(p -> {
                openDownloads(f, prefs);
                return true;
            });
            manage.setOnPreferenceClickListener(p -> {
                openManage(f, prefs);
                return true;
            });
        }
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
        final Preference snapdragon = screen.findPreference(KEY_SNAPDRAGON);
        if (snapdragon != null) {
            // The standard app on a Snapdragon phone: point to the faster build
            // (same package and key, so it installs over this one, voices kept).
            snapdragon.setVisible(offersSnapdragonBuild());
            snapdragon.setOnPreferenceClickListener(p -> {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(SNAPDRAGON_RELEASES)));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(context, SNAPDRAGON_RELEASES, Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }
        final Preference acceleration = screen.findPreference(PiperVoiceStore.PREF_ACCELERATION);
        if (acceleration != null && PiperModel.hasNpuRuntime()) {
            acceleration.setVisible(false); // this build uses the NPU itself; its runtime has no NNAPI
        }
        if (acceleration != null) {
            acceleration.setOnPreferenceChangeListener((p, value) -> {
                PiperEngine.get().setAcceleration(Boolean.TRUE.equals(value));
                return true;
            });
        }
        refresh(context, screen, prefs);
        if (row instanceof PreferenceScreen) {
            // Back from a voice page: a voice may have been given a language there.
            PAGES.put((PreferenceScreen) row, new Runnable[] {() -> refresh(context, screen, prefs), null});
        }

        // Live while the screen is visible: voices finishing their download
        // appear (and are announced) without leaving the page, and download
        // progress ticks along in the Download row's summary.
        final Handler handler = POLL_HANDLER;
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                handler.removeCallbacks(this);
                final boolean running = updateProgress(context, screen);
                tick(fragment);
                if (running) {
                    handler.postDelayed(this, PROGRESS_POLL_MS);
                }
            }
        };
        final BroadcastReceiver changed = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                refresh(context, screen, prefs);
                if (fragment != null && fragment.isAdded()) {
                    onShown(fragment.getPreferenceScreen()); // the page showing, if it is one of ours
                }
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
                EspeakApp.runAsync(() -> {
                    PiperDownloads.reconcile(context.getApplicationContext(), storage(context));
                    handler.post(() -> refresh(context, screen, prefs));
                });
                sPoll = poll;
                handler.post(poll);
            }

            @Override
            public void onPause(@NonNull LifecycleOwner owner) {
                handler.removeCallbacks(poll);
                sPoll = null;
                releasePlayer(SAMPLE);
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
            final long bytes = PiperVoiceStore.diskBytes(storage(context));
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
            region = new Locale.Builder().setRegion(region.substring(us + 1)).build().getDisplayCountry();
        }
        return context.getString(R.string.piper_voice_entry, config.displayName(),
                region == null ? "" : region, qualityLabel(context, config.quality));
    }

    // ---- Pages: Download -> language -> voice; Downloaded voices -> voice ----
    //
    // Pages on the settings screen's own stack, not chained dialogs: each opens
    // one level below the last, so Back returns exactly one step (a dialog
    // chain closed itself on every tap, and Back or Cancel then dropped the
    // user several levels up). Dialogs remain only to confirm or pick a value.

    private static final String KEY_PROGRESS = "piper_page_progress";

    /** {build, tick} per page: rebuilt when shown again, ticked while downloading. */
    private static final Map<PreferenceScreen, Runnable[]> PAGES = new WeakHashMap<>();
    /** The catalog the Download page loaded; null until then. */
    private static List<PiperDownloads.CatalogVoice> sCatalog;
    /** The recorded sample playing, so leaving or a second sample stops it. */
    private static final MediaPlayer[] SAMPLE = {null};

    /** A page shown again (Back from the page below it): rebuild what changed. */
    static void onShown(PreferenceScreen screen) {
        final Runnable[] page = PAGES.get(screen);
        if (page != null) {
            page[0].run();
        }
    }

    /** Progress on the page showing, in place (rebuilding would move TalkBack focus). */
    private static void tick(PreferenceFragmentCompat fragment) {
        final Runnable[] page = fragment != null ? PAGES.get(fragment.getPreferenceScreen()) : null;
        if (page != null && page[1] != null) {
            page[1].run();
        }
    }

    private static PreferenceScreen newPage(TtsSettingsActivity.PrefsEspeakFragment f, CharSequence title,
                                            java.util.function.Consumer<PreferenceScreen> build,
                                            java.util.function.Consumer<PreferenceScreen> tick) {
        final PreferenceScreen screen = f.getPreferenceManager().createPreferenceScreen(f.requireContext());
        screen.setTitle(title);
        PAGES.put(screen, new Runnable[] {() -> {
            if (f.isAdded()) {
                build.accept(screen);
            }
        }, tick == null ? null : () -> tick.accept(screen)});
        return screen;
    }

    private static void open(TtsSettingsActivity.PrefsEspeakFragment f, PreferenceScreen screen) {
        releasePlayer(SAMPLE);
        onShown(screen);
        f.pushScreen(screen);
    }

    /** A plain row; null {@code click} makes it text only. */
    private static Preference row(Context context, String key, CharSequence title, CharSequence summary,
                                  Runnable click) {
        final Preference p = new Preference(context);
        p.setKey(key);
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        p.setIconSpaceReserved(false);
        p.setSingleLineTitle(false);
        if (click == null) {
            p.setSelectable(false);
        } else {
            p.setOnPreferenceClickListener(x -> {
                click.run();
                return true;
            });
        }
        return p;
    }

    private static void openDownloads(final TtsSettingsActivity.PrefsEspeakFragment f,
                                      final SharedPreferences prefs) {
        final Context context = f.requireContext();
        final PreferenceScreen screen = newPage(f, context.getString(R.string.setting_piper_download),
                s -> {
                    if (sCatalog == null) {
                        loadCatalog(f, s, prefs, false);
                    } else {
                        buildLanguages(f, s, prefs);
                    }
                }, null);
        open(f, screen);
    }

    /** Loads the catalog into the Download page: a loading row, then the languages. */
    private static void loadCatalog(final TtsSettingsActivity.PrefsEspeakFragment f,
                                    final PreferenceScreen screen, final SharedPreferences prefs,
                                    final boolean refresh) {
        final Context context = f.requireContext();
        screen.removeAll();
        screen.addPreference(row(context, null, context.getString(R.string.piper_loading_catalog), null, null));
        EspeakApp.runAsync(() -> {
            List<PiperDownloads.CatalogVoice> catalog = null;
            try {
                catalog = PiperDownloads.loadCatalog(storage(context), refresh);
            } catch (Exception e) {
                Log.w(TAG, "Catalog load failed", e);
            }
            final List<PiperDownloads.CatalogVoice> result = catalog;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!f.isAdded()) {
                    return;
                }
                if (result == null || result.isEmpty()) {
                    screen.removeAll();
                    screen.addPreference(row(context, null, context.getString(R.string.piper_catalog_failed),
                            context.getString(R.string.piper_tap_to_retry),
                            () -> loadCatalog(f, screen, prefs, true)));
                    return;
                }
                sCatalog = result;
                buildLanguages(f, screen, prefs);
            });
        });
    }

    private static void buildLanguages(final TtsSettingsActivity.PrefsEspeakFragment f,
                                       final PreferenceScreen screen, final SharedPreferences prefs) {
        final Context context = f.requireContext();
        final Context storage = storage(context);
        final Map<String, List<PiperDownloads.CatalogVoice>> byLanguage = new LinkedHashMap<>();
        for (PiperDownloads.CatalogVoice v : sCatalog) {
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
        screen.removeAll();
        for (String key : keys) {
            final List<PiperDownloads.CatalogVoice> voices = byLanguage.get(key);
            final PiperDownloads.CatalogVoice first = voices.get(0);
            final String name = languageName(first.family);
            final String title = name.equalsIgnoreCase(first.nameNative) ? name
                    : context.getString(R.string.piper_language_row, name, first.nameNative);
            int have = 0;
            for (PiperDownloads.CatalogVoice v : voices) {
                if (PiperVoiceStore.find(storage, v.key) != null) {
                    have++;
                }
            }
            String summary = context.getResources().getQuantityString(
                    R.plurals.piper_voice_count, voices.size(), voices.size());
            if (have > 0) {
                summary = context.getString(R.string.piper_language_have, summary, have);
            }
            screen.addPreference(row(context, "piper_lang_" + key, title, summary,
                    () -> openLanguage(f, prefs, voices)));
        }
        screen.addPreference(row(context, null, context.getString(R.string.piper_refresh),
                context.getString(R.string.piper_refresh_summary), () -> loadCatalog(f, screen, prefs, true)));
    }

    private static void openLanguage(final TtsSettingsActivity.PrefsEspeakFragment f,
                                     final SharedPreferences prefs,
                                     final List<PiperDownloads.CatalogVoice> voices) {
        final Context context = f.requireContext();
        final List<PiperDownloads.CatalogVoice> ordered =
                PiperDownloads.orderCatalogForPhone(voices, PiperDevice.heavyFit());
        open(f, newPage(f, context.getString(R.string.piper_choose_voice, languageName(voices.get(0).family)),
                s -> {
                    s.removeAll();
                    // Grouped by country under headings, the phone's country
                    // first: English alone has 40 voices.
                    final Map<String, List<PiperDownloads.CatalogVoice>> byRegion = new LinkedHashMap<>();
                    for (PiperDownloads.CatalogVoice v : ordered) {
                        final String region = v.region != null ? v.region : "";
                        List<PiperDownloads.CatalogVoice> list = byRegion.get(region);
                        if (list == null) {
                            list = new ArrayList<>();
                            byRegion.put(region, list);
                        }
                        list.add(v);
                    }
                    final List<String> regions = new ArrayList<>(byRegion.keySet());
                    final String home = Locale.getDefault().getCountry();
                    Collections.sort(regions, (a, b) -> a.equals(home) != b.equals(home)
                            ? (a.equals(home) ? -1 : 1)
                            : regionName(byRegion.get(a).get(0)).compareToIgnoreCase(regionName(byRegion.get(b).get(0))));
                    final boolean grouped = regions.size() > 1;
                    for (String region : regions) {
                        final List<PiperDownloads.CatalogVoice> list = byRegion.get(region);
                        androidx.preference.PreferenceGroup group = s;
                        if (grouped) {
                            final PreferenceCategory heading = new PreferenceCategory(context);
                            heading.setTitle(regionName(list.get(0)));
                            heading.setIconSpaceReserved(false);
                            s.addPreference(heading);
                            group = heading;
                        }
                        for (PiperDownloads.CatalogVoice v : list) {
                            group.addPreference(row(context, v.key, v.displayName(),
                                    voiceSummary(context, v, !grouped), () -> openVoice(f, prefs, v, ordered)));
                        }
                    }
                },
                s -> {
                    for (PiperDownloads.CatalogVoice v : ordered) {
                        final Preference p = s.findPreference(v.key);
                        if (p != null) {
                            p.setSummary(voiceSummary(context, v, p.getParent() == s));
                        }
                    }
                }));
    }

    /** "India" in the phone's language, from the voice's region code. */
    private static String regionName(PiperDownloads.CatalogVoice v) {
        final String name = v.region == null || v.region.isEmpty() ? null
                : new Locale.Builder().setRegion(v.region).build().getDisplayCountry();
        return name == null || name.isEmpty() || name.equals(v.region) ? v.country : name;
    }

    /** "India, Standard, 63 MB, 2 speakers, downloaded" for a catalog voice; no country under its heading. */
    private static String voiceSummary(Context context, PiperDownloads.CatalogVoice v, boolean country) {
        final Context storage = storage(context);
        String s = context.getString(R.string.piper_catalog_voice_row, catalogQualityLabel(context, v),
                Formatter.formatShortFileSize(context, v.modelSize));
        if (country) {
            s = regionName(v) + ", " + s;
        }
        if (v.numSpeakers > 1) {
            s += ", " + context.getResources().getQuantityString(
                    R.plurals.piper_speakers, v.numSpeakers, v.numSpeakers);
        }
        if (PiperVoiceStore.find(storage, v.key) != null) {
            return context.getString(R.string.piper_catalog_voice_installed, s);
        }
        final String progress = progressText(context, v.key);
        return progress != null ? s + ", " + progress : s;
    }

    /** "Downloading, 45%" while this voice downloads, else null. */
    private static String progressText(Context context, String key) {
        if (!PiperDownloads.pendingKeys(storage(context)).contains(key)) {
            return null;
        }
        final PiperDownloads.Progress p = PiperDownloads.progress(context.getApplicationContext(),
                storage(context), key);
        return p == null || p.percent() <= 0 ? context.getString(R.string.piper_download_waiting)
                : context.getString(R.string.piper_download_percent, p.percent());
    }

    /** A catalog voice: about, sample, download (or its installed actions). */
    private static void openVoice(final TtsSettingsActivity.PrefsEspeakFragment f,
                                  final SharedPreferences prefs, final PiperDownloads.CatalogVoice v,
                                  final List<PiperDownloads.CatalogVoice> siblings) {
        final Context context = f.requireContext();
        final Context storage = storage(context);
        open(f, newPage(f, v.displayName(), s -> {
            s.removeAll();
            final PiperVoiceStore.Installed installed = PiperVoiceStore.find(storage, v.key);
            if (installed != null) {
                addInstalledRows(f, s, prefs, installed);
                return;
            }
            s.addPreference(row(context, null, context.getString(R.string.piper_voice_about_title,
                    languageName(v.family), regionName(v)), aboutText(context, v), null));
            addSampleRow(context, s, v);
            if (PiperDownloads.pendingKeys(storage).contains(v.key)) {
                s.addPreference(row(context, KEY_PROGRESS, progressText(context, v.key),
                        context.getString(R.string.piper_action_cancel_download), () -> new Thread(() -> {
                            PiperDownloads.cancel(context.getApplicationContext(), storage, v.key);
                            new Handler(Looper.getMainLooper()).post(() -> onShown(s));
                        }, "piper-cancel").start()));
                return;
            }
            final PiperDownloads.CatalogVoice compact =
                    siblings == null ? null : compactInstead(storage, siblings, v);
            if (compact != null) {
                // Slow phone, heavy Standard: its Compact twin first, one level down.
                s.addPreference(row(context, null, context.getString(R.string.piper_compact_choice_title),
                        context.getString(R.string.piper_compact_row_summary,
                                Formatter.formatShortFileSize(context, compact.modelSize)),
                        () -> openVoice(f, prefs, compact, siblings)));
            }
            s.addPreference(row(context, null, context.getString(R.string.piper_download_row,
                    Formatter.formatShortFileSize(context, v.modelSize)),
                    context.getString(R.string.piper_download_row_summary),
                    () -> startDownload(context, v, () -> onShown(s))));
        }, s -> {
            final Preference p = s.findPreference(KEY_PROGRESS);
            if (p == null) {
                return;
            }
            final String progress = progressText(context, v.key);
            if (progress != null) {
                p.setTitle(progress);
            } else {
                onShown(s); // finished or failed: show what the voice is now
            }
        }));
    }

    /** Language, country, quality and size; who made a community voice and its license. */
    private static String aboutText(Context context, PiperDownloads.CatalogVoice v) {
        String text = context.getString(R.string.piper_voice_about, catalogQualityLabel(context, v),
                Formatter.formatShortFileSize(context, v.modelSize));
        if (v.numSpeakers > 1) {
            text += " " + context.getResources().getQuantityString(
                    R.plurals.piper_speakers_sentence, v.numSpeakers, v.numSpeakers);
        }
        if (v.source != null) {
            text += "\n" + context.getString(R.string.piper_voice_source, v.source,
                    v.license != null ? v.license : "?");
        }
        return text;
    }

    private static void addSampleRow(Context context, PreferenceScreen s, PiperDownloads.CatalogVoice v) {
        if (v.sampleUrl() != null) {
            s.addPreference(row(context, null, context.getString(R.string.piper_play_sample),
                    context.getString(R.string.piper_play_sample_summary),
                    () -> playSample(context, v, SAMPLE)));
        }
    }

    private static void openManage(final TtsSettingsActivity.PrefsEspeakFragment f,
                                   final SharedPreferences prefs) {
        final Context context = f.requireContext();
        open(f, newPage(f, context.getString(R.string.piper_manage_title), s -> {
            s.removeAll();
            final List<PiperVoiceStore.Installed> installed = PiperVoiceStore.list(storage(context));
            if (installed.isEmpty()) {
                s.addPreference(row(context, null, context.getString(R.string.piper_manage_empty),
                        context.getString(R.string.setting_piper_download),
                        () -> openDownloads(f, prefs)));
                return;
            }
            for (PiperVoiceStore.Installed v : installed) {
                s.addPreference(row(context, v.key, voiceLabel(context, v.config),
                        context.getString(R.string.piper_manage_row, languageName(v.config.languageFamily),
                                Formatter.formatShortFileSize(context, v.sizeBytes()),
                                speaksText(context, prefs, v)),
                        () -> openInstalled(f, prefs, v.key)));
            }
        }, null));
    }

    private static void openInstalled(final TtsSettingsActivity.PrefsEspeakFragment f,
                                      final SharedPreferences prefs, final String key) {
        final Context context = f.requireContext();
        final PiperVoiceStore.Installed voice = PiperVoiceStore.find(storage(context), key);
        if (voice == null) {
            return;
        }
        open(f, newPage(f, voice.config.displayName(), s -> {
            s.removeAll();
            final PiperVoiceStore.Installed now = PiperVoiceStore.find(storage(context), key);
            if (now != null) {
                addInstalledRows(f, s, prefs, now);
            }
        }, null));
    }

    /** "Speaks Hindi" or "Not speaking any language" for an installed voice. */
    private static String speaksText(Context context, SharedPreferences prefs, PiperVoiceStore.Installed v) {
        return v.key.equals(PiperVoiceStore.assignedKey(prefs, v.languageKey()))
                ? context.getString(R.string.piper_speaks, languageName(v.config.languageFamily))
                : context.getString(R.string.piper_speaks_none);
    }

    /** Below this many seconds of audio per second of decoding, long text pauses. */
    private static final double SLOW_SPEED = 2.0;

    /** Test, use, speed, speaker, Compact and delete for an installed voice. */
    private static void addInstalledRows(final TtsSettingsActivity.PrefsEspeakFragment f,
                                         final PreferenceScreen s, final SharedPreferences prefs,
                                         final PiperVoiceStore.Installed voice) {
        final Context context = f.requireContext();
        final String language = languageName(voice.config.languageFamily);
        s.addPreference(row(context, null, voiceLabel(context, voice.config),
                context.getString(R.string.piper_manage_row, language,
                        Formatter.formatShortFileSize(context, voice.sizeBytes()),
                        speaksText(context, prefs, voice)), null));
        s.addPreference(row(context, null, context.getString(R.string.piper_action_test),
                context.getString(R.string.piper_action_test_summary), () -> testVoice(context, voice)));
        if (!voice.key.equals(PiperVoiceStore.assignedKey(prefs, voice.languageKey()))) {
            s.addPreference(row(context, null, context.getString(R.string.piper_use_for, language),
                    context.getString(R.string.piper_use_for_summary), () -> {
                        PiperVoiceStore.assign(prefs, voice.languageKey(), voice.key);
                        PiperEngine.get().preload(voice.key, voice.model(), voice.config);
                        f.announce(context.getString(R.string.piper_speaks, language));
                        onShown(s);
                    }));
        }
        s.addPreference(row(context, null, context.getString(R.string.setting_piper_speed),
                speedLabel(context, prefs, voice.key), () -> chooseSpeed(context, prefs, voice, () -> onShown(s))));
        if (voice.config.numSpeakers > 1) {
            s.addPreference(row(context, null, context.getString(R.string.piper_speaker_title),
                    speakerLabel(context, voice.config, PiperVoiceStore.speakerId(prefs, voice.key)),
                    () -> chooseSpeaker(context, prefs, voice, () -> onShown(s))));
        }
        final PiperDownloads.CatalogVoice compact = compactVersion(context, voice);
        if (compact != null) {
            final String size = Formatter.formatShortFileSize(context, compact.modelSize);
            final boolean assignedHere = voice.key.equals(
                    PiperVoiceStore.assignedKey(prefs, voice.languageKey()));
            if (assignedHere && PiperDevice.heavyFit() == PiperDevice.Fit.SLOW) {
                // Slow phone speaking this Standard voice: one tap swaps it.
                s.addPreference(row(context, null, context.getString(R.string.piper_action_switch_compact, size),
                        null, () -> confirmSwap(context, voice, compact)));
            } else {
                final PiperModel loaded = PiperEngine.get().getLoaded(voice.key);
                final double speed = loaded == null ? 0 : loaded.decodeSpeed();
                // The speed this phone measured, when it is too slow to keep up.
                s.addPreference(row(context, null, context.getString(R.string.piper_action_compact, size),
                        speed > 0 && speed < SLOW_SPEED
                                ? context.getString(R.string.piper_action_compact_measured, speed) : null,
                        () -> openVoice(f, prefs, compact, null)));
            }
        }
        s.addPreference(row(context, null, context.getString(R.string.piper_action_delete),
                null, () -> confirmDelete(f, prefs, voice)));
    }

    /**
     * The Compact twin to offer instead of a heavy Standard voice on a slow
     * phone: present, and neither installed nor downloading. Null otherwise.
     */
    private static PiperDownloads.CatalogVoice compactInstead(Context storage,
            List<PiperDownloads.CatalogVoice> ordered, PiperDownloads.CatalogVoice v) {
        if (!PiperDownloads.preferCompact(v.heavy, v.quality, PiperDevice.heavyFit())) {
            return null;
        }
        final String twin = PiperDownloads.compactKey(v.key);
        for (PiperDownloads.CatalogVoice c : ordered) {
            if (c.key.equals(twin) && PiperVoiceStore.find(storage, twin) == null
                    && !PiperDownloads.pendingKeys(storage).contains(twin)) {
                return c;
            }
        }
        return null;
    }

    /** Streams the voice's recorded sample. Tapping again while playing stops playback. */
    private static void playSample(Context context, PiperDownloads.CatalogVoice v, MediaPlayer[] player) {
        if (player[0] != null) {
            try {
                if (player[0].isPlaying()) {
                    releasePlayer(player);
                    toast(context.getApplicationContext(), context.getString(R.string.test_voice_stopped));
                    return;
                }
            } catch (Exception ignored) {
            }
            releasePlayer(player);
        }
        final String url = v.sampleUrl();
        if (url == null) {
            return;
        }
        toast(context.getApplicationContext(), context.getString(R.string.test_voice_playing));
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> releasePlayer(player));
            mp.setOnErrorListener((m, what, extra) -> {
                releasePlayer(player);
                Toast.makeText(context, R.string.piper_sample_failed, Toast.LENGTH_SHORT).show();
                return true;
            });
            mp.prepareAsync();
            player[0] = mp;
        } catch (Exception e) {
            Log.w(TAG, "Sample playback failed", e);
            releasePlayer(player);
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

    /** @param then run on the main thread once the download is under way (or installed) */
    private static void startDownload(final Context context, final PiperDownloads.CatalogVoice v,
                                      final Runnable then) {
        final Context app = context.getApplicationContext();
        EspeakApp.runAsync(() -> {
            int message;
            boolean installed = false;
            try {
                installed = PiperDownloads.start(app, storage(context), v) < 0;
                message = 0;
            } catch (PiperDownloads.UnsupportedVoiceException e) {
                message = R.string.piper_download_unsupported;
            } catch (Exception e) {
                Log.w(TAG, "Download start failed", e);
                message = R.string.piper_download_start_failed;
            }
            if (message != 0) {
                // Nothing is downloading: drop the staging and any swap record.
                PiperDownloads.cancel(app, storage(context), v.key);
            }
            if (message == 0 && installed) {
                toast(app, app.getString(R.string.piper_download_installed, v.displayName()));
            } else {
                toast(app, message == 0
                        ? app.getString(R.string.piper_download_started, v.displayName())
                        : app.getString(message));
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                startPolling();
                if (then != null) {
                    then.run();
                }
            });
        });
    }

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

    /**
     * One-tap Compact swap: downloads Compact, makes it speak the language,
     * and removes the Standard copy to free the space (done on install).
     */
    private static void confirmSwap(final Context context,
            final PiperVoiceStore.Installed voice, final PiperDownloads.CatalogVoice compact) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.piper_confirm_switch_title,
                        voice.config.displayName()))
                .setMessage(context.getString(R.string.piper_confirm_switch_message,
                        Formatter.formatShortFileSize(context, compact.modelSize),
                        languageName(voice.config.languageFamily),
                        Formatter.formatShortFileSize(context, voice.sizeBytes())))
                .setPositiveButton(R.string.piper_confirm_download, (d, w) -> {
                    PiperDownloads.requestSwap(storage(context), compact.key, voice.key);
                    startDownload(context, compact, null);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
                                      final PiperVoiceStore.Installed voice, final Runnable done) {
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
                .setOnDismissListener(d -> done.run())
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
                                    final PiperVoiceStore.Installed voice, final Runnable done) {
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
                .setOnDismissListener(d -> done.run())
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /** Deletes after a confirm, then goes back one page: this voice's page is gone. */
    private static void confirmDelete(final TtsSettingsActivity.PrefsEspeakFragment f,
                                      final SharedPreferences prefs, final PiperVoiceStore.Installed voice) {
        final Context context = f.requireContext();
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.piper_delete_confirm, voice.config.displayName()))
                .setPositiveButton(R.string.piper_action_delete, (d, w) -> EspeakApp.runAsync(() -> {
                    PiperVoiceStore.delete(storage(context), prefs, voice.key);
                    PiperDownloads.broadcastChanged(context.getApplicationContext(), null, null);
                    final String msg = context.getString(R.string.piper_deleted, voice.config.displayName());
                    toast(context.getApplicationContext(), msg);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (f.isAdded()) {
                            f.announce(msg);
                            f.popToParent();
                        }
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TtsSettingsActivity.markAlertTitleHeading(dialog);
    }

    /**
     * Speaks the sample sentence with this voice directly (loaded here if
     * needed), whether or not it is the voice chosen for its language - so a
     * voice can be heard before it is picked. Tapping again while playing stops
     * playback.
     */
    private static void testVoice(final Context context, final PiperVoiceStore.Installed voice) {
        final Context app = context.getApplicationContext();
        synchronized (PiperSettings.class) {
            if (sTestTrack != null && sTestTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                stopPlayback();
                toast(app, app.getString(R.string.test_voice_stopped));
                return;
            }
        }
        if (PiperEngine.get().getLoaded(voice.key) == null) {
            Toast.makeText(app, app.getString(R.string.piper_test_loading, voice.config.displayName()),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(app, app.getString(R.string.test_voice_playing), Toast.LENGTH_SHORT).show();
        }
        EspeakApp.runAsync(() -> {
            try {
                final PiperModel model = PiperEngine.get().loadNow(voice.key, voice.model(), voice.config);
                final SpeechSynthesis espeak = new SpeechSynthesis(storage(context), null);
                final String text = SpeechSynthesis.getSampleText(app,
                        new Locale.Builder().setLanguage(voice.config.languageFamily).build());
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
        });
    }

    /**
     * The voice test playing now. Held: an AudioTrack nothing references can
     * be collected mid-sample and cut off (its finalizer releases it), and a
     * second test must stop the first instead of talking over it.
     */
    private static AudioTrack sTestTrack;

    /**
     * Stops any currently playing sample track and releases audio resources.
     */
    public static synchronized void stopPlayback() {
        if (sTestTrack != null) {
            try {
                if (sTestTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    sTestTrack.stop();
                }
                sTestTrack.release();
            } catch (Exception ignored) {
            }
            sTestTrack = null;
        }
    }

    private static synchronized void play(byte[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) {
            return;
        }
        stopPlayback();
        try {
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
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return;
            }
            track.write(pcm, 0, pcm.length);
            track.setNotificationMarkerPosition(pcm.length / 2);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(AudioTrack t) {
                    synchronized (PiperSettings.class) {
                        try {
                            t.release();
                        } catch (Exception ignored) {
                        }
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
        } catch (Exception e) {
            Log.w("PiperSettings", "Sample playback failed", e);
        }
    }
}
