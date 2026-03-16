# Inbox Sync Attachments — Phase 3: Updated Markdown Generation + Folder Structure

**Goal:** Update markdown output to include new frontmatter fields (`pages`, `source`) and page image embed, and switch from flat file to per-note folder layout.

**Architecture:** Modify two existing functions in `InboxSyncEngine`: `generateMarkdown()` gains `pages` and `source` fields in YAML frontmatter plus an appended `![[page-N.jpg]]` embed section; `writeMarkdownFile()` creates a per-note subfolder `{timestamp}/` and returns the folder `File` so Phase 4 can write sibling files into it. Both functions are pure or near-pure (string generation + file I/O), so changes are testable with JVM unit tests.

**Tech Stack:** Kotlin, JUnit 4, `java.io.File`

**Scope:** Phase 3 of 4 from original design

**Codebase verified:** 2026-03-15

---

## Acceptance Criteria Coverage

This phase implements and tests:

### inbox-sync-attachments.AC1: Sync produces per-note folder with all three files
- **inbox-sync-attachments.AC1.1 Success:** Syncing a page with strokes creates folder containing `{timestamp}.md`, `page-1.jpg`, and `page-1.sb1`

### inbox-sync-attachments.AC2: Markdown has correct frontmatter and image embed
- **inbox-sync-attachments.AC2.1 Success:** Frontmatter contains created date, tags, `pages: 1`, `source: aragonite`
- **inbox-sync-attachments.AC2.2 Success:** Body contains HWR text followed by `---` separator and `![[page-1.jpg]]` embed
- **inbox-sync-attachments.AC2.3 Success:** Annotations are wrapped as `[[wiki links]]` or `#tags` in the HWR text (existing behavior preserved)
- **inbox-sync-attachments.AC2.4 Edge:** Page with no tags produces frontmatter without tags field

---

## Reference Files

The executor should read these files for context:
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — Target file. Key areas: lines 263-279 (generateMarkdown), lines 281-295 (writeMarkdownFile), lines 135-139 (call sites in syncInboxPage)
- `/home/jtd/development/notable/CLAUDE.md` — Project coding conventions

---

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->

<!-- START_TASK_1 -->
### Task 1: Update generateMarkdown() with new frontmatter and image embed

**Verifies:** inbox-sync-attachments.AC2.1, inbox-sync-attachments.AC2.2, inbox-sync-attachments.AC2.4

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` lines 263-279

**Implementation:**

Modify `generateMarkdown()` to add two new parameters and an image embed section.

**Current signature** (line 263):
```kotlin
private fun generateMarkdown(
    createdDate: String,
    tags: List<String>,
    content: String
): String
```

**New signature:**
```kotlin
private fun generateMarkdown(
    createdDate: String,
    tags: List<String>,
    content: String,
    pages: Int = 1,
    source: String = "aragonite"
): String
```

**Changes to the function body:**

1. After the tags block (before the closing `---`), add:
   ```kotlin
   sb.appendLine("pages: $pages")
   sb.appendLine("source: $source")
   ```

2. After appending `content.trim()`, add the image embed section:
   ```kotlin
   sb.appendLine()
   sb.appendLine("---")
   for (i in 1..pages) {
       sb.appendLine("![[page-$i.jpg]]")
   }
   ```

**Expected output format:**
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

Note: AC2.3 (annotations wrapped as wikilinks/tags) is already handled by existing annotation processing code in `syncInboxPage()` — no changes needed here.

Note: AC2.4 (no tags = no tags field) is already handled by the existing `if (tags.isNotEmpty())` guard — no changes needed.

**Verification:**
Run: `./gradlew assembleDebug`
Expected: Build succeeds

**Commit:** `feat: add pages, source to markdown frontmatter and image embed`

<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Update writeMarkdownFile() to create per-note folder

**Verifies:** inbox-sync-attachments.AC1.1 (folder structure)

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` lines 281-295

**Implementation:**

