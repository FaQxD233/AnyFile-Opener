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
- **Visual Refresh**: Implementation of a modern Material 3 design system (`AnyFileOpenerTheme`) with full support for Dynamic Color (Material You) and pure black AMOLED Dark Mode.
- **Identity Unification**: Consolidated all launcher and settings flows under a unified Compose-driven experience, while preserving `MainActivity` as a high-speed, transparent intent gatekeeper.
- **Repository**: Maintained in the [FaQxD233 fork](https://github.com/FaQxD233/AnyFile-Opener.git).

### 🛠 Architecture & Tech Stack

- **Core Engine**: Binary-level MIME detection (`MimeDetector`) with confidence, evidence, bounded ZIP inspection, and script heuristics.
- **UI Framework**: Modern hybrid architecture combining **Jetpack Compose** (`LauncherScreen`, `SettingsScreen`) with high-performance **ViewBinding** bottom sheets (`InspectBottomSheet`, `OpenAsBottomSheet`) and lifecycle-aware coroutines (`lifecycleScope.launch(Dispatchers.IO)`).
- **Intent Routing**: Advanced URI permission bridging (`IntentRouter`) that solves the Android "Access Denied" bottleneck for cross-app file sharing from restricted cache folders.
- **Build Infrastructure**: A manually triggered GitHub Actions workflow builds and verifies APKs, with optional release-signing secrets and a debug fallback.

---

## ✨ Key Features

### 🔍 Smart Detection (`MimeDetector`)
Unlike standard file managers, AnyFile X inspects the **Magic Bytes** of every file to identify its true nature, even when misnamed or lacking an extension.

### 🛡 Permission Bridging (`IntentRouter` & `MainActivity`)
Automatically handles the complex `FLAG_GRANT_READ_URI_PERMISSION` propagation, ensuring that target applications (media players, document viewers, package installers) have explicit permission to read the file URI.

### 📊 Advanced Binary Inspector (`InspectBottomSheet`)
A deep-dive tool into file metadata and structure:
- **Hex & ASCII Dump**: Real-time paginated viewing (`buildHexDump` & `buildAsciiPreview`) of file headers and raw byte contents.
- **Header Verification**: Compares magic-byte detection against filesystem metadata.
- **Share Action**: Forward the inspected file to another app (including file managers like MT) via the system share sheet.

### 🗂 Recent Files & Storage Shortcuts
- **Recent File History**: DataStore-backed storage (`RecentFileStore`) tracking recently analyzed files for quick re-opening.
- **Home Screen Widget**: A custom Android app widget (`FolderWidgetProvider`) offering one-tap access to system storage roots.
- **Multi-file Queue**: `ACTION_SEND_MULTIPLE` shares are processed sequentially with open, skip, and cancel controls.
- **Default App Rules**: Exact MIME rules take priority over extension rules and can be managed from Settings.

---

## 🛡️ Technical Safeguards & Stability

To ensure long-term reliability and prevent the "messed up" states typical of legacy intent-handling apps, AnyFile X implements several architectural "locks":

### 🧵 Lifecycle-Safe I/O
- **De-coupled Execution**: Heavy file analysis runs on `Dispatchers.IO`.
- **Reference Safety**: Bottom-sheet work uses `viewLifecycleOwner.lifecycleScope`, so closing or recreating a view cancels pending UI updates.

### 🧩 Intent Integrity
- **Transparent Bridge**: The `MainActivity` acts as a dedicated intent gatekeeper. It does not hold UI state, which ensures that complex URI data is passed cleanly to the Compose layer without being dropped during configuration changes (like screen rotation).
- **URI Permission Forwarding**: Read access is attached to the launched intent and its `ClipData`; unrelated chooser candidates are not pre-authorized.

### 🏗️ Build Hardening
- **Signature Security**: Release signing is enabled only when all credentials and the actual keystore file are available.

### 🔒 Signature Locking (Detection Engine)
- **Bounded Container Verification**: ZIP inspection limits entry count, per-entry bytes, and total decompressed bytes before falling back to header/extension evidence.
- **Buffer Integrity**: Initial detection uses a fixed 33KB header buffer; the inspector has a separate 64KB display limit.

---

## 📈 Current Status

- [x] **Namespace Migration** (`com.anyfile.x`)
- [x] **Material 3 Implementation** (`AnyFileOpenerTheme`, Dynamic Color, AMOLED Dark Mode)
- [x] **Hybrid UI Architecture** (Jetpack Compose + ViewBinding BottomSheets)
- [x] **Lifecycle Stability Fixes** (`viewLifecycleOwner`-bound bottom-sheet work)
- [x] **Binary Inspector UI** (Hex & ASCII dumps, pagination, metadata inspection)
- [x] **Share Action** (Hand files to file managers / other apps via ACTION_SEND)
- [x] **Production Signing Configuration** (Graceful `keystore.properties` support)
- [x] **Repository Relocation** (Synchronized at `AnyFile-Opener`)

---

## 🗺 Roadmap (Gemini Next)

- [x] **Batch Processing**: Sequential handling of shared files with queue controls.
- [x] **Default App Rules**: Per-MIME and per-extension target application rules.
- [ ] **Custom Detection Rules**: User-definable magic-byte and extension-to-MIME overrides.
- [x] **Share Action**: Hand files to file managers / other apps via the system share sheet.
- [x] **Hex & ASCII Inspector**: Native lightweight hex/ASCII preview inside the inspection sheet.

---

*Generated by Antigravity | Project Gemini 2026*
