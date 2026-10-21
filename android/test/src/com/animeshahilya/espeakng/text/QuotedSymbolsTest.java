package com.animeshahilya.espeakng.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** "Name a symbol in quotes" (espeak-ng issue #150). */
public class QuotedSymbolsTest {

    private static String name(String symbol) {
        return NvdaSymbolProcessor.processSingleSymbol(symbol, "en").trim();
    }

    @Test
    public void loneQuotedSymbolIsNamed() {
        assertEquals("press '" + name("_") + "' to quit",
                TextPreprocessor.nameQuotedSymbols("press '_' to quit", "en"));
        assertEquals("type “" + name("#") + "” first",
                TextPreprocessor.nameQuotedSymbols("type “#” first", "en"));
        assertEquals("(\"" + name("~") + "\")",
                TextPreprocessor.nameQuotedSymbols("(\"~\")", "en"));
        assertFalse(name("_").equals("_"));
    }

    @Test
    public void wordsLettersAndLongerTextAreLeftAlone() {
        for (String text : new String[] {"rock 'n' roll", "it's", "say '--' twice", "x'_'y",
                "''", "'_ '", "the \"best\" one"}) {
            assertSame(text, TextPreprocessor.nameQuotedSymbols(text, "en"));
        }
    }

    @Test
    public void namesFollowTheVoiceLanguage() {
        assertEquals("'" + NvdaSymbolProcessor.processSingleSymbol("@", "de").trim() + "'",
                TextPreprocessor.nameQuotedSymbols("'@'", "de"));
    }
}
