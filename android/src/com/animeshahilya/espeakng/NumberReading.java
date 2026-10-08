package com.animeshahilya.espeakng;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in number extras on top of the NVDA baseline. All are off by default;
 * with them off, text reaches eSpeak exactly as NVDA would send it.
 *
 * Numbers are left as digits so eSpeak still says them in its own voice;
 * only the order and the unit words change ("$5.50" becomes
 * "5 dollars 50 cents", not "dollar five point five zero").
 */
public final class NumberReading {

    private NumberReading() {
    }

    // ==========================================
    // Money amounts (English voices)
    // ==========================================

    /** Major unit singular/plural, minor unit singular/plural (null: no minor unit). */
    private static final class Currency {
        final String one, many, minorOne, minorMany;

        Currency(String one, String many, String minorOne, String minorMany) {
            this.one = one;
            this.many = many;
            this.minorOne = minorOne;
            this.minorMany = minorMany;
        }
    }

    private static final Currency DOLLAR = new Currency("dollar", "dollars", "cent", "cents");
    private static final Currency EURO = new Currency("euro", "euros", "cent", "cents");
    private static final Currency POUND = new Currency("pound", "pounds", "penny", "pence");
    private static final Currency YEN = new Currency("yen", "yen", null, null);
    private static final Currency RUPEE = new Currency("rupee", "rupees", "paisa", "paise");

    private static final String UNIT =
            "US\\$|A\\$|C\\$|\\$|€|£|¥|₹|Rs\\.?|USD|EUR|GBP|JPY|INR";
    // 1,234,567 or 1,23,456 or 1234, with an optional decimal part.
    private static final String AMOUNT = "(\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.(\\d+))?";
    // Words may follow a space; abbreviations must touch ("$5m", not "$5 m").
    private static final String SCALE =
            "(\\s?(?:thousand|million|billion|trillion|lakh|crore)|k|K|mn|m|M|bn|B)?\\b";
    // A minus only at the start of a word: "$5-$10" is a range, not "minus 10".
    private static final String SIGN = "(?:(?<![\\p{L}\\d])([-−])\\s?)?";

    // "$5.50", "-$4", "USD 20", "€2.5M"
    private static final Pattern MONEY_PREFIX = Pattern.compile(
            SIGN + "(?<![\\p{L}\\d])(" + UNIT + ")\\s?" + AMOUNT + SCALE + "(?![\\d.,]\\d)");
    // "20 USD", "5€"; "3,50 €" is left alone (decimal comma is ambiguous)
    private static final Pattern MONEY_SUFFIX = Pattern.compile(
            SIGN + "(?<![\\p{L}\\d.,])" + AMOUNT + SCALE + "\\s?(€|¥|₹|USD|EUR|GBP|JPY|INR)(?![\\p{L}\\d])");

    private static final java.util.Map<String, Boolean> LATIN_SCRIPT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether the language is written in Latin script. Such a voice reads
     * Latin words as its own language, so its numbers already match; any
     * other voice switches to English for Latin words but not for numbers.
     */
    public static boolean isLatinScript(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return true;
        Boolean cached = LATIN_SCRIPT_CACHE.get(languageTag);
        if (cached != null) return cached;
        String script = android.icu.util.ULocale.addLikelySubtags(
                android.icu.util.ULocale.forLanguageTag(languageTag)).getScript();
        boolean isLatin = script.isEmpty() || "Latn".equals(script);
        LATIN_SCRIPT_CACHE.put(languageTag, isLatin);
        return isLatin;
    }

    public static boolean isEnglish(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return false;
        int len = languageTag.length();
        int start = 0;
        while (start < len && languageTag.charAt(start) <= ' ') {
            start++;
        }
        if (start + 2 <= len) {
            char c0 = languageTag.charAt(start);
            char c1 = languageTag.charAt(start + 1);
            if ((c0 == 'e' || c0 == 'E') && (c1 == 'n' || c1 == 'N')) {
                int end = start + 2;
                return end == len || languageTag.charAt(end) == '-' || languageTag.charAt(end) == '_' || languageTag.charAt(end) <= ' ';
            }
        }
        return false;
    }

