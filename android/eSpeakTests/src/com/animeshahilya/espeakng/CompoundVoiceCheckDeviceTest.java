/*
 * Copyright (C) 2026 eSpeak NG contributors
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

package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Compound-splitting A/B render for off-device scoring (Parakeet):
 * the same lone words spoken by the eSpeak US voice with
 * {@code espeak_split_compounds} off and on.
 *
 * <p>Runs only with {@code -e set compounds} (slow: ~120 utterances);
 * otherwise skipped. Output in the app's external cache under
 * {@code compound_check/&lt;tag&gt;-off/} and {@code -on/}, with
 * {@code index.txt} (id, word). Pull both dirs and score with
 * {@code scratch/voice-eval/compound_ab.py}.
 *
 * <p>Same word order in both runs, so eSpeak's carry-over noise state
 * follows the same trajectory and cannot favor either side.
 */
@RunWith(AndroidJUnit4.class)
public class CompoundVoiceCheckDeviceTest {

    private static final String[] COMPOUNDS = {
            "w|acetylcholine", "w|anteater", "w|backstop", "w|bedchamber",
            "w|blackwood", "w|bookbinders", "w|briefcases", "w|campfires",
            "w|checklist", "w|cockroaches", "w|cowboy", "w|daydreaming",
            "w|dovetail", "w|earthquakes", "w|featherbedding", "w|flagship",
            "w|foreground", "w|girlfriend", "w|greasewood", "w|halftime",
            "w|haycock", "w|heirloom", "w|honeydew", "w|icebox",
            "w|kneecaps", "w|leeway", "w|lowlands", "w|metalworker",
            "w|mousetrap", "w|nightstick", "w|overkill", "w|paramedic",
            "w|pitchman", "w|pratfalls", "w|redwood", "w|saleswoman",
            "w|searchlights", "w|shortcoming", "w|skyscraper", "w|southpaw",
            "w|steamboat", "w|strongman", "w|taxiway", "w|touchback",
            "w|undergarment", "w|warlords", "w|wellbeing", "w|woodchuck",
    };

    private static final String[] CONTROLS = {
            "w|Settings", "w|Messages", "w|Bluetooth", "w|Download",
            "w|Calendar", "w|Battery", "w|Search", "w|Volume",
            "w|Back", "w|Home", "w|Delete", "w|Cancel",
    };

    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Test
    public void render() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("pass -e set compounds to render the A/B corpus",
                "compounds".equals(args.getString("set")));
        final String tag = args.getString("tag", "run");
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Context storage = context.createDeviceProtectedStorageContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(storage);

        final boolean oldSplit = prefs.getBoolean(VoiceSettings.PREF_SPLIT_COMPOUNDS, false);
        final boolean oldPiper = prefs.getBoolean(PiperVoiceStore.PREF_ENABLED, false);
        // -e only w1,w2: re-render a subset (flip verification) with stable ids.
        final String only = args.getString("only", null);
        final String[] words = only != null ? filter(concat(COMPOUNDS, CONTROLS), only)
                : concat(COMPOUNDS, CONTROLS);
        final String[] items = carriers(words);
        try {
            // eSpeak engine only: a Hindi/English natural voice must not
            // steal these runs, or off and on stop being comparable.
            prefs.edit().putBoolean(PiperVoiceStore.PREF_ENABLED, false).commit();
            prefs.edit().putBoolean(VoiceSettings.PREF_SPLIT_COMPOUNDS, false).commit();
            render(context, new Locale("en", "US"), tag + "-off", items, words);
            prefs.edit().putBoolean(VoiceSettings.PREF_SPLIT_COMPOUNDS, true).commit();
            render(context, new Locale("en", "US"), tag + "-on", items, words);
        } finally {
            final SharedPreferences.Editor e = prefs.edit()
                    .putBoolean(VoiceSettings.PREF_SPLIT_COMPOUNDS, oldSplit)
                    .putBoolean(PiperVoiceStore.PREF_ENABLED, oldPiper);
            e.commit();
        }
    }

    private static String[] concat(String[] a, String[] b) {
        final String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Keeps global word order for a subset (scoring matches by word, not id). */
    private static String[] filter(String[] words, String only) {
        final java.util.Set<String> keep = new java.util.HashSet<>();
        for (String w : only.split(",")) {
            keep.add(w.trim().toLowerCase(Locale.ROOT));
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : words) {
            if (keep.contains(w.substring(2).toLowerCase(Locale.ROOT))) {
                out.add(w);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Lone eSpeak words are unjudgeable (Parakeet mishears them the same way
     * under both conditions, so the A/B drowns in noise): every word rides
     * in one fixed carrier sentence instead.
     */
    private static String[] carriers(String[] words) {
        final String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            out[i] = "s|I said " + words[i].substring(2) + " yesterday.";
        }
        return out;
    }

    private void render(Context context, Locale locale, String tag, String[] items, String[] words)
            throws Exception {
        final File dir = new File(context.getExternalCacheDir(), "compound_check/" + tag);
        deleteContents(dir);
        dir.mkdirs();
        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech tts = new TextToSpeech(context, s -> ready.countDown(), context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }

                @Override public void onDone(String id) {
                    done(id);
                }

                @Override public void onError(String id) {
                    done(id);
                }
            });
            assertThat(tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE, is(true));
            say(tts, "Warming up the voice.", new File(dir, "warm.wav"), "warm");
            final StringBuilder index = new StringBuilder();
            for (int i = 0; i < items.length; i++) {
                final String id = String.format(Locale.ROOT, "%03d", i);
                final String text = items[i].substring(2);
                say(tts, text, new File(dir, id + ".wav"), id);
                index.append(id).append("\t").append(items[i].charAt(0)).append("\t").append(text)
                        .append("\t").append(words[i].substring(2)).append("\n");
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, "index.txt"))) {
                out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            tts.shutdown();
        }
    }

    private void say(TextToSpeech tts, String text, File out, String id) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(120, TimeUnit.SECONDS));
    }

    private void done(String id) {
        final CountDownLatch latch = mDone.get(id);
        if (latch != null) {
            latch.countDown();
        }
    }

    private static void deleteContents(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }
}
