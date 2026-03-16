# Human Test Plan: Inbox Sync Attachments

## Prerequisites
- Boox device connected via USB with ADB authorized (`adb devices` shows device)
- Debug APK built and installed: `./gradlew installDebug`
- `./gradlew test` passing (21 tests, 0 failures)
- Obsidian inbox path configured in app settings (e.g. `Documents/Obsidian/vault/inbox`)
- At least one inbox page exists in the app

## Phase 1: Basic Sync Output (AC1.1)

| Step | Action | Expected |
|------|--------|----------|
| 1 | Open the Notable app, navigate to an inbox page | Inbox page loads with toolbar visible |
| 2 | Draw several strokes using the stylus (at least 3 distinct words) | Strokes appear on the page |
| 3 | Tap the sync button in the toolbar | Sync initiates without UI freeze |
| 4 | On host machine, run: `adb shell ls /sdcard/Documents/Obsidian/vault/inbox/` | A folder named `YYYY-MM-DD-HH-MM-SS` (matching page creation time) is listed |
| 5 | Run: `adb shell ls /sdcard/Documents/Obsidian/vault/inbox/<folder>/` | Folder contains exactly 3 files: `<timestamp>.md`, `page-1.jpg`, `page-1.sb1` |

## Phase 2: JPG Quality and Correctness (AC1.2)

| Step | Action | Expected |
|------|--------|----------|
| 1 | Create an inbox page with "lined" background, draw several strokes and place one image | Content visible on page |
| 2 | Sync the page | Sync completes |
| 3 | Pull the JPG: `adb pull /sdcard/Documents/Obsidian/vault/inbox/<folder>/page-1.jpg /tmp/` | File transfers successfully |
| 4 | Open `/tmp/page-1.jpg` on host machine | Strokes are visible, lined background pattern is rendered, placed image appears. Image is not blank or corrupted |
| 5 | Check file size: `ls -la /tmp/page-1.jpg` | File size is reasonable for 85% JPEG quality at device resolution (expect 200KB-2MB range for a page with content) |

## Phase 3: SB1 Container On-Device Validation (AC3 integration)

| Step | Action | Expected |
|------|--------|----------|
| 1 | After syncing a page with strokes from Phase 1 or 2, run: `adb shell stat /sdcard/Documents/Obsidian/vault/inbox/<folder>/page-1.sb1` | File exists, size > 36 bytes (has strokes) |
| 2 | Pull and inspect first bytes: `adb pull .../page-1.sb1 /tmp/ && xxd -l 7 /tmp/page-1.sb1` | First 7 bytes: `4143 0100 0000 00` (AC 01 00000000) |

## Phase 4: Graceful Degradation - JPG Failure (AC1.3)

| Step | Action | Expected |
|------|--------|----------|
| 1 | In `InboxSyncEngine.kt`, add `throw RuntimeException("test JPG failure")` as the first line inside `writePageJpg()` | Code modified |
| 2 | Build and install: `./gradlew installDebug` | APK installs |
| 3 | Open an inbox page, draw strokes, tap sync | Sync completes (no crash) |
| 4 | Run: `adb shell ls /sdcard/Documents/Obsidian/vault/inbox/<folder>/` | `<timestamp>.md` exists, `page-1.sb1` exists, `page-1.jpg` is ABSENT |
| 5 | Check logcat: `adb logcat -d \| grep "Failed to render page JPG"` | Log line present with the RuntimeException message |
| 6 | Revert the temporary throw | Code restored to original |

## Phase 5: Graceful Degradation - SB1 Failure (AC1.4)

| Step | Action | Expected |
|------|--------|----------|
| 1 | In `InboxSyncEngine.kt`, add `throw RuntimeException("test SB1 failure")` before the `Sb1ContainerWriter.writeSb1Container()` call (inside the try block) | Code modified |
| 2 | Build and install: `./gradlew installDebug` | APK installs |
| 3 | Open an inbox page, draw strokes, tap sync | Sync completes (no crash) |
| 4 | Run: `adb shell ls /sdcard/Documents/Obsidian/vault/inbox/<folder>/` | `<timestamp>.md` exists, `page-1.jpg` exists, `page-1.sb1` is ABSENT |
| 5 | Check logcat: `adb logcat -d \| grep "Failed to write SB1 container"` | Log line present with the RuntimeException message |
| 6 | Revert the temporary throw | Code restored to original |

## Phase 6: Empty Page Sync (AC1.5)

