package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class NumberReadingTest {

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
}
