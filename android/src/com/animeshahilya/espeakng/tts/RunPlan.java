package com.animeshahilya.espeakng.tts;

import com.animeshahilya.espeakng.text.LanguageRuns;
import com.animeshahilya.espeakng.text.TextPreprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Who reads each part of a text: the one routing decision behind both of
 * TtsService's paths (a natural voice speaking the request, or eSpeak with
 * natural voices taking over runs). Each language run goes to its natural
 * voice when one is chosen and already loaded, else to the eSpeak voice that
 * reads that language; neighbouring runs with the same reader are one
 * segment. A lone letter stays with eSpeak (natural voices garble a lone
 * syllable: TalkBack's "क्ष, deleted" with an English UI). A language's card
 * can choose another reader for its runs inside other text
 * ({@link Readers#whenMixed}).
 *
 * <p>Android-free: the voices come in through {@link Readers}, so the rules
 * are tested on the JVM. {@code V} is the eSpeak voice type.
 */
public final class RunPlan {
    private RunPlan() {
    }

    /** What the routing needs to know about the phone's voices. */
    public interface Readers<V> {
        /** The natural voice chosen for this language, or null. */
        String naturalFor(String language);

        boolean isLoaded(String naturalKey);

        /** Called for a chosen voice that is not loaded yet, so a later request finds it ready. */
        void preload(String naturalKey);

        /** The eSpeak voice that reads this language when no natural voice does. */
        V espeakVoiceFor(String language);

        /**
         * Who reads a run of this language inside another language's text
         * (its card, "Voice when inside other text"): {@link #MIXED_DEFAULT},
         * {@link #MIXED_ESPEAK}, {@link #MIXED_SURROUNDING} or a natural
         * voice's key.
         */
        String whenMixed(String language);
    }

    /** The usual rule: the language's natural voice if chosen and loaded, else eSpeak. */
    public static final String MIXED_DEFAULT = "";
    /** Always eSpeak for this language inside other text. */
    public static final String MIXED_ESPEAK = "espeak";
    /** No switch: the voice reading the request's own language reads it too. */
    public static final String MIXED_SURROUNDING = "surrounding";

    /** A part of the text and its reader: {@code naturalKey} when a natural voice reads it, else {@code espeakVoice}. */
    public static final class Segment<V> {
        /** Code-point offset within the planned text. */
        public final int start;
        public final String text;
        public final String naturalKey;
        public final V espeakVoice;

        Segment(int start, String text, String naturalKey, V espeakVoice) {
            this.start = start;
            this.text = text;
            this.naturalKey = naturalKey;
            this.espeakVoice = espeakVoice;
        }

        public boolean isNatural() {
            return naturalKey != null;
        }

        boolean sameReader(String key, V voice) {
            return key != null ? key.equals(naturalKey) : naturalKey == null && java.util.Objects.equals(voice, espeakVoice);
        }
    }

    /**
     * @param runs         the text's language runs, in order
     * @param own          the request's language
     * @param espeakLetters lone letters stay with eSpeak
     * @param mayPreload   a voice of another language may start loading (a
     *                     one-voice phone still loading the request's own
     *                     natural voice must not evict it for another's)
     */
    public static <V> List<Segment<V>> plan(List<LanguageRuns.Run> runs, String own, Readers<V> readers,
                                           boolean espeakLetters, boolean mayPreload) {
        final String ownKey = readers.naturalFor(own);
        final List<Segment<V>> out = new ArrayList<>();
        for (LanguageRuns.Run run : runs) {
            String language = run.language;
            final String chosen;
            if (espeakLetters && isLoneLetter(run.text)) {
                chosen = null;
            } else if (language.equals(own)) {
                chosen = ownKey;
            } else {
                final String mixed = readers.whenMixed(language);
                if (MIXED_SURROUNDING.equals(mixed)) {
                    language = own;
                    chosen = ownKey;
                } else if (MIXED_ESPEAK.equals(mixed)) {
                    chosen = null;
                } else if (!mixed.isEmpty()) {
                    chosen = mixed;
                } else {
                    chosen = readers.naturalFor(language);
                }
            }
            final boolean loaded = chosen != null && readers.isLoaded(chosen);
            if (chosen != null && !loaded && !chosen.equals(ownKey) && mayPreload) {
                readers.preload(chosen);
            }
            final String key = loaded ? chosen : null;
            final V voice = loaded ? null : readers.espeakVoiceFor(language);
            final int last = out.size() - 1;
            if (last >= 0 && out.get(last).sameReader(key, voice)) {
                final Segment<V> prev = out.get(last);
                out.set(last, new Segment<>(prev.start, prev.text + run.text, key, voice));
            } else {
                out.add(new Segment<>(run.start, run.text, key, voice));
            }
        }
        return out;
    }

    /** True when every segment is read by a natural voice. */
    public static <V> boolean allNatural(List<Segment<V>> segments) {
        for (Segment<V> s : segments) {
            if (!s.isNatural()) {
                return false;
            }
        }
        return true;
    }

    /**
     * One character, ignoring the punctuation and spaces around it: what a
     * run holds when TalkBack says "क्ष, deleted" with an English UI.
     */
    static boolean isLoneLetter(String run) {
        if (run == null || run.isEmpty()) return false;
        int start = 0;
        int end = run.length();
        while (start < end) {
            final int cp = run.codePointAt(start);
            if (isLetterOrMark(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            final int cp = run.codePointBefore(end);
            if (isLetterOrMark(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return start < end && TextPreprocessor.isSingleCharacter(run.substring(start, end));
    }

    private static boolean isLetterOrMark(int cp) {
        final int type = Character.getType(cp);
        return Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }
}
