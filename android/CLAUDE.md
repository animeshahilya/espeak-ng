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

- compileSdk 37, minSdk 34 (Android 14+), targetSdk 37
- NDK 29.0.14206865, CMake 3.22.1
- JDK 17 (Temurin) in CI

The Gradle build has custom tasks that run automatically:
1. CMake builds `libttsespeak.so` (JNI) + generates `espeak-ng-data/` (all ~120 languages)
2. `splitLanguageData` splits that into `espeak-ng-data-core/` (English + every
   Indic/Dravidian language - see below) and `espeak-ng-data-extra/`
   (everything else)
3. `createDataArchive` zips the **core** subset into `res/raw/espeakdata.zip` -
   this is what actually ships in the APK
4. `createDataHash` generates SHA256 for upgrade detection
5. `createDataVersion` writes the hash to `res/raw/espeakdata_version`

`splitLanguageData` declares `inputs.dir(srcDir)` on the CMake-generated
`espeak-ng-data/` tree - without it Gradle has no way to know that directory
changed (it isn't produced by a task Gradle tracks file-by-file) and treats
the split as up-to-date forever after the first run, silently shipping a
stale core bundle on any incremental build that only touches
`espeak-ng-data/` (a new voice file, an updated dictionary) without also
touching a C/C++ source file. If a data-only change doesn't seem to reach
the APK, that input declaration is the first thing to check hasn't
regressed; `rm -rf build/generated/espeak-ng-data-core build/generated/espeak-ng-data-extra res/raw/espeakdata.zip`
forces a clean re-split either way.

`createExtraDataArchive` (not run automatically, and not wired into
`assembleDebug`/`assembleRelease`) zips the extra subset into
`build/outputs/data/espeakdata-extra.zip`. Run it explicitly and upload that
file as a GitHub Release asset named exactly `espeakdata-extra.zip` - the app
downloads it from `.../releases/latest/download/espeakdata-extra.zip` (see
"Language packs" below). Forgetting to re-upload it after a data-affecting
change leaves the app fetching a stale pack indefinitely, since nothing
currently version-checks it beyond a fresh `latest` release existing.

Native build disables `USE_ASYNC` and `USE_MBROLA` (not needed on Android) and
enables `USE_LIBSONIC` for speech rates above `espeakRATE_MAXIMUM`. libsonic has
no NDK sysroot package, so `jni/CMakeLists.txt` fetches and builds it from
source (pinned to a specific commit, bumped occasionally for upstream fixes
in `sonic.c` itself - that's the only file of the library actually compiled
here) and pre-seeds `SONIC_LIB`/`SONIC_INC` for `cmake/deps.cmake`.

`jni/CMakeLists.txt` also sets `-O3` gated to Release only (`$<$<CONFIG:Release>:-O3>`).
Don't make it unconditional again - it was for a while, and made native
breakpoints/variable inspection unreliable on debug builds since Gradle
never passes `-DCMAKE_BUILD_TYPE` explicitly here (AGP infers it from the
Debug/Release variant).

### Release signing

`build.gradle` loads `android/keystore.properties` (gitignored, alongside the
`.jks` it points to under `android/keystore/`) if present, and signs
`assembleRelease` with it; without the file, `assembleRelease` silently falls
back to an unsigned APK rather than failing. This app deliberately does **not**
enable R8/minification on the release build type: `SpeechSynthesis`'s native
methods are matched against exact unmangled Java method names by
`jni/jni/eSpeakService.c` (`Java_com_reecedunn_espeak_SpeechSynthesis_*`), and
an unguarded minify pass could rename them and silently break every JNI call
in the release build. Don't turn on `minifyEnabled` without adding a `-keep`
rule for that class first, and verifying on a real device.

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

### Key Classes (`src/com/reecedunn/espeak/`)

- **EspeakApp** — `Application` subclass; owns the device-protected storage context, the one-time migration of pre-2022 preferences into it, and the Wear launcher alias state (see below)
- **TtsService** — Android TTS engine service; handles `onSynthesizeText()`, voice selection, parameter setup. Also registers a receiver for `DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED` and reloads the engine's voice list on receipt, so a voice imported through `TtsSettingsActivity` becomes selectable without restarting the process. Also does text preprocessing before handing text to the native engine: `spaceSeparateDigits()` (the "Read numbers digit by digit" setting), `spaceSeparateSmartCodes()` (context-gated OTP/PIN/verification-code digit-by-digit reading plus ₹ → "rupees" expansion - gated on a nearby keyword like "otp"/"pin"/"code" so it doesn't misread ordinary numbers, e.g. a bare rupee amount), and `clarifyEmojiAnnouncements()` (sets emoji descriptions off with a light pause - "I'm happy, grinning face, today" - so they read as an aside rather than plain sentence text; only applies when the "Emoji processing" setting is Announce, not Ignore).
- **SpeechSynthesis** — JNI wrapper; loads `libttsespeak.so`, exposes native functions as Java API
- **UnicodeNormalization** — NFKC normalization of synthesis input (stylized Unicode → plain text) with a normalized→original offset map for `rangeStart()` word boundaries
- **VoiceSettings** — SharedPreferences wrapper for rate, pitch, volume, punctuation, variant
- **LanguageSettings** — Filters available voices by user-selected languages
- **CheckVoiceData** — Intent handler that verifies voice data files exist on device
- **DownloadVoiceData** — Extracts `espeakdata.zip` to device-protected storage
- **LanguagePackManager** — Downloads and extracts the optional "extra languages" pack from GitHub Releases (see "Language packs" below)
- **TtsSettingsActivity** — Preferences UI (voice variant, rate, pitch, etc.); also the `CONFIGURE_ENGINE` target and, on Wear, the launcher entry point
- **Voice / VoiceVariant** — Data models for voice metadata and variant parsing

