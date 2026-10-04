package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.Character.UnicodeScript;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extensive tests for multi-language and multi-script switching across:
 * - eSpeak (native formant synthesis)
 * - Piper (standard phoneme-based neural voices)
 * - SYSPIN (Indic Coqui VITS voices, 22050 Hz, character/text based)
 * - Rasa (AI4Bharat / tinisoft Indic neural voices, 24000 Hz, character/text based)
 */
public class LanguageSwitchingTest {

    private static String describe(List<LanguageRuns.Run> runs) {
        final StringBuilder sb = new StringBuilder();
        for (LanguageRuns.Run r : runs) {
            sb.append(r.language).append('@').append(r.start).append('[').append(r.text).append(']');
        }
        return sb.toString();
    }

    // =========================================================================
    // 1. Cross-Engine 4-way Mixed Sentences (eSpeak, Piper, SYSPIN, Rasa)
    // =========================================================================

    @Test
    public void fourWaySwitch_Piper_Syspin_Rasa_Espeak() {
        // Piper (Latin/English) + SYSPIN (Devanagari/Hindi) + Rasa (Tamil) + eSpeak (Cyrillic/Russian)
        final String text = "Hello friend, नमस्ते दोस्त, வணக்கம் நண்பா, Привет друг!";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "eng");

        // Verify full text is preserved without character loss
        final StringBuilder reconstructed = new StringBuilder();
        for (LanguageRuns.Run r : runs) {
            reconstructed.append(r.text);
        }
        assertEquals(text, reconstructed.toString());

