# Inbox Sync Attachments — Phase 1: SB1 Container Writer

**Goal:** Implement the binary container format writer that wraps existing SB1-encoded strokes with page-level metadata.

**Architecture:** New `Sb1ContainerWriter` utility object in the `io/` package. Uses `ByteBuffer` with little-endian byte order (matching existing SB1 conventions in `StrokePointConverter.kt`). Calls existing `encodeStrokePoints()` verbatim for per-stroke data. Includes background enum mapping and content bounds computation.

**Tech Stack:** Kotlin, `java.nio.ByteBuffer`, existing `StrokePointConverter.encodeStrokePoints()`, JUnit 4

**Scope:** Phase 1 of 4 from original design

**Codebase verified:** 2026-03-15

---

## Acceptance Criteria Coverage

This phase implements and tests:

### inbox-sync-attachments.AC3: SB1 container has valid binary structure
- **inbox-sync-attachments.AC3.1 Success:** Container starts with magic bytes `AC`, version 1, flags `0x00000000`
- **inbox-sync-attachments.AC3.2 Success:** Page metadata contains viewport dimensions, content bounds, background enum, and createdAt timestamp
- **inbox-sync-attachments.AC3.3 Success:** Each stroke entry has correct metaSize, stroke metadata (pen, size, color, maxPressure), and verbatim SB1-encoded point data
- **inbox-sync-attachments.AC3.4 Success:** strokeCount matches actual number of stroke entries
- **inbox-sync-attachments.AC3.5 Edge:** Container with zero strokes has strokeCount=0 and no stroke entries after the metadata block

---

## Reference Files

The executor should read these files for context:
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt` — Existing SB1 binary encoding conventions (magic bytes, ByteBuffer patterns, LZ4)
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/data/db/Stroke.kt` — Stroke and StrokePoint data classes, Pen enum import
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/data/db/Page.kt` — Page data class with background field
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/data/db/Image.kt` — Image data class (x, y, width, height)
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/ExportEngine.kt` lines 445-462 — `computeContentDimensions()` reference implementation
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/MainActivity.kt` lines 58-59 — `SCREEN_WIDTH`/`SCREEN_HEIGHT` globals
- `/home/jtd/development/notable/CLAUDE.md` — Project coding conventions
- `/home/jtd/development/notable/app/src/androidTest/java/com/ethran/notable/db/EncodingTest.kt` — Existing binary encoding test patterns

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: Create Sb1ContainerWriter.kt

**Verifies:** inbox-sync-attachments.AC3.1, inbox-sync-attachments.AC3.2, inbox-sync-attachments.AC3.3, inbox-sync-attachments.AC3.4, inbox-sync-attachments.AC3.5

**Files:**
- Create: `app/src/main/java/com/ethran/notable/io/Sb1ContainerWriter.kt`

**Implementation:**

Create a new Kotlin `object` in the `io/` package: `Sb1ContainerWriter`. This utility contains:

1. **Background enum mapping** — Function `backgroundToEnum(background: String): Byte` that maps the `Page.background` string to a 1-byte enum index:
   - `"blank"` → `0`
   - `"dotted"` → `1`
   - `"lined"` → `2`
   - `"squared"` → `3`
   - `"hexed"` → `4`
   - `"inbox"` → `5`
   - Any other value → `0` (default to blank)

2. **Content bounds data class** — `data class ContentBounds(val width: Int, val height: Int)` returned by `computeContentBounds()`.

3. **`computeContentBounds(strokes: List<Stroke>, images: List<Image>, viewportWidth: Int, viewportHeight: Int): ContentBounds`** — Mirrors the logic in `ExportEngine.computeContentDimensions()`:
   - If no strokes and no images, return `ContentBounds(viewportWidth, viewportHeight)`
   - Compute max right/bottom from stroke bounds (`stroke.right`, `stroke.bottom`) and image bounds (`image.x + image.width`, `image.y + image.height`)
   - Add 50px padding to both width and height (note: ExportEngine conditionally omits height padding when `visualizePdfPagination` is true, but the container always adds it since PDF pagination is irrelevant here), apply `coerceAtLeast(viewportWidth)` / `coerceAtLeast(viewportHeight)`
   - Return `ContentBounds(width, height)`

