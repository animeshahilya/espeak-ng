package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.Character.UnicodeScript;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Language runs by writing system, as eSpeak switches by alphabet. */
public class LanguageRunsTest {

    private static String describe(List<LanguageRuns.Run> runs) {
        final StringBuilder sb = new StringBuilder();
        for (LanguageRuns.Run r : runs) {
            sb.append(r.language).append('@').append(r.start).append('[').append(r.text).append(']');
        }
        return sb.toString();
    }

    @Test
    public void languagesKeepTheirOwnScripts() {
        // Each language's own script stays with it (one run), never a stand-in
        assertEquals("srp@0[Dobar dan, kako ste?]", describe(LanguageRuns.split("Dobar dan, kako ste?", "srp")));
        assertEquals("srp@0[Добар дан]", describe(LanguageRuns.split("Добар дан", "srp")));
        assertEquals("grc@0[Ἐν ἀρχῇ ἦν ὁ λόγος]", describe(LanguageRuns.split("Ἐν ἀρχῇ ἦν ὁ λόγος", "grc")));
        assertEquals("hyw@0[Բարեւ]", describe(LanguageRuns.split("Բարեւ", "hyw")));
        assertEquals("cmn@0[你好]", describe(LanguageRuns.split("你好", "cmn")));
        assertEquals("nog@0[Салам]", describe(LanguageRuns.split("Салам", "nog")));
        assertEquals("chr@0[ᎣᏏᏲ]", describe(LanguageRuns.split("ᎣᏏᏲ", "chr")));
        assertEquals("bpy@0[মণিপুরী]", describe(LanguageRuns.split("মণিপুরী", "bpy")));
        assertEquals("snd@0[सिंधी]", describe(LanguageRuns.split("सिंधी", "snd")));
        assertEquals("uzb@0[Салом]", describe(LanguageRuns.split("Салом", "uzb")));
    }

    @Test
    public void scriptVariantVoices() {
        final java.util.Locale l = java.util.Locale.ROOT;
        assertTrue(new Voice("fa-latn", "ira/fa-Latn", 0, 0, l).isScriptVariant());
        assertTrue(new Voice("cmn-latn-pinyin", "sit/cmn-Latn-pinyin", 0, 0, l).isScriptVariant());
        assertTrue(new Voice("en-shaw", "art/en-shaw", 0, 0, l).isScriptVariant());
        assertTrue(new Voice("chr-US-Qaaa-x-west", "iro/chr", 0, 0, l).isScriptVariant());
        assertFalse(new Voice("en-us-nyc", "gmw/en-US-nyc", 0, 0, l).isScriptVariant());
        assertFalse(new Voice("ps-x-northwest", "ira/ps", 0, 0, l).isScriptVariant());
        assertFalse(new Voice("hy-arevela", "ine/hy", 0, 0, l).isScriptVariant());
        assertFalse(new Voice("en-gb-scotland", "gmw/en-GB-scotland", 0, 0, l).isScriptVariant());
    }

    @Test
    public void kurmanjiIsLatinScript() {
        // Kurmanji text stays with the Kurmanji voice; it is not "foreign" Latin
        assertEquals("kur@0[Ez ê sibê biçim malê.]",
                describe(LanguageRuns.split("Ez ê sibê biçim malê.", "kur")));
    }

    @Test
    public void hindiInsideEnglishGoesToHindi() {
        assertEquals("eng@0[Hello,]hin@6[ नमस्ते दोस्त]eng@19[ how are you?]",
                describe(LanguageRuns.split("Hello, नमस्ते दोस्त how are you?", "eng")));
    }

    @Test
    public void englishInsideHindiGoesToEnglish() {
        assertEquals("hin@0[मेरा फ़ोन]eng@9[ Samsung]hin@17[ है।]",
                describe(LanguageRuns.split("मेरा फ़ोन Samsung है।", "hin")));
    }

    /** Marathi is written in Devanagari: it stays Marathi, not Hindi. */
    @Test
    public void ownScriptStaysWithTheLanguage() {
        assertEquals("mar@0[नमस्कार, कसे आहात?]",
                describe(LanguageRuns.split("नमस्कार, कसे आहात?", "mar")));
    }

    /** Digits and punctuation never split a run on their own. */
    @Test
    public void digitsAndPunctuationFollowTheirRun() {
        assertEquals("eng@0[Call 123-456, now!]",
                describe(LanguageRuns.split("Call 123-456, now!", "eng")));
        assertEquals("eng@0[42 !!]", describe(LanguageRuns.split("42 !!", "eng")));
    }

    @Test
    public void runsRejoinToTheText() {
        final String text = "Tamil வணக்கம் and Hindi नमस्ते, done.";
        final StringBuilder joined = new StringBuilder();
        for (LanguageRuns.Run r : LanguageRuns.split(text, "eng")) {
            joined.append(r.text);
        }
        assertEquals(text, joined.toString());
        assertEquals(5, LanguageRuns.split(text, "eng").size());
    }

