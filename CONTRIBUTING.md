# Contributing to Urik Keyboard

## Accepted Contributions

- Bug fixes
- Translation updates (improvements or new languages)
- Feature requests

## Before You Start

### Bug Fixes
1. Check existing issues to avoid duplicates
2. Open an issue describing the bug if none exists
3. Reference the issue in your PR

### Feature Requests
1. Open an issue tagged `enhancement`
2. Wait for maintainer approval before implementing
3. PRs without prior approval are less likely to merge

### Translations
- Default English: `res/values/strings.xml`
- New languages: `res/values-{code}/strings.xml`

## Development Process

1. Fork the repository
2. Create feature branch from `develop`
3. Make your changes following code standards
4. Run tests locally: `./gradlew test`
5. Ensure ktlint passes: `./gradlew ktlintCheck`
7. Push to your fork
8. Open PR against `develop` branch

## Code Standards

### Required
- ktlint must pass (CI enforces this)
- Existing tests must pass

### Privacy-First Principles
**CRITICAL:** This is a privacy-first keyboard. PRs violating these principles will be rejected:
- No network calls
- No analytics or telemetry
- No external dependencies that phone home
- No data collection beyond local device storage
- All user data stays encrypted on device

## Pull Request Requirements

- Branch from `develop`, target `develop`
- Include tests for new code
- Pass all CI checks (ktlint, tests, build)
- Reference related issue number if available

## Security Vulnerabilities

See [SECURITY.md](SECURITY.md) for reporting security issues. **Do not open public issues for vulnerabilities.**

## Questions?

Open a GitHub issue for discussion before starting work.