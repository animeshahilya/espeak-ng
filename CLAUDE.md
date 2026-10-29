# CLAUDE.md

Guidance for AI coding agents working in this repository. This is the only
agent instructions file - keep everything here rather than adding per-folder
copies.

## Project Overview

This repository is dedicated solely to the **eSpeak NG Android application**
(`com.animeshahilya.espeakng`). All non-Android desktop ports, desktop test
suites, editor integrations, and packaging tools have been removed.

- **`android/`** — the Android app: Java TTS service, UI, settings, NDK CMake
  build, JVM unit tests and instrumentation tests.
- **`src/libespeak-ng/`** — core C formant synthesizer, compiled into
  `libttsespeak.so`. **`src/speechPlayer/`**, **`src/ucd-tools/`**,
  **`src/include/`**, **`src/compat/`** — supporting native code and headers.
- **`src/espeak-ng.c`** — CLI binary run on the host during the build to
  compile phoneme and dictionary data.
- **`dictsource/`**, **`phsource/`**, **`espeak-ng-data/`** — pronunciation
  rules, phoneme definitions and voices, packed into the APK.

**Unless a path says otherwise, paths below are relative to `android/`**
(e.g. `jni/jni/eSpeakService.c` = `android/jni/jni/eSpeakService.c`).

Key invariants:
- **NVDA parity**: every non-Indian eSpeak voice reaches eSpeak with exactly
  the text NVDA would hand it. Extras are opt-in settings that default off.
- **Direct Boot**: all settings and engine data live in device-protected
  storage (`EspeakApp.getStorageContext()`) so TTS speaks before unlock.
- **Concurrency**: background work goes through `EspeakApp.runAsync()`.
- **Accessibility**: TalkBack-first UI (heading semantics, live regions,
  focus moves on sub-screen navigation, predictive back).

## Build Commands

All from `android/`:

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (signed if android/keystore.properties exists, unsigned otherwise)
./gradlew assembleRelease

# Run JVM unit tests (no device required, 40 test classes)
./gradlew testDebugUnitTest

# Run on-device instrumentation tests (requires connected device/emulator, 32 classes / ~180 tests)
./gradlew connectedAndroidTest
```

Output APKs land in `build/outputs/apk/debug/` and `build/outputs/apk/release/`.

## Build Configuration

- Package/namespace `com.animeshahilya.espeakng`; app label "eSpeak NG Advanced" (`R.string.app_name`, translatable="false" - single source, no per-locale override)
- compileSdk 37, minSdk 26 (Android 8.0+; the Snapdragon APK 27, for Qualcomm's QNN plugin), targetSdk 37
- NDK 30.0.16248370 (r30), CMake 3.22.1
- JDK 17 (Temurin) toolchain across dev and CI
- Native binaries built with 16 KB ELF page alignment for Android 15+ compatibility
- Edge-to-edge Material 3 UI with `WindowInsetsControllerCompat` and predictive back gesture support (`enableOnBackInvokedCallback="true"`, `OnBackPressedCallback`)
- `gradle.properties` enables Gradle's build cache and a larger daemon heap - build-time only, no effect on the shipped app; the native CMake build and the ~30MB multi-language data archive make clean builds slow enough for this to matter locally and in CI

Note: The app ships 153 languages.

The Gradle build has custom tasks that run automatically:
1. CMake builds `libttsespeak.so` (JNI) + generates `espeak-ng-data/` (all ~120 languages)
2. `createDataArchive` zips the tree except the compiled dictionaries into
   `res/raw/espeakdata.zip` (~0.5 MB); `createPackedAssets` puts every
   `*_dict` in the APK as `assets/espeak-dicts/<name>_dict.zst` (zstd 19 - 20/22 measured, no smaller,
   zstd-jni at build time, stored uncompressed; variant API) - every
   language ships in the APK, there is no core/extra split; it packs the
   text data in `android/data/` (symbols, chardesc, emoji, hinglish,
   numbers - plain text in git, written by `tools/`) the same way, as
   `assets/data/<path>.zst`, which `text/BundledData` opens for every
   loader (`SpeechSynthesis.unpackAsset`, the same native decoder). JVM
   tests read `android/data/` directly
3. `createDataHash` generates SHA256 for upgrade detection
4. `createDataVersion` writes the hash to `res/raw/espeakdata_version`

`createDataArchive` depends on `externalNativeBuildDebug`/`externalNativeBuildRelease`
so it always zips freshly-generated data; being a `Zip` task, its `from(...)`
source is tracked as a normal Gradle input, so an incremental build that only
touches `espeak-ng-data/` (a new voice file, an updated dictionary) without
touching a C/C++ source still re-zips correctly - no manual input declaration
needed here (unlike the old `splitLanguageData` task this replaced, which did
need one; see git history if resurrecting that split).

Native build disables `USE_ASYNC` (not needed on Android) and
enables `USE_LIBSONIC` for speech rates above `espeakRATE_MAXIMUM`.
MBROLA support was removed entirely from this fork (proprietary binary,
not viable/redistributable on Android); Klatt voices are the alternative. libsonic has
no NDK sysroot package, so `jni/CMakeLists.txt` fetches and builds it from
source (pinned to a specific commit, bumped occasionally for upstream fixes
in `sonic.c` itself - that's the only file of the library actually compiled
here) and pre-seeds `SONIC_LIB`/`SONIC_INC` for `cmake/deps.cmake`.

`libttsespeak.so` exports only its JNI functions (`jni/ttsespeak.map`), so the linker drops engine code JNI never reaches (the dictionary/phoneme compilers among it). If a new `native` method is added, it is exported automatically (`Java_*`).

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
`Java_com_animeshahilya_espeakng_SpeechSynthesis_*` for its 17 `native`
methods, plus explicit `GetMethodID()` lookups in `nativeClassInit()` for
`nativeSynthCallback`/`nativeSynthWordCallback` (private methods invoked
*from* native code, with no Java-side call site R8 can see marking them
used). If that keep rule is ever narrowed or removed, minification will
silently break every JNI call in the release build with no compile-time
warning - verify a real release build's TTS synthesis on a real device after
touching either `proguard-rules.pro` or this class.

The same trap bit natural voices: `PiperModel.SessionGroup.mapped` (and `mappedMid`,
`mappedDecoder`) is never read in Java
(it only keeps the memory-mapped model alive while ONNX Runtime reads weights
from it), so R8 removed it as write-only; the buffer was collected and
unmapped seconds after a voice loaded and the next inference crashed natively
(SIGSEGV in `OrtSession.run`, a crash loop on every release build, first seen
on a Galaxy S25 Ultra). `proguard-rules.pro` keeps it. It bit twice: moving
the fields from `PiperModel` into `SessionGroup` (shared Rasa sessions) left
the rule naming the old class, so it matched nothing. Any field that exists
only to keep native memory alive needs such a rule; check the release dex
(`dexdump -h`) rather than trusting that one exists. Debug builds and short
tests hide this: it takes a garbage collection. A release APK repro: Natural
voices -> Downloaded voices -> Test voice, a few times.

Methods native code calls by name need the same care. Phonon's runner calls
`Phonon.Stop.stopped()` and `Phonon.Frames.frame(float[])`; both are lambdas,
which R8 merged into one class and renamed, so every Phonon request in a
release build aborted the process ("no non-static method Lnp;.stopped()Z",
Galaxy S25, 2026-10-24, until the crash guard suspended the voice).
`proguard-rules.pro` keeps the interface methods, which holds for every
implementation; a rule on implementing classes alone did not.

## Architecture

```
Android TTS Framework
        │
   TtsService (TextToSpeechService) ── TextPreprocessor (same pipeline for both engines)
        │                                   │
        │ eSpeak voice                      │ language switched to a natural voice
        │                                   ▼
        │                          PiperEngine ── PiperModel (ONNX Runtime, VITS)
        │                                   │ phonemes
   SpeechSynthesis (Java JNI wrapper) ◄─────┘ phonemizeForPiper / sonicStream
        │  JNI calls
   eSpeakService.c + piperPhonemizer.c (jni/jni/) — bridge layer
        │  C API (espeak_*)
   libespeak-ng (repo-root src/libespeak-ng/)
