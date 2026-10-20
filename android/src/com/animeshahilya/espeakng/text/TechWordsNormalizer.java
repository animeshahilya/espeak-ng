/*
 * Copyright (C) 2026 eSpeak NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal text normalizer for technology brand names, compound app names,
 * and tech acronyms. Ensures natural voices (Piper, SYSPIN, Rasa, Kokoro, eSpeak)
 * pronounce ubiquitous terms (e.g. "ChatGPT", "GPay", "Gmail", "WhatsApp", "YouTube",
 * "UPI", "OTP", "WiFi", "PayTM", "PhonePe", "FASTag") accurately and fluently across
 * English and multilingual utterances.
 *
 * <p>One table says how each term may be written (case-insensitive unless
 * marked {@code (?-i:...)}), how it is said, and what its plural adds; the
 * matching pattern is built from it, so the two cannot drift apart. Earlier
 * rows win where two could match at one place.
 */
public final class TechWordsNormalizer {

    private TechWordsNormalizer() {
    }

    /** The spoken form of the matched term. */
    private interface Say {
        String of(String raw);
    }

    private static final class Term {
        final String regex;
        final Say say;
        /** Added for a plural "s" ("" swallows it), or null when the term takes none. */
        final String plural;
        /** Whether "'s" may follow (said as "'s"). */
        final boolean possessive;
        int group;

        Term(String regex, Say say, String plural, boolean possessive) {
            this.regex = regex;
            this.say = say;
            this.plural = plural;
            this.possessive = possessive;
        }
    }

    private static Term brand(String regex, String spoken) {
        return new Term(regex, raw -> spoken, null, true);
    }

    private static Term brands(String regex, String spoken, String plural) {
        return new Term(regex, raw -> spoken, plural, true);
    }

    private static Term word(String regex, String spoken) {
        return new Term(regex, raw -> spoken, null, false);
    }

    private static Term words(String regex, String spoken, String plural) {
        return new Term(regex, raw -> spoken, plural, false);
    }

    /** Said letter by letter as written: "kyc" -> "K Y C", "4g" -> "4 G", "ci/cd" -> "C I C D". */
    private static Term letters(String regex) {
        return new Term(regex, TechWordsNormalizer::spell, null, false);
    }