    /**
     * @param withRupee false when the Indian pass will read rupee amounts
     *                  itself (Indian voices with Indian numbering on)
     */
    public static String readMoney(String text, boolean withRupee) {
        if (text == null || text.isEmpty() || !containsAsciiDigit(text)) return text;
        text = replaceMoney(MONEY_PREFIX, text, 2, 3, 4, 5, withRupee);
        text = replaceMoney(MONEY_SUFFIX, text, 5, 2, 3, 4, withRupee);
        return text;
    }

    private static String replaceMoney(Pattern pattern, String text, int unitGroup,
                                       int intGroup, int fracGroup, int scaleGroup, boolean withRupee) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        StringBuffer sb = new StringBuffer(text.length() + 32);
        do {
            Currency c = currencyFor(m.group(unitGroup));
            String words = (c == null || (c == RUPEE && !withRupee)) ? null
                    : moneyWords(c, m.group(intGroup), m.group(fracGroup), m.group(scaleGroup));
            if (words == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                String sign = m.group(1) != null ? "minus " : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(sign + words));
            }
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static Currency currencyFor(String unit) {
        switch (unit) {
            case "$": case "US$": case "A$": case "C$": case "USD":
                return DOLLAR;
            case "€": case "EUR":
                return EURO;
            case "£": case "GBP":
                return POUND;
            case "¥": case "JPY":
                return YEN;
            case "₹": case "Rs": case "Rs.": case "INR":
                return RUPEE;
            default:
                return null;
        }
    }

    private static String moneyWords(Currency c, String intPart, String frac, String scale) {
        if (scale != null) {
            scale = scale.trim();
            // "$2.5M" -> "2.5 million dollars"
            String amount = frac != null ? intPart + "." + frac : intPart;
            return amount + " " + scaleWord(scale) + " " + c.many;
        }
        String digits = intPart.indexOf(',') >= 0 ? intPart.replace(",", "") : intPart;
        boolean zero = isAllZeros(digits);
        if (frac == null || isAllZeros(frac)) {
            return intPart + " " + ("1".equals(digits) ? c.one : c.many);
        }
        if (c.minorOne == null || frac.length() > 2) {
            // "¥1.5", "$3.499": the engine reads the decimal.
            return intPart + "." + frac + " " + c.many;
        }
        int minor = Integer.parseInt(frac.length() == 1 ? frac + "0" : frac);
        String minorWords = minor + " " + (minor == 1 ? c.minorOne : c.minorMany);
        if (zero) return minorWords;
        return intPart + " " + ("1".equals(digits) ? c.one : c.many) + " " + minorWords;
    }

