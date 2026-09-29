package com.animeshahilya.espeakng;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.*;

/** Phonetic letters in the voice's language, with NVDA's real files (assets/chardesc/). */
public class NvdaCharacterDescriptionsTest {

    private static final File ASSETS = new File("assets/chardesc");

    @Before
    public void useAssets() {
        assertTrue(ASSETS.getAbsolutePath(), ASSETS.isDirectory());
        NvdaCharacterDescriptions.setDataSource(name -> new FileInputStream(new File(ASSETS, name)));
    }

    @After
    public void noAssets() {
        NvdaCharacterDescriptions.setDataSource(null);
    }

    @Test
    public void lettersUseTheVoicesLanguage() {
        assertEquals("b, Bravo", TextPreprocessor.expandNatoSpelling("b", "en-us"));
        assertEquals("b, Berlin", TextPreprocessor.expandNatoSpelling("b", "de"));
        // Upper case looks up the lower-case entry, as NVDA does.
        assertEquals("B, Berlin", TextPreprocessor.expandNatoSpelling("B", "de-at"));
        assertEquals("б, Борис", TextPreprocessor.expandNatoSpelling("б", "ru"));
        // A language without a file of its own uses English.
        assertEquals("b, Bravo", TextPreprocessor.expandNatoSpelling("b", "xx"));
    }

    @Test
    public void chineseCharactersGetExampleWords() {
        final String out = TextPreprocessor.expandNatoSpelling("一", "zh-cn");
        assertTrue(out, out.startsWith("一, ") && out.length() > 4);
        assertNotNull(NvdaCharacterDescriptions.get("cmn", "一"));
    }

    @Test
    public void hindiPrimerWordsStayFirst() {
        assertEquals("क से कबूतर", TextPreprocessor.expandNatoSpelling("क", "hi"));
    }

    @Test
    public void alwaysModeSpellsLettersButNotCjkText() {
        assertEquals("Aachen Berlin", TextPreprocessor.expandPhoneticMode("ab", "de"));
        assertEquals("一二三", TextPreprocessor.expandPhoneticMode("一二三", "zh-cn"));
    }

    @Test
    public void withoutFilesNatoStillWorks() {
        NvdaCharacterDescriptions.setDataSource(null);
        assertEquals("b, Bravo", TextPreprocessor.expandNatoSpelling("b", "de"));
        assertEquals("Alfa Bravo", TextPreprocessor.expandPhoneticMode("ab", "de"));
    }
}
