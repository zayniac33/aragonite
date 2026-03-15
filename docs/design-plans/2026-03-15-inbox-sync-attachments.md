# Inbox Sync Attachments Design

## Summary

Inbox sync currently exports handwritten notes as a single flat markdown file. This change upgrades each sync to produce a **per-note folder** containing three artifacts: the markdown note (`.md`), a rendered JPEG of the page (`.jpg`), and a new binary SB1 container (`.sb1`) that bundles the raw stroke data alongside page-level metadata. The goal is to make synced notes self-contained and machine-readable — the JPEG gives Obsidian an embeddable preview, and the SB1 container preserves full stroke fidelity for future re-import or processing.

The implementation threads `ExportEngine` (which already knows how to render a page to a `Bitmap`) down through `SyncState` into `InboxSyncEngine`, which then handles compression, binary serialization, and file layout. The SB1 container is a new format ("AC01" — Aragonite Container) that wraps the app's existing per-stroke binary encoding with a fixed-size page metadata header. All four work items (container writer, JPG rendering, markdown updates, wiring) are independent enough to be implemented in parallel phases.

## Definition of Done

1. When a user syncs an inbox page, Aragonite creates a per-note folder containing: markdown (`.md`), rendered page image (`.jpg` at 85% quality), and SB1 stroke container (`.sb1`).
2. The markdown has YAML frontmatter (`created`, `tags`, `pages`, `source: aragonite`), HWR-recognized text body, and an embedded page image via `![[page-1.jpg]]` at the bottom.
3. The SB1 container wraps existing per-stroke SB1 data with an extensible page metadata header storing viewport dimensions and content bounds.
4. Export only — no import path yet.
5. Naming convention supports future multi-page (`page-1`, `page-2`, etc.) even though only single pages are handled now.

## Acceptance Criteria

### inbox-sync-attachments.AC1: Sync produces per-note folder with all three files
- **inbox-sync-attachments.AC1.1 Success:** Syncing a page with strokes creates folder containing `{timestamp}.md`, `page-1.jpg`, and `page-1.sb1`
- **inbox-sync-attachments.AC1.2 Success:** `page-1.jpg` contains rendered strokes, images, and background at 85% JPEG quality
- **inbox-sync-attachments.AC1.3 Failure:** If JPG rendering fails (e.g. OOM), markdown is still written (graceful degradation)
- **inbox-sync-attachments.AC1.4 Failure:** If SB1 writing fails, markdown is still written
- **inbox-sync-attachments.AC1.5 Edge:** Page with no strokes produces markdown with empty HWR text, JPG of blank background, and SB1 with zero strokes

### inbox-sync-attachments.AC2: Markdown has correct frontmatter and image embed
- **inbox-sync-attachments.AC2.1 Success:** Frontmatter contains created date, tags, `pages: 1`, `source: aragonite`
- **inbox-sync-attachments.AC2.2 Success:** Body contains HWR text followed by `---` separator and `![[page-1.jpg]]` embed
- **inbox-sync-attachments.AC2.3 Success:** Annotations are wrapped as `[[wiki links]]` or `#tags` in the HWR text (existing behavior preserved)
- **inbox-sync-attachments.AC2.4 Edge:** Page with no tags produces frontmatter without tags field

### inbox-sync-attachments.AC3: SB1 container has valid binary structure
- **inbox-sync-attachments.AC3.1 Success:** Container starts with magic bytes `AC`, version 1, flags `0x00000000`
- **inbox-sync-attachments.AC3.2 Success:** Page metadata contains viewport dimensions, content bounds, background enum, and createdAt timestamp
- **inbox-sync-attachments.AC3.3 Success:** Each stroke entry has correct metaSize, stroke metadata (pen, size, color, maxPressure), and verbatim SB1-encoded point data
- **inbox-sync-attachments.AC3.4 Success:** strokeCount matches actual number of stroke entries
- **inbox-sync-attachments.AC3.5 Edge:** Container with zero strokes has strokeCount=0 and no stroke entries after the metadata block

### inbox-sync-attachments.AC4: ExportEngine threading works end-to-end
- **inbox-sync-attachments.AC4.1 Success:** ExportEngine is passed from EditorView through SyncState to InboxSyncEngine without null or missing reference
- **inbox-sync-attachments.AC4.2 Success:** Sync runs on IO dispatcher, does not block main thread

