package com.animeshahilya.espeakng.text;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.Voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in: splits written-together English compounds ("aftermath" -&gt; "after
 * math") so each half keeps its full vowels instead of eSpeak's compound
 * reduction. Looked up whole-word, case-insensitively; words glued to
 * digits or symbols ("abc123", "user_name", paths) and ALL-CAPS acronyms
 * are left alone, like {@link HinglishReader}.
 *
 * <p>Measured on a Pixel 8 (eSpeak en-us, Parakeet judge, 48 compounds in
 * carrier sentences, reproduced on a fresh noise trajectory): blanket
 * splitting is net-zero (6 wins, 5 regressions), so entries that split
 * worse than eSpeak reads them whole (cowboy, pitchman, undergarment)
 * are pruned from the table rather than applied globally. Full harness
 * and report in scratch/voice-eval (compound_ab.py, CompoundVoiceCheck).
 *
 * <p>The table (assets/compounds/en.tsv) is vendored from TGSpeechBox
 * packs/dict/en-compounds.tsv (MIT); see its header and the README license
 * section. Loads lazily on first use, so installs with the setting off
 * never pay for it.
 */
public final class CompoundSplitter {
    private static final String TAG = "CompoundSplitter";
    private static final String FILE = "compounds/en.tsv";

    private static volatile AssetManager sAssets;
    private static volatile Map<String, String> sSplits;

    private CompoundSplitter() {
    }

    /** Called once from {@link EspeakApp#onCreate()}. */
    public static void init(Context context) {
        sAssets = context.getApplicationContext().getAssets();
    }

    /** Warm up the compound table in background. */
    public static void warmup() {
        load();
    }

    /** Loads the table on first use; empty when assets are unavailable. */
    private static Map<String, String> load() {
        Map<String, String> splits = sSplits;
        if (splits != null) {
            return splits;
        }
        synchronized (CompoundSplitter.class) {
            splits = sSplits;
            if (splits != null) {
                return splits;
            }
            splits = Collections.emptyMap();
            if (sAssets != null) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        sAssets.open(FILE), StandardCharsets.UTF_8))) {
                    final Map<String, String> table = new HashMap<>(4096);
                    parseEntries(in, table);
                    splits = table;
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Compound table unavailable", e);
                }
            }
            sSplits = splits;
            return splits;
        }
    }

    /** Parses "compound&lt;TAB&gt;halves" lines, skipping comments and malformed rows. */
    public static void parseEntries(BufferedReader in, Map<String, String> table) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            final int tab = line.indexOf('\t');
            if (tab <= 0 || tab + 1 >= line.length()) {
                continue;
            }
            final String compound = AsciiUtils.toAsciiLowerCase(line.substring(0, tab).trim());
            final String halves = line.substring(tab + 1).trim();
            if (!compound.isEmpty() && !halves.isEmpty() && !table.containsKey(compound)) {
                table.put(compound, halves);
            }
        }
    }

    public static String process(String text) {
        if (text == null || text.isEmpty() || !AsciiUtils.hasAsciiLetter(text)) {
            return text;
        }
        return process(text, load());
    }

    /** Test seam: pure-Java rewrite against an explicit table (no assets needed). */
    public static String process(String text, Map<String, String> splits) {
        if (text == null || text.isEmpty() || splits == null || splits.isEmpty()) {
            return text;
        }
        StringBuilder out = null; // lazy: same instance back when nothing matches
        final int n = text.length();
        int i = 0;
        while (i < n) {
            final char c = text.charAt(i);
            if (!AsciiUtils.isAsciiLetter(c)) {
                if (out != null) {
                    out.append(c);
                }
                i++;
                continue;
            }
            int j = i;
            while (j < n && AsciiUtils.isAsciiLetter(text.charAt(j))) {
                j++;
            }
            final String word = text.substring(i, j);
            String split = null;
            if (!HinglishReader.isAcronym(text, i, j)
                    && !HinglishReader.touchesDigitOrSymbol(text, i, j)) {
                split = splits.get(AsciiUtils.toAsciiLowerCase(word));
            }
            if (split != null) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16);
                    out.append(text, 0, i);
                }
                // "Afternoon" -> "After noon" (ALL-CAPS already skipped as acronyms).
                if (word.length() > 1 && Character.isUpperCase(word.charAt(0))
                        && Character.isLowerCase(word.charAt(1))) {
                    split = Character.toUpperCase(split.charAt(0)) + split.substring(1);
                }
                out.append(split);
            } else if (out != null) {
                out.append(word);
            }
            i = j;
        }
        return out != null ? out.toString() : text;
    }
}


