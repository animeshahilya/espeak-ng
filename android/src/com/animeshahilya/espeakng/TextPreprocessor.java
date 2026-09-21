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

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified text preprocessing pipeline for speech synthesis.
 * Handles symbol expansion, numerical and currency verbalization,
 * smart codes, emojis, and script normalizations with offset tracking.
 */
public final class TextPreprocessor {

    public static final class Result {
        public final String text;
        public final TextOffsetMap offsetMap;
        public final boolean isSingleCharacterUtterance;

        public Result(String text, TextOffsetMap offsetMap, boolean isSingleCharacterUtterance) {
            this.text = text;
            this.offsetMap = offsetMap;
            this.isSingleCharacterUtterance = isSingleCharacterUtterance;
        }
    }

    private TextPreprocessor() {
    }

    // ==========================================
    // Regular Expressions & Constants
    // ==========================================

    private static final Pattern HANG_CONTROLS =
            Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern EDGE_BRACKET_RUN =
            Pattern.compile("\\[{2,}|\\]{2,}");

    public static final int MAX_CHUNK_CHARS = 800;
    public static final int MAX_REQUEST_CHARS = 300000;
    public static final int MAX_CHUNKS = MAX_REQUEST_CHARS / MAX_CHUNK_CHARS + 1;

    private static final Pattern SMART_CODE_KEYWORD =
            Pattern.compile("(?i)\\b(otp|pin|pincode|passcode|password|secret|verification|security|token|login|id|txn|ref|vpa|cvv|pnr|aadhaar|aadhar|challan|account|acct|acc)\\b");
    private static final Pattern DANDA_BOUNDARY =
            Pattern.compile("([।॥])([^\\s])");
    private static final Pattern BANKING_SLASH_TXN =
            Pattern.compile("(?i)\\b(UPI|TXN|REF|IMPS|NEFT|RTGS)/([A-Za-z0-9/]+)");
    private static final Pattern CURRENCY_PREFIX =
            Pattern.compile("(?i)(?:₹\\s*|\\b(?:Rs\\.?|Re\\.?|INR)\\s*)([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)(?:\\s*(k|l|lac|lakhs?|cr|crores?))?\\b(?:\\s*(?:/-|/=|--))?");
    private static final Pattern INDIAN_NUMBER_COMMAS =
            Pattern.compile("\\b(\\d{1,2}(?:,\\d{2})+),(\\d{3})\\b");
    private static final Pattern SHORTHAND_THOUSAND =
            Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*k\\b");
    private static final Pattern SHORTHAND_LAKH =
            Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:l|lac|lakh|lakhs)\\b");
    private static final Pattern SHORTHAND_CRORE =
            Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:cr|crore|crores)\\b");
    private static final Pattern SLASH_RUN =
            Pattern.compile("/+");

    private static final Pattern CURRENCY_DOLLAR_PREFIX =
            Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)");
    private static final Pattern CURRENCY_DOLLAR_SUFFIX =
            Pattern.compile("([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:dollars?|USD)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_EURO =
            Pattern.compile("(?:€\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:€|euros?|EUR\\b))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_POUND =
            Pattern.compile("(?:£\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:£|pounds?|GBP\\b))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_YEN =
            Pattern.compile("(?:¥\\s*([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)|([0-9]+(?:,[0-9]+)*(?:\\.[0-9]+)?)\\s*(?:¥|yen|JPY\\b))", Pattern.CASE_INSENSITIVE);

    private static final Pattern TIME_HM =
            Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([AP])\\.?M\\.?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_NUMERIC =
            Pattern.compile("\\b(\\d{1,4})[/\\-](\\d{1,2})[/\\-](\\d{1,4})\\b");

    private static final Pattern SYM_NOT_EQUAL = Pattern.compile("!=|≠");
    private static final Pattern SYM_DOUBLE_EQUALS = Pattern.compile("==");
    private static final Pattern SYM_LESS_EQUAL = Pattern.compile("<=|≤");
    private static final Pattern SYM_GREATER_EQUAL = Pattern.compile(">=|≥");
    private static final Pattern SYM_FAT_ARROW = Pattern.compile("=>|⇒");
    private static final Pattern SYM_THIN_ARROW = Pattern.compile("->|→");
    private static final Pattern SYM_LEFT_ARROW = Pattern.compile("<-|←");
    private static final Pattern SYM_UP_ARROW = Pattern.compile("↑");
    private static final Pattern SYM_DOWN_ARROW = Pattern.compile("↓");
    private static final Pattern SYM_LOGICAL_AND = Pattern.compile("&&");
    private static final Pattern SYM_LOGICAL_OR = Pattern.compile("\\|\\|");
    private static final Pattern SYM_COMMENT_START = Pattern.compile("/\\*");
    private static final Pattern SYM_COMMENT_END = Pattern.compile("\\*/");
    private static final Pattern SYM_DOUBLE_SLASH = Pattern.compile("(?<!https?:)//");
    private static final Pattern SYM_ELLIPSIS = Pattern.compile("\\.{3,}|…");
    private static final Pattern SYM_PLUS_MINUS = Pattern.compile("±|\\+/-");
    private static final Pattern SYM_TIMES = Pattern.compile("(?<=\\d)\\s*[×*]\\s*(?=\\d)");
    private static final Pattern SYM_DIVIDE = Pattern.compile("(?<=\\d)\\s*÷\\s*(?=\\d)|÷");
    private static final Pattern SYM_ALMOST_EQUAL = Pattern.compile("≈");
    private static final Pattern SYM_CHECKMARK = Pattern.compile("[✓✔]");
    private static final Pattern SYM_BULLET = Pattern.compile("[•⁃◦]");
    private static final Pattern SYM_DEGREES = Pattern.compile("(?<=\\d)°");
    private static final Pattern SYM_SQRT = Pattern.compile("√");
    private static final Pattern SYM_INFINITY = Pattern.compile("∞");
    private static final Pattern SYM_INTEGRAL = Pattern.compile("∫");
    private static final Pattern SYM_FOR_ALL = Pattern.compile("∀");
    private static final Pattern SYM_EXISTS = Pattern.compile("∃");
    private static final Pattern SYM_NOT_ELEMENT_OF = Pattern.compile("∉");
    private static final Pattern SYM_ELEMENT_OF = Pattern.compile("∈");
    private static final Pattern SYM_UNION = Pattern.compile("∪");
    private static final Pattern SYM_INTERSECTION = Pattern.compile("∩");
    private static final Pattern SYM_LOGICAL_NOT = Pattern.compile("¬");
    private static final Pattern SYM_SET_AND = Pattern.compile("∧");
    private static final Pattern SYM_SET_OR = Pattern.compile("∨");
    private static final Pattern SYM_CENT = Pattern.compile("¢");
    private static final Pattern SYM_YEN = Pattern.compile("¥");
    private static final Pattern SYM_FLORIN = Pattern.compile("ƒ");

    private static final Pattern PATTERN_ROMAN_CONTEXT = Pattern.compile(
            "\\b(Chapter|Part|Section|Volume|Book|Act|Scene|Title|Grade|Level|Phase|World War|War|Super Bowl)\\s+([IVXLCDMivxlcdm]+)\\b" +
            "|\\b(King|Queen|Pope|Emperor)\\s+(?:(?-i:([A-Z][a-zA-Z'-]*))\\s+)?(?-i:([IVXLCDM]+))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PATTERN_URL = Pattern.compile(
            "\\b(?:https?://|www\\.)[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PATTERN_REPEATED_CHARS = Pattern.compile("([^\\s\\p{L}\\p{N}])\\1{2,}");
    private static final Pattern PATTERN_REPEATED_CHARS_TRUNCATE = Pattern.compile("([^\\s\\p{N}])\\1{3,}");
    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    private static final String[] NATO_PHONETICS = {
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf",
            "Hotel", "India", "Juliett", "Kilo", "Lima", "Mike", "November",
            "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"
    };

    // ==========================================
    // Core Pipeline Execution
    // ==========================================

    public static Result process(
            String text,
            Voice voice,
            VoiceSettings settings,
            boolean isSsml,
            TextOffsetMap initialOffsetMap,
            Context storageContext) {

        TextOffsetMap offsetMap = initialOffsetMap;

        if (!isSsml) {
            if (text.indexOf('\u0001') != -1) {
                String before = text;
                text = text.replace("\u0001", "");
                offsetMap = chainOffset(offsetMap, before, text);
            }
            String beforeSurrogates = text;
            text = stripUnpairedSurrogates(text);
            offsetMap = chainOffset(offsetMap, beforeSurrogates, text);
            if (text.contains("[[")) {
                String before = text;
                text = text.replace("[[", "[ [");
                offsetMap = chainOffset(offsetMap, before, text);
            }
        }

        if (settings.isUnicodeNormalizationEnabled()) {
            UnicodeNormalization.Result normalization = UnicodeNormalization.normalize(text);
            if (normalization != null) {
                text = normalization.text;
                offsetMap = TextOffsetMap.fromBoundaryMap(normalization.boundaryMap())
                        .composeWith(offsetMap);
            }
        }

        {
            String before = text;
            text = sanitizeForWatchdog(text, isSsml);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isUserDictionaryEnabled() && storageContext != null) {
            String before = text;
            text = UserDictionaryManager.getInstance(storageContext).applyRules(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        final boolean isSingleCharacterUtterance = !isSsml && (text.length() == 1 || text.trim().length() == 1);

        if (isSingleCharacterUtterance) {
            boolean characterRuleApplied = false;
            if (settings.isUserDictionaryEnabled() && storageContext != null) {
                String before = text;
                text = UserDictionaryManager.getInstance(storageContext)
                        .applyCharacterRule(text, languageTag(voice));
                characterRuleApplied = !text.equals(before);
                offsetMap = chainOffset(offsetMap, before, text);
            }
            if (!characterRuleApplied) {
                if (settings.isNatoSpellingEnabled()) {
                    String before = text;
                    text = expandNatoSpelling(text);
                    offsetMap = chainOffset(offsetMap, before, text);
                }
                if (settings.isSpokenDiacriticsEnabled()) {
                    String before = text;
                    text = expandDevanagariDiacritic(text);
                    offsetMap = chainOffset(offsetMap, before, text);
                }
            }
        }

        if (!isSsml && settings.isSimplifyUrlsEnabled()) {
            String before = text;
            text = simplifyUrls(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isIndianNumberingEnabled()) {
            String before = text;
            text = preprocessIndianText(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isCurrencyEnabled()) {
            String before = text;
            text = expandCurrencySymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isSpeakProgrammingSymbolsEnabled()) {
            String before = text;
            text = expandProgrammingSymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isRomanNumeralsEnabled()) {
            String before = text;
            text = expandRomanNumerals(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isTimeDateEnabled()) {
            String before = text;
            text = expandTimeDate(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        final boolean smartCodes = settings.isSmartCodesEnabled() && !isSsml;
        final int smartMin = settings.getSmartMinLen();
        final int smartMax = settings.getSmartMaxLen();
        final String digitGrouping = settings.getDigitGroupingMode();
        final boolean useGrouping = !isSsml && digitGrouping != null
                && !VoiceSettings.DIGIT_GROUP_OFF.equals(digitGrouping);
        if (useGrouping) {
            String before = text;
            text = formatDigitGrouping(text, digitGrouping, settings.getDigitGroupThreshold());
            offsetMap = chainOffset(offsetMap, before, text);
            if (smartCodes && !VoiceSettings.DIGIT_GROUP_SINGLE.equals(digitGrouping)) {
                before = text;
                text = spaceSeparateSmartCodes(text, smartMin, smartMax);
                offsetMap = chainOffset(offsetMap, before, text);
            }
        } else if (smartCodes) {
            String before = text;
            text = spaceSeparateSmartCodes(text, smartMin, smartMax);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isSimplifyUrlsEnabled()) {
            String before = text;
            text = simplifyUrls(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && settings.isCodeReadingModeEnabled()) {
            String before = text;
            text = expandProgrammingSymbols(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }
        if (!isSsml && settings.isSpellingModeEnabled()) {
            String before = text;
            text = expandSpellingMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        } else if (!isSsml && settings.isPhoneticModeEnabled()) {
            String before = text;
            text = expandPhoneticMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        final String repeatedMode = settings.getRepeatedCharactersMode();
        if (!isSsml && !VoiceSettings.REPEATED_CHARS_OFF.equals(repeatedMode)) {
            String before = text;
            text = condenseRepeatedCharacters(text, repeatedMode);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && containsPotentialEmoji(text)) {
            String before = text;
            text = settings.isEmojiIgnoreEnabled() ? filterEmojis(text) : clarifyEmojiAnnouncements(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        return new Result(text, offsetMap, isSingleCharacterUtterance);
    }

    public static TextOffsetMap chainOffset(TextOffsetMap previous, String before, String after) {
        if (before.equals(after)) {
            return previous;
        }
        return TextOffsetMap.diff(before, after).composeWith(previous);
    }

    // ==========================================
    // Fast-path Predicates & Char Scanners
    // ==========================================

    public static boolean containsProgrammingSymbolChars(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '!': case '=': case '<': case '>':
                case '-': case '&': case '|': case '/':
                case '*': case '.': case '≠': case '≤':
                case '≥': case '⇒': case '→': case '←':
                case '↑': case '↓': case '…': case '±':
                case '×': case '÷': case '≈': case '✓':
                case '✔': case '•': case '⁃': case '◦':
                case '°': case '√': case '∞': case '∫':
                case '∀': case '∃': case '∉': case '∈':
                case '∪': case '∩': case '¬': case '∧':
                case '∨': case '¢': case '¥': case 'ƒ':
                    return true;
            }
        }
        return false;
    }

    public static boolean containsIndianNuanceChars(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0x0900 && c <= 0x0D7F) || c == 0x20B9 || c == '/' || c == ',' ||
                c == 'k' || c == 'K' || c == 'l' || c == 'L' || c == 'c' || c == 'C' ||
                c == 'r' || c == 'R' || c == 's' || c == 'S' || c == 'i' || c == 'I' ||
                c == 'e' || c == 'E') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHangControls(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F
                    || (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2060 && c <= 0x2064) || c == 0xFEFF) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigit(String text) {
        final int len = text.length();
        for (int i = 0; i < len; ) {
            final int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    public static boolean containsPotentialEmoji(String text) {
        final int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) || c >= 0x2600) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsSmartCodeKeyword(String context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        return SMART_CODE_KEYWORD.matcher(context).find();
    }

    public static boolean isDevanagariNumberLang(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return false;
        String base = languageTag.trim().toLowerCase(Locale.ROOT);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        return base.equals("hi") || base.equals("mr") || base.equals("ne")
                || base.equals("sa") || base.equals("kok");
    }

    public static boolean isEmojiCodePoint(int codePoint) {
        final int type = Character.getType(codePoint);
        return (type == Character.OTHER_SYMBOL)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FFFF);
    }

    public static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    public static boolean isEmojiJoiner(int codePoint) {
        return (codePoint == 0x200D)
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF)
                || (codePoint >= 0xE0020 && codePoint <= 0xE007F)
                || (codePoint == 0x20E3);
    }

    // ==========================================
    // Text Transformation Methods
    // ==========================================

    public static String sanitizeForWatchdog(String text) {
        return sanitizeForWatchdog(text, false);
    }

    public static String sanitizeForWatchdog(String text, boolean isSsml) {
        if (text == null || text.isEmpty()) return text;
        if (containsHangControls(text)) {
            text = HANG_CONTROLS.matcher(text).replaceAll("");
        }
        if (!isSsml && (text.contains("[[") || text.contains("]]"))) {
            text = EDGE_BRACKET_RUN.matcher(text).replaceAll(" ");
        }
        return text;
    }

    public static String stripUnpairedSurrogates(String text) {
        if (text == null) return null;
        StringBuilder sb = null;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            boolean drop = false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    drop = true;
                }
            } else if (Character.isLowSurrogate(c)) {
                if (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1))) {
                    drop = true;
                }
            }
            if (drop && sb == null) {
                sb = new StringBuilder(length);
                sb.append(text, 0, i);
            }
            if (sb != null && !drop) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    public static String expandProgrammingSymbols(String text) {
        if (text == null || text.isEmpty() || !containsProgrammingSymbolChars(text)) {
            return text;
        }
        text = SYM_NOT_EQUAL.matcher(text).replaceAll(" not equal ");
        text = SYM_DOUBLE_EQUALS.matcher(text).replaceAll(" double equals ");
        text = SYM_LESS_EQUAL.matcher(text).replaceAll(" less than or equal to ");
        text = SYM_GREATER_EQUAL.matcher(text).replaceAll(" greater than or equal to ");
        text = SYM_FAT_ARROW.matcher(text).replaceAll(" implies ");
        text = SYM_THIN_ARROW.matcher(text).replaceAll(" arrow ");
        text = SYM_LEFT_ARROW.matcher(text).replaceAll(" left arrow ");
        text = SYM_UP_ARROW.matcher(text).replaceAll(" up arrow ");
        text = SYM_DOWN_ARROW.matcher(text).replaceAll(" down arrow ");
        text = SYM_LOGICAL_AND.matcher(text).replaceAll(" double ampersand ");
        text = SYM_LOGICAL_OR.matcher(text).replaceAll(" double pipe ");
        text = SYM_COMMENT_START.matcher(text).replaceAll(" comment start ");
        text = SYM_COMMENT_END.matcher(text).replaceAll(" comment end ");
        text = SYM_DOUBLE_SLASH.matcher(text).replaceAll(" double slash ");
        text = SYM_ELLIPSIS.matcher(text).replaceAll(" dot dot dot ");
        text = SYM_PLUS_MINUS.matcher(text).replaceAll(" plus or minus ");
        text = SYM_TIMES.matcher(text).replaceAll(" times ");
        text = SYM_DIVIDE.matcher(text).replaceAll(" divided by ");
        text = SYM_ALMOST_EQUAL.matcher(text).replaceAll(" almost equal to ");
        text = SYM_CHECKMARK.matcher(text).replaceAll(" check ");
        text = SYM_BULLET.matcher(text).replaceAll(" bullet ");
        text = SYM_DEGREES.matcher(text).replaceAll(" degrees ");
        text = SYM_SQRT.matcher(text).replaceAll(" square root ");
        text = SYM_INFINITY.matcher(text).replaceAll(" infinity ");
        text = SYM_INTEGRAL.matcher(text).replaceAll(" integral ");
        text = SYM_FOR_ALL.matcher(text).replaceAll(" for all ");
        text = SYM_EXISTS.matcher(text).replaceAll(" there exists ");
        text = SYM_NOT_ELEMENT_OF.matcher(text).replaceAll(" not an element of ");
        text = SYM_ELEMENT_OF.matcher(text).replaceAll(" element of ");
        text = SYM_UNION.matcher(text).replaceAll(" union ");
        text = SYM_INTERSECTION.matcher(text).replaceAll(" intersection ");
        text = SYM_LOGICAL_NOT.matcher(text).replaceAll(" not ");
        text = SYM_SET_AND.matcher(text).replaceAll(" and ");
        text = SYM_SET_OR.matcher(text).replaceAll(" or ");
        text = SYM_CENT.matcher(text).replaceAll(" cents ");
        text = SYM_YEN.matcher(text).replaceAll(" yen ");
        text = SYM_FLORIN.matcher(text).replaceAll(" florin ");
        return text;
    }

    public static String expandRomanNumerals(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PATTERN_ROMAN_CONTEXT.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length());
        do {
            String prefix;
            String roman;
            if (matcher.group(1) != null) {
                prefix = matcher.group(1);
                roman = matcher.group(2).toUpperCase(Locale.ROOT);
            } else {
                String name = matcher.group(4);
                prefix = name != null ? matcher.group(3) + " " + name : matcher.group(3);
                roman = matcher.group(5);
            }
            int val = parseRomanNumeral(roman);
            if (val > 0) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + " " + val));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static int parseRomanNumeral(String s) {
        if (s == null || s.isEmpty() || s.length() > 15) return -1;
        if (!s.matches("^M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")) {
            return -1;
        }
        int total = 0;
        int prevValue = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int curValue;
            switch (s.charAt(i)) {
                case 'I': curValue = 1; break;
                case 'V': curValue = 5; break;
                case 'X': curValue = 10; break;
                case 'L': curValue = 50; break;
                case 'C': curValue = 100; break;
                case 'D': curValue = 500; break;
                case 'M': curValue = 1000; break;
                default: return -1;
            }
            if (curValue < prevValue) {
                total -= curValue;
            } else {
                total += curValue;
                prevValue = curValue;
            }
        }
        return total > 0 ? total : -1;
    }

    public static String simplifyUrls(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PATTERN_URL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length());
        do {
            String url = matcher.group(0);
            String simplified = formatSimplifiedUrl(url);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(simplified));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String formatSimplifiedUrl(String url) {
        String s = url;
        if (s.startsWith("https://") || s.startsWith("HTTPS://")) {
            s = s.substring(8);
        } else if (s.startsWith("http://") || s.startsWith("HTTP://")) {
            s = s.substring(7);
        }
        if (s.startsWith("www.") || s.startsWith("WWW.")) {
            s = s.substring(4);
        }
        int qIdx = s.indexOf('?');
        if (qIdx != -1) {
            String query = s.substring(qIdx);
            if (query.length() > 10) {
                s = s.substring(0, qIdx) + " with parameters";
            }
        }
        s = s.replace("/", " slash ");
        return " link " + s.trim() + " ";
    }

    public static String condenseRepeatedCharacters(String text, String mode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (VoiceSettings.REPEATED_CHARS_TRUNCATE.equals(mode)) {
            return PATTERN_REPEATED_CHARS_TRUNCATE.matcher(text).replaceAll("$1$1$1");
        }
        if (!VoiceSettings.REPEATED_CHARS_COUNT.equals(mode)) {
            return text;
        }
        Matcher matcher = PATTERN_REPEATED_CHARS.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length());
        do {
            String match = matcher.group(0);
            char c = match.charAt(0);
            int count = match.length();
            String name = getSpokenCharName(c);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(" " + count + " " + name + " "));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String getSpokenCharName(char c) {
        switch (c) {
            case '-': return "dashes";
            case '*': return "asterisks";
            case '=': return "equals";
            case '_': return "underscores";
            case '.': return "dots";
            case '~': return "tildes";
            case '!': return "exclamations";
            case '?': return "question marks";
            case '#': return "hashes";
            case '/': return "slashes";
            case '\\': return "backslashes";
            case '+': return "pluses";
            case '<': return "less thans";
            case '>': return "greater thans";
            case ':': return "colons";
            case ';': return "semicolons";
            case '|': return "pipes";
            case '^': return "carets";
            case '"': return "quotes";
            case '\'': return "apostrophes";
            default:
                return String.valueOf(c);
        }
    }

    public static String normalizeIndicDigits(String text) {
        if (text == null || text.isEmpty()) return text;
        final int len = text.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            char ascii = 0;
            if (c >= 0x0966 && c <= 0x0D6F) {
                if (c <= 0x096F) ascii = (char) ('0' + (c - 0x0966));
                else if (c >= 0x09E6 && c <= 0x09EF) ascii = (char) ('0' + (c - 0x09E6));
                else if (c >= 0x0A66 && c <= 0x0A6F) ascii = (char) ('0' + (c - 0x0A66));
                else if (c >= 0x0AE6 && c <= 0x0AEF) ascii = (char) ('0' + (c - 0x0AE6));
                else if (c >= 0x0B66 && c <= 0x0B6F) ascii = (char) ('0' + (c - 0x0B66));
                else if (c >= 0x0BE6 && c <= 0x0BEF) ascii = (char) ('0' + (c - 0x0BE6));
                else if (c >= 0x0C66 && c <= 0x0C6F) ascii = (char) ('0' + (c - 0x0C66));
                else if (c >= 0x0CE6 && c <= 0x0CEF) ascii = (char) ('0' + (c - 0x0CE6));
                else if (c >= 0x0D66 && c <= 0x0D6F) ascii = (char) ('0' + (c - 0x0D66));
            }

            if (ascii != 0) {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(text, 0, i);
                }
                sb.append(ascii);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    public static String expandNatoSpelling(String text) {
        if (text == null) return text;
        String trimmed = text.trim();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return c + ", " + NATO_PHONETICS[c - 'a'];
            } else if (c >= 'A' && c <= 'Z') {
                return c + ", " + NATO_PHONETICS[c - 'A'];
            }
        }
        return text;
    }

    public static String expandDevanagariDiacritic(String text) {
        if (text == null) return text;
        String trimmed = text.trim();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            switch (c) {
                case '\u093E': return "आ की मात्रा"; // ा
                case '\u093F': return "इ की मात्रा"; // ि
                case '\u0940': return "ई की मात्रा"; // ी
                case '\u0941': return "उ की मात्रा"; // ु
                case '\u0942': return "ऊ की मात्रा"; // ू
                case '\u0943': return "ऋ की मात्रा"; // ृ
                case '\u0947': return "ए की मात्रा"; // े
                case '\u0948': return "ऐ की मात्रा"; // ै
                case '\u094B': return "ओ की मात्रा"; // ो
                case '\u094C': return "औ की मात्रा"; // ौ
                case '\u0902': return "अनुस्वार"; // ं
                case '\u0903': return "विसर्ग"; // ः
                case '\u0901': return "चन्द्रबिन्दु"; // ँ
                case '\u094D': return "हलन्त"; // ्
                case '\u093C': return "नुक्ता"; // ़
            }
        }
        return text;
    }

    public static String indianGroupedNumberToWords(String grouped) {
        return indianGroupedNumberToWords(grouped, false);
    }

    public static String indianGroupedNumberToWords(String grouped, boolean devanagari) {
        String lakhWord = devanagari ? "लाख" : "lakh";
        String croreWord = devanagari ? "करोड़" : "crore";
        if (grouped == null || grouped.isEmpty()) return grouped;
        String digits = grouped.replace(",", "");
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return digits;
        }
        if (value < 100000 || value > 9999999999L) {
            return digits;
        }
        StringBuilder out = new StringBuilder();
        long crore = value / 10000000L;
        long rest = value % 10000000L;
        if (crore > 0) {
            out.append(crore).append(' ').append(croreWord);
            if (rest > 0) out.append(' ');
        }
        if (rest > 0) {
            long lakh = rest / 100000L;
            long rest2 = rest % 100000L;
            if (lakh > 0) {
                out.append(lakh).append(' ').append(lakhWord);
                if (rest2 > 0) out.append(' ').append(rest2);
            } else {
                out.append(rest2);
            }
        }
        return out.toString();
    }

    public static String indianRupeeAmountToWords(String amount) {
        return indianRupeeAmountToWords(amount, false);
    }

    public static String indianRupeeAmountToWords(String amount, boolean devanagari) {
        if (amount == null || amount.isEmpty()) return devanagari ? " रुपये" : " rupees";
        int dot = amount.indexOf('.');
        String intPart = dot >= 0 ? amount.substring(0, dot) : amount;
        String fracPart = dot >= 0 ? amount.substring(dot + 1) : "";
        String intWords = intPart.contains(",")
                ? indianGroupedNumberToWords(intPart, devanagari)
                : intPart.replace(",", "");
        if (intWords.isEmpty()) intWords = "0";

        int paise = -1;
        if (fracPart.length() >= 1 && fracPart.length() <= 2) {
            try {
                paise = Integer.parseInt(fracPart);
                if (fracPart.length() == 1) {
                    paise *= 10; // e.g. .5 is 50 paise, not 5 paise
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Fractional-only amount, e.g. "₹0.50" -> "50 paise"
        if ("0".equals(intWords) && paise > 0) {
            String paiseWord = (paise == 1)
                    ? (devanagari ? "पैसा" : "paisa")
                    : (devanagari ? "पैसे" : "paise");
            return paise + " " + paiseWord;
        }

        boolean isSingularRupee = "1".equals(intWords);
        String rupeesWord = isSingularRupee
                ? (devanagari ? "रुपया" : "rupee")
                : (devanagari ? "रुपये" : "rupees");

        StringBuilder out = new StringBuilder(intWords).append(' ').append(rupeesWord);
        if (paise > 0) {
            String paiseWord = (paise == 1)
                    ? (devanagari ? "पैसा" : "paisa")
                    : (devanagari ? "पैसे" : "paise");
            out.append(' ').append(paise).append(' ').append(paiseWord);
        } else if (paise < 0 && !fracPart.isEmpty()) {
            out.append('.').append(fracPart);
        } else if (fracPart.length() > 2) {
            // Long fractions ("10.567") stay decimal for the engine.
            return intWords + "." + fracPart + " " + rupeesWord;
        }
        return out.toString();
    }

    public static String indianRupeeShorthandToWords(String amount, String unit, boolean devanagari) {
        String unitWord;
        char u = Character.toLowerCase(unit.charAt(0));
        if (u == 'k') {
            unitWord = devanagari ? "हज़ार" : "thousand";
        } else if (u == 'l') {
            unitWord = devanagari ? "लाख" : "lakh";
        } else {
            unitWord = devanagari ? "करोड़" : "crore";
        }
        String rupeesWord = devanagari ? "रुपये" : "rupees";
        return amount + " " + unitWord + " " + rupeesWord;
    }

    public static String preprocessIndianText(String text) {
        return preprocessIndianText(text, "");
    }

    public static String preprocessIndianText(String text, String languageTag) {
        if (text == null || text.isEmpty() || !containsIndianNuanceChars(text)) {
            return text;
        }
        final boolean devanagari = isDevanagariNumberLang(languageTag);
        text = normalizeIndicDigits(text);
        text = DANDA_BOUNDARY.matcher(text).replaceAll("$1 $2");
        Matcher txnMatcher = BANKING_SLASH_TXN.matcher(text);
        if (txnMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String expanded = SLASH_RUN.matcher(txnMatcher.group(0)).replaceAll(" / ");
                txnMatcher.appendReplacement(sb, Matcher.quoteReplacement(expanded));
            } while (txnMatcher.find());
            txnMatcher.appendTail(sb);
            text = sb.toString();
        }

        Matcher currMatcher = CURRENCY_PREFIX.matcher(text);
        if (currMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String amount = currMatcher.group(1);
                String unit = currMatcher.group(2);
                String words = (unit != null && !unit.isEmpty())
                        ? indianRupeeShorthandToWords(amount, unit, devanagari)
                        : indianRupeeAmountToWords(amount, devanagari);
                currMatcher.appendReplacement(sb, Matcher.quoteReplacement(words));
            } while (currMatcher.find());
            currMatcher.appendTail(sb);
            text = sb.toString();
        }

        Matcher numMatcher = INDIAN_NUMBER_COMMAS.matcher(text);
        if (numMatcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String grouped = numMatcher.group(0);
                String words = indianGroupedNumberToWords(grouped, devanagari);
                numMatcher.appendReplacement(sb, Matcher.quoteReplacement(words));
            } while (numMatcher.find());
            numMatcher.appendTail(sb);
            text = sb.toString();
        }

        if (devanagari) {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 हज़ार");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 लाख");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 करोड़");
        } else {
            text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 thousand");
            text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 lakh");
            text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 crore");
        }
        return text;
    }

    public static String expandCurrencySymbols(String text) {
        if (text == null || text.isEmpty() || !containsDigit(text)) return text;
        text = CURRENCY_DOLLAR_PREFIX.matcher(text).replaceAll("$1 dollars");
        text = CURRENCY_DOLLAR_SUFFIX.matcher(text).replaceAll("$1 dollars");
        text = CURRENCY_EURO.matcher(text).replaceAll("$1$2 euros");
        text = CURRENCY_POUND.matcher(text).replaceAll("$1$2 pounds");
        text = CURRENCY_YEN.matcher(text).replaceAll("$1$2 yen");
        return text;
    }

    public static String expandTimeDate(String text) {
        if (text == null || text.isEmpty() || !containsDigit(text)) return text;
        boolean hasSeparator = false;
        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            if (c == ':' || c == '/' || c == '-') {
                hasSeparator = true;
                break;
            }
        }
        if (!hasSeparator) return text;
        Matcher tm = TIME_HM.matcher(text);
        if (tm.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String mer = tm.group(3);
                String rep = tm.group(1) + " " + tm.group(2)
                        + (mer != null ? " " + mer + " M" : "");
                tm.appendReplacement(sb, Matcher.quoteReplacement(rep));
            } while (tm.find());
            tm.appendTail(sb);
            text = sb.toString();
        }
        Matcher dm = DATE_NUMERIC.matcher(text);
        if (dm.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String rep = dm.group(1) + " " + dm.group(2) + " " + dm.group(3);
                dm.appendReplacement(sb, Matcher.quoteReplacement(rep));
            } while (dm.find());
            dm.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    public static String expandSpellingMode(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                if (out.length() > 0) {
                    int last = out.length() - 1;
                    if (out.charAt(last) != ' ') out.append(' ');
                }
                out.appendCodePoint(cp);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return SPACE_RUNS.matcher(out.toString()).replaceAll(" ");
    }

    public static String expandPhoneticMode(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() * 6);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                int idx = Character.toUpperCase(cp) - 'A';
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                out.append(NATO_PHONETICS[idx]);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    public static String clarifyEmojiAnnouncements(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        final StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final int codePoint = text.codePointAt(i);
            if (!isEmojiCodePoint(codePoint)) {
                out.appendCodePoint(codePoint);
                i += Character.charCount(codePoint);
                continue;
            }

            final int runStart = i;
            boolean prevWasZwj = false;
            while (i < len) {
                final int c = text.codePointAt(i);
                if (!isEmojiCodePoint(c) && !isEmojiJoiner(c) && !prevWasZwj) break;
                prevWasZwj = (c == 0x200D);
                i += Character.charCount(c);
            }

            final int lastOut = out.length() - 1;
            if (lastOut >= 0) {
                char prev = out.charAt(lastOut);
                if (prev != ' ' && prev != ',' && prev != '.' && prev != '!' && prev != '?' && prev != ':' && prev != ';') {
                    out.append(',');
                }
                if (prev != ' ') {
                    out.append(' ');
                }
            }

            int prevCp = -1;
            int riRun = 0;
            for (int j = runStart; j < i; ) {
                int cp = text.codePointAt(j);
                boolean joiner = isEmojiJoiner(cp) || isEmojiJoiner(prevCp);
                if (isRegionalIndicator(cp)) {
                    riRun++;
                    joiner = (riRun % 2 == 0);
                } else {
                    riRun = 0;
                }
                if (j > runStart && !joiner) {
                    out.append(' ');
                }
                out.appendCodePoint(cp);
                prevCp = cp;
                j += Character.charCount(cp);
            }

            if (i < len) {
                char next = text.charAt(i);
                boolean isPunct = next == ',' || next == '.' || next == '!' || next == '?' || next == ':' || next == ';';
                if (next != ' ' && !isPunct) {
                    out.append(',');
                }
                if (next != ' ' && !isPunct) {
                    out.append(' ');
                }
            }
        }
        return out.toString();
    }

    public static String filterEmojis(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        final int len = text.length();
        int prevRaw = -1;
        for (int i = 0; i < len; ) {
            final int codePoint = text.codePointAt(i);
            if (!isEmojiCodePoint(codePoint) && !isEmojiJoiner(codePoint)
                    && !isRegionalIndicator(codePoint) && prevRaw != 0x200D) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append(' ');
            }
            prevRaw = codePoint;
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    public static String formatDigitGrouping(String text, String mode, int threshold) {
        if (text == null || text.isEmpty() || mode == null
                || VoiceSettings.DIGIT_GROUP_OFF.equals(mode)) {
            return text;
        }
        if (VoiceSettings.DIGIT_GROUP_SINGLE.equals(mode)) {
            return spaceSeparateDigits(text);
        }
        final int groupSize = VoiceSettings.DIGIT_GROUP_DOUBLE.equals(mode) ? 2 : 3;
        final int len = text.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                int runStart = i;
                int digitCount = 0;
                while (i < len) {
                    int c = text.codePointAt(i);
                    if (!Character.isDigit(c)) break;
                    digitCount++;
                    i += Character.charCount(c);
                }
                int runEnd = i;
                boolean regroup = digitCount >= 4
                        && (groupSize == 2 || digitCount >= Math.max(4, threshold));
                if (!regroup) {
                    out.append(text, runStart, runEnd);
                } else {
                    int groupCount = 0;
                    for (int j = runStart; j < runEnd; ) {
                        int c = text.codePointAt(j);
                        if (groupCount > 0 && groupCount % groupSize == 0) {
                            out.append(' ');
                        }
                        out.appendCodePoint(c);
                        groupCount++;
                        j += Character.charCount(c);
                    }
                }
            } else {
                out.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    public static String spaceSeparateDigits(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        StringBuilder out = new StringBuilder(len * 2);
        boolean prevWasDigit = false;
        for (int i = 0; i < len; ) {
            final int c = text.codePointAt(i);
            final int charCount = Character.charCount(c);
            final boolean isDigit = Character.isDigit(c);
            if (isDigit && prevWasDigit) {
                out.append(' ');
            }
            out.appendCodePoint(c);
            prevWasDigit = isDigit;
            i += charCount;
        }
        return out.toString();
    }

    public static String spaceSeparateSmartCodes(String text) {
        return spaceSeparateSmartCodes(text, 4, 10);
    }

    public static String spaceSeparateSmartCodes(String text, int minLen, int maxLen) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final int len = text.length();
        StringBuilder out = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            if (Character.isDigit(cp)) {
                int runStart = i;
                int digitCount = 0;
                while (i < len) {
                    int c = text.codePointAt(i);
                    if (!Character.isDigit(c)) break;
                    digitCount++;
                    i += Character.charCount(c);
                }
                int runEnd = i;

                boolean separate = false;
                if (digitCount >= Math.max(2, minLen) && digitCount <= Math.max(minLen, maxLen)) {
                    int contextStart = Math.max(0, runStart - 25);
                    String prefix = text.substring(contextStart, runStart);
                    int contextEnd = Math.min(len, runEnd + 25);
                    String suffix = text.substring(runEnd, contextEnd);
                    if (containsSmartCodeKeyword(prefix) || containsSmartCodeKeyword(suffix)) {
                        separate = true;
                    }
                }

                if (separate) {
                    for (int j = runStart; j < runEnd; ) {
                        int c = text.codePointAt(j);
                        if (j > runStart) {
                            out.append(' ');
                        }
                        out.appendCodePoint(c);
                        j += Character.charCount(c);
                    }
                } else {
                    out.append(text, runStart, runEnd);
                }
            } else {
                if (cp == 0x20B9) {
                    out.append(" rupees ");
                } else {
                    out.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    public static boolean endsWithQuestionOrExclamation(String text) {
        if (text == null) return false;
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ')' || c == ']'
                    || c == '”' || c == '’') {
                end--;
                continue;
            }
            break;
        }
        if (end == 0) return false;
        char last = text.charAt(end - 1);
        return last == '?' || last == '!' || last == '？' || last == '！';
    }

    public static List<String> chunkForWatchdog(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        String capped = text.length() > MAX_REQUEST_CHARS
                ? text.substring(0, MAX_REQUEST_CHARS) : text;
        if (capped.length() <= MAX_CHUNK_CHARS) {
            chunks.add(capped);
            return chunks;
        }

        final int len = capped.length();
        int start = 0;
        while (start < len && chunks.size() < MAX_CHUNKS) {
            if (len - start <= MAX_CHUNK_CHARS) {
                chunks.add(capped.substring(start));
                break;
            }

            int targetEnd = start + MAX_CHUNK_CHARS;
            int cutPoint = -1;

            for (int i = targetEnd - 1; i > start; i--) {
                char c = capped.charAt(i);
                if (isPunctOrNewline(c)) {
                    if (c == '.' && i > start && Character.isDigit(capped.charAt(i - 1))
                            && i + 1 < len && Character.isDigit(capped.charAt(i + 1))) {
                        continue;
                    }
                    if (c == '.' && isAbbreviationOrInitial(capped, start, i)) {
                        continue;
                    }

                    int p = i + 1;
                    while (p < len && isPunctOrNewline(capped.charAt(p))) {
                        p++;
                    }
                    while (p < len && (capped.charAt(p) == ' ' || capped.charAt(p) == '\t')) {
                        p++;
                    }
                    if (p <= targetEnd + 16) {
                        cutPoint = p;
                        break;
                    }
                }
            }

            if (cutPoint <= start) {
                for (int i = targetEnd - 1; i > start; i--) {
                    if (capped.charAt(i) <= ' ') {
                        int p = i + 1;
                        while (p < len && capped.charAt(p) <= ' ') {
                            p++;
                        }
                        if (p <= targetEnd) {
                            cutPoint = p;
                            break;
                        }
                    }
                }
            }

            if (cutPoint <= start) {
                cutPoint = targetEnd;
            }

            chunks.add(capped.substring(start, cutPoint));
            start = cutPoint;
        }
        return chunks;
    }

    private static boolean isAbbreviationOrInitial(String s, int start, int dotIndex) {
        int wordStart = dotIndex - 1;
        while (wordStart >= start && Character.isLetter(s.charAt(wordStart))) {
            wordStart--;
        }
        wordStart++;
        int wordLen = dotIndex - wordStart;
        if (wordLen == 1) {
            return true;
        }
        if (wordLen >= 2 && wordLen <= 4) {
            String word = s.substring(wordStart, dotIndex).toLowerCase(Locale.ROOT);
            if ("dr".equals(word) || "mr".equals(word) || "mrs".equals(word) || "ms".equals(word)
                    || "prof".equals(word) || "sr".equals(word) || "jr".equals(word) || "vs".equals(word)
                    || "eg".equals(word) || "ie".equals(word) || "etc".equals(word) || "rs".equals(word)
                    || "re".equals(word) || "al".equals(word) || "no".equals(word) || "st".equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPunctOrNewline(char c) {
        return c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
                || c == '\u0964' || c == '\u0965' || c == '\u3002' || c == '\uFF01' || c == '\uFF1F';
    }

    public static String languageTag(Voice voice) {
        if (voice == null || voice.locale == null) return "";
        String language = voice.locale.getLanguage();
        if (language == null) return "";
        language = language.toLowerCase(Locale.ROOT);
        String country = voice.locale.getCountry();
        if (country != null && !country.isEmpty()) {
            language += "-" + country.toLowerCase(Locale.ROOT);
        }
        return language;
    }
}
