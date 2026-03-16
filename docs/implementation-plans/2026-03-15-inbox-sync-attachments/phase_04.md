# Inbox Sync Attachments — Phase 4: Integration

**Goal:** Wire Phases 1-3 together into the sync flow: thread `ExportEngine` from `EditorView` through `SyncState` to `InboxSyncEngine`, add JPG and SB1 write steps to `syncInboxPage()`, and handle graceful degradation.

**Architecture:** Parameter threading pattern — `ExportEngine` (already available as a Hilt-injected parameter in `EditorView`) is passed through `SyncState.launchSync()` to `InboxSyncEngine.syncInboxPage()`. Inside `syncInboxPage()`, after HWR completes: (1) create per-note folder, (2) render JPG, (3) write SB1 container, (4) generate and write markdown last (so its presence signals completion to Obsidian file watchers). JPG and SB1 writes are wrapped in try-catch for graceful degradation.

**Tech Stack:** Kotlin, Coroutines (Dispatchers.IO), Hilt, ExportEngine, Sb1ContainerWriter

**Scope:** Phase 4 of 4 from original design

**Codebase verified:** 2026-03-15

---

## Acceptance Criteria Coverage

This phase implements and tests:

### inbox-sync-attachments.AC1: Sync produces per-note folder with all three files
- **inbox-sync-attachments.AC1.1 Success:** Syncing a page with strokes creates folder containing `{timestamp}.md`, `page-1.jpg`, and `page-1.sb1`
- **inbox-sync-attachments.AC1.2 Success:** `page-1.jpg` contains rendered strokes, images, and background at 85% JPEG quality
- **inbox-sync-attachments.AC1.3 Failure:** If JPG rendering fails (e.g. OOM), markdown is still written (graceful degradation)
- **inbox-sync-attachments.AC1.4 Failure:** If SB1 writing fails, markdown is still written
- **inbox-sync-attachments.AC1.5 Edge:** Page with no strokes produces markdown with empty HWR text, JPG of blank background, and SB1 with zero strokes

### inbox-sync-attachments.AC4: ExportEngine threading works end-to-end
- **inbox-sync-attachments.AC4.1 Success:** ExportEngine is passed from EditorView through SyncState to InboxSyncEngine without null or missing reference
- **inbox-sync-attachments.AC4.2 Success:** Sync runs on IO dispatcher, does not block main thread

### inbox-sync-attachments.AC5: File naming supports future multi-page
- **inbox-sync-attachments.AC5.1 Success:** Page files use `page-1` naming convention (not `page-0` or unnamed)

---

## Reference Files

The executor should read these files for context:
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/SyncState.kt` — Full file (46 lines). launchSync() at lines 20-45.
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/editor/EditorView.kt` — Composable signature lines 72-81 (exportEngine parameter), SyncState.launchSync() call site at line 244.
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — syncInboxPage() lines 26-142. Stroke fetch at line 34. HWR processing lines 51-131. Markdown generation at lines 133-139.
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/ExportEngine.kt` — renderBitmapForPage() at line 389 (made internal in Phase 2).
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/Sb1ContainerWriter.kt` — Created in Phase 1.
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/SCREEN_WIDTH` / `SCREEN_HEIGHT` — Top-level vars in MainActivity.kt lines 58-59.
- `/home/jtd/development/notable/CLAUDE.md` — Project coding conventions.

---

<!-- START_TASK_1 -->
### Task 1: Add exportEngine parameter to SyncState.launchSync()

**Verifies:** inbox-sync-attachments.AC4.1 (parameter threading)

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/SyncState.kt` — lines 20-45

**Implementation:**

Add `exportEngine: ExportEngine` parameter to `launchSync()` and pass it through to `InboxSyncEngine.syncInboxPage()`.

**Current signature (line 20):**
```kotlin
fun launchSync(
    appRepository: AppRepository,
    pageId: String,
    tags: List<String>,
    context: Context
)
```

**New signature:**
```kotlin
fun launchSync(
    appRepository: AppRepository,
    pageId: String,
    tags: List<String>,
    context: Context,
    exportEngine: ExportEngine
)
```

**Change the call inside (line 31):**
```kotlin
// Before:
InboxSyncEngine.syncInboxPage(appRepository, pageId, tags, context)
// After:
InboxSyncEngine.syncInboxPage(appRepository, pageId, tags, context, exportEngine)
```

Add import: `import com.ethran.notable.io.ExportEngine`

Note: This will cause a compile error until Task 2 (syncInboxPage signature update) and Task 3 (EditorView call site update) are also completed. All three tasks in this phase should be implemented together.

**Verification:**
Deferred to Task 4 (full build after all wiring is done)

**Commit:** Combined with Tasks 2-4

<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Add exportEngine parameter to syncInboxPage() and wire attachment writes

**Verifies:** inbox-sync-attachments.AC1.1, inbox-sync-attachments.AC1.2, inbox-sync-attachments.AC1.3, inbox-sync-attachments.AC1.4, inbox-sync-attachments.AC1.5, inbox-sync-attachments.AC4.2, inbox-sync-attachments.AC5.1

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — lines 26-142 (syncInboxPage), plus imports

**Implementation:**

This is the core integration task. Modify `syncInboxPage()` to:
1. Accept `exportEngine` parameter
2. Fetch images (for content bounds)
3. Create per-note folder FIRST
4. Render JPG (with try-catch for graceful degradation)
5. Write SB1 container (with try-catch for graceful degradation)
6. Generate and write markdown LAST

**New signature (line 26):**
```kotlin
suspend fun syncInboxPage(
    appRepository: AppRepository,
    pageId: String,
    tags: List<String>,
    context: Context,
    exportEngine: ExportEngine
)
```

