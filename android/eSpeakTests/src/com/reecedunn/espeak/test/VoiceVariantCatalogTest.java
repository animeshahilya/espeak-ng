/*
 * Copyright (C) 2026
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

package com.reecedunn.espeak.test;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.reecedunn.espeak.CheckVoiceData;
import com.reecedunn.espeak.preference.VoiceVariantPreference;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * VoiceVariantPreference's picker is a hardcoded table mapping display names
 * to espeak-ng-data/voices/!v/ filenames (see upstream issue #2376). Nothing
 * checks that table against the files actually shipped, so a typo or a
 * renamed/removed upstream file silently turns one picker entry into a
 * voice selection that espeak_SetVoiceByName() can never find - not a
 * crash, just quietly ignored. This caught two real instances of exactly
 * that ("anouncer" vs the shipped "announcer", "Marcelo" vs "marcelo").
 */
@RunWith(AndroidJUnit4.class)
public class VoiceVariantCatalogTest {
    @Test
    public void everyReferencedVariantHasABackingFile() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File variantDir = new File(CheckVoiceData.getDataPath(context), "voices/!v");
        assertThat("voices/!v not extracted - run after voice data install",
                variantDir.isDirectory(), is(true));

        Set<String> onDisk = new HashSet<String>();
        File[] files = variantDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    onDisk.add(f.getName());
                }
            }
        }

        Set<String> missing = new HashSet<String>();
        for (String name : VoiceVariantPreference.getReferencedVariantFiles()) {
            if (!onDisk.contains(name)) {
                missing.add(name);
            }
        }
        assertThat("Picker references variant(s) with no matching voices/!v/ file",
                missing, is(empty()));
    }
}
