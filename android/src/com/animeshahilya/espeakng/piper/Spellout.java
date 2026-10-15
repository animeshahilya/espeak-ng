package com.animeshahilya.espeakng.piper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole numbers as words from CLDR spell-out rules (data/numbers/), for the
 * natural voices that read letters. Android's ICU keeps RuleBasedNumberFormat
 * out of its public API and has rules for few Indian languages, so this reads
 * the subset of ICU's rule syntax CLDR's rules use for non-negative integers:
 * base values (with "/radix"), {@code << >> ==} substitutions with or without
 * a rule set, and optional {@code [text|alternate]} parts, split into two
 * rules and rolled back on exact multiples as ICU does. Special rules
 * (-x, x.x, Inf) are skipped. NumberWordsTest checks it against ICU4J.
 */
final class Spellout {
    private static final class Rule {
        final long base;
        final long divisor;
        final String text;

        Rule(long base, long radix, String text) {
            this.base = base;
            long d = 1;
            while (base > 0 && d <= base / radix) {
                d *= radix;
            }
            this.divisor = d;
            this.text = text;
        }

        boolean hasModulus() {
            return text.indexOf('>') >= 0;
        }
    }

    private final Map<String, List<Rule>> sets = new HashMap<>();

    Spellout(String description) {
        List<Rule> current = null;
        for (String chunk : description.split(";")) {
            String s = chunk.trim();
            if (s.startsWith("%")) {
                final int colon = s.indexOf(':');
                current = new ArrayList<>();
                sets.put(s.substring(0, colon), current);
                s = s.substring(colon + 1).trim();
            }
            final int colon = s.indexOf(':');
            if (current == null || colon <= 0) {
                continue;
            }
            final String key = s.substring(0, colon);
            final String text = s.substring(colon + 1).trim();
            final int slash = key.indexOf('/');
            final long base;
            final long radix;
            try {
                base = Long.parseLong(slash < 0 ? key : key.substring(0, slash));
                radix = slash < 0 ? 10 : Long.parseLong(key.substring(slash + 1));
            } catch (NumberFormatException e) {
                continue; // -x, x.x, Inf, NaN
            }
            final int open = text.indexOf('[');
            final int close = text.indexOf(']');
            if (open < 0 || close < open || base == 0) {
                current.add(new Rule(base, radix, text));
                continue;
            }
            // ICU: the exact base value reads without the optional part (or
            // with its alternate); the rule one above it reads with it.
            final String inside = text.substring(open + 1, close);
            final int bar = inside.indexOf('|');
            final String head = text.substring(0, open);
            final String tail = text.substring(close + 1);
            current.add(new Rule(base, radix, head + (bar < 0 ? "" : inside.substring(bar + 1)) + tail));
            current.add(new Rule(base + 1, radix, head + (bar < 0 ? inside : inside.substring(0, bar)) + tail));
        }
    }

    /** The number in words by the named public rule set; null if it is missing. */
    String format(long n, String set) {
        final List<Rule> rules = sets.get(set);
        return rules == null || n < 0 ? null : format(n, rules);
    }

    private String format(long n, List<Rule> rules) {
        int i = rules.size() - 1;
        while (i > 0 && rules.get(i).base > n) {
            i--;
        }
        Rule rule = rules.get(i);
        if (i > 0 && rule.hasModulus() && n % rule.divisor == 0 && rule.base % rule.divisor != 0) {
            rule = rules.get(i - 1);
        }
        final String t = rule.text;
        final StringBuilder out = new StringBuilder();
        for (int p = 0; p < t.length(); p++) {
            final char c = t.charAt(p);
            if (c != '<' && c != '>' && c != '=') {
                out.append(c);
                continue;
            }
            final int end = t.indexOf(c, p + 1);
            final String name = t.substring(p + 1, end);
            p = end;
            final long value = c == '<' ? n / rule.divisor : c == '>' ? n % rule.divisor : n;
            if (name.startsWith("%")) {
                out.append(format(value, sets.get(name)));
            } else if (name.isEmpty()) {
                out.append(format(value, rules));
            } else {
                out.append(value); // a decimal pattern (=#,##,##0=): beyond the words
            }
        }
        return out.toString();
    }
}
