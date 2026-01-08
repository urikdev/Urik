# Privacy Policy

**Effective Date:** October 18, 2025
**Last Updated:** January 7, 2026

## Introduction

Urik is a privacy-first keyboard for Android. This privacy policy explains what data we collect, how we use it, and your rights regarding your personal information.

**Core Privacy Principles:**
- All processing happens on your device
- No data is transmitted over the network
- No analytics or telemetry
- No third-party tracking
- You own and control your data

## Data Controller

**Urik Development**  
Email: hello@urik.io
GitHub: https://github.com/urikdev/Urik

For privacy inquiries, contact us at the email above.

## Data We Collect

### 1. Learned Words (Encrypted)

**What:** Words you type or swipe while using the keyboard.

**Purpose:** To provide personalized autocorrect, suggestions, and word completion.

**Storage:** Encrypted local SQLCipher database on your device using hardware-backed encryption (Android Keystore).

**Details Stored:**
- The word itself (lowercase, normalized)
- Language code
- Frequency of use (count)
- Input method (typed, swiped, or selected from suggestions)
- Timestamps (when created, last used)
- Character count

**What We Don't Store:**
- Context or sentences
- Which app you typed the word in
- Your location when typing
- Device identifiers
- User identifiers

**Encryption:** Your learned words are encrypted using AES-256 encryption. The encryption key is stored in the Android Keystore, a hardware-backed secure storage system. If your device has a lock screen (PIN, pattern, or biometric), the encryption key is protected by this lock screen. Without your device unlock, your learned words cannot be decrypted.

**Fallback:** If your device does not have a lock screen configured, learned words are stored without encryption, and you will see a warning about this in the app.

### 2. Clipboard History (Encrypted)

**What:** Text content copied to your device's system clipboard from any app.

**Purpose:** To provide quick access to your recent clipboard history for convenient re-use.

**Storage:** Encrypted local SQLCipher database on your device using hardware-backed encryption (Android Keystore).

**Details Stored:**
- Text content of clipboard items (truncated to first 100,000 characters if longer)
- Timestamp when copied
- Pin status (if you manually pin items)

**What We Don't Store:**
- Which app copied the text
- Context or surrounding content
- Your location when copying
- Device identifiers
- User identifiers

**Encryption:** Same AES-256 encryption as learned words. The encryption key is stored in the Android Keystore and protected by your device lock screen.

**Monitoring Behavior:**
- **Enabled by default** on first install
- Monitors all clipboard activity system-wide (not limited to keyboard input)
- Stores text copied from any app (web browser, messaging, password managers, etc.)
- Starts monitoring immediately when keyboard is installed
- Consent screen shown when you first access clipboard history (long-press symbols key)

**Limitations:**
- Text truncated to 100,000 characters (approximately 100KB)
- Maximum 100 unpinned items (oldest automatically deleted)
- Pinned items never auto-deleted
- Only stores text content (ignores images, files, etc.)
- Duplicate detection prevents storing identical consecutive copies

**User Control:**
- **Access clipboard history:** Long-press symbols key on keyboard
- **Pin items:** Tap pin icon to prevent auto-deletion
- **Delete individual items:** Tap × button
- **Delete all unpinned items:** Tap "Delete All" button in Recent tab
- **Disable monitoring completely:** Settings → Privacy & Data → Clipboard History (toggle off)
- **Clear all clipboard data:** Settings → Privacy & Data → Clear All Data

**Important:** Clipboard monitoring captures text from all apps, including sensitive data if you copy passwords, credit card numbers, or private messages. While stored encrypted, consider disabling the feature if you frequently copy sensitive information. The keyboard cannot distinguish between sensitive and non-sensitive clipboard content.

### 3. Recent Emoji Selections (Not Encrypted)

**What:** Your recently selected emojis from the emoji picker.

**Purpose:** To provide quick access to frequently used emojis.

**Storage:** Local SharedPreferences on your device (Android Preferences API).

**Details Stored:**
- Up to 50 most recently selected emoji characters
- Ordered by recency (most recent first)

**What We Don't Store:**
- When you selected each emoji
- Which app you used the emoji in
- Context or surrounding text

**Encryption:** Not encrypted (stored as plain text preferences).

**User Control:**
- Data automatically limited to 50 emojis (oldest removed when exceeded)
- Cleared when you uninstall the app
- Cannot be manually cleared without uninstalling

### 4. Keyboard Settings (Not Encrypted)

**What:** Your keyboard preferences and configuration.

**Examples:**
- Theme selection
- Key size preferences
- Haptic feedback settings
- Enabled languages
- Suggestion display preferences

**Storage:** Local DataStore (Android Preferences API) on your device.

**Purpose:** To remember your keyboard customization choices.

### 5. Error Logs (Local Only)

**What:** Technical error information when the keyboard encounters critical failures.