    private static final Term[] TERMS = {
            brand("chat[ -]?gpt", "Chat G P T"),
            brand("g[ -]?pay", "G-Pay"),
            brands("g[ -]?mail", "G-mail", "s"),
            brands("whats[ -]?app", "Whats-App", ""),
            brands("you[ -]?tube", "You-Tube", ""),
            brands("git[ -]?hub", "Git-Hub", ""),
            brands("git[ -]?lab", "Git-Lab", ""),
            brand("linked[ -]?in", "Linked-In"),
            brands("face[ -]?book", "Facebook", ""),
            brands("tik[ -]?tok", "Tik-Tok", ""),
            brands("snap[ -]?chat", "Snap-Chat", ""),
            brand("pay[ -]?tm", "Pay T M"),
            brand("phone[ -]?pe", "Phone Pay"),
            // Natural voices say "Samato" and "Sweetie" for the plain names.
            brand("zomato", "Zo-mato"),
            brand("swiggy", "Swig-ee"),
            brands("(?:fastag|fast[ -]?tag)", "Fast-Tag", "s"),
            word("wi[ -]?fi", "Wi-Fi"),
            words("qr[ -]?code", "Q R code", "s"),
            word("scan[ -]?qr", "scan Q R"),
            words("upi[ -]?pin", "U P I PIN", "s"),
            words("atm[ -]?pin", "A-T M PIN", "s"),
            words("pin[ -]code", "PIN code", "s"),
            words("user[ -]?id", "user I D", "s"),
            words("email[ -]?id", "email I D", "s"),
            words("voter[ -]?id", "voter I D", "s"),
            words("caller[ -]?id", "caller I D", "s"),
            words("upi[ -]?id", "U P I I D", "s"),
            words("face[ -]?id", "face I D", "s"),
            words("touch[ -]?id", "touch I D", "s"),
            words("govt?[ -]?id", "govt I D", "s"),
            words("id[ -]card", "I D card", "s"),
            brand("upi", "U P I"),
            // Plurals keep their "'s": "O T Ps" reads "P S".
            brands("otp", "O T P", "'s"),
            brands("faq", "F A-Q", "'s"),
            brands("pdf", "P D F", "'s"),
            letters("sms"),
            brands("api", "A-P I", "'s"),
            brands("url", "U R L", "'s"),
            brands("e[ -]?sim", "e SIM", "s"),
            brand("m[ -]?pin", "M PIN"),
            letters("neft"),
            letters("rtgs"),
            letters("(?-i:IMPS)"), // lower case is the English word "imps"
            // A lone "A" is the article to eSpeak, so it is joined to the next letter.
            brands("atm", "A-T M", "'s"),
            letters("kyc"),
            letters("html"),
            letters("gps"),
            letters("usb"),
            letters("nfc"),
            letters("vpn"),
            letters("cpu"),
            letters("gpu"),
            brand("open[ -]?ai", "Open-AI"),
            brand("deep[ -]?seek", "Deep-Seek"),
            brand("mid[ -]?journey", "Mid-Journey"),
            brand("gen[ -]?ai", "Gen-AI"),
            brands("iphone", "i-Phone", "s"),
            brands("ipad", "i-Pad", "s"),
            brands("imac", "i-Mac", "s"),
            brands("ipod", "i-Pod", "s"),
            brand("iwatch", "i-Watch"),
            word("ios", "i O S"),
            word("mac[ -]?os", "Mac O S"),
            // GPT, GPT-4, gpt4o, GPT-3.5: the version stays as written.
            new Term("gpt(?:-?[0-9]+(?:\\.[0-9]+)?[a-z]*)?", raw -> raw.length() == 3 ? "G P T"
                    : "G P T" + (raw.charAt(3) == '-' ? "" : "-") + raw.substring(3), null, false),
            brand("co[ -]?pilot", "Co-pilot"),
            // Capitalized only: lower case they are English words
            // ("the perplexity of", "a mistral wind").
            brand("(?-i:Perplexity)", "Per-plexity"),
            brand("(?-i:Anthropic)", "An-thropic"),
            brand("(?-i:Mistral)", "Mis-tral"),
            brand("ollama", "O-llama"),
            brand("hugging[ -]?face", "Hugging-Face"),
            brand("stable[ -]?diffusion", "Stable-Diffusion"),
            word("json", "JAY-son"),
            word("yaml", "yam-el"),
            word("sql", "S-Q-L"),
            word("nosql", "No-S-Q-L"),
            letters("sdk"),
            words("(?-i:IDE)", "I D E", "s"),
            letters("(?-i:CLI)"),
            letters("(?-i:GUI)"),
            letters("ci[ /\\-]?cd"),
            letters("css"),
            letters("png"),
            word("jpe?g", "J-PEG"),
            letters("svg"),
            word("blue[ -]?tooth", "Blue-tooth"),
            letters("hdmi"),
            letters("ssd"),
            word("oled", "O-LED"),
            word("amoled", "Am-O-LED"),
            letters("lcd"),
            words("(?-i:LED)", "L E D", "s"),
            letters("[48]k"),
            letters("[45]g"),
            letters("lte"),
            word("volte", "Vo-L T E"),
            word("vowifi", "Vo-Wi-Fi"),
            brand("insta(?:gram)?", "Insta-gram"),
            brand("telegram", "Tele-gram"),
            brand("reddit", "Red-dit"),
            brand("spotify", "Spoti-fy"),
            brand("netflix", "Net-flix"),
            brand("flipkart", "Flip-kart"),
            brand("meesho", "Mee-sho"),
            brand("blinkit", "Blink-it"),
            brand("zepto", "Zep-to"),
            brand("(?-i:Ola)", "Oh-la"),
            brand("(?-i:Uber)", "Oo-ber"),
    };

    /** Every term as its own group, then its optional plural and possessive groups. */
    private static final Pattern PATTERN;

    static {
        final StringBuilder p = new StringBuilder("(?i)\\b(?:");
        int group = 1;
        for (Term t : TERMS) {
            if (group > 1) {
                p.append('|');
            }
            t.group = group++;
            p.append('(').append(t.regex).append(')');
            if (t.plural != null) {
                p.append("(s)?");
                group++;
            }
            if (t.possessive) {
                p.append("(['’]s)?");
                group++;
            }
        }
        PATTERN = Pattern.compile(p.append(")\\b").toString());
    }

    /**
     * Normalizes tech brand names, acronyms, and compound words into clear,
     * phonetically pronounceable forms for natural and synthesised voices.
     *
     * @param text The input text to normalize.
     * @return The normalized text, or the original string if unchanged.
     */
    public static String process(String text) {
        if (text == null || text.isEmpty() || !AsciiUtils.hasAsciiLetter(text)) {
            return text;
        }
        return AsciiUtils.replaceMatches(PATTERN, text, TechWordsNormalizer::say);
    }

    private static String say(Matcher m) {
        for (Term t : TERMS) {
            final String raw = m.group(t.group);
            if (raw == null) {
                continue;
            }
            int g = t.group + 1;
            final boolean plural = t.plural != null && m.group(g++) != null;
            final boolean possessive = t.possessive && m.group(g) != null;
            return t.say.of(raw) + (plural ? t.plural : "") + (possessive ? "'s" : "");
        }
        return m.group();
    }

    private static String spell(String raw) {
        final StringBuilder out = new StringBuilder(raw.length() * 2);
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(c));
            }
        }
        return out.toString();
    }
}