### inbox-sync-attachments.AC5: File naming supports future multi-page
- **inbox-sync-attachments.AC5.1 Success:** Page files use `page-1` naming convention (not `page-0` or unnamed)

## Glossary

- **Inbox page**: A special single-page note in the app used as a quick-capture surface. When synced, it is exported to Obsidian and then cleared.
- **Inbox sync / `syncInboxPage()`**: The operation that reads an inbox page's strokes and annotations, runs HWR, and writes output files to the configured Obsidian vault path.
- **SB1**: The app's custom binary format for encoding stylus stroke point data. Stores coordinates using polyline delta encoding, with optional per-point pressure/tilt and LZ4 compression for large strokes.
- **SB1 container (AC01)**: A new file format introduced by this change. Wraps one or more SB1-encoded strokes with a page-level metadata header (viewport size, content bounds, background type, creation timestamp). Magic bytes `AC` stand for "Aragonite Container."
- **ExportEngine**: A Hilt-injected class that renders pages to `Bitmap`, `PDF`, `PNG`, or `JPEG`. This design re-uses its `renderBitmapForPage()` method to produce the page preview image during sync.
- **InboxSyncEngine**: A Kotlin `object` singleton responsible for the full inbox sync pipeline: fetching page data, running HWR, processing annotations, generating markdown, and writing output files.
- **SyncState**: A class that owns the lifecycle of a running sync operation. Acts as the bridge between the UI layer (`EditorView`) and `InboxSyncEngine`. This change adds `exportEngine` as a parameter it threads through.
- **HWR (Handwriting Recognition)**: Converting stylus strokes to text. Handled here by `OnyxHWREngine`, which calls into Onyx's on-device `ksync` recognition service via AIDL.
- **OnyxHWREngine**: Wrapper around Onyx's undocumented handwriting recognition service. Binds to the `ksync` system process, submits strokes, and returns recognized text.
- **Annotation / `AnnotationType`**: A rectangular region drawn on the page to mark a span of handwriting as either a `WIKILINK` or a `TAG`. During sync, the text within the region is wrapped as `[[...]]` or `#...` in the markdown output.
- **Wikilink embed (`![[page-1.jpg]]`)**: Obsidian's syntax for embedding a file (image, PDF, etc.) inline in a note. The `!` prefix renders the image rather than showing a link.
- **YAML frontmatter**: A block of structured metadata at the top of a markdown file, delimited by `---`. Obsidian and tools like Dataview read these fields to enable queries (e.g. "show all notes where `source: aragonite`").
- **Dataview**: An Obsidian community plugin that lets users query notes like a database using frontmatter fields. The `source: aragonite` field is added specifically to support Dataview queries.
- **`obsidianInboxPath`**: A user-configured setting that holds the path to the inbox folder inside the Obsidian vault, either absolute or relative to external storage.
- **LZ4**: A fast lossless compression algorithm used by the existing SB1 encoder when raw point data exceeds 512 bytes and compression saves at least 25%.
- **Hilt**: Android's dependency injection framework (built on Dagger). `ExportEngine` is Hilt-injected into `EditorView`; `InboxSyncEngine` is a plain `object` that receives it as a function parameter instead.
- **`internal` visibility**: A Kotlin visibility modifier meaning "visible within the same Gradle module." Phase 2 relaxes several `private` methods in `ExportEngine` to `internal` so `InboxSyncEngine` (in the same module) can call them.

## Architecture

Thread `ExportEngine` through the existing call chain to give `InboxSyncEngine` rendering capability:

```
EditorView (already has exportEngine)
  → SyncState.launchSync(..., exportEngine)
    → InboxSyncEngine.syncInboxPage(..., exportEngine)
```

`InboxSyncEngine` uses `ExportEngine.renderBitmapForPage()` to produce a `Bitmap`, compresses it to JPG, and writes the file. A new `writeSb1Container()` function handles the binary container format. The existing per-stroke SB1 codec (`encodeStrokePoints()` in `StrokePointConverter.kt`) is used verbatim — the container just wraps it with page-level metadata.

### SB1 Container Binary Format

