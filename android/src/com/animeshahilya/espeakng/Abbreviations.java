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

    public static String process(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        text = replace(WORD, text, m -> {
            String full = lookup(WORDS, m.group(1));
            return full + (m.group(2) != null && endsClause(m) ? "." : "");
        });
        text = replace(UNIT, text, m -> {
            for (String[] u : UNITS) {
                if (u[0].equals(m.group(2))) {
                    boolean one = m.group(1).equals("1");
                    return m.group(1) + " " + (one ? u[1] : u[2]);
                }
            }
            return m.group();
        });
        text = replace(MONTH, text, m -> lookup(MONTHS, m.group(1) != null ? m.group(1) : m.group(2)));
        text = replace(NUMBER, text, m -> m.group(1).equals("N") ? "Number " : "number ");
        return text;
    }

    /** A dot at the very end stays, as the pause that ends the text. */
    private static boolean endsClause(Matcher m) {
        return m.end() >= m.regionEnd();
    }

    private static String lookup(String[][] table, String key) {
        for (String[] e : table) {
            if (e[0].equals(key)) {
                return e[1];
            }
        }
        return key;
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