        // Verify routing to respective language engines:
        // eng -> Piper
        // hin -> SYSPIN (or Rasa Hindi)
        // tam -> Rasa Tamil
        // rus -> eSpeak Russian
        assertEquals("eng@0[Hello friend,]hin@13[ नमस्ते दोस्त,]tam@27[ வணக்கம் நண்பா,]rus@42[ Привет друг!]",
                describe(runs));
    }

    @Test
    public void fourWaySwitch_RasaPrimary_Tam_Hin_Eng_Ell() {
        // Primary voice is Rasa Tamil: switches to SYSPIN (Hindi), Piper (English), eSpeak (Greek)
        final String text = "வணக்கம், नमस्ते, Hello, Γεια σου";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "tam");

        assertEquals("tam@0[வணக்கம்,]hin@8[ नमस्ते,]eng@16[ Hello,]ell@23[ Γεια σου]",
                describe(runs));
    }

    @Test
    public void fourWaySwitch_SyspinPrimary_Hin_Ben_Kan_Eng() {
        // Primary voice is SYSPIN Hindi: switches to Bengali, Kannada, English
        final String text = "नमस्ते, হ্যালো, ನಮಸ್ಕಾರ, Goodbye";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "hin");

        assertEquals("hin@0[नमस्ते,]ben@7[ হ্যালো,]kan@15[ ನಮಸ್ಕಾರ,]eng@24[ Goodbye]",
                describe(runs));
    }

    // =========================================================================
    // 2. SYSPIN Indic Voices Switching with English & Numbers
    // =========================================================================

    @Test
    public void syspinHindi_EmbeddedEnglishAndTechTokens() {
        // SYSPIN Hindi speaking text with Latin brand names, technical tokens, and prices
        final String text = "मेरा फ़ोन Samsung Galaxy S25 है और इसमें 5G सपोर्ट है।";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "hin");

        // Samsung Galaxy S25 and 5G must be routed to English (Piper / eSpeak)
        assertEquals("hin@0[मेरा फ़ोन]eng@9[ Samsung Galaxy S25]hin@28[ है और इसमें]eng@40[ 5G]hin@43[ सपोर्ट है।]",
                describe(runs));
    }

    @Test
    public void syspinMarathi_DevanagariStaysMarathi() {
        // Marathi is in Devanagari script: must remain Marathi, NOT incorrectly switch to Hindi
        final String text = "माझा फोन Samsung आहे आणि किंमत ₹15,000 आहे.";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "mar");

        assertEquals("mar@0[माझा फोन]eng@8[ Samsung]mar@16[ आहे आणि किंमत ₹15,000 आहे.]",
                describe(runs));
    }

    @Test
    public void syspinBengali_EmbeddedEnglishWords() {
        final String text = "আজকের Weather খুব সুন্দর এবং Temperature 28°C.";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "ben");

        assertEquals("ben@0[আজকের]eng@5[ Weather]ben@13[ খুব সুন্দর এবং]eng@28[ Temperature 28°C.]",
                describe(runs));
    }

    @Test
    public void syspinTelugu_EmbeddedEnglishWords() {
        final String text = "ఈ రోజు Google Office లో meeting ఉంది.";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "tel");

        assertEquals("tel@0[ఈ రోజు]eng@6[ Google Office]tel@20[ లో]eng@23[ meeting]tel@31[ ఉంది.]",
                describe(runs));
    }

    // =========================================================================
    // 3. Rasa Indic Voices Switching with English & Numbers
    // =========================================================================

    @Test
    public void rasaTamil_EmbeddedEnglishAndPunctuation() {
        final String text = "நான் YouTube இல் புதிய video பார்த்தேன்.";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "tam");

        assertEquals("tam@0[நான்]eng@4[ YouTube]tam@12[ இல் புதிய]eng@22[ video]tam@28[ பார்த்தேன்.]",
                describe(runs));
    }

    @Test
    public void rasaKannada_DigitsSplitToEspeak() {
        // Kannada text voice (Rasa / dhanush) does not have ICU number words.
        // Digits must be extracted to "zxx" (eSpeak-only) so they are spoken by eSpeak.
        final LanguageRuns.Run run = new LanguageRuns.Run(0, "ಬೆಂಗಳೂರಿನಲ್ಲಿ ತಾಪಮಾನ 28 ಡಿಗ್ರಿ ಆಗಿದೆ", "kan");
        final List<LanguageRuns.Run> withDigits = LanguageRuns.splitDigits(run, "zxx");

        assertEquals("kan@0[ಬೆಂಗಳೂರಿನಲ್ಲಿ ತಾಪಮಾನ]zxx@20[ 28]kan@23[ ಡಿಗ್ರಿ ಆಗಿದೆ]",
                describe(withDigits));
    }

    @Test
    public void rasaMalayalam_EmbeddedEnglish() {
        final String text = "എന്റെ പുതിയ Laptop വളരെ fast ആണ്.";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "mal");

        assertEquals("mal@0[എന്റെ പുതിയ]eng@11[ Laptop]mal@18[ വളരെ]eng@23[ fast]mal@28[ ആണ്.]",
                describe(runs));
    }

    @Test
    public void rasaGujarati_StaysGujarati() {
        final String text = "કેમ છો, મજામાં?";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "guj");

        assertEquals("guj@0[કેમ છો, મજામાં?]", describe(runs));
    }

    // =========================================================================
    // 4. ICU Spellout Support vs eSpeak Digit Fallback
    // =========================================================================

    @Test
    public void digitFallbackRoutingForLanguagesWithoutIcu() {
        // On JVM/Android without numberWords for Kannada, splitDigits routes digits cleanly to "zxx"
        final LanguageRuns.Run knRun = new LanguageRuns.Run(5, "ಬೆಲೆ 450 ರೂಪಾಯಿ", "kan");
        final List<LanguageRuns.Run> knSplit = LanguageRuns.splitDigits(knRun, "zxx");
        assertEquals("kan@5[ಬೆಲೆ]zxx@9[ 450]kan@13[ ರೂಪಾಯಿ]", describe(knSplit));

        // Telugu without numberWords: digits to zxx
        final LanguageRuns.Run teRun = new LanguageRuns.Run(0, "సమయం 12:45 అయ్యింది", "tel");
        final List<LanguageRuns.Run> teSplit = LanguageRuns.splitDigits(teRun, "zxx");
        assertEquals("tel@0[సమయం]zxx@4[ 12:45]tel@10[ అయ్యింది]", describe(teSplit));
    }

    // =========================================================================
    // 5. User Script Preferences (ScriptLanguages.chosen)
    // =========================================================================

    @Test
    public void userChosenScriptLanguageRouting() {
        final Map<UnicodeScript, String> chosen = new EnumMap<>(UnicodeScript.class);
        // User chose Marathi for Devanagari, Urdu for Arabic, Ukrainian for Cyrillic
        chosen.put(UnicodeScript.DEVANAGARI, "mar");
        chosen.put(UnicodeScript.ARABIC, "urd");
        chosen.put(UnicodeScript.CYRILLIC, "ukr");

        final String text = "Hello नमस्कार شکریہ Привіт";
        final List<LanguageRuns.Run> runs = LanguageRuns.split(text, "eng", chosen);

        assertEquals("eng@0[Hello]mar@5[ नमस्कार]urd@13[ شکریہ]ukr@19[ Привіт]",
                describe(runs));
    }

    // =========================================================================
    // 6. Resampling Verification Across Engine Sample Rates
    // =========================================================================

    @Test
    public void resamplerConversionsAcrossEngines() {
        // eSpeak/Piper/SYSPIN = 22050 Hz
        // Rasa = 24000 Hz
        // Compact/Mobile = 16000 Hz

        // 1. Rasa (24000 Hz) -> eSpeak/Piper/SYSPIN (22050 Hz)
        final PcmResampler r24to22 = new PcmResampler(24000, 22050);
        final short[] in24 = new short[2400]; // 100 ms of audio
        for (int i = 0; i < in24.length; i++) {
            in24[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / 24000.0));
        }
        final short[] out22 = r24to22.process(in24);
        final short[] flushed22 = r24to22.flush();
        final int total22 = out22.length + flushed22.length;
        // 2400 * 22050 / 24000 = 2205 samples
        assertEquals(2205, total22);

        // 2. eSpeak/Piper/SYSPIN (22050 Hz) -> Rasa (24000 Hz)
        final PcmResampler r22to24 = new PcmResampler(22050, 24000);
        final short[] in22 = new short[2205]; // 100 ms of audio
        for (int i = 0; i < in22.length; i++) {
            in22[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / 22050.0));
        }
        final short[] out24 = r22to24.process(in22);
        final short[] flushed24 = r22to24.flush();
        final int total24 = out24.length + flushed24.length;
        // 2205 * 24000 / 22050 = 2400 samples
        assertEquals(2400, total24);
    }

    // =========================================================================
    // 7. Alphanumeric Compounds and Edge Cases
    // =========================================================================

    @Test
    public void alphanumericCompoundsAcrossScripts() {
        // Hindi + "COVID-19" + Hindi
        assertEquals("hin@0[यह]eng@2[ COVID-19]hin@11[ का मामला है]",
                describe(LanguageRuns.split("यह COVID-19 का मामला है", "hin")));

        // Tamil + "MP3" + Tamil
        assertEquals("tam@0[இது ஒரு]eng@7[ MP3]tam@11[ கோப்பு]",
                describe(LanguageRuns.split("இது ஒரு MP3 கோப்பு", "tam")));

        // Bengali + "4K" + Bengali
        assertEquals("ben@0[এই]eng@2[ 4K]ben@5[ ভিডিওটি দেখুন]",
                describe(LanguageRuns.split("এই 4K ভিডিওটি দেখুন", "ben")));
    }
}
