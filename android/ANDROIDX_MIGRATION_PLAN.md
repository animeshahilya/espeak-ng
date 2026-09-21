# AndroidX Preference migration plan

For: an AI coding agent working this repo. Read this file plus
`CLAUDE.md` (repo root) and `android/CLAUDE.md` before starting.
Do not start Phase 2+ until the Gate 0 go/no-go decision is recorded here.

## 0. Goal, scope, non-goals

- Goal: eliminate the ~149 remaining `javac -Xlint:deprecation` warnings, all
  of which come from the deprecated `android.preference` framework
  (deprecated since API 28) plus its companion framework-fragment APIs
  (`getFragmentManager`, `PreferenceFragment`), by migrating the settings UI
  to `androidx.preference`.
- Scope: 16 files (12 app, 4 test) that import `android.preference`.
- Non-goals: no behavior changes, no preference-key renames, no new
  settings, no AppCompat theming beyond what dialogs require, no network,
  no `INTERNET` permission, minSdk stays 26.

## 1. Starting state (verified)

- Branch: `android` on `https://github.com/animeshahilya/espeak-ng.git`.
- Prior work already landed: quick-win deprecations fixed (`Locale`,
  `versionCode`, `onError`, a11y `obtain`/`announce`, `embeddedTts`
  literal); detector sweep reads **200 -> 149**, with zero warnings left
  outside the `android.preference` bucket. Baseline: `connectedAndroidTest`
  is **103/103 green** on Pixel 8.
- Settings UI shape (this is why the plan looks like it does):
  - `TtsSettingsActivity` (2051 lines) extends framework
    `PreferenceActivity`, hosts one `PrefsEspeakFragment` (framework
    `PreferenceFragment`), and builds ~60 preferences **programmatically**
    via static `create*Preference` factories. `res/xml/preferences.xml`
    is an empty shell; `res/xml/tts_engine.xml` is only the TTS-engine
    pointer. There is almost no XML migration to do.
  - 4 sub-screens (`numberScreen`, `textProcessingScreen`, `locksScreen`,
    `toolsScreen`) are built programmatically with
    `pm.createPreferenceScreen()` and shown via a hand-rolled
    `setupSubScreenDialog`. AndroidX navigates nested screens automatically,
    so this custom dialog code gets **deleted**, not ported.
  - 6 custom widgets in `src/com/animeshahilya/espeakng/preference/`:
    `SeekBarPreference`, `SpeakPunctuationPreference`,
    `VoiceVariantPreference` (extend `DialogPreference`),
    `SupportedLanguagesPreference` (extends `MultiSelectListPreference`),
    `ImportVoicePreference` (extends `Preference`),
    `AccessiblePreferenceCategory` (extends `PreferenceCategory`).
  - No AppCompat anywhere: theme is `android:Theme.DeviceDefault`
    (plus `-v29`/`-watch` variants), plain Java, kotlin-stdlib deliberately
    excluded. Adding `androidx.preference` pulls appcompat/recyclerview
    transitives into the APK - Gate 0 measures this.
- Files importing `android.preference` (16): app -
  `TtsSettingsActivity`, `SeekBarPreference`, `SpeakPunctuationPreference`,
  `VoiceVariantPreference`, `SupportedLanguagesPreference`,
  `ImportVoicePreference`, `AccessiblePreferenceCategory`, `TtsService`,
  `CheckVoiceData`, `EspeakApp`, `BackupRestoreHelper`, `LogExporter`;
  tests - `LanguageFilterSynthesisTest`, `TextToSpeechServiceTest`,
  `VoiceSettingsTest`, `PreferenceStorageTest`.
- Behavior-pinning tests that must stay green throughout:
  `PreferenceStorageTest` (device-protected storage location, no CE file),
  `VoiceSettingsTest`, `LanguageFilterSynthesisTest`,
  `TextToSpeechServiceTest`, `DictionaryDeviceTest`,
  `SpeechSynthesisTest` (locale mapping).

## 2. Hard invariants (violating any of these fails the phase)