    /** Code point offsets, not UTF-16: emoji before a switch must not shift it. */
    @Test
    public void startsAreCodePointOffsets() {
        final List<LanguageRuns.Run> runs = LanguageRuns.split("Hi 😀 नमस्ते", "eng");
        assertEquals(2, runs.size());
        assertEquals(4, runs.get(1).start); // "Hi", space, emoji, then the space before नमस्ते
    }

    /** A language the user chose for a script replaces eSpeak's. */
    @Test
    public void chosenLanguageReplacesEspeaks() {
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        chosen.put(UnicodeScript.DEVANAGARI, "mar");
        chosen.put(UnicodeScript.CYRILLIC, "ukr");
        assertEquals("eng@0[Hello]mar@5[ नमस्कार]eng@13[ and]ukr@17[ Привіт]",
                describe(LanguageRuns.split("Hello नमस्कार and Привіт", "eng", chosen)));
        // A script the speaking language is written in still stays with it.
        assertEquals("hin@0[नमस्ते]", describe(LanguageRuns.split("नमस्ते", "hin", chosen)));
    }

    /**
     * Numbers and times with a language of their own: digits, and the colon
     * between them, become a run in it; unset, they stay with their words.
     */
    @Test
    public void numbersCanHaveTheirOwnLanguage() {
        final Map<UnicodeScript, String> none = new EnumMap<>(UnicodeScript.class);
        assertEquals("hin@0[मीटिंग]eng@6[ 10:30]hin@12[ बजे]",
                describe(LanguageRuns.split("मीटिंग 10:30 बजे", "hin", none, "eng")));
        assertEquals("eng@0[call at]hin@7[ 5]eng@9[ pm]",
                describe(LanguageRuns.split("call at 5 pm", "eng", none, "hin")));
        assertEquals("hin@0[मीटिंग 10:30 बजे]",
                describe(LanguageRuns.split("मीटिंग 10:30 बजे", "hin", none, null)));
    }

    /** Scripts eSpeak has no language for get the chosen one. */
    @Test
    public void chosenLanguageCoversNewScripts() {
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        chosen.put(UnicodeScript.ETHIOPIC, "amh");
        assertEquals("eng@0[Say]amh@3[ ሰላም]", describe(LanguageRuns.split("Say ሰላም", "eng", chosen)));
        assertEquals("eng@0[Say ሰላም]", describe(LanguageRuns.split("Say ሰላም", "eng")));
    }

    /** A voice without number words: its digits go to eSpeak, offsets stay in the request. */
    @Test
    public void digitsSplitOutOfARun() {
        final LanguageRuns.Run run = new LanguageRuns.Run(4, "ಸಮಯ 10:30 ಆಗಿದೆ", "kan");
        assertEquals("kan@4[ಸಮಯ]zxx@7[ 10:30]kan@13[ ಆಗಿದೆ]",
                describe(LanguageRuns.splitDigits(run, "zxx")));
        final LanguageRuns.Run plain = new LanguageRuns.Run(0, "ಬಟನ್", "kan");
        assertEquals("kan@0[ಬಟನ್]", describe(LanguageRuns.splitDigits(plain, "zxx")));
    }

    /** Opening brackets and quotes go with the words they open. */
    @Test
    public void openingMarksJoinTheFollowingRun() {
        assertEquals("eng@0[Song: Tum]hin@9[ हो]eng@12[ (Arijit Singh)]",
                describe(LanguageRuns.split("Song: Tum हो (Arijit Singh)", "eng")));
        assertEquals("hin@0[उसने कहा]eng@8[ \"Hello\"]",
                describe(LanguageRuns.split("उसने कहा \"Hello\"", "hin")));
        // A quote inside a word (don't) is not an opening mark.
        assertEquals("hin@0[हाँ]eng@3[ don't]",
                describe(LanguageRuns.split("हाँ don't", "hin")));
        // Guillemets and angular quote marks stay with the opened phrase.
        assertEquals("eng@0[Book:]rus@5[ «Война и мир»]",
                describe(LanguageRuns.split("Book: «Война и мир»", "eng")));
    }

    /** Additional non-Latin scripts stay with their speaking language. */
    @Test
    public void additionalScriptsStayWithSpeakingLanguage() {
        assertEquals("kas@0[سلام]", describe(LanguageRuns.split("سلام", "kas")));
        assertEquals("amh@0[ሰላም]", describe(LanguageRuns.split("ሰላም", "amh")));
        assertEquals("bod@0[བཀྲ་ཤིས་བདེ་ལེགས།]", describe(LanguageRuns.split("བཀྲ་ཤིས་བདེ་ལེགས།", "bod")));
    }

