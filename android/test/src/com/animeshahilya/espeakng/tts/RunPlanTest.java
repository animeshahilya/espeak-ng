package com.animeshahilya.espeakng.tts;

import com.animeshahilya.espeakng.text.LanguageRuns;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Who reads which part of a mixed-language text (both of TtsService's paths). */
public class RunPlanTest {

    /** Voices as strings: natural "N:key", eSpeak "E:voice". */
    private static final class Fake implements RunPlan.Readers<String> {
        final Map<String, String> chosen = new HashMap<>();
        final Set<String> loaded = new HashSet<>();
        final List<String> preloaded = new ArrayList<>();

        @Override public String naturalFor(String language) { return chosen.get(language); }
        @Override public boolean isLoaded(String key) { return loaded.contains(key); }
        @Override public void preload(String key) { preloaded.add(key); }
        @Override public String espeakVoiceFor(String language) { return "espeak-" + language; }
        final Map<String, String> mixed = new HashMap<>();
        @Override public String whenMixed(String language) {
            return mixed.containsKey(language) ? mixed.get(language) : RunPlan.MIXED_DEFAULT;
        }
    }

    private static List<LanguageRuns.Run> runs(String... langAndText) {
        final List<LanguageRuns.Run> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < langAndText.length; i += 2) {
            out.add(new LanguageRuns.Run(start, langAndText[i + 1], langAndText[i]));
            start += langAndText[i + 1].codePointCount(0, langAndText[i + 1].length());
        }
        return out;
    }

    private static String describe(List<RunPlan.Segment<String>> plan) {
        final StringBuilder sb = new StringBuilder();
        for (RunPlan.Segment<String> s : plan) {
            sb.append(s.isNatural() ? "N:" + s.naturalKey : "E:" + s.espeakVoice)
                    .append('@').append(s.start).append('[').append(s.text).append("] ");
        }
        return sb.toString().trim();
    }

    @Test
    public void ownNaturalVoiceLoadedReadsEverything() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        f.loaded.add("priya");
        final List<RunPlan.Segment<String>> plan = RunPlan.plan(runs("hin", "नमस्ते दुनिया"), "hin", f, true, true);
        assertEquals("N:priya@0[नमस्ते दुनिया]", describe(plan));
        assertTrue(RunPlan.allNatural(plan));
    }

    @Test
    public void ownNaturalVoiceNotLoadedLeavesEspeakAndIsNotPreloadedHere() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        final List<RunPlan.Segment<String>> plan = RunPlan.plan(runs("hin", "नमस्ते"), "hin", f, true, true);
        assertEquals("E:espeak-hin@0[नमस्ते]", describe(plan));
        assertFalse(RunPlan.allNatural(plan));
        // The request's own voice is preloaded by the caller, before planning.
        assertTrue(f.preloaded.isEmpty());
    }

    @Test
    public void hindiInsideEnglishGoesToTheHindiVoice() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        f.loaded.add("priya");
        final List<RunPlan.Segment<String>> plan = RunPlan.plan(
                runs("eng", "I said ", "hin", "नमस्ते ", "eng", "to him."), "eng", f, true, true);
        assertEquals("E:espeak-eng@0[I said ] N:priya@7[नमस्ते ] E:espeak-eng@14[to him.]", describe(plan));
    }

    @Test
    public void unloadedVoiceOfAnotherLanguageStartsLoadingOnlyWhenAllowed() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        RunPlan.plan(runs("eng", "a ", "hin", "क ख"), "eng", f, true, true);
        assertEquals(Arrays.asList("priya"), f.preloaded);

        final Fake g = new Fake();
        g.chosen.put("hin", "priya");
        final List<RunPlan.Segment<String>> plan = RunPlan.plan(runs("eng", "a ", "hin", "क ख"), "eng", g, true, false);
        assertTrue(g.preloaded.isEmpty());
        assertEquals("E:espeak-eng@0[a ] E:espeak-hin@2[क ख]", describe(plan));
    }

    @Test
    public void loneLetterInAnotherLanguageStaysWithEspeak() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        f.chosen.put("eng", "amy");
        f.loaded.add("priya");
        f.loaded.add("amy");
        final List<LanguageRuns.Run> text = runs("hin", "क्ष", "eng", ", deleted");
        assertEquals("E:espeak-hin@0[क्ष] N:amy@3[, deleted]",
                describe(RunPlan.plan(text, "eng", f, true, true)));
        // Setting off ("eSpeak for single letters"): the natural voice reads it.
        assertEquals("N:priya@0[क्ष] N:amy@3[, deleted]",
                describe(RunPlan.plan(text, "eng", f, false, true)));
    }

    @Test
    public void neighbouringRunsWithOneReaderAreOneSegment() {
        final Fake f = new Fake();
        // Bhojpuri and Hindi both read by the same voice; digits by eSpeak.
        f.chosen.put("hin", "rasa");
        f.chosen.put("bho", "rasa");
        f.loaded.add("rasa");
        final List<RunPlan.Segment<String>> plan = RunPlan.plan(
                runs("hin", "एक ", "bho", "दूसरा ", "zxx", "10", "hin", " तीन"), "hin", f, true, true);
        assertEquals("N:rasa@0[एक दूसरा ] E:espeak-zxx@9[10] N:rasa@11[ तीन]", describe(plan));
        assertFalse(RunPlan.allNatural(plan));
    }

    @Test
    public void loneLetterRecognition() {
        assertTrue(RunPlan.isLoneLetter("क्ष"));
        assertTrue(RunPlan.isLoneLetter(" a, "));
        assertTrue(RunPlan.isLoneLetter("कि"));
        assertFalse(RunPlan.isLoneLetter("ab"));
        assertFalse(RunPlan.isLoneLetter(" , "));
        assertFalse(RunPlan.isLoneLetter(""));
    }

    /** English inside Hindi, with natural voices for both: the card decides who reads the English. */
    private static Fake hindiAndEnglish() {
        final Fake f = new Fake();
        f.chosen.put("hin", "priya");
        f.chosen.put("eng", "amy");
        f.chosen.put("eng2", "rahul");
        f.loaded.add("priya");
        f.loaded.add("amy");
        f.loaded.add("rahul");
        return f;
    }

    private static final List<LanguageRuns.Run> MIXED = runs("hin", "मेरा ", "eng", "meeting ", "hin", "है ना");

    @Test
    public void mixedDefaultIsTheLanguagesOwnVoice() {
        assertEquals("N:priya@0[मेरा ] N:amy@5[meeting ] N:priya@13[है ना]",
                describe(RunPlan.plan(MIXED, "hin", hindiAndEnglish(), true, true)));
    }

    @Test
    public void mixedEspeakKeepsTheLanguageWithEspeak() {
        final Fake f = hindiAndEnglish();
        f.mixed.put("eng", RunPlan.MIXED_ESPEAK);
        assertEquals("N:priya@0[मेरा ] E:espeak-eng@5[meeting ] N:priya@13[है ना]",
                describe(RunPlan.plan(MIXED, "hin", f, true, true)));
        // Its own text is untouched: English requests still go to Amy.
        assertEquals("N:amy@0[hello]", describe(RunPlan.plan(runs("eng", "hello"), "eng", f, true, true)));
    }

    @Test
    public void mixedSurroundingIsReadByTheRequestsVoice() {
        final Fake f = hindiAndEnglish();
        f.mixed.put("eng", RunPlan.MIXED_SURROUNDING);
        assertEquals("N:priya@0[मेरा meeting है ना]", describe(RunPlan.plan(MIXED, "hin", f, true, true)));
        // Its natural voice not loaded yet: eSpeak in the request's language, still one piece.
        f.loaded.remove("priya");
        assertEquals("E:espeak-hin@0[मेरा meeting है ना]", describe(RunPlan.plan(MIXED, "hin", f, true, true)));
    }

    @Test
    public void mixedCanNameAnotherVoice() {
        final Fake f = hindiAndEnglish();
        f.mixed.put("eng", "rahul");
        assertEquals("N:priya@0[मेरा ] N:rahul@5[meeting ] N:priya@13[है ना]",
                describe(RunPlan.plan(MIXED, "hin", f, true, true)));
        // Not loaded: eSpeak reads it meanwhile, and the voice starts loading.
        f.loaded.remove("rahul");
        assertEquals("N:priya@0[मेरा ] E:espeak-eng@5[meeting ] N:priya@13[है ना]",
                describe(RunPlan.plan(MIXED, "hin", f, true, true)));
        assertTrue(f.preloaded.contains("rahul"));
    }
}
