/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
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
 * This Activity is used by Android to get the list of languages to display
 * to the user when selecting the text-to-speech language. This is by locale,
 * not voice name.
 */

package com.animeshahilya.espeakng;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech.Engine;
import android.util.Log;

import com.animeshahilya.espeakng.SpeechSynthesis.SynthReadyCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CheckVoiceData extends Activity {
    private static final String TAG = "eSpeakTTS";

    /** Resources required for eSpeak to run correctly. */
    private static final String[] BASE_RESOURCES = {
        "version",
        "intonations",
        "phondata",
        "phonindex",
        "phontab",
        "en_dict",
    };

    public static File getDataPath(Context context) {
        Context storage = EspeakApp.requireStorageContext(context);
        if (storage == null) {
            storage = context;
        }
        return new File(storage.getDir("voices", MODE_PRIVATE), "espeak-ng-data");
    }

    public static boolean hasBaseResources(Context context) {
        final File dataPath = getDataPath(context);

        for (String resource : BASE_RESOURCES) {
            final File resourceFile = new File(dataPath, resource);

            if (!resourceFile.exists()) {
                // Expected on first run before extraction; info, not an error.
                Log.i(TAG, "Missing base resource: " + resourceFile.getPath());
                return false;
            }
        }

        return true;
    }

    public static boolean canUpgradeResources(Context context) {
        try (java.io.InputStream stream = context.getResources().openRawResource(R.raw.espeakdata_version)) {
            final String version = FileUtils.read(stream);
            final String installedVersion = FileUtils.read(new File(getDataPath(context), "version"));
            return !version.trim().equals(installedVersion.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts or refreshes voice data when missing or stale. Unifies the
     * check-then-extract sequence every entry point used to repeat.
     *
     * @return true when usable voice data is present afterwards.
     */
    public static boolean ensureVoiceData(Context context) {
        if (!hasBaseResources(context) || canUpgradeResources(context)) {
            return extractVoiceData(context);
        }
        return true;
    }

    /**
     * Guards extraction against concurrent callers. TtsService.onCreate(),
     * TtsSettingsActivity.onCreate() and DownloadVoiceData's executor can
     * each independently decide extraction is needed and call this around
     * the same time (e.g. the TTS framework binds the service while the
     * user has the reinstall screen open); without a lock they would race
     * rmdir() against each other's writes into the same directory.
     */
    private static final Object EXTRACT_LOCK = new Object();

    public static boolean extractVoiceData(Context context) {
        synchronized (EXTRACT_LOCK) {
            // A concurrent caller may have already extracted a fresh, valid
            // tree while this thread was waiting on the lock - skip the
            // redundant rmdir()+re-extract (and the UI hiccup it causes)
            // rather than doing the same ~1s of work twice.
            if (hasBaseResources(context) && !canUpgradeResources(context)) {
                return true;
            }

            final File dataPath = getDataPath(context);
            FileUtils.rmdir(dataPath);

            try (java.io.InputStream dataStream = context.getResources().openRawResource(R.raw.espeakdata)) {
                FileUtils.extractZip(dataStream, dataPath.getParentFile());

                final String version;
                try (java.io.InputStream versionStream = context.getResources().openRawResource(R.raw.espeakdata_version)) {
                    version = FileUtils.read(versionStream);
                }
                FileUtils.write(new File(getDataPath(context), "version"), version);

                // A crash or full disk mid-extract used to leave a half-written
                // data dir behind: only report success when the base resources
                // (now including the freshly stamped version) actually landed,
                // so the next launch retries instead of serving broken voices.
                // Note the version must be written *before* this check - it is
                // itself one of the base resources.
                if (!hasBaseResources(context)) {
                    Log.e(TAG, "Voice data extraction incomplete, will retry");
                    return false;
                }
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract voice data", e);
                return false;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context storageContext = EspeakApp.requireStorageContext(this);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
        ArrayList<String> availableLanguages = new ArrayList<String>();
        ArrayList<String> unavailableLanguages = new ArrayList<String>();

        boolean haveBaseResources = hasBaseResources(storageContext);
        if (!haveBaseResources || canUpgradeResources(storageContext)) {
            if (!haveBaseResources) {
                unavailableLanguages.add(Locale.ENGLISH.toString());
            }
            returnResults(Engine.CHECK_VOICE_DATA_FAIL, availableLanguages, unavailableLanguages);
            return;
        }

        final SpeechSynthesis engine = new SpeechSynthesis(storageContext, mSynthReadyCallback);
        final List<Voice> voices = LanguageSettings.filterVoices(engine.getAvailableVoices(), prefs);
        if (BuildConfig.DEBUG) {
            Set<String> selected = LanguageSettings.getSelectedLanguages(prefs);
            Log.i(TAG, "CheckVoiceData: selected=" + (selected == null ? "ALL" : selected.size()) + ", exposing=" + voices.size());
        }

        for (Voice voice : voices) {
            availableLanguages.add(voice.toString());
        }

        returnResults(Engine.CHECK_VOICE_DATA_PASS, availableLanguages, unavailableLanguages);
    }

    private void returnResults(int result, ArrayList<String> availableLanguages, ArrayList<String> unavailableLanguages) {
        final Intent returnData = new Intent();
        returnData.putStringArrayListExtra(Engine.EXTRA_AVAILABLE_VOICES, availableLanguages);
        returnData.putStringArrayListExtra(Engine.EXTRA_UNAVAILABLE_VOICES, unavailableLanguages);
        setResult(result, returnData);
        finish();
    }

    private final SynthReadyCallback mSynthReadyCallback = new SynthReadyCallback() {
        @Override
        public void onSynthDataReady(byte[] audioData) {
            // Do nothing.
        }

        @Override
        public void onSynthDataComplete() {
            // Do nothing.
        }

        @Override
        public void onSynthWordBoundary(int textPosition, int textLength, int markerInFrames) {
            // Do nothing.
        }
    };
}
