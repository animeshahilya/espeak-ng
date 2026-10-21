package com.animeshahilya.espeakng.tts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which imported files are taken as voice variants. */
public class ImportedVariantsTest {

    @Test
    public void nvdaStyleVariantIsRecognised() {
        assertTrue(ImportedVariants.looksLikeVariant("language variant\r\n"
                + "name Glenn\r\n"
                + "gender male\r\n"
                + "\r\n"
                + "// shared on the NVDA list\r\n"
                + "pitch 70 110\r\n"
                + "formant 0 100 100 100\r\n"
                + "formant 1 96 97 100 // a comment after values\r\n"
                + "flutter 2\r\n"));
    }

    @Test
    public void otherFilesAreNot() {
        assertFalse(ImportedVariants.looksLikeVariant("")); // empty
        assertFalse(ImportedVariants.looksLikeVariant("name Only\nlanguage en\n")); // no sound lines
        assertFalse(ImportedVariants.looksLikeVariant("pitch 70 110\n{\"profile\": 1}\n")); // JSON
        assertFalse(ImportedVariants.looksLikeVariant("pitch 70 110\n\u0000\u0001binary")); // binary
        assertFalse(ImportedVariants.looksLikeVariant("Hello, this is just a note.\npitch 1\n"));
    }
}