```
MAGIC          2 bytes    'A' 'C' (0x41 0x43 — "Aragonite Container")
VERSION        1 byte     1
FLAGS          4 bytes    Reserved. Bit 0: has annotations. Bit 1: multi-page.

PAGE METADATA (fixed-size block):
  viewportWidth   4 bytes Int     SCREEN_WIDTH at export time
  viewportHeight  4 bytes Int     SCREEN_HEIGHT at export time
  contentWidth    4 bytes Int     Computed from stroke/image bounding boxes
  contentHeight   4 bytes Int     Computed from stroke/image bounding boxes
  background      1 byte          Enum index (0=blank 1=dotted 2=lined 3=squared 4=hexed 5=inbox)
  createdAt       8 bytes Long    Page creation timestamp (epoch millis)

STROKE DATA:
  strokeCount     4 bytes Int
  Per stroke:
    metaSize      2 bytes UShort  Size of stroke metadata block
    strokeMeta:
      pen         1 byte          Pen enum index
      size        4 bytes Float
      color       4 bytes Int
      maxPressure 2 bytes UShort
    dataSize      4 bytes Int     Size of SB1-encoded point data
    data          [dataSize]      Existing encodeStrokePoints() output, untouched
```

Byte order: little-endian throughout (matches existing SB1 convention).

### File Layout

```
{obsidianInboxPath}/
  {timestamp}/
    {timestamp}.md
    page-1.jpg
    page-1.sb1
```

Timestamp format: `yyyy-MM-dd-HH-mm-ss` (matches current `writeMarkdownFile()` behavior). Page files use `page-N` naming to support future multi-page export.

### Markdown Format

```markdown
---
created: "[[2025-03-15]]"
tags:
  - meeting-notes
pages: 1
source: aragonite
---

HWR recognized text with [[wiki links]] and #tags...

---
![[page-1.jpg]]
```

New frontmatter fields: `pages` (integer, always 1 for now) and `source: aragonite` (enables Dataview queries). The page image is embedded using Obsidian wikilink syntax for automatic path resolution.

### Updated syncInboxPage() Flow

1. Fetch page + strokes + annotations *(unchanged)*
2. HWR recognition + annotation processing *(unchanged)*
3. Create per-note folder `{inboxPath}/{timestamp}/`
4. Render bitmap via `exportEngine.renderBitmapForPage(pageId)`, write as `page-1.jpg` at 85% quality, recycle bitmap
5. Write SB1 container `page-1.sb1` with metadata header + length-prefixed strokes
6. Generate markdown with updated frontmatter and `![[page-1.jpg]]` embed
7. Write markdown to `{timestamp}.md` inside the per-note folder *(markdown written last so its appearance signals sync completion to any Obsidian file watcher)*

## Existing Patterns

Investigation found these patterns already in use:

- **Parameter threading for singletons:** `InboxSyncEngine` is an `object` singleton that receives `AppRepository` and `Context` as function parameters rather than using DI. Adding `ExportEngine` follows this same pattern.
- **ExportEngine bitmap rendering:** `renderBitmapForPage()` already handles background drawing, image compositing, and stroke rendering via `drawBg()`, `drawImage()`, and `drawStroke()`. Content dimensions computed from stroke/image bounding boxes with `SCREEN_WIDTH`/`SCREEN_HEIGHT` as minimum.
- **SB1 binary encoding:** `StrokePointConverter.kt` uses `ByteBuffer` with little-endian byte order, magic bytes, version field, mask bits, and optional LZ4 compression. The container format follows the same conventions (magic, version, flags, ByteBuffer).
- **File writing in InboxSyncEngine:** `writeMarkdownFile()` resolves `obsidianInboxPath` as either absolute or relative to `Environment.getExternalStorageDirectory()`, creates directories with `mkdirs()`, and writes with `File.writeText()`. New file writes follow this same resolution logic.

No divergence from existing patterns.

## Implementation Phases

<!-- START_PHASE_1 -->
### Phase 1: SB1 Container Writer

**Goal:** Implement the binary container format writer and content bounds computation.

**Components:**
- `Sb1ContainerWriter` utility in `app/src/main/java/com/ethran/notable/io/Sb1ContainerWriter.kt` — writes the AC01 container format to a `File`, given a `Page`, `List<Stroke>`, and viewport dimensions
- `computeContentBounds()` helper in the same file — computes content width/height from stroke and image bounding boxes (same logic as `ExportEngine.computeContentDimensions()` but returns a data class)
- Background enum mapping — maps `Page.background` string to the 1-byte enum index defined in the container spec

