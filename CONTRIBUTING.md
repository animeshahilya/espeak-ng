# Contributing to eSpeak NG for Android

Thank you for your interest in contributing! This project is a fork of [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng) focused on the Android TTS engine.

## Ways to Contribute

- **Bug reports** - Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.md)
- **Feature requests** - Use the [feature request template](.github/ISSUE_TEMPLATE/feature_request.md)
- **Code contributions** - Fork, branch, and open a PR
- **Translations** - Help translate the UI via [Transifex](https://www.transifex.com/espeak-ng/espeak-ng-android/)
- **Testing** - Run tests on devices/emulators and report issues

## Development Setup

### Prerequisites
- JDK 17 (Temurin recommended)
- Android SDK (compileSdk 37)
- NDK 29.0.14206865
- CMake 3.22.1
- Git

### Building
```bash
cd android
./gradlew assembleDebug
```

### Running Tests
```bash
# Requires connected device or emulator (API 34+)
./gradlew connectedAndroidTest
```

## Code Style

### Java/Kotlin
- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use 4-space indentation
- Run `./gradlew spotlessApply` before committing (if configured)

### C/C++ (JNI layer)
- Follow the existing code style in `android/jni/`
- Use `-O3` only for Release builds (see `android/jni/CMakeLists.txt`)
- Export new JNI functions in `android/jni/jni/ttsespeak.map`

### Native Library (libespeak-ng)
- Upstream changes should go to [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng)
- This fork only modifies Android-specific build glue

## Pull Request Process

1. **Fork** the repository and create a feature branch
2. **Write tests** for new functionality (instrumentation tests in `android/eSpeakTests/`)
3. **Ensure CI passes** - all builds and tests must succeed
4. **Update documentation** - README, CHANGELOG.md, and relevant docs
5. **Keep commits focused** - one logical change per commit
6. **Reference issues** - use `Fixes #123` or `Closes #123` in commit messages

### PR Requirements
- [ ] Debug and Release builds pass
- [ ] Instrumentation tests pass on API 34+
- [ ] No new warnings in CI
- [ ] CHANGELOG.md updated (under `## Unreleased`)
- [ ] Documentation updated if user-facing changes

## NVDA Parity Rule

**Critical**: Every non-Indian voice must produce identical output to eSpeak in NVDA. Changes affecting synthesis must:
- Pass `PipelineSnapshotTest` (diff against baseline)
- Be gated behind opt-in settings defaulting to OFF
- Not affect Indian voices unless explicitly intended

## Voice Data Changes

Voice data comes from `dictsource/` and `phsource/`. To update:
1. Modify source files
2. Run `./gradlew assembleDebug` - CMake rebuilds `espeak-ng-data/` automatically
3. Verify with `PipelineSnapshotTest`

## Architecture Notes

See [android/CLAUDE.md](android/CLAUDE.md) for detailed architecture, build configuration, and common workflows.

## License

By contributing, you agree that your contributions will be licensed under:
- GPL v3 or later (main codebase)
- BSD 2-Clause (getopt compatibility code)
- CC BY-SA 4.0 (Hinglish/Urdu datasets)

## Code of Conduct

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before participating.

## Questions?

- Open a [discussion](https://github.com/animeshahilya/espeak-ng/discussions)
- Check existing [issues](https://github.com/animeshahilya/espeak-ng/issues)