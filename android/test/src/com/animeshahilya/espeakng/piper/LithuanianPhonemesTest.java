package com.animeshahilya.espeakng.piper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** The Java port gives piper1-gpl's output for the same per-word eSpeak IPA. */
public class LithuanianPhonemesTest {
    private static LithuanianPhonemes lt;

    @BeforeClass
    public static void load() throws Exception {
        try (BufferedReader k = reader("lt_kirciai.tsv"); BufferedReader r = reader("lt_raides.tsv");
             BufferedReader v = reader("lt_kreipiniai.tsv")) {
            lt = LithuanianPhonemes.load(k, r, v);
        }
    }

    private static BufferedReader reader(String name) throws Exception {
        return Files.newBufferedReader(Paths.get("data", "lithuanian", name), StandardCharsets.UTF_8);
    }

    @Test
    public void matchesPiperOnRealText() throws Exception {
        final JSONObject fixture = new JSONObject(new String(Files.readAllBytes(
                Paths.get("test", "resources", "lithuanian_cases.json")), StandardCharsets.UTF_8));
        final JSONObject words = fixture.getJSONObject("words");
        final JSONArray cases = fixture.getJSONArray("cases");
        final List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.length(); i++) {
            final String text = cases.getJSONArray(i).getString(0);
            final String want = cases.getJSONArray(i).getString(1);
            final String got = lt.sentence(text, w -> words.optString(w, "?" + w));
            if (!got.equals(want)) {
                failures.add(text + "\n  got  " + got + "\n  want " + want);
            }
        }
        assertTrue(cases.length() > 60);
        assertEquals(Collections.emptyList(), failures);
    }

    @Test
    public void accentGoesBeforeTheSyllable() {
        assertEquals("daˌbar", LithuanianPhonemes.placeAccent("dabar", 1, LithuanianPhonemes.CIRCUMFLEX));
        assertEquals("ˈkalbeedamas", LithuanianPhonemes.placeAccent("kalbeedamas", 0, LithuanianPhonemes.ACUTE));
        assertEquals(2, LithuanianPhonemes.vowelGroups("maistas").size());
        assertEquals(3, LithuanianPhonemes.vowelGroups("moːtʲerʲis").size());
    }

    @Test
    public void lettersAndVocatives() {
        assertEquals("ˈel̩", lt.letterIpa("el", ""));
        assertNull(lt.letterIpa("el", "paštas")); // the abbreviation keeps its expansion
        assertEquals("ˈmaːːma", LithuanianPhonemes.vocativeAccent("mama"));
        assertEquals("ˌl̩abas, ˈmaːːma.", lt.sentence("Labas, mama.",
                w -> "labas".equals(w.toLowerCase()) ? "l̩ˈabas" : "mˈama"));
    }
}
