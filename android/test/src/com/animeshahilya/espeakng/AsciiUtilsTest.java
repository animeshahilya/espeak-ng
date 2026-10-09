package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class AsciiUtilsTest {

    @Test
    public void testIsAsciiLetter() {
        assertTrue(AsciiUtils.isAsciiLetter('a'));
        assertTrue(AsciiUtils.isAsciiLetter('z'));
        assertTrue(AsciiUtils.isAsciiLetter('A'));
        assertTrue(AsciiUtils.isAsciiLetter('Z'));
        assertFalse(AsciiUtils.isAsciiLetter('0'));
        assertFalse(AsciiUtils.isAsciiLetter('9'));
        assertFalse(AsciiUtils.isAsciiLetter('@'));
        assertFalse(AsciiUtils.isAsciiLetter(' '));
        assertFalse(AsciiUtils.isAsciiLetter('é'));
        assertFalse(AsciiUtils.isAsciiLetter('क'));
    }

    @Test
    public void testIsAsciiDigit() {
        for (char c = '0'; c <= '9'; c++) {
            assertTrue(AsciiUtils.isAsciiDigit(c));
        }
        assertFalse(AsciiUtils.isAsciiDigit('a'));
        assertFalse(AsciiUtils.isAsciiDigit('/'));
        assertFalse(AsciiUtils.isAsciiDigit(':'));
        assertFalse(AsciiUtils.isAsciiDigit(' '));
        assertFalse(AsciiUtils.isAsciiDigit('२')); // Indic digit is non-ASCII
    }

    @Test
    public void testHasAsciiLetter() {
        assertFalse(AsciiUtils.hasAsciiLetter(null));
        assertFalse(AsciiUtils.hasAsciiLetter(""));
        assertFalse(AsciiUtils.hasAsciiLetter("12345 !@#"));
        assertTrue(AsciiUtils.hasAsciiLetter("123a45"));
        assertTrue(AsciiUtils.hasAsciiLetter("Z"));
        assertTrue(AsciiUtils.hasAsciiLetter("Hello world"));
        assertFalse(AsciiUtils.hasAsciiLetter("नमस्ते"));
    }

    @Test
    public void testHasDigit() {
        assertFalse(AsciiUtils.hasDigit(null));
        assertFalse(AsciiUtils.hasDigit(""));
        assertFalse(AsciiUtils.hasDigit("abc def !@#"));
        assertTrue(AsciiUtils.hasDigit("abc 123 def"));
        assertTrue(AsciiUtils.hasDigit("0"));
        // Unicode digits: Devanagari ०-९
        assertTrue(AsciiUtils.hasDigit("मूल्य ₹ ५०"));
        assertTrue(AsciiUtils.hasDigit("সময় ১০:৩০"));
    }

    @Test
    public void testHasAsciiDigit() {
        assertFalse(AsciiUtils.hasAsciiDigit(null));
        assertFalse(AsciiUtils.hasAsciiDigit(""));
        assertFalse(AsciiUtils.hasAsciiDigit("abc def !@#"));
        assertFalse(AsciiUtils.hasAsciiDigit("मूल्य ₹ ५०")); // Indic digit is not ASCII digit
        assertTrue(AsciiUtils.hasAsciiDigit("abc 123 def"));
        assertTrue(AsciiUtils.hasAsciiDigit("0"));
        assertTrue(AsciiUtils.hasAsciiDigit("9"));
    }

    @Test
    public void testToAsciiLowerCase() {
        assertNull(AsciiUtils.toAsciiLowerCase(null));
        assertEquals("", AsciiUtils.toAsciiLowerCase(""));
        assertEquals("hello world", AsciiUtils.toAsciiLowerCase("HELLO WORLD"));
        assertEquals("hello world 123", AsciiUtils.toAsciiLowerCase("Hello World 123"));
        assertEquals("en-us", AsciiUtils.toAsciiLowerCase("en-US"));
    }

    @Test
    public void testToAsciiLowerCaseRange() {
        assertNull(AsciiUtils.toAsciiLowerCase(null, 0, 0));
        assertEquals("", AsciiUtils.toAsciiLowerCase("Hello", 2, 2));
        assertEquals("hel", AsciiUtils.toAsciiLowerCase("HELLO", 0, 3));
        assertEquals("lo", AsciiUtils.toAsciiLowerCase("HELLO", 3, 5));
        assertEquals("world", AsciiUtils.toAsciiLowerCase("say World now", 4, 9));
        assertEquals("already", AsciiUtils.toAsciiLowerCase("already lower", 0, 7));
    }

    @Test
    public void testIsWhitespace() {
        assertTrue(AsciiUtils.isWhitespace(' '));
        assertTrue(AsciiUtils.isWhitespace('\t'));
        assertTrue(AsciiUtils.isWhitespace('\n'));
        assertTrue(AsciiUtils.isWhitespace('\r'));
        assertTrue(AsciiUtils.isWhitespace('\f'));
        assertFalse(AsciiUtils.isWhitespace('a'));
        assertFalse(AsciiUtils.isWhitespace('.'));
    }

    @Test
    public void testToAsciiLowerCaseChar() {
        assertEquals('a', AsciiUtils.toAsciiLowerCase('A'));
        assertEquals('z', AsciiUtils.toAsciiLowerCase('Z'));
        assertEquals('a', AsciiUtils.toAsciiLowerCase('a'));
        assertEquals('1', AsciiUtils.toAsciiLowerCase('1'));
        assertEquals(' ', AsciiUtils.toAsciiLowerCase(' '));
        assertEquals('é', AsciiUtils.toAsciiLowerCase('é'));
    }

    @Test
    public void testEndsWithIgnoreCase() {
        assertFalse(AsciiUtils.endsWithIgnoreCase(null, ".zip"));
        assertFalse(AsciiUtils.endsWithIgnoreCase("test.zip", null));
        assertFalse(AsciiUtils.endsWithIgnoreCase("zip", ".zip"));
        assertTrue(AsciiUtils.endsWithIgnoreCase("archive.zip", ".zip"));
        assertTrue(AsciiUtils.endsWithIgnoreCase("ARCHIVE.ZIP", ".zip"));
        assertTrue(AsciiUtils.endsWithIgnoreCase("archive.Zip", ".ZIP"));
        assertFalse(AsciiUtils.endsWithIgnoreCase("archive.tar.gz", ".zip"));
    }

    @Test
    public void testBaseLanguage() {
        assertEquals("", AsciiUtils.baseLanguage(null));
        assertEquals("", AsciiUtils.baseLanguage(""));
        assertEquals("", AsciiUtils.baseLanguage("   "));
        assertEquals("en", AsciiUtils.baseLanguage("en-US"));
        assertEquals("en", AsciiUtils.baseLanguage(" en-US "));
        assertEquals("en", AsciiUtils.baseLanguage("en_GB"));
        assertEquals("hi", AsciiUtils.baseLanguage("hi-IN"));
        assertEquals("hi", AsciiUtils.baseLanguage("hi"));
        assertEquals("zh", AsciiUtils.baseLanguage("cmn"));
        assertEquals("zh", AsciiUtils.baseLanguage("cmn-Hans"));
        assertEquals("zh", AsciiUtils.baseLanguage("cmn_CN"));
    }

    @Test
    public void testNormalizeLocaleTag() {
        assertEquals("", AsciiUtils.normalizeLocaleTag(null));
        assertEquals("", AsciiUtils.normalizeLocaleTag(""));
        assertEquals("en_us", AsciiUtils.normalizeLocaleTag("en-US"));
        assertEquals("pt_br", AsciiUtils.normalizeLocaleTag("pt-BR"));
        assertEquals("en_gb", AsciiUtils.normalizeLocaleTag("en_gb"));
    }
}