```

Both engines write audio through one `TtsAudioDispatcher` per request
(`TtsService.newDispatcher`): it splits PCM to the framework's buffer size,
runs the optional `AudioOptimizer` DSP, and maps synthesizer code-point
positions back through `TextOffsetMap` to the caller's UTF-16 text for word
ranges. `VoiceEngineRegistry` holds `EspeakVoiceEngine` and
`PiperVoiceEngine` (`VoiceEngine` SPI) for lifecycle fan-out only - stop
and trim memory; synthesis routing stays in `TtsService`. The service
registers them in `onCreate` and must unregister them in `onDestroy` (the
registry is process-wide and would otherwise leak the service).

### Natural voices (Piper)

Optional neural voices, chosen per language (Settings → Natural voices).
eSpeak's own voice list is untouched: a language assigned to a Piper voice
is spoken by it whichever eSpeak voice/variant of that language the client
asked for, so NVDA parity for eSpeak voices is unaffected.

**Off by default** (`piper_enabled` defaults false in both
`preferences.xml` and `PiperVoiceStore.isEnabled`): opt-in, for users who
want plain eSpeak. Off means nothing natural-voice runs: `resolve()` returns
null, TtsService's preference listener calls `PiperEngine.unloadAllUnless()` on
the switch, and ONNX Runtime is never even loaded (see Network). The
language, download and screen-reader rows depend on the switch; "Downloaded
voices" does not, so voices can still be deleted to free space.

- **Phonemes come from this fork.** `jni/jni/piperPhonemizer.c` mirrors
  piper-phonemize (`espeak_TextToPhonemesWithTerminator`, IPA, `(lang)` flags
  dropped, clause terminator -> punctuation phoneme) and additionally
  reports each clause's code-point span for word ranges and turns on
  `option_phoneme_input` so user-dictionary `[[...]]` overrides work.
  `PiperPhonemesTest` pins the id encoding to piper-tts 1.8's output for a
  real voice; the fork's own pronunciation changes can yield phonemes a voice
  has no id for - those are skipped and logged once per voice.
- **Having an id is not enough: the voice must have heard the symbol.**
  Every published voice was trained on upstream eSpeak's IPA, and Piper's
  id map is shared by all voices, so a fork-only symbol usually *has* an id
  that the voice never trained. Upstream never defined `r.`/`r.h` in
  `hi_base`, so it wrote ड़/ढ़ as the raw text "r."/"r.h" and the voices
  learned the flap from that; the fork's `ɽ`/`ɽʱ` came out garbled (Whisper
  large-v3-turbo, 14 Hindi sentences x 3 voices: 0/54 ड़/ढ़ words recognised,
  CER 13.2%; spelled upstream's way 34/54, CER 7.1% - better than upstream's
  own phonemes, 8.0%). `PiperPhonemes.trainedSpellings` maps such
  spellings back, per eSpeak voice and per training generation: piper
  1.0-1.2 voices (`PiperVoiceConfig.oldEspeak`, also when `piper_version`
  is missing) were trained on rhasspy's 2023 eSpeak NG snapshot
  (rhasspy/espeak-ng 0f65aa30), 1.3+ on upstream. The 2023-trained voices
  also miss upstream's *own* later changes (Swedish retroflexes, Ukrainian
  в = `β`, Portuguese nasals and r, Catalan, German `ʏ`). Each rule was kept
  only if it raised the share of words spelled exactly as the voice was
  trained (1500 CLDR/dictionary words per language, confirmed on 1500
  held-out ones, e.g. Ukrainian 906 -> 1443, pt-BR 306 -> 1131, Nepali
  540 -> 771; Whisper CER on 15 sentences: pt_BR-faber 28.9% -> 17.9%,
  ne_NP-google 39.8% -> 29.1%). Improvements that only reorder known symbols (schwa
  deletion, vowel length) are kept, not reverted. Checked by ear-proxy
  2026-10-07: Hindi rules undoing them (word-final `iː` -> `i`, no `ˌ` on
  it, stressed `ə` -> `ʌ`) raised held-out exact spellings 882 -> 1133 of
  1500, yet IndicConformer understood fewer lone words (Rohan 41 -> 38 of
  80, Priyamvada 47 -> 29: the short final ई is heard as ि). A spelling
  gain alone is not enough; confirm a rule with an ASR judge. `tools/piper_spellings.py`
  re-measures (`score`, `residual`) and regenerates
  `test/resources/piper_spellings_cases.tsv`, 480 real-word cases the JVM
  test runs through the Java table - its Python `RULES` mirror the Java
  ones. After changing a language's phonemes (fork or upstream merge),
  score it against both reference builds.
  `PIPER_TERMINATOR_MASK` also drops `CLAUSE_OPTIONAL_SPACE_AFTER`: with it,
  danda/Urdu/CJK full stops and paragraph ends reached the model with no
  final punctuation (upstream piper-phonemize still has that gap).
- **eSpeak's clause reader looks one character ahead**, so the offsets it
  leaves behind land inside the next clause's first word;
  `PiperPhonemes.alignToText()` pulls each boundary back. Don't "fix" this in C.
- **Latency.** Chunks, not utterances: the first chunk is cut at the first
  clause past 60 phonemes, later ones at 220 (`PiperPhonemes`), so audio
  starts after one phrase. The next chunk renders on `piper-render` while
  the current one plays - model run *and* post-processing (level, trim,
  libsonic, PCM bytes): the framework blocks the synthesis thread until only
  ~0.5 s of audio is queued, so anything slow left on that thread is a gap.
  Before this, 142 s of Hindi played in 148 s (AudioFlinger: 250835 underrun
  frames); now 0. Measure gaps with `dumpsys media.audio_flinger`'s
  Underruns column or wall vs audio time - word-range callback timing has
  jitter of its own and invents stalls. Leading/trailing model silence is
  trimmed; chunk pauses follow reading pace. No pause after the last chunk.
  Across voices too (`PiperEngine.prepare`/`play`, `TtsService.NaturalAhead`):
  the next run of a mixed-language request starts rendering once the current
  run's last chunk has rendered (or while eSpeak speaks the part before it),
  so a switch waits for no render. Pixel 8, Hindi/Marathi/English mixes:
  no measurable gap before or after (voices there are fast enough); it is
  for slower phones. `stop()` cancels a run prepared ahead.
- **Lead-in (SYSPIN only).** The SYSPIN voices garble the first sound of an
  input, which is exactly what a language switch hands them: one word
  ("Delhi" -> "E", "battery" -> "vatri" on a Pixel 8). `PiperEngine.LEAD_IN`
  blank ids go after BOS and are cut at their exact frames (`cutLeadIn`
  zeroes their durations, so word timing, the phrase cache and the first-piece
  cut stay consistent; the split path decodes from after them, keeping them
  as context). Whisper, 20 English words x 3 renders: Priya Compact 10 -> 32
  of 60, Standard 10 -> 27; Pixel 8 end to end 10 of 20 words (before: 2 of
  9). Rasa and Piper voices said lone words well already and got worse with
  it (Mrunal 9 -> 4 of 12, Priyamvada 6 -> 4), so `needsLeadIn` is SYSPIN
  only (letter voices at 22050 Hz). Harness: lone words through the app's
  tokenization with the Ceil output exposed, scored by Whisper.
- **Streaming split** (2026-09-30, from piper1-gpl PR #302 and Sonata's
  "fast"/RT voices). `PiperSplit` cuts each voice into encoder (text
  encoder, durations, flow - the whole chunk, so prosody is unchanged) and
  decoder (HiFi-GAN) at the tensors "/dec/" nodes read from outside it.
  The decoder is convolutional, so pieces decoded with 12 frames of context
  per side (`PiperModel.DECODE_OVERLAP`) join exactly: measured 12 exact,
  8 leaves -26 to -69 dB of error, Sonata's 3 -13 to -33 dB. Only the first
  chunk of each text is rendered in two pieces (`PiperEngine.Pass`), cut at
  a word start (so word timing stays exact) placed by the voice's measured
  decoder speed: the rest must decode before the first piece has played,
  so a slow (Enhanced) voice gets a longer first piece instead of a gap.
  Pieces are trimmed only at the chunk's ends. Both pieces are at least
  1 s (`MIN_PIECE_S`) or the chunk renders whole, and the second piece is
  levelled from the whole chunk (`PiperAudio.speechPower` of both), so
  everything after the first second matches rendering whole exactly; only
  the first second has its own level (0.6 dB off on average). The first
  version levelled the rest from a first piece as short as one word: a
  Hindi run inside English text came out ~3 dB quieter and
  `PiperE2EDeviceTest.j_*` failed - keep that test passing. Same-draw
  audio is otherwise identical (1e-6); the user heard no difference.
  Pixel 8, long first sentence (`PiperLatencyDeviceTest`, which also
  stops 30 times mid-chunk): first audio Hindi Priyamvada 748 -> 281-490
  ms, English LibriVox 577 -> ~300 ms (cool phone with 0.25 s pieces; 1 s
  pieces, warm phone, for the upper Hindi figure); total render the same
  to +7%. Encoder share of a run: ~30% for
  medium voices, ~5% for high, so Enhanced gains most (PC sim at the
  Pixel's ~2x: 1377 -> 706 ms, no gap). Compare builds with the same
  random draw: two runs of one build already differ audibly.
  **Growing pieces (2026-10-15).** Once a voice has rendered a chunk in
  this process its usual level is known (`PiperEngine.mVoiceLevel`, a
  log-average of whole chunks), and the first chunk is cut into growing
  pieces instead (`pieceEnds`): the first ends at the first word start
  0.3 s in (`FIRST_PIECE_S`) from which every later piece, each as long as
  possible, still decodes - with its 12+12 frames of context - while the
  ones before it play; all pieces take the voice's level, so a short first
  piece is no loudness risk. Later pieces decode in one renderer task
  queued at once (before the next chunk), handed over through
  CompletableFutures; `Rendered.restTask` is what a stop awaits. Only for
  a decoder faster than real time with the margin; otherwise the 1 s
  two-piece rule above. Fresh text (said once, so the phrase cache never
  answers; `PiperLatencyDeviceTest.freshTextFirstAudio`), median first
  audio, two runs each: Pixel 8 Kannada Chetan (Rasa, CPU) phrases
  496/521 -> 253/235 ms, sentences 624/513 -> 280/266 ms; Mamta (SYSPIN)
  sentences 254/234 -> 224/232; Priyamvada 73-84/128-139 -> 80-90/144-148
  (noise). Galaxy S25 Ultra (NPU decoder) Priyamvada phrases 69 -> 54 ms,
  sentences 93 -> 80 ms.
  **A playing voice is never evicted** (`PiperEngine.mPlaying`): a voice
  loading in the background (startup preload of recent voices) evicted the
  LRU voice even while it spoke, closing it, and the rest of the text went
  silent (found by `PiperE2EDeviceTest.b_*` in the full suite: 0.5 s of a
  sentence). It goes at a later load instead.
  **Service rebinds** (2026-10-19): the engine is process-wide, so only the
  last `TtsService` instance frees the voices, and its unload re-checks when
  it runs (`PiperEngine.unloadAllUnless`): a client that rebound right after
  the last one left got a new instance whose preload found the voices still
  loading and skipped them, and the old instance's queued unload then emptied
  them - its requests were read by eSpeak (`PiperE2EDeviceTest.i_*` in the
  full suite on the S25). `onDestroy` unregisters only its own engines.
- **Model loading** (`PiperModel`, numbers from `PiperBenchDeviceTest` on a
  Pixel 8): the first load writes `model.<ORT version>.enc.ort` and
  `.dec.ort` beside the model (`.whole.ort` for a voice that doesn't split;
  the older `.opt.ort` is deleted and rebuilt once, ~3 s) - ORT-format,
  graph-optimized once, with the duration output exposed
  (`PiperAlignment`, the in-Java equivalent of piper1-gpl's
  patch_voice_with_alignment.py). Later loads memory-map them and use their
  weights in place (~15 MB private memory per voice instead of ~80; 1.1-1.3 s
  loads instead of 2.1-3.5 s). Each run shrinks ORT's arena (it otherwise
  keeps ~160 MB of peak per voice); a tiny warm-up run follows every load.
  Re-measured 2026-09-29 (Priyamvada, 3 rounds): this setup renders 11.4x
  real time, the same as copying the weights into memory (11.1-11.6x, +77
  MB); only skipping the arena shrink is faster (12.7x, +160 MB per loaded
  voice - not worth it). Earlier notes of 20-40% lower throughput for the
  mapped weights did not reproduce. Chunking was checked too: every
  sentence ends a chunk, so render-ahead keeps up without gaps down to 2.5x
  real time (0.3 s of gaps in 135 s at 1.8x, an Enhanced voice here).
  XNNPACK was 3x slower; NNAPI was removed (see below).
  ARM session options were measured too (Pixel 8, 2026-09-29), with no gain:
  `mlas.enable_gemm_fastmath_arm64_bfloat16` (same speed, VITS is not
  GEMM-bound), `session.dynamic_block_base` (0-10% slower) and
  `session.intra_op.allow_spinning=0` (Hindi 11x -> 8x, first clause 80 ->
  390 ms). eSpeak's own code already builds -O3 + ThinLTO; no -mcpu flag,
  since the arm64 baseline (armv8.0) is all Android guarantees.
- **Word timing.** Exact from the model's durations when the IPA words pair
  up one to one with the text's words, else proportional
  (`PiperAudio.alignedWordFrames` / `estimateWordFrames`).
- **Never silent.** `TtsService` only uses a model that is already loaded
  (`PiperEngine.getLoaded`); otherwise it speaks with eSpeak and calls
  `preload()`. Models preload on `onLoadLanguage/onLoadVoice`, on settings
  change and at service start (system language). Voices kept loaded (LRU)
  follow `PiperDevice`'s tier: 3 on flagships (Media Performance Class or
  7+ GB), 2 mid-range, 1 on low-RAM phones; `onTrimMemory` keeps only the
  current one. A failed load is not retried for 60 s. Loads (including the
  settings screen's test) all run on the one loader thread.
- **Device fit.** Intra-op threads = cores with at least half the top
  core's `cpu_capacity`, max 4 (`PiperEngine.threadsFor`): 4 on a Pixel 8,
  2 on a 2+6 phone. The catalog offers Piper "medium" as Standard and "high"
  as Enhanced (low/x_low dropped; every language keeps a voice); Enhanced
  says "recommended" on HIGH tier and "may be slow" on LOW.
- **No NNAPI** (removed 2026-10-08 with its opt-in switch, user's call):
  on a Pixel 8 it took ~10 of ~2700 VITS nodes, ran them on Android's
  reference CPU driver (15% slower) and failed the run for the unoptimized
  graph. Snapdragon phones get the NPU directly from the Snapdragon build
  (QNN) instead. Not yet measured: NNAPI on a Snapdragon too old to be
  offered NPU voices (outside NpuFiles.TARGETS).
- **Stop.** `onStop()` -> `PiperEngine.stop()` -> ORT `RunOptions.setTerminate`,
  plus the chunk loop checks between chunks.
- **Customization.** `piper_style` (Steady/Natural/Lively) multiplies the
  voice's own `noise_scale`/`noise_w` (0.5/0.5, 1/1, 1.3/1.25; measured on
  a Pixel 8 with Priyamvada: mean utterance length 112.6k/117.3k/120.6k
  bytes). Per voice, under Downloaded voices: `piper_speed_<key>` overrides
  `piper_speed`, and `piper_speaker_<key>` picks the speaker of multi-speaker
  voices (names from `speaker_id_map`; the pref was read but had no UI
  before). Test voice uses all of these.
- **Startup preload.** `piper_recent` (device-local, not backed up) keeps
  the last 3 voices used; the service preloads them after the system
  language's, up to the tier's loaded-voice budget. Before, after a service
  restart the first Hindi words were eSpeak's while the voice loaded (~1 s).
- **Speed/pitch.** Model speed via `length_scale` clamped to 0.5-1.8x; the
  rest (rate boost) and pitch via `SpeechSynthesis.sonicStream` (the
  libsonic already linked into espeak-ng), one stream per chunk across its
  pieces (`PiperEngine.Pass.Effects`, with the treble cut): until 2026-10-24
  each piece was stretched and flushed alone, leaving 3-6 ms of digital
  silence and a click at every piece join. Pitch settings 40 (this app's
  default) to 50 (eSpeak's, carried over from older installs) all keep the
  voice's own pitch (`RequestSettings.naturalPitch`); centred on 50 alone,
  the default 40 pitched every voice 7% down through libsonic (Kannada user:
  "annoying sound"). SSML stays on eSpeak; single
  characters too unless `piper_espeak_for_characters` is off. In Indian
  scripts a "character" is an akshara (`TextPreprocessor.isSingleIndicCluster`:
  a letter with its vowel signs, nukta, and virama-joined consonants), as
  TalkBack types, deletes and moves over them; counted as code points,
  कि/क्ष/क्/ड़ reached the natural voices, which garble a lone syllable
  (user report, 2026-10-08). A lone letter run inside a mixed request
  (TalkBack's "क्ष, deleted" with an English UI) stays with eSpeak too
  (`TtsService.isLoneLetter`), and a half letter is named ("क हलन्त") where
  eSpeak alone said a 0.07 s click. Verified on a Pixel 8 by logging the
  route each request took.
- **Threading.** Phonemizing goes through `sSynthLock` like every other
  espeak_* call and switches the native voice, so it clears `sLastVoiceKey`.
  Inference holds `PiperModel`'s read lock; eviction takes the write lock.
- **Storage/download.** Voices live in device-protected
  `files/piper/voices/<key>/model.onnx{,.json}` plus the optimized copy,
  deleted with the voice (`PiperVoiceStore`). Once the optimized copy has
  loaded, `ModelFiles.dropOriginal` deletes model.onnx (and a shared
  original): the phone kept both, twice the space (Pixel 8, 2026-10-08:
  Priyamvada 35 -> 18 MB, Hetal 93 -> 49, Kareem 137 -> 74). `source` (the
  model MD5) then stands for it: it marks the voice installed, keys the
  phrase cache and session sharing (`ModelFiles.stamp`), and a second Rasa
  voice installs from the shared optimized copy alone
  (`ModelFiles.hasSharedOptimized`, 28 kB, loads in 2 ms beside the first).
  After an ONNX Runtime update the older copy is adopted
  (`adoptOlderOptimized`; ORT format loads in newer runtimes); if it fails
  it is deleted and `PiperDownloads.restoreMissing` downloads the voice
  again at the next service start. Since 2026-10-15 the Snapdragon build
  drops originals too: its NPU graphs come hosted, not cut from them. Direct
  Boot works). Models sharing one upstream file (all 20 Rasa voices share
  `vits-rasa-13-piper-model.onnx`) keep a single content-addressed copy in
  `files/piper/shared/<md5>.onnx`, symlinked into each voice directory
  (`linkOrCopy`, copy fallback; hardlinks are denied by Android's SELinux
  policy in app storage, so the first version silently copied everything); a voice whose bytes are already shared
  installs with no download (`tryInstallShared`, `-1` from `start()`),
  `dedupSharedModels` folds older duplicate copies on install and on
  `reconcile()`, and deleting the last voice using bytes garbage-collects
  them. Copies another ONNX Runtime version wrote (`<md5>.<version>.*`) go
  once the current runtime's copy exists - the Snapdragon build keeps
  originals, so a runtime update re-optimized and left the old copies (S25:
  1.29 and 1.31 beside 1.30, ~50 MB); the version comes from
  `PiperModel.loadedRuntimeVersion()`, null until a voice loaded the runtime
  (asking would load it). `PiperDownloads`: catalog = rhasspy/piper-voices `voices.json`
  (cached 7 days); the small config is fetched first and refused unless
  `phoneme_type` is espeak/text; the model goes through `DownloadManager` to
  app-specific external storage, is MD5-checked, then moved in by
  `PiperDownloadReceiver` (exported for the system's DOWNLOAD_COMPLETE; acts
  only on ids it enqueued). The first voice of a language is auto-assigned
  (`assignIfFirst`) - only the first: with another of its voices installed,
  "none chosen" means the user chose eSpeak, and before 2026-10-12 a second
  download overrode that.
  `piper_dl_*` bookkeeping prefs are excluded from backups. `complete()` is
  synchronized: the receiver and the page's `reconcile()` can race on it.
- **Community voices.** `assets/piper/extra_voices.json` (voices.json format
  plus `base_url`, `source`, `license`) adds languages rhasspy lacks: Tamil
  (tinisoft rasa female/male, CC BY 4.0; Jeyaram-K hemalatha female / valluvar male, Apache-2.0) and Sinhala (chan4lk, MIT);
  Emirati Arabic female (vadimbelsky, MIT, 22.05 kHz, eSpeak ar phonemes) providing a premier natural female voice alongside the official Kareem male voice; and,
  added 2026-09-29 from a scan of ~2000 Hugging Face repos, Kurmanji (90 speakers,
  Common Voice 24, MIT, default speaker 54), Latvian Rudolfs (Latvian Library
  for the Blind audiobooks with permission, CC0) and Sinhala Dilu (MIT).
  Added 2026-09-29 from the SherpaVoices catalog, each rendered through
  this fork's phonemes and heard first: Indian English SPICOR
  (NavGurukul, AGPL-3.0), Tracy ManyVoice (Bryce Beattie, 16 LibriVox
  speakers, public domain), British assistant Jarvis (jgkawell, MIT),
  HAL 9000 (campwill, Apache-2.0) and Nepali Seto Bagh (Wiseyak, 18 speakers,
  OpenRAIL). Removed 2026-09-30: en_AU LibriVox (DataCraftsmanAustralia),
  which garbles a word spoken on its own ("office" alone -> "in the
  wallpits", Whisper, 3/3) and so every English word switched out of Hindi
  text; whole sentences were fine. Test a candidate on isolated words too
  (SPICOR and Tracy pass). Of SherpaVoices' other 44 Piper voices not carried here, 35
  are named-person/character clones and the rest lack a license or
  provenance, are synthetic-data or non-commercial (OpenVoiceOS Dii/Miro),
  low quality (Sinhala Weerawardhana, 16 kHz), or duplicates. Its Mimic3
  Gujarati would need a Mimic3 input mode ("#" word separator, own id map).
  Rejected in that scan: clones of named people or game/film characters,
  voices named after Azure voices, proprietary or no-provenance models.
  Sinhala voices lack the fork's prenasal `ⁿ` id (skipped, logged). Each
  `base_url` is pinned to a commit so its MD5s always match; only this
  bundled list may set `base_url` (the remote catalog cannot redirect
  downloads - `PiperCatalogTest`). Vet before adding: public, licensed,
  `phoneme_type` espeak, not trained on another company's TTS output, and
  listened to. Their configs name things loosely, so `PiperVoiceConfig`
  takes the display name and region from the catalog key.
- **Kept voices** (2026-10-05). `assets/piper/kept_voices.json` lists, per
  language Whisper large-v3 can transcribe, the two voices it understood
  best (character error rate over six levels: lone words, random words,
  rare words, short and long sentences, a paragraph; close calls rerun on
  a second corpus; harness and full results in scratch/voice-eval).
  `PiperDownloads.keptOnly` hides the rest of a tested language's voices,
  rhasspy's included, plus their Compact versions; untested languages
  (Bhojpuri, Maithili, Kurmanji...) keep all. Voices already downloaded keep
  working. The other bundled extras and NPU decoders were removed.
  2026-10-06: Norwegian keeps only talesyntese (nvcc garbled plain
  sentences: 171 words only it missed vs 5). Whisper cannot judge Indian
  languages (it writes Malayalam in Devanagari or English, Assamese in
  Devanagari), so the Indian voices were re-judged with AI4Bharat's
  IndicConformer (59 voices x 30 lone words + 30 sentences; harness
  scratch/voice-eval big_*.py, big_report.md): Bengali Riya -> Tithi (lone
  words 13 -> 20 of 30), Malayalam Aparna -> Meera (19 -> 24), Nepali Google
  -> Chitwan (8 -> 19); the other Indian choices held. SYSPIN voices garble
  lone words (Telugu Aditya 1/30, Gujarati 4-8/30) and no tried fix helped
  (final '।', longer lead-in, 30% slower). 2026-10-07: English also keeps
  Indian English Rahul (user's choice, after the long-sentence fix below),
  his extras, Compact and NPU entries restored from 8ff10277^.
  2026-10-08: Catalan adds Rviure/ca_ES-ona-high (UPC-FestCat Ona, piper 1.5,
  CC BY-SA 3.0, pinned 6283b705; INT8 in int8-v1, 114 -> 28 MB, log-mel
  0.75 dB): Whisper CER 7.3% vs upc_ona-medium 8.8%, user listened. Turkish
  ozgurguler antalia was rejected (55% CER).
  2026-10-24: Kannada keeps four - SYSPIN Vikram/Ananya back beside Rasa
  Spoorthi/Chetan at a Kannada user's request (they liked them; the ASR
  ranking had dropped them). Since 2026-10-26 they ship int8-v1 like the
  other SYSPIN voices (44 -> 30 MB; PC log-mel to the original 1.91/2.71 dB
  vs 1.45/2.51 for the old Compact, ASR CER unchanged) under the `-medium`
  key and config; an installed old copy is replaced by the daily update
  check. NPU files since 2026-10-27: the INT8 decoders npu-v1 already had
  (vits-syspin-kn-*-npu.onnx), encoder/flow built by build_npu_set.py and
  compiled for the three chips (Galaxy S25 Ultra, Vikram, fresh text, first
  audio: phrases 407 -> 70 ms, sentences 313 -> 65 ms vs the CPU).
- **Per-voice fixes** (2026-10-06, from a per-voice diagnosis: words one
  voice misses that the language's other voices say fine; scratch/voice-eval
  findings.md, diag.py, exp_fix.py). Applied only where Whisper showed a gain,
  never globally (a final '.' for every voice moved the average by nothing and
  broke some voices). `PiperVoiceConfig.FINAL_STOP`: '.' ends an unpunctuated
  sentence (`PiperPhonemes.chunk`), Rapunzelina 38.6 -> 29.8% (short phrases
  71 -> 30%). `PiperVoiceConfig.TASHKEEL`: Arabic vowel marks before eSpeak
  (`Tashkeel`, a port of piper-phonemize's libtashkeel, same output as the
  Python reference), Kareem 16.6 -> 9.7%; text the writer already vowelled is
  left alone; clause spans are mapped back (`PiperPhonemes.remap`) so word
  ranges stay on the caller's text. Its 10 MB model (MIT, rhasspy/piper-
  phonemize pinned commit, MD5-checked) is fetched beside the voice by
  `PiperDownloads.fetchVoiceExtras` (with the NPU decoders), so the APK does not
  grow; without it Kareem reads unvowelled as before. Emirati was trained
  without vowel marks and gets worse with them (15 -> 19%).
  `PiperVoiceConfig.SHORT_CHUNKS` (2026-10-07): Indian English Rahul collapses
  past ~110 letters per model run (audio stops growing, the rest mumbled), so
  its long sentences become one clause per word and `chunk` cuts them at
  `PiperPhonemes.SHORT_CHUNK` (70) letters: Parakeet 37.7 -> 5.9% (Compact
  37.0 -> 6.6%). Its 2026-10-05 Whisper score (39.1, paragraph 100%) was this
  bug. Every other text voice keeps its length on long sentences. Whisper artifacts
  that looked like voice faults: Gujarati Jay "stopping mid-sentence" (Whisper
  dropping long audio: the same text per sentence 18%, like Hetal) and lone
  Gujarati words; Malayalam/Assamese written in Devanagari, Latin or English.
- **Acronyms, app names, phone numbers** (2026-10-08, US English Amy/HFC
  diagnosis, scratch/voice-eval/en_diag). The voices were not at fault:
  eSpeak itself reads OTP "ot-p", UPI "yoopi", KYC "kick", FAQ "fack", and
  "9876543210" as "nine billion...". When a natural voice reads the
  request's language, `TextPreprocessor` always runs `TechWordsNormalizer`
  (the setting now only adds it for eSpeak, which keeps NVDA's reading)
  and `NumberReading.spellLongNumbers` (10+ digit runs digit by digit).
  The normalizer spells letters with spaces ("O T P"): its old hyphens
  joined them into one eSpeak word ("F-A-Q" -> "ɛfɐkjuː", the A lost); a
  lone "A" is the article to eSpeak, so it is hyphened to the next letter
  ("A-T M", "F A-Q"), and plurals are "O T P's" ("O T Ps" reads "P S").
  Parakeet, 4 renders: OTP/UPI/KYC/FAQ 0/16 -> 16/16 per voice; Pixel 8
  end to end confirmed. PhonePe -> "Phone Pay", Zomato -> "Zo-mato",
  Swiggy -> "Swig-ee" (small gains); WhatsApp is heard as "What's up"
  whatever the spelling. Lone short words ("End" -> "And", "All" -> "Oh",
  "On" -> "Um") were NOT changed: Parakeet mishears eSpeak's lone words
  the same way, so an ASR cannot judge them; routing them to eSpeak was
  tried and reverted. Indian names stay anglicized on US voices (Rahul is
  the Indian English choice).
- **Character voices (SYSPIN + Rasa, 2026-10-01).** 42 voices that read
  script letters, not eSpeak phonemes (`phoneme_type` "text"): 22 SYSPIN
  (IISc, Coqui VITS, MIT, 22050 Hz) and 20 AI4Bharat Rasa (CC BY 4.0,
  24000 Hz, one 20-speaker file; each voice is its own entry with
  `num_speakers` 1 and `default_speaker_id` = its speaker, which
  `PiperModel.putInputs` passes as `sid`, so the shared file downloads once
  per voice). Hosted on animeshahilya/sherpa-onnx-respin-syspin releases
  (`PiperDownloads.RESPIN_SYSPIN_RELEASES`, the one non-Hugging-Face
  `base_url` allowed; paths carry the tag, MD5s pin the bytes); configs
  and catalog entries come from that repo's `build_piper_configs.py`.
  `_`/`^`/`$` map to the model's blank, which reproduces its add_blank
  training input (one extra blank at each end). Text voices tokenize as
  NFC letters lower-cased, decomposing only letters the voice lacks
  (`PiperPhonemes.textTokens`): plain NFD split Bengali/Tamil vowel signs
  the voices were trained on composed. Digits become words in the voice's
  language (`PiperEngine.numberWords`): Indian languages through CLDR's
  spell-out rules bundled in `data/numbers/` (bn gu hi kn ml mr ne pa ta te;
  `tools/update_number_words.py`, CLDR main 8ed39456 - the Indian rules came
  after release 48.2; read by `RuleBasedNumberFormat`, checked by
  `NumberWordsTest` with desktop icu4j), the Devanagari languages eSpeak
  lacks (bho brx doi hne mag mai sa) with Hindi's, as eSpeak's Hindi
  stand-in already read them; other languages through the phone's ICU
  (Android's own had only hi/gu/ta/ne/en, Pixel 8, 2026-10-02). Since
  2026-10-15 only Assamese is left: where there are no rules
  `TtsService.naturalRuns` moves the digits to a run
  eSpeak reads in the request's language (`LanguageRuns.splitDigits`, run
  language "zxx", which no natural voice is ever chosen for). Before
  2026-10-02 the voice skipped them: "ಸಮಯ 10:30" was read as "ಸಮಯ". Languages eSpeak lacks (Bhojpuri,
  Chhattisgarhi, Magahi, Maithili, Sanskrit, Bodo, Dogri) keep their own
  language and, like SherpaVoices, become TTS voices of their own once a
  natural voice is chosen for them (`PiperVoiceStore.naturalOnlyVoices`,
  `Voice.naturalOnly`): eSpeak stands in with the rules of the script's
  language (`LanguageRuns.standIn`, Devanagari -> Hindi), which also drives
  text processing (`TextPreprocessor.languageTag`). They must be added in
  `CheckVoiceData` too: Android Settings' TTS language list comes from
  CHECK_TTS_DATA, not `onGetVoices`. Native `sample_text` in
  `values-b+hne` etc. Android has no name for "hne" (its lists say
  "hne (India)"); the app falls back to the catalog's English name
  (`PiperVoiceConfig.englishName`). `PiperSplit` also cuts Coqui
  (`/waveform_decoder/`) and transformers (`/decoder/`) decoders, keeping
  fp16 weights' upcast nodes with the decoder; checked on both: 12-frame
  overlapped decoding matches whole within 2e-6, 256 samples per frame.
  Device-verified on a Pixel 8 and a Galaxy S25 Ultra. Speed is their
  weakness: whole-model ORT on a Pixel 8 CPU, Priyamvada 12x real time vs
  SYSPIN/Rasa 0.8-1.7x (their HiFi-GAN decoders are 7-15x heavier), so
  long text stalls on CPU-only phones.
- **Snapdragon build** (`./gradlew assembleRelease -Psnapdragon` ->
  `espeak-snapdragon-release.apk`, ~90 MB vs 33): the same slim ONNX Runtime
  as the standard APK plus Qualcomm's QNN plugin
  (`com.qualcomm.qti:onnxruntime-android-qnn` 2.6.0, QAIRT 2.50, with
  `qnn-runtime` 2.50.0; since 2026-10-12 - Microsoft's built-in QNN package
  stopped at 1.29 / QNN 2.42). `NpuFiles.npuDevices` registers the plugin
  (`libonnxruntime_providers_qnn.so` from the native lib dir) and
  `addNpu` puts a session on its device; `backend_path` must be the full
  path of `libQnnHtp.so` (by `backend_type` alone the DSP could not open
  `libQnnHtpV79Skel.so`: QNN_DEVICE_ERROR_INVALID_CONFIG on a Galaxy S25
  Ultra). The plugin needs minSdk 27, so the Snapdragon APK has 27. NPU only
  on the chips we compile for (`NpuFiles.TARGETS`: SM8650 8 Gen 3, SM8750
  8 Elite, SM8850 8 Elite Gen 5; Android 12+); other chips get CPU voices.
  GPU, DSP, HTP v68/v69/v73 libraries and QNN's compiler are left out
  (`packaging.jniLibs.excludes`). Same speed as before on
  the S25 Ultra (Hindi first audio 123-132 ms). **Hosted NPU files, no
  graph cutting on the phone (2026-10-15):** every voice we host has its NPU
  graphs built on the PC (sherpa-onnx-respin-syspin `build_npu_enc.py` /
  `build_npu_set.py`, release `npu-v2`, list `assets/piper/npu_files.json`,
  fetched with the INT8 decoders by `PiperDownloads.fetchNpuDecoders`):
  the text encoder fixed to 256 ids (`npuenc.onnx`), the duration
  predictor + length regulator + noise as a CPU graph (`npumid.onnx`; the
  NPU cannot run its data-dependent NonZero), the flow fixed to 512 frames
  (SYSPIN 2048, ~6.8 frames per id; `npuflow.onnx`) and, for voices with no
  INT8 decoder (`npu.onnx`, npu_decoders.json), the decoder with its latent
  fixed to 80 frames (`npudec.onnx`). **Compiled on the PC, never on the
  phone (2026-10-22):** sherpa-onnx-respin-syspin `build_npu_ctx.py` compiles
  every such graph and the INT8 decoders for each target (fp16 HTP,
  `session.disable_quant_qdq`; INT8 with `offload_graph_io_quantization`;
  the wrapper's compatibility tag gets the target's HTP arch - the PC writes
  0, which the phone rejects), `build_supertonic_ctx.py` Supertonic's
  shared-weight buckets, `build_npu_ctx_catalog.py` the app's
  `assets/piper/npu_ctx.json`; releases `npu-ctx-v1-<sm8650|sm8750|sm8850>`
  (assets `<source md5>-<name>`). `PiperDownloads.fetchCompiled` downloads the
  phone's chip's pair under the names the phone used to give its own compiles
  (`model.<ort>.<file>.<size>.ctx.onnx` + `_qnn.bin`; INT8 `npuq.<size>`),
  writing the source's size (`<name>.size`) instead of the source, so the
  cache logic is unchanged; only `npumid.onnx` (a CPU graph) still downloads
  as a source. `PiperModel.attachNpu` loads them (failures as `-r1` markers)
  and checks they hand the decoder exactly this voice's boundary tensors;
  `encode()` runs text encoder (NPU) -> middle (CPU) -> flow (NPU) for a
  chunk within the buckets, else the CPU encoder. Older exports that name
  only their weights (Rohan, Arjun/Meera, Chitwan...) get the decoder file
  only. Galaxy S25 Ultra, Priyamvada fresh text: first audio phrases 54 ->
  39-44 ms, sentences 80 -> 54-58 ms (bench: text encoder 24 -> 2.8 ms, flow
  36 -> 1.8 ms; SNR 65-71 / 45-65 dB vs the CPU); loads 0.5 -> 0.8-1.2 s
  (three compiled graphs), compiled graphs ~33 MB per voice. Voices from
  rhasspy we cannot re-host (lessac, ryan) run on the CPU on Snapdragon.
  **NPU-first loads** (2026-10-19): once a voice's NPU parts are compiled
  (`npuReady`) and its boundary shapes are known (`model.<ort>.boundary`,
  written at the first load, tied to the encoder file's size and time), it
  loads without its CPU encoder: that session was 0.4-0.6 s of every load
  (a graph of thousands of nodes; prepacking off changed nothing). It opens
  on first need (`SessionGroup.cpuSession`: a chunk over the 256-id bucket or
  the flow's frames, or an NPU failure). The middle part keeps an ORT-format
  copy (`.npumid.<size>.ort`, its mapping held in `mappedMid`, see Release
  signing). S25 Ultra loads: Priyamvada 975 -> ~505 ms, Amy 620 -> ~235 ms.
  **Run-time NPU failure** (a DSP restart...): `retireNpu` takes that part
  out of use and the call is redone on the CPU; before, every request of the
  voice failed until the process restarted. Checked by injecting a fault
  into every NPU run: full E2E suite passed on the CPU.
  **Hosted sources go once compiled** (`dropHostedSources`, ~16 MB a voice):
  their size, which names the compiled files, stays in `<name>.size`
  (`hostedSize`); `fetchNpuFile` fetches a file again only when this runtime
  has nothing compiled from it (`compiledAvailable`, e.g. after an ORT
  update), and a voice waits on the CPU meanwhile (no failure marker). A
  replaced hosted file deletes what was compiled from it (`forgetCompiled`:
  compiled names are size-keyed, so a same-size rebuild kept the old graph).
  Voice folders also lose dangling links and copies another runtime made
  once this runtime's exist (`PiperDownloads.collectVoiceGarbage`, service
  start).
  **APK size:** without QNN's compiler (`libQnnHtpPrepare.so`, 81 MB
  unpacked) and the v69/v73 libraries the Snapdragon APK went 88 -> ~52 MB
  (2026-10-22); a voice's first use no longer waits for a 13-17 s compile.
  Supertonic's wrappers and the weights file they share must come from one
  build: its files are MD5-stamped (`<name>.md5`), never trusted by size
  (two builds' files matched in size and named different graphs: "context
  node name not exists in table qnn_models"). Rapunzelina's hosted npu-v2
  files are invalid ("scales" undefined), so it stays on the CPU.
  `decodeOnNpu` runs fixed windows with `DECODE_OVERLAP` context. All of the graph on the NPU or none
  (`disable_cpu_ep_fallback`); a failure keeps the CPU part and writes a
  `.failed` marker with the reason (bump the revision after NPU code changes
  so phones retry). The load log says where the decoder and encoder run.
  A missing weights file is never marked (`NpuFiles.remember`: a download
  replacing the compiled files under a load), a compiled graph counts only
  with its `_qnn.bin` (`compiledAvailable`), and downloads write weights
  before wrappers (`PiperDownloads.wrappersLast`). 2026-10-24, Galaxy S25:
  `restoreMissing` took a voice installed from the shared optimized copy
  (second Rasa voice) for missing and reinstalled it at every start, wiping
  its NPU files under a load; the marker then kept Chetan and Spoorthi (one
  session) on the CPU for good. It now counts the shared copy.
  Measured on the S25 Ultra, no gain, not used: `rpc_control_latency` 100,
  `htp_share_resource_optimization` (two voices loaded), ONNX Runtime 1.31
  (renders the same, loads voices 40-100% slower). Needs `<uses-native-library libcdsprpc.so>` in the
  manifest (Android 12+ hides it otherwise, and QNN silently falls back to
  the CPU); do not set ADSP_LIBRARY_PATH yourself (it made QNN reject 37
  convolutions). Galaxy S25 Ultra (8 Elite, HTP v79): decoders ~22x real
  time on the NPU vs ~4x on 6 CPU threads; Piper voices use it too. HTP
  fp16 is not bit-exact (11.9 dB SNR vs CPU for SYSPIN, 22.7 for Rasa;
  PC fp16 is 84 dB, so it is HTP arithmetic) - the user listened: "mostly
  same". QNN's GPU backend failed (ORT NHWC ConvTranspose bug). Threads: up to 6 on chips
  with no little cores (8 Elite: 6 threads 4.0x vs 4 threads 2.6x, 8
  threads 1.8x); others keep 4. CI builds only the standard APK.
  Two APKs on purpose: Qualcomm's QNN licence allows distributing its
  runtime only "as incorporated in Your software application", never "on a
  standalone basis", so a downloadable NPU pack for one app is not allowed
  (user chose two APKs over one 85 MB APK). Releases are signed locally: attach
  both `espeak-release.apk` and `espeak-snapdragon-release.apk` (named
  `espeak-ng-<version>.apk` / `espeak-ng-<version>-snapdragon.apk` on the
  release), then update the repo-root `latest.json` (versionCode, versionName,
  both URLs, both SHA-256s, one plain-text `notes` line) and push it: that file
  is what the in-app updater reads (`util/AppUpdater`, below), so a release
  without it is invisible to installed apps. The standard
  app on a chip in `NpuFiles.TARGETS` shows "Faster on this
  phone: Snapdragon version" (`PiperSettings.offersSnapdragonBuild`), opening
  the latest release; same package and key, so it installs over and keeps
  voices.
- **Supertonic 3** (2026-10-13), a BETA: Snapdragon build on a chip in
  `NpuFiles.TARGETS` (`PiperDownloads.supertonicCapable`) and only with the opt-in switch
  `piper_supertonic_beta` on (off by default; user, after testing the release
  build on the S25: "sometimes a jarring sound, does not read well - fine as a
  beta, not for stable users"). Off hides it from the download list;
  downloaded voices keep working. Supertone's flow-matching TTS
  (OpenRAIL-M), 31 languages x 10 styles (F1-F5, M1-M5) = 310 voices, all
  listed (user's call; exempt from `kept_voices`). Like the Rasa speakers each
  is its own catalog entry (`assets/piper/supertonic_voices.json`, keys
  `<code>-supertonic_<female|male>_<n>-medium`) over ONE shared model.onnx
  (the vector estimator); its per-voice `.onnx.json` (`phoneme_type`
  "supertonic") names language, style, 8 steps and the companion files
  (duration predictor, text encoder, vocoder, `unicode_indexer.json`,
  `tts.json`, `style.json`) that `PiperDownloads.fetchSupertonicFiles` puts in
  the shared store (MD5-named, kept by `collectSharedGarbage`) and links into
  the voice folder. All from release `supertonic-v1` on
  sherpa-onnx-respin-syspin (its `build_supertonic.py` / `_catalog.py`;
  samples in samples-v1). Files are INT8-weight (381 -> 110 MB; log-mel
  2.3-2.8 dB vs 10 dB between two random draws of the original). Runtime
  `Supertonic` (shared by `PiperModel.SessionGroup`, style per voice):
  duration predictor + text encoder on the CPU, unpadded; flow (8 steps) and
  vocoder on the NPU in size buckets (`Supertonic.BUCKETS`, 32/96/192 latent
  frames, 3072 samples each at 44.1 kHz; 32 since 2026-10-15 for screen-reader
  phrases, which are 20-35 frames with the model's silence and filled a
  quarter of the 96 bucket: S25 phrase first audio 517 -> 316-344 ms,
  sentences unchanged; the 192 bucket loads at first use
  (`Supertonic.LAZY_BUCKET`), so screen-reader use stays at two buckets'
  memory - PSS 564 MB before, 545-553 with three buckets lazily, 742 with
  all three loaded; long text loads it while its first chunk plays, 30 s
  of audio in 4.5 s), padded with masks, compiled on the PC per target
  (`build_supertonic_ctx.py`: lengths pinned, each stage's buckets sharing one
  weights file, 184 MB instead of 561) and downloaded into the shared store
  (`<md5>.<ort>.st-flow96.ctx.onnx` ... + `st-<flow|voc>-shared_qnn.bin`,
  `PiperDownloads.fetchSupertonicNpu`); `session.disable_quant_qdq` widens the
  INT8 weights for the fp16 graph. NPU-readiness rewrites done on the PC
  (both CPU-exact): Softplus -> log(1+exp) (QNN has none) and the 4-D
  attention MatMuls -> 3-D (the 4-D form crashed the HTP: DSP restart,
  error 1003). Text: `Supertonic.preprocess` ports Supertone's py/helper.py
  exactly (`SupertonicTest` checks it against cases their code wrote);
  numbers stay digits (Supertonic reads them, currency and units itself);
  characters its table lacks are repaired or dropped (`Supertonic.known`:
  their table gives -1, which Gather reads as its last row - an unrelated
  sound; Hindi ऋ -> रि, ॐ, ॥, Devanagari digits, arrows, stars).
  Runs (`Supertonic.chunks`, 2026-10-14): whole sentences, several to a run
  up to the budget, as Supertone's helper; a '.' ends a sentence only before
  a space and not after an abbreviation or initial (before, "3.5", "Dr.",
  "1.9.7", "U.S." were each cut into separately read runs - Parakeet 19.8%
  vs 10.3% on such sentences); a long sentence is cut at , ; : first, at
  a word only inside a too-long clause (evenly, ending in ',' so the voice
  keeps a continuing tone instead of the '.' preprocess adds). Budgets
  (first 60, later 160 letters; 30/80 Korean/Japanese) scale with the model
  speed. Speed: Supertonic's own pace is 154 wpm (x `RATE_FACTOR` to reach
  the set rate) and it garbles squeezed (Parakeet, long sentences: x1.0
  0.9%, x1.25 2.2%, x1.5 11%, x1.8 32%), so the model stops at
  `Supertonic.MAX_SPEED` 1.25 and libsonic does the rest (VITS keeps 1.8).
  Harness: scratch/supertonic3-eval/harness (PC CPU, INT8 files, the app's
  libsonic built for Windows). Word timing is
  estimated (no per-id durations); noise is seeded per chunk. Galaxy S25
  Ultra (bench): 13x real time at 8 steps (CPU alone 4.3x; Pixel 8 CPU
  1.0-1.5x, hence Snapdragon only), short TalkBack phrase ~0.4 s, ~460 MB
  RSS; flow per step 54 ms (96) / 111 ms (192). The user listened: NPU and
  CPU sound the same; fewer steps (4, 2) sound worse - keep 8. The slim ORT
  needed opset-19 kernels and Sin/Cos/Log/PRelu/Tile/BatchNormalization/
  LayerNormalization/MatMulNBits (`tools/ort-voices/voices_ops.config`).
  Kokoro (Apache-2.0) is next but only ~2.3x on the S25 NPU after six QNN
  fixes (see the user's memory notes); not added.
- **Phonon 31M** (2026-10-22, Snapdragon build on `NpuFiles.TARGETS` only,
  `PiperDownloads.phononOffered`): Gradium's English TTS (Apache-2.0 weights and
  37 voices, all offered; xn-ptts v0.4.0) on the NPU through its own QNN runner,
  vendored in `jni/phonon/` (`libphonon_jni.so`, built only with
  `-Pqairt=<QAIRT 2.50 root>`: QNN's headers are Qualcomm-confidential and stay
  out of the repo) plus its Rust text front end `libs/phonon-jni/.../libptts_text.so`
  (`tools/phonon/build_text.sh`). `piper/Phonon` (JNI) and `PhononBundle`: each
  voice installs its own ~1.5 MB file (model.onnx) plus shared metadata, tokenizer
  and BOS data (companion files, like Supertonic's); the chip's compiled model
  (~84 MB, from Gradium's bundle) comes through `npu_ctx.json` into `piper/phonon/`;
  every load assembles `piper/phonon/bundle/` from the installed voices (the runtime
  reads every voice its metadata lists) and one runtime serves them all. Text in
  (code points through the id pipeline), numbers read by Phonon itself, model speed
  fixed at 1 (libsonic does rate and pitch), 24 kHz resampled. `Phonon.load` sets
  `ADSP_LIBRARY_PATH` to the app's library folder only if unset - exactly what ONNX
  Runtime's QNN plugin sets; any other value broke ONNX Runtime's NPU graphs.
  Where offered, US/UK Piper English voices leave the download list
  (`phononForEnglish`); Rahul (Indian English) stays. S25 Ultra: "Button" 56 ms
  end to end, ~30x real time, PiperE2E 10/10 with Easton as English; release
  `phonon-v1` + per-chip models in `npu-ctx-v1-*` (`build_phonon_catalog.py`).
  The user listened: "sounds nice including other voices".
  **Streamed** (2026-10-23, user: "a little slow compared to Piper"): the
  runner hands each 80 ms frame to Java (`Phonon.Frames`), and a first chunk
  plays while it is made (`PiperEngine.Pass.renderStream`, on its own
  `phonon-stream` thread): growing pieces from 0.15 s of speech, each ending
  where the speech so far ends (`PiperAudio.speechSpan`), so silence is
  never played ahead of the speech after it and only the last piece trims
  the chunk's tail. It needs the voice's level, so each Phonon voice says a
  sentence at load (`learnStreamLevel`, +~90 ms to the load). Words are
  placed by the voice's measured pace (`mSamplesPerChar`). The runner also
  clears only the KV-cache slots the last chunk wrote (it zeroed all 6 MB:
  9-18 ms before every chunk). S25 Ultra, fresh text, first audio: phrases
  110-120 -> 24-25 ms, sentences 174-186 -> 24-25, long text 185-199 -> 44-49
  (Rahul, Piper on the NPU: 54-57 / 154-199 / 62-66), 30 stops mid-stream
  clean, PiperE2E 10/10 with Everly as English.
- **NPU decoders** (Snapdragon build): `assets/piper/npu_decoders.json`
  (voice key -> `npu-v1/<model>-npu.onnx` on sherpa-onnx-respin-syspin, made
  by its `build_npu.py`): each voice's decoder fully INT8 (Conv, ConvTranspose,
  LeakyRelu, Add, Div, Tanh, Mul), fixed 80-frame window, the Standard
  decoder's input names. `PiperDownloads.fetchNpuDecoders` (service start and
  after each install; any network, mobile data too; MD5-checked, `npu.onnx`
  + `.md5` stamp in the voice folder) then reloads the voice;
  `PiperModel.attachInt8Npu` compiles it once (`model.<ort>.npuq.<size>.onnx`)
  and uses it if its inputs match the split, else the FP16 graph. S25 Ultra,
  Kavya: INT8 24.5x vs FP16 12.4x real time, same accuracy (speech SNR 12.4
  vs 11.9 dB, pause noise -48.2 vs -48.9 dBFS); Compact on the NPU gains
  nothing (its float islands - LeakyRelu, last stage - fall back to the CPU),
  and full INT8 is bad on a CPU (pause noise -38 dBFS), so the three stay
  separate files. Covered: the kept SYSPIN/Rasa keys (now the Compact model,
  whose split reads the same names) and Piper high (32).
  Speed is measured, not assumed: an NPU graph is kept only if a timed window
  runs at `MIN_NPU_SPEED` (4x) or more - only the 8 Elite was ever measured.
  NPU tuning, Galaxy S25 Ultra, 2026-10-08 (native bench, QNN 2.42 / ORT
  1.29 QNN): Piper decoder fp16 on the NPU 7 ms per 80-frame window (137x
  raw, ~99x after the 12+12 overlap); 160 frames ~106x, 240 ~77x, so 80
  stays. SYSPIN INT8 NPU decoder 21 ms per window (44x raw). QNN
  `htp_graph_finalization_optimization_mode=3`: INT8 unchanged, fp16 slower
  (137 -> 114x), compile 2.6x longer - not used. With the decoder at ~100x
  the CPU encoder is about half the time (Priyamvada ~70 ms a sentence, 6
  threads; 2 threads 10-25% slower, so threads unchanged). INT8-weight
  voices reach the fp16 NPU graph only with `session.disable_quant_qdq`
  (folds their DequantizeLinear weights); the fp16-decoder Standards
  (SYSPIN/Rasa/Piper high) are not accepted by it at all and rely on their
  INT8 NPU decoder file (all kept ones have one). NNAPI on the S25 is no
  better: whole Piper models fail in the driver (OP_FAILED), decoders run
  at CPU speed. The FastRPC "dspqueue"/"open_shell" errors QNN logs are
  harmless (7 ms per window includes the call).
- **Compact tier** (quality "compact", keys `<lang>-<name>-compact`, release
  `compact-v1` on sherpa-onnx-respin-syspin, made by its `build_compact.py`):
  the same voice with only its HiFi-GAN decoder in INT8 (static QDQ,
  per-channel, Conv/ConvTranspose), keeping the decoder's last upsampling
  stage and conv_post float (where INT8's noise floor showed: pause noise
  -50.6 -> -55.2 dBFS). Text encoder, durations and flow stay float, so word
  timing is unchanged (whole-model dynamic INT8 had shifted durations).
  Pixel 8 CPU, 4 threads: Kavya 1.9 -> 2.9x real time, Kaveri 1.2 -> 2.3x,
  LJSpeech high 1.3 -> 2.3x; 42-49 MB instead of 58-62 (114 for Piper
  high). User listened: "compact sounds ok". Offered for all 42 SYSPIN/Rasa
  voices and 10 Piper "high" voices whose licences allow re-hosting (not
  lessac: Blizzard licence; ryan: NC; es_MX-claude and en_US-libritts:
  older exports with unnamed graph nodes, so no decoder to find - they
  don't stream-split either).
  `PiperSplit` finds the latent as conv_pre's input (INT8 Rasa lists its
  speaker "cond" Conv first; node order alone fed it the speaker vector).
  Downloaded-voices storage totals count shared inodes once
  (`PiperVoiceStore.diskBytes`), plus the shared store's files derived
  from a voice's model (Supertonic's NPU graphs, linked from no voice
  folder). A downloaded voice shows its whole size and "shared with N
  other voices"; a catalog row shows what downloading it would add now
  (`CatalogVoice.downloadBytes`: model and Supertonic companions - listed
  as `extra_files` in its catalog entry - only where the shared store
  lacks them; a second Rasa or Supertonic voice is well under 1 MB).
  **SYSPIN/Rasa: one version since 2026-10-11** (user's call - the 2.9
  INT8 voices were well liked, the big Standard not needed). Each kept
  SYSPIN/Rasa voice is one catalog entry: the Standard key (`-medium`,
  quality "medium", its `piper-v2` config) with the Compact model
  (`int8-v1/...-compact.onnx`); no `-compact` entries, no `heavy` flag (so
  plain "Standard" labels and no Compact steering).
  **Piper "high" too since 2026-10-12** (user: "everything should be more
  unified"): de thorsten, kk issai, uk mykyta/oleksa are their `-high` key
  and config with the Compact model; the twin machinery (Compact-first
  catalog order, swap offers, `heavy` flag, `PiperDevice.heavyFit`) is
  gone. Their configs differ from Compact's in quality and indentation, so
  `mergeInto` patches the quality to the Standard's own and records the
  catalog's config MD5 (no second download). Installed copies move
  over: an old Standard through `checkForUpdates` (its model MD5 changed),
  an installed Compact through `PiperDownloads.mergeCompactTwins` at service
  start - folder renamed to the Standard key, config quality patched (the
  released configs then match the catalog's byte for byte, checked for all
  28), assignments/speed/speaker/recent prefs moved, a Standard copy beside
  it deleted. Its rule: the Compact key is no longer listed and its Standard
  twin is (`mergedTwin`). For the NPU, `PiperSplit` puts a Compact latent's
  QuantizeLinear/DequantizeLinear pair (and Rasa's speaker vector's, copied
  since the flow reads it too) in the decoder, so the boundary keeps the
  Standard names (`/Mul_7_output_0`, `/Add_output_0`) the INT8 NPU decoders
  read; checked on PC: enc+dec equals the whole model exactly, and the NPU
  file fed the Compact latent is as close as with the Standard one.
  `ModelFiles.forgetOptimized` re-splits a migrated voice where the original
  is still kept (Snapdragon build).
- **INT8 weights** (2026-10-08, release `int8-v1` on sherpa-onnx-respin-syspin,
  its `build_small.py`): every model the app lists is stored with INT8
  weights - per output channel, the clip with the least squared error,
  widened back to float by a DequantizeLinear in front of its consumer, so
  compute stays float (no activation clipping, no noise floor). Piper and
  community voices are bundled under their own keys (63 -> 17 MB; Piper high
  114-128 -> 43-47 MB, below); `PiperDownloads.mergeExtras` lets a bundled entry replace
  rhasspy's of the same key, and `checkForUpdates` moves installed copies to
  it. There is no second version of these: the user heard them as the same.
  Heavy voices kept Standard + Compact until 2026-10-11 (SYSPIN/Rasa now
  ship only Compact, see above; Piper high since 2026-10-12): Standard computes its HiFi-GAN
  decoder in fp16 (`_half_decoder`; conv_pre stays float so the split and
  the NPU decoders still read the float latent) - Pixel 8, SYSPIN decoder
  2.0 -> 2.4-2.7x real time, 62-78 dB SNR vs float32, 58 -> 43 MB (Rasa
  62 -> 48.5); Compact keeps its INT8 decoder (4.0-4.6x) and shrinks
  (SYSPIN 44 -> 30, Rasa 49 -> 34). Measured and rejected (2026-10-08, user's call): the
  fp16 decoder's weights stored INT8 and widened to fp16 per run
  (com.microsoft DequantizeLinear, fp16 scale; ORT keeps it unfolded, so
  the phone copy is small too): 43 -> 30 MB, 23 dB SNR, but the Pixel 8
  CPU decoder 6-7% slower, and it needs that kernel added to the slim
  runtime. Slow phones get the 30 MB Compact anyway. Measured (scratch/voice-eval/size,
  compare.py, noise scales 0): log-mel distance to the original 0.74-1.15
  dB over 11 voices (es_MX-claude 1.64; the first Compact was 1.43); ASR
  error rates unchanged within noise. Pixel 8, native ORT 1.29 bench
  (`/data/local/tmp`): whole Priyamvada 10x real time either way, so the
  per-run DequantizeLinear costs nothing; a Piper medium decoder is 17-18x
  in float32, INT8 or fp16 alike - nothing left to gain there; fp16 for
  Compact's float last stage: no clear gain. ONNX Runtime keeps the
  DequantizeLinear nodes when it optimizes, so the `.ort` copies stay small
  too. An fp16-stored weight's widening Cast is folded into its
  DequantizeLinear: through the Cast, `PiperSplit` passed every decoder
  weight across the split. INT4 (block-wise, opset 21): 4.3 dB, no size gain.
- **Phrase cache** (`PiperPhraseCache`, in `PiperEngine.Pass.render`):
  screen readers repeat short phrases ("Button", "Double-tap to activate"),
  so a chunk's raw model output is kept and replayed through the normal
  post-processing (level, trim, speed, pitch - so those settings still
  apply). Keyed on the voice's model file (key + size + mtime), speaker,
  model speed, style and the chunk's phoneme ids: a dictionary edit or voice
  update just misses. Chunks up to 3 s, kept from their first sighting, 8 MB
  of 16-bit audio, LRU, memory only (gone with the process - which is what
  makes first-sighting admission acceptable); forgotten on unload. Measured
  with `PhraseCacheReplayDeviceTest` (replays `tools/talkback_trace.py`'s
  TalkBack-like trace of real screens through the TTS API, render-to-file,
  ~1 min; driving real TalkBack over adb was unreliable - injected swipes and
  Alt+arrow keys moved focus only sometimes): Pixel 8, Priya Compact, 258
  utterances: second-sighting/float 46% of chunks (its ceiling), 35 entries
  in 5.7 MB; first-sighting/16-bit 60% (ceiling 64%), 92 entries in 7.1 MB;
  first audio 5 ms cached vs ~540 ms rendered. Counts only (never text) go
  to the log every 25 chunks and accumulate in prefs
  `piper_cache_hits_total` / `piper_cache_chunks_total`. Second tier on
  storage (`PiperPhraseCache.setDisk`, `files/piper/phrase-cache`,
  device-protected and outside the backup rules): 32 MB, a phrase written
  once heard twice (hits count as sightings - memory keeps it from the first,
  so its repeats are hits), each file `<voice key>-<hash>.pcm` holding its
  full key (checked on read: a hash collision never plays another phrase),
  oldest out past the cap (trimmed every 20 writes), a deleted voice's files
  deleted with it. Pixel 8, replay in two processes: run 2 (memory empty, as
  after the service restarts) 78% of chunks from the cache vs 60% cold, 45
  read back from storage at ~8 ms, whole session 46 vs 90 s. Idle
  pre-rendering not done: storage already covers restarts; only a style,
  speed or voice change starts the cache over.
  Android performance hints (ADPF) were measured and dropped (2026-10-01):
  a hint session over ORT's pools ("piper-loader" threads), the renderer and
  the synthesis thread, 100 ms target, reporting each chunk render. Pixel 8,
  the replay test inside 150 s Perfetto power-rail traces, off/on/off/on:
  CPU energy 192.7 vs 181.3 J (within the run-to-run drift, which fell
  200 -> 183 -> 186 -> 179 J as the phone settled), render to first audio
  614 vs 600 ms (two "off" runs alone differed 540 vs 687 ms). No gain, no
  measurable cost. Perfetto configs must be in /data/misc/perfetto-configs;
  rails are power.rails.cpu.big/.mid/.little in uWs.
- **Voice-file auto-update** (`PiperDownloads.checkForUpdates`, from the
  service's warm-up thread, at most daily, natural voices on): each voice
  keeps a `source` file (catalog model + config MD5; hashed once, streamed,
  for older installs); a changed catalog entry re-downloads through the
  normal path at once, on any network and without waiting for charging
  (the user asked for neither restriction),
  and `complete()` swaps it in only after the checksum matches. The voices
  -changed broadcast unloads that key so the new files load on next use.
  The bundled list changes with app updates, rhasspy's with its voices.json.
- **Language switching.** Like eSpeak switching language by alphabet,
  `LanguageRuns` splits each unit by script (Devanagari -> hin, Latin ->
  eng, ...; a script the speaking language is written in stays with it)
  and each run goes to its language's natural voice when one is chosen and
  already loaded - the first use starts loading it and the current voice
  reads that run meanwhile. Preloading
  only on phones that keep several voices (`keepsSeveralLoaded`), so a
  one-voice phone does not evict and reload on every switch. A run in a
  language with no loaded natural voice (Gujarati with only Hindi and
  English natural voices, or English words with only Hindi - the user's
  choice) is read by eSpeak in that language: `TtsService.espeakReadsPart`
  sends such a request down the eSpeak path, where `withNaturalRuns` gives
  the voice's own runs back to its natural voice. Before 2026-09-30 such
  runs stayed with the current natural voice (Priyamvada read Gujarati
  from eSpeak's Gujarati phonemes). A request has one sample rate, so a
  voice at another rate is resampled to it (`PcmResampler`, windowed sinc,
  in `TtsService`). Until 2026-10-02 such voices were skipped
  instead: the 24 kHz Rasa voices (Kannada Spoorthi/Chetan, Tamil Kaveri...)
  never spoke inside English or Hindi requests - a tester reported them as
  "not responding" - and a 24 kHz primary voice read English itself. Numbers and
  times (`VoiceSettings.PREF_NUMBERS_LANGUAGE`, on the Mixed-language page,
  replacing the "Numbers in English text" switch, whose "on" reads as
  "around"): automatic (unchanged), the words around them, the voice's
  language (digits become their own run, `LanguageRuns.split(..., numbers)`)
  or English (written out as English words for non-Latin voices,
  `NumberReading.englishNumbers`, 10:30 as "ten thirty").
  The same holds when an eSpeak voice is speaking (`withNaturalRuns`):
  English on eSpeak with a Hindi natural voice chosen hands each Devanagari
  run to it as its own unit, resampled to eSpeak's 22050 Hz where its rate
  differs. Before this, only a natural primary voice switched,
  so eSpeak-English users never heard their Hindi voice in mixed text.
  `PiperE2EDeviceTest.j_*` checks it (the render must differ from eSpeak's own with the Hindi voice unset; peaks stopped telling them apart with the INT8 voices).
  Script tables (`LanguageRuns.LANGUAGE_SCRIPT` + `ALSO_WRITTEN_IN`) decide
  what is "another language": a language's text in any of its scripts stays
  with it. Kurdish `ku` is Kurmanji (Latin) - it was listed as Arabic, so the
  Kurmanji natural voice never got Kurmanji text (fixed 2026-10-04, with
  missing grc/hyw/hak/cmn/nog/ab/shn/bpy/chr entries and two-script
  languages: Serbian, Kazakh, Uzbek, Sindhi, Santali...). eSpeak voices for
  their language in another script (`fa-latn`, `cmn-latn-pinyin`, `en-shaw`;
  `Voice.isScriptVariant`) never use natural voices, which were trained on
  the usual script. Devanagari text is split by sentence among Hindi,
  Marathi, Nepali, Sanskrit and the SYSPIN/Rasa dialects
  (`DevanagariClassifier`): one language per sentence (no voice switch
  mid-sentence), leaving the default Devanagari language only on clear
  evidence (`SWITCH_MARGIN`); Hindi vs Marathi also uses fused Marathi case
  endings and each language's own words. `DevanagariCorpusTest` runs
  `test/resources/devanagari/` (50 Hindi + 50 Marathi tuned, 20 + 20 held
  out, 1 held-out Marathi miss) with Hindi, Marathi and English voices: keep the tuned set at zero misroutes
  and add a failing sentence there before changing the rules.
  A language the user chose for a script (see "Mixed languages" below)
  replaces the script's default here too, and `piper_switch_languages`
  (on by default) turns natural-voice switching off.
  Within a script, a run's language comes from letters only that language
  uses (`LanguageRuns.identify`, 2026-10-05): Cyrillic is Russian unless
  Ukrainian/Belarusian/Serbian/Macedonian/Kazakh/Tatar/Bashkir letters say
  otherwise; Arabic script is Urdu/Pashto/Sindhi/Sorani/Uyghur by their own
  letters, else Persian or Arabic by the weaker ی ک پ گ vs ة ي ك markers;
  Bengali script with ৰ/ৱ is Assamese; Han is Japanese when the text has
  kana, Korean when it has Hangul. A script the user chose a language for
  is never second-guessed. On the eSpeak path (natural voices on), a run
  eSpeak would not switch to by itself (`LanguageRuns.espeakReadsItself`:
  it spells Cyrillic, Telugu, Thai... letter by letter and reads Marathi or
  Urdu with the wrong rules) gets that language's eSpeak voice;
  `ScriptSwitchingDeviceTest` checks a Ukrainian sentence under an English
  voice reads exactly as long as the Ukrainian voice. Two traps found
  there: a request that is one unit in another voice took the direct path
  in the request's voice (fixed), and eSpeak recomputes speed only when the
  rate is set, so a voice file's `speed` (uk 80) stuck for that unit and the
  next one - the unit loop sets the rate after each voice. With natural
  voices off nothing changes (NVDA parity).
- **Crash guard.** A native crash inside ONNX Runtime kills the process and
  Android restarts the service, which reloads the voice: a crash loop that
  silences TalkBack. `PiperCrashGuard` records voices whose native work is in
  progress (a file, written around every load and inference); if the last
  process died of a native crash (`ApplicationExitInfo`) with a voice in that
  file twice within 10 minutes, the voice is suspended - `resolve()` skips it,
  its language row says so, and choosing it again clears it. Deliberately not
  "reset on success": GC-timed crashes let several inferences succeed first.
- **Network.** `INTERNET` exists only for the catalog, samples and voice
  downloads, and app updates - never on the speech path. Keep it that way.
  **App updates** (`util/AppUpdater`, 2026-10-15, modelled on Tarang's): the
  settings screen fetches `latest.json` from raw.githubusercontent.com at most
  daily (and About -> Check for updates on demand), offers a newer versionCode
  with its notes (Update / Later / Skip this version), and Update opens the
  APK of the running build's kind (`BuildConfig.SNAPDRAGON`) in the browser;
  Android's installer takes it from there. 2.10 downloaded and installed the
  APK itself (`REQUEST_INSTALL_PACKAGES`, DownloadManager, SHA-256 check,
  FileProvider) and Play Protect blocked it as an unsafe app; Tarang has the
  same in-app installer and is not blocked, so most likely it is the pairing with a
  speech engine that hears everything the screen reader says (and Recent
  speech) that looks like spyware (the likely cause, not confirmed by Google). Do not add the permission back. Never from
  the speech service. Microsoft's
  onnxruntime-android merged in a `TelemetryInitializer` content provider
  and `ACCESS_NETWORK_STATE` (the provider ran at every process start and
  set up telemetry networking); our own ORT build and Qualcomm's QNN plugin
  merge in neither, so the manifest no longer removes them (2026-10-13).
  Check the merged manifest on every ORT or QNN bump.
- **APK size.** ONNX Runtime is this app's own build (2026-10-08,
  `libs/onnxruntime-voices-1.30.0.aar`, recipe and operator list in
  `tools/ort-voices/`): arm64 only, only the operators the voices use, no
  NNAPI/XNNPACK/WebGPU/ML ops/telemetry. `libonnxruntime.so` 16 MB instead
  of Microsoft's 33 (6 MB compressed in the APK instead of 12; APK 34.7 ->
  28.4 MB, and 17 MB less unpacked on install). The operator list comes from
  every kind of voice model the app loads, as published and as ONNX Runtime
  optimized it on a Pixel 8 - ARM-only fused kernels (NhwcFusedConv,
  QLinearConv, QLinearLeakyRelu) appear only there; a model kind missing
  from it fails to load (missing kernel), so regenerate the list (see the
  script) before shipping a new kind of model or a new runtime version.
  Pixel 8: same speed as Microsoft's build (SYSPIN fp16 decoder 2.7-3.2x
  for all; Priyamvada whole equal); a session takes ~80 ms longer to create.
- **Imported voices** (2026-10-27, `piper/VoiceImport`, Natural voices ->
  Import a voice file): a voice the user downloaded elsewhere - a sherpa-onnx
  archive (`vits-piper-*.tar.bz2`, `vits-coqui-*.tar.bz2`), a .zip or .tar.gz
  of one, or the files themselves (multiple selection: the .onnx with its
  .onnx.json or tokens.txt). Two kinds run like catalog voices: Piper voices
  (eSpeak phonemes) and Coqui VITS letter voices (sherpa-onnx metadata
  `frontend` "characters", `add_blank` 1). Without a Piper config one is
  written from tokens.txt and the model's sherpa-onnx metadata the way
  build_piper_configs.py made the SYSPIN/Rasa ones (`_ ^ $` -> the blank; CR
  stripped from tokens.txt, SYSPIN's is CRLF). The model is opened once with
  this app's ONNX Runtime and must take Piper's inputs (`input`,
  `input_lengths`, `scales`): MMS, Kokoro, Matcha... are refused with a
  reason. When the config, its eSpeak voice, the metadata and the file name
  (`en_US-name...`) do not tell its language, the user picks it from a list
  and the import runs again with it (`PickedFiles.askLanguage`; VoxSherpa's
  import asks every time, here only then). Key
  `<code>-<name>-imported`, quality "imported" (label "Imported"), which no
  catalog lists, so updates and restores pass it by; the `imported` file
  marks the folder and `ModelFiles.dropOriginal` keeps its model (it could
  not be downloaded again: twice the space, Amy low 127 MB). No NPU files:
  CPU only. Archives are read by bytes, not names (content providers rename
  files); only .onnx, .onnx.json and tokens.txt are kept, 1 GB at most.
  bzip2 decodes natively (`util/Bzip2InputStream`, the vendored bzip2 1.0.8
  in `src/bzip2`, `jni/bzip2Stream.c`), tar in `VoiceImport.unpackTar`:
  with commons-compress in Java a first import took 65 s on a Pixel 8 (ART
  had not compiled the decoder; 15 s once it had), natively 9 s.
  `VoiceImportDeviceTest -e archives a.tar.bz2,b.tar.bz2` (files in the
  app's external files folder) imports and speaks each. Checked with
  sherpa-onnx's Amy low (Piper) and Bengali custom_female (Coqui).
- Not on Wear (`PiperSettings.KEY_SCREEN` is dropped there).

### Mixed languages (per-script languages)

eSpeak switches language by alphabet with fixed choices
(`alphabets[]` in `tr_languages.c`): Devanagari -> hi, Latin inside a
non-Latin voice -> en, and scripts without `AL_WORDS` (Cyrillic, Hebrew,
Telugu, Odia, CJK, Ethiopic) spelled letter by letter.
`espeak_SetScriptLanguage(script, voice)` (fork extension, `speak_lib.h`)
overrides one script; unset keeps eSpeak's choice, so default output is
unchanged (checked: 21 voices x 12 mixed texts, phonemes and audio identical
to the previous build). The override translator is built from the voice
*file* (`LoadSwitchTranslator` in `voices.c`), not `SelectTranslator(name)`,
so a variant like en-in gets its dictionary rules, phoneme table and
`replace` rules - the latter apply to switched words only
(`switch_replace_phonemes`, `phonemelist.c`). Clause-level options
(intonation, tunes, word gaps) stay the speaking voice's.
`ScriptLanguages` lists the offered choices; each was checked with the CLI
(`--script=cyr:ru`) to read words, not letters (Thai, Burmese, Hakka,
Ancient Greek and Western Armenian did not, so they are not offered).
`TtsService` applies them per request through the memoized
`SpeechSynthesis.setScriptLanguages`. `ScriptLanguagesDeviceTest` checks the
engine end to end.

### Per-language speech (language cards)

Settings -> Mixed languages -> Speech by language (`ui/LanguageCards`):
per language (ISO 639-2, the `piper_voice_<lang>` key space) a speed,
pitch and volume as 50-200% of the main settings, stored as
`lang_<lang>_<field>` = "80"; unset or empty is 100%, so output is
unchanged until a user sets one (NVDA parity). Each `SynthUnit` carries its
reader's language (the eSpeak voice's, or the natural voice's) and
`RequestSettings.setProsody` / `NaturalAhead.start` apply its card - the
request's own language included. Who reads which part is one decision,
`tts/RunPlan` (JVM-tested), for both the natural-voice and eSpeak paths.
Limit: a run eSpeak switches into by itself inside one unit (Hindi words in
an English eSpeak unit, natural voices off) keeps the unit's settings.
`LanguageCardsDeviceTest`: Hindi at 50% on eSpeak 3.22 -> 6.48 s with
English unchanged; Priyamvada at 150% 3.97 -> 3.06 s.
Step 2: `lang_<lang>_cue` ("" / "pause" 250 ms / "tone", `Earcons.switchCue`)
plays where speech switches into the language from another, written before
the next unit's chunk context so word ranges stay aligned
(`RequestSettings.writeSwitchCue`, both paths); a cue on both languages gives one
each way. Test: Ukrainian inside English +0.25 s pause, +0.11 s tone.
eSpeak lengths drift ~15 ms with the utterances before them (its generator
keeps state): device tests warm up once and compare within 25 ms.
Step 3: `lang_<lang>_mixed` = "" (its usual voice) / "espeak" /
"surrounding" (no switch: the request's voice reads it) / a natural voice
key, for that language's runs inside another language's text only
(`RunPlan.Readers.whenMixed`); "surrounding" falls back to the usual rule
when the request's natural voice reads letters only (SYSPIN/Rasa would drop
the words), a deleted or crash-suspended voice likewise. Test: English
"meeting" inside Hindi, natural pieces 3 (usual) / 2 (eSpeak) / 1
(surrounding).
Step 4: `lang_<lang>_switch` (words/phrases/sentences, "" = the main
`espeak_switching_sensitivity`) applies to that language's runs inside
other text (`LanguageRuns.split` takes a per-language lookup; the one-value
form wraps it), and `lang_<lang>_numbers` ("" = main, "auto" = automatic,
else the `VoiceSettings.NUMBERS_*` values) to numbers in that language's
text, through `VoiceSettings.getNumbersLanguage(language)` in both
`TextPreprocessor` and `TtsService`. Tests: English set to phrases, 1
natural piece instead of 3; Hindi numbers in English, "10:30" 1.79 -> 1.93 s.
Step 5, routing preview (Mixed languages -> Try your own text): the
user's text is spoken through the engine with `TtsService.PARAM_SHOW_PLAN`
in the request params; that request records its units
(`TtsService.takeShownPlan`) and the dialog lists each part's text,
language and reader. It shows the plan speech used, not a re-computation.
Parts follow the language runs (`RunPlan.Segment.runs`, kept per merged
segment), so eSpeak's English voice reading a Hindi word shows "Hindi,
eSpeak"; a natural voice still loading shows eSpeak (true for that play).
Display only: cards still apply by the reader's language. Test:
`routingPreviewShowsWhoReadEachPart`.
Step 6, dictionary "Read in" (`UserDictionary.setReadIn`, ISO 639-2,
"" = the usual voice, JSON/.dic extra `readIn`): a match is wrapped in
private-use marks naming the language (`text/ReadIn`: U+E110, three letter
marks U+E120+, the word, U+E111; with no replacement the match itself is
kept, where an empty replacement otherwise deletes). `TtsService.buildUnits`
makes the span a unit of its own in that language's eSpeak voice
(`findVoiceForLanguage`, even where eSpeak would read it itself: French in
English) and drops the marks while counting them in `base`, so word ranges
stay aligned; a span can cross watchdog chunks. On the natural path the
unit is one forced run of that language: its natural voice when loaded,
its card's prosody/cue, and its card's "Voice when inside other text" is
ignored (the entry is the more specific choice). Recent speech keeps the
caller's text, which never has the marks. Tests: `ReadInTest`,
`dictionaryEntryReadsItsWordInItsLanguage`. All six planned steps are done.

### Key Classes (`src/com/animeshahilya/espeakng/`)

Packages (split 2026-10-10, 89c1dcaa; tests mirror them so package-private
access still works):
- root: `EspeakApp`, `SpeechSynthesis`, `TtsService`, `TtsSettingsActivity`, `Voice`, `VoiceVariant`
- `text/`: the text pipeline - `TextPreprocessor`, `TextOffsetMap`, `UnicodeNormalization`, `Nvda*`, `NumberReading`, `Abbreviations`, `PhrasePauses`, `Earcons`, `TechWordsNormalizer`, `HinglishReader`, `LanguageRuns`, `DevanagariClassifier`, `ScriptLanguages`, `Tashkeel`, `UserDictionary`/`UserDictionaryManager`, `AsciiUtils`
- `tts/`: audio and framework entry points - `VoiceEngine`/`VoiceEngineRegistry`/`EspeakVoiceEngine`/`PiperVoiceEngine`, `TtsAudioDispatcher`, `AudioOptimizer`, `PcmResampler`, `RequestSettings` (rate, volume, per-language prosody and natural-voice parameters of one request), `CheckVoiceData`, `DownloadVoiceData`, `GetSampleText`, `PiperDownloadReceiver`
- `piper/`: natural voices (see above)
- `ui/`: `VoiceSettings`, `LanguageSettings`, `VoiceProfile`, `UserDictionaryScreen`, and the settings screen's parts (`SettingsFragment`, `SettingsBuilder`, `SettingsDialogs`, `SpeechPreview`, `PickedFiles`, `LanguageLabels`, split out of `TtsSettingsActivity` 2026-10-20); `ui/preference/`: custom preference widgets (14, incl. `AccessiblePreferenceCategory`, `VoiceVariantPreference`)
- `util/`: `TolerantPreferences`, `BackupRestoreHelper`, `LogExporter`, `FileUtils`

Manifest components use the package path (`.tts.CheckVoiceData`, etc.) and
`proguard-rules.pro` names `piper.PiperModel$SessionGroup` - moving a class
again means updating both (see Release signing).

- **EspeakApp** — `Application` subclass; owns the device-protected storage context, the one-time migration of pre-2022 preferences into it, the shared daemon executor (`runAsync(Runnable)` daemon thread pool used for background tasks, replacing ad-hoc threads), and the Wear launcher alias state (see below)
- **TtsService** — Android TTS engine service; handles `onSynthesizeText()`, voice selection, parameter setup, and safe ISO-3 / ISO-2 language code fallbacks. Also registers a receiver for `DownloadVoiceData.BROADCAST_LANGUAGES_UPDATED` and reloads the engine's voice list on receipt, so a voice imported through `TtsSettingsActivity` becomes selectable without restarting the process. Text goes through `TextPreprocessor.process()` before the engine sees it.
- **TextPreprocessor** — the text pipeline. **NVDA parity rule: every non-Indian voice must reach eSpeak with exactly the text NVDA would hand it.** Handles single-character / character-by-character navigation in TalkBack (`isSingleCharacter`) across supplementary Unicode code points (emojis, mathematical symbols) via code point counting without splitting surrogate pairs. Indian-only steps (lakh/crore and rupee reading, Indic digits, spoken Devanagari matras) are gated on `isIndianLanguage()` (lang/inc, lang/dra voices plus en-in). The lakh/crore/rupee words are English or Devanagari, so since 2026-10-15 other Indian scripts skip them: eSpeak reads "12,34,567" in lakhs in each (checked bn as gu pa or ta te kn ml ur si sd) and names ₹ from the language's symbols, while "1 lakh" made it - and natural voices - switch to English for one word. Extras beyond NVDA are allowed only as opt-in settings that default off: `NumberReading` reads money amounts ("$5.50" -> "5 dollars 50 cents", English voices only), grouped digits (including pairs, pairs with pause, triplets, triplets with pause, with interactive audio preview), and codes near words like OTP/PIN/account digit by digit (every voice), both only in Normal reading mode. Also opt-in: `Abbreviations` (English voices: Prof, vs, govt, units after numbers, months next to numbers), `PhrasePauses` (English/Hindi: a comma before "and"/"और"-type words in long comma-less stretches, added after the symbol pass so it is never announced), and `Earcons` (brackets and quotes the punctuation level would name become private-use markers; `TtsService` splits units at them and writes a short tone instead). Numbers stay as digits so eSpeak still says them itself. Symbols follow NVDA's architecture: `NvdaSymbolProcessor` (a Java reimplementation of NVDA's `characterProcessing` symbol engine - per-symbol levels none/some/most/all, preserve modes, complex symbols like sentence-ending dots vs decimal points, 4+ repeat collapsing) is driven by the punctuation preset and speaks each voice's language: like NVDA it merges that locale's NVDA `symbols.dic`, its CLDR symbol names, this app's English table, NVDA's English `symbols.dic` and English CLDR names, first source to set a field wins (`data/symbols/`, regenerated by `tools/update_nvda_symbols.py`; NVDA's files are GPL v2 or later, compatible). Before, every voice got the English names (an Arabic voice read "comma" with Arabic rules). English ASCII output is unchanged (`NvdaSymbolProcessorTest`); NVDA's Python regexes must also compile on ICU - `NvdaSymbolsDeviceTest` checks every language on a device, and a pattern that fails is skipped alone (`skippedPatterns`). Phonetic letters use NVDA's
per-language `characterDescriptions.dic` the same way
(`NvdaCharacterDescriptions`, `data/chardesc/`, same tool; locale, base
language, then English, lower-cased): German "b, Berlin", CJK example words.
The fork's Hindi primer words ("क से कबूतर") still come first, NATO covers
a-z without assets, and "always" mode skips CJK characters (their example
words would replace running text). The native engine is forced to `PUNCT_NONE` outside SSML so symbols never announce twice. Emoji: `NvdaEmoji` names each emoji from NVDA's own per-language CLDR dictionaries (`data/emoji/`, regenerated by `tools/update_emoji_names.py` from nvda-cldr's main-out branch), looked up like NVDA: full locale, base language, then English; the name is padded with spaces as NVDA pads a replacement. `PipelineSnapshotTest` logs the text sent to eSpeak plus an audio hash for a fixed corpus across 18 voices - diff two runs to prove a change leaves non-Indian voices alone (see its class comment).
- **ScriptLanguages** — Resolves script-to-language mappings for mixed-script text; uses an atomic `CacheHolder` reference for thread-safe caching, eliminating race conditions across concurrent TTS requests.
- **SpeechSynthesis** — JNI wrapper; loads `libttsespeak.so`, exposes native functions as Java API; handles native punctuation list resets safely when null/empty.
- **UnicodeNormalization** — NFKC normalization of synthesis input (stylized Unicode → plain text) with a normalized→original offset map for `rangeStart()` word boundaries
- **VoiceSettings** — SharedPreferences wrapper for rate, pitch, volume, punctuation, variant; also the sleep timer's stop time (reading apps stop, the screen reader never does), what's-new seen version. Reads through **TolerantPreferences**: a setting stored with the wrong type reads as its default instead of throwing ClassCastException out of `onSynthesizeText` (which silenced every utterance). TtsService and EspeakApp's startup reads use it too
- **LanguageSettings** — Filters available voices by user-selected languages; favorite voices pin to the top of `onGetVoices()` order
- **UserDictionaryManager** / **UserDictionary** — `.dic` import/export follows NVDA's columns (pattern, replacement, comment, case, type 0 anywhere / 1 regex / 2 whole word); this app's extra fields (section, language, phonemes) ride in the comment as `eSpeakAndroid {json}`, so an export imports back unchanged (`DicFormatTest`). Regular expressions are compiled with resilient ICU regex error handling to ensure invalid syntax never crashes speech synthesis.
- Perf notes: dictionary search debounces 200ms with precomputed lowercase + lazy row labels (large imports filter per keystroke otherwise); `TtsService` chunks are one `SynthUnit` list, not parallel arrays; debug builds run StrictMode (log-only) to catch main-thread IO regressions; the settings screen shows a loading row until the engine tree lands. Cold start (2026-10-20, Pixel 8, release-like build): `EspeakApp.onCreate` starts two background tasks at process start - `warmUpText` (preprocessor patterns, the system language's NVDA symbols, user dictionary, emoji) and `SpeechSynthesis.prewarm` (native engine + voice list, both process-wide) - so `TtsService.onCreate` finds the engine ready (~16 -> ~2 ms on its main thread) and the first request no longer waits for the symbol tables behind the natural-voice housekeeping (preprocess 32 -> 4 ms). Service start to first audio ~100 -> 74 ms; what is left is mostly the framework's own `TextToSpeechService.onCreate` (~28 ms) and native synthesis (~8 ms). Measure with a non-debuggable, `-O3 -DNDEBUG` debug build (`cmd package compile -m speed -f`), `ANDROID_SERIAL` pinned, app force-stopped before each `EspeakLatencyDeviceTest#aColdStart`
- **VoiceProfile** — Named preset files (variant, rate, pitch, punctuation) via SAF; unknown keys ignored both directions
- **CheckVoiceData** — Intent handler that verifies voice data files exist on device
- **DownloadVoiceData** — Extracts `espeakdata.zip` (every bundled language) to device-protected storage on a single-thread executor (~1 s on a Pixel 8 for the ~14 MB archive; runs on the caller's behalf via `CheckVoiceData.ensureVoiceData()`, which also re-verifies the tree before reporting success)
- **TtsSettingsActivity** — the settings host: edge-to-edge window insets, predictive back, the menu's SearchView, file-picker results, and the `CONFIGURE_ENGINE` target (on Wear, the launcher entry point). The screen itself is `ui/SettingsFragment` (screen stack, sub-screen focus, the whole-tree settings search with scroll+focus to a result, the status-bar backing). `ui/SettingsBuilder` gathers engine data off the main thread, inflates `res/xml/preferences.xml` (list choices in `res/values/arrays.xml`) and wires every row (`buildPreferences()`: engine-dependent rows, Wear-hidden rows dropped, summaries, `showWhile`, sleep-timer arming). `ui/SpeechPreview` plays samples through the preview engine (per-language touch-and-hold samples retarget it per locale and restore the voice after; the routing preview). `ui/SettingsDialogs`: About, what's new, and `markAlertTitleHeading()` (dialog titles are TalkBack headings; also `ButtonDialogFragment.onStart()`). `ui/PickedFiles`: dictionary/backup/profile/voice-data import and export off the main thread. `ui/LanguageLabels`: voice-picker labels - the system-language name first (`Locale.getDisplayName()`), the voice data's English name in parentheses. `UserDictionaryScreen` holds the user dictionary editor. Sub-screen navigation moves TalkBack focus to the first row (announcement alone leaves focus behind); conditionally revealed rows announce on reveal
- **Voice / VoiceVariant** — Data models for voice metadata and variant parsing, with robust locale resolution
- **Piper\*** — natural voices (see "Natural voices (Piper)" above): `PiperEngine` (model cache + text->audio), `PiperModel` (ORT sessions and inference), `NpuFiles` (QNN plugin, NPU sessions, hosted NPU files and what they compile into), `ModelFiles` (optimized and shared model copies on disk), `PiperPhonemes` (records -> chunks -> ids), `PiperAudio` (PCM post-processing, word timing), `PiperVoiceConfig` (`.onnx.json`), `PiperVoiceStore` (installed voices + per-language prefs), `PiperDownloads`/`PiperDownloadReceiver` (catalog, DownloadManager, verify/install), `PiperSettings` (the settings page). Everything except the store/downloads/settings is Android-free and unit-tested on the JVM.
- **NumberReading (everyday + STEM)** — besides money/digit grouping/codes, opt-in rows for phone numbers, dates, maths, fractions, sub/superscripts, dimensions and Roman numerals (`espeak_read_*`, all default false, on the Numbers page with a live preview)
- **TechWordsNormalizer** — spells tech acronyms and brand names (OTP -> "O T P", ATMs -> "A-T M's", PhonePe -> "Phone Pay"); always on for natural voices, opt-in for eSpeak (see "Acronyms, app names, phone numbers")
- **HinglishReader** — Latin-script Hindi read as Hindi on English voices (Dakshina lexicon, `data/hinglish/`), decided per sentence; never a lone word
- **LanguageRuns** fast path and sensitivity — text wholly in the speaking language's own (unambiguous) script skips the resolver/classifier; `espeak_switching_sensitivity` = words (default, unchanged behaviour) / phrases (absorbs one-word foreign runs) / sentences
- **AudioOptimizer** — opt-in DSP on PCM in `TtsAudioDispatcher` (`espeak_audio_optimizer_level`, default "off"; per-module `espeak_opt_*` toggles: glottal tilt, ZCR sibilant bypass, warmth, presence, leveler, limiter, de-clicker; in-app preview). Changes audio, so a profile is a listening decision, never a default - NVDA parity
- **AppCards** (Speech by app, 2026-10-21) — per calling app (`SynthesisRequest.getCallerUid()` -> `callerPackage`; this app's own uid is never a caller) speed/pitch/volume 50-200%, punctuation preset and "eSpeak only", stored `app_<package>_<field>` like the language cards and applied through `VoiceSettings.setCaller` (`appFactor`, punctuation getters, `isAppEspeakOnly`) in `RequestSettings.effectiveRate/effectiveVolume` and both pitch sites. Unset = unchanged (NVDA parity). A shared uid's card is "android" if listed, else the first package alphabetically (uid 1000's first package on a Pixel is Pixel Camera Services; Settings' "Play" comes from it). Apps that spoke are listed from `espeak_apps_seen` (device-local, not backed up). Pixel 8: the Settings sample with a 200% card 33785 -> 20370 frames
- **RecentSpeech** / `RecentSpeechTile` / `RecentSpeechActivity` (2026-10-21) — the last 15 things other apps had the engine say, kept in memory for 5 minutes, and only while the Quick Settings tile is added (`espeak_recent_speech_tile`, set by the tile's add/remove callbacks); Say again / Say slowly / Spell (one request per character) / Copy (marked sensitive). The tile opens it after unlock only. What is said after the panel opens is left out (`onStartListening` marks the time)
- **VoicePresets** / `VoicePresetTile` (2026-10-21) — named voice setups in `espeak_presets` (JSON, name -> `VoiceProfile.capture`: variant, rate, rate boost, pitch, range, volume, intonation, reading mode, punctuation; a key at its default is JSON null, so applying puts the default back). "In use" = the settings match a preset now (never stored). Switched from Backup and about -> Presets, the tile (each tap = next preset, name as tile state) or Tasker (`preset` extra). `VoiceProfile` now writes every value as a string: it used to putInt the punctuation level, which the settings could not read back (punctuation silently reset on every profile load)
- **ImportedVariants** (2026-10-21) — a voice variant file (NVDA users share them) imported through Import is kept in `files/variants/` and copied into the data's `voices/!v/`, again after every data extraction (an app update deletes that tree); `VoiceVariantPreference` lists them under "Imported". Detection: text only, every line a keyword, at least one sound line (formant, pitch, klatt...)
- **Intonation** — one choice: Natural / Flat / Expressive, each optionally "clearer questions" (`<style>_questions`; was the separate Emphasize questions switch, migrated at start by `EspeakApp.migrateSettings`, also from an older backup)
- **SettingsReceiver** (2026-10-21, espeak-ng #2085) — explicit broadcast `com.animeshahilya.espeakng.SET_SETTINGS` (rate/pitch/volume absolute or "+n"/"-n", punctuation, reading_mode, natural_voices); ignored unless `espeak_allow_other_apps` is on
- **AsciiUtils** — shared allocation-free helpers; use `replaceMatches()` for regex rewrite loops (replacements always quoted) and `singularOrPlural()` instead of new Matcher/appendReplacement loops or per-class plural logic

### Launcher icon

`TtsSettingsActivity` carries a `MAIN`/`LAUNCHER` intent-filter (launcher icon)
plus the `CONFIGURE_ENGINE` entry used from the system Text-to-speech settings.
This single declaration serves both phones and Wear OS watches cleanly without
redundant aliases or runtime PackageManager modifications.

### JNI Layer (`jni/jni/eSpeakService.c`)

17 JNI functions mapping `SpeechSynthesis.native*()` Java methods to `espeak_*()` C API calls (two of them for Piper: `nativePhonemizeForPiper`, `nativeSonicOpen`/`Write`/`Close`; `nativeUnpackAsset` for packed assets) (plus `JNI_OnLoad`/`JNI_OnUnload`, which aren't Java-callable). Audio flows back via `SynthCallback` → `nativeSynthCallback()` → `SynthesisCallback.audioAvailable()`.

### Voice Data Lifecycle

On first launch (or version mismatch), `DownloadVoiceData` extracts `res/raw/espeakdata.zip` (phoneme data, voices, language files; ~0.8 MB) to device-protected storage. `CheckVoiceData` validates required files: `version`, `intonations`, `phondata`, `phonindex`, `phontab`.

Compiled dictionaries (29.9 of the 30.7 MB) are never extracted (2026-10-08): `LoadDictionary` (dictionary.c), the one place eSpeak opens a `*_dict`, falls back to `espeak_SetDictionaryReader` (fork extension, speak_lib.h) when the file is not on disk, and the JNI layer maps `espeak-dicts/<name>_dict.zst` from the APK with `AAssetManager` and decompresses it (`readDictionaryAsset` in eSpeakService.c, set in `nativeCreate`; zstd's vendored single-file decoder, `src/zstd/`). zstd since 2026-10-12: dictionaries 14.1 MB (APK deflate) -> 11.0 MB; xz was 10.3 MB but decodes 9x slower (PC, Russian 146 ms). Every load goes through it, eSpeak's own mid-sentence language switches included, and eSpeak keeps a loaded dictionary in memory as before (no extra RAM). Pixel 8: user data 452 -> 422 MB; a switch into a language costs (median, `DictionaryAssetsDeviceTest.dictionaryLoadTimes`, zstd) Russian 41 ms (9.1 MB), Faroese 19, Pashto 9, Chinese 4, Hindi 0 (deflate was 56/37/15/11). The decoder builds at -O2 even in debug builds (`zstddec` in jni/CMakeLists.txt): unoptimized it took Russian 225 ms. The CLI and desktop builds set no reader: files only. `DictionaryAssetsDeviceTest` checks every voice reads and that no `*_dict` sits in app storage.

All languages are bundled directly in the APK and selectable from first
launch - there is no core/extra split and no network-fetched language pack
(the only network access in the app is the optional Piper natural-voice
download - see above; eSpeak data never comes from the network).
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
(`android/src/.../ui/preference/VoiceVariantPreference.java`), each needing a
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
├── src/com/animeshahilya/espeakng/   # Java: 6 root classes + text/ tts/ piper/ ui/ util/ (see Key Classes)
│   └── ui/preference/                # Custom preference widgets (14 classes)
├── test/                             # Host JVM unit tests (40 classes)
│   ├── src/                          # Same subpackages as the sources
│   └── resources/                    # Fixtures (TSV cases, Devanagari corpus)
├── eSpeakTests/                      # On-device instrumentation tests (32 classes, ~180 tests)
│   ├── src/                          # Root package plus test/ subpackage
│   └── res/                          # Test resources
├── jni/
│   ├── CMakeLists.txt                # Native build (links espeak-ng + JNI, builds libsonic)
│   ├── jni/eSpeakService.c           # JNI bridge (17 native methods)
│   ├── jni/piperPhonemizer.c         # eSpeak -> Piper phonemizer (clause records)
│   ├── jni/bzip2Stream.c             # bzip2 decoding for imported voice archives (src/bzip2)
│   └── include/                      # config.h, Log.h
├── res/                              # Resources (75 locale translations, plus night/v29/watch)
├── assets/                           # Bundled as-is: piper catalog JSON
├── data/                             # Packed (zstd) by the build: symbols, chardesc, emoji, hinglish, numbers
├── tools/                            # Data regenerators (NVDA symbols, emoji, piper spellings, ort-voices)
├── build.gradle                      # Gradle config (AGP 9.4.1, Gradle 9.8.1, Java 17 toolchain)
└── AndroidManifest.xml               # Services, activities, intents
```

## Testing

### JVM Unit Tests

`./gradlew testDebugUnitTest` - 40 classes (~650 tests) in `test/src/`,
mirroring the source subpackages:
- root: `TtsRoutingPreviewTest` (routing preview merge over the planned runs)
- `text/`: pipeline steps and fuzzing (`TextSanitizeTest`, `TextStepsFuzzTest`, `TextOffsetMapTest`), NVDA symbols/character descriptions, `NumberReadingTest`, `AbbreviationsTest`, `TechWordsNormalizerTest`, `EarconsTest`, `PhrasePausesTest`, language runs/switching, `DevanagariClassifierTest` + `DevanagariCorpusTest`, `ScriptLanguagesTest`, `UserDictionaryTest`, `AsciiUtilsTest`
- `tts/`: `TtsAudioDispatcherTest` (slice clamping, sample alignment), `VoiceEngineRegistryTest`, `AudioOptimizerTest`, `AcousticSampleGeneratorTest`, `PcmResamplerTest`
- `piper/`: phonemes/id encoding, split, alignment, audio, catalog, compact policy, phrase cache, shared models, model files
- `ui/`: `DicFormatTest`

They read `data/` from the source tree, declared as the test task's input
(build.gradle `unitTests.all`) so a data-only change reruns them;
`NvdaCharacterDescriptionsTest.everyBundledFileIsUtf8` guards the encoding
(NVDA's de_CH characterDescriptions.dic is Windows-1252 - its git blob,
checked at 7e3f795; Swiss German read "C�sar" until 2026-10-12, and
`tools/update_nvda_symbols.py` re-encodes it).
These run on the desktop JDK, not Android's ICU/platform classes: a regex the
JDK accepts can still be rejected on a device (an unbounded look-behind did
exactly that and silenced all synthesis, d56ba251). Anything touching
regexes, Locale or platform APIs needs a device run.

### Device Instrumentation Tests

`./gradlew connectedAndroidTest` - 32 classes, ~180 tests, in
`eSpeakTests/src/com/animeshahilya/espeakng/` (plus its `test/` subpackage):
voice enumeration, settings and `PreferenceStorageTest`, variant parsing and
`VoiceVariantCatalogTest` (hardcoded picker vs shipped `voices/!v/` files -
upstream #2376), locale translation (`testJavaToIanaCountryCode`), synthesis,
dictionaries (`DictionaryDeviceTest`, `DictionaryAssetsDeviceTest`), the text
pipeline (`TextPipelineDeviceTest`: paragraph breaks survive preprocessing),
`PipelineSnapshotTest` (NVDA-parity diff), language/script switching,
Indian features, natural voices (`PiperE2EDeviceTest` - keep `j_*` passing,
`PiperLatencyDeviceTest`, `PiperBenchDeviceTest`), latency
(`EspeakLatencyDeviceTest`), word ranges (`RangeFramesDeviceTest`) and A/B
harnesses (`EnUsProbeDeviceTest`,
`PhraseCacheReplayDeviceTest`). `NpuAuditDeviceTest` (run alone on a Snapdragon
phone, `-e fetch true` to fetch missing NPU files first) loads every
installed voice and logs where each runs.

Lint (`./gradlew lintDebug`) reports 0 errors (2026-10-12); deliberate
findings (androidx.preference internals, TalkBack focus on sub-screens, the
startup-provider manifest entry) are suppressed in `android/lint.xml` with
the reason, so a new error is a real one.

Performance work on the native engine must stay bit-exact: compare
`PipelineSnapshotTest` hash dumps before and after (18 voices, zero diff).
Run that class on its own (`am instrument -e class ...PipelineSnapshotTest`),
never inside the full suite: eSpeak's audio generator carries state from one
utterance to the next, so after any earlier synthesis in the same process
every hash differs (checked 2026-10-12: resetting all parameters, script
languages and the voice did not help; only test classes that synthesize
affect it). Stock eSpeak behaves the same, so it is not a parity issue.

Tests that clear the app's settings carry `@ClassRule KeepUserSettings` (eSpeakTests `test/`), which puts the phone's own settings back afterwards: before 2026-10-12 a suite run switched natural voices off and dropped every voice assignment on the tester's phone, and later `PiperE2EDeviceTest` runs skipped. Run single classes with `adb shell am instrument` to keep app data. Note `connectedAndroidTest` reinstalls the app (wiping extracted data), so data-dependent tests must ensure extraction first - see `CheckVoiceDataTest.ensureVoiceData()` and the `ensureVoiceData()` calls in the direct-engine test setups. CI runs API 34; also run on API 35+ when possible (Locale canonicalizes legacy codes like `in`&#8594;`id` there - see `VoiceData.expectedEngineLanguage()`).

## Common Workflows

### Modifying JNI bindings

1. Add/change native method declaration in `SpeechSynthesis.java`
2. Implement in `jni/jni/eSpeakService.c` (function name follows JNI convention: `Java_com_animeshahilya_espeakng_SpeechSynthesis_<methodName>`)
3. `./gradlew assembleDebug` rebuilds native libs automatically

### Updating voice data

Voice data comes from the parent project's `dictsource/` and `phsource/`. The Gradle build runs CMake which rebuilds `espeak-ng-data/`, then zips it. Just run `./gradlew assembleDebug`.

### Adding a new preference/setting

1. Add preference key constant in `VoiceSettings.java`
2. Add it to `res/xml/preferences.xml` (the key must match the constant; `PreferenceStorageTest.settingsScreenHasEverySetting` checks).
   Placement (simplified 2026-10-18): the first page is only for what most people change (voice, speed, rate boost, pitch, punctuation, one row per topic page); a topic page (Natural voices, Text and symbols, Numbers, Mixed languages) holds what its users reach for; anything expert goes under Advanced, in the category of the same topic (Sound, Pauses, Text, Languages, Speech control). A switch gets ONE short `android:summary`, an example where it helps ("$5.50 as 5 dollars 50 cents") - no `summaryOn`/`summaryOff` (TalkBack already says on or off; only the natural-voices and Supertonic switches keep them, where off changes what the page does). Rows that only matter under another choice are hidden with `showWhile`, not greyed out
3. Wire it through `TtsService.onSynthesizeText()` to the appropriate `SpeechSynthesis` parameter

Settings and data paths strictly live in device-protected storage so that `TtsService` can read them
before the device is unlocked (Direct-Boot invariant, available since Android 7.0/API 24 - this
app's minSdk 26 floor is comfortably above it). Key invariants:
- `ui/SettingsFragment` switches its `PreferenceManager` to device-protected storage.
- All helpers (`CheckVoiceData.getDataPath()`, `TtsSettingsActivity`) consistently resolve through `EspeakApp.getStorageContext()`.
- Never call `PreferenceManager.getDefaultSharedPreferences()` on a plain `Context`: the
  value lands in a credential-encrypted file that `EspeakApp` discards at the
  next process start, so the setting silently does nothing (#2536).
- `PreferenceStorageTest` fails if opening the settings screen creates a CE storage file.
