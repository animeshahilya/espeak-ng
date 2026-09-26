/*
 * Copyright (C) 2026 Alexandr Epaneshnikov
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

package com.animeshahilya.espeakng.test;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.preference.PreferenceFragmentCompat;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.LanguageSettings;
import com.animeshahilya.espeakng.TtsSettingsActivity;
import com.animeshahilya.espeakng.VoiceSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assume.assumeTrue;

/**
 * Settings live in device-protected storage, where TtsService reads them
 * before the device is unlocked. Regression tests for #2536: a Preference
 * persisted through a plain Context created a credential-encrypted file, and
 * the startup migration then copied it over the real settings every time a
 * screen reader restarted the engine.
 */
@RunWith(AndroidJUnit4.class)
public class PreferenceStorageTest
{
    private Context mAppContext;
    private Context mStorageContext;
    private String mName;

    @Before
    public void setUpStorage()
    {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        mAppContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mStorageContext = mAppContext.createDeviceProtectedStorageContext();
        // getDefaultSharedPreferencesName() is private in AndroidX; both
        // frameworks compute it as packageName + "_preferences". The test's
        // real assertion is the storage *location*, not the name formula.
        mName = mAppContext.getPackageName() + "_preferences";
    }

    @After
    public void resetStorage()
    {
        if (mAppContext == null) {
            return;
        }
        deviceProtected().edit().clear().commit();
        mAppContext.deleteSharedPreferences(mName);
    }

    /** The file a plain Context writes to. It must stay empty. */
    private SharedPreferences credentialEncrypted()
    {
        return mAppContext.getSharedPreferences(mName, Context.MODE_PRIVATE);
    }

    /**
     * The file TtsService reads. Fetched anew on every call, because a
     * migration evicts the cached instance.
     */
    private SharedPreferences deviceProtected()
    {
        return mStorageContext.getSharedPreferences(mName, Context.MODE_PRIVATE);
    }

    /**
     * The settings screen loads the engine's voices on a worker thread and
     * adds its preferences afterwards, so wait until the one under test is
     * attached before looking at what it persisted.
     */
    private static void waitForPreference(ActivityScenario<TtsSettingsActivity> scenario,
                                          final String key) throws InterruptedException
    {
        final AtomicBoolean attached = new AtomicBoolean();
        for (int i = 0; i < 100 && !attached.get(); ++i) {
            scenario.onActivity(activity -> {
                final PreferenceFragmentCompat fragment = (PreferenceFragmentCompat)
                        activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
                attached.set(fragment != null && fragment.findPreference(key) != null);
            });
            if (!attached.get()) {
                Thread.sleep(100);
            }
        }
        assertThat("preference " + key + " attached within 10 s", attached.get(), is(true));
    }

    @Test
    public void settingsScreenWritesOnlyToDeviceProtectedStorage() throws InterruptedException
    {
        mAppContext.deleteSharedPreferences(mName);

        // Attaching the preferences persists their defaults; that is how
        // #2536 created the stray file without the user touching anything.
        try (ActivityScenario<TtsSettingsActivity> scenario =
                ActivityScenario.launch(TtsSettingsActivity.class)) {
            waitForPreference(scenario, VoiceSettings.PREF_UNICODE_NORMALIZATION);
            assertThat(credentialEncrypted().getAll().keySet(), is(empty()));
            assertThat(deviceProtected().contains(VoiceSettings.PREF_UNICODE_NORMALIZATION), is(true));
            // "All languages" is the absence of the key, so languages added
            // by a later update show up; just opening the screen must not
            // freeze today's list into it.
            assertThat(deviceProtected().contains(LanguageSettings.PREF_SUPPORTED_LANGUAGES), is(false));
        }
    }