4. **`writeSb1Container(file: File, page: Page, strokes: List<Stroke>, images: List<Image>, viewportWidth: Int, viewportHeight: Int)`** — Writes the AC01 container format:

   **Header (7 bytes):**
   - Magic: `'A'.code.toByte()`, `'C'.code.toByte()` (2 bytes)
   - Version: `1.toByte()` (1 byte)
   - Flags: `0x00000000` (4 bytes, Int, little-endian)

   **Page metadata (25 bytes):**
   - `viewportWidth` (4 bytes Int)
   - `viewportHeight` (4 bytes Int)
   - `contentBounds.width` (4 bytes Int) — from `computeContentBounds()`
   - `contentBounds.height` (4 bytes Int)
   - `backgroundToEnum(page.background)` (1 byte)
   - `page.createdAt.time` (8 bytes Long) — epoch millis

   **Stroke data:**
   - `strokes.size` (4 bytes Int) — strokeCount
   - For each stroke:
     - `metaSize` (2 bytes UShort) — fixed value 11 (1 + 4 + 4 + 2 = pen + size + color + maxPressure). Note: ByteBuffer has no `putUShort()` — write UShort values using `putShort(value.toShort())`. The 2-byte representation is identical regardless of signedness.
     - Stroke metadata:
       - `stroke.pen.ordinal.toByte()` (1 byte)
       - `stroke.size` (4 bytes Float)
       - `stroke.color` (4 bytes Int)
       - `stroke.maxPressure.toUShort()` (2 bytes) — write as Short
     - Encode stroke points: `val pointData = encodeStrokePoints(stroke.points)`
     - `pointData.size` (4 bytes Int) — dataSize
     - `pointData` (raw bytes) — verbatim SB1-encoded point data

   **ByteBuffer approach:** Allocate a ByteBuffer, set to `ByteOrder.LITTLE_ENDIAN`, write all fields, then write the backing array to the file via `FileOutputStream`. For variable-length stroke data, either:
   - Pre-compute total size (7 + 25 + 4 + per-stroke overhead), OR
   - Use a `ByteArrayOutputStream` with a `DataOutputStream` wrapper, OR
   - Use `FileOutputStream` + `BufferedOutputStream` and write the ByteBuffer for the fixed header, then iterate strokes writing each one

   The simplest approach: use a `ByteArrayOutputStream`, wrap the fixed-size header in a small ByteBuffer, write it, then iterate strokes appending to the output stream. Finally write the whole byte array to the file.

   **Import `encodeStrokePoints` from:** `com.ethran.notable.data.db.StrokePointConverter.encodeStrokePoints` — note this function is in a companion or top-level scope within StrokePointConverter.kt. Check the actual declaration to get the import right.

**Verification:**
Run: `./gradlew assembleDebug`
Expected: Build succeeds with new file compiled

**Commit:** `feat: add SB1 container writer with AC01 binary format`

<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Unit tests for Sb1ContainerWriter

**Verifies:** inbox-sync-attachments.AC3.1, inbox-sync-attachments.AC3.2, inbox-sync-attachments.AC3.3, inbox-sync-attachments.AC3.4, inbox-sync-attachments.AC3.5

**Files:**
- Create: `app/src/test/java/com/ethran/notable/io/Sb1ContainerWriterTest.kt`

**Testing:**

Unit tests (JVM, not instrumented) since `Sb1ContainerWriter` uses only `java.nio.ByteBuffer`, `java.io.File`, and existing codec code — no Android framework dependencies.

Tests must verify each AC listed above:

- **inbox-sync-attachments.AC3.1:** Write a container, read back first 7 bytes. Assert magic = `0x41, 0x43` ('A', 'C'), version = `1`, flags = `0x00000000` (4 bytes little-endian).

- **inbox-sync-attachments.AC3.2:** Write a container with known page metadata (specific viewport dimensions, a non-blank background, known createdAt timestamp). Read back the 25-byte metadata block starting at offset 7. Assert each field matches: viewportWidth, viewportHeight, contentWidth, contentHeight, background enum byte, createdAt epoch millis.

- **inbox-sync-attachments.AC3.3:** Write a container with 1 stroke having known pen, size, color, maxPressure, and a small set of known points. Read back the stroke entry. Assert metaSize = 11, pen byte matches `Pen.BALLPEN.ordinal`, size/color/maxPressure match, and the trailing point data matches the output of `encodeStrokePoints()` called directly on the same points.

- **inbox-sync-attachments.AC3.4:** Write a container with N strokes (e.g., 3). Read back strokeCount at offset 36 (7 header + 25 metadata + 4 strokeCount). Assert it equals 3. Walk through all stroke entries to verify exactly 3 exist.

- **inbox-sync-attachments.AC3.5:** Write a container with an empty stroke list. Assert strokeCount = 0 and total file size = 7 (header) + 25 (metadata) + 4 (strokeCount) = 36 bytes.

**Test helpers needed:**
- Create test `Stroke` instances with known fields (construct `Stroke(...)` directly with test data)
- Create test `Page` instances with known background and createdAt
- Read the output file bytes and wrap in a little-endian `ByteBuffer` for assertions
- Use a temporary file (`@Rule TemporaryFolder` or `File.createTempFile()`)

**Additional test for `computeContentBounds()`:**
- Empty strokes + empty images → returns viewport dimensions
- Strokes with known bounds → returns max(strokeRight + 50, viewportWidth) and max(strokeBottom, viewportHeight)

**Additional test for `backgroundToEnum()`:**
- Each known string maps correctly
- Unknown string maps to 0

**Verification:**
Run: `./gradlew test`
Expected: All unit tests pass

**Commit:** `test: add unit tests for SB1 container writer`

<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->
