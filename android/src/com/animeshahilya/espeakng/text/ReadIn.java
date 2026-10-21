package com.animeshahilya.espeakng.text;

/**
 * "Read in" (user dictionary): a matched word is wrapped in private-use
 * marks naming a language, and TtsService gives it a unit of its own read by
 * that language's voice (natural or eSpeak), with its language card. The
 * marks stay in the text until the units are built, so word ranges count
 * them like any other character (like {@link Earcons} markers).
 *
 * <p>Open mark, three letter marks (ISO 639-2), the word, close mark.
 */
public final class ReadIn {
    private ReadIn() {
    }

    private static final char OPEN = '';
    private static final char CLOSE = '';
    private static final char LETTER_A = '';
    /** Open mark plus the language's three letters. */
    public static final int OPEN_LENGTH = 4;

    /** The marks that start text read in {@code language}, or null unless it is three letters a-z. */
    public static String open(String language) {
        if (language == null || language.length() != 3) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(OPEN_LENGTH).append(OPEN);
        for (int i = 0; i < 3; i++) {
            final char c = language.charAt(i);
            if (c < 'a' || c > 'z') {
                return null;
            }
            sb.append((char) (LETTER_A + (c - 'a')));
        }
        return sb.toString();
    }

    public static String close() {
        return String.valueOf(CLOSE);
    }

    public static boolean isOpen(char c) {
        return c == OPEN;
    }

    public static boolean isClose(char c) {
        return c == CLOSE;
    }

    public static boolean isMark(char c) {
        return c == OPEN || c == CLOSE || (c >= LETTER_A && c < LETTER_A + 26);
    }

    /** The language an open mark at {@code i} names, or null if {@code text} has none there. */
    public static String languageAt(String text, int i) {
        if (i < 0 || i + OPEN_LENGTH > text.length() || text.charAt(i) != OPEN) {
            return null;
        }
        final char[] lang = new char[3];
        for (int k = 0; k < 3; k++) {
            final char c = text.charAt(i + 1 + k);
            if (c < LETTER_A || c >= LETTER_A + 26) {
                return null;
            }
            lang[k] = (char) ('a' + (c - LETTER_A));
        }
        return new String(lang);
    }

    /** {@code text} without its marks (direct synthesis, previews). */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (isMark(c)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length()).append(text, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? text : sb.toString();
    }
}
