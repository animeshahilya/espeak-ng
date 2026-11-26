package com.animeshahilya.espeakng.piper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * tiny-voca (n4ze3m/tiny-voca, Apache-2.0): a 4.5M-parameter English voice
 * model read from this app's eSpeak NG IPA. Its phonemes are eSpeak-style
 * American IPA (ɾ ʔ ɚ ɐ ᵻ n̩ ɑːɹ ...) with stress as a second input on each
 * vowel (0 none, 1 primary, 2 secondary); eSpeak writes ˈ and ˌ before the
 * syllable, so the mark goes to the next vowel. The voice's config lists the
 * phonemes as its phoneme_id_map, and each id carries its stress in the bits
 * above {@link #STRESS_SHIFT} until {@link PiperModel} splits the two inputs.
 */
final class TinyVoca {
    static final int STRESS_SHIFT = 16;
    /** The model's own speed limit; libsonic does the rest (see PiperEngine.maxModelSpeed). */
    static final float MAX_SPEED = 1.25f;
    private static final long ID_MASK = (1L << STRESS_SHIFT) - 1;
    private static final String WORD_GAP = "<sp>";
    private static final String END_MARKS = ",.?!;:";
    /**
     * Speed factors that give tiny-voca the Piper voices' pace (user's call):
     * it keeps the asked speed exactly (speed x length constant, 121 s on the
     * scratch/voice-eval tinyvoca_pace.py set), while a Piper model's lengths
     * bottom out at a frame per phoneme, so Amy and HFC fall short of it the
     * faster they go (1.8 asked, ~1.58 made). Their mean, per Piper model
     * speed (1.0 ... {@link PiperEngine#MAX_MODEL_SPEED}; beyond it libsonic
     * stretches both alike); tiny-voca at 1.0 is already 14% quicker.
     */
    private static final float[] PACE_AT = {1.0f, 1.1f, 1.2f, 1.3f, 1.6f, 1.8f};
    private static final float[] PACE = {0.876f, 0.833f, 0.808f, 0.801f, 0.796f, 0.772f};

    /** The speed that makes tiny-voca as fast as a Piper voice asked for {@code speed}. */
    static float pace(float speed) {
        final float m = Math.max(PACE_AT[0], Math.min(PACE_AT[PACE_AT.length - 1], speed));
        int i = 1;
        while (i < PACE_AT.length - 1 && m > PACE_AT[i]) {
            i++;
        }
        final float t = (m - PACE_AT[i - 1]) / (PACE_AT[i] - PACE_AT[i - 1]);
        return speed * (PACE[i - 1] + t * (PACE[i] - PACE[i - 1]));
    }

    /** eSpeak symbols the voice lacks -> its nearest phoneme (see {@link #ids}). */
    private static final java.util.Map<Character, String> FALLBACK = new java.util.HashMap<>();
    static {
        FALLBACK.put('r', "ɹ");
        FALLBACK.put('x', "k");
        FALLBACK.put('ç', "k");
        FALLBACK.put('ɬ', "l");
        FALLBACK.put('ʲ', "j");
        FALLBACK.put('ɑ', "ɑː");
        FALLBACK.put('e', "eɪ");
        FALLBACK.put('u', "uː");
    }

    private TinyVoca() {
    }

    /** The voice's phonemes, longest first, for greedy matching. */
    private static List<String> keys(PiperVoiceConfig config) {
        final List<String> keys = new ArrayList<>();
        for (String k : config.phonemeIdMap.keySet()) {
            if (!k.startsWith("<") && !PiperVoiceConfig.PAD.equals(k) && !PiperVoiceConfig.BOS.equals(k)
                    && !PiperVoiceConfig.EOS.equals(k)) {
                keys.add(k);
            }
        }
        Collections.sort(keys, (a, b) -> b.length() - a.length());
        return keys;
    }

    private static boolean isVowel(String phoneme) {
        if ("n̩".equals(phoneme)) {
            return true; // syllabic n carries stress like a vowel
        }
        for (int i = 0; i < phoneme.length(); i++) {
            if ("aeiouæɐɑɒɔəɚɛɜɪʊʌᵻ".indexOf(phoneme.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A chunk's eSpeak IPA as packed ids: start, phonemes with a word gap
     * between words, a closing mark (without one the model cuts the last word
     * short), end.
     *
     * @param wordStarts if not null, receives the id index where each word starts
     */
    static long[] ids(String ipa, PiperVoiceConfig config, List<Integer> wordStarts) {
        final Map<String, int[]> map = config.phonemeIdMap;
        final List<String> keys = keys(config);
        final int gap = map.get(WORD_GAP)[0];
        final List<Long> out = new ArrayList<>();
        out.add((long) map.get(PiperVoiceConfig.BOS)[0]);
        int pending = 0;
        boolean inWord = false;
        final String s = trained(ipa.trim(), config);
        final Map<Character, String> fallback = config.tinyVocaFallback != null ? config.tinyVocaFallback : FALLBACK;
        for (int i = 0; i < s.length(); ) {
            final char c = s.charAt(i);
            if (c == 'ˈ' || c == 'ˌ') {
                pending = !config.tinyVocaStress ? 0 : c == 'ˈ' ? 1 : 2;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add((long) gap);
                }
                inWord = false;
                i++;
                continue;
            }
            String match = null;
            int consumed = 0;
            for (String k : keys) {
                if (s.startsWith(k, i)) {
                    match = k;
                    consumed = k.length();
                    break;
                }
            }
            if (match == null) {
                // eSpeak's en-us also writes a few sounds the voice has no token
                // for (en_list, 4463 words: Kyrgyz r, Ahmed x, Utrecht ç, blanc ɑ̃,
                // Bologna nʲ...): the nearest American one rather than nothing.
                // Others (a lone length mark, the nasal tilde) are dropped.
                final String near = fallback.get(c);
                if (near == null || !map.containsKey(near)) {
                    i++;
                    continue;
                }
                match = near;
                consumed = 1;
            }
            final boolean mark = END_MARKS.indexOf(match.charAt(0)) >= 0 && match.length() == 1;
            if (!mark && !inWord && wordStarts != null) {
                wordStarts.add(out.size());
            }
            inWord = !mark || inWord;
            int stress = 0;
            if (pending != 0 && isVowel(match)) {
                stress = pending;
                pending = 0;
            }
            out.add(map.get(match)[0] | ((long) stress << STRESS_SHIFT));
            i += consumed;
        }
        if (out.get(out.size() - 1) == gap) {
            out.remove(out.size() - 1);
        }
        final long last = out.get(out.size() - 1) & ID_MASK;
        boolean closed = false;
        for (int j = 0; j < END_MARKS.length(); j++) {
            final int[] id = map.get(String.valueOf(END_MARKS.charAt(j)));
            closed |= id != null && id[0] == last;
        }
        if (!closed) {
            out.add((long) map.get(".")[0]);
        }
        out.add((long) map.get(PiperVoiceConfig.EOS)[0]);
        final long[] ids = new long[out.size()];
        for (int j = 0; j < ids.length; j++) {
            ids[j] = out.get(j);
        }
        return ids;
    }

    /**
     * eSpeak IPA in the phonemes a fine-tuned voice learned
     * ({@link PiperVoiceConfig#tinyVocaReplace}, longest first at each place).
     */
    static String trained(String ipa, PiperVoiceConfig config) {
        final List<String[]> replace = config.tinyVocaReplace;
        if (replace.isEmpty()) {
            return ipa;
        }
        final StringBuilder out = new StringBuilder(ipa.length());
        outer:
        for (int i = 0; i < ipa.length(); ) {
            for (String[] r : replace) {
                if (ipa.startsWith(r[0], i)) {
                    out.append(r[1]);
                    i += r[0].length();
                    continue outer;
                }
            }
            out.append(ipa.charAt(i++));
        }
        return out.toString();
    }

    /** The model's token ids from packed ids. */
    static long[] tokens(long[] ids) {
        final long[] t = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            t[i] = ids[i] & ID_MASK;
        }
        return t;
    }

    /** The model's stress values from packed ids. */
    static long[] stress(long[] ids) {
        final long[] t = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            t[i] = ids[i] >>> STRESS_SHIFT;
        }
        return t;
    }
}