**Dependencies:** None (first phase)

**Covers:** `inbox-sync-attachments.AC3`

**Done when:** `writeSb1Container()` produces a valid binary file with correct magic bytes, version, flags, page metadata, and length-prefixed SB1 stroke data. Unit tests verify header structure, stroke count, and round-trip of individual stroke data within the container.
<!-- END_PHASE_1 -->

<!-- START_PHASE_2 -->
### Phase 2: ExportEngine Visibility + JPG Rendering

**Goal:** Make page bitmap rendering accessible from InboxSyncEngine and add JPG compression.

**Components:**
- `ExportEngine.renderBitmapForPage()` in `app/src/main/java/com/ethran/notable/io/ExportEngine.kt` — change visibility from `private` to `internal`
- `ExportEngine.fetchPageData()` — change visibility from `private` to `internal` (needed by renderBitmapForPage)
- `ExportEngine.computeContentDimensions()` — change visibility from `private` to `internal` (needed by renderBitmapForPage)
- JPG write helper in `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — renders bitmap, compresses to JPEG at 85%, writes to file, recycles bitmap

**Dependencies:** None (independent of Phase 1)

**Covers:** `inbox-sync-attachments.AC1.2`

**Done when:** Given a page ID and an ExportEngine instance, a JPG file is written with the rendered page content. Bitmap is recycled after write.
<!-- END_PHASE_2 -->

<!-- START_PHASE_3 -->
### Phase 3: Updated Markdown Generation + Folder Structure

**Goal:** Update markdown output to include new frontmatter fields and image embed, and switch to per-note folder layout.

**Components:**
- `generateMarkdown()` in `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — add `pages` and `source` parameters, append horizontal rule and `![[page-N.jpg]]` embed section
- `writeMarkdownFile()` in `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — create per-note subfolder `{timestamp}/` under inbox path, write `.md` inside it
- Return the created folder `File` from `writeMarkdownFile()` so other phases can write sibling files

**Dependencies:** None (independent of Phases 1-2)

**Covers:** `inbox-sync-attachments.AC1.1`, `inbox-sync-attachments.AC2`

**Done when:** Markdown files are written inside per-note folders with correct frontmatter (created, tags, pages, source) and `![[page-1.jpg]]` embed. Unit tests verify frontmatter structure and embed syntax.
<!-- END_PHASE_3 -->

<!-- START_PHASE_4 -->
### Phase 4: Integration

**Goal:** Wire everything together in the sync flow.

**Components:**
- `SyncState.launchSync()` in `app/src/main/java/com/ethran/notable/io/SyncState.kt` — add `exportEngine` parameter, pass through to InboxSyncEngine
- `InboxSyncEngine.syncInboxPage()` in `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — add `exportEngine` parameter, add JPG render step (Phase 2), SB1 write step (Phase 1), use updated markdown generation (Phase 3)
- `EditorView` call site in `app/src/main/java/com/ethran/notable/editor/EditorView.kt` — pass existing `exportEngine` to `SyncState.launchSync()`

**Dependencies:** Phases 1, 2, 3

**Covers:** `inbox-sync-attachments.AC1`, `inbox-sync-attachments.AC4`, `inbox-sync-attachments.AC5`

**Done when:** Full sync flow produces a per-note folder with `.md`, `.jpg`, and `.sb1` files. Manual verification on device confirms all three files are written correctly. Markdown is written last.
<!-- END_PHASE_4 -->

## Additional Considerations

**Error handling:** If JPG rendering or SB1 writing fails, the sync should still produce the markdown file (graceful degradation). Log the error and continue — a note without attachments is better than no note at all.

**Memory:** `renderBitmapForPage()` allocates a full-page bitmap. On devices with limited RAM (Palma 2 Pro), this is the most likely failure point. The bitmap is recycled immediately after JPEG compression to minimize memory pressure.

**Future extensibility:** The SB1 container's FLAGS and VERSION fields enable adding annotation data (flag bit 0) and multi-page support (flag bit 1) without breaking existing parsers. A future reader that sees an unknown flag can skip the unknown section using the length-prefixed structure.
