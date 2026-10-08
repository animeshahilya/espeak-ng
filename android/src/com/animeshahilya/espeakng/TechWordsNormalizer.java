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

package com.animeshahilya.espeakng;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal text normalizer for technology brand names, compound app names,
 * and tech acronyms. Ensures natural voices (Piper, SYSPIN, Rasa, Kokoro, eSpeak)
 * pronounce ubiquitous terms (e.g. "ChatGPT", "GPay", "Gmail", "WhatsApp", "YouTube",
 * "UPI", "OTP", "WiFi", "PayTM", "PhonePe", "FASTag") accurately and fluently across
 * English and multilingual utterances.
 */
public final class TechWordsNormalizer {

    private TechWordsNormalizer() {
    }

    // Consolidated regex pattern covering common tech brands, compound words,
    // and acronyms. Uses (?i) for case-insensitivity and word boundaries.
    private static final Pattern TECH_WORDS_PATTERN = Pattern.compile(
            "(?i)\\b(?:"
                    + "chat[ -]?gpt(?:'s)?"
                    + "|g[ -]?pay(?:'s)?"
                    + "|g[ -]?mails?(?:'s)?"
                    + "|whats[ -]?apps?(?:'s)?"
                    + "|you[ -]?tubes?(?:'s)?"
                    + "|git[ -]?hubs?(?:'s)?"
                    + "|git[ -]?labs?(?:'s)?"
                    + "|linked[ -]?in(?:'s)?"
                    + "|face[ -]?books?(?:'s)?"
                    + "|tik[ -]?toks?(?:'s)?"
                    + "|snap[ -]?chats?(?:'s)?"
                    + "|pay[ -]?tm(?:'s)?"
                    + "|phone[ -]?pe(?:'s)?"
                    + "|zomato(?:'s)?"
                    + "|swiggy(?:'s)?"
                    + "|(?:fastag|fast[ -]?tag)s?(?:'s)?"
                    + "|wi[ -]?fi"
                    + "|qr[ -]?codes?|scan[ -]?qr"
                    + "|(?:upi|atm)[ -]?pins?|pin[ -]codes?"
                    + "|(?:user|email|voter|caller|upi|face|touch|gov|govt)[ -]?id"
                    + "|id[ -]cards?"
                    + "|upi(?:'s)?"
                    + "|otps?(?:'s)?"
                    + "|faqs?(?:'s)?"
                    + "|pdfs?(?:'s)?"
                    + "|sms"
                    + "|apis?(?:'s)?"
                    + "|urls?(?:'s)?"
                    + "|e[ -]?sims?(?:'s)?"
                    + "|m[ -]?pin(?:'s)?"
                    + "|neft"
                    + "|rtgs"
                    + "|(?-i:IMPS)" // lower case is the English word "imps"
                    + "|atm(?:'s)?"
                    + "|kyc"
                    + "|html"
                    + "|gps"
                    + "|usb"
                    + "|nfc"
                    + "|vpn"
                    + "|cpu"
                    + "|gpu"
                    + "|open[ -]?ai(?:'s)?"
                    + "|deep[ -]?seek(?:'s)?"
                    + "|mid[ -]?journey(?:'s)?"
                    + "|gen[ -]?ai(?:'s)?"
                    + "|iphones?(?:'s)?"
                    + "|ipads?(?:'s)?"
                    + "|imacs?(?:'s)?"
                    + "|ipods?(?:'s)?"
                    + "|iwatch(?:'s)?"
                    + "|ios"
                    + "|mac[ -]?os"
                    + "|gpt(?:-?[0-9]+(?:\\.[0-9]+)?[a-z]*)?"
                    + ")\\b"
    );

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

