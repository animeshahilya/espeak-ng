/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * VoiceEngine implementation wrapping Piper Neural TTS models.
 */
public final class PiperVoiceEngine implements VoiceEngine {

    public static final String ID = "piper";

    private final PiperEngine mPiper;

    public PiperVoiceEngine(PiperEngine piper) {
        mPiper = piper != null ? piper : PiperEngine.get();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Piper Neural";
    }

    @Override
    public boolean isAvailable(Context context) {
        return !PiperVoiceStore.list(context).isEmpty();
    }

    @Override
    public boolean isEnabled(SharedPreferences prefs) {
        return PiperVoiceStore.isEnabled(prefs);
    }

    @Override
    public void onStop() {
        mPiper.stop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            mPiper.trim(true);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            mPiper.trim(false);
        }
    }

    @Override
    public void unloadAll() {
        mPiper.unloadAll();
    }
}
