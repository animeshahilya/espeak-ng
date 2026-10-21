/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2012-2015 Reece H. Dunn
 * Copyright (C) 2011 Google Inc.
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

/*
 * This file implements the Android Text-to-Speech engine for eSpeak.
 *
 * Minimum Android Version: 8.0 (Oreo)
 * Minimum API Version:     26
 */

package com.animeshahilya.espeakng.tts;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import com.animeshahilya.espeakng.SpeechSynthesis;
import com.animeshahilya.espeakng.piper.PiperEngine;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.animeshahilya.espeakng.text.Earcons;
import com.animeshahilya.espeakng.ui.AppCards;
import com.animeshahilya.espeakng.ui.LanguageCards;
import com.animeshahilya.espeakng.ui.VoiceSettings;

/**
 * What one synthesis request asks for, from its parameters and the user's settings: rate, volume, per-language prosody and switch cues, and the natural-voice parameters. Pure functions of the request and the settings.
 */
public final class RequestSettings {
    private static final String TAG = "RequestSettings";

    private RequestSettings() {
    }

    /**
     * True when a natural voice cannot read digits itself (its language has
     * no ICU number words and it is not letter-reading): digit runs must go
     * to eSpeak instead of being skipped ("ಸಮಯ 10:30" lost its time).
     */
    public static boolean needsDigitHelp(PiperVoiceStore.Installed natural) {
        return natural != null && !natural.config.usesEspeak() && !natural.config.isSupertonic()
                && PiperEngine.numberWords(natural.config.languageFamily) == null;
    }

    // BaseBundle.get(String) was deprecated in API 33, but no typed getter
    // preserves these reads: the debug dump takes arbitrary keys, and volume
    // defensively accepts Number or String. Both callers keep exact behavior.
    @SuppressWarnings("deprecation")
    public static void logSynthesisParams(Bundle params) {
        if (params == null) {
            return;
        }
        for (String key : params.keySet()) {
            Log.v(TAG,
                    "Synthesis request contained param {" + key + ", " + params.get(key) + "}");
        }
    }

