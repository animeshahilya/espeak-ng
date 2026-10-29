package com.animeshahilya.espeakng.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.Voice;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.text.ScriptLanguages;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
    /** RunPlan.MIXED_* or a natural voice key: who reads the language inside other text. */
    public static final String WHEN_MIXED = "mixed";
    /** VoiceSettings.SWITCHING_*: how much of the language switches voice; "" = the main setting. */
    public static final String SWITCH_AT = "switch";
    /** VoiceSettings.NUMBERS_* ({@link #NUMBERS_AUTO} for automatic) in its text; "" = the main setting. */
    public static final String NUMBERS = "numbers";
    /** Stored for an explicit "automatic", which is "" in the main setting. */
    public static final String NUMBERS_AUTO = "auto";
    /** VoiceSettings.EMOJI_* ({@link #EMOJI_VOICE} for the voice's language) in its text; "" = the main setting. */
    public static final String EMOJI = "emoji";
    /** Stored for an explicit "in the voice's language", which is "" in the main setting. */
    public static final String EMOJI_VOICE = "voice";
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
        return percentFactor(prefs, key(language, field));
    }

    /** A stored "50"-"200" percent as a factor: 1 when unset or unreadable (shared with {@link AppCards}). */
    static float percentFactor(SharedPreferences prefs, String key) {
        int percent = 100;
        try {
            final String raw = prefs.getString(key, null);
            if (raw != null && !raw.isEmpty()) {
                percent = Integer.parseInt(raw);
            }
        } catch (ClassCastException | NumberFormatException e) {
            percent = 100; // a mistyped value never changes speech
        }
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent)) / 100f;
    }

    /** Who reads this language inside other text: RunPlan.MIXED_DEFAULT unless set. */
    public static String whenMixed(SharedPreferences prefs, String language) {
        return string(prefs, language, WHEN_MIXED);
    }

    /** The language's switching sensitivity: "" for the main setting. */
    public static String switchAt(SharedPreferences prefs, String language) {
        return string(prefs, language, SWITCH_AT);
    }

    /** The language's emoji-name choice: "" for the main setting. */
    public static String emoji(SharedPreferences prefs, String language) {
        return string(prefs, language, EMOJI);
    }

    /** The language's numbers choice: "" for the main setting. */
    public static String numbers(SharedPreferences prefs, String language) {
        return string(prefs, language, NUMBERS);
    }

    private static String string(SharedPreferences prefs, String language, String field) {
        if (prefs == null || language == null || language.isEmpty()) {
            return "";
        }
        try {
            final String v = prefs.getString(key(language, field), null);
            return v == null ? "" : v;
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** The cue where speech switches into this language: Earcons.CUE_NONE unless set. */
    public static String switchCue(SharedPreferences prefs, String language) {
        return string(prefs, language, SWITCH_CUE);
    }


    /**
     * Fills the "Speech by language" category: the languages the user reads
     * (the phone's, those with a natural voice or a script choice, those
     * with a card), each opening its page, and "Add language" for the rest.
     */
    public static void configure(Context context, PreferenceScreen screen, PreferenceFragmentCompat fragment,
                                 SharedPreferences prefs, List<Voice> voices) {
        final PreferenceCategory category = screen.findPreference(KEY_CATEGORY);
        if (category == null || !(fragment instanceof SettingsFragment)) {
            return;
        }
        final SettingsFragment f = (SettingsFragment) fragment;
        final Map<String, String> all = languageNames(voices);
        for (String lang : usedLanguages(prefs, voices)) {
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

    /**
     * The languages the user reads (ISO 639-2, sorted): the phone's, those
     * with a natural voice, a card or a script choice.
     */
    public static Set<String> usedLanguages(SharedPreferences prefs, List<Voice> voices) {
        final Set<String> used = new TreeSet<>();
        used.add(PiperVoiceStore.languageKey(Locale.getDefault()));
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            final String k = e.getKey();
            final String v = String.valueOf(e.getValue());
            if (k.startsWith(PiperVoiceStore.PREF_VOICE_PREFIX) && !v.isEmpty()) {
                used.add(k.substring(PiperVoiceStore.PREF_VOICE_PREFIX.length()));
            } else if (k.startsWith(PREF_PREFIX) && !v.isEmpty() && k.lastIndexOf('_') > PREF_PREFIX.length()) {
                used.add(k.substring(PREF_PREFIX.length(), k.lastIndexOf('_')));
            } else if (k.startsWith(ScriptLanguages.PREF_PREFIX) && !v.isEmpty()) {
                for (Voice voice : voices) {
                    if (voice.name.equals(v)) {
                        used.add(PiperVoiceStore.languageKey(voice.locale));
                    }
                }
            }
        }
        return used;
    }

    /** The voices' languages (ISO 639-2) and their names, sorted by code. */
    public static Map<String, String> languageNames(List<Voice> voices) {
        final Map<String, String> all = new TreeMap<>();
        for (Voice v : voices) {
            final String lang = PiperVoiceStore.languageKey(v.locale);
            if (!lang.isEmpty()) {
                all.put(lang, PiperSettings.languageName(lang));
            }
        }
        return all;
    }

    private static Preference row(Context context, SettingsFragment f,
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
        final boolean mixedSet = !whenMixed(prefs, lang).isEmpty();
        final boolean sameProsody = factor(prefs, lang, SPEED) == 1f && factor(prefs, lang, PITCH) == 1f
                && factor(prefs, lang, VOLUME) == 1f;
        if (sameProsody && cue.isEmpty() && !mixedSet) {
            return context.getString(R.string.language_card_summary_same);
        }
        if (mixedSet && sameProsody && cue.isEmpty()) {
            return context.getString(R.string.language_card_summary_mixed_only);
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
    private static void open(Context context, SettingsFragment f,
                             SharedPreferences prefs, String lang, String name) {
        final PreferenceScreen page = f.getPreferenceManager().createPreferenceScreen(context);
        page.setTitle(name);
        addProsody(context, page, field -> key(lang, field));
        final java.util.List<CharSequence> mixedEntries = new java.util.ArrayList<>();
        final java.util.List<CharSequence> mixedValues = new java.util.ArrayList<>();
        mixedEntries.add(context.getString(R.string.language_card_mixed_default));
        mixedValues.add(com.animeshahilya.espeakng.tts.RunPlan.MIXED_DEFAULT);
        mixedEntries.add(context.getString(R.string.language_card_mixed_espeak));
        mixedValues.add(com.animeshahilya.espeakng.tts.RunPlan.MIXED_ESPEAK);
        mixedEntries.add(context.getString(R.string.language_card_mixed_surrounding));
        mixedValues.add(com.animeshahilya.espeakng.tts.RunPlan.MIXED_SURROUNDING);
        for (PiperVoiceStore.Installed v : PiperVoiceStore.list(
                com.animeshahilya.espeakng.EspeakApp.requireStorageContext(context))) {
            if (lang.equals(v.languageKey())) {
                mixedEntries.add(v.config.displayName());
                mixedValues.add(v.key);
            }
        }
        final ListPreference mixed = new ListPreference(context);
        mixed.setKey(key(lang, WHEN_MIXED));
        mixed.setTitle(R.string.language_card_mixed);
        mixed.setDialogTitle(R.string.language_card_mixed);
        mixed.setEntries(mixedEntries.toArray(new CharSequence[0]));
        mixed.setEntryValues(mixedValues.toArray(new CharSequence[0]));
        mixed.setDefaultValue(com.animeshahilya.espeakng.tts.RunPlan.MIXED_DEFAULT);
        mixed.setIconSpaceReserved(false);
        mixed.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        page.addPreference(mixed);
        page.addPreference(choice(context, key(lang, SWITCH_AT), R.string.language_card_switch_at,
                R.array.switching_sensitivity_entries, R.array.switching_sensitivity_values, ""));
        page.addPreference(choice(context, key(lang, NUMBERS), R.string.language_card_numbers,
                R.array.numbers_language_entries, R.array.numbers_language_values, NUMBERS_AUTO));
        page.addPreference(choice(context, key(lang, EMOJI), R.string.language_card_emoji,
                R.array.emoji_language_entries, R.array.emoji_language_values, EMOJI_VOICE));
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

    /** Names the stored key of one card field. */
    interface FieldKey {
        String of(String field);
    }

    /** Speed, pitch and volume rows, each "same as main" or 50-200% (shared with {@link AppCards}). */
    static void addProsody(Context context, PreferenceScreen page, FieldKey key) {
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
            setting.setKey(key.of(FIELDS[i]));
            setting.setTitle(titles[i]);
            setting.setDialogTitle(titles[i]);
            setting.setEntries(entries);
            setting.setEntryValues(values);
            setting.setDefaultValue("");
            setting.setIconSpaceReserved(false);
            setting.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            page.addPreference(setting);
        }
    }

    /**
     * A choice that defaults to the main setting: "Same as main setting"
     * first, then the main setting's own entries ({@code auto} stands in for
     * an entry whose value is "", which here means "same as main").
     */
    static ListPreference choice(Context context, String key, int title, int entriesRes,
                                         int valuesRes, String auto) {
        final CharSequence[] mainEntries = context.getResources().getTextArray(entriesRes);
        final CharSequence[] mainValues = context.getResources().getTextArray(valuesRes);
        final CharSequence[] entries = new CharSequence[mainEntries.length + 1];
        final CharSequence[] values = new CharSequence[mainValues.length + 1];
        entries[0] = context.getString(R.string.language_card_main_setting);
        values[0] = "";
        for (int i = 0; i < mainEntries.length; i++) {
            entries[i + 1] = mainEntries[i];
            values[i + 1] = mainValues[i].length() == 0 ? auto : mainValues[i];
        }
        final ListPreference p = new ListPreference(context);
        p.setKey(key);
        p.setTitle(title);
        p.setDialogTitle(title);
        p.setEntries(entries);
        p.setEntryValues(values);
        p.setDefaultValue("");
        p.setIconSpaceReserved(false);
        p.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        return p;
    }
}
