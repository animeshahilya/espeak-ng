package com.animeshahilya.espeakng;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abbreviation expansion (opt-in, English voices, like Eloquence's
 * abbreviation dictionary). eSpeak already expands Dr, Mr, Mrs, St, etc,
 * e.g., dept and Ltd itself; these are the common ones it reads as written
 * or spells out. Ambiguous ones only expand in a context that settles them:
 * units after a number, months next to a number, "No." before one.
 */
public final class Abbreviations {
    private Abbreviations() {}

    private static final String[][] WORDS = {
            {"Prof", "Professor"}, {"vs", "versus"}, {"approx", "approximately"},
            {"govt", "government"}, {"Govt", "Government"}, {"Pvt", "Private"},
            {"Inc", "Incorporated"}, {"misc", "miscellaneous"}, {"Misc", "Miscellaneous"},
            {"Jr", "Junior"}, {"Sr", "Senior"}, {"Rd", "Road"}, {"Ave", "Avenue"},
            {"Tel", "Telephone"}, {"Asst", "Assistant"}, {"Mgr", "Manager"},
            {"Hon'ble", "Honourable"},
    };
    private static final Pattern WORD;
    static {
        StringBuilder alt = new StringBuilder();
        for (String[] w : WORDS) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(w[0]));
        }
        // whole word, optional trailing dot (kept as a pause only at a clause end)
        WORD = Pattern.compile("(?<![\\p{L}\\p{N}'])(" + alt + ")(\\.)?(?![\\p{L}\\p{N}'])");
    }

    private static final String[][] UNITS = {
            {"km", "kilometre", "kilometres"}, {"kg", "kilogram", "kilograms"},
            {"cm", "centimetre", "centimetres"}, {"mm", "millimetre", "millimetres"},
            {"ft", "foot", "feet"}, {"hr", "hour", "hours"}, {"hrs", "hour", "hours"},
            {"min", "minute", "minutes"}, {"mins", "minute", "minutes"},
            {"sec", "second", "seconds"}, {"secs", "second", "seconds"},
            {"yr", "year", "years"}, {"yrs", "year", "years"},
            {"mg", "milligram", "milligrams"},
    };
    private static final Pattern UNIT;
    static {
        StringBuilder alt = new StringBuilder();
        for (String[] u : UNITS) {
            if (alt.length() > 0) alt.append('|');
            alt.append(u[0]);
        }
        UNIT = Pattern.compile("(?<![\\p{L}\\p{N}.])(\\d+(?:[.,]\\d+)?) ?(" + alt + ")\\.?(?![\\p{L}\\p{N}])");
    }

    private static final String[][] MONTHS = {
            {"Jan", "January"}, {"Feb", "February"}, {"Mar", "March"}, {"Apr", "April"},
            {"Jun", "June"}, {"Jul", "July"}, {"Aug", "August"}, {"Sep", "September"},
            {"Sept", "September"}, {"Oct", "October"}, {"Nov", "November"}, {"Dec", "December"},
    };
    private static final Pattern MONTH = Pattern.compile(
            "(?<=\\d )(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)\\.?(?![\\p{L}])"
                    + "|(?<![\\p{L}])(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)\\.?(?= \\d)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}])([Nn])o\\. ?(?=\\d)");

    private static final java.util.Map<String, String> WORD_MAP = new java.util.HashMap<>(32);
    private static final java.util.Map<String, String[]> UNIT_MAP = new java.util.HashMap<>(32);
    private static final java.util.Map<String, String> MONTH_MAP = new java.util.HashMap<>(16);
    static {
        for (String[] w : WORDS) {
            WORD_MAP.put(w[0], w[1]);
        }
        for (String[] u : UNITS) {
            UNIT_MAP.put(u[0], new String[] {u[1], u[2]});
        }
        for (String[] m : MONTHS) {
            MONTH_MAP.put(m[0], m[1]);
        }
    }

    public static String process(String text) {
        if (text == null || text.isEmpty() || !AsciiUtils.hasAsciiLetter(text)) {
            return text;
        }
        text = replace(WORD, text, m -> {
            String full = WORD_MAP.get(m.group(1));
            if (full == null) full = m.group(1);
            return full + (m.group(2) != null && endsClause(m) ? "." : "");
        });
        if (AsciiUtils.hasDigit(text)) {
            text = replace(UNIT, text, m -> {
                String[] u = UNIT_MAP.get(m.group(2));
                if (u != null) {
                    boolean one = "1".equals(m.group(1));
                    return m.group(1) + " " + (one ? u[0] : u[1]);
                }
                return m.group();
            });
            text = replace(MONTH, text, m -> {
                String full = MONTH_MAP.get(m.group(1) != null ? m.group(1) : m.group(2));
                return full != null ? full : (m.group(1) != null ? m.group(1) : m.group(2));
            });
            if (text.indexOf("o.") != -1 || text.indexOf("O.") != -1) {
                text = replace(NUMBER, text, m -> "N".equals(m.group(1)) ? "Number " : "number ");
            }
        }
        return text;
    }

    /** A dot at the very end stays, as the pause that ends the text. */
    private static boolean endsClause(Matcher m) {
        return m.end() >= m.regionEnd();
    }

    private interface Rewrite {
        String apply(Matcher m);
    }

    private static String replace(Pattern p, String text, Rewrite r) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuffer sb = new StringBuffer(text.length() + 16);
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(m)));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
