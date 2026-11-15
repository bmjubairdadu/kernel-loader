# Contributing to Kernel Loader

Thank you for your interest in contributing to **Kernel Loader**! We welcome contributions from developers, kernel enthusiasts, and users worldwide. Whether you want to report a bug, submit a new kernel driver compatibility profile, improve the UI, or add translations, your help makes this project better for everyone.

---

## 📋 Table of Contents
- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
  - [1. Reporting Bugs](#1-reporting-bugs)
  - [2. Requesting New Driver Support](#2-requesting-new-driver-support)
  - [3. Suggesting Enhancements](#3-suggesting-enhancements)
  - [4. Adding or Improving Translations](#4-adding-or-improving-translations)
  - [5. Code Contributions (Pull Requests)](#5-code-contributions-pull-requests)
- [Development Setup](#development-setup)
- [Coding Guidelines & Standards](#coding-guidelines--standards)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Community & Questions](#community--questions)

---

## Code of Conduct

This project and everyone participating in it is governed by our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

---

## How Can I Contribute?

### 1. Reporting Bugs
If you encounter a bug, crash, or unexpected behavior:
1. Search existing [GitHub Issues](https://github.com/bmjubairdadu/kernel-loader/issues) to ensure the bug hasn't already been reported.
2. If it is new, open an issue using the **[Bug Report](.github/ISSUE_TEMPLATE/bug_report.yml)** template.
3. Include device details:
   - Device model & manufacturer (e.g., Xiaomi Redmi 10C, Poco M3)
   - Android version (e.g., Android 12, 13, 14)
   - Root method (Magisk, KernelSU, or APatch)
   - Kernel vermagic (visible on the app's home screen)
   - Terminal logs or logcat output

### 2. Requesting New Driver Support
If your device's kernel vermagic is not yet supported in the database:
1. Use the built-in **Report Pack Builder** in the app to generate a diagnostic dump (`.zip`).
2. Open a **[Driver Request](.github/ISSUE_TEMPLATE/driver_request.yml)** issue.
3. Provide:
   - Kernel version (`uname -r`)
   - Exact kernel vermagic
   - SoC platform (Qualcomm Snapdragon, MediaTek, Exynos, Unisoc)

### 3. Suggesting Enhancements
Feature requests are always welcome! Open an issue using the **[Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml)** template and describe:
- What problem the proposed feature solves.
- How you envision the feature working.

### 4. Adding or Improving Translations
Kernel Loader supports 28+ languages. If you'd like to improve an existing translation or add a new language:
- Localization strings are maintained in `app/src/main/java/com/kernelloader/ui/AppStrings.kt`.
- Open a PR with your translation additions in `SupportedLanguages` and the corresponding `UiStrings` mapping.

### 5. Code Contributions (Pull Requests)
We welcome PRs that fix bugs, improve performance, or add meaningful features.

---

## Development Setup

### Prerequisites
- **Java Development Kit (JDK):** JDK 17 (Temurin or OpenJDK recommended)
- **Android SDK:** Command-line tools or Android Studio Iguana+ (API 34/35 platform tools)
- **Git**

### Building the Project
Clone the repository:
```bash
git clone https://github.com/bmjubairdadu/kernel-loader.git
cd kernel-loader
```

Build the release APK:
```bash
# On Linux / macOS / WSL
./gradlew :app:assembleRelease

# On Windows
gradlew.bat :app:assembleRelease
```

The resulting APK will be generated at:
`app/build/outputs/apk/release/Kernel-Loader-v<version>.apk`

### Testing on a Physical Device
Connect your rooted ARM64 test device via USB with ADB enabled:
```bash
adb install -r app/build/outputs/apk/release/Kernel-Loader-v*.apk
adb shell monkey -p com.kernelloader -c android.intent.category.LAUNCHER 1
```

---

## Coding Guidelines & Standards

- **Language:** Kotlin 2.0+ with Jetpack Compose.
- **UI Architecture:** Modern Compose with declarative states and Material 3 design system.
- **Clean Code Rule:** Do not include comments (`//` or `/* */`) in Kotlin source files. Keep the code self-documenting, clean, and concise.
- **Commit Messages:** Follow [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat: add new feature`
  - `fix: resolve bug in driver verification`
  - `docs: update setup instructions`
  - `refactor: optimize string memory allocation`

---

## Submitting a Pull Request

1. **Fork** the repository to your own GitHub account.
2. **Create a topic branch** from `main`:
   ```bash
   git checkout -b feat/my-new-feature
   ```
3. **Make your changes** and test them thoroughly on an Android device or emulator.
4. **Commit** your changes with a clear, concise commit message.
5. **Push** to your fork:
   ```bash
   git push origin feat/my-new-feature
   ```
6. Open a **Pull Request** targeting the `main` branch of `bmjubairdadu/kernel-loader`.
7. Fill out the PR template checklist completely.
8. The GitHub Actions CI will automatically run build checks on your PR.

---

## Community & Questions

- **Author & Maintainer:** [JUBAIR HOSEN](https://github.com/bmjubairdadu)
- **Telegram Support & Discussion:** [@Jubairdadubm](https://t.me/Jubairdadubm)
- **Issues & Discussions:** [GitHub Issues](https://github.com/bmjubairdadu/kernel-loader/issues)

Thank you for contributing to Kernel Loader!
