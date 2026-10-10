package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.Abbreviations;

import org.junit.Test;
import static org.junit.Assert.*;

public class AbbreviationsTest {

    @Test
    public void testTitleAndWordAbbreviations() {
        assertEquals("Professor John arrived", Abbreviations.process("Prof John arrived"));
        assertEquals("Meet with government officials", Abbreviations.process("Meet with govt officials"));
        assertEquals("Government of India", Abbreviations.process("Govt of India"));
        assertEquals("Private Limited", Abbreviations.process("Pvt Limited"));
        assertEquals("for example this is a test", Abbreviations.process("e.g. this is a test"));
        assertEquals("that is the plan", Abbreviations.process("i.e. the plan"));
        assertEquals("See Figure 1", Abbreviations.process("See Fig. 1"));
    }

    @Test
    public void testUnitsSingularAndPlural() {
        assertEquals("1 kilometre", Abbreviations.process("1 km"));
        assertEquals("5 kilometres", Abbreviations.process("5 km"));
        assertEquals("1 kilogram", Abbreviations.process("1 kg"));
        assertEquals("10 kilograms", Abbreviations.process("10 kg"));
        assertEquals("2 hours", Abbreviations.process("2 hrs"));
        assertEquals("1 minute", Abbreviations.process("1 min"));
        assertEquals("16 gigabytes", Abbreviations.process("16 GB"));
        assertEquals("1 megabyte", Abbreviations.process("1 MB"));
        assertEquals("500 kilobytes", Abbreviations.process("500 KB"));
        assertEquals("2 terabytes", Abbreviations.process("2 TB"));
        assertEquals("60 kilometres per hour", Abbreviations.process("60 km/h"));
        assertEquals("100 kilometres per hour", Abbreviations.process("100 kmph"));
        assertEquals("45 miles per hour", Abbreviations.process("45 mph"));
        assertEquals("500 millilitres", Abbreviations.process("500 ml"));
        assertEquals("1 millilitre", Abbreviations.process("1 mL"));
        assertEquals("120 hertz", Abbreviations.process("120 Hz"));
        assertEquals("2.4 gigahertz", Abbreviations.process("2.4 GHz"));
        assertEquals("5000 milliampere hours", Abbreviations.process("5000 mAh"));
        assertEquals("65 watts", Abbreviations.process("65 W"));
        assertEquals("1 watt", Abbreviations.process("1 W"));
        assertEquals("10 decibels", Abbreviations.process("10 dB"));
        assertEquals("5 kilometres.", Abbreviations.process("5 km."));
        assertEquals("Distance is 5 kilometres. Next stop is near.",
                Abbreviations.process("Distance is 5 km. Next stop is near."));
        assertEquals("5 kilometres of rope", Abbreviations.process("5 km. of rope"));
    }

    @Test
    public void testTemperature() {
        assertEquals("25 degrees Celsius", Abbreviations.process("25°C"));
        assertEquals("1 degree Celsius", Abbreviations.process("1°C"));
        assertEquals("77 degrees Fahrenheit", Abbreviations.process("77°F"));
        assertEquals("1 degree Fahrenheit", Abbreviations.process("1°F"));
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
