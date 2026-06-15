# Urik

[![CI](https://github.com/urikdev/Urik/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/urikdev/Urik/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/urikdev/Urik?include_prereleases)](https://github.com/urikdev/Urik/releases)
[![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/urikdev/Urik/total)](https://github.com/urikdev/Urik/releases)

Privacy-focused Android keyboard with swipe typing, custom layouts, and password manager support. No tracking, 100% on-device, and fully open source.

<p float="left" align="middle">
  <img src="https://urik.io/screenshots/notracking.webp" width="200">
  <img src="https://urik.io/screenshots/multilingual.webp" width="200">
  <img src="https://urik.io/screenshots/swipe.webp" width="200">
  <img src="https://urik.io/screenshots/customize.webp" width="200">
</p>

## Status

**Beta Software** - In open beta testing.
<p><a href="https://f-droid.org/packages/com.urik.keyboard/" target="_blank"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" style="width: 280px"></a></p>
<p>Or download the APK directly from <a href="https://github.com/urikdev/Urik/releases" target="_blank">GitHub Releases</a>.</p>


## Features

**Input**
- Swipe typing with geometric path matching and vertex detection
- One-handed and split modes with coordinate transformation
- Adaptive layout for tablets and foldables
- Long-press for character variations and custom key mapping
- Long-press comma key to switch keyboards (IME picker)
- Spacebar swipe for cursor control
- Backspace swipe to delete previous word
- Optional number row
- QWERTZ, AZERTY, Dvorak, Colemak, Workman, Bds and Hcesar layouts
- Hardware keyboard detection

**Intelligence**
- Local bigram model for next-word prediction
- On-device spell checking using URIK (Ultra-compressed Ranked Input Korpus) — a custom binary dictionary format with Levenshtein automaton traversal
- Autocorrect with configurable pause-on-misspell; skips URLs, emails, and punctuation
- Word learning with encrypted SQLCipher database
- User-specific word frequency tracking
- 19 supported languages with dedicated language toggle button
- Merged dictionaries mode for multilingual typing sessions
- Emoji search with keyword support

**Integration**
- Inline autofill support for password managers (Android 11+)
- Clipboard history with encrypted storage

**Customization**
- 15+ built-in themes with favorites and Material You dynamic theming (Android 12+)
- Custom long-press symbol and key assignments
- Haptic feedback strength slider
- Auto-capitalization toggle
- Configurable key size, key label size, spacebar size, and cursor speed
- Manage learned words: browse, search, and delete entries
- WCAG AA contrast and TalkBack support

**Privacy**
- No telemetry, analytics, or network permissions
- Local on-device processing
- Encrypted local storage for user data (AES-256 + Android Keystore)
- Biometric authentication for accessing learned words
- Dictionary export and import for backup or migration
- Bigram predictions and word frequencies remain local-only (not exported)

## Privacy

All processing happens on-device. No data leaves your phone. User dictionary and learned words are stored in an encrypted local database.

We answer to our users, not investors. 

## Requirements

- Android 8.0 or higher (API level 26+)

## Building from Source
```bash
git clone https://github.com/urikdev/Urik.git
cd Urik
./gradlew assembleDebug
```

Requirements: Android Studio Panda+, JDK 17+, Android SDK 36+

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Security

Report vulnerabilities via [SECURITY.md](SECURITY.md).

## Dictionary Data

Spell checking uses word frequency lists from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by hermitdave, derived from the OpenSubtitles corpus.

- License: CC-BY-SA-4.0
- Modifications: Filtered top-N by frequency per language, compiled to URIK binary format at build time

## Emoji Annotations

Emoji search uses multilingual keyword annotations from [Unicode CLDR](https://github.com/unicode-org/cldr-json) (Common Locale Data Repository).

- License: Unicode License V3
- Modifications: Extracted emoji annotations, converted to searchable JSON format

## Japanese Conversion Data

Kana-to-kanji conversion uses dictionary data from [Mozc](https://github.com/google/mozc) by Google LLC.

- License: BSD 3-Clause
- Modifications: Extracted reading/surface/frequency triples; converted cost values to frequency scores; filtered single-character hiragana entries (covered by the spell-check dictionary)

## License

GNU General Public License v3.0

<p align="center"><a href="https://www.buymeacoffee.com/urikdevelopment" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a></p>
