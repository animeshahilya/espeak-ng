/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.tts;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.TtsService;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * VoiceEngine implementation wrapping the native eSpeak NG speech synthesizer.
 */
public final class EspeakVoiceEngine implements VoiceEngine {

    public static final String ID = "espeak";

    private final TtsService mService;

    public EspeakVoiceEngine(TtsService service) {
        mService = service;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "eSpeak NG";
    }

    @Override
    public boolean isAvailable(Context context) {
        return CheckVoiceData.hasBaseResources(context);
    }

    @Override
    public boolean isEnabled(SharedPreferences prefs) {
        return true; // Always enabled as core synthesizer
    }

    @Override
    public void onStop() {
        if (mService != null) {
            SpeechSynthesis engine = mService.getEngine();
            if (engine != null) {
                engine.stop();
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        // eSpeak dictionaries and native heap are lean; no trimming necessary
    }

    @Override
    public void unloadAll() {
        // Native engine is shared process-wide
    }
}