1. All ~47 `VoiceSettings` keys byte-identical, same shared-prefs file.
   Users must not lose settings; add an upgrade test from the current
   release build proving prefs survive (install release APK, set values,
   install new build, assert values).
2. Device-protected storage discipline from `android/CLAUDE.md`: resolve
   everything through `EspeakApp.getStorageContext()`; never
   `PreferenceManager.getDefaultSharedPreferences()` on a plain `Context`;
   `PrefsEspeakFragment` equivalent must use device-protected storage.
3. JNI/`SpeechSynthesis`/proguard keep rules untouched by this migration.
4. Wear launcher alias behavior unchanged (see `android/CLAUDE.md`).
5. One concern per commit; push per completed phase; working tree clean
   between phases.

## 3. Gate 0 - decision spike (one session, must end go/no-go)

The spike stays ISOLATED: throwaway files only, deleted afterwards. Do NOT
migrate the real host shell in place - `TtsSettingsActivity` mixes ~60
framework-typed factories in one file, and mixing both preference
frameworks there is churn that cannot be cleanly reverted.

1. Record the no-androidx baseline first: fresh `cleanPackageDebug
   packageDebug`, note exact APK bytes. (Baseline: 16117590 bytes /
   15.37 MB, debug, arm64-v8a.)
2. Add `implementation 'androidx.preference:preference:1.2.1'` to
   `android/build.gradle` (no version catalog exists - add the literal
   with a comment). Assemble and record the APK delta.
3. Throwaway proof screen (all deleted after the decision):
   `SpikeSettingsActivity` (`AppCompatActivity`, manifest
   `android:theme="@style/SpikeTheme"`, `exported=false`, private action
   string so no launcher icon appears) hosting `SpikeFragment`
   (`PreferenceFragmentCompat`) with one `EditTextPreference` and one
   dialog interaction, wired with
   `getPreferenceManager().setStorageDeviceProtected()` plus the
   `EspeakApp` storage-context pattern. `SpikeTheme` lives ONLY in
   `res/values/` (parent `Theme.AppCompat.DayNight`) so the app's real
   `DeviceDefault` themes are untouched.
4. Verify on device via adb (no manual tapping): launch by action,
   `uiautomator dump` confirms the screen renders, click the preference,
   dump again to confirm the AppCompat dialog renders under the current
   theme setup, then `run-as` the debug package and confirm the prefs
   XML landed under device-protected storage (`/data/user_de/0/...`),
   and `PreferenceStorageTest` stays green.
5. Go criteria: dialogs acceptable without a full theme rewrite, size
   delta tolerable, storage path proven. Otherwise STOP: revert the
   dependency, delete the spike, and keep the
   documented-`@SuppressWarnings` status quo (framework prefs keep working
   on all supported APIs - say so explicitly in the commit/plan instead of
   migrating).

## 3b. Gate 0 results (measured on Pixel 8, 2026-09-21)

- Baseline debug APK (no androidx): **16117590 bytes / 15.37 MB**.
- With `androidx.preference:1.2.1` (+appcompat/recyclerview/kotlin-stdlib
  transitives): debug **22113852 bytes (+6096262, +37.8%)** - worst case,
  unshrunk; release/R8 with dep: **16721691 bytes / 15.95 MB**
  (without-dep release baseline not re-measured; R8 keeps strictly less
  than debug, so the true release tax is well under the debug number).
- **kotlin-stdlib exclusion must be lifted**: `AppCompatActivity`
  crashes at class-load without it (`ClassNotFoundException:
  kotlin.jvm.internal.Intrinsics`, reproduced on device). The exclusion
  in `build.gradle` stays commented while any migration work is live.
- Theme: an `EditTextPreference` dialog renders correctly under a bare
  `Theme.AppCompat.DayNight` spike theme - no crash, no extra styling;
  the app's `DeviceDefault` themes were untouched. Visual polish remains
  a human check during the dialog slice.
- Storage: `setStorageDeviceProtected()` proven end-to-end - values land
  in `/data/user_de/0/<pkg>/shared_prefs/<pkg>_preferences.xml`, the same
  file the migration flag already lives in.
