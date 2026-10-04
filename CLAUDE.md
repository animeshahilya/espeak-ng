# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repository is dedicated solely to the **eSpeak NG Android application** (`com.animeshahilya.espeakng`). All non-Android desktop ports, desktop test suites, editor integrations, and packaging tools have been removed.

The project consists of:
- **`android/`** — The complete Android application (Java TTS service, UI, settings, Android NDK CMake configuration, JVM unit tests, and instrumentation tests).
- **`src/libespeak-ng/`** — Core C formant-based speech synthesis library compiled into `libttsespeak.so` for Android.
- **`src/speechPlayer/`**, **`src/ucd-tools/`**, **`src/include/`**, **`src/compat/`** — Supporting native libraries and headers.
- **`src/espeak-ng.c`** — CLI binary used on the host during the build to compile phoneme and dictionary data.
- **`dictsource/`**, **`phsource/`**, **`espeak-ng-data/`** — Language pronunciation rules, phoneme definitions, and voices compiled into `espeakdata.zip` inside the Android APK.

For detailed Android workflows, build instructions, and architecture, see [android/CLAUDE.md](android/CLAUDE.md).

## Build and Test Commands

All builds and tests run from the `android/` directory:

```bash
# Build debug APK
cd android
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run JVM unit tests (no device required, 25 test suites)
./gradlew testDebugUnitTest

# Run on-device Android instrumentation tests (122 tests)
./gradlew connectedAndroidTest
```

## Architecture

```
Android TTS Framework
        │
   TtsService (TextToSpeechService in android/src/)
        │
   SpeechSynthesis (Java JNI wrapper)
        │  JNI calls
   eSpeakService.c (android/jni/jni/) — bridge layer
        │  C API (espeak_*)
   libespeak-ng (src/libespeak-ng/)
```

Optional Piper neural voices (per-language, downloaded on request) reuse this
same eSpeak NG as their phonemizer and run on ONNX Runtime - see "Natural
voices (Piper)" in [android/CLAUDE.md](android/CLAUDE.md).

Key architectural invariants:
- **Direct Boot**: All settings and engine data live in device-protected storage (`EspeakApp.getStorageContext()`) so TTS speaks before device unlock.
- **Managed Concurrency**: Asynchronous background work is routed through `EspeakApp.runAsync()`; thread-safe caching is maintained via `ScriptLanguages.CacheHolder`.
- **TalkBack & Accessibility**: Native accessibility polish with heading semantics, live region announcements, predictive back gesture support, and supplementary Unicode/emoji character navigation.
- **NVDA Parity**: Non-Indian voices strictly preserve identical NVDA pronunciation behavior.
