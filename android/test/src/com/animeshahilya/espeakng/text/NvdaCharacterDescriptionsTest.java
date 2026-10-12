package com.animeshahilya.espeakng.text;

import com.animeshahilya.espeakng.text.NvdaCharacterDescriptions;
import com.animeshahilya.espeakng.text.TextPreprocessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.*;

/** Phonetic letters in the voice's language, with NVDA's real files (data/chardesc/). */
public class NvdaCharacterDescriptionsTest {

    private static final File ASSETS = new File("data/chardesc");

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
        // NVDA ships this one as Windows-1252; read as UTF-8 it said "C�sar".
        assertEquals("c, Cäsar", TextPreprocessor.expandNatoSpelling("c", "de-ch"));
        // A language without a file of its own uses English.
        assertEquals("b, Bravo", TextPreprocessor.expandNatoSpelling("b", "xx"));
    }

    /** Every loader reads data/ as UTF-8 (BundledData.reader); a stray encoding garbles silently. */
    @Test
    public void everyBundledFileIsUtf8() throws Exception {
        final java.nio.charset.CharsetDecoder utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(new File("data").toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator) {
                try {
                    utf8.decode(java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(p)));
                } catch (java.nio.charset.CharacterCodingException e) {
                    fail("not UTF-8: " + p);
                }
            }
        }
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