    /**
     * res/xml/preferences.xml spells the keys out as literals; a typo there
     * would leave a setting on screen that nothing reads.
     */
    @Test
    public void settingsScreenHasEverySetting() throws InterruptedException
    {
        final String[] keys = {
                LanguageSettings.PREF_SUPPORTED_LANGUAGES, VoiceSettings.PREF_VARIANT,
                VoiceSettings.PREF_RATE, VoiceSettings.PREF_RATE_BOOST_LEVEL, VoiceSettings.PREF_PITCH,
                VoiceSettings.PREF_PITCH_RANGE, VoiceSettings.PREF_INTONATION_STYLE,
                VoiceSettings.PREF_INTONATION_GROUP, VoiceSettings.PREF_VOLUME,
                VoiceSettings.PREF_WORD_GAP, VoiceSettings.PREF_PAUSE_SCALE,
                VoiceSettings.PREF_AUDIO_OPTIMIZER_LEVEL,
                VoiceSettings.PREF_USER_DICTIONARY, VoiceSettings.PREF_INDIAN_NUMBERING_VOICES,
                VoiceSettings.PREF_DIGIT_GROUPING, VoiceSettings.PREF_DIGIT_GROUP_THRESHOLD,
                VoiceSettings.PREF_PUNCTUATION_LEVEL, VoiceSettings.PREF_CAPITALS,
                VoiceSettings.PREF_CAPITALS_SCOPE, VoiceSettings.PREF_REPEATED_CHARS,
                VoiceSettings.PREF_UNICODE_NORMALIZATION,
                VoiceSettings.PREF_READING_MODE, VoiceSettings.PREF_PHONETIC_LETTERS, VoiceSettings.PREF_HINGLISH,
                VoiceSettings.PREF_SPOKEN_DIACRITICS, VoiceSettings.PREF_EMOJI_PROCESSING,
                VoiceSettings.PREF_SIMPLIFY_URLS, VoiceSettings.PREF_EMPHASIZE_QUESTIONS,
                VoiceSettings.PREF_FORCE_RATE, VoiceSettings.PREF_FORCE_PITCH,
                VoiceSettings.PREF_FORCE_VOLUME,
        };
        try (ActivityScenario<TtsSettingsActivity> scenario =
                ActivityScenario.launch(TtsSettingsActivity.class)) {
            waitForPreference(scenario, VoiceSettings.PREF_VARIANT);
            scenario.onActivity(activity -> {
                final PreferenceFragmentCompat fragment = (PreferenceFragmentCompat)
                        activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
                for (String key : keys) {
                    assertThat("setting " + key, fragment.findPreference(key), is(notNullValue()));
                }
            });
        }
    }

    @Test
    public void legacyPreferencesMoveIntoDeviceProtectedStorage()
    {
        // An install from before device-protected storage: nothing there yet,
        // the settings still sit in the credential-encrypted file.
        deviceProtected().edit().clear().commit();
        mAppContext.deleteSharedPreferences(mName);
        credentialEncrypted().edit().putString(VoiceSettings.PREF_RATE, "123").commit();

        EspeakApp.migrateLegacyPreferences(mAppContext, mStorageContext);

        assertThat(deviceProtected().getString(VoiceSettings.PREF_RATE, null), is("123"));
        assertThat(deviceProtected().getBoolean(EspeakApp.PREF_PREFERENCES_MIGRATED, false), is(true));
        assertThat(credentialEncrypted().getAll().keySet(), is(empty()));
    }

    @Test
    public void firstStartMarksDeviceProtectedStorageAsLive()
    {
        // A fresh install: neither file exists. The first unlocked start must
        // still leave a mark, so that a credential-encrypted file appearing
        // before the user ever opens the settings is discarded, not adopted.
        deviceProtected().edit().clear().commit();
        mAppContext.deleteSharedPreferences(mName);

        EspeakApp.migrateLegacyPreferences(mAppContext, mStorageContext);
        assertThat(deviceProtected().getBoolean(EspeakApp.PREF_PREFERENCES_MIGRATED, false), is(true));

        credentialEncrypted().edit().putString(VoiceSettings.PREF_RATE, "123").commit();
        EspeakApp.migrateLegacyPreferences(mAppContext, mStorageContext);

        assertThat(deviceProtected().contains(VoiceSettings.PREF_RATE), is(false));
        assertThat(credentialEncrypted().getAll().keySet(), is(empty()));
    }

    @Test
    public void strayCredentialEncryptedFileNeverReplacesSettings()
    {
        deviceProtected().edit().clear().putString(VoiceSettings.PREF_RATE, "123").commit();
        mAppContext.deleteSharedPreferences(mName);
        // The exact file #2536 left behind: one default, persisted through a
        // plain Context.
        credentialEncrypted().edit().putBoolean(VoiceSettings.PREF_UNICODE_NORMALIZATION, true).commit();

        EspeakApp.migrateLegacyPreferences(mAppContext, mStorageContext);

        assertThat(deviceProtected().getString(VoiceSettings.PREF_RATE, null), is("123"));
        assertThat(deviceProtected().contains(VoiceSettings.PREF_UNICODE_NORMALIZATION), is(false));
        assertThat(credentialEncrypted().getAll().keySet(), is(empty()));
    }
}
