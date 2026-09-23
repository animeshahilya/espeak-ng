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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Behavioral notes (deliberate, tested):
 * <ul>
 *   <li>Levels are NONE=0, SOME=100, MOST=200, ALL=300. A symbol is spoken
 *   exactly when {@code userLevel >= symbol.level}; level NONE therefore
 *   means "always spoken" (decimal points stay silent via their empty
 *   replacement instead). This matches NVDA, where e.g. math symbols and
 *   negative-number "minus" announce at every level.</li>
 *   <li>The data below is NVDA's English table, re-expressed (not copied):
 *   same identifiers, levels, preserve modes and replacements, with a few
 *   normalized wordings ("tilde" not "tilda", "divided by" not "divide by",
 *   hyphen repairs in "less than or equal to").</li>
 *   <li>NVDA has no ASCII programming digraphs ({@code !=}}, {@code ==}},
 *   {@code &&}}, ...); they are kept here as complex-style multi-character
 *   symbols at level ALL, since NVDA would otherwise read "!=" as
 *   "bang equals". Likewise {@code =>} keeps "implies" (NVDA's table only
 *   knows the {@code =>} glyph as "double right arrow").</li>
 *   <li>{@code \n} and {@code \r} are intentionally absent from the table:
 *   this engine renders paragraph pauses from newlines, so they always pass
 *   through untouched (see TextPipelineDeviceTest).</li>
 *   <li>Braille patterns (U+2800-U+28FF) are generated, not listed: the
 *   mapping is mechanical ("braille 1 2 3").</li>
 *   <li>Emoji naming (NVDA's CLDR dictionary) lives in {@link NvdaEmoji} /
 *   {@code NvdaEmojiTable} and backs Announce mode; glue codepoints with no
 *   name (joiners, variation selectors, lone regional indicators) are
 *   dropped, matching what NVDA's pass-through amounts to audibly.</li>
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
        final Pattern pattern;
        /** Representative text for custom-list matching (the id is a name). */
        final String sample;
        final String replacement;
        final int level;
        final int preserve;

        Complex(String regex, String sample, String replacement, int level, int preserve) {
            this.pattern = Pattern.compile(regex);
            this.sample = sample;
            this.replacement = replacement;
            this.level = level;
            this.preserve = preserve;
        }
    }

    private static final Map<String, Symbol> SIMPLE = new HashMap<String, Symbol>();
    private static final List<Complex> COMPLEX = new ArrayList<Complex>();
    private static final List<String> MULTI = new ArrayList<String>();
    private static String singleCharClass;
    private static String multiAlternation;

    private static Pattern masterCollapse;
    private static Pattern masterNoCollapse;
    /**
     * Repeat runs only, for the condensing entry point. Whitespace is
     * excluded (the old patterns never matched it either); emoji never
     * matches (no emoji in the table) and is condensed separately.
     */
    private static Pattern repeatsOnly;

    private static void sym(String id, String replacement, int level, int preserve) {
        Symbol s = new Symbol(id, replacement, level, preserve);
        SIMPLE.put(id, s);
        if (id.length() > 1) {
            MULTI.add(id);
        }
    }

    private static void complex(String regex, String sample, String replacement, int level,
            int preserve) {
        COMPLEX.add(new Complex(regex, sample, replacement, level, preserve));
    }

    static {
        buildTable();
        buildPatterns();
    }

    private static void buildTable() {
        // Complex symbols first: sentence/phrase endings, visual dot runs,
        // decimal points, in-word apostrophes, negative numbers. The "//"
        // guard is split in two because Java lookbehind needs fixed length
        // (NVDA's "(?<!https?:)" is variable length and won't compile).
        complex("(?<!https:)(?<!http:)//", "/", "double slash", A, n);
        complex("(?<=[^\\s.])\\.(?=[\"'\"\u201d\u2019)\\s]|$)", ".", "dot", A, a);
        complex("(?<=[^\\s!])!(?=[\"'\"\u201d\u2019)\\s]|$)", "!", "bang", A, a);
        complex("(?<=[^\\s?])\\?(?=[\"'\"\u201d\u2019)\\s]|$)", "?", "question", A, a);
        complex("(?<=[^\\s;]);(?=\\s|$)", ";", "semi", M, a);
        complex("(?<=[^\\s:]):(?=\\s|$)", ":", "colon", M, a);
        complex("\\.{4,}", "...", "multiple dots", A, a);
        complex("(?<![^\\d -])\\.(?=\\d)", ".", "", N, a);
        // [\p{L}\p{N}] approximates NVDA's [^\W_]: Unicode letters and
        // digits, no underscore. Java's \W is ASCII-only, so [^\W_] would
        // wrongly match after Devanagari/CJK letters.
        complex("(?<=[\\p{L}\\p{N}])['\u2019]", "'", "tick", A, r);
        complex("(?<!\\w)[-\u2212]{1}(?=[$\u00a3\u20ac\u00a5.]?\\d)", "-", "minus", N, r);
        // Bare "*" between words is a star; between digits it is times.
        complex("(?<=\\d)\\s*\\*\\s*(?=\\d)", "*", "times", S, n);

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
        sym("\uff01", "fullwidth exclamation mark", A, n);
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
        sym("\u060c", "arabic comma", A, a);
        sym("-", "dash", M, a);
        sym(".", "dot", S, n);
        sym("\uff0e", "fullwidth full stop", S, n);
        sym("/", "slash", S, n);
        sym(":", "colon", M, r);
        sym("\uff1a", "fullwidth colon", M, r);
        sym(";", "semi", M, n);
        sym("\u061b", "arabic semicolon", M, n);
        sym("\uff1b", "fullwidth semicolon", M, n);
        sym("?", "question", A, n);
        sym("\u061f", "arabic question mark", A, n);
        sym("\uff1f", "fullwidth question mark", A, n);
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
        SIMPLE.put("\u2800", new Symbol("\u2800", "space", A, n));
        for (int cp = 0x2801; cp <= 0x28ff; cp++) {
            StringBuilder dots = new StringBuilder("braille");
            for (int bit = 0; bit < 8; bit++) {
                if ((cp & (1 << bit)) != 0) {
                    dots.append(' ').append(bit + 1);
                }
            }
            String id = new String(Character.toChars(cp));
            SIMPLE.put(id, new Symbol(id, dots.toString(), A, n));
        }
    }

    private static void buildPatterns() {
        // Longest first, so "==" wins over "=" and "..." over ".".
        Collections.sort(MULTI, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        StringBuilder multi = new StringBuilder();
        for (int i = 0; i < MULTI.size(); i++) {
            if (i > 0) {
                multi.append('|');
            }
            multi.append(Pattern.quote(MULTI.get(i)));
        }
        multiAlternation = multi.toString();

        // Single characters as a character class. Newlines are absent from
        // the table by design (paragraph pauses); guard anyway.
        StringBuilder cls = new StringBuilder("[");
        List<String> singles = new ArrayList<String>();
        for (String id : SIMPLE.keySet()) {
            if (id.length() == 1 && !id.equals("\n") && !id.equals("\r")) {
                singles.add(id);
            }
        }
        Collections.sort(singles);
        for (String id : singles) {
            char c = id.charAt(0);
            if (c == '\\' || c == '^' || c == '-' || c == '[' || c == ']' || c == '&') {
                cls.append('\\');
            }
            cls.append(c);
        }
        cls.append(']');
        singleCharClass = cls.toString();

        masterCollapse = Pattern.compile(buildMaster(true));
        masterNoCollapse = Pattern.compile(buildMaster(false));

        StringBuilder rep = new StringBuilder("(?<run>");
        boolean first = true;
        for (String id : singles) {
            char c = id.charAt(0);
            if (c <= ' ') {
                continue;
            }
            if (!first) {
                rep.append('|');
            }
            rep.append(Pattern.quote(id));
            first = false;
        }
        rep.append(")\\k<run>{3,}");
        repeatsOnly = Pattern.compile(rep.toString());
    }

    private static String buildMaster(boolean collapseRepeats) {
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < COMPLEX.size(); i++) {
            if (i > 0) {
                rx.append('|');
            }
            // Group names must be valid Java identifiers: c0, c1, ...
            rx.append("(?<c").append(i).append('>');
            rx.append(COMPLEX.get(i).pattern.pattern());
            rx.append(')');
        }
        rx.append("|(?<rstrip>  +$)");
        if (collapseRepeats) {
            rx.append("|(?<repeated>(?<repTmp>").append(singleCharClass).append(")\\k<repTmp>{3,})");
        }
        rx.append("|(?<simple>").append(multiAlternation).append('|').append(singleCharClass).append(')');
        return rx.toString();
    }

    private NvdaSymbolProcessor() {
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
        return processText(text, userLevel, collapseRepeats, null);
    }

    /**
     * Custom-list mode: exactly the given characters are announced (with
     * their table names); every other known symbol degrades to keep-or-pause
     * and is never announced.
     *
     * @param customChars characters the user chose to hear, e.g. ".?!"
     */
    public static String processCustom(String text, String customChars, boolean collapseRepeats) {
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
        return processText(text, LEVEL_ALL, collapseRepeats, custom);
    }

    private static String processText(String text, int userLevel, boolean collapseRepeats,
            Set<Integer> custom) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Pattern master = collapseRepeats ? masterCollapse : masterNoCollapse;
        Matcher m = master.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length() + 16);
        do {
            String replacement = replaceMatch(m, userLevel, collapseRepeats, custom);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceMatch(Matcher m, int userLevel, boolean collapseRepeats,
            Set<Integer> custom) {
        if (m.group("rstrip") != null) {
            return "";
        }
        if (collapseRepeats && m.group("repeated") != null) {
            String run = m.group("repeated");
            Symbol symbol = SIMPLE.get(String.valueOf(run.charAt(0)));
            int effective = custom == null ? symbol.level
                    : (matchesCustom(String.valueOf(run.charAt(0)), custom) ? LEVEL_ALWAYS
                            : LEVEL_NEVER);
            if (userLevel >= effective) {
                return "  " + run.length() + " " + symbol.replacement + " ";
            }
            if (symbol.preserve == PRESERVE_ALWAYS || symbol.preserve == PRESERVE_NOREP) {
                return run;
            }
            return " ";
        }
        for (int i = 0; i < COMPLEX.size(); i++) {
            if (m.group("c" + i) != null) {
                Complex c = COMPLEX.get(i);
                return symbolOutput(m.group(), c.sample, c.replacement, c.level, c.preserve,
                        userLevel, custom);
            }
        }
        String text = m.group("simple");
        Symbol symbol = SIMPLE.get(text);
        return symbolOutput(text, text, symbol.replacement, symbol.level, symbol.preserve,
                userLevel, custom);
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

    /**
     * Repeat collapsing alone (" 20 dash "), without any other symbol
     * rewriting: the condensing entry point's contract. Runs need 4+
     * identical characters (NVDA's rule); anything else passes through,
     * so sentences keep their final punctuation here.
     */
    public static String collapseRepeatRuns(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = repeatsOnly.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length() + 16);
        do {
            // Whole match, not group("run"): the group only holds the first
            // character, the backreference holds the rest.
            String run = m.group();
            Symbol symbol = SIMPLE.get(String.valueOf(run.charAt(0)));
            m.appendReplacement(sb,
                    Matcher.quoteReplacement("  " + run.length() + " " + symbol.replacement + " "));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * NVDA's processSpeechSymbol equivalent for character navigation: the
     * table name of a lone character, or the character itself when unknown.
     * Whitespace-only input passes through (navigating onto a space must
     * not announce "space" here; the engine handles the pause).
     */
    public static String processSingleSymbol(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) != 1) {
            return text;
        }
        Symbol symbol = SIMPLE.get(trimmed);
        if (symbol == null || symbol.replacement == null || symbol.replacement.isEmpty()) {
            return text;
        }
        return symbol.replacement;
    }

    /** True when the text contains anything the processor would rewrite. */
    public static boolean containsKnownSymbol(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return masterCollapse.matcher(text).find();
    }
}
