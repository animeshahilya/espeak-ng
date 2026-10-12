package com.animeshahilya.espeakng.text;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReadInTest {
    @Test
    public void marksNameTheLanguageAndStripAway() {
        final String marked = "my " + ReadIn.open("eng") + "meeting" + ReadIn.close() + " today";
        assertEquals("eng", ReadIn.languageAt(marked, 3));
        assertEquals(ReadIn.OPEN_LENGTH, ReadIn.open("eng").length());
        assertEquals("my meeting today", ReadIn.strip(marked));
        assertNull(ReadIn.languageAt(marked, 0));
        assertNull("not ISO 639-2", ReadIn.open("en"));
        assertNull(ReadIn.open("EN1"));
    }

    @Test
    public void dictionaryEntryWrapsWhatItReads() {
        final UserDictionary keep = new UserDictionary("meeting", "", false, false, true);
        assertEquals("deletes, as before", "my  today", keep.apply("my meeting today"));
        keep.setReadIn("eng");
        assertEquals("my " + ReadIn.open("eng") + "meeting" + ReadIn.close() + " today",
                keep.apply("my meeting today"));

        final UserDictionary respelled = new UserDictionary("gif", "jif", false, false, true);
        respelled.setReadIn("fra");
        assertEquals(ReadIn.open("fra") + "jif" + ReadIn.close() + "!", respelled.apply("gif!"));

        final UserDictionary none = new UserDictionary("gif", "jif", false, false, true);
        none.setReadIn("bad");
        none.setReadIn("");
        assertEquals("jif", none.apply("gif"));
    }

    @Test
    public void readInSurvivesSavingAndExport() throws JSONException {
        final UserDictionary rule = new UserDictionary("Paris", "", false, false, true);
        rule.setReadIn("fra");
        assertEquals("fra", UserDictionary.fromJson(rule.toJson()).getReadIn());
        final UserDictionary back = UserDictionaryManager.parseDicLine(UserDictionaryManager.toDicLine(rule).trim());
        assertEquals("fra", back.getReadIn());
        assertTrue(back.apply("Paris").startsWith(ReadIn.open("fra")));
        // Saved before the field existed: the usual voice.
        assertEquals("", UserDictionary.fromJson(new UserDictionary("a", "b", false, false, true).toJson()).getReadIn());
    }
}
