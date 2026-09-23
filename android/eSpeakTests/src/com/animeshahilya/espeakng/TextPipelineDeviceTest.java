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
        assertThat(TtsService.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", false),
                containsString("\n\n"));
        assertThat(TtsService.sanitizeForWatchdog("First paragraph.\n\nSecond paragraph.", true),
                containsString("\n\n"));
    }

    @Test
    public void testChunkingKeepsParagraphBreaks() {
        final String text = "First paragraph with enough words to matter.\n\n"
                + "Second paragraph right after a blank line.\n\nThird one.";
        final List<String> chunks = TtsService.chunkForWatchdog(text);
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
    public void testExpandRomanNumerals() {
        assertThat(TtsService.expandRomanNumerals("Read Chapter IV carefully."), is("Read Chapter 4 carefully."));
        assertThat(TtsService.expandRomanNumerals("World War II ended in 1945."), is("World War 2 ended in 1945."));
        assertThat(TtsService.expandRomanNumerals("King Henry VIII of England"), is("King Henry 8 of England"));
        assertThat(TtsService.expandRomanNumerals("Section IX"), is("Section 9"));
        // Unmatched words without prefix should not be falsely converted
        assertThat(TtsService.expandRomanNumerals("This is plain IV text."), is("This is plain IV text."));
    }

    @Test
    public void testSimplifyUrls() {
        String simplified = TtsService.simplifyUrls("Check https://www.example.com/docs/guide?ref=twitter&utm_source=test");
        assertThat(simplified, containsString("example.com"));
        assertThat(simplified, containsString("docs"));
        assertThat(simplified, containsString("with parameters"));
        assertThat(simplified, not(containsString("https://")));
        assertThat(simplified, not(containsString("www.")));
    }

    /**
     * Regression test for a pipeline-ordering bug: simplifyUrls must run
     * before expandTimeDate (and the other digit/symbol expanders) in
     * TtsService's synthesis pipeline. A URL with a date-stamped path
     * segment (a common blog/news URL shape) has both a colon-free date
     * pattern and a recognizable URL prefix; if expandTimeDate ran first,
     * it would insert spaces into "2024-01-15", and simplifyUrls's
     * PATTERN_URL - whose path match is \S-only, can't cross a space -
     * would then only match the URL fragment up to that inserted space,
     * silently truncating the recognized URL and leaving the rest as raw
     * unprocessed text.
     */
    @Test
    public void testSimplifyUrlsSurvivesBeforeDateExpansion() {
        String url = "https://example.com/blog/2024-01-15-my-post";

        // Correct pipeline order (simplifyUrls first, as TtsService now
        // does): the whole path, including the date-shaped slug, is
        // recognized as part of the URL and survives as one contiguous
        // "slash"-joined unit - simplifyUrls only ever touches "/", so the
        // "2024-01-15-my-post" segment passes through with its hyphens intact.
        String correctOrder = TtsService.simplifyUrls(url);
        assertThat(correctOrder, containsString("2024-01-15-my-post"));
        assertThat(correctOrder, not(containsString("https://")));

        // Demonstrates why the order matters: running date expansion on the
        // *raw* URL first breaks the date slug apart with spaces before
        // simplifyUrls ever sees it, so the previously-intact
        // "2024-01-15-my-post" substring no longer survives.
        String wrongOrder = TtsService.simplifyUrls(TtsService.expandTimeDate(url));
        assertThat(wrongOrder, not(containsString("2024-01-15-my-post")));
    }

    /**
     * Regression test for a second pipeline-ordering bug of the same shape:
     * expandCurrencySymbols must run before expandProgrammingSymbols.
     * The NVDA table's "¥" entry does a blind "¥" -&gt; " yen " symbol
     * swap with no amount awareness; expandCurrencySymbols's CURRENCY_YEN
     * extracts the adjacent number too ("¥500" -&gt; "500 yen"). If the plain
     * symbol swap ran first, the "¥" character would already be gone by the
     * time CURRENCY_YEN ran, and "yen 500" (word before number) matches
     * neither of CURRENCY_YEN's prefix ("¥" + number) or suffix (number +
     * "yen") forms - the amount is left unformatted.
     */
    @Test
    public void testCurrencyYenSurvivesBeforeProgrammingSymbols() {
        String text = "It costs ¥500 today.";

        // Correct pipeline order (currency first, as TtsService now does):
        // the amount is properly extracted and reordered to "500 yen".
        String correctOrder = TtsService.expandCurrencySymbols(text);
        assertThat(correctOrder, containsString("500 yen"));
        assertThat(correctOrder, not(containsString("¥")));

        // Demonstrates why the order matters: running the plain symbol swap
        // first consumes the "¥" character before expandCurrencySymbols ever
        // sees it, so the proper "500 yen" phrasing is never produced.
        String wrongOrder = TtsService.expandCurrencySymbols(TtsService.expandProgrammingSymbols(text));
        assertThat(wrongOrder, not(containsString("500 yen")));
    }

    @Test
    public void testCondenseRepeatedCharacters() {
        // NVDA repeat rule: 4+ identical symbols collapse to "N name" with
        // the table's singular names ("dash", not "dashes").
        String counted = TtsService.condenseRepeatedCharacters("Divider: -------------------- end", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, containsString("20"));
        assertThat(counted, containsString("dash"));

        String asterisks = TtsService.condenseRepeatedCharacters("Password: ********", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(asterisks, containsString("8"));
        assertThat(asterisks, containsString("star"));

        // NVDA has no letter collapsing and no truncate mode: a letter run
        // passes through untouched (both count and truncate map to the NVDA
        // rule now).
        String letters = TtsService.condenseRepeatedCharacters("soooooooo long", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(letters, is("soooooooo long"));

        // Off mode
        String off = TtsService.condenseRepeatedCharacters("-------", VoiceSettings.REPEATED_CHARS_OFF);
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
        assertThat(TtsService.clarifyEmojiAnnouncements(flag), is("flag India"));
        assertThat(TtsService.clarifyEmojiAnnouncements("Go " + flag + "!"), is("Go flag India!"));

        // Two flags name *between* pairs, never within one.
        String fr = "\uD83C\uDDEB\uD83C\uDDF7"; // U+1F1EB U+1F1F7
        String de = "\uD83C\uDDE9\uD83C\uDDEA"; // U+1F1E9 U+1F1EA
        assertThat(TtsService.clarifyEmojiAnnouncements(fr + de), is("flag France flag Germany"));

        // Adjacent plain emoji each get their name; ZWJ families, skin tones
        // and VS16 sequences resolve to a single name.
        String tears = "😂"; // U+1F602
        assertThat(TtsService.clarifyEmojiAnnouncements(tears + tears),
                is("face with tears of joy face with tears of joy"));
        String family = "👨‍👩‍👧"; // U+1F468 U+200D U+1F469 U+200D U+1F467
        assertThat(TtsService.clarifyEmojiAnnouncements(family), is("family man, woman, girl"));
        String toned = "👍🏽"; // U+1F44D U+1F3FD
        assertThat(TtsService.clarifyEmojiAnnouncements(toned), is("thumbs up medium skin tone"));
        String heart = "❤️"; // U+2764 U+FE0F
        assertThat(TtsService.clarifyEmojiAnnouncements(heart), is("red heart"));

        // Abutting text still gets its aside-pause on both sides.
        assertThat(TtsService.clarifyEmojiAnnouncements("a⏯b"), is("a, play or pause button, b"));

        // A ZWJ pulls in whatever follows it even when that codepoint is not
        // emoji on its own: U+1F642 U+200D U+2194 is the single "head shaking
        // horizontally" emoji, and cutting the arrow off would announce two
        // wrong symbols instead (same failure mode as split flags).
        String shaking = "🙂‍↔";
        assertThat(TtsService.clarifyEmojiAnnouncements("a" + shaking + "b"),
                is("a, head shaking horizontally, b"));
    }

    @Test
    public void testCondenseRepeatedCharactersIgnoresDigits() {
        // A repeated digit run (PIN, serial, phone number) must be read as-is in
        // every mode - "count" must not say "4 fives" and "truncate" must not
        // shorten "5555" to "555" and silently change the value.
        String counted = TtsService.condenseRepeatedCharacters("Your PIN is 5555.", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, is("Your PIN is 5555."));

        String truncated = TtsService.condenseRepeatedCharacters("Call 1112223333 now.", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(truncated, is("Call 1112223333 now."));

        // Count mode also leaves plain letter runs alone (only runs get
        // counted) and never touches the surrounding sentence punctuation.
        String lettersUntouched = TtsService.condenseRepeatedCharacters("yessss!!!!", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(lettersUntouched, containsString("yessss"));
        assertThat(lettersUntouched, containsString("4 bang"));

        // Three repeats are below NVDA's 4+ threshold: untouched.
        assertThat(TtsService.condenseRepeatedCharacters("hey!!!", VoiceSettings.REPEATED_CHARS_COUNT),
                is("hey!!!"));
    }

    @Test
    public void testCondenseRepeatedEmojisCountMode() {
        // Simple repeating emoji run
        String counted = TtsService.condenseRepeatedCharacters("So funny 😂😂😂😂😂!", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(counted, containsString("😂, 5 times"));
        assertThat(counted, not(containsString("😂😂")));

        // Whitespace-separated repeating emoji run
        String spaced = TtsService.condenseRepeatedCharacters("Fire 🔥 🔥 🔥 🔥 🔥 here", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(spaced, containsString("🔥, 5 times"));

        // Repeating emoji with skin tones
        String toned = TtsService.condenseRepeatedCharacters("Nice 👍🏽👍🏽👍🏽👍🏽", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(toned, containsString("👍🏽, 4 times"));

        // 3 emojis trigger repeat collapsing
        String three = TtsService.condenseRepeatedCharacters("Three 😂😂😂 end", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(three, containsString("😂, 3 times"));

        // 2 emojis are below the repeat threshold (threshold is 3)
        String justTwo = TtsService.condenseRepeatedCharacters("Pair 😂 😂 ok", VoiceSettings.REPEATED_CHARS_COUNT);
        assertThat(justTwo, containsString("Pair 😂 😂 ok"));
    }

    @Test
    public void testCondenseRepeatedEmojisTruncateMode() {
        // Truncate mode caps repeated emoji runs at 3 instances
        String truncated = TtsService.condenseRepeatedCharacters("Love ❤️❤️❤️❤️❤️❤️ so much", VoiceSettings.REPEATED_CHARS_TRUNCATE);
        assertThat(truncated, containsString("❤️ ❤️ ❤️ "));
        assertThat(truncated, not(containsString("❤️❤️❤️❤️")));
    }

    @Test
    public void testCondenseRepeatedEmojisOffMode() {
        // Off mode preserves all repetitions untouched
        String off = TtsService.condenseRepeatedCharacters("Laugh 😂😂😂😂😂", VoiceSettings.REPEATED_CHARS_OFF);
        assertThat(off, is("Laugh 😂😂😂😂😂"));
    }

    @Test
    public void testSimplifyUrlsMixedCaseProtocols() {
        String simplified1 = TtsService.simplifyUrls("Visit Https://Example.com/page");
        assertThat(simplified1, containsString("Example.com"));
        assertThat(simplified1, not(containsString("Https://")));

        String simplified2 = TtsService.simplifyUrls("Go to HTTP://WWW.GITHUB.COM/test");
        assertThat(simplified2, containsString("GITHUB.COM"));
        assertThat(simplified2, not(containsString("HTTP://")));
        assertThat(simplified2, not(containsString("WWW.")));
    }
}
