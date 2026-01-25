# Urik

[![CI](https://github.com/urikdev/Urik/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/urikdev/Urik/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/urikdev/Urik?include_prereleases)](https://github.com/urikdev/Urik/releases)
[![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/urikdev/Urik/total)](https://github.com/urikdev/Urik/releases)

Privacy-focused Android keyboard with swipe typing, custom layouts, and password manager support. No tracking, 100% on-device, and fully open source.

<p float="left" align="middle">
  <img src="https://urik.io/screenshots/main.jpg" width="220">
  <img src="https://urik.io/screenshots/swipe.jpg" width="220">
  <img src="https://urik.io/screenshots/symbol.jpg" width="220">
</p>

## Status

**Beta Software** - In open beta testing.
<p><a href="https://play.google.com/store/apps/details?id=com.urik.keyboard" target="_blank"><img src="https://raw.githubusercontent.com/pioug/google-play-badges/06ccd9252af1501613da2ca28eaffe31307a4e6d/svg/English.svg" alt="Google Play Open Beta"></a></p>
<p><a href="https://f-droid.org/packages/com.urik.keyboard/" target="_blank"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" style="width: 280px"></a></p>


## Features

**Input**
- Swipe typing with geometric path matching and vertex detection
- One-handed and split modes with coordinate transformation
- Long-press for character variations and custom key mapping
- Long-press comma key to switch keyboards (IME picker)
- Spacebar swipe for cursor control
- QWERTZ, AZERTY, Dvorak, Colemak, and Workman layouts
- Hardware keyboard detection

**Intelligence**
- Local bigram model for next-word prediction
- On-device spell checking using SymSpell algorithm
- Smart autocorrect for URLs, emails, and punctuation context
- Word learning with encrypted SQLCipher database
- User-specific word frequency tracking
- Multilingual support with dedicated language toggle button
- Emoji search with keyword support

**Integration**
- Inline autofill support for password managers (Android 11+)
- Clipboard history with encrypted storage
- Material You dynamic theming (Android 12+)

**Customization**
- Custom long-press symbol and key assignments
- Haptic feedback strength slider
- Auto-capitalization toggle
- Configurable key sizes and typing behavior
- WCAG AA contrast and TalkBack support

**Privacy**
- No telemetry, analytics, or network permissions
- Local on-device processing
- Encrypted local storage for user data (AES-256 + Android Keystore)
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

Requirements: Android Studio Ladybug+, JDK 17+, Android SDK 34+

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Security

Report vulnerabilities via [SECURITY.md](SECURITY.md).

## Dictionary Data

Spell checking uses word frequency lists from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by hermitdave, derived from the OpenSubtitles corpus.

- License: CC-BY-SA-4.0
- Modifications: Sorted by frequency, filtered for keyboard use

## Emoji Annotations

Emoji search uses multilingual keyword annotations from [Unicode CLDR](https://github.com/unicode-org/cldr-json) (Common Locale Data Repository).

- License: Unicode License V3
- Modifications: Extracted emoji annotations, converted to searchable JSON format

## License

GNU General Public License v3.0

<p align="center"><a href="https://www.buymeacoffee.com/urikdevelopment" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a></p>