    // See logSynthesisParams for why Bundle.get() stays here.
    @SuppressWarnings("deprecation")
    private static float getVolumeScale(Bundle params) {
        Object volObj = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME);
        if (volObj instanceof Number) {
            return ((Number) volObj).floatValue();
        } else if (volObj instanceof String) {
            try {
                return Float.parseFloat((String) volObj);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1.0f;
    }

    /**
     * The cue the new language's card asks for where speech switches into it
     * from another language (Settings -> Mixed languages -> Speech by
     * language). Written before the next unit's chunk context is set, so its
     * word ranges still line up. Nothing at the start, or within a language.
     */
    public static void writeSwitchCue(TtsAudioDispatcher dispatcher, SharedPreferences prefs, String from,
                                       String to, int sampleRate, int channels, int volume) {
        if (from == null || from.equals(to)) {
            return;
        }
        final byte[] cue = Earcons.switchCue(LanguageCards.switchCue(prefs, to), sampleRate, channels, volume);
        if (cue.length > 0) {
            dispatcher.writeAudio(cue);
        }
    }

    /**
     * eSpeak's rate, pitch and volume for speech in {@code language}: the
     * request's own, times that language's card (Settings -> Mixed-language
     * text -> Speech by language). No card: exactly the request's values.
     */
    public static void setProsody(SpeechSynthesis engine, SharedPreferences prefs, String language,
                                   int rate, int pitch, int volume, boolean rateBoost) {
        final float speed = LanguageCards.factor(prefs, language, LanguageCards.SPEED);
        final float pitchFactor = LanguageCards.factor(prefs, language, LanguageCards.PITCH);
        final float volumeFactor = LanguageCards.factor(prefs, language, LanguageCards.VOLUME);
        int r = speed == 1f ? rate : Math.round(rate * speed);
        if (!rateBoost && r > 450) {
            r = 450;
        }
        engine.Rate.setValue(r);
        engine.Pitch.setValue(pitchFactor == 1f ? pitch : Math.min(100, Math.round(pitch * pitchFactor)));
        engine.Volume.setValue(volumeFactor == 1f ? volume : Math.min(200, Math.round(volume * volumeFactor)));
    }

    /**
     * The request's speaking rate in wpm: the saved rate scaled by the
     * caller's rate unless locked, capped at the engine maximum unless rate
     * boost is on.
     *
     * <p>The cap used to be 449 per NVDA issue #131, to avoid unintended
     * Sonic engagement at 450 WPM - but upstream #2165 moved Sonic
     * engagement strictly above 450, and #2355 made 450 the engine max,
     * so exactly 450 never engages Sonic (no shipped voice lowers
     * fast_settings below the 450 default either). NVDA still caps at
     * 449 out of caution for engines predating #2165; this app always
     * ships its own engine, so it follows the engine it ships.
     */
    public static int effectiveRate(VoiceSettings settings, SynthesisRequest request) {
        int rate = settings.getRate();
        int rateScale = request.getSpeechRate();
        if (rateScale <= 0) {
            rateScale = 100;
        }
        // Force override: lock to the saved rate regardless of caller requests.
        if (!settings.isForceRateEnabled()) {
            rate = (int)(((long)rate * rateScale) / 100);
        }
        // The calling app's card (Speech by app) applies even when locked:
        // it is the user's own choice, not the app's.
        final float appSpeed = settings.appFactor(AppCards.SPEED);
        if (appSpeed != 1f) {
            rate = Math.round(rate * appSpeed);
        }
        if (!settings.isRateBoostEnabled() && rate > 450) {
            rate = 450;
        }
        return rate;
    }

    /**
     * Volume, 0-200 (100 = normal): the saved volume times the caller's
     * KEY_PARAM_VOLUME (accessibility ducking) unless volume is locked.
     */
    public static int effectiveVolume(VoiceSettings settings, SynthesisRequest request) {
        float volumeScale = 1.0f;
        final Bundle params = request.getParams();
        if (!settings.isForceVolumeEnabled() && params != null) {
            volumeScale = getVolumeScale(params);
        }
        if (volumeScale < 0.0f) {
            volumeScale = 0.0f;
        } else if (volumeScale > 1.0f) {
            volumeScale = 1.0f;
        }
        return Math.min(200, Math.round(settings.getVolume() * volumeScale * settings.appFactor(AppCards.VOLUME)));
    }

    /** The speaker and speed of the voice speaking next (both chosen per voice). */
    public static void forVoice(PiperEngine.Params params, VoiceSettings settings,
                                 SynthesisRequest request, SharedPreferences prefs, String key) {
        params.speakerId = PiperVoiceStore.speakerId(prefs, key);
        params.speed = effectiveRate(settings, request) / (float) PiperEngine.NORMAL_RATE
                * PiperVoiceStore.speedFactor(prefs, key);
    }

    public static PiperEngine.Params naturalParams(VoiceSettings settings, SynthesisRequest request,
                                                    SharedPreferences prefs) {
        final PiperEngine.Params params = new PiperEngine.Params();
        params.speed = effectiveRate(settings, request) / (float) PiperEngine.NORMAL_RATE
                * PiperVoiceStore.speedFactor(prefs);
        final float[] style = PiperVoiceStore.styleScales(prefs);
        params.noiseScale = style[0];
        params.noiseW = style[1];
        int pitchScale = request.getPitch();
        if (pitchScale <= 0 || settings.isForcePitchEnabled()) {
            pitchScale = 100;
        }
        pitchScale = Math.round(pitchScale * settings.appFactor(AppCards.PITCH));
        // eSpeak pitch 0-100 around 50 -> about half an octave down/up,
        // times the caller's pitch. A neural voice has its own natural
        // pitch; this only shifts it, it never flattens intonation.
        final float userPitch = (float) Math.pow(2.0, (settings.getPitch() - 50) / 100.0);
        params.pitch = Math.max(0.5f, Math.min(2.0f, userPitch * pitchScale / 100f));
        params.volume = effectiveVolume(settings, request) / 100f;
        params.pauseScale = settings.getPauseScale();
        return params;
    }

    /**
     * The language digits get a run of their own in ({@link LanguageRuns}),
     * or null for the words around them (Mixed languages -> Numbers and
     * times; English and "around" are also written out as English words by
     * TextPreprocessor for non-Latin voices).
     */
    public static String numbersRunLanguage(VoiceSettings settings, String own) {
        final String numbers = settings.getNumbersLanguage(own);
        if (VoiceSettings.NUMBERS_VOICE.equals(numbers)) {
            return own;
        }
        return VoiceSettings.NUMBERS_ENGLISH.equals(numbers) ? "eng" : null;
    }
}
