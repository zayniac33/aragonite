# Test Requirements: inbox-sync-attachments

## Automated Tests

### AC3.1: Container starts with magic bytes AC, version 1, flags 0x00000000
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Write a container, read back first 7 bytes. Assert bytes 0-1 are 0x41, 0x43 ('A', 'C'), byte 2 is 1, bytes 3-6 are 0x00000000 (little-endian Int).

### AC3.2: Page metadata contains viewport dimensions, content bounds, background enum, and createdAt timestamp
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Write a container with known viewport (e.g. 1404x1872), a non-blank background ("lined"), and a known createdAt timestamp. Read back the 25-byte metadata block at offset 7. Assert viewportWidth, viewportHeight, contentWidth, contentHeight, background enum byte (2 for "lined"), and createdAt epoch millis all match inputs.

### AC3.3: Each stroke entry has correct metaSize, stroke metadata, and verbatim SB1-encoded point data
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Write a container with one stroke having known pen, size, color, maxPressure, and a small set of known points. Read back the stroke entry after the strokeCount field. Assert metaSize is 11, pen byte matches expected Pen ordinal, size/color/maxPressure match, dataSize matches length of independently-called encodeStrokePoints() output, and trailing point data bytes are identical.

### AC3.4: strokeCount matches actual number of stroke entries
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Write a container with 3 strokes. Read strokeCount Int at offset 36 (7 + 25 + 4). Assert it equals 3. Walk through all stroke entries using metaSize/dataSize to verify exactly 3 entries exist and consume all remaining bytes.

### AC3.5: Container with zero strokes has strokeCount=0 and no stroke entries
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Write a container with an empty stroke list. Assert strokeCount is 0 and total file size is exactly 36 bytes (7 header + 25 metadata + 4 strokeCount).

### AC3 (supplementary): computeContentBounds() correctness
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** (a) Empty strokes + empty images returns viewport dimensions as-is. (b) Strokes with known bounds return max(strokeRight + 50, viewportWidth) for width and max(strokeBottom + 50, viewportHeight) for height.

### AC3 (supplementary): backgroundToEnum() mapping
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`
- **What to verify:** Each known background string ("blank"->0, "dotted"->1, "lined"->2, "squared"->3, "hexed"->4, "inbox"->5) maps to the correct byte. Unknown strings default to 0.

### AC2.1: Frontmatter contains created date, tags, pages: 1, source: aragonite
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Call generateMarkdown("2025-03-15", listOf("meeting-notes"), "Hello world"). Parse output and assert frontmatter contains `created: "[[2025-03-15]]"`, `tags:` with `- meeting-notes`, `pages: 1`, `source: aragonite`.

### AC2.2: Body contains HWR text followed by separator and page image embed
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Call generateMarkdown() with known content. Assert the output contains the content text, followed by a `---` horizontal rule, followed by `![[page-1.jpg]]`.

### AC2.3: Annotations are wrapped as wiki links or tags in HWR text (existing behavior preserved)
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Call generateMarkdown() with content containing `[[wiki link]]` and `#tag`. Assert both pass through unchanged in the output body.

### AC2.4: Page with no tags produces frontmatter without tags field
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Call generateMarkdown("2025-03-15", emptyList(), "text"). Assert the output frontmatter does NOT contain a `tags:` line.

### AC2 (supplementary): Multi-page embed future-proofing
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Call generateMarkdown(..., pages = 3). Assert the output contains `![[page-1.jpg]]`, `![[page-2.jpg]]`, and `![[page-3.jpg]]` in order.

### AC5.1: Page files use page-1 naming convention
- **Type:** unit
- **File:** `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`
- **What to verify:** Assert generateMarkdown() output contains `page-1.jpg` (not `page-0.jpg` or unnumbered). Also verified structurally in AC3 tests where the container is written to a `page-1.sb1` file.

## Human Verification

