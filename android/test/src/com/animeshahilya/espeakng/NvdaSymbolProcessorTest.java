package com.animeshahilya.espeakng;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Per-language symbols, off-device, with the real NVDA data from
 * assets/symbols/ (tools/update_nvda_symbols.py).
 */
public class NvdaSymbolProcessorTest {

    private static final File ASSETS = new File("assets/symbols");

    @Before
    public void useAssets() {
        assertTrue("run from android/: " + ASSETS.getAbsolutePath(), ASSETS.isDirectory());
        NvdaSymbolProcessor.setDataSource(name -> new FileInputStream(new File(ASSETS, name)));
    }

    @After
    public void noAssets() {
        NvdaSymbolProcessor.setDataSource(null);
    }

    /** Arabic text gets NVDA's Arabic names, not English words read with Arabic rules. */
    @Test
    public void arabicVoiceGetsArabicNames() {
        final String out = NvdaSymbolProcessor.processText("مرحبا، كيف حالك؟",
                NvdaSymbolProcessor.LEVEL_ALL, true, "ar");
        assertTrue(out, out.contains("فاصلة"));
        assertTrue(out, out.contains("استفهام"));
        assertFalse(out, out.contains("comma"));
        // The same text on an English voice keeps the English names.
        final String en = NvdaSymbolProcessor.processText("مرحبا، كيف حالك؟",
                NvdaSymbolProcessor.LEVEL_ALL, true, "en-us");
        assertTrue(en, en.contains("arabic comma"));
    }

    /** Symbols a language does not define fall back to English, as in NVDA. */
    @Test
    public void missingSymbolsFallBackToEnglish() {
        assertEquals("braille 1 2 3",
                NvdaSymbolProcessor.processSingleSymbol("\u2807", "ar"));
        assertEquals("at", NvdaSymbolProcessor.processSingleSymbol("@", "xx"));
    }

    /** Region tags and eSpeak names find NVDA's locale: pt-br, pt, zh, ku. */
    @Test
    public void localesResolveLikeNvda() {
        assertEquals("pt_br", NvdaSymbolProcessor.locale("pt-br"));
        assertEquals("pt_pt", NvdaSymbolProcessor.locale("pt"));
        assertEquals("de", NvdaSymbolProcessor.locale("de-at"));
        assertEquals("zh_cn", NvdaSymbolProcessor.locale("cmn"));
        assertEquals("kmr", NvdaSymbolProcessor.locale("ku"));
        assertEquals("hi", NvdaSymbolProcessor.locale("hi-in"));
        assertEquals("en", NvdaSymbolProcessor.locale("en-gb"));
        assertEquals("en", NvdaSymbolProcessor.locale("xx"));
        assertEquals("en", NvdaSymbolProcessor.locale(null));
    }

    /**
     * Every language builds, no pattern is lost to Java regex syntax, and
     * newlines always pass through (paragraph pauses).
     */
    @Test
    public void everyLanguageBuildsAndKeepsNewlines() {
        final List<String> problems = new ArrayList<>();
        final String[] files = ASSETS.list();
        assertNotNull(files);
        int languages = 0;
        for (String file : files) {
            if (!file.endsWith(".dic") || file.endsWith(".cldr.dic")) {
                continue;
            }
            final String tag = file.substring(0, file.length() - 4);
            final NvdaSymbolProcessor.Table table = NvdaSymbolProcessor.forLanguage(tag);
            if (!table.skipped.isEmpty()) {
                problems.add(tag + " skipped " + table.skipped);
            }
            final String text = "a, b.\n\nc; (d) 3.5 -2 \"e\" 50% x@y.z!\r\n";
            for (int level : new int[] {NvdaSymbolProcessor.LEVEL_NONE,
                    NvdaSymbolProcessor.LEVEL_ALL}) {
                final String out = NvdaSymbolProcessor.processText(text, level, true, tag);
                if (!out.contains("\n\n") || !out.contains("\r\n")) {
                    problems.add(tag + " lost a newline: " + out);
                }
            }
            languages++;
        }
        assertTrue(languages > 50);
        assertEquals(Collections.emptyList(), problems);
    }

    /** NVDA's file format: sections, "-" inherits, escapes, display names, bad lines. */
    @Test
    public void parsesNvdaFormat() throws Exception {
        final NvdaSymbolProcessor.Source s = NvdaSymbolProcessor.parse(new BufferedReader(
                new StringReader("\ufeffcomplexSymbols:\n"
                        + "# comment\n"
                        + "x twice\t(x)(x)\n"
                        + "symbols:\n"
                        + "x twice\t\\1 and \\2\tsome\n"
                        + "\\#\thash\tall\tnorep\t# number sign\n"
                        + "\\t\ttab\n"
                        + "-\t-\tmost\n"
                        + "bad\tname\tloud\n")), true);
        assertEquals("(x)(x)", s.complex.get("x twice"));
        assertTrue(s.symbols.containsKey("#"));
        assertTrue(s.symbols.containsKey("\t"));
        assertTrue(s.symbols.containsKey("-"));
        assertFalse(s.symbols.containsKey("bad"));
        final List<NvdaSymbolProcessor.Source> sources = new ArrayList<>();
        sources.add(s);
        final NvdaSymbolProcessor.Table t = new NvdaSymbolProcessor.Table(sources);
        // Group references in a complex replacement are the symbol's own groups.
        assertEquals(" x and x ", t.process("xx", NvdaSymbolProcessor.LEVEL_ALL, false, null));
        // "-" named nothing anywhere, so NVDA drops it.
        assertEquals("a-b", t.process("a-b", NvdaSymbolProcessor.LEVEL_ALL, false, null));
    }

    /**
     * English keeps this app's table first: for ASCII text (all of it in
     * that table) NVDA's English files change nothing.
     */
    @Test
    public void englishAsciiIsUnchangedByNvdaFiles() {
        final String text = "Hi!!!! a != b; c: (d) [e] {f} 3.5 -2 \"q\" 'x' 50% #1 a@b.c "
                + "~/_`^|\\ it's 2*3 x*y ... ---- ok? http://a.b//c";
        final int[] levels = {NvdaSymbolProcessor.LEVEL_NONE, NvdaSymbolProcessor.LEVEL_SOME,
                NvdaSymbolProcessor.LEVEL_MOST, NvdaSymbolProcessor.LEVEL_ALL};
        final List<String> withData = new ArrayList<>();
        for (int level : levels) {
            withData.add(NvdaSymbolProcessor.processText(text, level, true, "en-us"));
        }
        NvdaSymbolProcessor.setDataSource(null);
        for (int i = 0; i < levels.length; i++) {
            assertEquals(NvdaSymbolProcessor.processText(text, levels[i], true), withData.get(i));
        }
        assertEquals("tilde", NvdaSymbolProcessor.processSingleSymbol("~", "en"));
    }
}
