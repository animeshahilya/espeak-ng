package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class NumberReadingTest {

    @Test
    public void longNumbersAreReadDigitByDigit() {
        assertEquals("Call 9 8 7 6 5 4 3 2 1 0 now", NumberReading.spellLongNumbers("Call 9876543210 now"));
        assertEquals("1,250 steps, 123456789 and 3.14159265358",
                NumberReading.spellLongNumbers("1,250 steps, 123456789 and 3.14159265358"));
    }

    @Test
    public void testDollarReading() {
        assertEquals("5 dollars 50 cents", NumberReading.readMoney("$5.50", false));
        assertEquals("1 dollar", NumberReading.readMoney("$1", false));
        assertEquals("20 dollars", NumberReading.readMoney("$20", false));
    }

    @Test
    public void testEuroAndPoundReading() {
        assertEquals("12 euros", NumberReading.readMoney("€12", false));
        assertEquals("5 pounds", NumberReading.readMoney("£5", false));
    }

    @Test
    public void testRupeeReadingWithFlag() {
        // withRupee = false -> left unchanged for Indian voice pass
        assertEquals("₹500", NumberReading.readMoney("₹500", false));

        // withRupee = true -> converted
        assertEquals("500 rupees", NumberReading.readMoney("₹500", true));
    }

    @Test
    public void testWonAndBitcoinAndCentsReading() {
        assertEquals("1000 won", NumberReading.readMoney("₩1000", false));
        assertEquals("1 bitcoin", NumberReading.readMoney("1 BTC", false));
        assertEquals("2.5 bitcoins", NumberReading.readMoney("2.5 BTC", false));
        assertEquals("50 cents", NumberReading.readMoney("50¢", false));
        assertEquals("1 cent", NumberReading.readMoney("1¢", false));
        assertEquals("25 dollars", NumberReading.readMoney("25 CAD", false));
        assertEquals("50 dollars", NumberReading.readMoney("50 AUD", false));
    }

    /** Numbers and times -> In the language of the words around them. */
    @Test
    public void numbersNextToEnglishWordsAreEnglish() {
        assertEquals("call at ten thirty today", NumberReading.englishNumbers("call at 10:30 today", false));
        assertEquals("I have twenty five apples", NumberReading.englishNumbers("I have 25 apples", false));
        // Next to Hindi they stay digits, for the Hindi voice.
        assertEquals("मेरे पास 25 सेब", NumberReading.englishNumbers("मेरे पास 25 सेब", false));
    }

    /** Numbers and times -> Always in English. */
    @Test
    public void allNumbersAndTimesInEnglish() {
        assertEquals("मेरे पास twenty five सेब, ten thirty बजे",
                NumberReading.englishNumbers("मेरे पास 25 सेब, 10:30 बजे", true));
        assertEquals("nine oh five", NumberReading.englishNumbers("9:05", true));
        assertEquals("ten o'clock", NumberReading.englishNumbers("10:00", true));
        assertEquals("fourteen forty five", NumberReading.englishNumbers("14:45", true));
        // Not a clock time: each number on its own.
        assertEquals("one:two:three", NumberReading.englishNumbers("1:2:3", true));
    }

    @Test
    public void testDigitGroupingWithPause() {
        // Digits with Pause: 123 -> 1, 2, 3 (One… Two… Three)
        assertEquals("1, 2, 3", TextPreprocessor.formatDigitGrouping("123", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        assertEquals("9", TextPreprocessor.formatDigitGrouping("9", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        assertEquals("1, 2", TextPreprocessor.formatDigitGrouping("12", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        assertEquals("1, 2, 3, 4, 5, 6", TextPreprocessor.formatDigitGrouping("123456", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        // Embedded thousands comma normalization in single digits mode
        assertEquals("1, 2, 3, 4", TextPreprocessor.formatDigitGrouping("1,234", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        // Decimal marks stay attached, so the point is still spoken
        assertEquals("3.1, 4", TextPreprocessor.formatDigitGrouping("3.14", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        assertEquals("3,1, 4", TextPreprocessor.formatDigitGrouping("3,14", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
        // Indian grouping commas are separators too
        assertEquals("1 2 3 4 5 6 7", TextPreprocessor.formatDigitGrouping("12,34,567", VoiceSettings.DIGIT_GROUP_SINGLE, 6));

        // Pairs with Pause: 1234 -> 12, 34 (Twelve… Thirty-Four)
        assertEquals("12, 34", TextPreprocessor.formatDigitGrouping("1234", VoiceSettings.DIGIT_GROUP_DOUBLE_PAUSE, 6));
        assertEquals("12, 34, 56", TextPreprocessor.formatDigitGrouping("123456", VoiceSettings.DIGIT_GROUP_DOUBLE_PAUSE, 6));
        assertEquals("1, 23, 45", TextPreprocessor.formatDigitGrouping("12345", VoiceSettings.DIGIT_GROUP_DOUBLE_PAUSE, 6));
        assertEquals("123", TextPreprocessor.formatDigitGrouping("123", VoiceSettings.DIGIT_GROUP_DOUBLE_PAUSE, 6));

        // Triplets with Pause: 123456 -> 123, 456 (One Twenty-Three… Four Fifty-Six..)
        assertEquals("123, 456", TextPreprocessor.formatDigitGrouping("123456", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 6));
        assertEquals("1, 234, 567", TextPreprocessor.formatDigitGrouping("1234567", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 6));
        assertEquals("1, 234, 567", TextPreprocessor.formatDigitGrouping("1,234,567", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 6));
        assertEquals("123, 456, 789", TextPreprocessor.formatDigitGrouping("123456789", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 6));
        assertEquals("12345", TextPreprocessor.formatDigitGrouping("12345", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 6));
        // A decimal comma ends the run: German 1234,56 keeps its fraction
        assertEquals("1, 234,56", TextPreprocessor.formatDigitGrouping("1234,56", VoiceSettings.DIGIT_GROUP_TRIPLE_PAUSE, 4));
    }

    @Test
    public void testDigitGroupingSpace() {
        assertEquals("1 2 3", TextPreprocessor.formatDigitGrouping("123", VoiceSettings.DIGIT_GROUP_SINGLE, 6));
        assertEquals("12 34", TextPreprocessor.formatDigitGrouping("1234", VoiceSettings.DIGIT_GROUP_DOUBLE, 6));
        assertEquals("123 456", TextPreprocessor.formatDigitGrouping("123456", VoiceSettings.DIGIT_GROUP_TRIPLE, 6));
        assertEquals("1 234 567", TextPreprocessor.formatDigitGrouping("1234567", VoiceSettings.DIGIT_GROUP_TRIPLE, 6));
    }

    @Test
    public void testDigitGroupingOff() {
        assertEquals("123456", TextPreprocessor.formatDigitGrouping("123456", VoiceSettings.DIGIT_GROUP_OFF, 6));
        assertEquals("1,234,567", TextPreprocessor.formatDigitGrouping("1,234,567", VoiceSettings.DIGIT_GROUP_OFF, 6));
    }

    @Test
    public void testDigitGroupingInSentence() {
        assertEquals("Call 12, 34 now",
                TextPreprocessor.formatDigitGrouping("Call 1234 now", VoiceSettings.DIGIT_GROUP_DOUBLE_PAUSE, 6));
        assertEquals("PIN: 1, 2, 3",
                TextPreprocessor.formatDigitGrouping("PIN: 123", VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 6));
    }

    @Test
    public void testReadCodesMultilingualPunctuation() {
        assertEquals("Your OTP is 4 8 2 9 1 3? Done.",
                NumberReading.readCodes("Your OTP is 482913? Done."));
        assertEquals("Your OTP is 4 8 2 9 1 3! Done.",
                NumberReading.readCodes("Your OTP is 482913! Done."));
        assertEquals("Your OTP is 4 8 2 9 1 3؟ Done.",
                NumberReading.readCodes("Your OTP is 482913؟ Done."));
        assertEquals("Your OTP is 4 8 2 9 1 3‽ Done.",
                NumberReading.readCodes("Your OTP is 482913‽ Done."));
    }

    @Test
    public void testReadDimensions() {
        assertEquals("1920 by 1080", NumberReading.readDimensions("1920x1080"));
        assertEquals("3840 by 2160", NumberReading.readDimensions("3840×2160"));
        assertEquals("4 by 4", NumberReading.readDimensions("4x4"));
        assertEquals("10 by 20 by 30", NumberReading.readDimensions("10x20x30"));
        assertEquals("10 by 20 by 30 by 40", NumberReading.readDimensions("10x20x30x40"));
        assertEquals("5.5 by 2.5", NumberReading.readDimensions("5.5 x 2.5"));
        assertEquals("0x1080", NumberReading.readDimensions("0x1080"));
        assertEquals("0x0", NumberReading.readDimensions("0x0"));
        assertEquals("x1080", NumberReading.readDimensions("x1080"));
        assertEquals("hello world", NumberReading.readDimensions("hello world"));
    }

    @Test
    public void testReadDates() {
        assertEquals("8 October 2026", NumberReading.readDates("2026-10-08"));
        assertEquals("31 December 1999", NumberReading.readDates("1999-12-31"));
        assertEquals("1 January 2024", NumberReading.readDates("2024-01-01"));
        assertEquals("date 2026-13-40 invalid", NumberReading.readDates("date 2026-13-40 invalid"));
        assertEquals("10:00 to 11:30", NumberReading.readDates("10:00-11:30"));
        assertEquals("10:00 to 11:30", NumberReading.readDates("10:00 - 11:30"));
        assertEquals("2:00 PM to 4:00 PM", NumberReading.readDates("2:00 PM – 4:00 PM"));
    }

    @Test
    public void testReadRomanNumerals() {
        assertEquals("Chapter 4", NumberReading.readRomanNumerals("Chapter IV"));
        assertEquals("World War 2", NumberReading.readRomanNumerals("World War II"));
        assertEquals("Act 3, Scene 1", NumberReading.readRomanNumerals("Act III, Scene I"));
        assertEquals("Henry the Eighth", NumberReading.readRomanNumerals("Henry VIII"));
        assertEquals("Charles the Third", NumberReading.readRomanNumerals("Charles III"));
        assertEquals("Queen Elizabeth the Second", NumberReading.readRomanNumerals("Queen Elizabeth II"));
        assertEquals("Pope John Paul the Second", NumberReading.readRomanNumerals("Pope John Paul II"));
        assertEquals("I have a dream", NumberReading.readRomanNumerals("I have a dream"));
        assertEquals("He went to IV", NumberReading.readRomanNumerals("He went to IV"));
    }

    @Test
    public void testReadPhoneNumbers() {
        assertEquals("Call +1 800 555 0199 now", NumberReading.readPhoneNumbers("Call +1-800-555-0199 now"));
        assertEquals("Call 800 555 0199", NumberReading.readPhoneNumbers("Call (800) 555-0199"));
        assertEquals("800 555 0199", NumberReading.readPhoneNumbers("800-555-0199"));
        assertEquals("9 8 7 6 5 4 3 2 1 0", NumberReading.readPhoneNumbers("9876543210"));
        assertEquals("100 - 50", NumberReading.readPhoneNumbers("100 - 50"));
    }

    @Test
    public void testReadMath() {
        assertEquals("5 plus 3 equals 8", NumberReading.readMath("5 + 3 = 8"));
        assertEquals("10 minus 4 equals 6", NumberReading.readMath("10 - 4 = 6"));
        assertEquals("6 times 7 equals 42", NumberReading.readMath("6 * 7 = 42"));
        assertEquals("20 divided by 4 equals 5", NumberReading.readMath("20 / 4 = 5"));
        assertEquals("100 minus 50", NumberReading.readMath("100 - 50"));
        assertEquals("2026-10-08", NumberReading.readMath("2026-10-08"));
    }

    @Test
    public void testSplitCamelCase() {
        assertEquals("get User Name", TextPreprocessor.splitCamelCase("getUserName"));
        assertEquals("on Synthesize Text", TextPreprocessor.splitCamelCase("onSynthesizeText"));
        assertEquals("XML Http Request", TextPreprocessor.splitCamelCase("XMLHttpRequest"));
        assertEquals("Text Preprocessor", TextPreprocessor.splitCamelCase("TextPreprocessor"));
        assertEquals("HTML5 Canvas", TextPreprocessor.splitCamelCase("HTML5Canvas"));
        assertEquals("Hello world", TextPreprocessor.splitCamelCase("Hello world"));
    }

    @Test
    public void testFractionsReading() {
        // Slash fractions
        assertEquals("1 half", NumberReading.readFractions("1/2"));
        assertEquals("1 quarter", NumberReading.readFractions("1/4"));
        assertEquals("3 quarters", NumberReading.readFractions("3/4"));
        assertEquals("1 third", NumberReading.readFractions("1/3"));
        assertEquals("2 thirds", NumberReading.readFractions("2/3"));
        assertEquals("5 eighths", NumberReading.readFractions("5/8"));
        assertEquals("minus 1 half", NumberReading.readFractions("-1/2"));

        // Mixed numbers
        assertEquals("2 and a half", NumberReading.readFractions("2 1/2"));
        assertEquals("1 and 3 quarters", NumberReading.readFractions("1 3/4"));
        assertEquals("Add 2 and a half cups of flour", NumberReading.readFractions("Add 2 1/2 cups of flour"));

        // Unicode fractions
        assertEquals("1 half", NumberReading.readFractions("½"));
        assertEquals("3 quarters", NumberReading.readFractions("¾"));
        assertEquals("2 and a half", NumberReading.readFractions("2 ½"));
        assertEquals("2 and a half", NumberReading.readFractions("2½"));
        assertEquals("1 and 3 quarters", NumberReading.readFractions("1 ¾"));

        // Date and score protection: calendar dates and scores must NOT be mangled
        assertEquals("2026/10/08", NumberReading.readFractions("2026/10/08"));
        assertEquals("10/12/2026", NumberReading.readFractions("10/12/2026"));
        assertEquals("Score is 0/2", NumberReading.readFractions("Score is 0/2"));
        assertEquals("0/4", NumberReading.readFractions("0/4"));
    }

    @Test
    public void testSubSuperReading() {
        // Metric area and volume
        assertEquals("square meters", NumberReading.readSubSuper("m²"));
        assertEquals("cubic meters", NumberReading.readSubSuper("m³"));
        assertEquals("square kilometers", NumberReading.readSubSuper("km²"));
        assertEquals("square centimeters", NumberReading.readSubSuper("cm²"));

        // Algebra and powers
        assertEquals("x squared + y squared", NumberReading.readSubSuper("x² + y²"));
        assertEquals("x cubed", NumberReading.readSubSuper("x³"));
        assertEquals("10 squared", NumberReading.readSubSuper("10²"));
        assertEquals("10 to the 5th", NumberReading.readSubSuper("10⁵"));
        assertEquals("x to the n", NumberReading.readSubSuper("xⁿ"));

        // Chemical formulas
        assertEquals("H 2 O", NumberReading.readSubSuper("H₂O"));
        assertEquals("CO 2", NumberReading.readSubSuper("CO₂"));
        assertEquals("CH 4", NumberReading.readSubSuper("CH₄"));
    }

    @Test
    public void testOrdinalsReading() {
        // English ordinals
        assertEquals("first", NumberReading.readOrdinals("1st"));
        assertEquals("second", NumberReading.readOrdinals("2nd"));
        assertEquals("third", NumberReading.readOrdinals("3rd"));
        assertEquals("fourth", NumberReading.readOrdinals("4th"));
        assertEquals("twenty-first", NumberReading.readOrdinals("21st"));
        assertEquals("twenty-second", NumberReading.readOrdinals("22nd"));
        assertEquals("twenty-third", NumberReading.readOrdinals("23rd"));
        assertEquals("thirty-first", NumberReading.readOrdinals("31st"));
        assertEquals("hundredth", NumberReading.readOrdinals("100th"));
        assertEquals("millionth", NumberReading.readOrdinals("1000000th"));

        // International symbol ordinals
        assertEquals("first", NumberReading.readOrdinals("1º"));
        assertEquals("second", NumberReading.readOrdinals("2º"));
        assertEquals("first", NumberReading.readOrdinals("1ª"));
        assertEquals("123456789012345th", NumberReading.readOrdinals("123456789012345º"));
        assertEquals("123456789012341st", NumberReading.readOrdinals("123456789012341º"));
        assertEquals("123456789012342nd", NumberReading.readOrdinals("123456789012342º"));
        assertEquals("123456789012343rd", NumberReading.readOrdinals("123456789012343º"));
        assertEquals("123456789012311th", NumberReading.readOrdinals("123456789012311º"));
    }

    @Test
    public void testCleanMarkdown() {
        // Fast bypass on plain text
        assertEquals("Hello world", TextPreprocessor.cleanMarkdown("Hello world"));

        // Bold and italic
        assertEquals("Hello world", TextPreprocessor.cleanMarkdown("**Hello world**"));
        assertEquals("Hello world", TextPreprocessor.cleanMarkdown("*Hello world*"));
        assertEquals("This is bold and italic text",
                TextPreprocessor.cleanMarkdown("This is **bold** and *italic* text"));

        // Inline code and code fences
        assertEquals("status_code", TextPreprocessor.cleanMarkdown("`status_code`"));
        assertEquals("code block", TextPreprocessor.cleanMarkdown("```python\ncode block\n```"));

        // Strikethrough
        assertEquals("cancelled", TextPreprocessor.cleanMarkdown("~~cancelled~~"));

        // Headings
        assertEquals("Main Heading", TextPreprocessor.cleanMarkdown("# Main Heading"));
        assertEquals("Subheading", TextPreprocessor.cleanMarkdown("### Subheading"));

        // Blockquotes
        assertEquals("quote: Important notice", TextPreprocessor.cleanMarkdown("> Important notice"));

        // Checkboxes / task lists
        assertEquals("todo: Buy groceries", TextPreprocessor.cleanMarkdown("- [ ] Buy groceries"));
        assertEquals("done: Pay electric bill", TextPreprocessor.cleanMarkdown("- [x] Pay electric bill"));
    }
}