    /** Glued alphanumeric tokens like 1st, 4G, 10am join the Latin run as whole units. */
    @Test
    public void gluedAlphanumericTokensJoinLatinRun() {
        // "1st" joins English whole so English pronounces "first"
        assertEquals("hin@0[आज]eng@2[ 1st]hin@6[ तारीख है]",
                describe(LanguageRuns.split("आज 1st तारीख है", "hin")));
        // "4G" joins English whole
        assertEquals("hin@0[मेरा फ़ोन]eng@9[ 4G]hin@12[ सपोर्ट करता है]",
                describe(LanguageRuns.split("मेरा फ़ोन 4G सपोर्ट करता है", "hin")));
        // "10am" joins English whole
        assertEquals("hin@0[मीटिंग]eng@6[ 10am]hin@11[ को है]",
                describe(LanguageRuns.split("मीटिंग 10am को है", "hin")));
        // "10:30am" joins English whole
        assertEquals("hin@0[मीटिंग]eng@6[ 10:30am]hin@14[ पर है]",
                describe(LanguageRuns.split("मीटिंग 10:30am पर है", "hin")));
        // Plain numbers without Latin letters still stay with surrounding text
        assertEquals("hin@0[आज 1 तारीख है]",
                describe(LanguageRuns.split("आज 1 तारीख है", "hin")));
        // Number separated by space from Latin word stays with surrounding text
        assertEquals("hin@0[फ़ोन 12345]eng@10[ hai]",
                describe(LanguageRuns.split("फ़ोन 12345 hai", "hin")));
        // Currency and compound numeric tokens: "$50k", "15%off", "10,000rpm", "3.5GHz"
        assertEquals("hin@0[यहाँ]eng@4[ $50k]hin@9[ इनाम है]",
                describe(LanguageRuns.split("यहाँ $50k इनाम है", "hin")));
        assertEquals("hin@0[फ्लैट]eng@5[ 15%off]hin@12[ डिस्काउंट]",
                describe(LanguageRuns.split("फ्लैट 15%off डिस्काउंट", "hin")));
        assertEquals("hin@0[स्पीड]eng@5[ 10,000rpm]hin@15[ है]",
                describe(LanguageRuns.split("स्पीड 10,000rpm है", "hin")));
        assertEquals("hin@0[क्लॉक]eng@5[ 3.5GHz]hin@12[ है]",
                describe(LanguageRuns.split("क्लॉक 3.5GHz है", "hin")));
    }

    /** Two-letter ISO 639-1 language codes work interchangeably with three-letter codes. */
    @Test
    public void twoLetterLanguageCodesSupported() {
        assertEquals("hi@0[मेरा फ़ोन]eng@9[ Samsung]hi@17[ है।]",
                describe(LanguageRuns.split("मेरा फ़ोन Samsung है।", "hi")));
        assertEquals("mr@0[नमस्कार, कसे आहात?]",
                describe(LanguageRuns.split("नमस्कार, कसे आहात?", "mr")));
        assertEquals("en@0[Hello,]hin@6[ नमस्ते दोस्त]en@19[ how are you?]",
                describe(LanguageRuns.split("Hello, नमस्ते दोस्त how are you?", "en")));
        assertEquals("hin", LanguageRuns.standIn("hi"));
        assertEquals("hin", LanguageRuns.standIn("mr"));
        assertEquals("ara", LanguageRuns.standIn("ar"));
    }

    @Test
    public void handlesAndHashtagsJoinFollowedRun() {
        assertEquals("hin@0[ट्विटर पर]eng@9[ @alex]hin@15[ को फॉलो करें]",
                describe(LanguageRuns.split("ट्विटर पर @alex को फॉलो करें", "hin")));
        assertEquals("hin@0[ट्रेंडिंग]eng@9[ #news]hin@15[ आज का]",
                describe(LanguageRuns.split("ट्रेंडिंग #news आज का", "hin")));
    }

    @Test
    public void hyphenatedAlphanumericCompoundsJoinLatinRun() {
        assertEquals("hin@0[फ्लैट]eng@5[ 12-A]hin@10[ में]",
                describe(LanguageRuns.split("फ्लैट 12-A में", "hin")));
        assertEquals("hin@0[फ़िल्म]eng@6[ 3-D]hin@10[ में]",
                describe(LanguageRuns.split("फ़िल्म 3-D में", "hin")));
    }

    @Test
    public void koreanHanjaStaysWithKorean() {
        assertEquals("kor@0[大韓民國 대한민국]",
                describe(LanguageRuns.split("大韓民國 대한민국", "kor")));
    }

    @Test
    public void greekScriptSwitchesToGreek() {
        assertEquals("eng@0[Greek:]ell@6[ Ελληνικά]",
                describe(LanguageRuns.split("Greek: Ελληνικά", "eng")));
    }

