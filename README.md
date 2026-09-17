# eSpeak NG — Android Fork

- [What this fork is](#what-this-fork-is)
- [What's different from upstream](#whats-different-from-upstream)
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

### Indian-locale text intelligence

A preprocessing pipeline (`TtsService.preprocessIndianText`) runs before
text reaches the synthesizer, specifically for the kinds of text Indian
users actually get read to them (bank SMS, UPI notifications, prices):

* **Indian number grouping**: `1,00,000` → "1 lakh", `1,23,45,678` → "1
  crore 23 lakh 45678" (remainders under 1 lakh are left as digits, which
  eSpeak already verbalizes correctly on its own).
* **Shorthand quantities**: `50k` → "50 thousand", `5L` → "5 lakh", `2cr` →
  "2 crore".
* **Rupee amounts**: `₹1,00,000` → "1 lakh rupees", `₹10.50` → "10 rupees 50
  paise" (only a 1-2 digit nonzero fraction becomes paise; anything longer
  is left for the engine to read as a decimal).
* **9 Indic numeral scripts** (Devanagari, Bengali, Gurmukhi, Gujarati,
  Oriya, Tamil, Telugu, Kannada, Malayalam) normalized to ASCII digits
  before any of the above runs.
* **Danda punctuation** (। and ॥) gets a space inserted when directly
  adjacent to text, so the engine's clause-break logic actually sees it as
  a boundary instead of a glued-on character.
* **Banking token splitting**: `UPI/423891028341/PAYTM` → `UPI /
  423891028341 / PAYTM`, so slash-concatenated reference strings read as
  separate tokens instead of one run-on word.

### Context-aware digit and code reading

* An opt-in "read numbers digit by digit" mode for general use.
* An always-on, keyword-gated heuristic reads likely OTPs/PINs/verification
  codes digit-by-digit (`Your OTP is 4829` → "4 8 2 9") while leaving
  ordinary numbers alone (`The year 2024 is great` stays "2024"): a 4-8
  digit run gets split only when a keyword (`otp`, `pin`, `passcode`,
  `verification`, `security`, `token`, `login`, `id`, `txn`, `ref`, `vpa`,
  `cvv`) appears within 25 characters either side, with `\b` word-boundary
  matching so it doesn't fire on substrings inside unrelated words. `code`
  is deliberately excluded from that list — it's too common an ordinary
  English word ("dress code", "zip code") and every real OTP message
  already matches via a more specific word.
* `$`/`€`/`£`/`¥` and `₹` amounts expand to words (`$5` → "5 dollars").

### Accessibility-specific text handling

* **Bilingual mixed-script reading**: text mixing Latin and an Indic script
  (e.g. an English sentence with a Hindi phrase in it) is split into spans
  by script, each span synthesized with the appropriate voice (Latin text
  with the requested Latin voice, Indic text with the matching Indic voice)
  instead of forcing one voice to mangle the other script.
* **Single-character spelling on cursor navigation**: when TalkBack lands
  on exactly one character (arrow-key/swipe navigation through text), it's
  disambiguated rather than just spoken bare:
  - Latin letters use NATO phonetic spelling (`a` → "a, Alpha").
  - Devanagari vowel signs and other combining marks, which are silent or
    meaningless in isolation, are named instead (ा → "आ की मात्रा", ् →
    "हलन्त", ं → "अनुस्वार", and so on for all the standard matras,
    anusvara, visarga, chandrabindu, halant, and nukta).
* **Unicode NFKC normalization**: stylized Unicode (Mathematical
  Alphanumeric Symbols popular on social media, fullwidth forms, enclosed/
  circled/squared letters) is normalized to plain text so it's read as the
  words it spells rather than character-by-character garbage — mirroring
  what NVDA and speech-dispatcher do before their own synthesis step. Word-
  boundary events are remapped back through every preprocessing step
  (dictionary replacement, NATO/programming-symbol expansion, Indian-number
  formatting, digit separation, normalization, emoji handling) so TalkBack
  still highlights the right span of the *original* text even though the
  text actually spoken has a different length.
* **Programming/math symbol expansion**: symbols like `∀ ∃ ∈ ∉ ∪ ∩ ¬ ∧ ∨`
  read as their names instead of being silently dropped or read as garbage
  (symbol set borrowed from NVDA's `symbols.dic`).
* **Clearer emoji announcements**: eSpeak NG's own CLDR-quality emoji
  dictionary ("😂" → "face with tears of joy") is set off from the
  surrounding sentence with a light pause and comma formatting, so it reads
  as an aside rather than running into the sentence as literal text.
  Consecutive emoji are cleanly separated.

### User pronunciation dictionary

Per-language custom pronunciation rules ("My Words"), NVDA speech-dictionary
compatible in semantics: pattern → replacement, with case sensitivity,
whole-word matching, and optional regex, organized into Main/Root/
Abbreviation buckets.

### Audio Optimizer

A speech-tailored two-band tone shaper (a real bandpass presence boost
around 2.5-5kHz for consonant clarity, plus a lowpass warmth band) with a
gentle loudness leveler and clip guard, re-tuned by ear specifically for
eSpeak's own formant/Klatt output.

### Backup, restore, and diagnostics

* **Backup & Restore**: exports voice tunings, preferences, and the user
  dictionary into a single JSON file, restorable via Android's standard
  backup/share flow.
* **Log Exporter**: one-tap collection of device info, app version,
  preference snapshot, and recent logcat output into a single shareable
  text blob — no storage permission needed (shared via `ACTION_SEND`).

### Every language bundled, nothing to download

All ~120 languages eSpeak NG data ships with are packed directly into the
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
Requires NDK 29.0.14206865 and CMake 3.22.1 (pinned in `android/build.gradle`).

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
