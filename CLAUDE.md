# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fork of [Ethran/notable](https://github.com/Ethran/notable) — an alternative note-taking app for Onyx Boox e-ink devices. Written in Kotlin with Jetpack Compose. Uses the Onyx Pen SDK for low-latency stylus input.

## Build & Test Commands

```bash
./gradlew assembleDebug                    # Build debug APK
./gradlew installDebug                     # Build + install to connected Boox device
./gradlew test                             # Run unit tests
./gradlew assembleDebug --stacktrace       # Build with full error output
./gradlew clean                            # Clean build
```

Deploy and launch in one shot:
```bash
./gradlew installDebug && adb shell am start -n com.ethran.notable/.MainActivity
```

Useful ADB commands:
```bash
adb devices                                           # Verify device connected
adb logcat --pid=$(adb shell pidof com.ethran.notable) # App logs
adb logcat -d | grep -A 20 "FATAL EXCEPTION"          # Crash stacktrace
adb shell am force-stop com.ethran.notable             # Force stop
adb uninstall com.ethran.notable                       # Uninstall
```

### Release signing

Uses env vars (`STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Switching between debug and release signing requires uninstalling first, which wipes app data.

### Build output
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## Architecture

### Data Flow

```
UI Composables → EditorControlTower → PageView → PageDataManager (memory cache) → AppRepository → Room DAOs → SQLite
```

### Key Components

**EditorControlTower** (`editor/EditorControlTower.kt`) — Central orchestrator for all editor operations: page switching, scroll/zoom (with `Mutex` for thread safety during renders), tool toggling, selection operations, and undo/redo delegation. Uses `CoroutineScope` for async operations.

**CanvasEventBus** (`editor/canvas/CanvasEventBus.kt`) — Singleton event system using Kotlin SharedFlows/StateFlows for loosely-coupled component communication. Key flows: `forceUpdate` (screen refresh), `commitHistorySignal` (pre-undo commits), `changePage`, `drawingInProgress` (Mutex lock).

**PageDataManager** (`data/PageDataManager.kt`) — Singleton multi-level memory cache. Caches strokes per page, stroke-by-ID lookups, page bitmaps (SoftReference), and background images with FileObserver. Has job-based loading to prevent duplicates, LRU eviction, and neighbor page pre-caching.

**DrawCanvas** (`editor/canvas/DrawCanvas.kt`) — SurfaceView-based canvas with OpenGL rendering. Routes stylus events through OnyxInputHandler for low-latency pen input.

**OnyxInputHandler** (`editor/canvas/OnyxInputHandler.kt`) — Integrates Onyx Pen SDK (`TouchHelper` + `RawInputCallback`). Dispatches to mode-specific handlers (draw, erase, select, annotate). Only works on Onyx hardware; falls back to normal touch events on other devices.

### Undo/Redo System (`editor/state/history.kt`)

Two stacks of `OperationBlock` (max 5 blocks each). Operations are fully reversible — undo stores the inverse. Supported operations: AddStroke, DeleteStroke, AddImage, DeleteImage, AddAnnotation, DeleteAnnotation. Commits pending drawing before undo, then redraws only the affected `Rect` region.

### Stroke Encoding (SB1 format)

Custom binary format in `data/db/Stroke.kt`: 9-byte header (magic, version, mask, count, compression), polyline-encoded coordinates, optional per-point data (pressure, tilt, dt). LZ4 compression when raw size ≥512 bytes and saves ≥25%.

### Navigation (`navigation/`)

Jetpack Compose Navigation with **no transitions** (e-ink displays cannot animate). Routes: Library, Welcome, Editor, Pages, Settings, SystemInfo, BugReport. Uses SavedStateHandle for process-death survival. QuickNav for fast page preview.

### State Management

**EditorState** — Drawing mode (Draw/Erase/Select/Line), annotation mode, toolbar visibility, pen settings.
**SelectionState** — Selected strokes/images, cutting lines, placement mode (Move/Paste), selection bitmap.

## Key Technical Constraints

### E-ink
- **No animations or transitions** — every frame causes ghosting
- Use `EpdController` for refresh mode control (`einkHelper.kt`)
- Batch UI updates; minimize recompositions
- High contrast only, no gradients

### Compose on E-ink
- Use `remember`/`derivedStateOf` to limit recomposition
- Keep Composables stateless
- Recomposition is expensive — avoid unnecessary state changes

### Onyx Pen SDK
- SDK deps: `onyxsdk-pen:1.5.1`, `onyxsdk-device:1.3.2`, `onyxsdk-base:1.8.3`
- Maven repo: `http://repo.boox.com/repository/maven-public/` (insecure HTTP, required — `allowInsecureProtocol = true`)
- Pen input only works on Onyx hardware — cannot test in emulator

### Threading
- Never block the main thread or OpenGL render thread with I/O; use coroutines
- Prefer `Flow` over LiveData for new reactive code

## Coding Conventions

- **Idiomatic Kotlin**: prefer `val`, data classes, scope functions, extension functions, sealed classes for state
- **Avoid `!!`**: use `?.`, `?:`, or explicit checks instead of force unwrap
- **Immutability**: update state via `copy()`, not direct mutation
- **MVVM + Hilt**: ViewModels use `@HiltViewModel`; never pass `Context` into a ViewModel
- **Room migrations**: any `@Entity` change requires a DB version bump, a migration in `Migrations.kt`, a schema snapshot in `app/schemas/`
- **Package placement**: follow `docs/file-structure.md`; never add code to `floatingEditor/` (unused/historical)

## Important Properties

- `applicationId`: `com.ethran.notable`
- `minSdk`: 29, `targetSdk`: 35, `compileSdk`: 36
- `JVM target`: 17
- `Gradle`: 9.1.0, `Kotlin`: 2.3.10, `AGP`: 9.0.0
- `IS_NEXT` must exist in `gradle.properties` (set to `false` for normal builds)

## Testing

Tests are primarily instrumented (run on device/emulator):
- `app/src/androidTest/.../db/MigrationTest.kt` — Room migration validation
- `app/src/androidTest/.../db/EncodingTest.kt` — Stroke encoding round-trip tests

Unit tests are minimal. The emulator can test UI layout/navigation but not pen/stylus features or e-ink refresh behavior.

## Documentation

- `docs/file-structure.md` — Where to add new code
- `docs/database-structure.md` — Data model and stroke encoding spec
- `docs/import-formats.md`, `docs/export-formats.md` — I/O capabilities

## CI

- **preview.yml**: Builds debug APK on push to main, uploads as "next" prerelease
- **release.yml**: Triggered by version tags, builds signed APK, creates GitHub release