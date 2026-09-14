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
        Context storage = EspeakApp.getStorageContext();
        if (storage == null && context != null) {
            storage = context.isDeviceProtectedStorage()
                    ? context
                    : context.createDeviceProtectedStorageContext();
        }
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
                Log.e(TAG, "Missing base resource: " + resourceFile.getPath());
                return false;
            }
        }

        return true;
    }

    public static boolean canUpgradeResources(Context context) {
        try (java.io.InputStream stream = context.getResources().openRawResource(R.raw.espeakdata_version)) {
            final String version = FileUtils.read(stream);
            final String installedVersion = FileUtils.read(new File(getDataPath(context), "version"));
            return !version.equals(installedVersion);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean extractVoiceData(Context context) {
        final File dataPath = getDataPath(context);
        FileUtils.rmdir(dataPath);

        try (java.io.InputStream dataStream = context.getResources().openRawResource(R.raw.espeakdata)) {
            FileUtils.extractZip(dataStream, dataPath.getParentFile());

            final String version;
            try (java.io.InputStream versionStream = context.getResources().openRawResource(R.raw.espeakdata_version)) {
                version = FileUtils.read(versionStream);
            }
            FileUtils.write(new File(getDataPath(context), "version"), version);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract voice data", e);
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context storageContext = EspeakApp.getStorageContext();
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
