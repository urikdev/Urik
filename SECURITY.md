# Security Policy

## Supported Versions

We support only the latest version available on Google Play. Please ensure automatic updates are enabled.

| Version | Supported          |
| ------- | ------------------ |
| Latest (Google Play) | :white_check_mark: |

## Reporting a Vulnerability

**Please do NOT open public issues for security vulnerabilities.**

Contact: **urikdevelopment@proton.me**

**Include in your report:**
- Description of the vulnerability
- Steps to reproduce
- Potential impact (privacy, data exposure, etc.)
- Affected versions (if known)
- Suggested fix (optional)

**Response timeline:**
- Initial acknowledgment: Within 48 hours
- Status update: Within 7 days
- Fix deployment: Varies by severity (critical issues prioritized)

**What happens after you report:**
1. We'll confirm receipt and assess severity
2. We'll develop and test a fix
3. We'll deploy the patch via Google Play update

## Security Scope

Urik is a privacy-first keyboard with:
- Zero network activity
- Local-only data storage (SQLCipher encrypted)
- No analytics or telemetry
- No cloud sync

**In scope:**
- Input handling vulnerabilities
- Data leakage (logs, IPC, broadcasts)
- Encryption implementation issues
- Privilege escalation
- Dependency vulnerabilities

**Out of scope:**
- Vulnerabilities requiring physical device access
- Social engineering attacks
- Issues in upstream dependencies (report to maintainers)
- Theoretical attacks without practical exploit

## Recognition

We appreciate security researchers who follow responsible disclosure. We're happy to credit you in release notes (if you wish to be named).