    private static boolean isAllZeros(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) != '0') return false;
        }
        return true;
    }

    private static String scaleWord(String s) {
        switch (s) {
            case "k": case "K": return "thousand";
            case "m": case "M": case "mn": return "million";
            case "b": case "B": case "bn": return "billion";
            default: return s;
        }
    }

    // ==========================================
    // Codes digit by digit (every voice)
    // ==========================================

    // Words that mark a nearby number as a code rather than a quantity.
    private static final Pattern CODE_KEYWORD = Pattern.compile(
            "(?iu)(?<![\\p{L}])(otp|one[- ]time|pin|passcode|password|code|cvv|pnr|token"
                    + "|txn|transaction|ref|reference|a/c|acct|account|card|id"
                    + "|ओटीपी|कोड|पिन|पासवर्ड|खाता)(?![\\p{L}])");

    // 4-12 digits, optionally in groups split by one space or hyphen
    // ("482913", "482 913", "4829-1374"), optionally after a mask ("XX1234").
    private static final Pattern CODE_RUN = Pattern.compile(
            "(?<![\\p{L}\\d.,:/$€£¥₹-])([Xx*•]{2,})?(\\d{2,}(?:[ -]\\d{2,})*)(?![\\d.,:%/]?\\d)(?![\\p{L}%])");

    // Money is never a code: "debited Rs 5000 from account", "5000 USD".
    private static final Pattern MONEY_BEFORE = Pattern.compile(
            "(?i)(?<![\\p{L}])(?:rs\\.?|inr|usd|eur|gbp|jpy)\\s?$");
    private static final Pattern MONEY_AFTER = Pattern.compile(
            "(?iu)^\\s?(?:[€¥₹$£]|(?:rs|rupees?|dollars?|euros?|pounds?|yen|usd|inr|eur|gbp|jpy|paise|cents?)(?![\\p{L}]))");

    private static final int LOOK_BEHIND = 40;
    private static final int LOOK_AHEAD = 30;

    // Ten digits or more with nothing joining them to other digits: a phone,
    // account or card number, never a quantity anyone says as one.
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d.,])\\d{10,}(?!\\d|[.,]\\d)");

    /** "9876543210" -> "9 8 7 6 5 4 3 2 1 0". */
    static String spellLongNumbers(String text) {
        if (text == null || !containsAsciiDigit(text)) return text;
        Matcher m = LONG_NUMBER.matcher(text);
        if (!m.find()) return text;
        StringBuffer sb = new StringBuffer(text.length() * 2);
        do {
            m.appendReplacement(sb,m.group().replaceAll("(?<=\\d)(?=\\d)", " "));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    public static String readCodes(String text) {
        if (text == null || text.isEmpty() || !containsAsciiDigit(text)) return text;
        if (!CODE_KEYWORD.matcher(text).find()) return text;
        Matcher m = CODE_RUN.matcher(text);
        StringBuffer sb = null;
        while (m.find()) {
            String run = m.group(2);
            int digits = 0;
            for (int i = 0; i < run.length(); i++) {
                if (run.charAt(i) >= '0' && run.charAt(i) <= '9') digits++;
            }
            if (digits < 4 || digits > 12 || isMoney(text, m.start(), m.end())
                    || !keywordNear(text, m.start(), m.end())) {
                continue;
            }
            if (sb == null) sb = new StringBuffer(text.length() + 32);
            StringBuilder out = new StringBuilder();
            if (m.group(1) != null) out.append("ending ");
            for (int i = 0; i < run.length(); i++) {
                char ch = run.charAt(i);
                if (ch == ' ' || ch == '-') {
                    out.append(',');
                } else {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                    out.append(ch);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out.toString()));
        }
        if (sb == null) return text;
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isMoney(String text, int start, int end) {
        return MONEY_BEFORE.matcher(text.substring(Math.max(0, start - 5), start)).find()
                || MONEY_AFTER.matcher(text.substring(end, Math.min(text.length(), end + 9))).find();
    }

    /** A keyword in the same sentence, shortly before or after the number. */
    private static boolean keywordNear(String text, int start, int end) {
        int from = Math.max(0, start - LOOK_BEHIND);
        for (int i = start - 1; i >= from; i--) {
            if (isSentenceEnd(text, i)) {
                from = i + 1;
                break;
            }
        }
        int to = Math.min(text.length(), end + LOOK_AHEAD);
        for (int i = end; i < to; i++) {
            if (isSentenceEnd(text, i)) {
                to = i;
                break;
            }
        }
        return CODE_KEYWORD.matcher(text).region(from, to).find();
    }

    private static boolean isSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (c == '\n' || c == '!' || c == '?' || c == '।'
                || c == '？' || c == '！' || c == '؟' || c == '\u037E' || c == '‽') return true;
        // A full stop ends a sentence only when followed by a space or the end.
        return c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
    }

    private static boolean containsAsciiDigit(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    // ==========================================
    // Numbers inside English text (non-Latin-script voices)
    // ==========================================

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,])(\\d+(?:,\\d+)*)(\\.\\d+)?(?![\\p{L}\\p{N}]|[.,]\\d)");
    private static final String SENTENCE_ENDS = ".!?।\n？！؟\u037E‽";
    /** Longer numbers (phone numbers, IDs) are read digit by digit. */
    private static final int MAX_WHOLE_DIGITS = 7;
    /** How far to look for the words either side of a number. */
    private static final int NEIGHBOUR_REACH = 40;

    /**
     * A voice for a non-Latin-script language (Hindi, Urdu, Russian, Arabic,
     * Greek...) reads English words in English but numbers in its own
     * language ("I have 25 apples" -> "I have pacchees apples"). A number
     * whose neighbouring words are Latin script, and none in another script,
     * is written out in English words, which eSpeak then reads in English
     * with the words around it. A number next to Hindi, Russian etc. is left
     * alone.
     */
    public static String englishNumbersInEnglishText(String text) {
        return englishNumbers(text, false);
    }

    /** Clock times: 10:30, 9:05, 14:00 (not ratios or scores with more digits). */
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\p{L}\\p{N}]|[.,:]\\d)");

    /**
     * Numbers and clock times written out as English words (see
     * {@link #englishNumbersInEnglishText}): every one when {@code all},
     * else only those whose neighbouring words are English. 10:30 is "ten
     * thirty", 9:05 "nine oh five", 10:00 "ten o'clock".
     */
    public static String englishNumbers(String text, boolean all) {
        if (text == null || !containsAsciiDigit(text)) {
            return text;
        }
        return replaceEnglish(replaceEnglish(text, TIME, all, true), NUMBER, all, false);
    }

    private static String replaceEnglish(String text, Pattern pattern, boolean all, boolean time) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = null;
        while (m.find()) {
            if (!all) {
                int before = neighbourScript(text, m.start() - 1, -1);
                int after = neighbourScript(text, m.end(), 1);
                boolean english = (before == LATIN || after == LATIN)
                        && before != OTHER && after != OTHER;
                if (!english) {
                    continue;
                }
            }
            if (sb == null) {
                sb = new StringBuffer(text.length() + 32);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(time
                    ? spellTime(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                    : spell(m.group(1).replace(",", ""), m.group(2))));
        }
        if (sb == null) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String spellTime(int hours, int minutes) {
        StringBuilder out = new StringBuilder();
        words(out, hours);
        if (minutes == 0) {
            out.append(" o'clock");
        } else {
            if (minutes < 10) {
                out.append(" oh");
            }
            words(out, minutes);
        }
        return out.toString().trim();
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty",
            "sixty", "seventy", "eighty", "ninety"};

    /** English words for a number: whole part in words, or digit by digit when long. */
    static String spell(String whole, String fraction) {
        StringBuilder out = new StringBuilder();
        if (whole.length() > MAX_WHOLE_DIGITS || (whole.length() > 1 && whole.charAt(0) == '0')) {
            digits(out, whole);
        } else {
            words(out, Integer.parseInt(whole));
        }
        if (fraction != null) {
            out.append(" point");
            digits(out, fraction.substring(1));
        }
        return out.toString().trim();
    }

    private static void digits(StringBuilder out, String ds) {
        for (int i = 0; i < ds.length(); i++) {
            out.append(' ').append(ONES[ds.charAt(i) - '0']);
        }
    }

    private static void words(StringBuilder out, int n) {
        if (n >= 1000000) {
            words(out, n / 1000000);
            out.append(" million");
            n %= 1000000;
            if (n == 0) return;
        }
        if (n >= 1000) {
            words(out, n / 1000);
            out.append(" thousand");
            n %= 1000;
            if (n == 0) return;
        }
        if (n >= 100) {
            out.append(' ').append(ONES[n / 100]).append(" hundred");
            n %= 100;
            if (n == 0) return;
        }
        if (n >= 20) {
            out.append(' ').append(TENS[n / 10]);
            if (n % 10 != 0) out.append(' ').append(ONES[n % 10]);
        } else if (n > 0 || out.length() == 0) {
            out.append(' ').append(ONES[n]);
        }
    }

    private static final int NONE = 0, LATIN = 1, OTHER = 2;

    /** Script of the nearest letter from {@code i} in direction {@code step}. */
    private static int neighbourScript(String text, int i, int step) {
        for (int n = 0; i >= 0 && i < text.length() && n < NEIGHBOUR_REACH; i += step, n++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                return Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN ? LATIN : OTHER;
            }
            if (SENTENCE_ENDS.indexOf(c) >= 0) {
                return NONE;     // a sentence boundary: the other sentence does not count
            }
        }
        return NONE;
    }
}
