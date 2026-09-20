# eSpeak NG — Android

[![Android CI](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](COPYING)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](android/)
[![Languages: 159](https://img.shields.io/badge/Languages-159%20Bundled-brightgreen.svg)](android/)
[![Offline](https://img.shields.io/badge/Network-100%25%20Offline-orange.svg)](android/)

A fast, lightweight, and fully offline formant-based (`TextToSpeechService`) speech synthesis engine for Android. Tailored specifically for blind and visually impaired screen reader users (TalkBack, Jieshuo, Commentary), power users, and language learners.

Every language, voice, and dictionary is packed directly into the APK. The engine operates **100% offline** with **zero network permissions**, zero analytics, and instant Direct-Boot availability before device unlock.

---

## Table of Contents

- [Core Highlights](#core-highlights)
- [Complete Feature Guide](#complete-feature-guide)
  - [1. Voice & Language Management](#1-voice--language-management)
  - [2. Voice Parameters & Prosody Tuning](#2-voice-parameters--prosody-tuning)
  - [3. Audio Optimizer (DSP Tone Shaping)](#3-audio-optimizer-dsp-tone-shaping)
  - [4. Smart Number & Code Reading](#4-smart-number--code-reading)
  - [5. Pronunciation & Text Processing](#5-pronunciation--text-processing)
  - [6. Caller-Proof Accessibility Locks](#6-caller-proof-accessibility-locks)
  - [7. User Pronunciation Dictionary ("My Words")](#7-user-pronunciation-dictionary-my-words)
  - [8. Reading Presets, Backup & Diagnostics](#8-reading-presets-backup--diagnostics)
  - [9. Accessibility & UI Polish](#9-accessibility--ui-polish)
  - [10. Android System Architecture](#10-android-system-architecture)
- [Indian English (`en-in`)](#indian-english-en-in)
- [Building the App](#building-the-app)
- [Testing](#testing)
- [License Information](#license-information)

---

## Core Highlights

* **159 Bundled Languages**: Zero downloads required. Every supported voice ships inside the APK.
* **100% Offline & Private**: Requests no `INTERNET` permission and makes zero network connections.
* **Direct-Boot Compatible**: Operates in device-protected storage (`/data/user_de/`), ensuring screen readers speak immediately on boot before entering your PIN/pattern/password.
* **Ultra-Fast & Responsive**: Formant synthesis starts speaking instantly with near-zero latency, negligible battery drain, and minuscule RAM consumption.
* **High-Speed Screen Reading**: Unlocks speech rates up to 750+ WPM via Sonic acceleration, paired with flat-intonation intelligibility modes.
* **Intelligent Preprocessing**: Built-in context-aware OTP detection, Indian numbering, Roman numeral expansion, repeated character collapsing, URL cleanup, and math/code reading.

---

## Complete Feature Guide

### 1. Voice & Language Management

* **159 Bundled Languages & Accents**: Broad coverage across global, regional, and indigenous languages.
* **Native Language Display**: Voice names are shown in the device's native language first with English names in parentheses (e.g. *"हिन्दी (Hindi)"*, *"Español (Spanish)"*), keeping regional variants distinct.
* **Supported Languages Picker**: Custom multi-select dialog with live search filter, count indicators, and Select-All / Clear-All toggles to unclutter the system TTS list.
* **Voice Variants**: Extensive gender, age, and formant style selections:
  * *Standard*: Default, Male (1–6), Female (1–6), Child, Aged.
  * *Klatt Formant Models*: Cascade-parallel formant synthesizers (Klatt 1–6) delivering distinct, classic hardware synthesizer timbres.
* **Bilingual Script Switching**: Automatically separates mixed Indic and Latin scripts, routing each run to its respective native synthesizer voice (e.g., Hindi voice for Devanagari, English voice for Latin words).
* **Secondary Language Voice Picker**: Select the specific voice used to synthesize Latin/English words occurring within non-English sentences.
* **External Voice Importer**: Direct storage-picker import to load external voice files or custom language archives into personal voice storage.
* **One-Tap Preview Sample**: Audition current voice, variant, pitch, speed, and audio processing configurations in real time.

---

### 2. Voice Parameters & Prosody Tuning

* **Speech Rate (80–450 WPM)**: Fine-grained slider with live WPM announcements.
* **Sonic Rate Boost (Up to 3× / 750+ WPM)**: Integrates libsonic pitch-preserving time-scale acceleration to achieve ultra-high reading speeds without distortion for experienced screen reader users.
* **Configurable Rate Boost Multiplier**: Customize the acceleration factor (2× to 4×) to match your listening preference.
* **Pitch & Pitch Range (Inflection)**: Independently adjust base vocal fundamental frequency (pitch) and intonation dynamic range (0 = complete monotone, 100% = expressive modulation).
* **Intonation Styles**:
  * *Natural (Standard)*: Preserves natural grammatical pitch contours and clause melodies.
  * *Flat / High-Speed*: Flattens pitch inflection (`espeakINTONATION=3`) for maximum phoneme clarity and syllable intelligibility at extreme reading rates (350–500+ WPM).
  * *Expressive*: Heightened pitch dynamics for animated storytelling.
  * *Custom Group*: Direct manual selection of the underlying eSpeak intonation group (0–7).
* **Emphasize Questions**: Automatically amplifies the terminal pitch inflection on question marks (`?`) and exclamation marks (`!`) as an accessibility aid for hard-of-hearing listeners.
* **Volume Amplification (0% to 200%)**: Provides clean digital gain with extra headroom for low-volume device speakers.
* **Word Gap (0–500 ms)**: Configurable inter-word pause length in precise 10 ms increments (displays clean `"0 ms"` when disabled).

---

### 3. Audio Optimizer (DSP Tone Shaping)

A dedicated multi-band equalizer and dynamic tone shaper engineered specifically for formant synthesis:

* **Audio Optimizer Toggle**: Enable/disable tone post-processing with one tap.
* **Optimizer Profiles**:
  * *Gentle warmth*: Softens aggressive high-frequency sibilants (`s`, `sh`, `f`) and fills out lower vocal body.
  * *Balanced (Recommended)*: Optimized 2.5–5 kHz presence lift that maximizes consonant intelligibility on small phone speakers.
  * *Full richness*: Deep voice profile with expanded dynamic presence and warm harmonic resonance.

---

### 4. Smart Number & Code Reading

Specialized numerical processing designed for accessible daily mobile use:

* **Smart OTP & Verification Code Reading**: Automatically detects incoming two-factor authentication codes, SMS OTPs, bank transaction references, and security PINs, verbalizing them digit-by-digit (`"Your OTP is 4829"` &rarr; `"4 8 2 9"`) while leaving ordinary amounts intact.
* **Configurable OTP Length Bounds**:
  * *Minimum Length*: 2 to 8 digits (default: 4).
  * *Maximum Length*: Minimum up to 12 digits (default: 10, covering Indian IRCTC PNRs and bank codes).
* **Digit Grouping Cadence**:
  * *Off*: Reads numbers as natural cardinals (e.g. `"one thousand two hundred"`).
  * *Single*: Speaks every number digit-by-digit.
  * *Double*: Groups numbers into pairs (`12 34 56`).
  * *Triple*: Groups numbers into triplets (`123 456`).
* **Triple-Grouping Threshold**: Minimum digit count (4 to 12 digits, default: 7) required before triplet grouping takes effect.
* **Roman Numeral Recognition**: Context-aware conversion of Roman numerals into spoken cardinals:
  * *"Chapter IV"* &rarr; *"Chapter 4"*
  * *"King Henry VIII"* &rarr; *"King Henry 8"*
  * *"World War II"* &rarr; *"World War 2"*
  * *"Section IX"* &rarr; *"Section 9"*
* **Indian Numbering System & Currency**:
  * *Lakh & Crore Grouping*: Understands Indian comma notation (`1,00,000` &rarr; *"1 lakh"*, `1,23,45,678` &rarr; *"1 crore 23 lakh 45678"*).
  * *Shorthand Quantities*: Expands shorthand suffixes (`50k` &rarr; *"50 thousand"*, `5L` &rarr; *"5 lakh"*, `2cr` &rarr; *"2 crore"*).
  * *Rupee Currency*: Expands `₹1,00,000` &rarr; *"1 lakh rupees"*.
  * *Identifiers*: Reads UPI IDs, IFSC codes, and vehicle registration numbers cleanly digit-by-digit.
* **Natural Time & Date**: Intelligently reads clock times (`10:30` &rarr; `"10 30"`) and calendar dates without awkward raw punctuation.
* **Currency Amounts**: Expands currency symbols (`$`, `€`, `£`, `¥`, `₹`) into spoken currency terms.

---

### 5. Pronunciation & Text Processing

* **Punctuation Verbosity Presets**:
  * *None*: Clean reading without punctuation announcements.
  * *Some*: Essential sentence-shaping punctuation (`.,!?;:'"-`).
  * *Most*: Standard punctuation plus common inline symbols (`()[]{}/@#$%&*+=<>_~^|\`).
  * *All*: Every punctuation mark and ASCII/Unicode symbol.
  * *Custom*: User-defined string of exact punctuation characters to speak.
* **Capital Letter Indication**:
  * *None*: No capital cue.
  * *Sound icon*: Plays a subtle audio chime prior to capital letters.
  * *Pitch rise*: Elevates vocal pitch on capital letters.
  * *Speak 'capital'*: Verbalizes the word "capital" before uppercase characters.
* **Capital Announcement Scope**:
  * *Character navigation only (Default)*: Capital cues sound strictly when stepping through text character-by-character.
  * *All reading*: Capital cues apply across continuous reading, words, and sentences (indispensable for proofreading, editing, and code reading).
* **Repeated Character Condensation**:
  * *Off*: Reads each character individually.
  * *Count repetitions*: Eliminates divider fatigue by announcing count and character name (`--------------------` &rarr; `"20 dashes"`, `********` &rarr; `"8 asterisks"`).
  * *Truncate*: Limits consecutive identical characters to at most 3 (`soooooooo` &rarr; `sooo`).
* **Simplify Web Addresses (URLs)**: Cleans up URLs in chat messages and web browsing by dropping protocol (`http://`, `https://`) and `www.`, spacing slashes for natural pauses, and condensing lengthy tracking parameters into `"with parameters"`.
* **Programming & Math Symbol Verbalization**: Direct, spoken expansions for technical symbols:
  * Math: `!=`, `==`, `<=`, `>=`, `+-`, `*`, `/`, `~=`, `sqrt`, `inf`, `deg`, `integral`.
  * Logic & Sets: `for all`, `exists`, `element of`, `union`, `intersection`.
  * Code: `=>`, `->`, `&&`, `||`, `//`, `...`, `bullet`.
* **Unicode Normalization (NFKC)**: Converts stylized social media Unicode (bold, italic, circled, script, math alphanumeric fonts) into readable plain text, preserving word-boundary highlighting for TalkBack.
* **Unified Reading Modes**:
  * *Normal*: Standard continuous reading.
  * *Spelling*: Letter-by-letter reading.
  * *Phonetic*: Spells words using NATO phonetics (Alpha, Bravo, Charlie...).
  * *Code reading*: Announces programming symbols and syntax with full punctuation.
* **NATO Phonetic Exploration**: Automatically vocalizes isolated letters using NATO phonetics when cursor-navigating character-by-character.
* **Devanagari Matras & Combining Marks**: Explicitly names silent combining vowel signs and marks (matras, halant, anusvara, visarga, nukta) during character-by-character navigation.
* **Emoji Processing**:
  * *Announce*: Speaks emojis set off with clean pauses and commas using eSpeak's CLDR dictionary.
  * *Ignore*: Completely silences emojis to prevent chat and social media clutter.

---

### 6. Caller-Proof Accessibility Locks

Prevents third-party applications (navigation, browsers, social media, games) from overriding your chosen speech parameters:

* **Lock Speech Rate**: Enforces your configured speech rate regardless of caller app requests.
* **Lock Pitch**: Enforces your configured pitch against overriding applications.
* **Lock Volume**: Prevents caller apps from ducking or dropping speech volume, keeping speech consistently audible.

---

### 7. User Pronunciation Dictionary ("My Words")

A full NVDA-compatible custom pronunciation dictionary:

* **Rule Format**: `Pattern` &rarr; `Replacement`.
* **Matching Modes**: Plain substring, Whole-Word only, and full Regular Expression (regex) support.
* **Case Sensitivity**: Optional case-sensitive or case-insensitive matching.
* **Categories / Buckets**: Organize rules into *Main*, *Root*, and *Abbreviation* categories.
* **Language Scoping**: Restrict rules to a specific language code (`en`, `hi`, `en-in`, etc.) or apply globally.
* **Filter & Search**: Live search bar and category dropdown filter for instant lookup across large dictionaries.
* **Import & Export**: Asynchronously import or export `.dic` files and JSON backups in the background without UI stutter.

---

### 8. Reading Presets, Backup & Diagnostics

* **Recommended Defaults Preset**: One-tap action to restore optimized settings (175 WPM, balanced pitch, character voice variants).
* **Full Backup & Restore**: Single-click JSON export/import encompassing all voice parameters, locks, reading preferences, and user dictionary rules.
* **Zero-Permission Log Exporter**: One-tap diagnostic tool that collects recent logs, device model, Android release, and active TTS settings into the system share sheet without requiring storage permissions.

---

### 9. Accessibility & UI Polish

* **Edge-to-Edge Navigation Bar Insets**: Bottom preferences dynamically adapt to 3-button, 2-button, and gesture navigation bars, preventing content from being obscured.
* **Light & Dark Theme Status Bar Adapting**: Automatically switches status bar and navigation bar icons between dark and light modes, ensuring clock, battery, and notification icons remain legible.
* **Subscreen Dialog Action Bar**: Subscreen dialogs (*Number & code reading*, *Pronunciation & text processing*, *Caller-proof locks*, *Tools & about*) feature a dedicated Action Bar with an accessible Up navigation back button (`<-`) and comfortable top insets.
* **Full-Width Voice Variant Selectors**: Stacked layout eliminates text truncation (*"Character voices"* displays in full).
* **Slider Breathing Room**: Generous horizontal margins and padding for easy touch adjustments.
* **Screen Reader Accessibility**: Every input, button, spinner, and seekbar features explicit accessibility labels and descriptions for TalkBack.

---

### 10. Android System Architecture

* **Direct-Boot Support**: All configuration files, phoneme data, and dictionary caches reside in Direct-Boot device-protected storage (`/data/user_de/`), ensuring full screen-reader speech prior to initial device unlock.
* **Concurrency-Guarded Extraction**: Voice data extraction from `espeakdata.zip` is guarded against concurrent access from multiple processes or re-installs.
* **Wear OS Compatible**: Built-in support and launch-alias handling for Android smartwatches.
* **Page-Size Alignment**: Native shared library (`libttsespeak.so`) is linked with `-Wl,-z,max-page-size=16384` for 16 KB page-size compatibility on modern Android 14/15/16/17 kernels.
* **Lean Dependencies**: Pure Java and native C with zero Kotlin runtime dependencies, keeping APK size minimal.

---

## Indian English (`en-in`)

This fork includes a custom Indian English voice (`en-in`), built directly on the base English phoneme table:

* Retroflex stops: Alveolar `t`/`d` &rarr; retroflex `ʈ`/`ɖ` (`time` &rarr; `ʈaɪm`).
* Dental stops: Dental fricatives `θ`/`ð` &rarr; dental stops `t̪`/`d̪`.
* Semivowel merger: `w`/`v` merger &rarr; labiodental approximant `ʋ`.
* Monophthongs: FACE/GOAT diphthongs `eɪ`/`oʊ` &rarr; monophthongs `eː`/`oː`.
* Non-velarized clear `l`: Clear `l` across all phonetic positions.
* True rhoticity: Fully rhotic with retroflex vowel transitions and cross-word liaison (`water is` &rarr; `ʋɔːʈər ɪz`), adhering to the OED World Englishes and Sailaja (2009) models.

---

## Building the App

### Requirements
* JDK 17 (Temurin recommended)
* Android SDK (compileSdk 37, minSdk 26, targetSdk 37)
* Android NDK (29.0.14206865)
* CMake (3.22.1)

### Build Commands

From the `android/` directory:

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK (signed if keystore.properties exists)
./gradlew assembleRelease
```

APKs are generated in `android/build/outputs/apk/{debug,release}/`.

---

## Testing

The project includes an extensive Android instrumentation test suite (`android/eSpeakTests/`) covering synthesis, audio pipelines, language filtering, preferences, Direct-Boot storage, and preprocessing:

```bash
# Run 100 on-device instrumentation tests on connected hardware / emulator
./gradlew connectedAndroidTest
```

---

## License Information

* **eSpeak NG Text-to-Speech**: Released under the [GNU General Public License v3.0 or later](COPYING).
* **getopt Compatibility**: Licensed under the [2-Clause BSD License](COPYING.BSD2).
* Android is a trademark of Google LLC.