### AC1.1: Syncing a page with strokes creates folder containing {timestamp}.md, page-1.jpg, and page-1.sb1
- **Why not automated:** End-to-end sync requires a running app with AppRepository, ExportEngine (Hilt-injected), Room database, and file system access to the Obsidian vault path. No integration test harness exists that can stand up this full stack.
- **Verification approach:** Install debug APK on device. Open inbox page, draw strokes, tap sync. Navigate to the Obsidian inbox path on the device file system (via adb shell or file manager). Verify the per-note folder exists with all three files named correctly.

### AC1.2: page-1.jpg contains rendered strokes, images, and background at 85% JPEG quality
- **Why not automated:** Bitmap rendering depends on ExportEngine which requires Android Canvas, Bitmap, and the full drawing pipeline. Cannot run on JVM. Visual correctness (strokes rendered, background visible) requires human inspection.
- **Verification approach:** After syncing an inbox page with strokes on a known background (e.g. lined), open the generated page-1.jpg. Verify strokes are visible, background pattern is rendered, and image is not blank or corrupted. JPEG quality can be spot-checked by file size relative to page dimensions.

### AC1.3: If JPG rendering fails, markdown is still written (graceful degradation)
- **Why not automated:** No mocking framework available. Simulating ExportEngine failure requires either a mock or temporary code modification. The try-catch logic is straightforward but the failure path cannot be triggered in normal test conditions.
- **Verification approach:** Temporarily insert `throw RuntimeException("test")` at the start of writePageJpg(). Build and install. Sync an inbox page. Verify: (1) the .md file is written in the per-note folder, (2) page-1.jpg is absent, (3) logcat shows the error message. Remove the temporary throw afterward.

### AC1.4: If SB1 writing fails, markdown is still written
- **Why not automated:** Same as AC1.3 — no mocking framework. The SB1 write is wrapped in an independent try-catch from JPG and markdown.
- **Verification approach:** Temporarily insert `throw RuntimeException("test")` at the start of Sb1ContainerWriter.writeSb1Container(). Build and install. Sync an inbox page. Verify: (1) the .md file is written, (2) page-1.sb1 is absent, (3) logcat shows the error message. Remove the temporary throw afterward.

### AC1.5: Page with no strokes produces markdown with empty HWR text, JPG of blank background, and SB1 with zero strokes
- **Why not automated:** The zero-strokes SB1 container IS unit-tested (AC3.5). The markdown with empty text IS unit-testable. However, the JPG of a blank background requires ExportEngine rendering on a real device, and the end-to-end flow (all three files for a blank page) requires the full app stack.
- **Verification approach:** Open the inbox page without drawing anything. Tap sync. Verify: (1) .md file has empty body between frontmatter and image embed, (2) page-1.jpg shows only the background pattern, (3) page-1.sb1 is exactly 36 bytes (verified via `adb shell stat` or `ls -l`).

### AC4.1: ExportEngine is passed from EditorView through SyncState to InboxSyncEngine without null or missing reference
- **Why not automated:** This is a compile-time guarantee (non-nullable Kotlin parameter) plus runtime wiring through Hilt DI. The compile check is verified by `./gradlew assembleDebug` succeeding. Runtime correctness requires the full Hilt injection graph on a running device.
- **Verification approach:** (1) Confirm `./gradlew assembleDebug` succeeds (compile-time proof of non-null threading). (2) On device, sync an inbox page and verify page-1.jpg is produced (proves ExportEngine was non-null and functional at runtime).

### AC4.2: Sync runs on IO dispatcher, does not block main thread
- **Why not automated:** Verifying dispatcher usage requires either a threading test harness or runtime profiling. The existing code already launches on Dispatchers.IO in SyncState.launchSync() — this is a code-review verification.
- **Verification approach:** (1) Code review: confirm SyncState.launchSync() uses `Dispatchers.IO` for the coroutine scope. (2) On device: sync a page with many strokes and verify the UI remains responsive during sync (no ANR, toolbar still responds to taps).
