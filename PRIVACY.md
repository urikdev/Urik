# Privacy Policy

**Effective Date:** October 18, 2025  
**Last Updated:** November 13, 2025

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

### 2. Keyboard Settings (Not Encrypted)

**What:** Your keyboard preferences and configuration.

**Examples:**
- Theme selection
- Key size preferences
- Haptic feedback settings
- Enabled languages
- Suggestion display preferences

**Storage:** Local DataStore (Android Preferences API) on your device.

**Purpose:** To remember your keyboard customization choices.

### 3. Error Logs (Local Only)

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

### 4. Temporary In-Memory Data

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
- **Deletion Options:**
    - Clear all learned words: Settings → Privacy & Data → Clear Learned Words
    - Reset settings to defaults: Settings → Privacy & Data → Reset to Defaults
    - Uninstall app: Automatically deletes all data

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
- All settings
- All error logs
- All cached data
- Encryption keys from Android Keystore

## Your Privacy Rights

### Rights Under GDPR (EU) and PIPEDA (Canada)

You have the right to:

1. **Access Your Data**
    - View your learned words: Settings → Privacy & Data → Learned Words (feature not yet available, but data is on your device)
    - View error logs: Settings → Privacy & Data → Export Error Logs

2. **Delete Your Data**
    - Clear specific learned words: Long-press suggestions to remove
    - Clear all learned words: Settings → Privacy & Data → Clear Learned Words
    - Clear all data: Settings → Privacy & Data → Clear All Data
    - Uninstall the app to delete everything

3. **Rectify Your Data**
    - Remove incorrect learned words and re-type correct versions
    - System will learn the corrected spelling

4. **Data Portability**
    - **Limitation:** While your learned words are stored locally in an SQLCipher database, there is no built-in feature to export them in a portable format
    - **Manual Request:** If you need your data urgently, contact us at hello@urik.io with device details, and we can provide instructions for manual database extraction (requires technical knowledge)

5. **Withdraw Consent**
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

1. **Consent (Article 6(1)(a)):** You consent to word learning by using the keyboard with word learning enabled
2. **Legitimate Interest (Article 6(1)(f)):** We have a legitimate interest in processing error logs to maintain and improve the keyboard

You can withdraw consent at any time by disabling word learning in settings or uninstalling the app.

## Contact Us

For privacy questions, data requests, or concerns:

**Email:** hello@urik.io
**GitHub Issues:** https://github.com/urikdev/Urik/issues

We will respond to privacy inquiries within 30 days.

---

Your privacy is our priority. If you have questions or concerns, please contact us.