### Launcher icon and Wear

There is no standalone launcher activity. The only `MAIN`/`LAUNCHER` entry is
the `.WearLauncher` `<activity-alias>` targeting `TtsSettingsActivity`, and it
ships `android:enabled="false"`. `EspeakApp.syncWearLauncherState()` enables it
at runtime on watches only; phones reach the settings UI through the gear in
the system Text-to-speech settings (`CONFIGURE_ENGINE`).

**Never disable that alias at runtime.** Disabling a launcher alias closes the
activity that was started through it — silently, with no exception, and
`DONT_KILL_APP` does not prevent it. An earlier revision shipped the alias
enabled and disabled it on phones at first launch, which made the settings
screen open and immediately vanish. A `@bool/`-with-`values-watch` override on
`android:enabled` does not work either: PackageManager parses that attribute
against a configuration with no UI mode, so it always resolves to the default.

### JNI Layer (`jni/jni/eSpeakService.c`)

10 JNI functions mapping `SpeechSynthesis.native*()` Java methods to `espeak_*()` C API calls (plus `JNI_OnLoad`, which isn't Java-callable). Audio flows back via `SynthCallback` → `nativeSynthCallback()` → `SynthesisCallback.audioAvailable()`.

### Voice Data Lifecycle

On first launch (or version mismatch), `DownloadVoiceData` extracts `res/raw/espeakdata.zip` (the core bundle) to device-protected storage. `CheckVoiceData` validates required files: `version`, `intonations`, `phondata`, `phonindex`, `phontab`, `en_dict`.

### Language packs

The APK only bundles English (including `en-in`, this fork's own Indian
English voice - see below) and the Indic (`lang/inc`)/Dravidian (`lang/dra`)
languages - roughly 3MB of dict/lang data versus ~28MB for the other ~100
languages `espeak-ng-data` ships. `android/build.gradle`'s `splitLanguageData`
task does this split (see `CORE_DICT_CODES`/`CORE_LANG_FAMILY_DIRS` there); it
operates purely at the Android packaging layer, after the shared CMake `data`
target has already built the full dataset, so it doesn't touch anything other
platforms/consumers of this repo depend on.

The rest is downloadable from Settings ("Download more languages",
`TtsSettingsActivity.createLanguagePackPreference`), fetched by
`LanguagePackManager` from a GitHub Release asset on this fork and extracted
into the *same* device-protected data path the core bundle uses - a
downloaded language's `_dict`/`lang/` files just become more files alongside
the core ones. This reuses `BROADCAST_LANGUAGES_UPDATED` (see `TtsService`
above) to make a running service pick up the new voices immediately.

Splitting `lang/` in lockstep with the dict files matters: a `lang/<family>/<code>`
file with no matching `_dict` bundled would make that voice appear selectable
in the picker and then fail to synthesize once chosen, rather than just not
appearing until its pack is downloaded.

This is the only thing in the app that uses the network
(`INTERNET`/`ACCESS_NETWORK_STATE` permissions exist solely for it) - it's
user-triggered from a confirmation dialog, never automatic.

### Indian English (`en-in`) and voice variants

`espeak-ng-data/lang/gmw/en-in` (added on this fork; upstream espeak-ng ships
no Indian English voice at all) tunes the base English phoneme table via
lightweight `replace` directives rather than a duplicated phoneme table:
retroflex `r.` for `r`, dental `t[`/`d[` stops for `T`/`D`, `v` for `w`/`v`,
plus `dictrules 1 2 8` for syllable-timed rhythm with unreduced full vowels.
Prefer this `replace`-directive style for any further Indian-language
phonetic tuning over copying a whole phoneme table - an upstream attempt at
en-IN that did the latter ([espeak-ng#1981](https://github.com/espeak-ng/espeak-ng/pull/1981))
was closed unmerged and covers less ground than this file does.

`espeak-ng-data/voices/!v/klatt_*` are custom Klatt-synthesis personas
(`klatt_paul`, `betty`, `harry`, `frank`, `kit`, `dennis`, `ursula`, `rita`,
`wendy`, `fast`/"Turbo") exposed in the Klatt category of
`VoiceVariantPreference`'s picker (`android/src/.../preference/VoiceVariantPreference.java`),
each needing a matching `variant_klatt_*` string in `res/values/strings.xml`
and a `VariantData` entry - `VoiceVariantCatalogTest` (see Testing) catches a
mismatch between the two. These are original parameter values in this
project's own style (character names as homage, not copied data) - the real
DECtalk source (`dectalk/dectalk`, `dectalk/463`) exists on GitHub but is
licensed `Other`/`NOASSERTION` with no clear redistribution rights, so it is
not a safe source to copy actual voice parameters from into this GPLv3 repo.

## Source Layout

```
android/
├── src/com/reecedunn/espeak/   # Java sources (15 classes)
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

Tests are in `eSpeakTests/src/com/reecedunn/espeak/test/` — instrumentation tests covering voice enumeration, settings, variant parsing, variant-catalog/data consistency (`VoiceVariantCatalogTest` checks `VoiceVariantPreference`'s hardcoded picker against the actual shipped `voices/!v/` files - see upstream #2376), and synthesis. They require a connected device or emulator.

## Common Workflows

### Modifying JNI bindings

1. Add/change native method declaration in `SpeechSynthesis.java`
2. Implement in `jni/jni/eSpeakService.c` (function name follows JNI convention: `Java_com_reecedunn_espeak_SpeechSynthesis_<methodName>`)
3. `./gradlew assembleDebug` rebuilds native libs automatically

### Updating voice data

Voice data comes from the parent project's `dictsource/` and `phsource/`. The Gradle build runs CMake which rebuilds `espeak-ng-data/`, then zips it. Just run `./gradlew assembleDebug`.

### Adding a new preference/setting

1. Add preference key constant in `VoiceSettings.java`
2. Add preference XML in `res/xml/` or programmatically in `TtsSettingsActivity.java`
3. Wire it through `TtsService.onSynthesizeText()` to the appropriate `SpeechSynthesis` parameter

Settings live in device-protected storage so that `TtsService` can read them
before the device is unlocked. Two things keep every writer on that one file:
`PrefsEspeakFragment` switches its `PreferenceManager` to device-protected
storage, so a `Preference` on the settings screen (and `getSharedPreferences()`
or `getEditor()` inside one) is already right; code outside the Preference
framework goes through `EspeakApp.getStorageContext()`. Never call
`PreferenceManager.getDefaultSharedPreferences()` on a plain `Context`: the
value lands in a credential-encrypted file that `EspeakApp` discards at the
next process start, so the setting silently does nothing (#2536).
`PreferenceStorageTest` fails if opening the settings screen creates that file.
