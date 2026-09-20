# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the Android app in this directory.

## Build Commands

```bash
# Build debug APK (from android/ directory)
./gradlew assembleDebug

# Build release APK (signed if android/keystore.properties exists, unsigned otherwise)
./gradlew assembleRelease

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

Output APKs land in `build/outputs/apk/debug/` and `build/outputs/apk/release/`.

## Build Configuration

- Package/namespace `com.animeshahilya.espeakng`; app label "eSpeak NG Advanced" (`R.string.app_name`, translatable="false" - single source, no per-locale override)
- compileSdk 37, minSdk 26 (Android 8.0+), targetSdk 37
- NDK 29.0.14206865, CMake 3.22.1
- JDK 17 (Temurin) in CI
- `gradle.properties` enables Gradle's build cache and a larger daemon heap - build-time only, no effect on the shipped app; the native CMake build and the ~30MB multi-language data archive make clean builds slow enough for this to matter locally and in CI

The Gradle build has custom tasks that run automatically:
1. CMake builds `libttsespeak.so` (JNI) + generates `espeak-ng-data/` (all ~120 languages)
2. `createDataArchive` zips the whole tree into `res/raw/espeakdata.zip` -
   every language ships in the APK, there is no core/extra split
3. `createDataHash` generates SHA256 for upgrade detection
4. `createDataVersion` writes the hash to `res/raw/espeakdata_version`

`createDataArchive` depends on `externalNativeBuildDebug`/`externalNativeBuildRelease`
so it always zips freshly-generated data; being a `Zip` task, its `from(...)`
source is tracked as a normal Gradle input, so an incremental build that only
touches `espeak-ng-data/` (a new voice file, an updated dictionary) without
touching a C/C++ source still re-zips correctly - no manual input declaration
needed here (unlike the old `splitLanguageData` task this replaced, which did
need one; see git history if resurrecting that split).

Native build disables `USE_ASYNC` and `USE_MBROLA` (not needed on Android) and
enables `USE_LIBSONIC` for speech rates above `espeakRATE_MAXIMUM`. libsonic has
no NDK sysroot package, so `jni/CMakeLists.txt` fetches and builds it from
source (pinned to a specific commit, bumped occasionally for upstream fixes
in `sonic.c` itself - that's the only file of the library actually compiled
here) and pre-seeds `SONIC_LIB`/`SONIC_INC` for `cmake/deps.cmake`.

`jni/CMakeLists.txt` also sets `-O3` gated to Release only (`$<$<CONFIG:Release>:-O3>`),
plus Release-only thin LTO (`-flto=thin`, compile and link). Don't make either
unconditional again - `-O3` was for a while, and made native
breakpoints/variable inspection unreliable on debug builds since Gradle
never passes `-DCMAKE_BUILD_TYPE` explicitly here (AGP infers it from the
Debug/Release variant).

### Release signing

`build.gradle` loads `android/keystore.properties` (gitignored, alongside the
`.jks` it points to under `android/keystore/`) if present, and signs
`assembleRelease` with it; without the file, `assembleRelease` silently falls
back to an unsigned APK rather than failing.

The release build type has `minifyEnabled true`. This is safe only because
`proguard-rules.pro` explicitly `-keep`s the whole `SpeechSynthesis` class:
`jni/jni/eSpeakService.c` binds to it by exact unmangled name -
`Java_com_animeshahilya_espeakng_SpeechSynthesis_*` for its 10 `native`
methods, plus explicit `GetMethodID()` lookups in `nativeClassInit()` for
`nativeSynthCallback`/`nativeSynthWordCallback` (private methods invoked
*from* native code, with no Java-side call site R8 can see marking them
used). If that keep rule is ever narrowed or removed, minification will
silently break every JNI call in the release build with no compile-time
warning - verify a real release build's TTS synthesis on a real device after
touching either `proguard-rules.pro` or this class.

## Architecture

```
Android TTS Framework
        │
   TtsService (TextToSpeechService)
        │
   SpeechSynthesis (Java JNI wrapper)
        │  JNI calls
   eSpeakService.c (jni/jni/) — bridge layer
        │  C API (espeak_*)
   libespeak-ng (../../src/libespeak-ng/)
