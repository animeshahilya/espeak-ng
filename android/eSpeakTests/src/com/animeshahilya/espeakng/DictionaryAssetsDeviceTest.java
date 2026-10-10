package com.animeshahilya.espeakng;

import com.animeshahilya.espeakng.tts.CheckVoiceData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiled dictionaries are read from the APK (assets/espeak-dicts, through
 * espeak_SetDictionaryReader), never extracted: every voice still loads its
 * language, and the data directory holds no *_dict file.
 */
@RunWith(AndroidJUnit4.class)
public class DictionaryAssetsDeviceTest {
    private Context mContext;
    private SpeechSynthesis mEngine;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CheckVoiceData.ensureVoiceData(mContext);
        mEngine = new SpeechSynthesis(mContext, new SpeechSynthesis.SynthReadyCallback() {
            @Override public void onSynthDataReady(byte[] audioData) { }
            @Override public void onSynthDataComplete() { }
            @Override public void onSynthWordBoundary(int a, int b, int c) { }
        });
    }

    /**
     * A voice whose dictionary failed to load reads nothing: its voice file
     * (the identifier, as eSpeak names it) is selected and given a number
     * and a letter, which every language's rules cover.
     */
    @Test
    public void everyVoiceReadsFromItsDictionary() {
        final List<String> failed = new ArrayList<>();
        int checked = 0;
        for (Voice v : mEngine.getAvailableVoices()) {
            final String ipa = mEngine.phonemizeForPiper(v.identifier, "123 a");
            checked++;
            if (ipa == null || ipa.trim().isEmpty()) {
                failed.add(v.identifier);
            }
        }
        assertTrue("voices checked: " + checked, checked > 100);
        assertEquals("voices with no phonemes: " + failed, 0, failed.size());
    }

    @Test
    public void noDictionaryIsCopiedIntoAppStorage() {
        final File[] dicts = CheckVoiceData.getDataPath(mContext).listFiles((d, n) -> n.endsWith("_dict"));
        assertTrue(dicts == null || dicts.length == 0);
    }
}
