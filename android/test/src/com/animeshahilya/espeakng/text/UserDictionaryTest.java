package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.UserDictionary;
import com.animeshahilya.espeakng.ui.UserDictionaryScreen;

import org.junit.Test;
import static org.junit.Assert.*;

public class UserDictionaryTest {

    @Test
    public void testLiteralReplacementCaseInsensitive() {
        UserDictionary rule = new UserDictionary("quick", "fast", false, false, false);
        assertTrue(rule.isValid());
        assertEquals("The fast brown fox", rule.apply("The quick brown fox"));
        assertEquals("The fast brown fox", rule.apply("The Quick brown fox"));
        assertEquals("The fast brown fox", rule.apply("The QUICK brown fox"));
    }

    @Test
    public void testLiteralReplacementCaseSensitive() {
        UserDictionary rule = new UserDictionary("Quick", "Fast", true, false, false);
        assertTrue(rule.isValid());
        assertEquals("The Fast brown fox", rule.apply("The Quick brown fox"));
        assertEquals("The quick brown fox", rule.apply("The quick brown fox"));
    }

    @Test
    public void testWholeWordMatching() {
        UserDictionary rule = new UserDictionary("cat", "feline", false, false, true);
        assertTrue(rule.isValid());
        assertEquals("The feline sat", rule.apply("The cat sat"));
        assertEquals("The category was cats", rule.apply("The category was cats"));
    }

    @Test
    public void testWholeWordUnicode() {
        // Indic script word boundary test: \p{L}/\p{N}
        UserDictionary rule = new UserDictionary("भारत", "India", false, false, true);
        assertTrue(rule.isValid());
        assertEquals("यह India का है", rule.apply("यह भारत का है"));

        // Arabic script word boundary test: \p{L}/\p{N}
        UserDictionary arRule = new UserDictionary("مرحبا", "أهلاً", false, false, true);
        assertTrue(arRule.isValid());
        assertEquals("أهلاً بالعالم", arRule.apply("مرحبا بالعالم"));
    }

    @Test
    public void testRegexCapturingGroups() {
        UserDictionary rule = new UserDictionary("(\\d+)\\s*kg", "$1 kilograms", false, true, false);
        assertTrue(rule.isValid());
        assertEquals("Weight: 10 kilograms total", rule.apply("Weight: 10 kg total"));
    }

    @Test
    public void testReDoSProtectionNestedQuantifiers() {
        // (a+)+ should be rejected to protect against catastrophic backtracking
        UserDictionary rule1 = new UserDictionary("(a+)+", "b", false, true, false);
        assertFalse(rule1.isValid());

        // (a*)* should be rejected
        UserDictionary rule2 = new UserDictionary("(a*)*", "b", false, true, false);
        assertFalse(rule2.isValid());

        // ((a)+)+ should be rejected
        UserDictionary rule3 = new UserDictionary("((a)+)+", "b", false, true, false);
        assertFalse(rule3.isValid());
    }

    @Test
    public void testSafeQuantifiersAllowed() {
        // (\d+) and ([a-z]+) are safe single-level quantifiers and MUST be valid
        UserDictionary ruleDigits = new UserDictionary("(\\d+)", "num", false, true, false);
        assertTrue(ruleDigits.isValid());

        UserDictionary ruleLetters = new UserDictionary("([a-zA-Z]+)", "words", false, true, false);
        assertTrue(ruleLetters.isValid());

        UserDictionary ruleAlt = new UserDictionary("(cat|dog)+", "pet", false, true, false);
        assertTrue(ruleAlt.isValid());
    }

