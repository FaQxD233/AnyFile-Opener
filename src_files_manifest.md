# 📂 AnyFile X - Source Files Manifest & Architectural Guide

This document lists the files under `app/src`, with their role in the app.

---

## 🗺️ Source Directory Structure

```
app/src
├── main/
│   ├── AndroidManifest.xml
│   ├── java/com/anyfile/x/
│   │   ├── AnyFileApp.kt, LauncherActivity.kt, MainActivity.kt, SettingsActivity.kt
│   │   ├── data/       PrefsManager, RecentFile, RecentFileStore
│   │   ├── engine/     MimeDetector, MimeTypes, NameCheck, FileInspector, ZipInspector,
│   │   │               Ole2Inspector, ContainerAccess, TextEncoding, utils/
│   │   ├── feature/
│   │   │   ├── common/    shared Compose components and helpers
│   │   │   ├── details/   details sheet
│   │   │   ├── launcher/  home screen
│   │   │   ├── openas/    "Open as" sheet
│   │   │   └── settings/  settings screen
│   │   ├── files/      ApkInfoReader, FileBridge, FileExporter, FileHasher, FileLocation, StreamPump
│   │   ├── routing/    IntentRouter, FileQueue, ChooserRequestStore, ChooserResultReceiver, SystemFileManager
│   │   ├── tasks/      FileTasks, FileTaskService
│   │   ├── ui/         Theme, ThemePreference
│   │   └── widget/     FolderWidgetProvider, FolderShortcutActivity
│   └── res/
│       ├── drawable/   launcher icon, widget background and icon, notification icon
│       ├── layout/     widget_folder_shortcut.xml
│       ├── values/, values-night/
│       └── xml/        backup rules, FileProvider paths, widget info
└── test/java/com/anyfile/x/   engine, hashing, timeout and queue tests
```

---

## ⚡ Core Source Files (`app/src/main/java/com/anyfile/x/`)

### Entry points

| File | Role |
| :--- | :--- |
| [`AnyFileApp.kt`](app/src/main/java/com/anyfile/x/AnyFileApp.kt) | Applies the light/dark choice before any screen starts and prunes expired cached copies. |
| [`MainActivity.kt`](app/src/main/java/com/anyfile/x/MainActivity.kt) | Transparent handler for `ACTION_VIEW`, `ACTION_SEND` and `ACTION_SEND_MULTIPLE`; shows the queued files one sheet at a time. |
| [`LauncherActivity.kt`](app/src/main/java/com/anyfile/x/LauncherActivity.kt) | Hosts the home screen and opens its sheets. |
| [`SettingsActivity.kt`](app/src/main/java/com/anyfile/x/SettingsActivity.kt) | Hosts the settings screen. |

### Detection engine (`engine/`)

| File | Role |
| :--- | :--- |
| [`MimeDetector.kt`](app/src/main/java/com/anyfile/x/engine/MimeDetector.kt) | Magic-byte detection from a 33 KB header, with confidence, source and evidence; text and script heuristics. |
| [`ZipInspector.kt`](app/src/main/java/com/anyfile/x/engine/ZipInspector.kt) | Identifies APK, split-APK bundles, Office Open XML, EPUB/OpenDocument and JAR from ZIP entry names without inflating data. |
| [`Ole2Inspector.kt`](app/src/main/java/com/anyfile/x/engine/Ole2Inspector.kt) | Identifies legacy Office files from OLE2 directory entries. |
| [`ContainerAccess.kt`](app/src/main/java/com/anyfile/x/engine/ContainerAccess.kt) | Seekable or streaming access to a whole file for container checks. |
| [`FileInspector.kt`](app/src/main/java/com/anyfile/x/engine/FileInspector.kt) | Metadata and detection under time limits, falling back to the file name. |
| [`MimeTypes.kt`](app/src/main/java/com/anyfile/x/engine/MimeTypes.kt) | MIME constants, validation and extension knowledge. |
| [`NameCheck.kt`](app/src/main/java/com/anyfile/x/engine/NameCheck.kt) | Flags extensions that do not fit the content and proposes a corrected name. |
| [`TextEncoding.kt`](app/src/main/java/com/anyfile/x/engine/TextEncoding.kt) | Text encoding detection (BOM, UTF-16, UTF-8, GB18030) used by the text heuristics. |
| `utils/` | Byte reading and searching, little-endian reads, and URI metadata (name, size, modification time, legacy path). |

### Screens and sheets (`feature/`)