    @Test
    public void arabicInsideEnglishGoesToArabic() {
        assertEquals("eng@0[Hello,]ara@6[ مرحبا صديقي]eng@18[ how are you?]",
                describe(LanguageRuns.split("Hello, مرحبا صديقي how are you?", "eng")));
    }

    @Test
    public void englishInsideArabicGoesToEnglish() {
        assertEquals("ara@0[مرحبا]eng@5[ Google]ara@12[ كيف حالك]",
                describe(LanguageRuns.split("مرحبا Google كيف حالك", "ara")));
    }

    @Test
    public void arabicVocalizedTashkeelStaysArabic() {
        assertEquals("ara@0[مَرْحَبًا بِكَ]",
                describe(LanguageRuns.split("مَرْحَبًا بِكَ", "ara")));
    }

    @Test
    public void languageIsToldApartWithinItsScript() {
        // Letters only one language of the script uses decide it; otherwise
        // the script's usual language stays.
        assertEquals("ukr@0[Привіт, як справи?]eng@18[ means hello.]",
                describe(LanguageRuns.split("Привіт, як справи? means hello.", "eng")));
        assertEquals("rus@0[Здравствуйте]eng@12[ is Russian.]",
                describe(LanguageRuns.split("Здравствуйте is Russian.", "eng")));
        assertEquals("srp@0[Ђорђе]", describe(LanguageRuns.split("Ђорђе", "eng")));
        assertEquals("kaz@0[Қазақстан]", describe(LanguageRuns.split("Қазақстан", "eng")));
        assertEquals("bel@0[Мінск, ўсё]", describe(LanguageRuns.split("Мінск, ўсё", "eng")));
        assertEquals("asm@0[অসমৰ]", describe(LanguageRuns.split("অসমৰ", "eng")));
        // Urdu also writes Persian's ی ک پ: one ے or ہ outweighs them.
        assertEquals("eng@0[Urdu:]urd@5[ میں آپ کا شکریہ ادا کرتا ہوں]",
                describe(LanguageRuns.split("Urdu: میں آپ کا شکریہ ادا کرتا ہوں", "eng")));
        assertEquals("fas@0[حال شما چطور است]", describe(LanguageRuns.split("حال شما چطور است", "eng")));
        assertEquals("pus@0[زه ښه یم]", describe(LanguageRuns.split("زه ښه یم", "eng")));
        assertEquals("ara@0[شكرا جزيلا]", describe(LanguageRuns.split("شكرا جزيلا", "eng")));
        assertEquals("ara@0[مرحبا]", describe(LanguageRuns.split("مرحبا", "eng")));
    }

    @Test
    public void hanFollowsKanaAndHangul() {
        assertEquals("jpn@0[東京タワー]", describe(LanguageRuns.split("東京タワー", "eng")));
        assertEquals("jpn@0[東京]eng@2[ and]jpn@6[ すし]",
                describe(LanguageRuns.split("東京 and すし", "eng")));
        assertEquals("kor@0[漢字]eng@2[ and]kor@6[ 한국어]",
                describe(LanguageRuns.split("漢字 and 한국어", "eng")));
        assertEquals("eng@0[Shop at]zho@7[ 東京]", describe(LanguageRuns.split("Shop at 東京", "eng")));
    }

    @Test
    public void aChosenScriptLanguageIsNotSecondGuessed() {
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        chosen.put(UnicodeScript.CYRILLIC, "rus");
        assertEquals("rus@0[Привіт]", describe(LanguageRuns.split("Привіт", "eng", chosen)));
    }

    @Test
    public void espeakNeedsItsOwnVoiceOnlyWhereItWouldNotSwitch() {
        // eSpeak switches these scripts itself.
        assertTrue(LanguageRuns.espeakReadsItself("hin", "eng", null));
        assertTrue(LanguageRuns.espeakReadsItself("eng", "hin", null));
        assertTrue(LanguageRuns.espeakReadsItself("ara", "eng", null));
        assertTrue(LanguageRuns.espeakReadsItself("zxx", "tam", null));
        // It spells Cyrillic, Telugu and Thai letter by letter, and reads
        // Marathi, Urdu or Hindi-in-Marathi with the wrong rules.
        assertFalse(LanguageRuns.espeakReadsItself("rus", "eng", null));
        assertFalse(LanguageRuns.espeakReadsItself("tel", "eng", null));
        assertFalse(LanguageRuns.espeakReadsItself("mar", "eng", null));
        assertFalse(LanguageRuns.espeakReadsItself("urd", "eng", null));
        assertFalse(LanguageRuns.espeakReadsItself("hin", "mar", null));
        // A script language the user chose is set in eSpeak itself.
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        chosen.put(UnicodeScript.CYRILLIC, "ukr");
        assertTrue(LanguageRuns.espeakReadsItself("ukr", "eng", chosen));
    }
}
