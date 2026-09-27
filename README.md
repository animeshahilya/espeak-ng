# eSpeak NG for Android

[![Android CI](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml)
[![Code Quality](https://github.com/animeshahilya/espeak-ng/actions/workflows/quality.yml/badge.svg)](https://github.com/animeshahilya/espeak-ng/actions/workflows/quality.yml)
[![Release](https://github.com/animeshahilya/espeak-ng/actions/workflows/release.yml/badge.svg)](https://github.com/animeshahilya/espeak-ng/actions/workflows/release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](COPYING)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-green.svg)](android/)
[![153 languages](https://img.shields.io/badge/Languages-153-brightgreen.svg)](espeak-ng-data/lang)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md)

eSpeak NG as an Android text-to-speech engine, built for screen reader users (TalkBack, Jieshuo, Commentary and others).

It speaks the way eSpeak speaks in NVDA on Windows, so it sounds familiar if you already use NVDA. The Indian languages are the only exception: they get extra fixes here, and those fixes affect Indian voices only.

- **Offline speech.** All 153 languages are inside the app. The internet is used only if you choose to download a natural voice.
- **Natural voices, if you want them.** Download a Piper neural voice for any language it covers and pick, per language, whether eSpeak or that voice speaks it. Piper voices read through this app's own eSpeak pronunciation rules, including the Indian-language fixes and your own dictionary.
- **Speaks before unlock.** Works in Direct Boot, so your screen reader talks on the lock screen after a restart.
- **Fast and light.** Starts speaking at once and handles very high speech rates.

---

## Download

- **Latest Release**: [GitHub Releases](https://github.com/animeshahilya/espeak-ng/releases) (unsigned APK)
- **Nightly Builds**: Available as artifacts from [CI runs](https://github.com/animeshahilya/espeak-ng/actions/workflows/android.yml)

> The release APK is unsigned. For a signed build, build from source with a keystore.

---

## Contents

- [NVDA parity](#nvda-parity)
- [Settings](#settings)
- [Natural voices (Piper)](#natural-voices-piper)
- [My Words (pronunciation dictionary)](#my-words-pronunciation-dictionary)
- [Indian languages](#indian-languages)
- [Building](#building)
- [Testing](#testing)
- [Contributing](#contributing)
- [Security](#security)
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

- **Languages**: choose which of the 153 languages show up in Android's voice list. Includes a search box. Touch and hold a language to hear a sample of it.
- **Favorite voices**: pin voices to the top of the voice list.
- **Voice variant**: male 1–8, female 1–6, Klatt, about 170 character voices (such as Max and Edward), young, old, croak and whisper.
- **Test voice**: hear what your current settings sound like.
- **Settings search**: find any setting from the top bar. Results jump straight to the matching row.

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
- **Read Hinglish (beta, off by default)**: on English voices, Hindi typed in English letters ("aap kaise ho bhai") is read with Hindi pronunciation. English sentences are left alone.
- **Matras and signs**: names Indic vowel signs, halant, anusvara and similar marks when you move by character.
- **Emoji**: read them out or skip them.
- **Shorter web links**: drops `https://` and `www.`, and reads long tracking parameters as "with parameters".
- **Emphasise questions**: raises the pitch more at the end of questions and exclamations.

### Locks

Stops other apps from changing your **rate**, **pitch** or **volume**.

- **Sleep timer**: pause all speech for 15–60 minutes, then resume automatically. Open settings and choose Off to cancel early.

### Tools

- **Recommended defaults**, which restores the settings with one tap.
- **Voice profiles**: save your variant, rate, pitch and punctuation as a file, and load profiles shared by others.
- **Reading history** (off by default): keeps recently spoken text on your device so you can re-hear it from **Recent reading**. Short codes and markup are never kept.
- **Backup and restore** of all settings plus your dictionary, as one file.
- **Export log**, which shares a troubleshooting log. No storage permission is needed.
- **Import voice**, which adds voice or dictionary data from a file.

---

## Natural voices (Piper)

eSpeak is built for speed: it starts talking instantly and stays clear at very high rates, which is what most screen reader users want. Some people would rather hear a human-sounding voice for reading, at least in some languages. **Settings → Natural voices** lets you choose, language by language.

- **Download natural voices**: browse the official [Piper voice catalog](https://huggingface.co/rhasspy/piper-voices) by language, play a recorded sample, and download. Each voice is 20–75 MB. Downloads run in the background with a progress notification, and every file is checked against the catalog's checksum before it can be used.
- **Voice for each language**: every language you have a natural voice for gets a row. Choose eSpeak or one of its natural voices. The first voice you download for a language is chosen for it automatically.
- **Use natural voices**: one switch to go back to eSpeak everywhere without losing your choices.
- **eSpeak for single letters** (on by default): moving by character and spelling stay on eSpeak, which starts instantly.
- **Natural voice speed**: natural voices follow your eSpeak rate; this makes them a little slower or faster than it.
- **Downloaded voices**: test or delete a voice.

How it fits with the rest of the app:

- **Same pronunciation.** Piper voices are driven by phonemes, and those phonemes come from this app's own eSpeak NG, the same way the Piper project itself uses eSpeak NG. That means everything this fork fixes carries over: Indian schwa rules, Indian numbering, symbol and emoji reading, and entries in My Words.
- **Same settings.** Rate, rate boost, pitch, volume, reading pace, the audio optimizer, punctuation sounds and reading history all apply. Very high rates are reached by speeding up the finished audio with libsonic, the same library eSpeak uses above 450 words per minute.
- **Never silent.** A voice takes a second or two to load. Until it is ready, and if it ever fails, eSpeak speaks instead. Speech starts after the first phrase is ready, not after the whole paragraph, and stopping speech stops the voice at once.
- **Works before unlock.** Voices are stored where eSpeak's data is, so they work on the lock screen after a restart too.
- **Offline.** Once downloaded, a voice never uses the internet.

Not every Piper voice can be used: newer Chinese, Japanese, Thai and Hebrew voices need their own phonemizers instead of eSpeak, and the app tells you so before downloading. Natural voices are not offered on Wear OS.

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

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on:

- Development setup
- Code style (Java, C/C++)
- Pull request process
- NVDA parity requirements
- Voice data changes

### Community

- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [GitHub Discussions](https://github.com/animeshahilya/espeak-ng/discussions)
- [Issue Tracker](https://github.com/animeshahilya/espeak-ng/issues)

---

## Security

Speech never uses the network. The app has the `INTERNET` permission only to download Piper natural voices, and it connects only when you open the voice list or start a download, to `huggingface.co` (voice catalog and voices) and `rhasspy.github.io` (voice samples). Downloaded voices are verified against the catalog's MD5 checksums before use.

See [SECURITY.md](SECURITY.md) for vulnerability reporting and our disclosure policy.

---

## License

- eSpeak NG: [GPL v3 or later](COPYING)
- getopt compatibility code: [BSD 2-Clause](COPYING.BSD2)
- Hinglish word list (`android/assets/hinglish/hi.tsv`): built by `android/tools/build_hinglish.py` from Google's [Dakshina dataset](https://github.com/google-research-datasets/dakshina), [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). English words were identified with CMUdict.
- Urdu word pronunciations (generated block at the end of `dictsource/ur_list`): built by `android/tools/urdu/ur_from_hindi.py` from the same Dakshina dataset, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Android is a trademark of Google LLC.

---

## Acknowledgments

- Upstream [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng) for the core synthesis engine
- [NVDA](https://www.nvaccess.org/) for symbol/emoji processing reference
- [Transifex translators](https://www.transifex.com/espeak-ng/espeak-ng-android/) for 46 locale translations
- Google's [Dakshina dataset](https://github.com/google-research-datasets/dakshina) for Hinglish/Urdu data
- [Piper](https://github.com/OHF-Voice/piper1-gpl) and the [piper-voices](https://huggingface.co/rhasspy/piper-voices) contributors for the natural voices (each voice has its own license; see its MODEL_CARD in the catalog)
- [ONNX Runtime](https://onnxruntime.ai/) for running them on the phone