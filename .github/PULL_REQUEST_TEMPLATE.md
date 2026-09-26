# Pull Request Template

## Description
Brief description of changes and motivation.

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Refactoring
- [ ] Documentation update
- [ ] Test addition/improvement
- [ ] Build/CI change
- [ ] Voice data update

## NVDA Parity Check
**Required for any synthesis-affecting changes:**
- [ ] This change does NOT affect non-Indian voice output, OR
- [ ] This change is gated behind an opt-in setting defaulting to OFF
- [ ] `PipelineSnapshotTest` passes (run locally or check CI)
- [ ] Indian voices behave as intended

## Testing
- [ ] Debug build passes (`./gradlew assembleDebug`)
- [ ] Release build passes (`./gradlew assembleRelease`)
- [ ] Instrumentation tests pass (`./gradlew connectedAndroidTest`)
- [ ] Added/updated tests for new functionality
- [ ] Tested on device/emulator (API 34+)

## Documentation
- [ ] CHANGELOG.md updated under `## Unreleased`
- [ ] README.md updated if user-facing change
- [ ] Code comments added for complex logic
- [ ] android/CLAUDE.md updated if architecture changed

## Checklist
- [ ] Commits are focused (one logical change per commit)
- [ ] Commit messages follow conventional format (`type: description`)
- [ ] No new CI warnings
- [ ] No hardcoded secrets/keys
- [ ] ProGuard rules updated if JNI surface changed

## Screenshots/Videos
If UI change, attach before/after screenshots or screen recordings.

## Related Issues
Fixes #(issue number)
Closes #(issue number)
Related to #(issue number)

## Additional Notes
Anything else reviewers should know.