        final Matcher matcher = TECH_WORDS_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        final StringBuffer sb = new StringBuffer(text.length() + 32);
        do {
            final String match = matcher.group();
            final String replacement = normalizeTerm(match);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String normalizeTerm(String raw) {
        final String lower = raw.toLowerCase(Locale.ROOT);
        final boolean possessive = lower.endsWith("'s");
        final String apostropheS = possessive ? "'s" : "";

        // Compound QR, MPIN, PIN, ID
        if (lower.contains("qr")) {
            if (lower.contains("code")) return "Q R code" + apostropheS;
            if (lower.contains("scan")) return "scan Q R" + apostropheS;
            return "Q R" + apostropheS;
        }
        if (lower.startsWith("mpin") || lower.startsWith("m-pin") || lower.startsWith("m pin")) {
            return "M PIN" + apostropheS;
        }
        if (lower.endsWith(" pin") || lower.endsWith("-pin") || lower.startsWith("pin ") || lower.startsWith("pin-") || lower.equals("pin")) {
            if (lower.contains("upi")) return "U P I PIN" + apostropheS;
            if (lower.contains("atm")) return "A-T M PIN" + apostropheS;
            if (lower.contains("code")) return "PIN code" + apostropheS;
            return "PIN" + apostropheS;
        }
        if (lower.endsWith(" id") || lower.endsWith("-id") || lower.startsWith("id ") || lower.startsWith("id-") || lower.equals("id")) {
            if (lower.contains("card")) return "I D card" + apostropheS;
            if (lower.contains("user")) return "user I D" + apostropheS;
            if (lower.contains("email")) return "email I D" + apostropheS;
            if (lower.contains("voter")) return "voter I D" + apostropheS;
            if (lower.contains("caller")) return "caller I D" + apostropheS;
            if (lower.contains("upi")) return "U P I I D" + apostropheS;
            if (lower.contains("face")) return "face I D" + apostropheS;
            if (lower.contains("touch")) return "touch I D" + apostropheS;
            if (lower.contains("gov")) return "govt I D" + apostropheS;
            return "I D" + apostropheS;
        }

        // ChatGPT & GPT variations
        if (lower.startsWith("chat")) {
            return "Chat G P T" + apostropheS;
        }
        if (lower.startsWith("gpt")) {
            final String suffix = raw.substring(3); // e.g. "-4", "4", "-3.5"
            if (suffix.isEmpty()) {
                return "G P T";
            }
            if (suffix.startsWith("-")) {
                return "G P T" + suffix;
            }
            return "G P T-" + suffix;
        }

        // Payments & Banking apps
        if (lower.startsWith("g") && lower.contains("pay")) {
            return "G-Pay" + apostropheS;
        }
        if (lower.startsWith("pay") && lower.contains("tm")) {
            return "Pay T M" + apostropheS;
        }
        if (lower.startsWith("phone") && lower.contains("pe")) {
            return "Phone Pay" + apostropheS;
        }
        if (lower.startsWith("fast") && (lower.contains("tag") || lower.contains("ag"))) {
            final boolean plural = lower.contains("tags") || lower.contains("ags");
            return "Fast-Tag" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.equals("upi") || lower.equals("upi's")) {
            return "U P I" + apostropheS;
        }
        if (lower.contains("mpin") || lower.contains("m-pin") || lower.contains("m pin")) {
            return "M PIN" + apostropheS;
        }
        if (lower.equals("neft")) {
            return "N E F T";
        }
        if (lower.equals("rtgs")) {
            return "R T G S";
        }
        if (raw.equals("IMPS")) {
            return "I M P S";
        }

        // Natural voices say "Samato" and "Sweetie" for the plain names.
        if (lower.startsWith("zomato")) {
            return "Zo-mato" + apostropheS;
        }
        if (lower.startsWith("swiggy")) {
            return "Swig-ee" + apostropheS;
        }

        // Google & Messaging brands
        if (lower.startsWith("g") && lower.contains("mail")) {
            final boolean plural = lower.contains("mails");
            return "G-mail" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.startsWith("whats")) {
            return "Whats-App" + apostropheS;
        }
        if (lower.startsWith("you")) {
            return "You-Tube" + apostropheS;
        }
        if (lower.startsWith("git")) {
            if (lower.contains("hub")) return "Git-Hub" + apostropheS;
            if (lower.contains("lab")) return "Git-Lab" + apostropheS;
        }
        if (lower.startsWith("linked")) {
            return "Linked-In" + apostropheS;
        }
        if (lower.startsWith("face")) {
            return "Facebook" + apostropheS;
        }
        if (lower.startsWith("tik")) {
            return "Tik-Tok" + apostropheS;
        }
        if (lower.startsWith("snap")) {
            return "Snap-Chat" + apostropheS;
        }

        // Apple & OS
        if (lower.startsWith("iphone")) {
            final boolean plural = lower.contains("iphones");
            return "i-Phone" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.startsWith("ipad")) {
            final boolean plural = lower.contains("ipads");
            return "i-Pad" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.startsWith("imac")) {
            final boolean plural = lower.contains("imacs");
            return "i-Mac" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.startsWith("ipod")) {
            final boolean plural = lower.contains("ipods");
            return "i-Pod" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.startsWith("iwatch")) {
            return "i-Watch" + apostropheS;
        }
        if (lower.equals("ios")) {
            return "i O S";
        }
        if (lower.contains("macos") || lower.contains("mac os") || lower.contains("mac-os")) {
            return "Mac O S";
        }

        // AI Companies
        if (lower.contains("openai") || lower.contains("open ai") || lower.contains("open-ai")) {
            return "Open-AI" + apostropheS;
        }
        if (lower.contains("deepseek") || lower.contains("deep seek") || lower.contains("deep-seek")) {
            return "Deep-Seek" + apostropheS;
        }
        if (lower.contains("midjourney") || lower.contains("mid journey") || lower.contains("mid-journey")) {
            return "Mid-Journey" + apostropheS;
        }
        if (lower.contains("genai") || lower.contains("gen ai") || lower.contains("gen-ai")) {
            return "Gen-AI" + apostropheS;
        }

        // Connectivity & Acronyms
        if (lower.contains("wi") && lower.contains("fi")) {
            return "Wi-Fi";
        }
        if (lower.startsWith("otp")) {
            final boolean plural = lower.contains("otps");
            return "O T P" + (plural ? "'s" : "") + apostropheS;
        }
        if (lower.startsWith("faq")) {
            final boolean plural = lower.contains("faqs");
            return "F A-Q" + (plural ? "'s" : "") + apostropheS;
        }
        if (lower.startsWith("pdf")) {
            final boolean plural = lower.contains("pdfs");
            return "P D F" + (plural ? "'s" : "") + apostropheS;
        }
        if (lower.equals("sms")) {
            return "S M S";
        }
        if (lower.startsWith("api")) {
            final boolean plural = lower.contains("apis");
            return "A-P I" + (plural ? "'s" : "") + apostropheS;
        }
        if (lower.startsWith("url")) {
            final boolean plural = lower.contains("urls");
            return "U R L" + (plural ? "'s" : "") + apostropheS;
        }
        if (lower.contains("esim") || lower.contains("e-sim") || lower.contains("e sim")) {
            final boolean plural = lower.contains("sims");
            return "e SIM" + (plural ? "s" : "") + apostropheS;
        }
        if (lower.equals("atm") || lower.equals("atms") || lower.equals("atm's")) {
            return "A-T M" + apostropheS;
        }
        if (lower.equals("kyc")) {
            return "K Y C";
        }
        if (lower.equals("html")) {
            return "H T M L";
        }
        if (lower.equals("gps")) {
            return "G P S";
        }
        if (lower.equals("usb")) {
            return "U S B";
        }
        if (lower.equals("nfc")) {
            return "N F C";
        }
        if (lower.equals("vpn")) {
            return "V P N";
        }
        if (lower.equals("cpu")) {
            return "C P U";
        }
        if (lower.equals("gpu")) {
            return "G P U";
        }

        return raw;
    }
}
