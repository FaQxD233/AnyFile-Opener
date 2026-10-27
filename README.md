# 🌉 AnyFile Opener (OpenBridge)

**AnyFile Opener** (internally known as **OpenBridge**) is a lightweight Android utility that acts as a universal bridge for file intents. It solves the common "No app can open this file" error and fixes "Permission Denied" issues when opening files from restricted app caches.

[![Status](https://img.shields.io/badge/Status-Active%20Development-green.svg)](#overall-progress)
[![API](https://img.shields.io/badge/API-26%2B-blue.svg)](https://developer.android.com)

## 🚀 Key Highlights
- **Binary Signature Detection**: Identifies files by their magic bytes (headers), not just unreliable extensions.
- **Robust URI Forwarding**: Ensures target apps (like VLC, MX Player, or Installers) have proper permissions to read files from restricted folders.
- **Universal Handler**: Registers for `*/*` to provide a fallback when the system is stumped.
- **File Details**: Path (or URI) with a copy button, size, modification time, MD5/SHA-256 checksums and APK details, including certificates replaced through key rotation.
- **Extension Fixes**: Flags files whose extension does not match their content; save a copy or open them under the corrected name.
- **Light & Dark Themes**: System default, light or dark, built around a midnight `#1F1E33` palette.
- **Background Copies**: Saving a copy, or preparing one under a corrected name, keeps running with a progress notification and a cancel action.
- **Recent Files & Widgets**: Quick access to your history and system storage roots.

## 🖼 App Screenshot

![App screenshot](images/photo_2026-05-31_19-16-11.jpg)


---

## 📂 Documentation
For detailed technical information, architecture diagrams, and feature lists, please refer to:
👉 **[Project notes](./gemini.md)**

---

## 🛠 Usage

### 1. The "Open with" Flow (Passive)
Simply choose **AnyFile Opener** when clicking a file in any app. The bridge will analyze the file and offer the best categories (Video, Audio, APK, etc.) to re-dispatch the file correctly.

Multiple shared files are kept in a sequential queue. Each item can be opened,
skipped, or used to cancel the remaining queue.

### 2. The Launcher Flow (Active)
Open the app directly to:
- **Pick a File**: Browse storage and manually select a file to open.
- **Force MIME**: Manually type a MIME type (e.g., `application/pdf`) to force-open a mislabelled file.
- **Details**: See where the file is, its size and modification time, compute checksums, and read APK details.
- **`Open with <app>`**: Skip the chooser and use the app last picked for this kind of file.
- **Save Copy / Open As**: Store a copy anywhere, or forward one under the corrected file name.
- **Detection Details**: Review confidence, detection source, and concrete evidence.

### 3. Home Screen Widget
Add the **Files shortcut** widget to your home screen for a one-tap shortcut to the system file manager at internal storage.

---

## 🏗 Build & Install

### Prerequisites
- JDK 17
- Android SDK (API 34)

### Build Command
```powershell
./gradlew assembleDebug
```

### Install
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

The `Build Android` workflow is intentionally manual. Open the repository's
**Actions** tab, run the workflow, enter a version, and choose whether to create a
GitHub Release. Without signing secrets it produces a debug APK. With
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
`ANDROID_KEY_PASSWORD`, it produces a signed release APK.
To build without publishing, for example to verify a change, set
**Create GitHub Release** to false (`-f create_release=false` with `gh workflow run`).

---

## 📈 Overall Progress
- [x] Magic-byte MIME Detection (33KB deep peek)
- [x] URI Permission Bridging
- [x] Deep peeking for ZIP/Office/ISO formats
- [x] Recent Files History
- [x] File details sheet (path, size, modification time, checksums, APK details)
- [x] Extension mismatch warnings, Save copy and corrected-name forwarding
- [x] Home Screen Widgets
- [x] Settings & Themes (system, light, dark)
- [x] Background copies with a progress notification


---

## ⚖ License
This project is provided for utility and educational purposes. See the repository for specific licensing details.
