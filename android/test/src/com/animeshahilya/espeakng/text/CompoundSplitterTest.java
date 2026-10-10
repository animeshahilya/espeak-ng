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

package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.CompoundSplitter;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class CompoundSplitterTest {

    private static Map<String, String> table() {
        final Map<String, String> table = new HashMap<>();
        table.put("aftermath", "after math");
        table.put("afternoon", "after noon");
        table.put("acetylcholine", "acetyl choline");
        return table;
    }

    @Test
    public void testNullEmptyAndNoMatch() {
        assertEquals(null, CompoundSplitter.process(null, table()));
        assertEquals("", CompoundSplitter.process("", table()));
        assertEquals("hello world", CompoundSplitter.process("hello world", table()));
        assertEquals("hello world", CompoundSplitter.process("hello world", null));
        assertEquals("hello world",
                CompoundSplitter.process("hello world", new HashMap<String, String>()));
    }

    @Test
    public void testBasicSplits() {
        assertEquals("after math", CompoundSplitter.process("aftermath", table()));
        assertEquals("the after math of it",
                CompoundSplitter.process("the aftermath of it", table()));
        assertEquals("acetyl choline and after noon",
                CompoundSplitter.process("acetylcholine and afternoon", table()));
    }

    @Test
    public void testCaseHandling() {
        // Capitalized keeps its capital on the first half.
        assertEquals("After noon", CompoundSplitter.process("Afternoon", table()));
        // ALL-CAPS reads as an acronym, never split.
        assertEquals("AFTERNOON", CompoundSplitter.process("AFTERNOON", table()));
    }

    @Test
    public void testGlueAndPunctuation() {
        // Words glued to digits, paths or emails are not compounds.
        assertEquals("abc123aftermath", CompoundSplitter.process("abc123aftermath", table()));
        assertEquals("user_aftermath", CompoundSplitter.process("user_aftermath", table()));
        // Punctuation around the word is fine; possessives stay on the last half.
        assertEquals("(after math!)", CompoundSplitter.process("(aftermath!)", table()));
        assertEquals("after math's end", CompoundSplitter.process("aftermath's end", table()));
    }

    @Test
    public void testSameInstanceWhenNothingMatches() {
        final String text = "nothing to split here";
        assertSame(text, CompoundSplitter.process(text, table()));
    }

    @Test
    public void testParseEntries() throws Exception {
        final Map<String, String> table = new HashMap<>();
        CompoundSplitter.parseEntries(new BufferedReader(new StringReader(
                "# comment\n"
                        + "\n"
                        + "aftermath\tafter math\n"
                        + "badline\n"
                        + "afternoon\tafter noon\n"
                        + "aftermath\tother split\n")),
                table);
        assertEquals(2, table.size());
        assertEquals("after math", table.get("aftermath"));
        assertEquals("after noon", table.get("afternoon"));
    }
}