**New imports needed at top of file:**
```kotlin
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.data.db.Image
```

**Add image fetching** after existing stroke fetch (around line 37):
```kotlin
val images = appRepository.pageRepository.getWithImageById(pageId).images
```

**Replace the current markdown generation + write block (lines 133-139) with the new integration flow.** Uses `resolveNoteDir()` from Phase 3 to create the folder, writes attachments, then calls `writeMarkdownFile()` last:

```kotlin
val finalContent = fullText

// --- Attachment writes (Phases 1-3 integration) ---
val inboxPath = GlobalAppSettings.current.obsidianInboxPath
val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)

// Create per-note folder first (Phase 3 helper)
val noteDir = resolveNoteDir(page.createdAt, inboxPath)

// Phase 2: Render JPG (graceful degradation — AC1.3)
try {
    val jpgFile = File(noteDir, "page-1.jpg")
    writePageJpg(exportEngine, pageId, jpgFile)
} catch (e: Exception) {
    log.e("Failed to render page JPG: ${e.message}", e)
}

// Phase 1: Write SB1 container (graceful degradation — AC1.4)
try {
    val sb1File = File(noteDir, "page-1.sb1")
    Sb1ContainerWriter.writeSb1Container(
        file = sb1File,
        page = page,
        strokes = allStrokes,
        images = images,
        viewportWidth = SCREEN_WIDTH,
        viewportHeight = SCREEN_HEIGHT
    )
} catch (e: Exception) {
    log.e("Failed to write SB1 container: ${e.message}", e)
}

// Generate and write markdown LAST (its presence signals sync completion to Obsidian file watchers)
val markdown = generateMarkdown(createdDate, tags, finalContent)
writeMarkdownFile(markdown, page.createdAt, noteDir)
```

**Key design decisions:**
- Uses Phase 3's `resolveNoteDir()` helper for folder creation (no duplicated path resolution logic)
- Calls Phase 3's updated `writeMarkdownFile(markdown, createdAt, noteDir)` for final markdown write
- Markdown written LAST so its appearance signals sync completion to Obsidian file watchers
- JPG and SB1 writes are each wrapped in independent try-catch for graceful degradation (AC1.3, AC1.4)
- Page files use `page-1` naming convention (AC5.1)

**Add import for Sb1ContainerWriter:**
```kotlin
import com.ethran.notable.io.Sb1ContainerWriter
```

**Verification:**
Deferred to Task 4

**Commit:** Combined with Tasks 1, 3, 4

<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Update EditorView call site to pass exportEngine

**Verifies:** inbox-sync-attachments.AC4.1 (parameter threading)

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/editor/EditorView.kt` — line 244

**Implementation:**

Update the `SyncState.launchSync()` call in the `onSave` lambda to pass `exportEngine`.

**Current code (lines 243-248):**
```kotlin
onSave = {
    SyncState.launchSync(
        appRepository, pageId, selectedTags.toList(), context
    )
    navController.popBackStack()
},
```

**New code:**
```kotlin
onSave = {
    SyncState.launchSync(
        appRepository, pageId, selectedTags.toList(), context, exportEngine
    )
    navController.popBackStack()
},
```

`exportEngine` is already available as a parameter of the `EditorView` composable (line 75).

**Verification:**
Deferred to Task 4

**Commit:** Combined with Tasks 1, 2, 4

<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Build verification and commit

**Verifies:** inbox-sync-attachments.AC4.1, inbox-sync-attachments.AC4.2

**Files:** None (verification only)

**Verification:**

Run: `./gradlew assembleDebug`
Expected: Build succeeds with all four changes compiled together.

Check that no compile errors exist — the parameter threading chain must be complete:
`EditorView(exportEngine)` → `SyncState.launchSync(..., exportEngine)` → `InboxSyncEngine.syncInboxPage(..., exportEngine)` → `writePageJpg(exportEngine, ...)` and `Sb1ContainerWriter.writeSb1Container(...)`

**Commit:**
```bash
git add app/src/main/java/com/ethran/notable/io/SyncState.kt \
       app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt \
       app/src/main/java/com/ethran/notable/editor/EditorView.kt
git commit -m "feat: wire ExportEngine through sync flow for JPG and SB1 attachment writes"
```

**On-device verification (manual):**
1. Install debug APK: `./gradlew installDebug`
2. Open the inbox page, draw some strokes
3. Tap sync with tags selected
4. Check the Obsidian inbox path on the device file system
5. Verify: per-note folder exists with `{timestamp}.md`, `page-1.jpg`, `page-1.sb1`
6. Verify: markdown has `pages: 1`, `source: aragonite` in frontmatter, and `![[page-1.jpg]]` embed
7. Verify: JPG shows rendered strokes on background
8. Verify: SB1 file is non-empty binary

**Graceful degradation verification (AC1.3, AC1.4):**

These failure paths cannot be automated without adding a mocking framework (the project has none). They are verified via manual testing:

- **AC1.3 (JPG failure):** To simulate, temporarily throw an exception inside `writePageJpg()` before the `bitmap.compress()` call. Run sync, verify the `.md` file is still written and the folder exists. Remove the temporary throw.
- **AC1.4 (SB1 failure):** Similarly, temporarily throw inside `Sb1ContainerWriter.writeSb1Container()`. Verify `.md` file is still written.

Both paths log the error via `log.e()` — check logcat output confirms the error is logged and sync completes normally.

These criteria are tracked as human-verified in `test-requirements.md`.

<!-- END_TASK_4 -->
