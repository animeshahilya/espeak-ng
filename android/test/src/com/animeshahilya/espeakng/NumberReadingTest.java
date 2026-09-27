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
}
