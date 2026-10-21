package com.animeshahilya.espeakng.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.animeshahilya.espeakng.SpeechSynthesis;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Speech by app (cards applied through VoiceSettings) and Recent speech. */
public class SpeechByAppTest {

    /** Read-only SharedPreferences over a map. */
    private static SharedPreferences prefs(Map<String, Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    final Object v = values.get((String) args[0]);
                    return v != null ? v : args[1];
                });
    }

    @After
    public void forgetRecentSpeech() {
        RecentSpeech.clear();
    }

    @Test
    public void cardAppliesOnlyToItsApp() {
        final Map<String, Object> values = new HashMap<>();
        values.put(AppCards.key("com.reader", AppCards.SPEED), "150");
        values.put(AppCards.key("com.reader", AppCards.PUNCTUATION), "none");
        values.put(AppCards.key("com.reader", AppCards.VOICES), "espeak");
        values.put(VoiceSettings.PREF_PUNCTUATION_CHARACTERS, ".,");
        final VoiceSettings reader = new VoiceSettings(prefs(values), null);
        reader.setCaller("com.reader");
        assertEquals(1.5f, reader.appFactor(AppCards.SPEED), 0f);
        assertEquals(1f, reader.appFactor(AppCards.PITCH), 0f);
        assertEquals(SpeechSynthesis.PUNCT_NONE, reader.getPunctuationLevel());
        assertEquals(null, reader.getPunctuationCharacters());
        assertTrue(reader.isAppEspeakOnly());

        final VoiceSettings other = new VoiceSettings(prefs(values), null);
        other.setCaller("com.talkback");
        assertEquals(1f, other.appFactor(AppCards.SPEED), 0f);
        assertEquals(".,", other.getPunctuationCharacters());
        assertFalse(other.isAppEspeakOnly());
        other.setCaller(null);
        assertEquals(1f, other.appFactor(AppCards.SPEED), 0f);
    }

    @Test
    public void sharedUidIsNamedSteadily() {
        assertEquals("", AppCards.cardPackage(null));
        assertEquals("com.reader", AppCards.cardPackage(new String[] {"com.reader"}));
        assertEquals("android", AppCards.cardPackage(
                new String[] {"com.google.pixel.camera.services", "android", "com.android.settings"}));
        assertEquals("com.a", AppCards.cardPackage(new String[] {"com.b", "com.a"}));
    }

    @Test
    public void cardValuesAreClampedAndMistypesIgnored() {
        final Map<String, Object> values = new HashMap<>();
        values.put(AppCards.key("a.b_c", AppCards.SPEED), "900");
        values.put(AppCards.key("a.b_c", AppCards.VOLUME), "loud");
        values.put(AppCards.key("a.b_c", AppCards.PUNCTUATION), "most");
        final VoiceSettings s = new VoiceSettings(prefs(values), null);
        s.setCaller("a.b_c");
        assertEquals(2f, s.appFactor(AppCards.SPEED), 0f);
        assertEquals(1f, s.appFactor(AppCards.VOLUME), 0f);
        assertEquals(SpeechSynthesis.PUNCT_SOME, s.getPunctuationLevel());
        assertEquals(VoiceSettings.PUNCTUATION_CHARS_MOST, s.getPunctuationCharacters());
    }

    @Test
    public void recentSpeechKeepsTheNewestOnceAndForgetsOldOnes() {
        RecentSpeech.add("Inbox", 1_000);
        RecentSpeech.add("Your code is 482916", 2_000);
        RecentSpeech.add("Inbox", 3_000);
        List<RecentSpeech.Item> items = RecentSpeech.items(4_000);
        assertEquals(2, items.size());
        assertEquals("Inbox", items.get(0).text);
        assertEquals("Your code is 482916", items.get(1).text);
        for (int i = 0; i < 40; i++) {
            RecentSpeech.add("item " + i, 5_000 + i);
        }
        assertEquals(RecentSpeech.MAX_ITEMS, RecentSpeech.items(6_000).size());
        assertTrue(RecentSpeech.items(5_000 + RecentSpeech.KEEP_MS + 100).isEmpty());
    }

    @Test
    public void whatIsSaidAboutThePanelIsLeftOut() {
        RecentSpeech.add("Meeting at 5 in room 204", 10_000);
        RecentSpeech.markPanelOpened(20_000);
        RecentSpeech.add("Quick settings", 22_000);
        RecentSpeech.add("Recent speech, button", 23_000);
        final List<RecentSpeech.Item> items = RecentSpeech.items(24_000);
        assertEquals(1, items.size());
        assertEquals("Meeting at 5 in room 204", items.get(0).text);
    }

    @Test
    public void onlyPanelSpeechStillShowsRatherThanNothing() {
        RecentSpeech.markPanelOpened(20_000);
        RecentSpeech.add("Quick settings", 22_000);
        assertEquals(1, RecentSpeech.items(24_000).size());
    }
}
