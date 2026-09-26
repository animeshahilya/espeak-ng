# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| Latest release | :white_check_mark: |
| Previous release | :white_check_mark: (security fixes only) |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, report them privately via:

1. **GitHub Security Advisories** - Use the "Report a vulnerability" tab on the [Security page](https://github.com/animeshahilya/espeak-ng/security/advisories)
2. **Email** - Send details to the maintainer (see GitHub profile)

### What to Include
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)
- Whether you've disclosed it elsewhere

## Response Timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 7 days
- **Fix timeline**: Depends on severity; critical issues targeted within 30 days

## Security Considerations

This app:
- Has **no `INTERNET` permission** - no network access
- Uses **device-protected storage** for voice data and settings (Direct Boot compatible)
- Runs native code via JNI - only exports `Java_*` functions via `ttsespeak.map`
- Bundles all 153 languages offline - no dynamic downloads
- Uses `minifyEnabled true` with explicit `-keep` rules for JNI entry points

## Disclosure Policy

We follow [Coordinated Vulnerability Disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure). We will:
1. Confirm receipt
2. Investigate and validate
3. Develop and test a fix
4. Release a patched version
5. Publish a security advisory (with credit to reporter if desired)

## Scope

This policy covers:
- The Android app (`android/` directory)
- The bundled `libespeak-ng` native library
- Build infrastructure (Gradle, GitHub Actions)

Upstream `espeak-ng` core library issues should be reported to [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng/security).