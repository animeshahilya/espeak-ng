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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Guards the paragraph-break contract the synthesis pipeline relies on:
 * blank lines must survive every preprocessing step so the engine still
 * sees them and produces its longer paragraph pauses (readclause.c treats
 * a second newline as a paragraph, with still longer pauses for runs of
 * blank lines). A step that collapsed "\n\n" would silently flatten
 * paragraph prosody into sentence prosody.
 */
@RunWith(AndroidJUnit4.class)
public class TextPipelineDeviceTest {
    @Test
    public void testWatchdogSanitizerKeepsParagraphBreaks() {
        assertThat(TextPreprocessor.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", false),
                containsString("\n\n"));
        assertThat(TextPreprocessor.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", true),
                containsString("\n\n"));
    }

    @Test
    public void testChunkingKeepsParagraphBreaks() {
        final String text = "First paragraph with enough words to matter.\n\n"
                + "Second paragraph right after a blank line.\n\nThird one.";
        final List<String> chunks = TextPreprocessor.chunkForWatchdog(text);
        assertThat(chunks, is(not(empty())));
        final StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            assertThat(chunk, is(not("")));
            joined.append(chunk);
        }
        assertThat(joined.toString(), is(text));
        assertThat(joined.toString(), containsString("\n\n"));
    }

    @Test
    public void testUserDictionaryFastPathBypass() {
        UserDictionary rule = new UserDictionary("quick", "slow", true, false, false);
        assertThat(rule.apply("The fast brown fox"), is("The fast brown fox"));
        assertThat(rule.apply("The quick brown fox"), is("The slow brown fox"));
        assertThat(rule.apply("hi"), is("hi")); // Shorter than pattern
    }

    @Test
    public void testFastTanhAccuracy() {
        for (float x = -4.0f; x <= 4.0f; x += 0.1f) {
            float expected = (float) Math.tanh(x);
            float actual = AudioOptimizer.fastTanh(x);
            assertThat("x=" + x, Math.abs(actual - expected), lessThan(0.002f));
        }
    }

    @Test
    public void testSimplifyUrls() {
        String simplified = TextPreprocessor.simplifyUrls("Check https://www.example.com/docs/guide?ref=twitter&utm_source=test");
        assertThat(simplified, containsString("example.com"));
        assertThat(simplified, containsString("docs"));
        assertThat(simplified, containsString("with parameters"));
        assertThat(simplified, not(containsString("https://")));
        assertThat(simplified, not(containsString("www.")));
    }

    /** A date-shaped path segment stays part of the simplified URL. */
    @Test
    public void testSimplifyUrlsKeepsDateSlug() {
        String simplified = TextPreprocessor.simplifyUrls("https://example.com/blog/2024-01-15-my-post");
        assertThat(simplified, containsString("2024-01-15-my-post"));
        assertThat(simplified, not(containsString("https://")));
    }

    @Test
    public void testCondenseRepeatedCharacters() {
        // NVDA repeat rule: 4+ identical symbols collapse to "N name" with
        // the table's singular names ("dash", not "dashes").
        String counted = TextPreprocessor.condenseRepeatedCharacters("Divider: -------------------- end", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, containsString("20"));
        assertThat(counted, containsString("dash"));

        String asterisks = TextPreprocessor.condenseRepeatedCharacters("Password: ********", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(asterisks, containsString("8"));
        assertThat(asterisks, containsString("star"));

        // NVDA has no letter collapsing and no truncate mode: a letter run
        // passes through untouched (both count and truncate map to the NVDA
        // rule now).
        String letters = TextPreprocessor.condenseRepeatedCharacters("soooooooo long", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(letters, is("soooooooo long"));

        // Off mode
        String off = TextPreprocessor.condenseRepeatedCharacters("-------", VoiceSettings.REPEATED_CHARS_OFF);
        assertThat(off, is("-------"));
    }

    @Test
    public void testClarifyEmojiKeepsFlagPairsGlued() {
        // NVDA CLDR naming: every cluster resolves longest-first, so the
        // Indian flag (RI I + RI N) announces as one name instead of two
        // lone regional indicators ("symbol one ef one eye...").
        // Flag pairs use \\u escapes (raw RI chars have been mangled by
        // tooling before); other emoji are literals with codepoints noted.
        String flag = "\uD83C\uDDEE\uD83C\uDDF3"; // U+1F1EE U+1F1F3
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(flag), is("flag India"));
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements("Go " + flag + "!"), is("Go flag India !"));

        // Two flags name *between* pairs, never within one.
        String fr = "\uD83C\uDDEB\uD83C\uDDF7"; // U+1F1EB U+1F1F7
        String de = "\uD83C\uDDE9\uD83C\uDDEA"; // U+1F1E9 U+1F1EA
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(fr + de), is("flag France flag Germany"));

        // Adjacent plain emoji each get their name; ZWJ families, skin tones
        // and VS16 sequences resolve to a single name.
        String tears = "😂"; // U+1F602
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(tears + tears),
                is("face with tears of joy face with tears of joy"));
        String family = "👨‍👩‍👧"; // U+1F468 U+200D U+1F469 U+200D U+1F467
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(family), is("family man, woman, girl"));
        String toned = "👍🏽"; // U+1F44D U+1F3FD
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(toned), is("thumbs up medium skin tone"));
        String heart = "❤️"; // U+2764 U+FE0F
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements(heart), is("red heart"));

        // Abutting text is padded with spaces, as NVDA pads a replacement.
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements("a⏯b"), is("a play or pause button b"));

        // A ZWJ pulls in whatever follows it even when that codepoint is not
        // emoji on its own: U+1F642 U+200D U+2194 is the single "head shaking
        // horizontally" emoji, and cutting the arrow off would announce two
        // wrong symbols instead (same failure mode as split flags).
        String shaking = "🙂‍↔";
        assertThat(TextPreprocessor.clarifyEmojiAnnouncements("a" + shaking + "b"),
                is("a head shaking horizontally b"));
    }

    @Test
    public void testCondenseRepeatedCharactersIgnoresDigits() {
        // A repeated digit run (PIN, serial, phone number) must be read as-is in
        // every mode - "count" must not say "4 fives" and "truncate" must not
        // shorten "5555" to "555" and silently change the value.
        String counted = TextPreprocessor.condenseRepeatedCharacters("Your PIN is 5555.", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, is("Your PIN is 5555."));

        String truncated = TextPreprocessor.condenseRepeatedCharacters("Call 1112223333 now.", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(truncated, is("Call 1112223333 now."));

        // Count mode also leaves plain letter runs alone (only runs get
        // counted) and never touches the surrounding sentence punctuation.
        String lettersUntouched = TextPreprocessor.condenseRepeatedCharacters("yessss!!!!", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(lettersUntouched, containsString("yessss"));
        assertThat(lettersUntouched, containsString("4 bang"));

        // Three repeats are below NVDA's 4+ threshold: untouched.
        assertThat(TextPreprocessor.condenseRepeatedCharacters("hey!!!", VoiceSettings.REPEATED_CHARS_COUNT),
                is("hey!!!"));
    }

    @Test
    public void testCondenseRepeatedEmojisCountMode() {
        // Simple repeating emoji run
        String counted = TextPreprocessor.condenseRepeatedCharacters("So funny 😂😂😂😂😂!", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, containsString("😂, 5 times"));
        assertThat(counted, not(containsString("😂😂")));

        // Whitespace-separated repeating emoji run
        String spaced = TextPreprocessor.condenseRepeatedCharacters("Fire 🔥 🔥 🔥 🔥 🔥 here", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(spaced, containsString("🔥, 5 times"));

        // Repeating emoji with skin tones
        String toned = TextPreprocessor.condenseRepeatedCharacters("Nice 👍🏽👍🏽👍🏽👍🏽", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(toned, containsString("👍🏽, 4 times"));

        // 3 emojis trigger repeat collapsing
        String three = TextPreprocessor.condenseRepeatedCharacters("Three 😂😂😂 end", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(three, containsString("😂, 3 times"));

        // 2 emojis are below the repeat threshold (threshold is 3)
        String justTwo = TextPreprocessor.condenseRepeatedCharacters("Pair 😂 😂 ok", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(justTwo, containsString("Pair 😂 😂 ok"));
    }

    @Test
    public void testCondenseRepeatedEmojisTruncateMode() {
        // Truncate mode caps repeated emoji runs at 3 instances
        String truncated = TextPreprocessor.condenseRepeatedCharacters("Love ❤️❤️❤️❤️❤️❤️ so much", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(truncated, containsString("❤️ ❤️ ❤️ "));
        assertThat(truncated, not(containsString("❤️❤️❤️❤️")));
    }

    @Test
    public void testCondenseRepeatedEmojisOffMode() {
        // Off mode preserves all repetitions untouched
        String off = TextPreprocessor.condenseRepeatedCharacters("Laugh 😂😂😂😂😂", VoiceSettings.REPEATED_CHARS_OFF);
        assertThat(off, is("Laugh 😂😂😂😂😂"));
    }

    @Test
    public void testSimplifyUrlsMixedCaseProtocols() {
        String simplified1 = TextPreprocessor.simplifyUrls("Visit Https://Example.com/page");
        assertThat(simplified1, containsString("Example.com"));
        assertThat(simplified1, not(containsString("Https://")));

        String simplified2 = TextPreprocessor.simplifyUrls("Go to HTTP://WWW.GITHUB.COM/test");
        assertThat(simplified2, containsString("GITHUB.COM"));
        assertThat(simplified2, not(containsString("HTTP://")));
        assertThat(simplified2, not(containsString("WWW.")));
    }

    /**
     * Direct NVDA-parity pins for the symbol engine. Note the characteristic
     * subtlety: sentence-ending dots carry level ALL (not SOME), so at SOME
     * "Hello, world. Bye." passes through untouched - the comma is kept
     * silently, the dots are sentence dots, and only a non-sentence dot
     * like "a.b" is announced. Level-zero symbols (negative minus) announce
     * even at NONE, and decimal points are never expanded.
     */
    @Test
    public void testNvdaSymbolLevels() {
        assertThat(NvdaSymbolProcessor.processText("Hello, world. Bye.",
                NvdaSymbolProcessor.LEVEL_SOME, true), is("Hello, world. Bye."));
        assertThat(NvdaSymbolProcessor.processText("a.b",
                NvdaSymbolProcessor.LEVEL_SOME, true), containsString("dot"));
        assertThat(NvdaSymbolProcessor.processText("End.",
                NvdaSymbolProcessor.LEVEL_ALL, true), containsString("dot"));

        String none = NvdaSymbolProcessor.processText("Temp -5, ok.",
                NvdaSymbolProcessor.LEVEL_NONE, true);
        assertThat(none, containsString("minus"));
        assertThat(none, not(containsString("dot")));
        assertThat(none, containsString("."));

        assertThat(NvdaSymbolProcessor.processText("Pi is 3.14.",
                NvdaSymbolProcessor.LEVEL_ALL, true), containsString("3.14"));

        assertThat(NvdaSymbolProcessor.processText("well-known",
                NvdaSymbolProcessor.LEVEL_SOME, true), not(containsString("dash")));
    }

    /**
     * Custom-list mode speaks exactly the listed characters: the dot run is
     * announced while "?" and "!" are neither announced NOR removed - their
     * preserve=always keeps them as pauses, exactly like NVDA.
     */
    @Test
    public void testNvdaCustomSymbols() {
        assertThat(NvdaSymbolProcessor.processCustom("Wait... what? Yes!", ".", true),
                is("Wait dot dot dot... what? Yes!"));
        assertThat(NvdaSymbolProcessor.processCustom("a?b", ".?!", true),
                containsString("question"));
    }

    @Test
    public void testNvdaSingleSymbol() {
        assertThat(NvdaSymbolProcessor.processSingleSymbol("!"), is("bang"));
        assertThat(NvdaSymbolProcessor.processSingleSymbol("a"), is("a"));
        assertThat(NvdaSymbolProcessor.processSingleSymbol(" "), is(" "));
        assertThat(NvdaSymbolProcessor.processSingleSymbol(null), is(nullValue()));
    }

    @Test
    public void testNvdaRepeatRuns() {
        assertThat(NvdaSymbolProcessor.collapseRepeatRuns("-------"), containsString("7 dash"));
        assertThat(NvdaSymbolProcessor.collapseRepeatRuns("hey!!!"), is("hey!!!"));
        assertThat(NvdaSymbolProcessor.collapseRepeatRuns(null), is(nullValue()));
    }

    @Test
    public void testNvdaEmojiNames() {
        assertThat(NvdaEmoji.substitute("🇮🇳"), is("flag India"));
        assertThat(NvdaEmoji.substitute("👍🏽"), is("thumbs up medium skin tone"));
    }

    @Test
    public void testNvdaEmojiNamesFollowVoiceLanguage() {
        // NVDA's lookup: full locale, then base language, then English.
        assertThat(NvdaEmoji.substitute("🇮🇳", "hi"), is("झंडा भारत"));
        assertThat(NvdaEmoji.substitute("🇮🇳", "en-in"), is("flag India"));
        assertThat(NvdaEmoji.substitute("😀", "de"), is("grinsendes Gesicht"));
        assertThat(NvdaEmoji.substitute("😀", "pt-br"), is(NvdaEmoji.substitute("😀", "pt_BR")));
        // No NVDA dictionary for Marathi: English, as NVDA does.
        assertThat(NvdaEmoji.substitute("😀", "mr"), is("grinning face"));
    }

    /** Opt-in money reading: unit after the number, cents split out, plurals right. */
    @Test
    public void testReadMoney() {
        assertThat(NumberReading.readMoney("$5.50", true), is("5 dollars 50 cents"));
        assertThat(NumberReading.readMoney("$1", true), is("1 dollar"));
        assertThat(NumberReading.readMoney("$0.99", true), is("99 cents"));
        assertThat(NumberReading.readMoney("£1.50", true), is("1 pound 50 pence"));
        assertThat(NumberReading.readMoney("20 USD", true), is("20 dollars"));
        assertThat(NumberReading.readMoney("€2.5M", true), is("2.5 million euros"));
        assertThat(NumberReading.readMoney("-$4", true), is("minus 4 dollars"));
        assertThat(NumberReading.readMoney("$5-$10", true), is("5 dollars-10 dollars"));
        assertThat(NumberReading.readMoney("echo $HOME", true), is("echo $HOME"));
        // Left for the Indian pass when it owns rupee amounts.
        assertThat(NumberReading.readMoney("₹250", false), is("₹250"));
        assertThat(NumberReading.isEnglish("en-US"), is(true));
        assertThat(NumberReading.isEnglish("hi"), is(false));
    }

    /** Opt-in code reading: only numbers near a code word, never money or quantities. */
    @Test
    public void testReadCodes() {
        assertThat(NumberReading.readCodes("Your OTP is 482913. Valid for 10 minutes."),
                is("Your OTP is 4 8 2 9 1 3. Valid for 10 minutes."));
        assertThat(NumberReading.readCodes("Use code 482 913"), is("Use code 4 8 2, 9 1 3"));
        assertThat(NumberReading.readCodes("A/c XX1234 debited by Rs 5000"),
                is("A/c ending 1 2 3 4 debited by Rs 5000"));
        assertThat(NumberReading.readCodes("In 2026 we sold 48291 units"),
                is("In 2026 we sold 48291 units"));
        assertThat(NumberReading.readCodes("Code at 10:30"), is("Code at 10:30"));
    }

    /** Opt-in punctuation sounds: only symbols the level would name become markers. */
    @Test
    public void testPunctuationSounds() {
        String most = Earcons.mark("f(x) \"hi\"", NvdaSymbolProcessor.LEVEL_MOST, null);
        assertThat(most.length(), is(9));
        assertThat(Earcons.isMarker(most.charAt(1)), is(true));
        assertThat(Earcons.isMarker(most.charAt(3)), is(true));
        assertThat(Earcons.isMarker(most.charAt(5)), is(true));
        assertThat(most.charAt(1) != most.charAt(3), is(true));
        // At "some" brackets are not named, so nothing becomes a sound.
        assertThat(Earcons.mark("f(x)", NvdaSymbolProcessor.LEVEL_SOME, null), is("f(x)"));
        // A custom list decides per character.
        assertThat(Earcons.mark("(x)", NvdaSymbolProcessor.LEVEL_ALL, "("), is(Earcons.markerFor('(') + "x)"));
        byte[] pcm = Earcons.pcm(Earcons.markerFor('('), 22050, 1, 100);
        assertThat(pcm.length > 22050 / 10, is(true));
    }

    /** Opt-in phrase pauses: only long comma-less stretches, never short ones. */
    @Test
    public void testPhrasePauses() {
        assertThat(PhrasePauses.process(
                "I went to the market early this morning and bought some fresh vegetables for dinner.", "en"),
                is("I went to the market early this morning, and bought some fresh vegetables for dinner."));
        assertThat(PhrasePauses.process("Salt and pepper.", "en"), is("Salt and pepper."));
        assertThat(PhrasePauses.process(
                "मैं आज सुबह बाजार गया था और वहाँ से ताज़ी सब्ज़ियाँ लेकर आया।", "hi"),
                is("मैं आज सुबह बाजार गया था, और वहाँ से ताज़ी सब्ज़ियाँ लेकर आया।"));
        assertThat(PhrasePauses.supports("de"), is(false));
    }

    /** Opt-in abbreviations: the ones eSpeak reads as written, only in a settling context. */
    @Test
    public void testExpandAbbreviations() {
        assertThat(Abbreviations.process("Prof. Rao vs the govt"), is("Professor Rao versus the government"));
        assertThat(Abbreviations.process("It is approx 5 km and 1 hr away"),
                is("It is approximately 5 kilometres and 1 hour away"));
        assertThat(Abbreviations.process("Due on 12 Jan, room No. 7"), is("Due on 12 January, room Number 7"));
        assertThat(Abbreviations.process("The sun rose in Jan"), is("The sun rose in Jan"));
        assertThat(Abbreviations.process("Tata Pvt Ltd"), is("Tata Private Ltd"));
        assertThat(Abbreviations.process("Ask Prof."), is("Ask Professor."));
    }

    /** Phonetic letters on character navigation: Devanagari letters get the primer word. */
    @Test
    public void testDevanagariLetterWords() {
        assertThat(TextPreprocessor.expandNatoSpelling("क"), is("क से कबूतर"));
        assertThat(TextPreprocessor.expandNatoSpelling("ह"), is("ह से हल"));
        assertThat(TextPreprocessor.expandNatoSpelling("b"), is("b, Bravo"));
    }

    /** Beta Hinglish reading: Hindi sentences in Latin letters become Devanagari, English stays. */
    @Test
    public void testHinglish() {
        HinglishReader.init(androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext());
        assertThat(HinglishReader.process("aap kaise ho bhai"), is("आप कैसे हो भाई"));
        assertThat(HinglishReader.process("main theek hoon, tum batao"), is("मैं ठीक हूं, तुम बताओ"));
        // English, a lone label, and acronyms stay as they are.
        assertThat(HinglishReader.process("Your order has been shipped"), is("Your order has been shipped"));
        assertThat(HinglishReader.process("Emoji"), is("Emoji"));
        assertThat(HinglishReader.process("OTP bhej do yaar"), startsWith("OTP "));
        // A mixed message: only the Hindi sentence changes.
        assertThat(HinglishReader.process("Meeting is at 5 pm. kal milte hain"),
                is("Meeting is at 5 pm. कल मिलते हैं"));
    }
}
