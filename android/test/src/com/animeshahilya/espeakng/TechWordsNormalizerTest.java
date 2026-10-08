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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TechWordsNormalizerTest {

    @Test
    public void testNullAndEmptyText() {
        assertNull(TechWordsNormalizer.process(null));
        assertEquals("", TechWordsNormalizer.process(""));
    }

    @Test
    public void testNonAsciiBypass() {
        // Pure non-Latin text should quickly bypass without modification
        assertEquals("नमस्ते दुनिया", TechWordsNormalizer.process("नमस्ते दुनिया"));
        assertEquals("मराठी मजकूर", TechWordsNormalizer.process("मराठी मजकूर"));
        assertEquals("مرحبا بالعالم", TechWordsNormalizer.process("مرحبا بالعالم"));
    }

    @Test
    public void testChatGptVariations() {
        assertEquals("Chat G P T", TechWordsNormalizer.process("chatgpt"));
        assertEquals("Chat G P T", TechWordsNormalizer.process("ChatGPT"));
        assertEquals("Chat G P T", TechWordsNormalizer.process("chat gpt"));
        assertEquals("Chat G P T", TechWordsNormalizer.process("chat-gpt"));
        assertEquals("Chat G P T's response", TechWordsNormalizer.process("ChatGPT's response"));
        assertEquals("Chat G P T's answer", TechWordsNormalizer.process("chatgpt's answer"));
        assertEquals("Ask Chat G P T about it", TechWordsNormalizer.process("Ask ChatGPT about it"));
    }

    @Test
    public void testGPayVariations() {
        assertEquals("G-Pay", TechWordsNormalizer.process("gpay"));
        assertEquals("G-Pay", TechWordsNormalizer.process("GPay"));
        assertEquals("G-Pay", TechWordsNormalizer.process("g pay"));
        assertEquals("G-Pay", TechWordsNormalizer.process("g-pay"));
        assertEquals("G-Pay's interface", TechWordsNormalizer.process("GPay's interface"));
        assertEquals("Send ₹500 via G-Pay", TechWordsNormalizer.process("Send ₹500 via GPay"));
    }

    @Test
    public void testGmailVariations() {
        assertEquals("G-mail", TechWordsNormalizer.process("gmail"));
        assertEquals("G-mail", TechWordsNormalizer.process("Gmail"));
        assertEquals("G-mail", TechWordsNormalizer.process("g mail"));
        assertEquals("G-mail", TechWordsNormalizer.process("g-mail"));
        assertEquals("G-mails", TechWordsNormalizer.process("gmails"));
        assertEquals("G-mail's inbox", TechWordsNormalizer.process("Gmail's inbox"));
        assertEquals("Check your G-mail account", TechWordsNormalizer.process("Check your gmail account"));
    }

    @Test
    public void testWhatsAppAndYouTube() {
        assertEquals("Whats-App", TechWordsNormalizer.process("whatsapp"));
        assertEquals("Whats-App", TechWordsNormalizer.process("WhatsApp"));
        assertEquals("Whats-App", TechWordsNormalizer.process("whats app"));
        assertEquals("Whats-App", TechWordsNormalizer.process("whats-app"));

        assertEquals("You-Tube", TechWordsNormalizer.process("youtube"));
        assertEquals("You-Tube", TechWordsNormalizer.process("YouTube"));
        assertEquals("You-Tube", TechWordsNormalizer.process("you tube"));
        assertEquals("You-Tube", TechWordsNormalizer.process("you-tube"));
    }

    @Test
    public void testFintechAndBanking() {
        assertEquals("Pay T M", TechWordsNormalizer.process("paytm"));
        assertEquals("Pay T M", TechWordsNormalizer.process("Paytm"));
        assertEquals("Pay T M", TechWordsNormalizer.process("PayTM"));
        assertEquals("Pay T M", TechWordsNormalizer.process("pay tm"));

        assertEquals("Phone Pay", TechWordsNormalizer.process("phonepe"));
        assertEquals("Phone Pay", TechWordsNormalizer.process("PhonePe"));
        assertEquals("Phone Pay", TechWordsNormalizer.process("phone pe"));

        assertEquals("Fast-Tag", TechWordsNormalizer.process("fastag"));
        assertEquals("Fast-Tag", TechWordsNormalizer.process("FASTag"));
        assertEquals("Fast-Tag", TechWordsNormalizer.process("Fastag"));
        assertEquals("Fast-Tags", TechWordsNormalizer.process("fastags"));

        assertEquals("U P I", TechWordsNormalizer.process("upi"));
        assertEquals("U P I", TechWordsNormalizer.process("UPI"));
        assertEquals("M PIN", TechWordsNormalizer.process("mpin"));
        assertEquals("M PIN", TechWordsNormalizer.process("MPIN"));

        assertEquals("N E F T", TechWordsNormalizer.process("neft"));
        assertEquals("R T G S", TechWordsNormalizer.process("rtgs"));
        assertEquals("I M P S", TechWordsNormalizer.process("IMPS"));
        assertEquals("little imps", TechWordsNormalizer.process("little imps"));
    }

    @Test
    public void testAcronymsAndConnectivity() {
        assertEquals("Wi-Fi", TechWordsNormalizer.process("wifi"));
        assertEquals("Wi-Fi", TechWordsNormalizer.process("WiFi"));
        assertEquals("Wi-Fi", TechWordsNormalizer.process("wi-fi"));
        assertEquals("Wi-Fi", TechWordsNormalizer.process("wi fi"));

        assertEquals("O T P", TechWordsNormalizer.process("otp"));
        assertEquals("O T P", TechWordsNormalizer.process("OTP"));
        assertEquals("O T P's", TechWordsNormalizer.process("otps"));

        assertEquals("F A-Q", TechWordsNormalizer.process("faq"));
        assertEquals("F A-Q's", TechWordsNormalizer.process("faqs"));
        assertEquals("F A-Q", TechWordsNormalizer.process("FAQ"));
        assertEquals("F A-Q's", TechWordsNormalizer.process("FAQs"));

        assertEquals("P D F", TechWordsNormalizer.process("pdf"));
        assertEquals("P D F's", TechWordsNormalizer.process("pdfs"));

        assertEquals("S M S", TechWordsNormalizer.process("sms"));
        assertEquals("S M S", TechWordsNormalizer.process("SMS"));

        assertEquals("A-P I", TechWordsNormalizer.process("api"));
        assertEquals("A-P I's", TechWordsNormalizer.process("apis"));

        assertEquals("U R L", TechWordsNormalizer.process("url"));
        assertEquals("U R L's", TechWordsNormalizer.process("urls"));

        assertEquals("e SIM", TechWordsNormalizer.process("esim"));
        assertEquals("e SIM", TechWordsNormalizer.process("eSIM"));
        assertEquals("e SIM", TechWordsNormalizer.process("e-sim"));

        assertEquals("A-T M", TechWordsNormalizer.process("atm"));
        assertEquals("K Y C", TechWordsNormalizer.process("kyc"));
        assertEquals("G P S", TechWordsNormalizer.process("gps"));
        assertEquals("U S B", TechWordsNormalizer.process("usb"));
        assertEquals("N F C", TechWordsNormalizer.process("nfc"));
        assertEquals("V P N", TechWordsNormalizer.process("vpn"));
        assertEquals("C P U", TechWordsNormalizer.process("cpu"));
        assertEquals("G P U", TechWordsNormalizer.process("gpu"));
    }

    @Test
    public void testAppleAndOsDevices() {
        assertEquals("i-Phone", TechWordsNormalizer.process("iphone"));
        assertEquals("i-Phone", TechWordsNormalizer.process("iPhone"));
        assertEquals("i-Phones", TechWordsNormalizer.process("iphones"));
        assertEquals("i-Pad", TechWordsNormalizer.process("ipad"));
        assertEquals("i-Pad", TechWordsNormalizer.process("iPad"));
        assertEquals("i-Mac", TechWordsNormalizer.process("imac"));
        assertEquals("i-Pod", TechWordsNormalizer.process("ipod"));
        assertEquals("i-Watch", TechWordsNormalizer.process("iwatch"));
        assertEquals("i O S", TechWordsNormalizer.process("ios"));
        assertEquals("Mac O S", TechWordsNormalizer.process("macos"));
        assertEquals("Mac O S", TechWordsNormalizer.process("mac os"));
    }

    @Test
    public void testDevelopersAndAiBrands() {
        assertEquals("Git-Hub", TechWordsNormalizer.process("github"));
        assertEquals("Git-Hub", TechWordsNormalizer.process("GitHub"));
        assertEquals("Git-Lab", TechWordsNormalizer.process("gitlab"));
        assertEquals("Linked-In", TechWordsNormalizer.process("linkedin"));
        assertEquals("Linked-In", TechWordsNormalizer.process("LinkedIn"));
        assertEquals("Tik-Tok", TechWordsNormalizer.process("tiktok"));
        assertEquals("Snap-Chat", TechWordsNormalizer.process("snapchat"));

        assertEquals("Open-AI", TechWordsNormalizer.process("openai"));
        assertEquals("Open-AI", TechWordsNormalizer.process("OpenAI"));
        assertEquals("Deep-Seek", TechWordsNormalizer.process("deepseek"));
        assertEquals("Mid-Journey", TechWordsNormalizer.process("midjourney"));
        assertEquals("Gen-AI", TechWordsNormalizer.process("genai"));

        assertEquals("G P T-4", TechWordsNormalizer.process("gpt-4"));
        assertEquals("G P T-4", TechWordsNormalizer.process("gpt4"));
        assertEquals("G P T-3.5", TechWordsNormalizer.process("gpt-3.5"));
    }

    @Test
    public void testCompoundPhrases() {
        assertEquals("Q R code", TechWordsNormalizer.process("qr code"));
        assertEquals("scan Q R", TechWordsNormalizer.process("scan qr"));
        assertEquals("user I D", TechWordsNormalizer.process("user id"));
        assertEquals("email I D", TechWordsNormalizer.process("email id"));
        assertEquals("voter I D", TechWordsNormalizer.process("voter id"));
        assertEquals("I D card", TechWordsNormalizer.process("id card"));
        assertEquals("U P I PIN", TechWordsNormalizer.process("upi pin"));
        assertEquals("A-T M PIN", TechWordsNormalizer.process("atm pin"));
        assertEquals("PIN code", TechWordsNormalizer.process("pin code"));
    }

    @Test
    public void testMultilingualMixedUtterances() {
        // Hindi mixed with tech words
        assertEquals("G-Pay से पैसे भेजे और Chat G P T से पूछो। अपना O T P शेयर मत करना।",
                TechWordsNormalizer.process("GPay से पैसे भेजे और ChatGPT से पूछो। अपना OTP शेयर मत करना।"));

        // Marathi mixed with tech words
        assertEquals("माझा G-Pay नंबर आणि G-mail चेक करा, U P I PIN टाका.",
                TechWordsNormalizer.process("माझा GPay नंबर आणि Gmail चेक करा, UPI PIN टाका."));

        // Tamil mixed with tech words
        assertEquals("உங்கள் G-Pay எண்ணை அனுப்பவும், O T P பகிர வேண்டாம்.",
                TechWordsNormalizer.process("உங்கள் GPay எண்ணை அனுப்பவும், OTP பகிர வேண்டாம்."));

        // Arabic mixed with tech words
        assertEquals("تحقق من بريدك على G-mail واستخدم Chat G P T",
                TechWordsNormalizer.process("تحقق من بريدك على Gmail واستخدم ChatGPT"));
    }

    @Test
    public void testNamesNaturalVoicesMisread() {
        assertEquals("Order on Zo-mato or Swig-ee", TechWordsNormalizer.process("Order on Zomato or Swiggy"));
        assertEquals("Phone Pay", TechWordsNormalizer.process("PhonePe"));
        assertEquals("H T M L page", TechWordsNormalizer.process("HTML page"));
        // A lone "A" is the article to eSpeak: joined to the next letter it is a letter name.
        assertEquals("Read the F A-Q", TechWordsNormalizer.process("Read the FAQ"));
    }

    @Test
    public void testPreservesRegularWords() {
        // Regular words that contain substrings of brands must NOT be modified
        assertEquals("We had a nice chat yesterday.", TechWordsNormalizer.process("We had a nice chat yesterday."));
        assertEquals("Please pay your bill on time.", TechWordsNormalizer.process("Please pay your bill on time."));
        assertEquals("Check the postal mail box.", TechWordsNormalizer.process("Check the postal mail box."));
        assertEquals("Safety pin for the shirt.", TechWordsNormalizer.process("Safety pin for the shirt."));
        assertEquals("Freud discussed the id and ego.", TechWordsNormalizer.process("Freud discussed the id and ego."));
        // "led" as past tense of lead must not become "L E D"
        assertEquals("He led the team forward.", TechWordsNormalizer.process("He led the team forward."));
    }

    @Test
    public void testAiAndDevExpandedVocab() {
        assertEquals("Co-pilot", TechWordsNormalizer.process("copilot"));
        assertEquals("Co-pilot", TechWordsNormalizer.process("co-pilot"));
        assertEquals("Per-plexity", TechWordsNormalizer.process("Perplexity"));
        assertEquals("An-thropic", TechWordsNormalizer.process("Anthropic"));
        assertEquals("Mis-tral", TechWordsNormalizer.process("Mistral"));
        assertEquals("O-llama", TechWordsNormalizer.process("ollama"));
        assertEquals("Hugging-Face", TechWordsNormalizer.process("HuggingFace"));
        assertEquals("Stable-Diffusion", TechWordsNormalizer.process("Stable Diffusion"));

        assertEquals("JAY-son", TechWordsNormalizer.process("json"));
        assertEquals("yam-el", TechWordsNormalizer.process("yaml"));
        assertEquals("S-Q-L", TechWordsNormalizer.process("sql"));
        assertEquals("No-S-Q-L", TechWordsNormalizer.process("nosql"));
        assertEquals("S D K", TechWordsNormalizer.process("sdk"));
        assertEquals("I D E", TechWordsNormalizer.process("IDE"));
        assertEquals("C L I", TechWordsNormalizer.process("CLI"));
        assertEquals("G U I", TechWordsNormalizer.process("GUI"));
        assertEquals("C I C D", TechWordsNormalizer.process("ci/cd"));
        assertEquals("C S S", TechWordsNormalizer.process("css"));
        assertEquals("P N G", TechWordsNormalizer.process("png"));
        assertEquals("J-PEG", TechWordsNormalizer.process("jpeg"));
        assertEquals("J-PEG", TechWordsNormalizer.process("jpg"));
        assertEquals("S V G", TechWordsNormalizer.process("svg"));
    }

    @Test
    public void testHardwareAndAppsExpandedVocab() {
        assertEquals("Blue-tooth", TechWordsNormalizer.process("bluetooth"));
        assertEquals("H D M I", TechWordsNormalizer.process("hdmi"));
        assertEquals("S S D", TechWordsNormalizer.process("ssd"));
        assertEquals("O-LED", TechWordsNormalizer.process("oled"));
        assertEquals("Am-O-LED", TechWordsNormalizer.process("amoled"));
        assertEquals("L C D", TechWordsNormalizer.process("lcd"));
        assertEquals("L E D", TechWordsNormalizer.process("LED"));
        assertEquals("4 K", TechWordsNormalizer.process("4k"));
        assertEquals("8 K", TechWordsNormalizer.process("8k"));
        assertEquals("5 G", TechWordsNormalizer.process("5g"));
        assertEquals("4 G", TechWordsNormalizer.process("4g"));
        assertEquals("L T E", TechWordsNormalizer.process("lte"));
        assertEquals("Vo-L T E", TechWordsNormalizer.process("volte"));
        assertEquals("Vo-Wi-Fi", TechWordsNormalizer.process("vowifi"));

        assertEquals("Insta-gram", TechWordsNormalizer.process("Instagram"));
        assertEquals("Tele-gram", TechWordsNormalizer.process("telegram"));
        assertEquals("Red-dit", TechWordsNormalizer.process("reddit"));
        assertEquals("Spoti-fy", TechWordsNormalizer.process("spotify"));
        assertEquals("Net-flix", TechWordsNormalizer.process("netflix"));
        assertEquals("Flip-kart", TechWordsNormalizer.process("Flipkart"));
        assertEquals("Mee-sho", TechWordsNormalizer.process("meesho"));
        assertEquals("Blink-it", TechWordsNormalizer.process("Blinkit"));
        assertEquals("Zep-to", TechWordsNormalizer.process("Zepto"));
        assertEquals("Oh-la", TechWordsNormalizer.process("Ola"));
        assertEquals("Oo-ber", TechWordsNormalizer.process("Uber"));
    }
}
