/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NVDA-style symbol pronunciation, reimplemented for Android/Java.
 *
 * <p>This mirrors the algorithm of NVDA's {@code characterProcessing} module
 * ({@code SpeechSymbolProcessor}): every known symbol carries a <em>level</em>
 * (the minimum verbosity at which it is spoken) and a <em>preserve</em> mode
 * (whether the raw character is also kept for the synthesizer). Complex
 * (regex) symbols win over plain ones, multi-character symbols match longest
 * first, runs of 4+ identical symbols collapse to "N name", and trailing
 * space runs are stripped. Symbols below the user's level degrade to either
 * the raw character (preserve) or a pause (a single space), never to silence
 * that would glue words together.
 *
 * <p>Per language, like NVDA: a voice's table merges, in order, NVDA's
 * {@code symbols.dic} for its locale, that locale's CLDR symbol names, the
 * English table below, NVDA's English {@code symbols.dic} and English CLDR
 * names (assets/symbols/, from tools/update_nvda_symbols.py). For each
 * symbol the first source that sets a field wins; "-" inherits. So an
 * Arabic voice says "فاصلة" for "،" where it used to get the English word
 * "comma" read with Arabic rules. A language NVDA has no data for uses
 * English, as NVDA does. Without assets (JVM tests) only the English table
 * below exists.
 *
 * <p>Behavioral notes (deliberate, tested):
 * <ul>
 *   <li>Levels are NONE=0, SOME=100, MOST=200, ALL=300. A symbol is spoken
 *   exactly when {@code userLevel >= symbol.level}; level NONE therefore
 *   means "always spoken" (decimal points stay silent via their empty
 *   replacement instead). This matches NVDA, where e.g. math symbols and
 *   negative-number "minus" announce at every level.</li>
 *   <li>The English data below is NVDA's English table, re-expressed:
 *   same identifiers, levels, preserve modes and replacements, with a few
 *   normalized wordings ("tilde" not "tilda", "divided by" not "divide by",
 *   hyphen repairs in "less than or equal to"). It comes before NVDA's own
 *   English file, which only fills what it lacks.</li>
 *   <li>NVDA has no ASCII programming digraphs ({@code !=}, {@code ==},
 *   {@code &&}, ...); they are kept here as complex-style multi-character
 *   symbols at level ALL, since NVDA would otherwise read "!=" as
 *   "bang equals". Likewise {@code =>} keeps "implies" (NVDA's table only
 *   knows the &#x21d2; glyph as "double right arrow").</li>
 *   <li>{@code \n} and {@code \r} never enter a table, whatever a source
 *   says: this engine renders paragraph pauses from newlines, so they always
 *   pass through untouched (see TextPipelineDeviceTest).</li>
 *   <li>Braille patterns (U+2800-U+28FF) are generated, not listed: the
 *   mapping is mechanical ("braille 1 2 3").</li>
 *   <li>Emoji naming (NVDA's per-language CLDR dictionaries) lives in
 *   {@link NvdaEmoji} and backs Announce mode; glue codepoints with no
 *   name (joiners, variation selectors, lone regional indicators) are
 *   dropped, matching what NVDA's pass-through amounts to audibly. The
 *   CLDR files here hold only the non-emoji names.</li>
 * </ul>
 */
public final class NvdaSymbolProcessor {

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_SOME = 100;
    public static final int LEVEL_MOST = 200;
    public static final int LEVEL_ALL = 300;

    private static final int LEVEL_CHAR = 1000;
    /** Effective level for symbols outside a custom set: never spoken. */
    private static final int LEVEL_NEVER = 1001;
    /** Effective level for symbols inside a custom set: always spoken. */
    private static final int LEVEL_ALWAYS = -1;

    private static final int PRESERVE_NEVER = 0;
    private static final int PRESERVE_ALWAYS = 1;
    private static final int PRESERVE_NOREP = 2;

    private static final int N = LEVEL_NONE;
    private static final int S = LEVEL_SOME;
    private static final int M = LEVEL_MOST;
    private static final int A = LEVEL_ALL;
    private static final int C = LEVEL_CHAR;
    private static final int n = PRESERVE_NEVER;
    private static final int a = PRESERVE_ALWAYS;
    private static final int r = PRESERVE_NOREP;

    /** One symbol as a source defines it; a null field inherits (NVDA's "-"). */
    private static final class Entry {
        final String replacement;
        final Integer level;
        final Integer preserve;

        Entry(String replacement, Integer level, Integer preserve) {
            this.replacement = replacement;
            this.level = level;
            this.preserve = preserve;
        }
    }

    /** One dictionary's raw data in file order: NVDA's SpeechSymbols. */
    static final class Source {
        final Map<String, String> complex = new LinkedHashMap<String, String>();
        /** Text a custom character list matches a complex symbol by. */
        final Map<String, String> samples = new HashMap<String, String>();
        final Map<String, Entry> symbols = new LinkedHashMap<String, Entry>();
    }

    private static final class Symbol {
        final String id;
        final String replacement;
        final int level;
        final int preserve;

        Symbol(String id, String replacement, int level, int preserve) {
            this.id = id;
            this.replacement = replacement;
            this.level = level;
            this.preserve = preserve;
        }
    }

    private static final class Complex {
        final String regex;
        /** Representative text for custom-list matching (the id is a name). */
        final String sample;
        final String replacement;
        final int level;
        final int preserve;
        final int internalGroups;
        /** Group number of this symbol's own group in the master pattern. */
        int group;

        Complex(String regex, String sample, String replacement, int level, int preserve, int internalGroups) {
            this.regex = regex;
            this.sample = sample;
            this.replacement = replacement;
            this.level = level;
            this.preserve = preserve;
            this.internalGroups = internalGroups;
        }
    }

    /** Opens a file of assets/symbols/ by name; missing files throw. */
    public interface DataSource {
        InputStream open(String name) throws IOException;
    }

    private static final Source ENGLISH = new Source();
    private static final String EN = "en";
    /**
     * eSpeak languages whose NVDA locale is not the language code itself:
     * a region-less tag has no file of its own there.
     */
    private static final Map<String, String> LOCALE_ALIASES = new HashMap<String, String>();

    private static volatile DataSource sData;
    private static final Map<String, Table> TABLES = new ConcurrentHashMap<String, Table>();
    private static final Map<String, Table> TABLES_BY_LANG = new ConcurrentHashMap<String, Table>();
    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<String, Source>();
    private static final Source MISSING = new Source();

    private static void sym(String id, String replacement, int level, int preserve) {
        ENGLISH.symbols.put(id, new Entry(replacement, level, preserve));
    }

    private static void complex(String id, String regex, String sample, String replacement,
            int level, int preserve) {
        ENGLISH.complex.put(id, regex);
        ENGLISH.samples.put(id, sample);
        sym(id, replacement, level, preserve);
    }

    static {
        buildTable();
        LOCALE_ALIASES.put("pt", "pt_pt");
        LOCALE_ALIASES.put("zh", "zh_cn");
        LOCALE_ALIASES.put("cmn", "zh_cn");
        LOCALE_ALIASES.put("yue", "zh_hk");
        LOCALE_ALIASES.put("nb", "nb_no");
        LOCALE_ALIASES.put("no", "nb_no");
        LOCALE_ALIASES.put("nn", "nn_no");
        LOCALE_ALIASES.put("ku", "kmr");
        LOCALE_ALIASES.put("af", "af_za");
    }

    private static void buildTable() {
        // Complex symbols first: sentence/phrase endings, visual dot runs,
        // decimal points, in-word apostrophes, negative numbers. Identifiers
        // are NVDA's, so a language's file can rename or redefine them. The
        // "//" guard is split in two because Java lookbehind needs fixed length
        // (NVDA's "(?<!https?:)" is variable length and won't compile).
        complex("double slash", "(?<!https:)(?<!http:)//", "/", "double slash", A, n);
        complex(". sentence ending", "(?<=[^\\s.])\\.(?=[\"'\"”’)\\s]|$)", ".", "dot", A, a);
        complex("! sentence ending", "(?<=[^\\s!])!(?=[\"'\"”’)\\s]|$)", "!", "bang", A, a);
        complex("? sentence ending", "(?<=[^\\s?])\\?(?=[\"'\"”’)\\s]|$)", "?", "question", A, a);
        complex("; phrase ending", "(?<=[^\\s;]);(?=\\s|$)", ";", "semi", M, a);
        complex(": phrase ending", "(?<=[^\\s:]):(?=\\s|$)", ":", "colon", M, a);
        complex("multiple .", "\\.{4,}", "...", "multiple dots", A, a);
        complex("decimal point", "(?<![^\\d -])\\.(?=\\d)", ".", "", N, a);
        // [\p{L}\p{N}] approximates NVDA's [^\W_]: Unicode letters and
        // digits, no underscore. Java's \W is ASCII-only, so [^\W_] would
        // wrongly match after Devanagari/CJK letters.
        complex("in-word '", "(?<=[\\p{L}\\p{N}])['’]", "'", "tick", A, r);
        complex("negative number", "(?<!\\w)[-−]{1}(?=[$£€¥.]?\\d)", "-", "minus", N, r);
        // Bare "*" between words is a star; between digits it is times.
        complex("* between numbers", "(?<=\\d)\\s*\\*\\s*(?=\\d)", "*", "times", S, n);

        // ASCII programming digraphs: no NVDA equivalent (NVDA would read
        // "!=" as "bang equals"), kept as multi-character symbols at ALL.
        sym("!=", "not equal", A, n);
        sym("==", "double equals", A, n);
        sym("<=", "less than or equal to", A, n);
        sym(">=", "greater than or equal to", A, n);
        sym("=>", "implies", A, n);
        sym("->", "arrow", A, n);
        sym("<-", "left arrow", A, n);
        sym("&&", "double ampersand", A, n);
        sym("||", "double pipe", A, n);
        sym("/*", "comment start", A, n);
        sym("*/", "comment end", A, n);
        sym("+/-", "plus or minus", A, n);
        sym("...", "dot dot dot", A, a);

        // Whitespace. Newlines are absent on purpose (paragraph pauses).
        sym("\0", "blank", C, n);
        sym("\t", "tab", A, n);
        sym("\f", "page break", N, n);
        sym(" ", "space", C, n);
        sym("\u00a0", "space", C, n); // no-break space

        // Standard punctuation and symbols (NVDA English levels).
        sym("!", "bang", A, n);
        // CJK sentence punctuation is kept for eSpeak (its pauses), like the
        // ASCII sentence endings. NVDA's English file lacks \u3002 and \uff0c, so their
        // CLDR names (level none) said "ideographic period" after every
        // Chinese sentence even with punctuation off, and dropped the pause.
        sym("\uff01", "fullwidth exclamation mark", A, a);
        sym("\"", "quote", M, n);
        sym("#", "number", S, n);
        sym("$", "dollar", A, r);
        sym("\u00a3", "pound", A, r);
        sym("\u20ac", "euro", A, r);
        sym("\u00a2", "cents", A, r);
        sym("\u00a5", "yen", A, r);
        sym("\u20b9", "rupee", S, r);
        sym("\u0192", "florin", A, r);
        sym("\u00a4", "currency sign", A, r);
        sym("%", "percent", S, n);
        sym("\u2030", "per mille", S, n);
        sym("&", "and", S, n);
        sym("'", "tick", A, n);
        sym("(", "left paren", M, a);
        sym(")", "right paren", M, a);
        sym("*", "star", S, n);
        sym(",", "comma", A, a);
        sym("\u3001", "ideographic comma", A, a);
        sym("\uff0c", "fullwidth comma", A, a);
        sym("\u060c", "arabic comma", A, a);
        sym("-", "dash", M, a);
        sym(".", "dot", S, n);
        sym("\uff0e", "fullwidth full stop", S, n);
        sym("\u3002", "ideographic full stop", A, a);
        sym("/", "slash", S, n);
        sym(":", "colon", M, r);
        sym("\uff1a", "fullwidth colon", M, r);
        sym(";", "semi", M, n);
        sym("\u061b", "arabic semicolon", M, n);
        sym("\uff1b", "fullwidth semicolon", M, a);
        sym("?", "question", A, n);
        sym("\u061f", "arabic question mark", A, n);
        sym("\uff1f", "fullwidth question mark", A, a);
        sym("@", "at", S, n);
        sym("[", "left bracket", M, n);
        sym("]", "right bracket", M, n);
        sym("\\", "backslash", M, n);
        sym("^", "caret", M, n);
        sym("_", "line", M, n);
        sym("`", "grave", M, n);
        sym("{", "left brace", M, n);
        sym("}", "right brace", M, n);
        sym("|", "bar", M, n);
        sym("\u00a6", "broken bar", M, n);
        sym("~", "tilde", M, n);
        sym("\u00a1", "inverted exclamation point", S, n);
        sym("\u00bf", "inverted question mark", S, n);
        sym("\u00b7", "middle dot", M, n);
        sym("\u201a", "single low quote", M, n);
        sym("\u201e", "double low quote", M, n);
        sym("\u2032", "prime", S, n);
        sym("\u2033", "double prime", S, n);
        sym("\u2034", "triple prime", S, n);
        sym("\u2010", "hyphen", M, a);
        sym("\u21d2", "implies", A, n);

        // Other characters.
        sym("\u2022", "bullet", S, n);
        sym("\u2026", "dot dot dot", A, a);
        sym("\u201c", "left quote", M, n);
        sym("\u201d", "right quote", M, n);
        sym("\u2018", "left tick", M, n);
        sym("\u2019", "right tick", M, n);
        sym("\u2013", "en dash", M, a);
        sym("\u2014", "em dash", M, a);
        sym("\u00ad", "soft hyphen", M, n);
        sym("\u2043", "hyphen bullet", N, n);
        sym("\u25cf", "circle", M, n);
        sym("\u25cb", "white circle", M, n);
        sym("\u00a8", "diaeresis", M, n);
        sym("\u00af", "macron", M, n);
        sym("\u00b4", "acute", M, n);
        sym("\u00b8", "cedilla", M, n);
        sym("\u200e", "left to right mark", C, n);
        sym("\u200f", "right to left mark", C, n);
        sym("\u00b6", "paragraph marker", M, n);
        sym("\u25a0", "black square", S, n);
        sym("\u25aa", "black square", S, n);
        sym("\u25be", "black square", S, n);
        sym("\u25a1", "white square", S, n);
        sym("\u25e6", "white bullet", S, n);
        sym("\u21e8", "right white arrow", S, n);
        sym("\u2794", "right-pointing arrow", S, n);
        sym("\u27a2", "right arrowhead", S, n);
        sym("\u2756", "black diamond minus white X", S, n);
        sym("\u2663", "black club", S, n);
        sym("\u2666", "black diamond", S, n);
        sym("\u25c6", "black diamond", S, n);
        sym("\u00a7", "section", A, n);
        sym("\u00b0", "degrees", S, n);
        sym("\u00ab", "double left pointing angle bracket", M, a);
        sym("\u00bb", "double right pointing angle bracket", M, a);
        // CJK brackets (book titles, quotes) at bracket level: only CLDR names
        // them, at level none, so every language said "open double angle
        // bracket" around a Chinese book title with punctuation off.
        sym("\u3008", "open angle bracket", M, n);
        sym("\u3009", "close angle bracket", M, n);
        sym("\u300a", "open double angle bracket", M, n);
        sym("\u300b", "close double angle bracket", M, n);
        sym("\u300c", "open corner bracket", M, n);
        sym("\u300d", "close corner bracket", M, n);
        sym("\u300e", "open hollow corner bracket", M, n);
        sym("\u300f", "close hollow corner bracket", M, n);
        sym("\u3010", "open black lens bracket", M, n);
        sym("\u3011", "close black lens bracket", M, n);
        sym("\u3014", "open tortoise shell bracket", M, n);
        sym("\u3015", "close tortoise shell bracket", M, n);
        sym("\u3016", "open hollow lens bracket", M, n);
        sym("\u3017", "close hollow lens bracket", M, n);
        sym("\u00b5", "micro", S, n);
        sym("\u2070", "superscript 0", S, n);
        sym("\u00b9", "superscript 1", S, n);
        sym("\u00b2", "superscript 2", S, n);
        sym("\u00b3", "superscript 3", S, n);
        sym("\u2074", "superscript 4", S, n);
        sym("\u2075", "superscript 5", S, n);
        sym("\u2076", "superscript 6", S, n);
        sym("\u2077", "superscript 7", S, n);
        sym("\u2078", "superscript 8", S, n);
        sym("\u2079", "superscript 9", S, n);
        sym("\u207a", "superscript plus", S, n);
        sym("\u207c", "superscript equals", S, n);
        sym("\u207d", "superscript left paren", S, n);
        sym("\u207e", "superscript right paren", S, n);
        sym("\u207f", "superscript n", S, n);
        sym("\u2080", "subscript 0", S, n);
        sym("\u2081", "subscript 1", S, n);
        sym("\u2082", "subscript 2", S, n);
        sym("\u2083", "subscript 3", S, n);
        sym("\u2084", "subscript 4", S, n);
        sym("\u2085", "subscript 5", S, n);
        sym("\u2086", "subscript 6", S, n);
        sym("\u2087", "subscript 7", S, n);
        sym("\u2088", "subscript 8", S, n);
        sym("\u2089", "subscript 9", S, n);
        sym("\u208a", "subscript plus", S, n);
        sym("\u208b", "subscript minus", S, n);
        sym("\u208c", "subscript equals", S, n);
        sym("\u208d", "subscript left paren", S, n);
        sym("\u208e", "subscript right paren", S, n);
        sym("\u00ae", "registered", S, n);
        sym("\u2122", "trademark", S, n);
        sym("\u00a9", "copyright", S, n);
        sym("\u2120", "service mark", S, n);
        sym("\u2190", "left arrow", S, n);
        sym("\u2191", "up arrow", S, n);
        sym("\u2192", "right arrow", S, n);
        sym("\u2193", "down arrow", S, n);
        sym("\u2713", "check", S, n);
        sym("\u2714", "check", S, n);
        sym("\ud83e\ude7a", "right arrow", S, n); // U+1F87A, astral: multi-char path
        sym("\u2020", "dagger", S, n);
        sym("\u2021", "double dagger", S, n);
        sym("\u2023", "triangular bullet", N, n);
        sym("\u2717", "x-shaped bullet", N, n);
        sym("\u2295", "circled plus", N, n);
        sym("\u2296", "circled minus", N, n);
        sym("\u21c4", "right arrow over left arrow", N, n);

        // Arithmetic operators.
        sym("+", "plus", S, n);
        sym("\u2212", "minus", S, n);
        sym("\u00d7", "times", S, n);
        sym("\u22c5", "times", S, n);
        sym("\u2a2f", "times", N, n);
        sym("\u2215", "divided by", S, n);
        sym("\u2044", "divided by", S, n);
        sym("\u00f7", "divided by", S, n);
        sym("\u2213", "minus or plus", S, n);
        sym("\u00b1", "plus or minus", S, n);

        // Equality and comparison.
        sym("=", "equals", S, n);
        sym("\u2243", "asymptotically equal to", N, n);
        sym("\u2244", "not asymptotically equal to", N, n);
        sym("\u2245", "approximately equal to", N, n);
        sym("\u2246", "approximately but not actually equal to", N, n);
        sym("\u2248", "almost equal to", N, n);
        sym("\u224c", "all equal to", N, n);
        sym("\u224d", "equivalent to", N, n);
        sym("\u226d", "not equivalent to", N, n);
        sym("\u224e", "geometrically equivalent to", N, n);
        sym("\u2251", "geometrically equal to", N, n);
        sym("\u225a", "equiangular to", N, n);
        sym("\u226c", "between", N, n);
        sym("\u2260", "not equal to", N, n);
        sym("\u2261", "identical to", N, n);
        sym("\u2263", "strictly identical to", N, n);
        sym("\u2262", "not identical to", N, n);
        sym("\u223c", "similar to", N, n);
        sym("\u2259", "estimates", N, n);
        sym("\u225f", "questioned equal to", N, n);
        sym("<", "less", S, n);
        sym(">", "greater", S, n);
        sym("\u2264", "less than or equal to", N, n);
        sym("\u2266", "less than or equal to", N, n);
        sym("\u226a", "much smaller than", N, n);
        sym("\u2265", "greater than or equal to", N, n);
        sym("\u2267", "greater than or equal to", N, n);
        sym("\u226b", "much bigger than", N, n);
        sym("\u2276", "less than or greater than", N, n);
        sym("\u2277", "greater than or less than", N, n);
        sym("\u226e", "not less than", N, n);
        sym("\u226f", "not greater than", N, n);

        // Logic, sets, calculus and miscellaneous math (all level NONE:
        // always spoken, exactly as in NVDA).
        sym("\u2200", "for all", N, n);
        sym("\u2203", "there exists", N, n);
        sym("\u2204", "there does not exist", N, n);
        sym("\u21cf", "does not imply", N, n);
        sym("\u21d0", "is implied by", N, n);
        sym("\u2208", "element of", N, n);
        sym("\u2209", "not an element of", N, n);
        sym("\u220a", "small element of", N, n);
        sym("\u2201", "complement of the set", N, n);
        sym("\u2216", "set minus", N, n);
        sym("\u228d", "set union", N, n);
        sym("\u2118", "power set of the set", N, n);
        sym("\ud835\udcab", "power set of the set", N, n); // U+1D4AB
        sym("\ud835\udd13", "power set of the set", N, n); // U+1D493
        sym("\ud835\udd38", "algebraic numbers", N, n); // U+1D538
        sym("\ud835\udd41", "nonnegative (whole) numbers", N, n); // U+1D541
        sym("\u220b", "contains as member", N, n);
        sym("\u220c", "does not contain as member", N, n);
        sym("\u220d", "small contains as member", N, n);
        sym("\u220e", "end of proof", N, n);
        sym("\u220f", "n-ary product", N, n);
        sym("\u2210", "n-ary coproduct", N, n);
        sym("\u2211", "n-ary summation", N, n);
        sym("\u221a", "square root", N, n);
        sym("\u221b", "cube root", N, n);
        sym("\u221c", "fourth root", N, n);
        sym("\u221d", "proportional to", N, n);
        sym("\u221e", "infinity", N, n);
        sym("\u2227", "and", N, n);
        sym("\u2228", "or", N, n);
        sym("\u00ac", "not", N, n);
        sym("\u2229", "intersection", N, n);
        sym("\u222a", "union", N, n);
        sym("\u222b", "integral", N, n);
        sym("\u222c", "double integral", N, n);
        sym("\u222d", "triple integral", N, n);
        sym("\u222e", "contour integral", N, n);
        sym("\u222f", "surface integral", N, n);
        sym("\u2230", "volume integral", N, n);
        sym("\u2231", "clockwise integral", N, n);
        sym("\u2232", "clockwise contour integral", N, n);
        sym("\u2233", "anticlockwise contour integral", N, n);
        sym("\u2234", "therefore", N, n);
        sym("\u2235", "because", N, n);
        sym("\u2236", "ratio", N, n);
        sym("\u2237", "proportion", N, n);
        sym("\u2239", "excess", N, n);
        sym("\u223a", "geometric proportion", N, n);
        sym("\u2240", "wreath product", N, n);
        sym("\u224f", "difference between", N, n);
        sym("\u2250", "approaches the limit", N, n);
        sym("\u2219", "bullet operator", N, n);
        sym("\u2223", "divides", N, n);
        sym("\u2224", "does not divide", N, n);
        sym("\u2254", "colon equals", N, n);
        sym("\u2255", "equals colon", N, n);
        sym("\u227a", "precedes", N, n);
        sym("\u227b", "succeeds", N, n);
        sym("\u2280", "does not precede", N, n);
        sym("\u2281", "does not succeed", N, n);
        sym("\u2205", "empty set", N, n);
        sym("\u2282", "subset of", N, n);
        sym("\u2284", "not a subset of", N, n);
        sym("\u2283", "superset of", N, n);
        sym("\u2285", "not a superset of", N, n);
        sym("\u2286", "subset of or equal to", N, n);
        sym("\u2288", "neither a subset of nor equal to", N, n);
        sym("\u2287", "superset of or equal to", N, n);
        sym("\u2289", "neither a superset of nor equal to", N, n);
        sym("\u228c", "multiset", N, n);
        sym("\u2202", "partial derivative", N, n);
        sym("\u2207", "gradient of", N, n);
        sym("\u2218", "ring operator", N, n);
        sym("\u20d7", "vector between", N, n);
        sym("\u25b3", "triangle", N, n);
        sym("\u25ad", "rectangle", N, n);
        sym("\u2226", "not parallel to", N, n);
        sym("\u27c2", "orthogonal to", N, n);
        sym("\u207b", "inverse", S, n);
        sym("\u25b3", "triangle", N, n);
        sym("\u25ad", "rectangle", N, n);
        sym("\u221f", "right angle", N, n);
        sym("\u2220", "angle", N, n);
        sym("\u2225", "parallel to", N, n);
        sym("\u2226", "not parallel to", N, n);
        sym("\u22a5", "perpendicular to", N, n);
        sym("\u27c2", "perpendicular to", N, n);
        sym("\u2016", "norm of vector", N, n);
        sym("\u0302", "hat", N, n);
        sym("\u223f", "sine wave", N, n);
        sym("\u2221", "measured angle", N, n);
        sym("\u2222", "spherical angle", N, n);

        // Vulgar fractions.
        sym("\u00bc", "one quarter", N, n);
        sym("\u00bd", "one half", N, n);
        sym("\u00be", "three quarters", N, n);
        sym("\u2150", "one seventh", N, n);
        sym("\u2151", "one ninth", N, n);
        sym("\u2152", "one tenth", N, n);
        sym("\u2153", "one third", N, n);
        sym("\u2154", "two thirds", N, n);
        sym("\u2155", "one fifth", N, n);
        sym("\u2156", "two fifths", N, n);
        sym("\u2157", "three fifths", N, n);
        sym("\u2158", "four fifths", N, n);
        sym("\u2159", "one sixth", N, n);
        sym("\u215a", "five sixths", N, n);
        sym("\u215b", "one eighth", N, n);
        sym("\u215c", "three eighths", N, n);
        sym("\u215d", "five eighths", N, n);
        sym("\u215e", "seven eighths", N, n);

        // Number sets and miscellaneous technical.
        sym("\u2102", "complex numbers", N, n);
        sym("\u2111", "imaginary part of complex number", N, n);
        sym("\u210d", "quaternions", N, n);
        sym("\u2115", "natural numbers", N, n);
        sym("\u211a", "rational numbers", N, n);
        sym("\u211d", "real numbers", N, n);
        sym("\u211c", "real part of complex number", N, n);
        sym("\u2124", "integers", N, n);
        sym("\u2135", "aleph number", N, n);
        sym("\u2136", "beth number", N, n);
        sym("\u2318", "mac command key", N, n);
        sym("\u2325", "mac option key", N, n);

        // Braille patterns are mechanical ("braille 1 2 3"); generate them.
        sym("⠀", "space", A, n);
        for (int cp = 0x2801; cp <= 0x28ff; cp++) {
            StringBuilder dots = new StringBuilder("braille");
            for (int bit = 0; bit < 8; bit++) {
                if ((cp & (1 << bit)) != 0) {
                    dots.append(' ').append(bit + 1);
                }
            }
            sym(new String(Character.toChars(cp)), dots.toString(), A, n);
        }
    }

    // ---- NVDA dictionary files ------------------------------------------

    private static final Map<String, Integer> LEVELS = new HashMap<String, Integer>();
    private static final Map<String, Integer> PRESERVES = new HashMap<String, Integer>();
    private static final Map<Character, String> ESCAPES = new HashMap<Character, String>();

    static {
        LEVELS.put("none", N);
        LEVELS.put("some", S);
        LEVELS.put("most", M);
        LEVELS.put("all", A);
        LEVELS.put("char", C);
        PRESERVES.put("never", n);
        PRESERVES.put("always", a);
        PRESERVES.put("norep", r);
        ESCAPES.put('0', "\0");
        ESCAPES.put('t', "\t");
        ESCAPES.put('n', "\n");
        ESCAPES.put('r', "\r");
        ESCAPES.put('f', "\f");
        ESCAPES.put('v', "\u000b");
        ESCAPES.put('#', "#");
        ESCAPES.put('\\', "\\");
    }

    /**
     * Parses an NVDA symbols.dic / cldr.dic exactly as SpeechSymbols.load
     * does: "complexSymbols:" and "symbols:" sections, tab-separated fields,
     * "-" for inherit, a trailing "# ..." field is a display name, and an
     * invalid line is skipped.
     */
    static Source parse(BufferedReader in, boolean allowComplex) throws IOException {
        final Source source = new Source();
        int section = 0; // 1 complex, 2 symbols
        boolean first = true;
        for (String line; (line = in.readLine()) != null; ) {
            if (first && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            first = false;
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("complexSymbols:") && allowComplex) {
                section = 1;
            } else if (line.equals("symbols:")) {
                section = 2;
            } else if (section == 1) {
                final String[] f = line.split("\t", -1);
                if (f.length == 2) {
                    source.complex.put(f[0], f[1]);
                }
            } else if (section == 2) {
                parseSymbol(source, line.split("\t", -1));
            }
        }
        return source;
    }

    private static void parseSymbol(Source source, String[] f) {
        int count = f.length;
        if (count > 0 && f[count - 1].startsWith("#")) {
            count--; // display name
        }
        if (count < 2 || f[0].isEmpty()) {
            return;
        }
        String id = f[0];
        if (id.startsWith("\\") && id.length() >= 2) {
            final String escape = ESCAPES.get(id.charAt(1));
            id = (escape != null ? escape : String.valueOf(id.charAt(1))) + id.substring(2);
        }
        final String replacement = "-".equals(f[1]) ? null : f[1];
        Integer level = null;
        Integer preserve = null;
        if (count > 2 && !"-".equals(f[2])) {
            level = LEVELS.get(f[2]);
            if (level == null) {
                return;
            }
        }
        if (count > 3 && !"-".equals(f[3])) {
            preserve = PRESERVES.get(f[3]);
            if (preserve == null) {
                return;
            }
        }
        source.symbols.put(id, new Entry(replacement, level, preserve));
    }

    // ---- Per-language tables --------------------------------------------

    /** Where the per-language data comes from (EspeakApp: assets/symbols/). */
    public static void setDataSource(DataSource data) {
        sData = data;
        TABLES.clear();
        TABLES_BY_LANG.clear();
        SOURCES.clear();
    }

    private static Source load(String file, boolean allowComplex) {
        final DataSource data = sData;
        if (data == null) {
            return null;
        }
        Source source = SOURCES.get(file);
        if (source == null) {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(data.open(file), StandardCharsets.UTF_8))) {
                source = parse(in, allowComplex);
            } catch (IOException e) {
                source = MISSING;
            }
            SOURCES.put(file, source);
        }
        return source == MISSING ? null : source;
    }

    /**
     * NVDA's locale for a voice language tag ("pt-br" -> "pt_br", then
     * "pt", then an alias), or English when NVDA has no symbol data for it.
     */
    static String locale(String languageTag) {
        for (String candidate : localeCandidates(languageTag)) {
            if (load(candidate + ".dic", true) != null
                    || load(candidate + ".cldr.dic", false) != null) {
                return candidate;
            }
        }
        return EN;
    }

    /**
     * NVDA locale names to try for a voice language tag, in order: "pt-br"
     * gives pt_br, pt, then pt's alias; English gives none (it is the base).
     */
    static List<String> localeCandidates(String languageTag) {
        final List<String> out = new ArrayList<String>(3);
        if (languageTag == null || languageTag.isEmpty()) {
            return out;
        }
        final String full = AsciiUtils.normalizeLocaleTag(languageTag);
        final int sep = full.indexOf('_');
        final String base = sep > 0 ? full.substring(0, sep) : full;
        if (EN.equals(base)) {
            return out;
        }
        out.add(full);
        if (!base.equals(full)) {
            out.add(base);
        }
        if (LOCALE_ALIASES.containsKey(base)) {
            out.add(LOCALE_ALIASES.get(base));
        }
        return out;
    }

    /** The merged table for a voice language (English for null/unknown). */
    static Table forLanguage(String languageTag) {
        final String tagKey = languageTag != null ? languageTag : "";
        Table cached = TABLES_BY_LANG.get(tagKey);
        if (cached != null) {
            return cached;
        }
        final String locale = locale(languageTag);
        Table table = TABLES.get(locale);
        if (table == null) {
            final List<Source> sources = new ArrayList<Source>();
            if (!EN.equals(locale)) {
                addIfPresent(sources, load(locale + ".dic", true));
                addIfPresent(sources, load(locale + ".cldr.dic", false));
            }
            sources.add(ENGLISH);
            addIfPresent(sources, load("en.dic", true));
            addIfPresent(sources, load("en.cldr.dic", false));
            table = new Table(sources);
            TABLES.put(locale, table);
        }
        TABLES_BY_LANG.put(tagKey, table);
        return table;
    }

    private static void addIfPresent(List<Source> sources, Source source) {
        if (source != null) {
            sources.add(source);
        }
    }

    /** Builds the table now, off the speech path (service start). */
    public static void warmup(String languageTag) {
        forLanguage(languageTag);
    }

    /**
     * NVDA complex-symbol patterns this device's regex engine (ICU on
     * Android) refused for a language; empty when all compiled.
     */
    public static List<String> skippedPatterns(String languageTag) {
        return Collections.unmodifiableList(forLanguage(languageTag).skipped);
    }

    /** One language's merged symbols, NVDA's SpeechSymbolProcessor. */
    static final class Table {
        private final Map<String, Symbol> simple = new HashMap<String, Symbol>();
        private final Symbol[] asciiSimple = new Symbol[128];
        private final List<Complex> complex = new ArrayList<Complex>();
        /** Complex symbols dropped because Java/ICU could not compile them. */
        final List<String> skipped = new ArrayList<String>();
        private final Pattern masterCollapse;
        private final Pattern masterNoCollapse;
        private final int rstripGroup;
        private final int repeatedGroup;
        private final int simpleGroupCollapse;
        private final int simpleGroupNoCollapse;
        /**
         * Repeat runs only, for the condensing entry point. Whitespace is
         * excluded; emoji never matches (no emoji in the table) and is
         * condensed separately.
         */
        private final Pattern repeatsOnly;

        Table(List<Source> sources) {
            // NVDA's merge: complex symbols from every source first (first
            // definition wins), then every source's symbols, each field
            // taken from the first source that sets it.
            final Map<String, String[]> fields = new LinkedHashMap<String, String[]>();
            final Map<String, Integer[]> numbers = new HashMap<String, Integer[]>();
            final Map<String, String> patterns = new LinkedHashMap<String, String>();
            final Map<String, String> samples = new HashMap<String, String>();
            for (Source source : sources) {
                for (Map.Entry<String, String> e : source.complex.entrySet()) {
                    if (!patterns.containsKey(e.getKey())) {
                        patterns.put(e.getKey(), e.getValue());
                        fields.put(e.getKey(), new String[1]);
                        numbers.put(e.getKey(), new Integer[2]);
                    }
                    if (!samples.containsKey(e.getKey()) && source.samples.containsKey(e.getKey())) {
                        samples.put(e.getKey(), source.samples.get(e.getKey()));
                    }
                }
            }
            for (Source source : sources) {
                for (Map.Entry<String, Entry> e : source.symbols.entrySet()) {
                    final String id = e.getKey();
                    if (id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0) {
                        continue; // paragraph pauses, see the class comment
                    }
                    String[] f = fields.get(id);
                    Integer[] num = numbers.get(id);
                    if (f == null) {
                        f = new String[1];
                        num = new Integer[2];
                        fields.put(id, f);
                        numbers.put(id, num);
                    }
                    final Entry entry = e.getValue();
                    if (f[0] == null) {
                        f[0] = entry.replacement;
                    }
                    if (num[0] == null) {
                        num[0] = entry.level;
                    }
                    if (num[1] == null) {
                        num[1] = entry.preserve;
                    }
                }
            }
            final List<String> multi = new ArrayList<String>();
            final List<String> singles = new ArrayList<String>();
            for (Map.Entry<String, String[]> e : fields.entrySet()) {
                final String id = e.getKey();
                final String replacement = e.getValue()[0];
                if (replacement == null) {
                    continue; // NVDA drops symbols nobody named
                }
                final Integer[] num = numbers.get(id);
                final int level = num[0] != null ? num[0] : A;
                final int preserve = num[1] != null ? num[1] : n;
                final String regex = patterns.get(id);
                if (regex != null) {
                    final String sample = samples.containsKey(id) ? samples.get(id) : sampleOf(id);
                    complex.add(new Complex(regex, sample, replacement, level, preserve, 0));
                    continue;
                }
                Symbol sym = new Symbol(id, replacement, level, preserve);
                simple.put(id, sym);
                if (id.length() == 1) {
                    singles.add(id);
                    char ch = id.charAt(0);
                    if (ch < 128) {
                        asciiSimple[ch] = sym;
                    }
                } else {
                    multi.add(id);
                }
            }
            // A pattern Java/ICU cannot compile (Python-only syntax) is
            // dropped on its own rather than losing the whole language.
            for (int i = complex.size() - 1; i >= 0; i--) {
                try {
                    Pattern p = Pattern.compile(complex.get(i).regex);
                    Complex old = complex.get(i);
                    complex.set(i, new Complex(old.regex, old.sample, old.replacement, old.level, old.preserve, p.matcher("").groupCount()));
                } catch (RuntimeException e) {
                    skipped.add(complex.get(i).regex);
                    complex.remove(i);
                }
            }
            // Longest first, so "==" wins over "=" and "..." over ".".
            Collections.sort(multi, new Comparator<String>() {
                @Override
                public int compare(String x, String y) {
                    return y.length() - x.length();
                }
            });
            Collections.sort(singles);
            final StringBuilder alternation = new StringBuilder();
            for (String id : multi) {
                if (alternation.length() > 0) {
                    alternation.append('|');
                }
                alternation.append(Pattern.quote(id));
            }
            // Consecutive characters as ranges (braille is one): the JDK
            // chains one predicate per class item, and a merged table's
            // thousand-odd items overflowed the stack while matching.
            final StringBuilder cls = new StringBuilder("[");
            final StringBuilder repeats = new StringBuilder("(?<run>");
            boolean firstRepeat = true;
            for (int i = 0; i < singles.size(); ) {
                final char from = singles.get(i).charAt(0);
                char to = from;
                while (i + 1 < singles.size() && singles.get(i + 1).charAt(0) == to + 1) {
                    to = singles.get(++i).charAt(0);
                }
                i++;
                cls.append(escapeChar(from));
                if (to > from) {
                    cls.append('-').append(escapeChar(to));
                }
            }
            cls.append(']');
            for (String id : singles) {
                if (id.charAt(0) > ' ') {
                    repeats.append(firstRepeat ? "" : "|").append(escapeChar(id.charAt(0)));
                    firstRepeat = false;
                }
            }
            repeats.append(")\\k<run>{3,}");
            final String singleClass = singles.isEmpty() ? "(?!)" : cls.toString();

            int grp = 1;
            for (int i = 0; i < complex.size(); i++) {
                final Complex c = complex.get(i);
                c.group = grp;
                grp += 1 + c.internalGroups;
            }
            rstripGroup = grp;
            repeatedGroup = grp + 1;
            simpleGroupCollapse = grp + 3;
            simpleGroupNoCollapse = grp + 1;

            masterCollapse = Pattern.compile(master(true, singleClass, alternation.toString()));
            masterNoCollapse = Pattern.compile(master(false, singleClass, alternation.toString()));
            repeatsOnly = Pattern.compile(firstRepeat ? "(?!)" : repeats.toString());
        }

        /** \x{...} for anything but letters and digits: safe in Java and ICU classes. */
        private static String escapeChar(char c) {
            return Character.isLetterOrDigit(c) ? String.valueOf(c)
                    : "\\x{" + Integer.toHexString(c) + "}";
        }

        private String master(boolean collapseRepeats, String singleClass, String alternation) {
            final StringBuilder rx = new StringBuilder();
            for (int i = 0; i < complex.size(); i++) {
                final Complex c = complex.get(i);
                if (i > 0) {
                    rx.append('|');
                }
                // Group names must be valid Java identifiers: c0, c1, ...
                rx.append("(?<c").append(i).append('>').append(c.regex).append(')');
            }
            rx.append(complex.isEmpty() ? "" : "|").append("(?<rstrip>  +$)");
            if (collapseRepeats) {
                rx.append("|(?<repeated>(?<repTmp>").append(singleClass).append(")\\k<repTmp>{3,})");
            }
            rx.append("|(?<simple>");
            if (alternation.length() > 0) {
                rx.append(alternation).append('|');
            }
            rx.append(singleClass).append(')');
            return rx.toString();
        }

        boolean isAnnounced(String symbol, int userLevel, String customChars) {
            Symbol s = null;
            if (symbol.length() == 1) {
                char ch = symbol.charAt(0);
                if (ch < 128) {
                    s = asciiSimple[ch];
                }
            }
            if (s == null) {
                s = simple.get(symbol);
            }
            if (s == null || s.replacement == null || s.replacement.isEmpty()) {
                return false;
            }
            if (customChars != null) {
                return customChars.contains(symbol);
            }
            return userLevel >= s.level;
        }

        String process(String text, int userLevel, boolean collapseRepeats, Set<Integer> custom) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            return AsciiUtils.replaceMatches(
                    (collapseRepeats ? masterCollapse : masterNoCollapse), text,
                    m -> replaceMatch(m, userLevel, collapseRepeats, custom));
        }

        private String replaceMatch(Matcher m, int userLevel, boolean collapseRepeats,
                Set<Integer> custom) {
            final int simpleGroup = collapseRepeats ? simpleGroupCollapse : simpleGroupNoCollapse;
            if (m.start(simpleGroup) >= 0) {
                final String text = m.group(simpleGroup);
                Symbol symbol = null;
                if (text.length() == 1) {
                    char ch = text.charAt(0);
                    if (ch < 128) {
                        symbol = asciiSimple[ch];
                    }
                }
                if (symbol == null) {
                    symbol = simple.get(text);
                }
                if (symbol == null) {
                    return text;
                }
                return symbolOutput(text, text, symbol.replacement, symbol.level, symbol.preserve,
                        userLevel, custom);
            }

            if (m.start(rstripGroup) >= 0) {
                return "";
            }

            if (collapseRepeats && m.start(repeatedGroup) >= 0) {
                final String run = m.group(repeatedGroup);
                char rc = run.charAt(0);
                Symbol symbol = (rc < 128) ? asciiSimple[rc] : null;
                if (symbol == null) {
                    symbol = simple.get(String.valueOf(rc));
                }
                if (symbol == null) {
                    return run;
                }
                final int effective = custom == null ? symbol.level
                        : (matchesCustom(String.valueOf(rc), custom) ? LEVEL_ALWAYS
                                : LEVEL_NEVER);
                if (userLevel >= effective) {
                    return "  " + run.length() + " " + symbol.replacement + " ";
                }
                if (symbol.preserve == PRESERVE_ALWAYS || symbol.preserve == PRESERVE_NOREP) {
                    return run;
                }
                return " ";
            }

            for (int i = 0; i < complex.size(); i++) {
                final Complex c = complex.get(i);
                if (m.start(c.group) >= 0) {
                    return symbolOutput(m.group(), c.sample, replaceGroups(m, c),
                            c.level, c.preserve, userLevel, custom);
                }
            }

            return m.group();
        }

        /** NVDA's _replaceGroups: \1..\9 are the symbol's own groups, \\ a backslash. */
        private static String replaceGroups(Matcher m, Complex c) {
            final String replacement = c.replacement;
            if (replacement.indexOf('\\') < 0) {
                return replacement;
            }
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < replacement.length(); i++) {
                final char ch = replacement.charAt(i);
                if (ch != '\\' || i + 1 >= replacement.length()) {
                    out.append(ch);
                    continue;
                }
                final char next = replacement.charAt(++i);
                if (next >= '0' && next <= '9') {
                    final String group = m.group(c.group + (next - '0'));
                    out.append(group != null ? group : "");
                } else {
                    out.append(next);
                }
            }
            return out.toString();
        }

        String collapseRepeatRuns(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            return AsciiUtils.replaceMatches(repeatsOnly, text, m -> {
                // Whole match, not group("run"): the group only holds the first
                // character, the backreference holds the rest.
                final String run = m.group();
                char rc = run.charAt(0);
                Symbol symbol = (rc < 128) ? asciiSimple[rc] : null;
                if (symbol == null) {
                    symbol = simple.get(String.valueOf(rc));
                }
                if (symbol == null || symbol.replacement == null || symbol.replacement.isEmpty()) {
                    return run;
                }
                return "  " + run.length() + " " + symbol.replacement + " ";
            });
        }

        String processSingleSymbol(String text) {
            if (text == null) {
                return null;
            }
            final String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) != 1) {
                return text;
            }
            Symbol symbol = null;
            if (trimmed.length() == 1) {
                char ch = trimmed.charAt(0);
                if (ch < 128) {
                    symbol = asciiSimple[ch];
                }
            }
            if (symbol == null) {
                symbol = simple.get(trimmed);
            }
            if (symbol == null || symbol.replacement == null || symbol.replacement.isEmpty()) {
                return text;
            }
            return symbol.replacement;
        }
    }

    /**
     * Custom-list text for a complex symbol NVDA names by words: its first
     * character that is not a letter or space (". sentence ending" -> "."),
     * else the identifier itself.
     */
    private static String sampleOf(String id) {
        for (int i = 0; i < id.length(); ) {
            final int cp = id.codePointAt(i);
            if (!Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp)) {
                return new String(Character.toChars(cp));
            }
            i += Character.charCount(cp);
        }
        return id;
    }

    private static boolean matchesCustom(String id, Set<Integer> custom) {
        for (int i = 0; i < id.length();) {
            int cp = id.codePointAt(i);
            if (!custom.contains(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static String symbolOutput(String text, String matchId, String replacement, int level,
            int preserve, int userLevel, Set<Integer> custom) {
        int effective = custom == null ? level
                : (matchesCustom(matchId, custom) ? LEVEL_ALWAYS : LEVEL_NEVER);
        String suffix = (preserve == PRESERVE_ALWAYS
                || (preserve == PRESERVE_NOREP && userLevel < effective)) ? text : " ";
        if (userLevel >= effective && replacement != null && !replacement.isEmpty()) {
            return " " + replacement + suffix;
        }
        return suffix;
    }

    private NvdaSymbolProcessor() {
    }

    // ---- Public API: English unless a language tag is given --------------

    /**
     * Whether a plain symbol is named at this level, or with a custom list
     * (customChars non-null) whether the list has it. Used by Earcons to swap
     * exactly the symbols this pass would have spoken.
     */
    public static boolean isAnnounced(String symbol, int userLevel, String customChars) {
        return isAnnounced(symbol, userLevel, customChars, EN);
    }

    public static boolean isAnnounced(String symbol, int userLevel, String customChars,
            String languageTag) {
        return forLanguage(languageTag).isAnnounced(symbol, userLevel, customChars);
    }

    /**
     * Processes text the way NVDA does: complex symbols, trailing-space
     * strip, optional repeat collapsing, then plain symbols longest-first.
     *
     * @param text the text to process (null passes through as null)
     * @param userLevel one of LEVEL_NONE/SOME/MOST/ALL
     * @param collapseRepeats whether 4+ runs collapse to "N name"
     * @return the processed text
     */
    public static String processText(String text, int userLevel, boolean collapseRepeats) {
        return processText(text, userLevel, collapseRepeats, EN);
    }

    /** {@link #processText(String, int, boolean)} with the voice language's symbol names. */
    public static String processText(String text, int userLevel, boolean collapseRepeats,
            String languageTag) {
        return forLanguage(languageTag).process(text, userLevel, collapseRepeats, null);
    }

    /**
     * Custom-list mode: exactly the given characters are announced (with
     * their table names); every other known symbol degrades to keep-or-pause
     * and is never announced.
     *
     * @param customChars characters the user chose to hear, e.g. ".?!"
     */
    public static String processCustom(String text, String customChars, boolean collapseRepeats) {
        return processCustom(text, customChars, collapseRepeats, EN);
    }

    public static String processCustom(String text, String customChars, boolean collapseRepeats,
            String languageTag) {
        if (text == null) {
            return null;
        }
        Set<Integer> custom = new HashSet<Integer>();
        if (customChars != null) {
            for (int i = 0; i < customChars.length();) {
                int cp = customChars.codePointAt(i);
                custom.add(cp);
                i += Character.charCount(cp);
            }
        }
        return forLanguage(languageTag).process(text, LEVEL_ALL, collapseRepeats, custom);
    }

    /**
     * Repeat collapsing alone (" 20 dash "), without any other symbol
     * rewriting: the condensing entry point's contract. Runs need 4+
     * identical characters (NVDA's rule); anything else passes through,
     * so sentences keep their final punctuation here.
     */
    public static String collapseRepeatRuns(String text) {
        return collapseRepeatRuns(text, EN);
    }

    public static String collapseRepeatRuns(String text, String languageTag) {
        return forLanguage(languageTag).collapseRepeatRuns(text);
    }

    /**
     * NVDA's processSpeechSymbol equivalent for character navigation: the
     * table name of a lone character, or the character itself when unknown.
     * Whitespace-only input passes through (navigating onto a space must
     * not announce "space" here; the engine handles the pause).
     */
    public static String processSingleSymbol(String text) {
        return processSingleSymbol(text, EN);
    }

    public static String processSingleSymbol(String text, String languageTag) {
        return forLanguage(languageTag).processSingleSymbol(text);
    }
}
