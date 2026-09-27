package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextOffsetMapTest {

    @Test
    public void testIdentityMapping() {
        String s = "Hello world";
        TextOffsetMap map = TextOffsetMap.diff(s, s);
        for (int i = 0; i <= s.length(); i++) {
            assertEquals(i, map.toPrevious(i));
        }
    }

    @Test
    public void testInsertion() {
        String before = "Hello world";
        String after = "Hello brave world";
        TextOffsetMap map = TextOffsetMap.diff(before, after);

        // Before inserted word "brave "
        assertEquals(0, map.toPrevious(0));
        assertEquals(6, map.toPrevious(6));

        // After inserted word: "world" in after starts at index 12, in before starts at 6
        int worldAfterStart = after.indexOf("world");
        int worldBeforeStart = before.indexOf("world");
        assertEquals(worldBeforeStart, map.toPrevious(worldAfterStart));
        assertEquals(before.length(), map.toPrevious(after.length()));
    }

    @Test
    public void testDeletion() {
        String before = "Hello brave world";
        String after = "Hello world";
        TextOffsetMap map = TextOffsetMap.diff(before, after);

        assertEquals(0, map.toPrevious(0));
        assertEquals(5, map.toPrevious(5)); // end of 'Hello'
        assertEquals(12, map.toPrevious(6)); // 'world' in after starts at 6, maps to 12 in before
        assertEquals(before.length(), map.toPrevious(after.length()));
    }

    @Test
    public void testMultipleDisjointEdits() {
        String before = "foo and bar and baz";
        String after = "FOO and BAR and BAZ";
        TextOffsetMap map = TextOffsetMap.diff(before, after);

        // Common parts " and " should map to same offsets
        int and1Before = before.indexOf(" and ");
        int and1After = after.indexOf(" and ");
        assertEquals(and1Before, map.toPrevious(and1After));

        int and2Before = before.lastIndexOf(" and ");
        int and2After = after.lastIndexOf(" and ");
        assertEquals(and2Before, map.toPrevious(and2After));

        assertEquals(0, map.toPrevious(0));
        assertEquals(before.length(), map.toPrevious(after.length()));
    }

    @Test
    public void testComposition() {
        String step0 = "dog";
        String step1 = "a brown dog";
        String step2 = "a very brown dog";

        TextOffsetMap map1 = TextOffsetMap.diff(step0, step1); // step1 -> step0
        TextOffsetMap map2 = TextOffsetMap.diff(step1, step2); // step2 -> step1

        TextOffsetMap composed = map2.composeWith(map1); // step2 -> step0

        // In step2, "dog" is at index 13.
        // In step1, "dog" was at index 8.
        // In step0, "dog" was at index 0.
        int dogStep2 = step2.indexOf("dog");
        assertEquals(step1.indexOf("dog"), map2.toPrevious(dogStep2));
        assertEquals(step0.indexOf("dog"), composed.toPrevious(dogStep2));
    }

    @Test
    public void testBoundarySafety() {
        TextOffsetMap map = TextOffsetMap.diff("abc", "abcdef");
        assertEquals(0, map.toPrevious(-5));
        assertEquals(3, map.toPrevious(100));

        TextOffsetMap emptyMap = TextOffsetMap.diff("", "");
        assertEquals(0, emptyMap.toPrevious(0));
        assertEquals(0, emptyMap.toPrevious(10));
    }

    @Test
    public void testLargeSliceFallback() {
        // Construct two large strings that trigger the dimension bound fallback (> 2000 chars)
        StringBuilder b1 = new StringBuilder("prefix-");
        StringBuilder b2 = new StringBuilder("prefix-");
        for (int i = 0; i < 2100; i++) {
            b1.append('a');
            b2.append('b');
        }
        b1.append("-suffix");
        b2.append("-suffix");

        TextOffsetMap map = TextOffsetMap.diff(b1.toString(), b2.toString());
        assertNotNull(map);
        assertEquals(0, map.toPrevious(0));
        assertEquals(b1.length(), map.toPrevious(b2.length()));
    }
}
