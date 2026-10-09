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
    private static final Currency WON = new Currency("won", "won", null, null);
    private static final Currency BITCOIN = new Currency("bitcoin", "bitcoins", null, null);
    private static final Currency CENT_ONLY = new Currency("cent", "cents", null, null);

    private static final String UNIT =
            "US\\$|A\\$|C\\$|\\$|€|£|¥|₹|Rs\\.?|USD|EUR|GBP|JPY|INR|CAD|AUD|NZD|KRW|₩|BTC|₿";
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
            SIGN + "(?<![\\p{L}\\d.,])" + AMOUNT + SCALE + "\\s?(€|¥|₹|USD|EUR|GBP|JPY|INR|CAD|AUD|NZD|KRW|₩|BTC|₿|¢)(?![\\p{L}\\d])");

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
        return AsciiUtils.replaceMatches(pattern, text, m -> {
            Currency c = currencyFor(m.group(unitGroup));
            String words = (c == null || (c == RUPEE && !withRupee)) ? null
                    : moneyWords(c, m.group(intGroup), m.group(fracGroup), m.group(scaleGroup));
            if (words == null) {
                return m.group(0);
            }
            return (m.group(1) != null ? "minus " : "") + words;
        });
    }

    private static Currency currencyFor(String unit) {
        switch (unit) {
            case "$": case "US$": case "A$": case "C$": case "USD": case "CAD": case "AUD": case "NZD":
                return DOLLAR;
            case "€": case "EUR":
                return EURO;
            case "£": case "GBP":
                return POUND;
            case "¥": case "JPY":
                return YEN;
            case "₹": case "Rs": case "Rs.": case "INR":
                return RUPEE;
            case "₩": case "KRW":
                return WON;
            case "₿": case "BTC":
                return BITCOIN;
            case "¢":
                return CENT_ONLY;
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
            return intPart + " " + AsciiUtils.singularOrPlural(digits, c.one, c.many);
        }
        if (c.minorOne == null || frac.length() > 2) {
            // "¥1.5", "$3.499": the engine reads the decimal.
            return intPart + "." + frac + " " + c.many;
        }
        int minor = Integer.parseInt(frac.length() == 1 ? frac + "0" : frac);
        String minorWords = minor + " " + AsciiUtils.singularOrPlural(minor, c.minorOne, c.minorMany);
        if (zero) return minorWords;
        return intPart + " " + AsciiUtils.singularOrPlural(digits, c.one, c.many) + " " + minorWords;
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
        return AsciiUtils.replaceMatches(LONG_NUMBER, text,
                m -> m.group().replaceAll("(?<=\\d)(?=\\d)", " "));
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

    // ==========================================
    // Dimensions ("1920x1080" -> "1920 by 1080")
    // ==========================================

    private static final Pattern DIMENSION = Pattern.compile(
            "(?<![\\p{L}\\d.,])(\\d+(?:\\.\\d+)?)\\s*[xX×]\\s*(\\d+(?:\\.\\d+)?)(?![\\d.,]|(?!\\s*[xX×]\\s*\\d)[\\p{L}])");

    private static boolean containsDimensionCross(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c == 'x' || c == 'X' || c == '\u00D7') return true;
        }
        return false;
    }

    public static String readDimensions(String text) {
        if (text == null || text.isEmpty() || !containsDimensionCross(text) || !containsAsciiDigit(text)) return text;
        Matcher m = DIMENSION.matcher(text);
        if (!m.find()) return text;
        StringBuffer sb = new StringBuffer(text.length() + 16);
        boolean matched = false;
        do {
            if ("0".equals(m.group(1))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, m.group(1) + " by " + m.group(2));
                matched = true;
            }
        } while (m.find());
        m.appendTail(sb);
        String result = sb.toString();
        if (matched && containsDimensionCross(result) && DIMENSION.matcher(result).find()) {
            return readDimensions(result);
        }
        return result;
    }

    // ==========================================
    // ISO Dates ("2026-10-08" -> "8 October 2026") & Time Ranges ("10:00-11:30" -> "10:00 to 11:30")
    // ==========================================

    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<![\\d])(19\\d\\d|20\\d\\d)-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])(?![\\d])");

    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?<![\\p{L}\\d.,:])((?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*[aApP][mM])?)\\s*[-–—]\\s*((?:[01]?\\d|2[0-3]):[0-5]\\d(?:\\s*[aApP][mM])?)(?![\\p{L}\\d.,:]|[aApP][mM])");

    private static final String[] ISO_MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    public static String readDates(String text) {
        if (text == null || text.isEmpty() || !containsAsciiDigit(text)) return text;
        if (text.indexOf('-') != -1) {
            text = AsciiUtils.replaceMatches(ISO_DATE, text, m -> {
                String year = m.group(1);
                int monthIdx = Integer.parseInt(m.group(2)) - 1;
                int day = Integer.parseInt(m.group(3));
                String monthName = (monthIdx >= 0 && monthIdx < ISO_MONTHS.length) ? ISO_MONTHS[monthIdx] : m.group(2);
                return day + " " + monthName + " " + year;
            });
        }
        if (text.indexOf(':') != -1 && (text.indexOf('-') != -1 || text.indexOf('–') != -1 || text.indexOf('—') != -1)) {
            text = AsciiUtils.replaceMatches(TIME_RANGE, text, m -> m.group(1) + " to " + m.group(2));
        }
        return text;
    }

    // ==========================================
    // Phone numbers ("+1-800-555-0199" -> "+1 800 555 0199", "9876543210" -> "9 8 ...")
    // ==========================================

    private static final Pattern FORMATTED_PHONE = Pattern.compile(
            "(?<![\\p{L}\\d.,])(\\+?\\d{1,3}[- ])?(\\(?\\d{3}\\)?[- ])(\\d{3})[- ](\\d{4})(?![\\p{L}\\d.,])");

    public static String readPhoneNumbers(String text) {
        if (text == null || text.isEmpty() || !containsAsciiDigit(text)) return text;
        if (text.indexOf('-') != -1 || text.indexOf('(') != -1) {
            text = AsciiUtils.replaceMatches(FORMATTED_PHONE, text, m ->
                    m.group().replace('-', ' ').replace('(', ' ').replace(')', ' ').replaceAll(" +", " ").trim());
        }
        return spellLongNumbers(text);
    }

    // ==========================================
    // Basic Math Expressions ("5 + 3 = 8" -> "5 plus 3 equals 8")
    // ==========================================

    private static final Pattern MATH_PLUS = Pattern.compile("(?<=\\d)\\s*\\+\\s*(?=\\d)");
    private static final Pattern MATH_EQUALS = Pattern.compile("(?<=\\d)\\s*=\\s*(?=\\d)");
    private static final Pattern MATH_TIMES = Pattern.compile("(?<=\\d)\\s*[×*]\\s*(?=\\d)");
    private static final Pattern MATH_DIVIDE = Pattern.compile("(?<=\\d)\\s*[÷/]\\s*(?=\\d)");
    private static final Pattern MATH_MINUS = Pattern.compile("(?<=\\d)\\s+-\\s+(?=\\d)");

    public static String readMath(String text) {
        if (text == null || text.isEmpty() || !containsAsciiDigit(text)) return text;
        if (text.indexOf('+') != -1) {
            text = MATH_PLUS.matcher(text).replaceAll(" plus ");
        }
        if (text.indexOf('=') != -1) {
            text = MATH_EQUALS.matcher(text).replaceAll(" equals ");
        }
        if (text.indexOf('*') != -1 || text.indexOf('\u00D7') != -1) {
            text = MATH_TIMES.matcher(text).replaceAll(" times ");
        }
        if (text.indexOf('/') != -1 || text.indexOf('\u00F7') != -1) {
            text = MATH_DIVIDE.matcher(text).replaceAll(" divided by ");
        }
        if (text.indexOf('-') != -1) {
            text = MATH_MINUS.matcher(text).replaceAll(" minus ");
        }
        return text;
    }

    // ==========================================
    // Roman Numerals in Headings and Names
    // ==========================================

    private static final String[] ROMAN_ORDINALS = {
            "the First", "the Second", "the Third", "the Fourth", "the Fifth",
            "the Sixth", "the Seventh", "the Eighth", "the Ninth", "the Tenth",
            "the Eleventh", "the Twelfth", "the Thirteenth", "the Fourteenth", "the Fifteenth",
            "the Sixteenth", "the Seventeenth", "the Eighteenth", "the Nineteenth", "the Twentieth"
    };

    private static final String[] ROMAN_NUMERALS = {
            "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
            "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"
    };

    private static final java.util.Map<String, Integer> ROMAN_VALUES = new java.util.HashMap<>(32);
    static {
        for (int i = 0; i < ROMAN_NUMERALS.length; i++) {
            ROMAN_VALUES.put(ROMAN_NUMERALS[i], i + 1);
        }
    }

    // Regnal / monarch / papal titles or names followed by Roman numeral
    private static final Pattern REGNAL_ROMAN = Pattern.compile(
            "(?i)(?<![\\p{L}])(King|Queen|Pope|Emperor|Prince|Princess|Henry|George|Charles"
                    + "|Louis|Edward|James|William|Elizabeth|Richard|Alexander|Nicholas|Philip|Paul)"
                    + "\\s+(X{0,2}(?:IX|IV|V?I{1,3}|X))(?![\\p{L}\\p{N}])");

    // Headings / chapter / section / wars followed by Roman numeral
    private static final Pattern HEADING_ROMAN = Pattern.compile(
            "(?i)(?<![\\p{L}])(Chapter|Part|Section|Volume|Vol|Act|Scene|Book|World War|WW|Grade|Phase|Tier)"
                    + "\\s+(X{0,2}(?:IX|IV|V?I{1,3}|X))(?![\\p{L}\\p{N}])");

    public static String readRomanNumerals(String text) {
        if (text == null || text.isEmpty() || !AsciiUtils.hasAsciiLetter(text)) return text;
        // Check regnal first
        text = AsciiUtils.replaceMatches(REGNAL_ROMAN, text, m -> {
            String roman = m.group(2).toUpperCase(Locale.ROOT);
            Integer val = ROMAN_VALUES.get(roman);
            if (val != null && val >= 1 && val <= ROMAN_ORDINALS.length) {
                return m.group(1) + " " + ROMAN_ORDINALS[val - 1];
            }
            return m.group(0);
        });

        // Check headings
        text = AsciiUtils.replaceMatches(HEADING_ROMAN, text, m -> {
            String roman = m.group(2).toUpperCase(Locale.ROOT);
            Integer val = ROMAN_VALUES.get(roman);
            if (val != null) {
                return m.group(1) + " " + val;
            }
            return m.group(0);
        });

        return text;
    }

    // ==========================================
    // Fractions & Mixed Numbers
    // ==========================================

    private static final String UNICODE_FRACTIONS_CHARS = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞";

    private static boolean containsUnicodeFraction(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (UNICODE_FRACTIONS_CHARS.indexOf(text.charAt(i)) != -1) return true;
        }
        return false;
    }

    private static String unicodeFractionName(char ch, boolean mixed) {
        switch (ch) {
            case '½': return mixed ? "a half" : "1 half";
            case '⅓': return mixed ? "a third" : "1 third";
            case '⅔': return "2 thirds";
            case '¼': return mixed ? "a quarter" : "1 quarter";
            case '¾': return "3 quarters";
            case '⅕': return mixed ? "a fifth" : "1 fifth";
            case '⅖': return "2 fifths";
            case '⅗': return "3 fifths";
            case '⅘': return "4 fifths";
            case '⅙': return mixed ? "a sixth" : "1 sixth";
            case '⅚': return "5 sixths";
            case '⅛': return mixed ? "an eighth" : "1 eighth";
            case '⅜': return "3 eighths";
            case '⅝': return "5 eighths";
            case '⅞': return "7 eighths";
            default: return null;
        }
    }

    private static String fractionName(int num, int den, boolean mixed) {
        if (num == 1) {
            if (den == 2) return mixed ? "a half" : "1 half";
            if (den == 4) return mixed ? "a quarter" : "1 quarter";
            if (den == 3) return mixed ? "a third" : "1 third";
        }
        String dName;
        switch (den) {
            case 2: dName = num == 1 ? "half" : "halves"; break;
            case 3: dName = num == 1 ? "third" : "thirds"; break;
            case 4: dName = num == 1 ? "quarter" : "quarters"; break;
            case 5: dName = num == 1 ? "fifth" : "fifths"; break;
            case 6: dName = num == 1 ? "sixth" : "sixths"; break;
            case 7: dName = num == 1 ? "seventh" : "sevenths"; break;
            case 8: dName = num == 1 ? "eighth" : "eighths"; break;
            case 9: dName = num == 1 ? "ninth" : "ninths"; break;
            case 10: dName = num == 1 ? "tenth" : "tenths"; break;
            case 12: dName = num == 1 ? "twelfth" : "twelfths"; break;
            case 16: dName = num == 1 ? "sixteenth" : "sixteenths"; break;
            case 32: dName = num == 1 ? "thirty-second" : "thirty-seconds"; break;
            case 64: dName = num == 1 ? "sixty-fourth" : "sixty-fourths"; break;
            case 100: dName = num == 1 ? "hundredth" : "hundredths"; break;
            default: return null;
        }
        return num + " " + dName;
    }

    private static final Pattern MIXED_UNICODE_FRACTION = Pattern.compile(
            "(\\b\\d+)\\s*([½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞])");

    private static final Pattern STANDALONE_UNICODE_FRACTION = Pattern.compile(
            "([½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞])");

    private static final Pattern MIXED_SLASH_FRACTION = Pattern.compile(
            "(\\b\\d+)\\s+(\\d{1,2})/(\\d{1,3})(?![\\d/])");

    private static final Pattern STANDALONE_SLASH_FRACTION = Pattern.compile(
            "(?<![\\d/])(-)?(\\d{1,2})/(\\d{1,3})(?![\\d/])");

    public static String readFractions(String text) {
        if (text == null || text.isEmpty()) return text;
        boolean hasSlash = text.indexOf('/') != -1;
        boolean hasUnicode = containsUnicodeFraction(text);
        if (!hasSlash && !hasUnicode) return text;

        if (hasUnicode) {
            text = AsciiUtils.replaceMatches(MIXED_UNICODE_FRACTION, text, m -> {
                String whole = m.group(1);
                char fracChar = m.group(2).charAt(0);
                String name = unicodeFractionName(fracChar, true);
                return name != null ? whole + " and " + name : m.group(0);
            });

            text = AsciiUtils.replaceMatches(STANDALONE_UNICODE_FRACTION, text, m -> {
                char fracChar = m.group(1).charAt(0);
                String name = unicodeFractionName(fracChar, false);
                return name != null ? name : m.group(0);
            });
        }

        if (hasSlash) {
            text = AsciiUtils.replaceMatches(MIXED_SLASH_FRACTION, text, m -> {
                String whole = m.group(1);
                int num = Integer.parseInt(m.group(2));
                int den = Integer.parseInt(m.group(3));
                String name = (num < den) ? fractionName(num, den, true) : null;
                return name != null ? whole + " and " + name : m.group(0);
            });

            text = AsciiUtils.replaceMatches(STANDALONE_SLASH_FRACTION, text, m -> {
                boolean negative = m.group(1) != null;
                int num = Integer.parseInt(m.group(2));
                int den = Integer.parseInt(m.group(3));
                String name = (num < den) ? fractionName(num, den, false) : null;
                return name != null ? (negative ? "minus " : "") + name : m.group(0);
            });
        }

        return text;
    }

    // ==========================================
    // Superscripts and Subscripts
    // ==========================================

    private static boolean containsSubSuper(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '²' || c == '³' || c == '¹' || c == 'ⁿ') return true;
            if (c >= '\u2070' && c <= '\u209F') return true;
        }
        return false;
    }

    private static final Pattern AREA_VOLUME_UNITS = Pattern.compile(
            "\\b(mm|cm|m|km|in|ft|yd|mi)([²³])(?![\\p{L}\\d])");

    private static final Pattern SQUARED_CUBED = Pattern.compile(
            "(?<=[\\p{L}\\d])([²³])");

    public static String readSubSuper(String text) {
        if (text == null || text.isEmpty() || !containsSubSuper(text)) return text;

        text = AsciiUtils.replaceMatches(AREA_VOLUME_UNITS, text, m -> {
            String unit = m.group(1);
            char p = m.group(2).charAt(0);
            String prefix = (p == '²') ? "square " : "cubic ";
            String uName;
            switch (unit) {
                case "mm": uName = "millimeters"; break;
                case "cm": uName = "centimeters"; break;
                case "m": uName = "meters"; break;
                case "km": uName = "kilometers"; break;
                case "in": uName = "inches"; break;
                case "ft": uName = "feet"; break;
                case "yd": uName = "yards"; break;
                case "mi": uName = "miles"; break;
                default: uName = unit; break;
            }
            return prefix + uName;
        });

        text = AsciiUtils.replaceMatches(SQUARED_CUBED, text,
                m -> m.group(1).charAt(0) == '²' ? " squared" : " cubed");

        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '⁰': sb.append(" to the 0"); break;
                case '¹': sb.append(" to the 1"); break;
                case '⁴': sb.append(" to the 4th"); break;
                case '⁵': sb.append(" to the 5th"); break;
                case '⁶': sb.append(" to the 6th"); break;
                case '⁷': sb.append(" to the 7th"); break;
                case '⁸': sb.append(" to the 8th"); break;
                case '⁹': sb.append(" to the 9th"); break;
                case 'ⁿ': sb.append(" to the n"); break;
                case '⁺': sb.append(" plus"); break;
                case '⁻': sb.append(" minus"); break;
                case '₀': sb.append(" 0 "); break;
                case '₁': sb.append(" 1 "); break;
                case '₂': sb.append(" 2 "); break;
                case '₃': sb.append(" 3 "); break;
                case '₄': sb.append(" 4 "); break;
                case '₅': sb.append(" 5 "); break;
                case '₆': sb.append(" 6 "); break;
                case '₇': sb.append(" 7 "); break;
                case '₈': sb.append(" 8 "); break;
                case '₉': sb.append(" 9 "); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    // ==========================================
    // Ordinal Numbers
    // ==========================================

    private static final String[] ORDINALS_1_TO_31 = {
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
            "eighteenth", "nineteenth", "twentieth", "twenty-first", "twenty-second", "twenty-third",
            "twenty-fourth", "twenty-fifth", "twenty-sixth", "twenty-seventh", "twenty-eighth",
            "twenty-ninth", "thirtieth", "thirty-first"
    };

    private static final Pattern ENGLISH_ORDINALS = Pattern.compile(
            "\\b(\\d+)(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SYMBOL_ORDINALS = Pattern.compile(
            "\\b(\\d+)[ºª]\\b");

    private static boolean containsOrdinalIndicator(String text) {
        if (text == null || text.length() < 2) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'º' || c == 'ª') return true;
            if (AsciiUtils.isAsciiDigit(c) && i + 2 < text.length()) {
                char c1 = AsciiUtils.toAsciiLowerCase(text.charAt(i + 1));
                char c2 = AsciiUtils.toAsciiLowerCase(text.charAt(i + 2));
                if ((c1 == 's' && c2 == 't') || (c1 == 'n' && c2 == 'd')
                        || (c1 == 'r' && c2 == 'd') || (c1 == 't' && c2 == 'h')) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String readOrdinals(String text) {
        if (text == null || text.isEmpty() || !containsOrdinalIndicator(text)) return text;

        text = AsciiUtils.replaceMatches(SYMBOL_ORDINALS, text, m -> {
            int num = Integer.parseInt(m.group(1));
            String suf = "th";
            if (num % 100 < 11 || num % 100 > 13) {
                if (num % 10 == 1) suf = "st";
                else if (num % 10 == 2) suf = "nd";
                else if (num % 10 == 3) suf = "rd";
            }
            return num + suf;
        });

        text = AsciiUtils.replaceMatches(ENGLISH_ORDINALS, text, m -> {
            try {
                int num = Integer.parseInt(m.group(1));
                if (num >= 1 && num <= 31) {
                    return ORDINALS_1_TO_31[num - 1];
                } else if (num == 100) {
                    return "hundredth";
                } else if (num == 1000) {
                    return "thousandth";
                } else if (num == 1000000) {
                    return "millionth";
                }
                return m.group(0);
            } catch (NumberFormatException e) {
                return m.group(0);
            }
        });

        return text;
    }

    private static boolean isSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (c == '\n' || c == '!' || c == '?' || c == '।'
                || c == '？' || c == '！' || c == '؟' || c == '\u037E' || c == '‽') return true;
        // A full stop ends a sentence only when followed by a space or the end.
        return c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
    }

    private static boolean containsAsciiDigit(String text) {
        return AsciiUtils.hasAsciiDigit(text);
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
