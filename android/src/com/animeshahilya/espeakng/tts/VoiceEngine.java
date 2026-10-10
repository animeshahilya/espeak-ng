/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.tts;
import com.animeshahilya.espeakng.Voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Service Provider Interface (SPI) for voice synthesis engines.
 *
 * <p>Unifies core synthesizer backends (eSpeak NG, Piper Neural, and future
 * engines such as Kokoro, VITS, or Sherpa-ONNX) under a standard lifecycle
 * and capability contract.
 */
public interface VoiceEngine {

    /** Unique identifier for this engine (e.g. "espeak", "piper"). */
    String getId();

    /** Human-readable display name. */
    String getName();

    /** Whether the required native binaries, models, or assets are present on the system. */
    boolean isAvailable(Context context);

    /** Whether this engine is enabled according to user preferences. */
    boolean isEnabled(SharedPreferences prefs);

    /** Aborts active speech synthesis immediately. */
    void onStop();

    /** Evicts in-memory caches or models under system memory pressure. */
    void onTrimMemory(int level);

    /** Unloads all in-memory neural models or dictionaries held by this engine. */
    void unloadAll();
}