    @Test
    public void testLanguageScoping() {
        UserDictionary universal = new UserDictionary("hello", "hi", false, false, false, "");
        assertTrue(universal.appliesToLanguage("en"));
        assertTrue(universal.appliesToLanguage("en-in"));
        assertTrue(universal.appliesToLanguage("hi"));

        UserDictionary englishOnly = new UserDictionary("hello", "hi", false, false, false, "en");
        assertTrue(englishOnly.appliesToLanguage("en"));
        assertTrue(englishOnly.appliesToLanguage("en-US"));
        assertTrue(englishOnly.appliesToLanguage("en-IN"));
        assertTrue(englishOnly.appliesToLanguage("en_US"));
        assertTrue(englishOnly.appliesToLanguage("en_IN"));
        assertFalse(englishOnly.appliesToLanguage("hi"));
        assertFalse(englishOnly.appliesToLanguage(null));

        UserDictionary enInOnly = new UserDictionary("crore", "ten million", false, false, false, "en-in");
        assertTrue(enInOnly.appliesToLanguage("en-in"));
        assertTrue(enInOnly.appliesToLanguage("en_IN"));
        assertFalse(enInOnly.appliesToLanguage("en-us"));
        assertFalse(enInOnly.appliesToLanguage("en_US"));
        assertFalse(enInOnly.appliesToLanguage("en"));

        UserDictionary arOnly = new UserDictionary("كتاب", "دفتر", false, false, false, "ar");
        assertTrue(arOnly.appliesToLanguage("ar"));
        assertTrue(arOnly.appliesToLanguage("ar-SA"));
        assertTrue(arOnly.appliesToLanguage("ar_AE"));
        assertFalse(arOnly.appliesToLanguage("en"));
    }

    @Test
    public void testPhonemeOverrideValidation() {
        // Valid phoneme string
        UserDictionary validPhonemes = new UserDictionary("test", "test", false, false, false, "en", UserDictionary.CATEGORY_MAIN, "t'Est");
        assertTrue(validPhonemes.hasPhonemeOverride());
        assertEquals("[[t'Est]]", validPhonemes.apply("test"));

        // Unusable phonemes containing ]] or [[
        UserDictionary badPhonemes = new UserDictionary("test", "test", false, false, false, "en", UserDictionary.CATEGORY_MAIN, "t]]Est");
        assertFalse(badPhonemes.hasPhonemeOverride());
    }

    @Test
    public void testCategoryNormalization() {
        assertEquals(UserDictionary.CATEGORY_MAIN, UserDictionary.normalizeCategory(null));
        assertEquals(UserDictionary.CATEGORY_MAIN, UserDictionary.normalizeCategory("unknown"));
        assertEquals(UserDictionary.CATEGORY_ROOT, UserDictionary.normalizeCategory("root"));
        assertEquals(UserDictionary.CATEGORY_ABBREV, UserDictionary.normalizeCategory("abbrev"));
        assertEquals(UserDictionary.CATEGORY_ABBREV, UserDictionary.normalizeCategory("abbreviation"));
        assertEquals(UserDictionary.CATEGORY_CHARACTER, UserDictionary.normalizeCategory("character"));
    }

    @Test
    public void testCaseInsensitiveUnicodeFolding() {
        // Kelvin sign \u212A matches 'k'
        UserDictionary ruleK = new UserDictionary("k", "kay", false, false, false);
        assertTrue(ruleK.isValid());
        assertEquals("kay", ruleK.apply("\u212A"));

        // Greek capital sigma \u03A3 and lowercase sigma \u03C3
        UserDictionary ruleSigma = new UserDictionary("\u03C3", "sigma", false, false, false);
        assertTrue(ruleSigma.isValid());
        assertEquals("sigma", ruleSigma.apply("\u03A3"));
    }

    @Test
    public void testHighestGroupReference() {
        assertEquals(1, UserDictionaryScreen.highestGroupReference("$1 kilograms"));
        assertEquals(5, UserDictionaryScreen.highestGroupReference("$2 and $5"));
        assertEquals(0, UserDictionaryScreen.highestGroupReference("\\$5 dollars"));
        assertEquals(0, UserDictionaryScreen.highestGroupReference("no groups"));
        assertEquals(0, UserDictionaryScreen.highestGroupReference(""));
        assertEquals(0, UserDictionaryScreen.highestGroupReference("$0"));
    }
}
