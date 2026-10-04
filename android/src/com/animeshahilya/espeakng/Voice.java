/*
 * Copyright (C) 2012-2013 Reece H. Dunn
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

package com.animeshahilya.espeakng;

import java.util.Locale;
import java.util.MissingResourceException;

import android.speech.tts.TextToSpeech;

public class Voice {
    public final String name;
    public final String identifier;
    public final int gender;
    public final int age;
    public final Locale locale;
    /**
     * For a language only a natural voice speaks (Chhattisgarhi): the eSpeak
     * voice that stands in for it - its rules read the text meanwhile and
     * its language drives text processing. Null for eSpeak's own voices.
     */
    public final Voice standIn;

    public Voice(String name, String identifier, int gender, int age, Locale locale) {
        this(name, identifier, gender, age, locale, null);
    }

    private Voice(String name, String identifier, int gender, int age, Locale locale, Voice standIn) {
        this.name = name;
        this.identifier = identifier;
        this.gender = gender;
        this.age = age;
        this.locale = locale;
        this.standIn = standIn;
    }

    /** A voice for {@code locale} that eSpeak speaks as {@code standIn}. */
    public static Voice naturalOnly(String name, Locale locale, Voice standIn) {
        return new Voice(name, standIn.identifier, standIn.gender, standIn.age, locale, standIn);
    }

    /**
     * Attempts a partial match against a query locale.
     *
     * @param query The locale to match.
     * @return A text-to-speech availability code. One of:
     *         <ul>
     *         <li>{@link TextToSpeech#LANG_NOT_SUPPORTED}
     *         <li>{@link TextToSpeech#LANG_AVAILABLE}
     *         <li>{@link TextToSpeech#LANG_COUNTRY_AVAILABLE}
     *         <li>{@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE}
     *         </ul>
     */
    public int match(Locale query) {
        if (query == null) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (locale == null) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        String thisLang;
        String queryLang;
        try {
            thisLang = locale.getISO3Language();
        } catch (MissingResourceException e) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        try {
            queryLang = query.getISO3Language();
        } catch (MissingResourceException e) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (!thisLang.equals(queryLang)) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }

        String thisCountry;
        String queryCountry;
        try {
            thisCountry = locale.getISO3Country();
        } catch (MissingResourceException e) {
            thisCountry = "";
        }
        try {
            queryCountry = query.getISO3Country();
        } catch (MissingResourceException e) {
            queryCountry = "";
        }
        if (!thisCountry.equals(queryCountry)) {
            return TextToSpeech.LANG_AVAILABLE;
        }

        String thisVariant = locale.getVariant();
        String queryVariant = query.getVariant();
        if (!thisVariant.equals(queryVariant)) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
    }

    @Override
    public String toString() {
        String ret;
        try {
            ret = locale.getISO3Language();
        } catch (MissingResourceException e) {
            ret = locale.getLanguage();
        }
        String country;
        try {
            country = locale.getISO3Country();
        } catch (MissingResourceException e) {
            country = locale.getCountry();
        }
        if (country != null && !country.isEmpty()) {
            ret += '-' + country;
        }
        if (locale.getVariant() != null && !locale.getVariant().isEmpty()) {
            ret += '-' + locale.getVariant();
        }
        return ret;
    }
}