**Details Logged:**
- Component name where error occurred
- Exception type and message
- Stack trace (code location)
- Timestamp
- Script/language context

**What We Don't Log:**
- User input or typed text
- Personal information

**Storage:** Local JSON file on your device (`error_log.json`), maximum 100 entries or 500KB.

**Purpose:** To help debug issues if you choose to share logs when reporting problems.

**Sharing:** Error logs are never transmitted automatically. You can manually export and share them via the settings menu if you choose to report a bug.

### 6. Temporary In-Memory Data

**What:** Recent words and processing data held briefly in RAM for performance.

**Details:**
- Last ~200 typed words (automatically cleared after 5 minutes)
- Spell check results cache
- Dictionary lookup cache

**Lifecycle:** Automatically cleared when:
- Data expires (5-minute time-to-live)
- You switch languages
- You switch to a different app
- The keyboard service stops

**Secure Fields:** When you type in password fields or other secure inputs, all processing is bypassed. No text is cached, spell-checked, or learned.

## How We Use Your Data

All data processing occurs locally on your device:

- **Learned Words:** Generate personalized suggestions and autocorrect
- **Clipboard History:** Quick access to recently copied text for re-use
- **Recent Emoji Selections:** Display frequently used emojis first in emoji picker
- **Settings:** Apply your keyboard preferences
- **Error Logs:** Debug issues (only if you share them)
- **Temporary Caches:** Improve typing performance and responsiveness

**We do not:**
- Transmit any data to external servers
- Use your data for advertising or marketing
- Sell or share your data with third parties
- Build user profiles or track behavior
- Use analytics or telemetry services

## Data Storage and Security

### Encryption

Your learned words are protected by:

1. **AES-256 encryption** using SQLCipher
2. **Hardware-backed key storage** in Android Keystore (Trusted Execution Environment or StrongBox)
3. **Device lock screen protection** - encryption key accessible only when device unlocked

### Network Isolation

The Urik keyboard has **no INTERNET permission**. It is technically impossible for the keyboard to transmit data over the network, even if compromised.

### Secure Field Detection

The keyboard automatically detects password fields, credit card inputs, email addresses, URL bars, and other secure text fields. In these fields:
- No text is processed or cached
- No suggestions are displayed
- No words are learned
- All processing is bypassed

## Data Retention

### Learned Words
- **Retention:** Indefinite, until you manually delete them or uninstall the app
- **Management Options:**
    - Export dictionary: Settings → Privacy & Data → Export Dictionary
    - Import dictionary: Settings → Privacy & Data → Import Dictionary
    - Clear all learned words: Settings → Privacy & Data → Clear Learned Words
    - Reset settings to defaults: Settings → Privacy & Data → Reset to Defaults
    - Uninstall app: Automatically deletes all data

### Clipboard History
- **Retention:** Indefinite for pinned items; unpinned items auto-deleted after 100 items reached (oldest first)
- **Deletion Options:**
    - Delete individual items: Long-press symbols key → tap × button
    - Delete all unpinned items: Long-press symbols key → Recent tab → Delete All
    - Disable monitoring: Settings → Privacy & Data → Clipboard History (toggle off)
    - Clear all clipboard data: Settings → Privacy & Data → Clear All Data
    - Uninstall app: Automatically deletes all data

### Recent Emoji Selections
- **Retention:** Indefinite, until you uninstall the app (automatically limited to 50 most recent)
- **Deletion Options:**
    - Uninstall app: Automatically deletes all data
    - No in-app option to clear recent emoji selections

### Settings
- **Retention:** Until you reset to defaults or uninstall the app

### Error Logs
- **Retention:** Maximum 100 entries or 500KB, automatically rotated
- **Export Only:** Settings → Privacy & Data → Export Error Log

### Temporary Caches
- **Retention:** Maximum 5 minutes, then automatically cleared

### On Uninstall
When you uninstall Urik, Android automatically deletes:
- All learned words (encrypted database)
- All clipboard history (encrypted database)
- All recent emoji selections
- All settings
- All error logs
- All cached data
- Encryption keys from Android Keystore

## Your Privacy Rights

### Rights Under GDPR (EU) and PIPEDA (Canada)

You have the right to:

1. **Access Your Data**
    - View clipboard history: Long-press symbols key on keyboard
    - View error logs: Settings → Privacy & Data → Export Error Logs
    - Your learned words and clipboard history are stored locally on your device in an encrypted database

2. **Delete Your Data**
    - Clear specific learned words: Long-press suggestions to remove
    - Clear specific clipboard items: Long-press symbols key → tap × button
    - Clear all clipboard unpinned items: Long-press symbols key → Recent tab → Delete All
    - Clear all learned words: Settings → Privacy & Data → Clear Learned Words
    - Clear all data (includes clipboard): Settings → Privacy & Data → Clear All Data
    - Uninstall the app to delete everything