Split `writeMarkdownFile()` into two functions: a folder resolution helper and a markdown writer that takes the pre-created folder. This lets Phase 4 create the folder early, write attachments, then write markdown last.

**Current signature** (line 281):
```kotlin
private fun writeMarkdownFile(markdown: String, createdAt: Date, inboxPath: String)
```

**Add new helper function** `resolveNoteDir()`:
```kotlin
internal fun resolveNoteDir(createdAt: Date, inboxPath: String): File {
    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(createdAt)

    val baseDir = if (inboxPath.startsWith("/")) {
        File(inboxPath)
    } else {
        File(Environment.getExternalStorageDirectory(), inboxPath)
    }

    val noteDir = File(baseDir, timestamp)
    noteDir.mkdirs()
    return noteDir
}
```

**Update `writeMarkdownFile()` to accept `noteDir` instead of resolving paths itself:**
```kotlin
private fun writeMarkdownFile(markdown: String, createdAt: Date, noteDir: File) {
    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(createdAt)
    val fileName = "$timestamp.md"
    val file = File(noteDir, fileName)
    file.writeText(markdown)
    log.i("Written inbox note to ${file.absolutePath}")
}
```

Key changes:
- `resolveNoteDir()` is `internal` so Phase 4 can call it from `syncInboxPage()` to create the folder early
- `writeMarkdownFile()` no longer resolves paths — it writes to the pre-created `noteDir`
- Phase 4 will call `resolveNoteDir()` first, write attachments to the folder, then call `writeMarkdownFile()` last

The file layout becomes:
```
{obsidianInboxPath}/{timestamp}/{timestamp}.md
{obsidianInboxPath}/{timestamp}/page-1.jpg    (written in Phase 4)
{obsidianInboxPath}/{timestamp}/page-1.sb1    (written in Phase 4)
```

**Verification:**
Run: `./gradlew assembleDebug`
Expected: Build succeeds. The call site in `syncInboxPage()` (line 139) needs updating to pass `noteDir` — Phase 4 handles this.

**Commit:** `feat: switch to per-note folder layout for inbox sync`

<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Unit tests for updated generateMarkdown()

**Verifies:** inbox-sync-attachments.AC2.1, inbox-sync-attachments.AC2.2, inbox-sync-attachments.AC2.4

**Files:**
- Create: `app/src/test/java/com/ethran/notable/io/InboxSyncEngineMarkdownTest.kt`

**Testing:**

Unit tests (JVM) for `generateMarkdown()`. Change `generateMarkdown()` from `private` to `internal` visibility (permanent change). It's a pure function with no side effects — exposing it within the module is safe and follows the pattern used for `renderBitmapForPage()` in Phase 2. This allows direct testing from unit tests.

Tests must verify each AC listed above:

- **inbox-sync-attachments.AC2.1:** Call `generateMarkdown("2025-03-15", listOf("meeting-notes"), "Hello world")`. Parse the YAML frontmatter. Assert it contains `created: "[[2025-03-15]]"`, `tags:` with `- meeting-notes`, `pages: 1`, `source: aragonite`.

- **inbox-sync-attachments.AC2.2:** Call `generateMarkdown(...)` with known content. Assert the output ends with `\n---\n![[page-1.jpg]]\n` (horizontal rule separator followed by image embed).

- **inbox-sync-attachments.AC2.4:** Call `generateMarkdown("2025-03-15", emptyList(), "text")`. Assert the frontmatter does NOT contain a `tags:` line.

- **Multi-page embed (future-proofing):** Call `generateMarkdown(..., pages = 3)`. Assert the output contains `![[page-1.jpg]]`, `![[page-2.jpg]]`, `![[page-3.jpg]]`.

- **Existing behavior preserved (AC2.3):** Test that content with `[[wiki links]]` and `#tags` passes through unchanged — the function doesn't strip or modify annotation wrappers.

**Verification:**
Run: `./gradlew test`
Expected: All unit tests pass

**Commit:** `test: add unit tests for updated markdown generation`

<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_A -->
