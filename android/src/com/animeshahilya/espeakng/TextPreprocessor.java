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
    // Capital L only: a bare lowercase "l" is litres ("2 l of water").
    private static final Pattern SHORTHAND_LAKH =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(?:L|(?i:lac|lakhs?))\\b");
    private static final Pattern SHORTHAND_CRORE =
            Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:cr|crore|crores)\\b");
    private static final Pattern SLASH_RUN =
            Pattern.compile("/+");

    private static final Pattern PATTERN_URL = Pattern.compile(
            "\\b(?:https?://|www\\.)[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    // ICAO spellings, matching NVDA's characterDescriptions.dic ("Alfa",
    // "Juliett", "Xray" - the Consultative Committee's spellings, chosen for
    // cross-accent clarity, not the common misspellings).
    private static final String[] NATO_PHONETICS = {
            "Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf",
            "Hotel", "India", "Juliett", "Kilo", "Lima", "Mike", "November",
            "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "Xray", "Yankee", "Zulu"
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

        if (text == null || text.isEmpty()) {
            return new Result(text != null ? text : "", initialOffsetMap, false);
        }

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

        UserDictionaryManager dictManager = null;
        if (!isSsml && settings.isUserDictionaryEnabled() && storageContext != null) {
            // Resolved once: getInstance() is static synchronized, and the
            // single-character path below needs the same instance.
            dictManager = UserDictionaryManager.getInstance(storageContext);
            String before = text;
            text = dictManager.applyRules(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        final boolean isSingleCharacterUtterance = !isSsml
                && (text.length() == 1 || trimmedLength(text) == 1);

        if (isSingleCharacterUtterance) {
            boolean characterRuleApplied = false;
            if (dictManager != null) {
                String before = text;
                text = dictManager.applyCharacterRule(text, languageTag(voice));
                characterRuleApplied = !text.equals(before);
                offsetMap = chainOffset(offsetMap, before, text);
            }
            if (!characterRuleApplied) {
                if (!VoiceSettings.PHONETIC_OFF.equals(settings.getPhoneticLetters())) {
                    String before = text;
                    text = expandNatoSpelling(text);
                    offsetMap = chainOffset(offsetMap, before, text);
                }
                if (settings.isSpokenDiacriticsEnabled() && isIndianLanguage(languageTag(voice))) {
                    String before = text;
                    text = expandDevanagariDiacritic(text);
                    offsetMap = chainOffset(offsetMap, before, text);
                }
                // NVDA processSpeechSymbol: character navigation always names
                // the character, independent of the symbol level. No-op for
                // letters and whitespace (the lookup trims first).
                String before = text;
                text = NvdaSymbolProcessor.processSingleSymbol(text);
                offsetMap = chainOffset(offsetMap, before, text);
            }
        }

        if (!isSsml && settings.isSimplifyUrlsEnabled()) {
            String before = text;
            text = simplifyUrls(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Read once: getReadingMode() re-reads SharedPreferences, and the
        // value is consulted by the number extras, the spelling/phonetic
        // branches below and the NVDA pass (code mode selects level ALL there).
        final String readingMode = settings.getReadingMode();
        final boolean normalReading = !isSsml && VoiceSettings.READING_NORMAL.equals(readingMode);
        final boolean indianNumbers = settings.isIndianNumberingEnabled()
                && isIndianLanguage(languageTag(voice));

        // Beta extra: Hinglish words become Devanagari, which eSpeak's own
        // script detection then reads with Hindi pronunciation. Before the
        // code pass, so Hindi code words ("pin" -> पिन) still mark a code.
        if (normalReading && settings.isHinglishEnabled()
                && NumberReading.isEnglish(languageTag(voice))) {
            String before = text;
            text = HinglishReader.process(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Opt-in extras, off by default. Codes run first so a code is never
        // read as money; money keeps its digits for the Indian pass below.
        if (normalReading && settings.isReadCodesEnabled()) {
            String before = text;
            text = NumberReading.readCodes(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }
        if (normalReading && settings.isReadMoneyEnabled()
                && NumberReading.isEnglish(languageTag(voice))) {
            String before = text;
            text = NumberReading.readMoney(text, !indianNumbers);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Indian-voice only: every other voice reads these exactly as NVDA's
        // eSpeak does ("5k", "2 l", "12,34,567" untouched).
        if (!isSsml && indianNumbers) {
            String before = text;
            text = preprocessIndianText(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        final String digitGrouping = settings.getDigitGroupingMode();
        final boolean useGrouping = !isSsml && digitGrouping != null
                && !VoiceSettings.DIGIT_GROUP_OFF.equals(digitGrouping);
        if (useGrouping) {
            String before = text;
            text = formatDigitGrouping(text, digitGrouping, settings.getDigitGroupThreshold());
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // Phonetic letters "Always" reads every letter as Alfa, Bravo and so
        // on; it replaces spelling (which it already implies) but not code mode.
        if (!isSsml && !isSingleCharacterUtterance
                && VoiceSettings.PHONETIC_ALWAYS.equals(settings.getPhoneticLetters())
                && !VoiceSettings.READING_CODE.equals(readingMode)) {
            String before = text;
            text = expandPhoneticMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        } else if (!isSsml && VoiceSettings.READING_SPELLING.equals(readingMode)) {
            String before = text;
            text = expandSpellingMode(text);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        // NVDA architecture: one symbol pass owns announcement, levels and
        // repeat collapsing. It runs before emoji condensing so the ", "
        // separators the condensing inserts are never mistaken for content
        // punctuation. Single-character utterances skip this pass - the
        // single-char branch above already named the character.
        if (!isSsml && !isSingleCharacterUtterance) {
            String before = text;
            text = processNvdaSymbols(text, settings, readingMode);
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && containsPotentialEmoji(text)
                && !VoiceSettings.REPEATED_CHARS_OFF.equals(settings.getRepeatedCharactersMode())) {
            String before = text;
            text = condenseRepeatedEmojis(text, settings.getRepeatedCharactersMode());
            offsetMap = chainOffset(offsetMap, before, text);
        }

        if (!isSsml && containsPotentialEmoji(text)) {
            String before = text;
            text = settings.isEmojiIgnoreEnabled()
                    ? filterEmojis(text) : clarifyEmojiAnnouncements(text, languageTag(voice));
            offsetMap = chainOffset(offsetMap, before, text);
        }

        return new Result(text, offsetMap, isSingleCharacterUtterance);
    }

    /**
     * Maps the punctuation preset onto an NVDA symbol level. Code-reading
     * mode forces ALL; a custom character list announces exactly those
     * characters (NVDA has no custom mode - its users edit symbols
     * individually, which is what the list approximates here).
     */
    private static String processNvdaSymbols(String text, VoiceSettings settings, String readingMode) {
        final boolean collapse =
                !VoiceSettings.REPEATED_CHARS_OFF.equals(settings.getRepeatedCharactersMode());
        if (VoiceSettings.READING_CODE.equals(readingMode)) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_ALL, collapse);
        }
        final int preset = settings.getPunctuationLevel();
        final String chars = settings.getPunctuationCharacters();
        if (preset == SpeechSynthesis.PUNCT_ALL) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_ALL, collapse);
        }
        if (preset == SpeechSynthesis.PUNCT_NONE) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_NONE, collapse);
        }
        if (chars == null || chars.isEmpty()) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_NONE, collapse);
        }
        if (chars.equals(VoiceSettings.PUNCTUATION_CHARS_SOME)) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_SOME, collapse);
        }
        if (chars.equals(VoiceSettings.PUNCTUATION_CHARS_MOST)) {
            return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_MOST, collapse);
        }
        return NvdaSymbolProcessor.processCustom(text, chars, collapse);
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

    /**
     * True when the text holds anything the NVDA symbol pass would rewrite.
     * The old 37-pattern chain needed an elaborate trigger index because it
     * ran 37 full-text scans; the NVDA pass is a single scan, so no gate is
     * needed in the pipeline - this stays only as a cheap predicate.
     */
    public static boolean containsProgrammingSymbolChars(String text) {
        return NvdaSymbolProcessor.containsKnownSymbol(text);
    }

    /**
     * Length of {@code text} after trimming leading/trailing chars
     * {@code <= ' '} (exactly {@code String.trim()} semantics) without
     * copying the string.
     */
    private static int trimmedLength(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') start++;
        while (end > start && text.charAt(end - 1) <= ' ') end--;
        return end - start;
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

    /**
     * The voices the fork's Indian-language work targets: eSpeak's Indo-Aryan
     * and Dravidian voices (lang/inc, lang/dra) plus Indian English. Every
     * other voice must read text exactly as NVDA's eSpeak does.
     */
    public static boolean isIndianLanguage(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return false;
        String tag = languageTag.trim().toLowerCase(Locale.ROOT);
        if (tag.equals("en-in")) return true;
        int dash = tag.indexOf('-');
        String base = dash >= 0 ? tag.substring(0, dash) : tag;
        switch (base) {
            case "as": case "bn": case "bpy": case "gu": case "hi": case "kok":
            case "mr": case "ne": case "or": case "pa": case "sd": case "si":
            case "ur": case "kn": case "ml": case "ta": case "te":
                return true;
            default:
                return false;
        }
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

    public static int getEmojiClusterLength(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) {
            return 0;
        }
        final int len = text.length();
        final int firstCp = text.codePointAt(start);
        if (isRegionalIndicator(firstCp)) {
            int step = Character.charCount(firstCp);
            if (start + step < len) {
                int secondCp = text.codePointAt(start + step);
                if (isRegionalIndicator(secondCp)) {
                    return step + Character.charCount(secondCp);
                }
            }
            return step;
        }
        if (!isEmojiCodePoint(firstCp)) {
            return 0;
        }

        int i = start + Character.charCount(firstCp);
        boolean prevWasZwj = false;
        while (i < len) {
            int cp = text.codePointAt(i);
            if (isRegionalIndicator(cp)) {
                break;
            }
            if (prevWasZwj) {
                prevWasZwj = (cp == 0x200D);
                i += Character.charCount(cp);
                continue;
            }
            if (isEmojiJoiner(cp)) {
                prevWasZwj = (cp == 0x200D);
                i += Character.charCount(cp);
                continue;
            }
            break;
        }
        return i - start;
    }

    public static String condenseRepeatedEmojis(String text, String mode) {
        if (text == null || text.isEmpty() || VoiceSettings.REPEATED_CHARS_OFF.equals(mode)) {
            return text;
        }
        final int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            int clusterLen = getEmojiClusterLength(text, i);
            if (clusterLen == 0) {
                sb.append(text.charAt(i));
                i++;
                continue;
            }

            String cluster = text.substring(i, i + clusterLen);
            int count = 1;
            int nextPos = i + clusterLen;

            while (nextPos < len) {
                int peek = nextPos;
                while (peek < len && (text.charAt(peek) == ' ' || text.charAt(peek) == ',')) {
                    peek++;
                }
                int nextClusterLen = getEmojiClusterLength(text, peek);
                if (nextClusterLen > 0 && text.regionMatches(peek, cluster, 0, cluster.length())) {
                    count++;
                    nextPos = peek + nextClusterLen;
                } else {
                    break;
                }
            }

            if (count >= 3) {
                if (VoiceSettings.REPEATED_CHARS_COUNT.equals(mode)) {
                    sb.append(cluster).append(", ").append(count).append(" times ");
                } else if (VoiceSettings.REPEATED_CHARS_TRUNCATE.equals(mode)) {
                    sb.append(cluster).append(' ').append(cluster).append(' ').append(cluster).append(' ');
                } else {
                    sb.append(text, i, nextPos);
                }
                i = nextPos;
            } else {
                sb.append(text, i, nextPos);
                i = nextPos;
            }
        }
        return sb.toString();
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
        // Now an NVDA symbol pass at ALL (announce everything known). The
        // old 37-pattern chain, its trigger index and its collect-then-apply
        // machinery are gone; the differential fuzz harness that verified
        // that machinery is retired with them.
        return NvdaSymbolProcessor.processText(text, NvdaSymbolProcessor.LEVEL_ALL, true);
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
        if (s.regionMatches(true, 0, "https://", 0, 8)) {
            s = s.substring(8);
        } else if (s.regionMatches(true, 0, "http://", 0, 7)) {
            s = s.substring(7);
        }
        if (s.regionMatches(true, 0, "www.", 0, 4)) {
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
        if (text == null || text.isEmpty() || VoiceSettings.REPEATED_CHARS_OFF.equals(mode)) {
            return text;
        }
        // NVDA owns repeat collapsing now (" 20 dash ", 4+ runs, singular
        // table names): the old count/truncate split, the pluralized names
        // and the letter-run truncation are gone. Only runs are touched -
        // the rest of the text (sentence punctuation included) passes
        // through, exactly like the old contract. Emoji runs still condense
        // first - the symbol table has no emoji names (CLDR data, not
        // algorithm), so emoji never reach the repeat rule as named symbols.
        String processed = text;
        if (containsPotentialEmoji(processed)) {
            processed = condenseRepeatedEmojis(processed, mode);
        }
        return NvdaSymbolProcessor.collapseRepeatRuns(processed);
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
        if (text == null || text.isEmpty()) {
            return text;
        }
        final boolean devanagari = isDevanagariNumberLang(languageTag);
        // normalizeIndicDigits is already a single allocation-free char scan;
        // every regex below is additionally gated on a trigger its own
        // pattern provably requires (danda chars, '/', ',', ASCII digits),
        // so ordinary prose without such triggers skips all of them. Each
        // gate is exact, not an approximation: a pattern cannot match when
        // its trigger is absent, and the full pattern still decides whenever
        // the trigger is present.
        text = normalizeIndicDigits(text);
        if (text.indexOf('।') >= 0 || text.indexOf('॥') >= 0) {
            text = DANDA_BOUNDARY.matcher(text).replaceAll("$1 $2");
        }
        if (text.indexOf('/') >= 0) {
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
        }

        // CURRENCY_PREFIX, INDIAN_NUMBER_COMMAS and every SHORTHAND_*
        // pattern all require ASCII digits; computed once for all of them.
        final boolean hasDigit = containsDigit(text);
        if (hasDigit) {
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
        }

        if (text.indexOf(',') >= 0) {
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
        }

        if (hasDigit) {
            if (devanagari) {
                text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 हज़ार");
                text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 लाख");
                text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 करोड़");
            } else {
                text = SHORTHAND_THOUSAND.matcher(text).replaceAll("$1 thousand");
                text = SHORTHAND_LAKH.matcher(text).replaceAll("$1 lakh");
                text = SHORTHAND_CRORE.matcher(text).replaceAll("$1 crore");
            }
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

    /** {@link #clarifyEmojiAnnouncements(String, String)} with English names. */
    public static String clarifyEmojiAnnouncements(String text) {
        return clarifyEmojiAnnouncements(text, "en");
    }

    /**
     * NVDA-style emoji announcement: each emoji run is replaced by its CLDR
     * name in the voice's language ({@link NvdaEmoji}, longest match first,
     * so flags, ZWJ families and skin tones resolve to one name), padded
     * with spaces the way NVDA's symbol processor pads a replacement.
     */
    public static String clarifyEmojiAnnouncements(String text, String languageTag) {
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

            if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            out.append(NvdaEmoji.substitute(text.substring(runStart, i), languageTag));
            if (i < len && text.charAt(i) != ' ') {
                out.append(' ');
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