- Environment lessons: spike activity needs `exported=true` for
  adb-shell launches (private action string keeps it undiscoverable, no
  launcher icon); shell `input tap` did not dispatch on this
  device/ROM, so proof used programmatic `performClick()` plus
  `uiautomator dump` assertions instead.
- Verdict: **mechanics GO** (build, theme, storage all proven). Whether
  to fund slices 1-6 at the measured size cost is an owner decision -
  the documented-`@SuppressWarnings` status quo remains valid since
  framework prefs function on all supported APIs.

## 4. Unified migration pattern (apply identically everywhere)

- Class map: framework X -> `androidx.preference` X (`DialogPreference`,
  `MultiSelectListPreference`, `PreferenceCategory`, `Preference`,
  `PreferenceManager`); `PreferenceActivity` -> `AppCompatActivity`;
  framework `PreferenceFragment` -> `PreferenceFragmentCompat`;
  `getFragmentManager()` -> `getSupportFragmentManager()`.
- Factories in `TtsSettingsActivity`: change return/param types and
  constructor calls only; keep listeners, summaries, and dialog content.
- Delete (do not port): `setupSubScreenDialog` and manual sub-screen
  dialog handling - AndroidX handles nested-screen navigation.
- Listener/suppression hygiene: every remaining `@SuppressWarnings`
  carries a rationale comment; suppressions stay method- (never
  file-) scoped outside tests.

## 4b. API mapping table (compiler-verified against preference 1.2.1)

A throwaway probe subclassing every widget base and calling every member
the real code uses showed the AndroidX API is NOT a 1:1 rename. Verified
identical (safe to retype): all `(Context[, AttributeSet[, int[, int]]])`
constructors; `get/setKey`, `setPersistent`, `get/setSummary`,
`setLayoutResource`, `getContext`, `callChangeListener`, `shouldPersist`,
`getSharedPreferences`, all `MultiSelectListPreference` data APIs
(`set/getEntries`, `set/getEntryValues`, `set/getValues`,
`persist/getPersistedStringSet`), `getFragmentManager`-free fragment APIs
(`getPreferenceManager`, `getPreferenceScreen`, `findPreference`,
`setPreferencesFromResource`).
Verified GONE or signature-changed (must be redesigned, not retyped):
`Preference.onBindView(View)` -> `onBindViewHolder(PreferenceViewHolder)`
(`AccessiblePreferenceCategory` title-heading logic moves there, resolved
via `holder.itemView`); `DialogPreference.onCreateDialogView()` /
`onBindDialogView(View)` / `onClick(DialogInterface,int)` (AndroidX
`onClick()` takes no arguments) / `onDismiss(DialogInterface)` all gone;
`MultiSelectListPreference.showDialog(Bundle)` / `getDialog()` gone;
`Preference.getEditor()` / `shouldCommit()` gone.
Consequence: the three custom dialogs (`SeekBar` incl. its rotary-encoder
handling, `SpeakPunctuation` radio groups, `VoiceVariant` spinners) and
the `SupportedLanguagesPreference` button wiring must move to
`PreferenceDialogFragmentCompat` subclasses (one per dialog) plus an
`onDisplayPreferenceDialog()` override in the hosting fragment. That is a
per-dialog re-architecture, not a type swap - budget the cutover
accordingly and verify each dialog on device with TalkBack.

## 5. Slices (each lands green or it does not land)

Slice order follows a hard constraint discovered in Slice 1: leaf widgets
cannot migrate before the host tree (an androidx widget cannot be added
to a framework group - unrelated types, ~13 host sites break). So
non-UI call sites go first, the visible tree cuts over atomically last.

