package com.animeshahilya.espeakng;

import org.junit.Test;
import static org.junit.Assert.*;

public class AbbreviationsTest {

    @Test
    public void testTitleAndWordAbbreviations() {
        assertEquals("Professor John arrived", Abbreviations.process("Prof John arrived"));
        assertEquals("Meet with government officials", Abbreviations.process("Meet with govt officials"));
        assertEquals("Government of India", Abbreviations.process("Govt of India"));
        assertEquals("Private Limited", Abbreviations.process("Pvt Limited"));
    }

    @Test
    public void testUnitsSingularAndPlural() {
        assertEquals("1 kilometre", Abbreviations.process("1 km"));
        assertEquals("5 kilometres", Abbreviations.process("5 km"));
        assertEquals("1 kilogram", Abbreviations.process("1 kg"));
        assertEquals("10 kilograms", Abbreviations.process("10 kg"));
        assertEquals("2 hours", Abbreviations.process("2 hrs"));
        assertEquals("1 minute", Abbreviations.process("1 min"));
    }

    @Test
    public void testDateAndMonth() {
        assertEquals("15 January 2026", Abbreviations.process("15 Jan 2026"));
        assertEquals("October 5", Abbreviations.process("Oct 5"));
    }

    @Test
    public void testNumberPrefix() {
        assertEquals("Number 42", Abbreviations.process("No. 42"));
        assertEquals("number 7", Abbreviations.process("no. 7"));
    }
}
