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
visually impaired users navigating with TalkBack: fast, fully offline,
zero network dependency beyond an optional language-pack download, and
tuned for the accessibility use case rather than general-purpose narration.

## What's different from upstream

* **Indian English (`en-in`)** — upstream eSpeak NG ships no Indian English
  voice at all. This fork adds one (`espeak-ng-data/lang/gmw/en-in`):
  retroflex `r`, dental `t`/`d` stops, syllable-timed rhythm with unreduced
  full vowels. Bundled in the app's core language set alongside the full
  Indic (`lang/inc`) and Dravidian (`lang/dra`) language families — Hindi,
  Bengali, Marathi, Tamil, Telugu, Kannada, Malayalam, Gujarati, Punjabi,
  and more are built in, not a separate download.
* **10 Klatt-synthesis voice personas** — Paul, Betty, Harry, Frank, Kit,
  Dennis, Ursula, Rita, Wendy, and Turbo (a rate-optimized voice for
  high-speed TalkBack use), selectable from the Voice variant picker's
  Klatt category.
* **Context-aware digit reading** — an opt-in "read numbers digit by digit"
  mode for the general case, plus an always-on heuristic that reads likely
  OTPs/PINs/verification codes (4-8 digits near a keyword like "OTP" or
  "code") digit-by-digit while leaving ordinary numbers and rupee amounts
  read naturally. The ₹ symbol expands to "rupees".
* **Clearer emoji announcements** — eSpeak NG's own CLDR-quality emoji
  dictionary ("😂" → "face with tears of joy") is set off from the
  surrounding sentence with a light pause, so it reads as an aside rather
  than running into the sentence as if it were literal text.
* **Reliability work**: several file-descriptor leaks in the voice-data
  extraction path, a native return value that silently masked synthesis
  failures, a build task that could silently ship stale voice data on
  incremental builds, and a debug-build native-debugging regression, all
  found and fixed on this fork — see `android/CLAUDE.md` for the details
  and the reasoning behind each.

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
