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
                    + "chat[ -]?gpt(?:['\u2019]s)?"
                    + "|g[ -]?pay(?:['\u2019]s)?"
                    + "|g[ -]?mails?(?:['\u2019]s)?"
                    + "|whats[ -]?apps?(?:['\u2019]s)?"
                    + "|you[ -]?tubes?(?:['\u2019]s)?"
                    + "|git[ -]?hubs?(?:['\u2019]s)?"
                    + "|git[ -]?labs?(?:['\u2019]s)?"
                    + "|linked[ -]?in(?:['\u2019]s)?"
                    + "|face[ -]?books?(?:['\u2019]s)?"
                    + "|tik[ -]?toks?(?:['\u2019]s)?"
                    + "|snap[ -]?chats?(?:['\u2019]s)?"
                    + "|pay[ -]?tm(?:['\u2019]s)?"
                    + "|phone[ -]?pe(?:['\u2019]s)?"
                    + "|zomato(?:['\u2019]s)?"
                    + "|swiggy(?:['\u2019]s)?"
                    + "|(?:fastag|fast[ -]?tag)s?(?:['\u2019]s)?"
                    + "|wi[ -]?fi"
                    + "|qr[ -]?codes?|scan[ -]?qr"
                    + "|(?:upi|atm)[ -]?pins?|pin[ -]codes?"
                    + "|(?:user|email|voter|caller|upi|face|touch|gov|govt)[ -]?ids?"
                    + "|id[ -]cards?"
                    + "|upi(?:['\u2019]s)?"
                    + "|otps?(?:['\u2019]s)?"
                    + "|faqs?(?:['\u2019]s)?"
                    + "|pdfs?(?:['\u2019]s)?"
                    + "|sms"
                    + "|apis?(?:['\u2019]s)?"
                    + "|urls?(?:['\u2019]s)?"
                    + "|e[ -]?sims?(?:['\u2019]s)?"
                    + "|m[ -]?pin(?:['\u2019]s)?"
                    + "|neft"
                    + "|rtgs"
                    + "|(?-i:IMPS)" // lower case is the English word "imps"
                    + "|atms?(?:['\u2019]s)?"
                    + "|kyc"
                    + "|html"
                    + "|gps"
                    + "|usb"
                    + "|nfc"
                    + "|vpn"
                    + "|cpu"
                    + "|gpu"
                    + "|open[ -]?ai(?:['\u2019]s)?"
                    + "|deep[ -]?seek(?:['\u2019]s)?"
                    + "|mid[ -]?journey(?:['\u2019]s)?"
                    + "|gen[ -]?ai(?:['\u2019]s)?"
                    + "|iphones?(?:['\u2019]s)?"
                    + "|ipads?(?:['\u2019]s)?"
                    + "|imacs?(?:['\u2019]s)?"
                    + "|ipods?(?:['\u2019]s)?"
                    + "|iwatch(?:['\u2019]s)?"
                    + "|ios"
                    + "|mac[ -]?os"
                    + "|gpt(?:-?[0-9]+(?:\\.[0-9]+)?[a-z]*)?"
                    + "|co[ -]?pilot(?:['\u2019]s)?"
                    + "|perplexity(?:['\u2019]s)?"
                    + "|anthropic(?:['\u2019]s)?"
                    + "|mistral(?:['\u2019]s)?"
                    + "|ollama(?:['\u2019]s)?"
                    + "|hugging[ -]?face(?:['\u2019]s)?"
                    + "|stable[ -]?diffusion(?:['\u2019]s)?"
                    + "|json"
                    + "|yaml"
                    + "|sql"
                    + "|nosql"
                    + "|sdk"
                    + "|(?-i:IDE|IDEs)"
                    + "|(?-i:CLI)"
                    + "|(?-i:GUI)"
                    + "|ci[ /\\-]?cd"
                    + "|css"
                    + "|png"
                    + "|jpe?g"
                    + "|svg"
                    + "|blue[ -]?tooth"
                    + "|hdmi"
                    + "|ssd"
                    + "|oled"
                    + "|amoled"
                    + "|lcd"
                    + "|(?-i:LED|LEDs)"
                    + "|(?:4|8)k"
                    + "|(?:4|5)g"
                    + "|lte"
                    + "|volte"
                    + "|vowifi"
                    + "|insta(?:gram)?(?:['\u2019]s)?"
                    + "|telegram(?:['\u2019]s)?"
                    + "|reddit(?:['\u2019]s)?"
                    + "|spotify(?:['\u2019]s)?"
                    + "|netflix(?:['\u2019]s)?"
                    + "|flipkart(?:['\u2019]s)?"
                    + "|meesho(?:['\u2019]s)?"
                    + "|blinkit(?:['\u2019]s)?"
                    + "|zepto(?:['\u2019]s)?"
                    + "|(?-i:Ola)(?:['\u2019]s)?"
                    + "|(?-i:Uber)(?:['\u2019]s)?"
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
        return AsciiUtils.replaceMatches(TECH_WORDS_PATTERN, text, m -> normalizeTerm(m.group()));
    }

    private static String normalizeTerm(String raw) {
        final String lower = AsciiUtils.toAsciiLowerCase(raw);
        final boolean possessive = lower.endsWith("'s") || lower.endsWith("’s");
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
        final boolean isPin = lower.endsWith(" pin") || lower.endsWith("-pin") || lower.startsWith("pin ") || lower.startsWith("pin-") || lower.equals("pin")
                || lower.endsWith(" pins") || lower.endsWith("-pins") || lower.startsWith("pins ") || lower.startsWith("pins-") || lower.equals("pins");
        if (isPin) {
            final boolean plural = (lower.endsWith(" pins") || lower.endsWith("-pins") || lower.equals("pins")
                    || lower.contains("pin codes") || lower.contains("pin-codes") || lower.contains("pin- codes")) && !possessive;
            final String pinWord = plural ? "PINs" : "PIN";
            if (lower.contains("upi")) return "U P I " + pinWord + apostropheS;
            if (lower.contains("atm")) return "A-T M " + pinWord + apostropheS;
            if (lower.contains("code")) return "PIN code" + (plural ? "s" : "") + apostropheS;
            return pinWord + apostropheS;
        }
        final boolean isId = lower.endsWith(" id") || lower.endsWith("-id") || lower.startsWith("id ") || lower.startsWith("id-") || lower.equals("id")
                || lower.endsWith(" ids") || lower.endsWith("-ids") || lower.startsWith("ids ") || lower.startsWith("ids-") || lower.equals("ids");
        if (isId) {
            final boolean cardPlural = lower.contains("cards");
            final boolean idPlural = (lower.endsWith(" ids") || lower.endsWith("-ids") || lower.equals("ids") || lower.startsWith("ids ")) && !possessive;
            final String idWord = idPlural ? "I Ds" : "I D";
            if (lower.contains("card")) return "I D card" + (cardPlural ? "s" : "") + apostropheS;
            if (lower.contains("user")) return "user " + idWord + apostropheS;
            if (lower.contains("email")) return "email " + idWord + apostropheS;
            if (lower.contains("voter")) return "voter " + idWord + apostropheS;
            if (lower.contains("caller")) return "caller " + idWord + apostropheS;
            if (lower.contains("upi")) return "U P I " + idWord + apostropheS;
            if (lower.contains("face")) return "face " + idWord + apostropheS;
            if (lower.contains("touch")) return "touch " + idWord + apostropheS;
            if (lower.contains("gov")) return "govt " + idWord + apostropheS;
            return idWord + apostropheS;
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
        if (lower.contains("vowifi") || lower.contains("vo-wifi") || lower.contains("vo wifi")) {
            return "Vo-Wi-Fi";
        }
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
        if (lower.equals("atm") || lower.equals("atms") || lower.equals("atm's")
                || lower.equals("atms's")) {
            // Plural "ATMs" reads as "A-T M S" (lone S) without the apostrophe;
            // "A-T M's" keeps the plural audible, same convention as OTPs/FAQs.
            final boolean plural = lower.startsWith("atms");
            if (plural && !possessive) {
                return "A-T M's";
            }
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

        // AI & Assistant brands
        if (lower.startsWith("co") && lower.contains("pilot")) {
            return "Co-pilot" + apostropheS;
        }
        if (lower.startsWith("perplexity")) {
            return "Per-plexity" + apostropheS;
        }
        if (lower.startsWith("anthropic")) {
            return "An-thropic" + apostropheS;
        }
        if (lower.startsWith("mistral")) {
            return "Mis-tral" + apostropheS;
        }
        if (lower.startsWith("ollama")) {
            return "O-llama" + apostropheS;
        }
        if (lower.contains("hugging") && lower.contains("face")) {
            return "Hugging-Face" + apostropheS;
        }
        if (lower.contains("stable") && lower.contains("diffusion")) {
            return "Stable-Diffusion" + apostropheS;
        }

        // Developer tools, formats & protocols
        if (lower.equals("json")) {
            return "JAY-son";
        }
        if (lower.equals("yaml")) {
            return "yam-el";
        }
        if (lower.equals("sql")) {
            return "S-Q-L";
        }
        if (lower.equals("nosql")) {
            return "No-S-Q-L";
        }
        if (lower.equals("sdk")) {
            return "S D K";
        }
        if (raw.equals("IDE") || raw.equals("IDEs")) {
            return "I D E" + (raw.endsWith("s") ? "s" : "");
        }
        if (raw.equals("CLI")) {
            return "C L I";
        }
        if (raw.equals("GUI")) {
            return "G U I";
        }
        if (lower.contains("ci") && lower.contains("cd")) {
            return "C I C D";
        }
        if (lower.equals("css")) {
            return "C S S";
        }
        if (lower.equals("png")) {
            return "P N G";
        }
        if (lower.equals("jpeg") || lower.equals("jpg")) {
            return "J-PEG";
        }
        if (lower.equals("svg")) {
            return "S V G";
        }

        // Hardware, display & networking
        if (lower.contains("blue") && lower.contains("tooth")) {
            return "Blue-tooth";
        }
        if (lower.equals("hdmi")) {
            return "H D M I";
        }
        if (lower.equals("ssd")) {
            return "S S D";
        }
        if (lower.equals("oled")) {
            return "O-LED";
        }
        if (lower.equals("amoled")) {
            return "Am-O-LED";
        }
        if (lower.equals("lcd")) {
            return "L C D";
        }
        if (raw.equals("LED") || raw.equals("LEDs")) {
            return "L E D" + (raw.endsWith("s") ? "s" : "");
        }
        if (lower.equals("4k")) {
            return "4 K";
        }
        if (lower.equals("8k")) {
            return "8 K";
        }
        if (lower.equals("4g")) {
            return "4 G";
        }
        if (lower.equals("5g")) {
            return "5 G";
        }
        if (lower.equals("lte")) {
            return "L T E";
        }
        if (lower.equals("volte")) {
            return "Vo-L T E";
        }
        if (lower.equals("vowifi")) {
            return "Vo-Wi-Fi";
        }

        // Daily social, entertainment & delivery apps
        if (lower.startsWith("insta")) {
            return "Insta-gram" + apostropheS;
        }
        if (lower.startsWith("telegram")) {
            return "Tele-gram" + apostropheS;
        }
        if (lower.startsWith("reddit")) {
            return "Red-dit" + apostropheS;
        }
        if (lower.startsWith("spotify")) {
            return "Spoti-fy" + apostropheS;
        }
        if (lower.startsWith("netflix")) {
            return "Net-flix" + apostropheS;
        }
        if (lower.startsWith("flipkart")) {
            return "Flip-kart" + apostropheS;
        }
        if (lower.startsWith("meesho")) {
            return "Mee-sho" + apostropheS;
        }
        if (lower.startsWith("blinkit")) {
            return "Blink-it" + apostropheS;
        }
        if (lower.startsWith("zepto")) {
            return "Zep-to" + apostropheS;
        }
        if (raw.startsWith("Ola")) {
            return "Oh-la" + apostropheS;
        }
        if (raw.startsWith("Uber")) {
            return "Oo-ber" + apostropheS;
        }

        return raw;
    }
}

