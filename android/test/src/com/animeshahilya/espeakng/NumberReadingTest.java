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
}