3. **Rectify Your Data**
    - Remove incorrect learned words and re-type correct versions
    - System will learn the corrected spelling

4. **Data Portability**
    - Export learned words: Settings → Privacy & Data → Export Dictionary
    - Import learned words: Settings → Privacy & Data → Import Dictionary
    - Export format: JSON file containing word, language, frequency, and timestamps
    - Import behavior: Merges with existing dictionary (sums frequencies for duplicates)
    - **Note:** Exported files are not encrypted. Store securely if they contain sensitive words.

5. **Withdraw Consent**
    - Disable clipboard monitoring: Settings → Privacy & Data → Clipboard History (toggle off)
    - Disable word learning: Settings → Typing Behavior → Disable "Learn Words"
    - Disable spell check: Settings → Typing Behavior → Disable "Spell Check"

6. **Lodge a Complaint**
    - EU residents: Contact your national data protection authority
    - Canadian residents: Contact the Office of the Privacy Commissioner of Canada
    - Contact us first: hello@urik.io

### Rights Under CCPA (California)

California residents have the right to:

1. **Know:** What personal information we collect and how we use it (see sections above)
2. **Delete:** Request deletion of your personal information (use in-app deletion options)
3. **Opt-Out:** We do not sell personal information
4. **Non-Discrimination:** We do not discriminate based on privacy rights exercise

### Exercising Your Rights

Most privacy rights can be exercised directly in the app via Settings → Privacy & Data. For requests that cannot be fulfilled through the app, email hello@urik.io.

## Third-Party Data and Services

### Dictionary Data

The keyboard uses word frequency lists derived from the **FrequencyWords** project for spell checking and suggestions.

- **Source:** [FrequencyWords by hermitdave](https://github.com/hermitdave/FrequencyWords)
- **License:** CC-BY-SA-4.0
- **Original Data:** OpenSubtitles corpus
- **Modifications:** Sorted by frequency, filtered for relevance
- **Privacy:** Dictionary data is bundled in the app. No external requests are made.

### Emoji Annotations

The keyboard uses multilingual emoji keyword annotations from the **Unicode CLDR** (Common Locale Data Repository) for emoji search functionality.

- **Source:** [Unicode CLDR](https://github.com/unicode-org/cldr-json)
- **License:** Unicode License V3
- **Original Data:** Multilingual Unicode Consortium emoji annotations
- **Modifications:** Extracted emoji annotations, converted to searchable JSON format
- **Privacy:** Emoji annotation data is bundled in the app. No external requests are made.

### No Other Third Parties

Urik does not integrate with or share data with:
- Analytics services (e.g., Google Analytics, Firebase Analytics)
- Crash reporting services (e.g., Crashlytics)
- Advertising networks
- Social media platforms
- Cloud services
- Any other external services

All dependencies are open-source libraries for local processing only.

## Children's Privacy

Urik does not knowingly collect personal information from children under 13 years of age. The keyboard is not specifically designed for children, but it is safe for all ages because:

- No data is transmitted off-device
- No account creation required
- No online interactions
- No advertising or tracking

Parents can review and delete any learned words through the keyboard settings.

## Changes to This Privacy Policy

We may update this privacy policy from time to time to reflect changes in:
- Legal requirements
- App functionality
- Privacy practices

**How We Notify You:**
- Updated "Last Updated" date at the top of this document
- Notification in app release notes on GitHub
- For material changes: In-app notification on first launch after update

**Your Continued Use:** By continuing to use Urik after changes take effect, you accept the updated privacy policy.

**Version History:** All versions of this privacy policy are available in the GitHub repository commit history.

## Data Processing Location

All data processing occurs locally on your device. No data is transmitted to external servers or processed in any other location.

## Open Source and Transparency

Urik is open-source software. You can review the complete source code, including all data collection and processing logic, at:

**https://github.com/urikdev/Urik**

We encourage security researchers and privacy advocates to audit the code and report any concerns.

## Legal Basis for Processing (GDPR)

Under GDPR Article 6, our legal bases for processing your personal data are:

1. **Consent (Article 6(1)(a)):**
    - Word learning: By using the keyboard with word learning enabled
    - Clipboard monitoring: By continuing to use the keyboard after first install with clipboard monitoring enabled by default (opt-out consent model)
2. **Legitimate Interest (Article 6(1)(f)):** We have a legitimate interest in processing error logs to maintain and improve the keyboard

You can withdraw consent at any time by disabling clipboard monitoring, disabling word learning in settings, or uninstalling the app.

## Contact Us

For privacy questions, data requests, or concerns:

**Email:** hello@urik.io
**GitHub Issues:** https://github.com/urikdev/Urik/issues

We will respond to privacy inquiries within 30 days.

---

Your privacy is our priority. If you have questions or concerns, please contact us.