package com.animeshahilya.espeakng.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.text.ScriptLanguages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-language speech settings ("language cards", Settings -> Mixed-language
 * text -> Speech by language): each language's speed, pitch and volume as a
 * percentage of the main settings, for eSpeak and natural voices alike,
 * whether the language is the request's own or a run inside another. Stored
 * as {@code lang_<ISO 639-2>_<field>} = "50".."200"; unset or empty is 100,
 * so nothing changes until a user sets one (NVDA parity).
 */
public final class LanguageCards {
    public static final String PREF_PREFIX = "lang_";
    public static final String SPEED = "speed";
    public static final String PITCH = "pitch";
    public static final String VOLUME = "volume";
    /** Earcons.CUE_*: what plays where speech switches into the language. */
    public static final String SWITCH_CUE = "cue";
    private static final String[] FIELDS = {SPEED, PITCH, VOLUME};
    private static final int MIN_PERCENT = 50;
    private static final int MAX_PERCENT = 200;
    private static final String KEY_CATEGORY = "category_language_cards";

    private LanguageCards() {
    }

    public static String key(String language, String field) {
        return PREF_PREFIX + language + "_" + field;
    }

    /** The language's setting as a factor of the main one: 1 when unset or unreadable. */
    public static float factor(SharedPreferences prefs, String language, String field) {
        if (prefs == null || language == null || language.isEmpty()) {
            return 1f;
        }
        int percent = 100;
        try {
            final String raw = prefs.getString(key(language, field), null);
            if (raw != null && !raw.isEmpty()) {
                percent = Integer.parseInt(raw);
            }
        } catch (ClassCastException | NumberFormatException e) {
            percent = 100; // a mistyped value never changes speech
        }
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent)) / 100f;
    }

    /** The cue where speech switches into this language: Earcons.CUE_NONE unless set. */
    public static String switchCue(SharedPreferences prefs, String language) {
        if (prefs == null || language == null || language.isEmpty()) {
            return com.animeshahilya.espeakng.text.Earcons.CUE_NONE;
        }
        try {
            final String v = prefs.getString(key(language, SWITCH_CUE), null);
            return v == null ? com.animeshahilya.espeakng.text.Earcons.CUE_NONE : v;
        } catch (ClassCastException e) {
            return com.animeshahilya.espeakng.text.Earcons.CUE_NONE;
        }
    }

    /**
     * Fills the "Speech by language" category: the languages the user reads
     * (the phone's, those with a natural voice or a script choice, those
     * with a card), each opening its page, and "Add language" for the rest.
     */
    public static void configure(Context context, PreferenceScreen screen, PreferenceFragmentCompat fragment,
                                 SharedPreferences prefs, List<Voice> voices) {
        final PreferenceCategory category = screen.findPreference(KEY_CATEGORY);
        if (category == null || !(fragment instanceof TtsSettingsActivity.PrefsEspeakFragment)) {
            return;
        }
        final TtsSettingsActivity.PrefsEspeakFragment f = (TtsSettingsActivity.PrefsEspeakFragment) fragment;
        final Map<String, String> all = new TreeMap<>();
        for (Voice v : voices) {
            final String lang = PiperVoiceStore.languageKey(v.locale);
            if (!lang.isEmpty()) {
                all.put(lang, PiperSettings.languageName(lang));
            }
        }
        final Map<String, String> used = new TreeMap<>();
        used.put(PiperVoiceStore.languageKey(Locale.getDefault()), "");
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            final String k = e.getKey();
            final String v = String.valueOf(e.getValue());
            if (k.startsWith(PiperVoiceStore.PREF_VOICE_PREFIX) && !v.isEmpty()) {
                used.put(k.substring(PiperVoiceStore.PREF_VOICE_PREFIX.length()), "");
            } else if (k.startsWith(PREF_PREFIX) && !v.isEmpty() && k.lastIndexOf('_') > PREF_PREFIX.length()) {
                used.put(k.substring(PREF_PREFIX.length(), k.lastIndexOf('_')), "");
            } else if (k.startsWith(ScriptLanguages.PREF_PREFIX) && !v.isEmpty()) {
                for (Voice voice : voices) {
                    if (voice.name.equals(v)) {
                        used.put(PiperVoiceStore.languageKey(voice.locale), "");
                    }
                }
            }
        }
        for (String lang : used.keySet()) {
            if (all.containsKey(lang)) {
                category.addPreference(row(context, f, prefs, lang, all.get(lang)));
            }
        }
        final ListPreference add = new ListPreference(context);
        add.setKey("action_language_card_add");
        add.setPersistent(false);
        add.setTitle(R.string.language_card_add);
        add.setDialogTitle(R.string.language_card_add);
        add.setIconSpaceReserved(false);
        add.setEntries(all.values().toArray(new CharSequence[0]));
        add.setEntryValues(all.keySet().toArray(new CharSequence[0]));
        add.setOnPreferenceChangeListener((p, value) -> {
            final String lang = String.valueOf(value);
            open(context, f, prefs, lang, all.get(lang));
            return false;
        });
        category.addPreference(add);
    }

    private static Preference row(Context context, TtsSettingsActivity.PrefsEspeakFragment f,
                                  SharedPreferences prefs, String lang, String name) {
        final Preference row = new Preference(context);
        row.setKey("language_card_" + lang);
        row.setPersistent(false);
        row.setTitle(name);
        row.setIconSpaceReserved(false);
        row.setSummaryProvider(p -> summary(context, prefs, lang));
        row.setOnPreferenceClickListener(p -> {
            open(context, f, prefs, lang, name);
            return true;
        });
        return row;
    }

    private static CharSequence summary(Context context, SharedPreferences prefs, String lang) {
        final String cue = switchCue(prefs, lang);
        final boolean sameProsody = factor(prefs, lang, SPEED) == 1f && factor(prefs, lang, PITCH) == 1f
                && factor(prefs, lang, VOLUME) == 1f;
        if (sameProsody && cue.isEmpty()) {
            return context.getString(R.string.language_card_summary_same);
        }
        final String prosody = sameProsody ? context.getString(R.string.language_card_summary_same)
                : context.getString(R.string.language_card_summary,
                        percent(context, prefs, lang, SPEED), percent(context, prefs, lang, PITCH),
                        percent(context, prefs, lang, VOLUME));
        return cue.isEmpty() ? prosody : context.getString(R.string.language_card_summary_cue, prosody,
                context.getString(com.animeshahilya.espeakng.text.Earcons.CUE_TONE.equals(cue)
                        ? R.string.language_card_cue_tone : R.string.language_card_cue_pause));
    }

    private static String percent(Context context, SharedPreferences prefs, String lang, String field) {
        return context.getString(R.string.language_card_percent, Math.round(factor(prefs, lang, field) * 100));
    }

    /** The language's page: speed, pitch and volume, each "same as main" or 50-200%. */
    private static void open(Context context, TtsSettingsActivity.PrefsEspeakFragment f,
                             SharedPreferences prefs, String lang, String name) {
        final PreferenceScreen page = f.getPreferenceManager().createPreferenceScreen(context);
        page.setTitle(name);
        final int[] titles = {R.string.language_card_speed, R.string.language_card_pitch,
                R.string.language_card_volume};
        final int steps = (MAX_PERCENT - MIN_PERCENT) / 10;
        final CharSequence[] entries = new CharSequence[steps + 1];
        final CharSequence[] values = new CharSequence[steps + 1];
        entries[0] = context.getString(R.string.language_card_same);
        values[0] = "";
        for (int i = 0, p = MIN_PERCENT; p <= MAX_PERCENT; p += 10) {
            if (p == 100) {
                continue; // that is "same as main"
            }
            i++;
            entries[i] = context.getString(R.string.language_card_percent, p);
            values[i] = String.valueOf(p);
        }
        for (int i = 0; i < FIELDS.length; i++) {
            final ListPreference setting = new ListPreference(context);
            setting.setKey(key(lang, FIELDS[i]));
            setting.setTitle(titles[i]);
            setting.setDialogTitle(titles[i]);
            setting.setEntries(entries);
            setting.setEntryValues(values);
            setting.setDefaultValue("");
            setting.setIconSpaceReserved(false);
            setting.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            page.addPreference(setting);
        }
        final ListPreference cue = new ListPreference(context);
        cue.setKey(key(lang, SWITCH_CUE));
        cue.setTitle(R.string.language_card_cue);
        cue.setDialogTitle(R.string.language_card_cue);
        cue.setEntries(new CharSequence[] {context.getString(R.string.language_card_cue_none),
                context.getString(R.string.language_card_cue_pause),
                context.getString(R.string.language_card_cue_tone)});
        cue.setEntryValues(new CharSequence[] {com.animeshahilya.espeakng.text.Earcons.CUE_NONE,
                com.animeshahilya.espeakng.text.Earcons.CUE_PAUSE,
                com.animeshahilya.espeakng.text.Earcons.CUE_TONE});
        cue.setDefaultValue(com.animeshahilya.espeakng.text.Earcons.CUE_NONE);
        cue.setIconSpaceReserved(false);
        cue.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        page.addPreference(cue);
        f.pushScreen(page);
    }
}