1. DONE - non-UI `PreferenceManager` call sites (both return plain
   `SharedPreferences`, so no UI entanglement): `TtsService`,
   `CheckVoiceData`, `EspeakApp`, `BackupRestoreHelper`, `LogExporter`,
   plus `LanguageFilterSynthesisTest`, `TextToSpeechServiceTest`,
   `VoiceSettingsTest`, `DictionaryDeviceTest` (fully qualified),
   and fully-qualified one-liners in mixed files (`TtsSettingsActivity`
   `getPrefs`, `PreferenceStorageTest` name lookup). Warning count went
   149 -> 105 with 103/103 device tests green, `PreferenceStorageTest`
   proving both frameworks resolve the same file. Two discoveries, keep
   them: AndroidX `getDefaultSharedPreferencesName()` is **private** -
   both frameworks compute `packageName + "_preferences"`, so spell it
   out with a comment; `EspeakApp`'s now-unused import was removed.
2. `SupportedLanguagesPreference` prep only (no UI change yet): extract
   whatever is framework-agnostic; the widget itself moves in slice 4.
3. The three `DialogPreference`s (`SeekBar`, `SpeakPunctuation`,
   `VoiceVariant`) - same prep treatment; dialog lifecycle differs most.
   Re-verify the `CollectionItemInfo` guard and announcement
   suppressions still compile in place.
4. ATOMIC CUTOVER (single commit): `TtsSettingsActivity` host (activity,
   `PrefsEspeakFragment`, 4 programmatic sub-screens,
   `onPreferenceTreeClick`/sub-screen dialogs get deleted, not ported -
   AndroidX handles nested-screen navigation), all 6 custom widgets
   rebuilt per the section 4b table (dialog-fragment subclasses +
   `onDisplayPreferenceDialog`, NOT retyped overrides), remaining call
   sites and the 4 test files. Nothing lands half-migrated.
5. Upgrade test from the current release build proving prefs survive,
   plus the manual TalkBack pass over every settings screen.

## 6. Verification protocol (every slice)

1. Warning sweep with file-level diagnostics (javac hides them by
   default). Create this init script OUTSIDE the repo (e.g. temp dir),
   never commit it:
   `allprojects { tasks.withType(JavaCompile).configureEach {
   options.compilerArgs += ['-Xlint:deprecation'] } }`
   then run:
   `.\gradlew.bat cleanCompileDebugJavaWithJavac
   cleanCompileDebugAndroidTestJavaWithJavac compileDebugJavaWithJavac
   compileDebugAndroidTestJavaWithJavac -I <script> --console=plain
   --no-build-cache`
   (`--no-build-cache` is required: Gradle otherwise serves `FROM-CACHE`
   and prints no warnings. Expect remaining warnings to be only the
   not-yet-migrated `android.preference` bucket; count must drop every
   slice.)
2. Build ladder: `assembleDebug`, then `assembleRelease` (exercises
   R8/minify + `lintVital`; release falls back to unsigned APK without
   `android/keystore.properties` - that is normal).
3. Device: `adb devices` must show the Pixel 8, then
   `connectedAndroidTest` - 103/103, 0 failures is the only passing grade.
4. UI slices additionally need a manual TalkBack pass over every touched
   screen (announcements, custom dialogs, select-all/deselect-all).

## 7. Traps (each already paid for once - do not repay)

- Abstract overrides can't be removed: `UtteranceProgressListener`
  `onError(String)` is abstract; keep it with a rationale comment.
- `Locale.forLanguageTag` canonicalizes (`in`->`id`) and `Locale.Builder`
  rejects 3-letter regions and throws checked exceptions: never use either
  in voice-matching paths or tests exercising legacy codes; the
  `@SuppressWarnings` + `legacyLocale` pattern in `TtsService` is the
  template.
- Java annotations can't go on lambdas: suppress the enclosing method.
- An inserted helper can split an `@Override` from its method (fatal
  compile error): always anchor edits on unique full blocks and re-read
  the region after editing.
- Tests reinstall the app: data-dependent tests must call
  `ensureVoiceData()` first (see `CheckVoiceDataTest`).
- `connectedAndroidTest` output APKs can go stale; `cleanPackageDebug
  packageDebug` before trusting APK sizes.

## 8. Definition of done

Zero `android.preference` imports, zero related javac warnings, 103/103
device tests green, prefs survive a release-upgrade test, APK size delta
recorded in the final commit message, this file deleted or archived.