| File | Role |
| :--- | :--- |
| [`launcher/LauncherScreen.kt`](app/src/main/java/com/anyfile/x/feature/launcher/LauncherScreen.kt), [`LauncherViewModel.kt`](app/src/main/java/com/anyfile/x/feature/launcher/LauncherViewModel.kt) | Home screen: pick a file, see the detection, act on it, manual MIME override, recent files. |
| [`openas/OpenAsBottomSheet.kt`](app/src/main/java/com/anyfile/x/feature/openas/OpenAsBottomSheet.kt), [`OpenAsContent.kt`](app/src/main/java/com/anyfile/x/feature/openas/OpenAsContent.kt), [`OpenAsViewModel.kt`](app/src/main/java/com/anyfile/x/feature/openas/OpenAsViewModel.kt) | One queued file: summary, actions, "Open as type" grid, skip and cancel; reports its outcome to `MainActivity`. |
| [`details/DetailsBottomSheet.kt`](app/src/main/java/com/anyfile/x/feature/details/DetailsBottomSheet.kt), [`DetailsContent.kt`](app/src/main/java/com/anyfile/x/feature/details/DetailsContent.kt), [`DetailsViewModel.kt`](app/src/main/java/com/anyfile/x/feature/details/DetailsViewModel.kt) | Name, type, path or URI, size, modification time, checksums and APK details. |
| [`settings/SettingsScreen.kt`](app/src/main/java/com/anyfile/x/feature/settings/SettingsScreen.kt) | Auto-open, fallback MIME, theme, help and about. |
| [`common/ComposeBottomSheet.kt`](app/src/main/java/com/anyfile/x/feature/common/ComposeBottomSheet.kt) | Bottom sheet fragment with themed Compose content, opened expanded, not closable while busy. |
| [`common/FileSummary.kt`](app/src/main/java/com/anyfile/x/feature/common/FileSummary.kt), [`FileActions.kt`](app/src/main/java/com/anyfile/x/feature/common/FileActions.kt), [`FileNameText.kt`](app/src/main/java/com/anyfile/x/feature/common/FileNameText.kt), [`TaskRows.kt`](app/src/main/java/com/anyfile/x/feature/common/TaskRows.kt) | Shared components: file summary and type icons, action buttons with `Open with <app>`, names that keep their extension, task progress rows. |
| [`common/SaveCopy.kt`](app/src/main/java/com/anyfile/x/feature/common/SaveCopy.kt), [`RenamedOpener.kt`](app/src/main/java/com/anyfile/x/feature/common/RenamedOpener.kt) | Save a copy, and open under the corrected name, both through background tasks. |
| [`common/InspectionArgs.kt`](app/src/main/java/com/anyfile/x/feature/common/InspectionArgs.kt), `Formatters.kt`, `SaveCopyContract.kt` | Passing a detection to a sheet, size formatting, the create-document contract. |

### Files, routing and tasks

| File | Role |
| :--- | :--- |
| [`routing/IntentRouter.kt`](app/src/main/java/com/anyfile/x/routing/IntentRouter.kt) | Chooser and direct opening with URI grants, sharing, and the app last picked per type. |
| [`routing/FileQueue.kt`](app/src/main/java/com/anyfile/x/routing/FileQueue.kt) | The incoming file queue and what follows each sheet outcome. |
| `routing/ChooserRequestStore.kt`, `ChooserResultReceiver.kt` | Tell a picked app from a dismissed chooser; record the picked app and recent files. |
| [`routing/SystemFileManager.kt`](app/src/main/java/com/anyfile/x/routing/SystemFileManager.kt) | Opens DocumentsUI at internal storage. |
| [`tasks/FileTasks.kt`](app/src/main/java/com/anyfile/x/tasks/FileTasks.kt), [`FileTaskService.kt`](app/src/main/java/com/anyfile/x/tasks/FileTaskService.kt) | Background copies with progress, cancel and results; the foreground service with the progress notification. |
| `files/FileBridge.kt`, `FileExporter.kt`, `StreamPump.kt` | Cached copies served by FileProvider, writing to a created document, the shared read loop. |
| `files/FileHasher.kt`, `ApkInfoReader.kt`, `FileLocation.kt` | MD5/SHA-256, APK package details, readable path of a URI. |
| `data/PrefsManager.kt`, `RecentFileStore.kt`, `RecentFile.kt` | Settings in SharedPreferences, recent files in DataStore. |
| `ui/Theme.kt`, `ThemePreference.kt` | Color schemes and the System/Light/Dark choice. |
| `widget/FolderWidgetProvider.kt`, `FolderShortcutActivity.kt` | Home screen widget and the activity it starts. |

---

## 🛡️ Manifest Configuration (`app/src/main/AndroidManifest.xml`)

- **Intent handling**: `MainActivity` handles `ACTION_VIEW` for content and file URIs, plus `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, all for `*/*`.
- **Package visibility**: `ACTION_VIEW` queries with a MIME type, so typed viewers are visible when listing handlers.
- **Background copies**: `FileTaskService` is a `dataSync` foreground service; `POST_NOTIFICATIONS` shows its progress on Android 13+.
- **Storage**: SAF and temporary URI grants only; copies are served by a non-exported `FileProvider`.
- **Backups**: Recent files and chooser state are excluded (`data_extraction_rules.xml`, `backup_rules.xml`).

---

## 🧪 Test Suite (`app/src/test/java/com/anyfile/x/`)

| File | Covers |
| :--- | :--- |
| `MimeDetectorTest.kt` | Signatures, header structure of short signatures, text heuristics, OLE2, HEIF brands. |
| `ZipInspectorTest.kt` | APK, bundles, OpenXML, EPUB/OpenDocument, JAR, central directory and local-header walks. |
| `NameCheckTest.kt` | Mismatch detection and corrected names. |
| `TextEncodingTest.kt` | Encoding detection. |
| `FileHasherTest.kt`, `FileInspectorTest.kt` | Digests and progress; time limits. |
| `FileQueueTest.kt` | Queue order and the next step after each outcome. |
