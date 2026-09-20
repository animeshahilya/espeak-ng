# eSpeak NG — Android Fork

- [What this fork is](#what-this-fork-is)
- [What's different from upstream](#whats-different-from-upstream)
- [Features & Customization Options](#features--customization-options)
- [Building the Android app](#building-the-android-app)
- [Supported languages](docs/languages.md)
- [Relationship to upstream eSpeak NG](#relationship-to-upstream-espeak-ng)
- [License Information](#license-information)
----------

This is [animeshahilya](https://github.com/animeshahilya)'s fork of
[eSpeak NG](https://github.com/espeak-ng/espeak-ng), an open source formant
("Klatt") speech synthesizer supporting 100+ languages. **This repository is
maintained for the Android app only** — everything in `android/` is the
active focus. The rest of the tree (the core `espeak-ng`/`libespeak-ng` C
library, `src/`, `dictsource/`, `phsource/`, the CLI, the Windows/Linux/Mac
build paths) is kept because the Android app builds directly on top of it via
CMake, not because those other platforms are being developed here — for
Linux, Windows, SAPI5, or general library use, go to the
[upstream project](https://github.com/espeak-ng/espeak-ng) instead.

The Android app is a `TextToSpeechService` engine aimed at blind and
visually impaired users navigating with TalkBack: fast, fully offline, with
zero network dependency — every language eSpeak NG supports (100+) ships in
the APK, so there is nothing to download — and tuned for the accessibility
use case rather than general-purpose narration.

## What's different from upstream

Everything below lives in `android/` (Java service layer) or
`espeak-ng-data/lang/gmw/en-in` + `phsource/ph_english_in` (the one new
voice) — the core C synthesis engine itself is unmodified upstream code.

### Indian English (`en-in`)

Upstream eSpeak NG ships no Indian English voice at all. This fork adds one,
built on the base `en` phoneme table rather than copying a whole table
(an upstream attempt that did the latter,
[espeak-ng#1981](https://github.com/espeak-ng/espeak-ng/pull/1981), was
closed unmerged and covers less ground than this does). Grounded in Wells
1982, Sailaja 2009, Gargesh 2008, and the OED World Englishes Indian
English model:

* Alveolar `t`/`d` → retroflex `ʈ`/`ɖ` (`time` → `ʈaɪm`).
* Dental fricatives `θ`/`ð` → dental stops `t̪`/`d̪` (no interdental sound in
  most L1s).
* `w`/`v` merger → labiodental approximant `ʋ`.
* FACE/GOAT diphthongs `eɪ`/`oʊ` → monophthongs `eː`/`oː`.
* Clear `l` in all positions (no dark/velarized `ɫ`).
* Fully rhotic, and realized as an actual retroflex consonant after the
  vowel (`water` → `ʋɔːʈər`, not an American-style r-colored vowel) —
  including cross-word liaison (`water is` → `ʋɔːʈər ɪz`), matching how
  Sailaja 2009 describes IndE rhoticity as consonantal rather than a single
  r-colored vowel phone the way US English does it.

## Features & Customization Options

The app is built specifically for TalkBack and screen reader power users. The settings interface is organized into a clean, compact hierarchy (~15 root items) with dedicated sub-screens to eliminate scroll fatigue while keeping every parameter accessible.

### 1. Voice & Language

* **Supported Languages**: Choose which of the 159 bundled languages appear in your system TTS list. Features a fast search bar and select-all/clear-all toggles.
* **Voice Variants**: Select gender, age, or formant tone styles. Organized into clear categories:
  * *Standard Voices*: Default, Male (1–6), Female (1–6), Child, Aged.
  * *Klatt Formant Voices*: Cascade-parallel formant models (Klatt 1–6) for distinctive hardware-style synthesized timbre.
* **Preview Voice Sample**: Immediate one-tap speech test to evaluate voice, speed, pitch, and effect changes.

### 2. Voice Parameters & Prosody Tuning

* **Speech Rate & Rate Boost**: Precise seekbar control with live WPM announcements. Default range 80–450 WPM; enabling **Rate Boost** unlocks up to 3× multiplier (up to 750+ WPM) for high-speed screen reader scanning.
* **Pitch & Pitch Range (Inflection)**: Independently tune base vocal pitch and intonation range (inflection magnitude, 0 = monotone).
* **Intonation Style**:
  * *Natural (Standard)*: Preserves natural language pitch melodies and clause contours.
  * *Flat / High-Speed*: Flattens inflection (`espeakINTONATION=3`) for maximum phoneme clarity and intelligibility at extreme speech rates (350–500+ WPM).
  * *Expressive*: Heightened pitch dynamics and phrase modulation.
* **Emphasize Questions**: Automatically amplifies the pitch rise on interrogative (`?`) and exclamatory (`!`) sentences as an accessibility aid for hard-of-hearing listeners.
* **Volume**: 0% to 200% with extra headroom for low-volume device speakers.
* **Word Gap**: Custom pause duration between words (0–500 ms in 10 ms steps).
* **Audio Optimizer**: Multi-band tone-shaping equalizer and dynamics leveler tuned specifically for eSpeak formant synthesis:
  * *Gentle warmth*: Softens harsh sibilants and adds low-end body.
  * *Balanced (Recommended)*: Optimized 2.5–5 kHz presence boost for consonant intelligibility.
  * *Full richness*: Deep voice profile with expanded dynamic presence.

### 3. Number & Code Reading (`sub_number_reading`)

* **Smart OTP & PIN Verification**: Automatically detects verification codes (SMS OTPs, PINs, transaction references, PNRs) and reads them digit-by-digit (`"Your OTP is 4829"` &rarr; `"4 8 2 9"`) while leaving ordinary numbers intact.
* **OTP Length Window**: Configurable minimum length (2–8 digits) and maximum length (min to 12 digits, default 10 for Indian PNRs).
* **Digit Grouping**: Configurable cadence for long numbers:
  * *Off*: Standard whole numbers.
  * *Single*: Speaks numbers digit-by-digit.
  * *Double*: Reads numbers in pairs (e.g. `12 34 56`).
  * *Triple*: Reads numbers in triplets.
* **Triple-Grouping Threshold**: Configurable threshold (4–12 digits) before triplet grouping engages.
* **Recognize Roman Numerals**: Automatically identifies contextual Roman numerals (*"Chapter IV"* &rarr; *"Chapter 4"*, *"King Henry VIII"* &rarr; *"King Henry 8"*, *"World War II"* &rarr; *"World War 2"*, *"Section IX"* &rarr; *"Section 9"*).
* **Indian Numbering System**: Natural Indian grouping (`1,00,000` &rarr; *"1 lakh"*, `1,23,45,678` &rarr; *"1 crore 23 lakh 45678"*), shorthand quantities (`50k` &rarr; *"50 thousand"*, `5L` &rarr; *"5 lakh"*, `2cr` &rarr; *"2 crore"*), and rupee formatting (`₹1,00,000` &rarr; *"1 lakh rupees"*).
* **Natural Time & Date**: Intelligently reads clock times and formatted calendar dates without raw punctuation.
* **Currency Amounts**: Verbalizes currency symbols (`$`, `€`, `£`, `¥`, `₹`) into spoken currency units.

### 4. Pronunciation & Text Processing (`sub_text_processing`)

* **Speak Punctuation**: Granular symbol reading presets:
  * *None*: Clean reading without punctuation announcements.
  * *Some*: Essential sentence-shaping punctuation (`.,!?;:'"-`).
  * *Most*: Standard punctuation plus common inline symbols (`()[]{}/@#$%&*+=<>_~^|\`).
  * *All*: Every punctuation and symbol character.
  * *Custom*: User-defined string of exact punctuation characters to speak.
* **Capital Letter Indication**: How capital letters are flagged:
  * *None*: No cue.
  * *Sound icon*: Plays a subtle audio chime.
  * *Pitch rise*: Raises vocal pitch on capital letters.
  * *Speak 'capital'*: Verbalizes the word "capital".
* **Capital Announcement Scope**:
  * *Character navigation only (Default)*: Capital cues sound strictly when stepping through text character-by-character.
  * *All reading*: Capital cues apply across continuous reading, words, and sentences (indispensable for proofreading, editing, and code reading).
* **Condense Repeated Characters**:
  * *Off*: Reads every repeated character individually.
  * *Count repetitions*: Eliminates divider fatigue by announcing count and character name (`--------------------` &rarr; `"20 dashes"`, `********` &rarr; `"8 asterisks"`).
  * *Truncate*: Limits consecutive identical characters to at most 3 (`soooooooo` &rarr; `sooo`).
* **Simplify Web Addresses**: Cleans up URLs in chat and web browsing by dropping protocol (`http://`, `https://`) and `www.`, spacing slashes for natural pauses, and condensing lengthy tracking query parameters into `"with parameters"`.
* **Programming & Math Symbols**: Direct expansion for technical symbols (`!=`, `==`, `<=`, `>=`, `=>`, `->`, `&&`, `||`, `//`, `...`, `+-`, `*`, `/`, `~=`, `V`, `bullet`, `deg`, `sqrt`, `inf`, `integral`, `for all`, `exists`, `element of`, `union`, `intersection`).
* **Unicode Normalization (NFKC)**: Converts stylized social media Unicode (bold, italic, circled, fullwidth, math alphanumeric fonts) into readable plain text, preserving word-boundary highlighting for TalkBack.
* **Unified Reading Modes**:
  * *Normal*: Standard reading.
  * *Spelling*: Letter-by-letter reading.
  * *Phonetic*: Spells words using NATO phonetics (Alpha, Bravo, Charlie...).
  * *Code reading*: Announces programming symbols with full punctuation.
* **NATO Phonetic Spelling**: Spells isolated letters with NATO phonetics during single-character cursor navigation.
* **Devanagari Matras in Exploration**: Names silent combining vowel signs and marks (matras, halant, anusvara, visarga, nukta) when navigating by character.
* **Emoji Processing**:
  * *Announce*: Speaks emojis set off with clean pauses and commas using eSpeak's CLDR dictionary.
  * *Ignore*: Completely silences emojis to prevent conversational clutter.
* **Bilingual Script Switching**: Automatically splits mixed Latin and Indic text, synthesizing each run with its dedicated native voice (e.g., Hindi voice for Devanagari, English voice for Latin).
* **Second Language Voice**: Choose the exact voice used for English words inside Indic sentences.

### 5. Caller-Proof Locks (`sub_caller_locks`)

Prevents third-party apps (e.g. navigation, browsers, or social media apps) from altering your accessibility settings:
* **Lock Speech Rate**: Ignores caller app rate requests, maintaining your configured reading speed.
* **Lock Pitch**: Ignores caller app pitch requests.
* **Lock Volume**: Ignores caller app volume ducking, keeping speech consistently audible.

### 6. User Pronunciation Dictionary ("My Words")

Full NVDA-compatible custom pronunciation dictionary:
* **Rule Syntax**: Pattern &rarr; Replacement.
* **Filters & Matching**: Whole-word matching, case sensitivity, and full regular expression (regex) support.
* **Buckets**: Organizable into Main, Root, and Abbreviation sections.
* **Language Targeting**: Scope rules to a specific language code (`en`, `hi`, `en-in`) or apply globally.
* **Background Import/Export**: Import massive `.dic` word lists or JSON files without UI freezes; export anytime.

### 7. Tools, Backup & Diagnostics (`sub_tools_backup`)

* **Reading Style Presets**: Quick-apply curated default profiles (e.g. Recommended High-Speed Screen Reader style).
* **Backup & Restore**: Single JSON export/import of all voice parameters, locks, preferences, and dictionary rules.
* **Log Exporter**: One-tap diagnostic report gathering Android version, device model, app preferences snapshot, and recent logs for troubleshooting without requiring storage permissions.
* **Custom Voice Import**: Install external voice and phoneme files directly into your personal voice library.

### Every language bundled, nothing to download

All 159 languages eSpeak NG data ships with are packed directly into the
APK, selectable from first launch with no network step (the app requests
no `INTERNET` permission and makes no network calls at all).

### Klatt formant voices

The 6 upstream cascade-parallel formant models (Klatt 1-6), selectable from
the Voice variant picker's Klatt category.

### Reliability & Direct-Boot storage

* All voice data and preferences strictly reside in Android Direct-Boot
  device-protected storage (`/data/user_de/`), so TalkBack stays functional
  before the device is unlocked.
* Missing ISO 3166-1 alpha-3 locale mappings (`AUS`, `CAN`, `IND`, `SGP`)
  added to the JNI locale translator for correct Android TTS locale
  matching.
* Voice-data extraction is guarded against concurrent callers (the TTS
  framework binding the service, the settings screen, and a manual
  reinstall can all independently decide extraction is needed) and against
  leaving a half-extracted tree that would pass validation.

## Building the Android app

See [`android/CLAUDE.md`](android/CLAUDE.md) for the full build/architecture
reference (data pipeline, signing, JNI layer, testing). Short version:

```bash
cd android
./gradlew assembleDebug     # debug APK, always builds
./gradlew assembleRelease   # release APK; signed if android/keystore.properties
                             # exists, unsigned otherwise (see CLAUDE.md)
```

Output APKs land in `android/build/outputs/apk/{debug,release}/`.
Requires JDK 17, Android SDK with compileSdk/targetSdk 37, NDK 29.0.14206865
and CMake 3.30.0 (pinned in `android/build.gradle`).

## Relationship to upstream eSpeak NG

Everything outside `android/` — the core synthesis engine, phoneme/dictionary
sources, and the CLI/library build — is upstream eSpeak NG, periodically
synced from [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng).
eSpeak NG itself is a compact, formant-based ("Klatt") text-to-speech
synthesizer supporting more than 100 languages, originally based on the
eSpeak engine by Jonathan Duddington and maintained as eSpeak NG by Reece H.
Dunn and contributors since 2015. For the general-purpose library, CLI,
Windows SAPI5 build, or the full list of supported languages and platforms,
see the upstream README and [documentation index](docs/index.md).

## License Information

eSpeak NG Text-to-Speech is released under the [GPL version 3](COPYING) or
later license.

The `getopt.c` compatibility implementation for getopt support on Windows is
taken from the NetBSD `getopt_long` implementation, which is licensed under a
[2-clause BSD](COPYING.BSD2) license.

Android is a trademark of Google LLC.
