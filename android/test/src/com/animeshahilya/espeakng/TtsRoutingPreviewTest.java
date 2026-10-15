package com.animeshahilya.espeakng;

import com.animeshahilya.espeakng.text.LanguageRuns;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Routing preview (Try your own text): the parts show the plan speech used -
 * one part per language and reader, with the runs underneath. Drives
 * TtsService's own recorder so the merge cannot drift from what speech does.
 */
public class TtsRoutingPreviewTest {
    private static final Class<?> SYNTH_UNIT = synthUnitClass();
    private static final Method RECORD_PLAN = recordPlanMethod();

    private static Class<?> synthUnitClass() {
        try {
            return Class.forName("com.animeshahilya.espeakng.TtsService$SynthUnit");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method recordPlanMethod() {
        try {
            final Method m = TtsService.class.getDeclaredMethod("recordPlan", List.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object unit(String text, Voice voice, int base, String modelKey, String language,
                               List<LanguageRuns.Run> runs) throws Exception {
        final Constructor<?> c = SYNTH_UNIT.getDeclaredConstructor(String.class, Voice.class, int.class,
                com.animeshahilya.espeakng.piper.PiperModel.class, String.class, String.class,
                List.class, String.class);
        c.setAccessible(true);
        return c.newInstance(text, voice, base, null, modelKey, language, runs, null);
    }

    private static Object espeakUnit(String text, Voice voice, int base, String language,
                                     LanguageRuns.Run... runs) throws Exception {
        return unit(text, voice, base, null, language, new ArrayList<>(Arrays.asList(runs)));
    }

    private static List<TtsService.PlanPart> record(List<Object> units) throws Exception {
        TtsService.takeShownPlan();
        RECORD_PLAN.invoke(null, units);
        return TtsService.takeShownPlan();
    }

    private static final Voice HINDI = new Voice("Hindi", "hin", 0, 0, new Locale("hi"));
    private static final Voice ENGLISH = new Voice("English", "en", 0, 0, Locale.US);

    @Test
    public void neighboursWithTheSameReaderAreOnePartAndKeepTheirRuns() throws Exception {
        final List<Object> units = new ArrayList<>();
        units.add(espeakUnit("आज मेरी ", HINDI, 0, "hin",
                new LanguageRuns.Run(0, "आज मेरी ", "hin")));
        units.add(espeakUnit("meeting ", ENGLISH, 8, "eng",
                new LanguageRuns.Run(0, "meeting ", "eng")));
        units.add(espeakUnit("कल है।", HINDI, 16, "hin",
                new LanguageRuns.Run(0, "कल है।", "hin")));
        final List<TtsService.PlanPart> plan = record(units);
        assertEquals(3, plan.size());
        assertEquals("आज मेरी", plan.get(0).text);
        assertEquals("hin", plan.get(0).language);
        assertNull(plan.get(0).naturalKey);
        assertEquals("meeting", plan.get(1).text);
        assertEquals("eng", plan.get(1).language);
        assertEquals("कल है।", plan.get(2).text);
        // The parts are views over the planned runs, not re-computed text.
        assertEquals(1, plan.get(0).runs.size());
        assertEquals("आज मेरी ", plan.get(0).runs.get(0).text);
        assertEquals(1, plan.get(1).runs.size());
        assertEquals("meeting ", plan.get(1).runs.get(0).text);
    }

    @Test
    public void splitUnitsWithTheSameReaderMergeAcross() throws Exception {
        // Two watchdog chunks of one Hindi sentence: still one preview part.
        final List<Object> units = new ArrayList<>();
        units.add(espeakUnit("आज मेरी ", HINDI, 0, "hin",
                new LanguageRuns.Run(0, "आज ", "hin"), new LanguageRuns.Run(4, "मेरी ", "hin")));
        units.add(espeakUnit("कल है।", HINDI, 8, "hin",
                new LanguageRuns.Run(0, "कल है।", "hin")));
        final List<TtsService.PlanPart> plan = record(units);
        assertEquals(1, plan.size());
        assertEquals("आज मेरी कल है।", plan.get(0).text);
        assertEquals(3, plan.get(0).runs.size());
    }

    @Test
    public void aVoiceStillLoadingShowsEspeak() throws Exception {
        // modelKey null: chosen but not loaded, eSpeak reads it this play.
        final List<Object> units = new ArrayList<>();
        units.add(unit("meeting", ENGLISH, 0, null, "eng",
                Collections.singletonList(new LanguageRuns.Run(0, "meeting", "eng"))));
        final List<TtsService.PlanPart> plan = record(units);
        assertEquals(1, plan.size());
        assertNull(plan.get(0).naturalKey);
        assertEquals("eng", plan.get(0).language);
    }

    @Test
    public void digitRunsMapToTheUnitsLanguage() throws Exception {
        // "zxx" (no linguistic content): read in the request voice's language.
        final List<Object> units = new ArrayList<>();
        units.add(espeakUnit("10:30", HINDI, 6, "hin",
                new LanguageRuns.Run(0, "10:30", "zxx")));
        final List<TtsService.PlanPart> plan = record(units);
        assertEquals(1, plan.size());
        assertEquals("hin", plan.get(0).language);
        assertEquals("10:30", plan.get(0).text);
    }

    @Test
    public void blankRunsAreSkipped() throws Exception {
        final List<Object> units = new ArrayList<>();
        units.add(espeakUnit("   ", HINDI, 0, "hin",
                new LanguageRuns.Run(0, "   ", "hin")));
        assertTrue(record(units).isEmpty());
    }
}