| Step | Action | Expected |
|------|--------|----------|
| 1 | Create a new inbox page with "dotted" background. Do NOT draw any strokes. Add at least one tag via the UI. | Empty page with tag selected |
| 2 | Tap sync | Sync completes |
| 3 | Pull the markdown file: `adb pull .../inbox/<folder>/<timestamp>.md /tmp/` | File transfers |
| 4 | Open the .md file | Frontmatter has `created:`, `tags:`, `pages: 1`, `source: aragonite`. Body between frontmatter and `---` separator is empty or whitespace only |
| 5 | Open page-1.jpg | Shows only the dotted background pattern, no strokes |
| 6 | Check SB1 size: `adb shell stat .../page-1.sb1` | File size is exactly 36 bytes |

## Phase 7: ExportEngine Wiring (AC4.1)

| Step | Action | Expected |
|------|--------|----------|
| 1 | Run `./gradlew assembleDebug` | Build succeeds (compile-time proof that ExportEngine parameter is non-null throughout the call chain) |
| 2 | On device, sync any inbox page with strokes | `page-1.jpg` is produced in the output folder (proves ExportEngine was injected and functional at runtime) |

## Phase 8: IO Dispatcher / No Main Thread Blocking (AC4.2)

| Step | Action | Expected |
|------|--------|----------|
| 1 | Open an inbox page, draw many strokes (fill the page densely) | Page has heavy content |
| 2 | Tap sync and immediately try tapping toolbar buttons, scrolling, or switching tools | UI remains responsive during sync. No ANR dialog appears. Toolbar taps register without delay |
| 3 | Verify in code: open `SyncState.kt` and confirm the sync coroutine launches on `Dispatchers.IO` | Dispatcher is IO, not Main or Default |

## End-to-End: Full Inbox Workflow

1. Launch Notable on device. Navigate to inbox.
2. Set background to "lined" via page settings.
3. Draw 2-3 lines of handwritten text with the stylus.
4. Draw an annotation box around one word, set type to "wikilink".
5. Draw another annotation box around a different word, set type to "tag".
6. Select tags "meeting-notes" and "project-alpha" from the tag picker.
7. Tap sync.
8. On host: `adb shell ls -la /sdcard/Documents/Obsidian/vault/inbox/<folder>/`
9. Verify: folder contains `<timestamp>.md`, `page-1.jpg`, `page-1.sb1`.
10. Pull and open the .md file. Verify: frontmatter has correct date, both tags listed, `pages: 1`, `source: aragonite`. Body contains recognized text with `[[...]]` around the wikilink word and `#...` before the tag word. Ends with `---` then `![[page-1.jpg]]`.
11. Open page-1.jpg. Verify: strokes visible on lined background, all content rendered.
12. Run `xxd -l 7 page-1.sb1` to confirm AC header bytes.

## Traceability

| Acceptance Criterion | Automated Test | Manual Step |
|----------------------|----------------|-------------|
| AC1.1: Folder with .md, .jpg, .sb1 | -- | Phase 1, steps 4-5 |
| AC1.2: JPG visual correctness | -- | Phase 2, steps 3-5 |
| AC1.3: JPG failure degradation | -- | Phase 4 |
| AC1.4: SB1 failure degradation | -- | Phase 5 |
| AC1.5: Empty page sync | -- | Phase 6 |
| AC2.1: Frontmatter structure | `testGenerateMarkdownFrontmatterWithTags` | End-to-End step 10 |
| AC2.2: Body + separator + embed | `testGenerateMarkdownImageEmbed` | End-to-End step 10 |
| AC2.3: Wiki links / tags preserved | `testGenerateMarkdownPreservesAnnotations` | End-to-End step 10 |
| AC2.4: No tags omits tags field | `testGenerateMarkdownFrontmatterNoTags` | -- |
| AC3.1: Magic bytes, version, flags | `testMagicBytesVersionAndFlags` | Phase 3 step 2 |
| AC3.2: Page metadata | `testPageMetadata` | Phase 3 step 1 |
| AC3.3: Stroke entry format | `testStrokeMetadataAndPointData` | Phase 3 step 2 |
| AC3.4: strokeCount correctness | `testStrokeCountWithMultipleStrokes` | Phase 3 step 1 |
| AC3.5: Zero strokes | `testEmptyStrokeList` | Phase 6 step 6 |
| AC4.1: ExportEngine wiring | -- | Phase 7 |
| AC4.2: IO dispatcher | -- | Phase 8 |
| AC5.1: page-1 naming | `testGenerateMarkdownImageEmbed` | Phase 1 step 5 |
