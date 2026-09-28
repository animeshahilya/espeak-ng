package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** NVDA .dic import/export (UserDictionaryManager.parseDicLine / toDicLine). */
public class DicFormatTest {

    /** NVDA's fifth column is the match type: 0 anywhere, 1 regex, 2 whole word. */
    @Test
    public void readsNvdaMatchTypes() {
        final UserDictionary anywhere = UserDictionaryManager.parseDicLine("colour\tcolor\t\t0\t0");
        assertFalse(anywhere.isRegex());
        assertFalse(anywhere.isWholeWord());
        final UserDictionary regex = UserDictionaryManager.parseDicLine("a+\tb\t\t0\t1");
        assertTrue(regex.isRegex());
        final UserDictionary word = UserDictionaryManager.parseDicLine("gif\tjif\t\t1\t2");
        assertTrue(word.isWholeWord());
        assertTrue(word.isCaseSensitive());
        // No type column (and plain "word=replacement" lists): whole word, as before.
        assertTrue(UserDictionaryManager.parseDicLine("gif\tjif").isWholeWord());
        assertTrue(UserDictionaryManager.parseDicLine("gif=jif").isWholeWord());
    }

    /** Everything a rule has survives export and import, including what NVDA has no column for. */
    @Test
    public void roundTripsEveryField() {
        final UserDictionary rule = new UserDictionary("s", "ess", true, false, false, "hi",
                UserDictionary.CATEGORY_CHARACTER, "E s");
        final String line = UserDictionaryManager.toDicLine(rule);
        assertEquals(5, line.trim().split("\t").length);
        final UserDictionary back = UserDictionaryManager.parseDicLine(line.trim());
        assertEquals("s", back.getPattern());
        assertEquals("ess", back.getReplacement());
        assertTrue(back.isCaseSensitive());
        assertFalse(back.isRegex());
        assertFalse(back.isWholeWord());
        assertEquals("hi", back.getLanguage());
        assertEquals(UserDictionary.CATEGORY_CHARACTER, back.getCategory());
        assertEquals("E s", back.getPhonemes());
    }

    @Test
    public void plainRuleExportsPlainNvdaLine() {
        final String line = UserDictionaryManager.toDicLine(
                new UserDictionary("gif", "jif", false, false, true));
        assertEquals("gif\tjif\teSpeakAndroid\t0\t2\n", line);
    }

    @Test
    public void findsHighestGroupReference() {
        assertEquals(0, UserDictionaryScreen.highestGroupReference("plain text"));
        assertEquals(2, UserDictionaryScreen.highestGroupReference("$1 and $2"));
        assertEquals(0, UserDictionaryScreen.highestGroupReference("costs \\$5"));
        // Java reads "$10" as group 1 then "0" when there is one group.
        assertEquals(1, UserDictionaryScreen.highestGroupReference("$10"));
        assertEquals(0, UserDictionaryScreen.highestGroupReference("ends with $"));
    }

    /** A tab or line break inside a rule must not split the exported line. */
    @Test
    public void tabsInsideFieldsDoNotBreakTheLine() {
        final String line = UserDictionaryManager.toDicLine(
                new UserDictionary("a\tb", "c\nd", false, false, true));
        assertEquals(5, line.trim().split("\t").length);
        assertEquals(1, line.split("\n").length);
    }
}