```

### Key Classes (`src/com/animeshahilya/espeakng/`)

- **EspeakApp** — `Application` subclass; owns the device-protected storage context, the one-time migration of pre-2022 preferences into it, and the Wear launcher alias state (see below)
- **TtsService** — Android TTS engine service; handles `onSynthesizeText()`, voice selection, parameter setup. Also registers a receiver for `DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED` and reloads the engine's voice list on receipt, so a voice imported through `TtsSettingsActivity` becomes selectable without restarting the process. Also does text preprocessing before handing text to the native engine: `spaceSeparateDigits()` (the "Read numbers digit by digit" setting), `spaceSeparateSmartCodes()` (context-gated OTP/PIN/verification-code digit-by-digit reading plus ₹ → "rupees" expansion - gated on a nearby keyword like "otp"/"pin"/"code" so it doesn't misread ordinary numbers, e.g. a bare rupee amount), and `clarifyEmojiAnnouncements()` (sets emoji descriptions off with a light pause - "I'm happy, grinning face, today" - so they read as an aside rather than plain sentence text; only applies when the "Emoji processing" setting is Announce, not Ignore).
- **SpeechSynthesis** — JNI wrapper; loads `libttsespeak.so`, exposes native functions as Java API
- **UnicodeNormalization** — NFKC normalization of synthesis input (stylized Unicode → plain text) with a normalized→original offset map for `rangeStart()` word boundaries
- **VoiceSettings** — SharedPreferences wrapper for rate, pitch, volume, punctuation, variant
- **LanguageSettings** — Filters available voices by user-selected languages
- **CheckVoiceData** — Intent handler that verifies voice data files exist on device
- **DownloadVoiceData** — Extracts `espeakdata.zip` (every bundled language) to device-protected storage on a single-thread executor (~1 s on a Pixel 8 for the ~14 MB archive; runs on the caller's behalf via `CheckVoiceData.ensureVoiceData()`, which also re-verifies the tree before reporting success)
- **TtsSettingsActivity** — Preferences UI (voice variant, rate, pitch, etc.); also the `CONFIGURE_ENGINE` target and, on Wear, the launcher entry point. Voice-picker labels are localized via `getVoiceLabel()`: the system-language name first (`Locale.getDisplayName()`), English name in parentheses for disambiguation
- **Voice / VoiceVariant** — Data models for voice metadata and variant parsing

### Launcher icon and Wear

`TtsSettingsActivity` carries a `MAIN`/`LAUNCHER` intent-filter (phone
launcher icon) plus the `CONFIGURE_ENGINE` entry used from the system
Text-to-speech settings. The `.WearLauncher` `<activity-alias>` targeting
the same activity ships `android:enabled="false"`.
`EspeakApp.syncWearLauncherState()` enables it at runtime on watches only.

**Never disable that alias at runtime.** Disabling a launcher alias closes the
activity that was started through it — silently, with no exception, and
`DONT_KILL_APP` does not prevent it. An earlier revision shipped the alias
enabled and disabled it on phones at first launch, which made the settings
screen open and immediately vanish. A `@bool/`-with-`values-watch` override on
`android:enabled` does not work either: PackageManager parses that attribute
against a configuration with no UI mode, so it always resolves to the default.

### JNI Layer (`jni/jni/eSpeakService.c`)

10 JNI functions mapping `SpeechSynthesis.native*()` Java methods to `espeak_*()` C API calls (plus `JNI_OnLoad`/`JNI_OnUnload`, which aren't Java-callable). Audio flows back via `SynthCallback` → `nativeSynthCallback()` → `SynthesisCallback.audioAvailable()`.

### Voice Data Lifecycle

On first launch (or version mismatch), `DownloadVoiceData` extracts `res/raw/espeakdata.zip` (every language, ~30MB) to device-protected storage. `CheckVoiceData` validates required files: `version`, `intonations`, `phondata`, `phonindex`, `phontab`, `en_dict`.

All languages are bundled directly in the APK and selectable from first
launch - there is no core/extra split and no network-fetched language pack
(the app has no `INTERNET` permission and makes no network calls at all).
`LanguageSettings.getSelectedLanguages()` still lets a user narrow which of
the bundled languages show up in the voice picker via the "Supported
languages" preference, but that's a display filter over data already on
disk, not a download gate - an unset/empty selection means all languages,
which is the default.

### Indian English (`en-in`) and voice variants

`espeak-ng-data/lang/gmw/en-in` (added on this fork; upstream espeak-ng ships
no Indian English voice at all) uses its own `en-in` phoneme table
(`phsource/ph_english_in`: retroflex `t.`/`d.` stops, monophthongal `eI`/`oU`,
clear `l`) plus `replace` directives in the voice file: `w`/`v` to the
labiodental approximant `v#`, dental `t[`/`d[` stops for `T`/`D`, retroflex
`t.`/`d.` (including the `t2`/`t#`/`d#` allophones `en_rules` emits),
rhotic `r.`/`r-`, `Z` to `dZ`, `eI`/`oU` to `e:`/`o:`, with `dictrules 1 2 8`
for syllable-timed rhythm with unreduced full vowels. Keep phonetic tuning
in these two files (table for phonemes missing from base `en`, `replace`
rules for remapping) rather than copying a whole phoneme table - an upstream
attempt at en-IN that did the latter ([espeak-ng#1981](https://github.com/espeak-ng/espeak-ng/pull/1981))
was closed unmerged and covers less ground than this setup does.

`espeak-ng-data/voices/!v/klatt*` (`klatt`, `klatt2`-`klatt6`) are the
upstream cascade-parallel formant models, exposed as "Klatt 1"-"Klatt 6" in
the Klatt category of `VoiceVariantPreference`'s picker
(`android/src/.../preference/VoiceVariantPreference.java`), each needing a
matching `variant_klatt_N` string in `res/values/strings.xml` and a
`VariantData` entry - `VoiceVariantCatalogTest` (see Testing) catches a
mismatch between the two. The "NVDA" variant category was renamed to
"Character voices" (`R.string.variant_character`); code comments crediting
NVDA as the source of specific fixes (control-character stripping, symbol
expansion, the Sonic-engagement rate cap) stay, since those are provenance
notes, not user-facing branding.

## Source Layout

```
android/
├── src/com/animeshahilya/espeakng/   # Java sources (15 classes)
│   └── preference/             # Custom preference widgets (5 classes)
├── jni/
│   ├── CMakeLists.txt          # Native build (links espeak-ng + JNI, builds libsonic)
│   ├── jni/eSpeakService.c     # JNI bridge (10 native methods)
│   └── include/                # config.h, Log.h
├── res/                        # Resources (46 locale translations, plus values-v21/-watch)
├── eSpeakTests/                # Instrumentation tests
├── build.gradle                # Gradle config
└── AndroidManifest.xml         # Services, activities, intents
```

## Testing

Tests are in `eSpeakTests/src/com/animeshahilya/espeakng/` — 100 instrumentation tests covering voice enumeration, settings, variant parsing, variant-catalog/data consistency (`VoiceVariantCatalogTest` checks `VoiceVariantPreference`'s hardcoded picker against the actual shipped `voices/!v/` files - see upstream #2376), locale translation (`testJavaToIanaCountryCode`), synthesis, bilingual/dictionary behavior, and the text pipeline (`TextPipelineDeviceTest` asserts paragraph breaks survive preprocessing, so the engine's longer paragraph pauses keep working). They require a connected device or emulator (`./gradlew connectedAndroidTest`). Note the suite reinstalls the app (wiping extracted data), so data-dependent tests must ensure extraction first - see `CheckVoiceDataTest.ensureVoiceData()` and the `ensureVoiceData()` calls in the direct-engine test setups. CI runs API 34; also run on API 35+ when possible (Locale canonicalizes legacy codes like `in`&#8594;`id` there - see `VoiceData.expectedEngineLanguage()`).

## Common Workflows

### Modifying JNI bindings

1. Add/change native method declaration in `SpeechSynthesis.java`
2. Implement in `jni/jni/eSpeakService.c` (function name follows JNI convention: `Java_com_animeshahilya_espeakng_SpeechSynthesis_<methodName>`)
3. `./gradlew assembleDebug` rebuilds native libs automatically

### Updating voice data

Voice data comes from the parent project's `dictsource/` and `phsource/`. The Gradle build runs CMake which rebuilds `espeak-ng-data/`, then zips it. Just run `./gradlew assembleDebug`.

### Adding a new preference/setting

1. Add preference key constant in `VoiceSettings.java`
2. Add preference XML in `res/xml/` or programmatically in `TtsSettingsActivity.java`
3. Wire it through `TtsService.onSynthesizeText()` to the appropriate `SpeechSynthesis` parameter

Settings and data paths strictly live in device-protected storage so that `TtsService` can read them
before the device is unlocked (Direct-Boot invariant, available since Android 7.0/API 24 - this
app's minSdk 26 floor is comfortably above it). Key invariants:
- `PrefsEspeakFragment` switches its `PreferenceManager` to device-protected storage.
- All helpers (`CheckVoiceData.getDataPath()`, `TtsSettingsActivity`) consistently resolve through `EspeakApp.getStorageContext()`.
- Never call `PreferenceManager.getDefaultSharedPreferences()` on a plain `Context`: the
  value lands in a credential-encrypted file that `EspeakApp` discards at the
  next process start, so the setting silently does nothing (#2536).
- `PreferenceStorageTest` fails if opening the settings screen creates a CE storage file.
