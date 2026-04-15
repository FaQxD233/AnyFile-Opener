# AnyFile Opener / OpenBridge

AnyFile Opener (a.k.a. OpenBridge) is a compact Android utility that registers as a universal `ACTION_VIEW` handler and lets you open any file with a forced MIME type. It detects file type via magic-byte inspection, offers a launcher UI to pick files, and safely re-dispatches the URI to other apps while forwarding URI permissions so media players and installers can read restricted storage locations.

**Highlights**
- Magic-byte based MIME detection (not just extension) for better file-type accuracy.
- Robust URI permission forwarding so apps like MX Player and VLC can open files from restricted locations.
- Launcher UI with "Force Override" MIME field and quick chips for common types.
- Binary inspector that shows a hex dump and ASCII preview of the first bytes of a file.

**Status**: Build succeeded locally; development is active.

**APK (debug)**: `app/build/outputs/apk/debug/app-debug.apk`

**Table of Contents**
- **Overview**
- **Why this app exists**
- **Architecture & Key Files**
- **Build & Run**
- **Usage**
- **Developer Notes & Gotchas**
- **Adding signatures**
- **Contributing**
- **License**

**Overview**
AnyFile Opener acts as a routing layer for files. When you choose "Open with → AnyFile Opener" from a file picker, OpenBridge will:
- Read the first N bytes of the file via `ContentResolver`.
- Match magic-byte signatures to infer a specific MIME (e.g., `video/mp4`, `image/png`).
- Re-fire an `ACTION_VIEW` chooser with the inferred MIME and carefully forward the URI grant to candidate apps (using ClipData + `grantUriPermission`).

This approach fixes the common problem where forwarded URIs are rejected by player apps when files are located in restricted folders (Telegram download cache, Android/data, etc.).

**Why this app exists**
- Android's incoming URI grants are scoped to the receiving app. If you take that URI and `startActivity()` for a different app without correctly forwarding permission, the target app cannot open the file.
- Many files (APK, MP4) are mislabelled by extension or by system MIME. Magic-byte detection improves reliability.

**Architecture & Key Files**
- **Launcher UI**: [app/src/main/java/com/openbridge/LauncherActivity.kt](app/src/main/java/com/openbridge/LauncherActivity.kt) — full-featured launcher: pick file, edit MIME, quick chips, open with force, inspect binary.
- **Transparent router**: [app/src/main/java/com/openbridge/MainActivity.kt](app/src/main/java/com/openbridge/MainActivity.kt) — receives `ACTION_VIEW` and immediately shows the bottom-sheet chooser for type selection.
- **Intent router**: [app/src/main/java/com/openbridge/IntentRouter.kt](app/src/main/java/com/openbridge/IntentRouter.kt) — creates `ACTION_VIEW` with proper ClipData and pre-grants URI read permission to candidate handlers.
- **MIME detection**: [app/src/main/java/com/openbridge/MimeDetector.kt](app/src/main/java/com/openbridge/MimeDetector.kt) — magic-byte signatures, `ftyp` parsing for MP4/3GP variants, extension fallback.
- **Byte reader**: [app/src/main/java/com/openbridge/utils/ByteReader.kt](app/src/main/java/com/openbridge/utils/ByteReader.kt) — safely reads first N bytes from a content URI.
- **Bottom sheets / UI**: [app/src/main/res/layout](app/src/main/res/layout) — `bottom_sheet_open_as.xml`, `bottom_sheet_inspect.xml`, and related item layouts.

**Build & Run (local dev)**
- Prerequisites: JDK (11+ recommended), Android SDK with API 34, Gradle wrapper (project includes `gradlew`).

Commands (PowerShell):

```powershell
cd "c:\Users\tapman\Desktop\open with apk"
./gradlew assembleDebug
```

Install the debug APK onto a device/emulator:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

**Usage (user-facing)**
- Open any file in a file manager, gallery, or downloads list.
- Tap "Open with" and select "AnyFile Opener".
- The bottom sheet shows the detected category and a selection grid. Choose one or toggle the MIME via the launcher UI.
- To force a specific MIME: open `AnyFile Opener` from the launcher, select a file, edit the `Target MIME Type` field, then tap `OPEN WITH FORCE`.
- To inspect a file: use `INSPECT BINARY` to view the first bytes (hex + ASCII) and basic metadata.

**Developer Notes & Gotchas**
- Kotlin lexer caveat (important): Kotlin 1.9.22 (and some compiler settings) can mis-interpret the `/*` token inside string literals, breaking compilation if `*/*` appears verbatim in a Kotlin source file. Avoid embedding `*/*` directly in source strings. The codebase uses a runtime string-template trick to assemble wildcard MIME strings (e.g. `val s = "/"; "video${s}*"`) to avoid this lexer issue.

- Manifest package attribute: the project uses `package="com.openbridge"` in the `AndroidManifest.xml` source. Newer Android Gradle Plugin versions recommend setting the namespace in Gradle: if you see a warning about `package="..." found in source AndroidManifest.xml`, consider moving `namespace` to the module's Gradle config.

- Granting URI permissions: `IntentRouter` intentionally pre-grants read permission to candidate handlers to avoid `SecurityException` when target apps try to open restricted URIs.

**Adding signatures (extending MIME detection)**
- `MimeDetector.detectFromHeader()` contains the signature table. To add a new signature:
  - Add a new matcher branch checking bytes or header patterns.
  - Return a `DetectionResult(FileType.XXX, "specific/mime")` when matched.
  - Update `FileType` enum with a suitable category if you want it to appear in the grid UI.

**Testing**
- Manual tests: open a range of media files (mp4, mkv, mp3, jpg, png), archives (zip, apk), and text files from different storage locations (Downloads, Android/data, Telegram folder) and verify target apps open them.
- Edge cases: files without extensions, truncated headers, or streaming URIs. The app falls back to extension heuristics if magic-byte match fails.

**Contributing**
- Fork the repo, make changes on a feature branch, and open a PR describing your change.
- Keep changes focused: add unit tests for new detection rules or UI behavior where feasible.

**License**
This repository contains your project codebase. Include a license file if you want to open-source it (e.g., MIT, Apache-2.0). If you want, I can add a recommended `LICENSE` file.

---

If you want, I can:
- Add a short `README` badge and a usage GIF/screenshot.
- Add a `CONTRIBUTING.md` and `LICENSE` file.
- Expand the detection docs with a concise table of magic signatures.

What would you like next?