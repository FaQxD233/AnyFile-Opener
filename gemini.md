# AnyFile X - Project Gemini Modernization

![Project Banner](https://img.shields.io/badge/Project-AnyFile%20X-blueviolet?style=for-the-badge&logo=android)
![Status](https://img.shields.io/badge/Status-Active%20Development-green?style=for-the-badge)
![UI](https://img.shields.io/badge/UI-Compose%20%2B%20Material%203-orange?style=for-the-badge&logo=jetpackcompose)

> **AnyFile X** (formerly Open Bridge / AnyFile Opener) is a high-performance, intent-driven Android file routing utility. It serves as the "Universal Translator" for the Android filesystem, ensuring that every file has a home and every intent finds its destination.

---

## 🚀 The Modernization Journey

Project Gemini represents a complete architectural overhaul and rebranding of the application.

### 💎 Rebranding & Identity

- **Namespace Migration**: Fully transitioned to the streamlined `com.anyfile.x` package namespace across all components, activities, and providers.
- **Visual Refresh**: A Material 3 design system (`AnyFileOpenerTheme`) around a midnight `#1F1E33` palette, with System default, Light and Dark modes applied app-wide through `AppCompatDelegate` (`AnyFileApp`), so XML themes, dialogs and Compose agree.
- **Identity Unification**: Consolidated all launcher and settings flows under a unified Compose-driven experience, while preserving `MainActivity` as a high-speed, transparent intent gatekeeper.
- **Repository**: Maintained in the [FaQxD233 fork](https://github.com/FaQxD233/AnyFile-Opener.git).

### 🛠 Architecture & Tech Stack

- **Core Engine**: Binary-level MIME detection (`MimeDetector`) with confidence, evidence, ZIP central-directory and OLE2 directory inspection, text encoding detection, and script heuristics.
- **UI Framework**: **Jetpack Compose** throughout. Screens (`LauncherScreen`, `SettingsScreen`) and bottom sheets (`OpenAsBottomSheet`, `DetailsBottomSheet`) share components such as `FileSummary`, `FileActions` and `FileNameText`; the sheets keep a `BottomSheetDialogFragment` shell (`ComposeBottomSheet`) for the queue's fragment results, with state in fragment-scoped ViewModels.
- **Background Work**: `FileTasks` runs copies (save a copy, prepare a renamed copy, bridge a file:// source) in the process scope; `FileTaskService`, a dataSync foreground service, keeps the process alive and shows progress with a cancel action.
- **Intent Routing**: Advanced URI permission bridging (`IntentRouter`) that solves the Android "Access Denied" bottleneck for cross-app file sharing from restricted cache folders.
- **Build Infrastructure**: A manually triggered GitHub Actions workflow builds and verifies APKs, with optional release-signing secrets and a debug fallback.

---

## ✨ Key Features

### 🔍 Smart Detection (`MimeDetector`)
Unlike standard file managers, AnyFile X inspects the **Magic Bytes** of every file to identify its true nature, even when misnamed or lacking an extension.

### 🛡 Permission Bridging (`IntentRouter` & `MainActivity`)
Automatically handles the complex `FLAG_GRANT_READ_URI_PERMISSION` propagation, ensuring that target applications (media players, document viewers, package installers) have explicit permission to read the file URI.

### 📊 File Details (`DetailsBottomSheet`)
- **Location**: A file-system path when one can be derived (file://, storage and download documents, the legacy `_data` column, see `FileLocation`), otherwise the URI, with a copy button.
- **Metadata**: Detected type with confidence and evidence, size and modification time.
- **Checksums & APK Info**: MD5/SHA-256 in one pass; package, version, SDK levels and signing certificate fingerprints for APKs, with certificates replaced through key rotation listed separately.

### 🚀 Opening Shortcuts
- **`Open with <app>`**: The app last picked in the chooser for the exact type, or for the category the "Open as" grid uses, opens the file directly when it can still handle it.
- **Share Action**: Forward the file to another app (including file managers like MT) via the system share sheet.

### 🗂 Recent Files & Storage Shortcuts
- **Recent File History**: DataStore-backed storage (`RecentFileStore`) tracking recently analyzed files for quick re-opening.
- **Home Screen Widget**: A custom Android app widget (`FolderWidgetProvider`) whose tap starts `FolderShortcutActivity`, which opens the system file manager at internal storage.
- **Multi-file Queue**: `ACTION_SEND_MULTIPLE` shares are processed sequentially with open, skip, and cancel controls; `FileQueue` decides what follows each sheet and is unit tested.
- **Extension Fixes**: Content-based results that contradict the file extension are flagged; files can be saved or forwarded under the corrected name through a FileProvider copy (`FileBridge`).

---

## 🛡️ Technical Safeguards & Stability

To ensure long-term reliability and prevent the "messed up" states typical of legacy intent-handling apps, AnyFile X implements several architectural "locks":

### 🧵 Lifecycle-Safe I/O
- **De-coupled Execution**: Heavy file analysis runs on `Dispatchers.IO`, including the readability check of recent files.
- **Screen Independence**: Copies run in `FileTasks`, not in a screen's scope, so closing a sheet no longer cancels a save. A renamed copy waits until its screen is visible before the app chooser opens, and is abandoned when that screen closes for good.

### 🧩 Intent Integrity
- **Transparent Bridge**: The `MainActivity` acts as a dedicated intent gatekeeper. It does not hold UI state, which ensures that complex URI data is passed cleanly to the Compose layer without being dropped during configuration changes (like screen rotation).
- **URI Permission Forwarding**: Read access is attached to the launched intent and its `ClipData`; unrelated chooser candidates are not pre-authorized.

### 🏗️ Build Hardening
- **Signature Security**: Release signing is enabled only when all credentials and the actual keystore file are available.

### 🔒 Signature Locking (Detection Engine)
- **Bounded Container Verification**: ZIP inspection reads the central directory without inflating entries; non-seekable sources walk local headers with entry and skip limits before falling back to header/extension evidence.
- **Time Limits**: Metadata queries stop after 3 s and content detection after 6 s (`FileInspector`), falling back to the file name.
- **Buffer Integrity**: Detection uses a fixed 33KB header buffer.
- **Short Signatures**: Two- and four-byte signatures need their header structure too (BMP DIB header size, sfnt table directory), so text starting with "BM" or an Access database is not a high-confidence image or font.

---

## 📈 Current Status

- [x] **Namespace Migration** (`com.anyfile.x`)
- [x] **Material 3 Implementation** (`AnyFileOpenerTheme`, System/Light/Dark, `#1F1E33` palette)
- [x] **Compose UI** (screens and fragment-hosted Compose bottom sheets)
- [x] **Background File Tasks** (`FileTasks`, `FileTaskService` progress notification)
- [x] **File Details** (path, size, modification time, checksums, APK info)
- [x] **Share Action** (Hand files to file managers / other apps via ACTION_SEND)
- [x] **Production Signing Configuration** (Graceful `keystore.properties` support)
- [x] **Repository Relocation** (Synchronized at `AnyFile-Opener`)

---

## 🗺 Roadmap (Gemini Next)

- [x] **Batch Processing**: Sequential handling of shared files with queue controls.
- [x] **Extension Fixes**: Mismatch warnings, Save copy, and corrected-name forwarding.
- [ ] **Custom Detection Rules**: User-definable magic-byte and extension-to-MIME overrides.
- [x] **Share Action**: Hand files to file managers / other apps via the system share sheet.
- [x] **Checksums & APK Details**: MD5/SHA-256 and APK details in the details sheet (the hex, ASCII and text previews were removed).

---

*Generated by Antigravity | Project Gemini 2026*
