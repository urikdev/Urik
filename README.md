# Urik

[![CI](https://github.com/urikdev/Urik/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/urikdev/Urik/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/urikdev/Urik?include_prereleases)](https://github.com/urikdev/Urik/releases)

Privacy-first keyboard for Android with on-device processing and encrypted storage.

## Status

**Beta Software** - In closed beta testing. Under active development.

## Features

- Swipe and tap input
- On-device spell checking (no network requests)
- Multilingual support
- Encrypted local database (SQLCipher)
- Word learning with privacy controls
- No telemetry or analytics
- No network permissions

## Privacy

All processing happens on-device. No data leaves your phone. User dictionary and learned words are stored in an encrypted local database.

## Requirements

- Android 8.0 or higher (API level 26+)

## Building from Source
```bash
git clone https://github.com/urikdev/Urik.git
cd Urik
./gradlew assembleDebug
```

Requirements: Android Studio Ladybug+, JDK 17+, Android SDK 34+

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Security

Report vulnerabilities via [SECURITY.md](SECURITY.md).

## Dictionary Data

Spell checking uses word frequency lists from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by hermitdave, derived from the OpenSubtitles corpus.

- License: CC-BY-SA-4.0
- Modifications: Sorted by frequency, filtered for keyboard use

## License

GNU General Public License v3.0

<p align="center"><a href="https://www.buymeacoffee.com/urikdevelopment" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a></p>