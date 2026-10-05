package com.animeshahilya.espeakng;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.junit.Test;

/**
 * Every text step on random mixed-script strings (digits, punctuation,
 * emoji, broken surrogates, control characters): none may throw, return
 * null, map an offset outside its input, or split a surrogate pair.
 */
public class TextStepsFuzzTest {
    private static final String[] PIECES = {
            "a", "Z", " ", "  ", ".", ",", "...", "?", "!", "-", "/", ":", ";", "'", "\"", "(", ")", "[", "]",
            "[[", "]]", "$", "₹", "€", "%", "@", "#", "&", "*", "_", "\n", "\t", "0", "1", "9", "12", "1,234",
            "3.14", "12,34,567", "10:30", "1st", "5k", "2cr", "Rs.", "OTP", "PIN", "code", "Dr.", "Prof",
            "vs", "Jan", "kg", "km", "https://x.co/a?b=1", "www.a.com", "user@mail.com", "and", "but",
            "नमस्ते", "है", "आहे", "़", "्", "ं", "।", "॥", "०", "१२", "क्ष", "ळ", "ऱ्", "‍", "‌",
            "مرحبا", "٣", "؟", "Привет", "こんにちは", "漢字", "한국어", "ελλάδα", "שלום", "ไทย",
            "😊", "👍🏽", "🇮🇳", "👨‍👩‍👧", "\uD83D", "\uDE00", "﻿", "\u0001", "\u0000", "‮",
            "ChatGPT", "gpay", "WhatsApp", "e-sim", "upi pin", "Wi-Fi", "iPhone's",
    };

    private static String random(Random r) {
        final StringBuilder sb = new StringBuilder();
        final int n = 1 + r.nextInt(14);
        for (int i = 0; i < n; i++) {
            sb.append(PIECES[r.nextInt(PIECES.length)]);
            if (r.nextInt(3) == 0) sb.append(' ');
        }
        return sb.toString();
    }

    @Test
    public void fuzz() throws Exception {
        final Map<String, Function<String, String>> steps = new LinkedHashMap<>();
        steps.put("readMoney", t -> NumberReading.readMoney(t, true));
        steps.put("readCodes", NumberReading::readCodes);
        steps.put("englishNumbers", t -> NumberReading.englishNumbers(t, true));
        steps.put("abbreviations", Abbreviations::process);
        steps.put("phrasePausesEn", t -> PhrasePauses.process(t, "en"));
        steps.put("phrasePausesHi", t -> PhrasePauses.process(t, "hi"));
        steps.put("earcons", t -> Earcons.mark(t, NvdaSymbolProcessor.LEVEL_SOME, null, "en"));
        steps.put("symbolsAll", t -> NvdaSymbolProcessor.processText(t, NvdaSymbolProcessor.LEVEL_ALL, true));
        steps.put("symbolsSome", t -> NvdaSymbolProcessor.processText(t, NvdaSymbolProcessor.LEVEL_SOME, true));
        steps.put("single", NvdaSymbolProcessor::processSingleSymbol);
        steps.put("techWords", TechWordsNormalizer::process);
        steps.put("indianHi", t -> TextPreprocessor.preprocessIndianText(t, "hi-IN"));
        steps.put("indianMr", t -> TextPreprocessor.preprocessIndianText(t, "mr-IN"));
        steps.put("urls", TextPreprocessor::simplifyUrls);
        steps.put("digits1", t -> TextPreprocessor.formatDigitGrouping(t, VoiceSettings.DIGIT_GROUP_SINGLE_PAUSE, 4));
        steps.put("digits3", t -> TextPreprocessor.formatDigitGrouping(t, VoiceSettings.DIGIT_GROUP_TRIPLE, 4));
        steps.put("watchdog", TextPreprocessor::sanitizeForWatchdog);
        steps.put("surrogates", TextPreprocessor::stripUnpairedSurrogates);
        steps.put("nato", t -> TextPreprocessor.expandNatoSpelling(t, "mr"));
        steps.put("diacritic", t -> TextPreprocessor.expandDevanagariDiacritic(t, "hi"));
        steps.put("repeats", t -> TextPreprocessor.condenseRepeatedCharacters(t, VoiceSettings.REPEATED_CHARS_COUNT));
        steps.put("runsEng", t -> LanguageRuns.split(t, "eng").toString());
        steps.put("runsHin", t -> LanguageRuns.split(t, "hin").toString());
        steps.put("spans", t -> String.valueOf(DevanagariClassifier.classify(t, "hin", "hin")));
        final StringBuilder report = new StringBuilder();
        final Map<String, Integer> fails = new LinkedHashMap<>();
        final Random r = new Random(42);
        for (int i = 0; i < 5000; i++) {
            final String in = random(r);
            for (Map.Entry<String, Function<String, String>> e : steps.entrySet()) {
                try {
                    final String out = e.getValue().apply(in);
                    if (out == null) throw new AssertionError("null");
                    if (!e.getKey().startsWith("runs") && !e.getKey().equals("spans")
                            && TextPreprocessor.stripUnpairedSurrogates(in).equals(in)
                            && !TextPreprocessor.stripUnpairedSurrogates(out).equals(out)) {
                        throw new AssertionError("split a surrogate pair: " + escape(out));
                    }
                    // Offset maps must hold for every length-changing step.
                    if (!e.getKey().startsWith("runs") && !e.getKey().equals("spans") && !out.equals(in)) {
                        final TextOffsetMap m = TextOffsetMap.diff(in, out);
                        for (int k = 0; k <= out.length(); k++) {
                            final int p = m.toPrevious(k);
                            if (p < 0 || p > in.length()) throw new AssertionError("offset " + k + "->" + p);
                        }
                    }
                } catch (Throwable t) {
                    final int n = fails.merge(e.getKey(), 1, Integer::sum);
                    if (n <= 3) {
                        report.append(e.getKey()).append(" | ").append(t).append(" | ")
                                .append(escape(in)).append(" | at ")
                                .append(t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "").append('\n');
                    }
                }
            }
        }
        if (!fails.isEmpty()) {
            throw new AssertionError(fails + " " + report);
        }
    }

    private static String escape(String s) {
        final StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || (c >= 0xD800 && c <= 0xDFFF) || c == 0xFEFF || c == 0x202E || c == 0x200D || c == 0x200C) {
                b.append(String.format("\\u%04X", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
