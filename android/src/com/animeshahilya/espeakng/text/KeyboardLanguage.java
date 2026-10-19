/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Detects the active on-screen keyboard (IME) language subtype.
 *
 * <p>TalkBack typing echo and single-character navigation lack sentence context.
 * When the user switches keyboard languages (e.g. typing on a Hindi, Russian, or
 * Ukrainian keyboard while system language is English), this detector identifies
 * the active keyboard layout so ambiguous keystrokes route to the correct voice.
 *
 * <p>Features a 500 ms TTL cache to eliminate redundant Binder IPC calls during
 * rapid typing.
 */
public final class KeyboardLanguage {
    private static final String TAG = "KeyboardLanguage";
    private static final long CACHE_TTL_MS = 500;

    private static volatile String sCachedLanguage = null;
    private static volatile long sLastCheckTime = 0;

    /** Old codes Android's Locale still reports; everything else maps through Locale. */
    private static final Map<String, String> LEGACY_CODES = new HashMap<>();

    static {
        LEGACY_CODES.put("in", "ind");
        LEGACY_CODES.put("iw", "heb");
        LEGACY_CODES.put("ji", "yid");
    }

    private KeyboardLanguage() {
    }

    /**
     * Reads the current active keyboard language as an ISO 639-2 code (e.g. "hin", "rus", "eng").
     * Returns null if undetectable or not a keyboard subtype.
     */
    public static String read(Context context) {
        if (context == null) {
            return null;
        }

        final long now = SystemClock.uptimeMillis();
        if (now - sLastCheckTime < CACHE_TTL_MS) {
            return sCachedLanguage;
        }

        String detected = readUncached(context);
        sCachedLanguage = detected;
        sLastCheckTime = now;
        return detected;
    }

    private static String readUncached(Context context) {
        try {
            final InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return null;
            }

            InputMethodSubtype current = null;
            try {
                current = imm.getCurrentInputMethodSubtype();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to get current subtype", e);
            }

            // The selected subtype's id is a hidden setting: where reading
            // it is refused, the subtype above is still the answer.
            String defaultIme = null;
            int selectedSubtypeId = -1;
            try {
                final ContentResolver cr = context.getContentResolver();
                defaultIme = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
                selectedSubtypeId = Settings.Secure.getInt(cr, "selected_input_method_subtype", -1);
            } catch (RuntimeException e) {
                // SecurityException on newer platforms; keep `current`
            }

            InputMethodSubtype resolved = null;
            if (defaultIme != null && selectedSubtypeId != -1
                    && (current == null || current.hashCode() != selectedSubtypeId)) {
                for (InputMethodInfo info : imm.getInputMethodList()) {
                    if (defaultIme.equals(info.getId())) {
                        for (int i = 0; i < info.getSubtypeCount(); i++) {
                            final InputMethodSubtype sub = info.getSubtypeAt(i);
                            if (sub.hashCode() == selectedSubtypeId) {
                                resolved = sub;
                                break;
                            }
                        }
                        break;
                    }
                }
            }

            if (resolved == null) {
                resolved = current;
            }

            if (resolved == null) {
                return null;
            }

            final String mode = resolved.getMode();
            if (mode != null && !mode.isEmpty() && !"keyboard".equalsIgnoreCase(mode)) {
                return null;
            }

            String tag = resolved.getLanguageTag();
            if (tag == null || tag.isEmpty()) {
                tag = resolved.getLocale();
            }
            if (tag == null || tag.isEmpty()) {
                return null;
            }

            return normalizeToIso3(tag);
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving keyboard language", t);
            return null;
        }
    }

    /**
     * Normalizes a locale tag (e.g. "hi-IN", "ru_RU", "en-US") to an ISO 639-2 language code.
     */
    public static String normalizeToIso3(String localeTag) {
        if (localeTag == null || localeTag.isEmpty()) {
            return null;
        }
        final String clean = localeTag.replace('_', '-');
        final Locale loc = Locale.forLanguageTag(clean);
        String lang = loc.getLanguage().toLowerCase(Locale.ROOT);
        if (lang.isEmpty()) {
            int dash = clean.indexOf('-');
            lang = (dash > 0 ? clean.substring(0, dash) : clean).toLowerCase(Locale.ROOT);
        }

        final String mapped = LEGACY_CODES.get(lang);
        if (mapped != null) {
            return mapped;
        }

        try {
            return new Locale(lang).getISO3Language();
        } catch (MissingResourceException ignored) {
            return lang.length() == 3 ? lang : null;
        }
    }
}
