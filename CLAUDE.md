# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repository is dedicated solely to the **eSpeak NG Android application** (`com.animeshahilya.espeakng`). All non-Android desktop ports, desktop test suites, editor integrations, and packaging tools have been removed.

The project consists of:
- **`android/`** — The complete Android application (Java TTS service, UI, settings, Android NDK CMake configuration, and instrumentation tests).
- **`src/libespeak-ng/`** — Core C formant-based speech synthesis library compiled into `libttsespeak.so` for Android.
- **`src/speechPlayer/`**, **`src/ucd-tools/`**, **`src/include/`**, **`src/compat/`** — Supporting native libraries and headers.
- **`src/espeak-ng.c`** — CLI binary used on the host during the build to compile phoneme and dictionary data.
- **`dictsource/`**, **`phsource/`**, **`espeak-ng-data/`** — Language pronunciation rules, phoneme definitions, and voices compiled into `espeakdata.zip` inside the Android APK.

For detailed Android workflows, build instructions, and architecture, see [android/CLAUDE.md](android/CLAUDE.md).

## Build Commands

All builds and tests run from the `android/` directory:

```bash
# Build debug APK
cd android
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run on-device Android instrumentation tests (111 tests)
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
