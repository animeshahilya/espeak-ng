/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.util;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.Set;

/**
 * Settings as the speech path reads them: a value stored with the wrong
 * type (a hand-edited or damaged backup, a setting whose type changed
 * between versions) reads as unset, i.e. its default, instead of throwing
 * ClassCastException out of onSynthesizeText - which silenced every
 * utterance until the setting was reset. Everything else delegates.
 */
public final class TolerantPreferences implements SharedPreferences {
    private static final String TAG = "TolerantPreferences";
    private final SharedPreferences mPrefs;

    private TolerantPreferences(SharedPreferences prefs) {
        mPrefs = prefs;
    }

    public static SharedPreferences of(SharedPreferences prefs) {
        return prefs instanceof TolerantPreferences ? prefs : new TolerantPreferences(prefs);
    }

    /** Warned once per setting: this runs several times per utterance. */
    private static final Set<String> sWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void wrongType(String key, ClassCastException e) {
        if (sWarned.add(key)) {
            Log.w(TAG, "Setting " + key + " has the wrong type; using its default", e);
        }
    }

    @Override
    public String getString(String key, String defValue) {
        try {
            return mPrefs.getString(key, defValue);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValue;
        }
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        try {
            return mPrefs.getStringSet(key, defValues);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValues;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        try {
            return mPrefs.getInt(key, defValue);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        try {
            return mPrefs.getLong(key, defValue);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        try {
            return mPrefs.getFloat(key, defValue);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        try {
            return mPrefs.getBoolean(key, defValue);
        } catch (ClassCastException e) {
            wrongType(key, e);
            return defValue;
        }
    }

    @Override
    public Map<String, ?> getAll() {
        return mPrefs.getAll();
    }

    @Override
    public boolean contains(String key) {
        return mPrefs.contains(key);
    }

    @Override
    public Editor edit() {
        return mPrefs.edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        mPrefs.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        mPrefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}

