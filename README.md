# eSpeak NG for Android

[![Android CI](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](COPYING)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-green.svg)](android/)
[![153 languages](https://img.shields.io/badge/Languages-153-brightgreen.svg)](espeak-ng-data/lang)

eSpeak NG as an Android text-to-speech engine, built for screen reader users (TalkBack, Jieshuo, Commentary and others).

It speaks the way eSpeak speaks in NVDA on Windows, so it sounds familiar if you already use NVDA. The Indian languages are the only exception: they get extra fixes here, and those fixes affect Indian voices only.

- **Fully offline.** All 153 languages are inside the app, and it has no internet permission.
- **Speaks before unlock.** Works in Direct Boot, so your screen reader talks on the lock screen after a restart.
- **Fast and light.** Starts speaking at once and handles very high speech rates.

---

## Contents

- [NVDA parity](#nvda-parity)
- [Settings](#settings)
- [My Words (pronunciation dictionary)](#my-words-pronunciation-dictionary)
- [Indian languages](#indian-languages)
- [Building](#building)
- [Testing](#testing)
- [License](#license)

---

## NVDA parity

Every voice except the Indian ones should sound the same as eSpeak in NVDA. The app follows NVDA in three areas:

- **Symbols and punctuation** are read with NVDA's symbol rules and the None, Some, Most and All levels.
- **Emoji** are read by their CLDR names in the voice's own language, as NVDA reads them.
- **Numbers, dates, times and currency** are left to eSpeak itself, just like in NVDA. Indian number reading (lakh, crore, ₹) is added only when an Indian voice is speaking.

On top of that there are a few extras NVDA doesn't have, such as money and code reading. They are off by default, so the default sound stays the same as NVDA's.

---

## Settings

### Voice and language
- **Languages**: choose which of the 153 languages show up in Android's voice list. Includes a search box.
- **Voice variant**: male 1–8, female 1–6, Klatt, about 170 character voices (such as Max and Edward), young, old, croak and whisper.
- **Test voice**: hear what your current settings sound like.

### Voice
- **Speech rate**, with an optional **rate boost** (off, or 2× to 5×) for very fast listening.
- **Pitch** and **pitch variation**. Set variation to 0 for a monotone voice.
- **Intonation style**: natural, flat (clearer at high speed), expressive, or custom.
- **Volume** up to 200%.
- **Word gap** and **reading pace** (pause length).
- **Audio optimizer**: off, gentle, balanced or full. Gives a warmer, fuller sound and evens out quiet passages.

### Numbers
- **Digit grouping**: read long numbers as a whole, or digit by digit, or in pairs, or in threes. You can set the length at which grouping in threes starts.
- **Indian numbering**: reads ₹1,00,000, 5L and 2cr in lakh and crore. Applies to Indian voices only.
- **Money amounts** (off by default, English voices): reads $5.50 as "5 dollars 50 cents". Also euros, pounds, yen and rupees.
- **Codes digit by digit** (off by default): reads OTPs, PINs and account numbers as single digits.

### Text
- **Punctuation**: None, Some, Most, All, or a custom set of characters. Maths and code symbols are read by name at the chosen level, as NVDA does.
- **Capital letters**: say nothing, play a sound, raise the pitch, or say "capital". You can limit this to character-by-character reading.
- **Repeated characters**: "20 dashes" instead of twenty dashes, or shorten them to three.
- **Fancy fonts**: reads stylised Unicode text as normal words.
- **Reading mode**: normal, spelling, or code.
- **Phonetic letters**: says Alfa, Bravo and so on, either when you move by character or for all text.
- **Matras and signs**: names Indic vowel signs, halant, anusvara and similar marks when you move by character.
- **Emoji**: read them out or skip them.
- **Shorter web links**: drops `https://` and `www.`, and reads long tracking parameters as "with parameters".
- **Emphasise questions**: raises the pitch more at the end of questions and exclamations.

### Locks
Stops other apps from changing your **rate**, **pitch** or **volume**.

### Tools
- **Recommended defaults**, which restores the settings with one tap.
- **Backup and restore** of all settings plus your dictionary, as one file.
- **Export log**, which shares a troubleshooting log. No storage permission is needed.
- **Import voice**, which adds voice or dictionary data from a file.

---

## My Words (pronunciation dictionary)

Your own pronunciations for words, names and abbreviations.

- Rules can match plain text, whole words only, or a regular expression, with or without matching case.
- Rules can apply to one language or to all of them.
- Search and filter make large lists quick to browse.
- NVDA `.dic` files can be imported and exported, so your NVDA dictionaries carry over.

---

## Indian languages

The only changes that go beyond NVDA are here, and they apply to Indian voices only.

- **Indian English (`en-in`)**: retroflex `t`/`d`, dental `th`, a merged `w`/`v`, the single vowels in "face" and "goat", a clear `l`, and a full `r`.
- **Hindi and related languages**: pronunciation fixes, including word-final vowel length.
- **Indian numbering**: see [Numbers](#numbers).

---

## Building

You need:
- JDK 17
- the Android SDK (compileSdk 37)
- NDK 29.0.14206865
- CMake 3.22.1

Run these from `android/`:

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

The release build is signed when `android/keystore.properties` exists. APKs go to `android/build/outputs/apk/`.

The app supports Android 8.0 and newer (minSdk 26), phones and Wear OS. The native library is built with 16 KB page alignment.

For architecture and contributor notes, see [android/CLAUDE.md](android/CLAUDE.md).

---

## Testing

There are 117 instrumentation tests in `android/eSpeakTests/`. They cover synthesis, settings, voice data, the text pipeline and the dictionary, and they need a connected device or emulator:

```bash
./gradlew connectedAndroidTest
```

---

## License

- eSpeak NG: [GPL v3 or later](COPYING)
- getopt compatibility code: [BSD 2-Clause](COPYING.BSD2)

Android is a trademark of Google LLC.
