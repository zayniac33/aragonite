# Inbox Sync Attachments — Phase 2: ExportEngine Visibility + JPG Rendering

**Goal:** Make page bitmap rendering accessible from InboxSyncEngine and add a JPG compression helper.

**Architecture:** Change `ExportEngine.renderBitmapForPage()` from `private` to `internal` so code within the same Gradle module (like `InboxSyncEngine`) can call it. Add a standalone helper function `writePageJpg()` to `InboxSyncEngine` that renders a page bitmap and compresses it to JPEG at 85% quality. The helper is wired into the sync flow in Phase 4.

**Tech Stack:** Kotlin, Android `Bitmap.compress()`, `ExportEngine`

**Scope:** Phase 2 of 4 from original design

**Codebase verified:** 2026-03-15

---

## Acceptance Criteria Coverage

This phase implements (functional verification in Phase 4 when wired end-to-end):

### inbox-sync-attachments.AC1: Sync produces per-note folder with all three files
- **inbox-sync-attachments.AC1.2 Success:** `page-1.jpg` contains rendered strokes, images, and background at 85% JPEG quality

---

## Reference Files

The executor should read these files for context:
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/ExportEngine.kt` — Target file for visibility changes. Key areas: lines 389-403 (renderBitmapForPage), lines 434-436 (PageData), lines 438-442 (fetchPageData), lines 445-462 (computeContentDimensions), lines 754-758 (Bitmap.toBytes extension)
- `/home/jtd/development/notable/app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — Target file for JPG write helper
- `/home/jtd/development/notable/CLAUDE.md` — Project coding conventions

---

<!-- START_TASK_1 -->
### Task 1: Change ExportEngine visibility modifiers

**Verifies:** None (infrastructure for AC1.2, tested in Phase 4)

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/ExportEngine.kt`
  - Line 389: `private suspend fun renderBitmapForPage` → `internal suspend fun renderBitmapForPage`

**Implementation:**

Change the visibility of `renderBitmapForPage()` from `private` to `internal` (line 389). This is the only method InboxSyncEngine needs to call — `fetchPageData()` and `computeContentDimensions()` are called internally by `renderBitmapForPage()` and can remain `private`.

Note: `PageData`, `fetchPageData()`, and `computeContentDimensions()` all stay `private` — they are implementation details of `renderBitmapForPage()` and not needed externally.

**Verification:**
Run: `./gradlew assembleDebug`
Expected: Build succeeds — `internal` visibility is valid within the same Gradle module

**Commit:** `refactor: make ExportEngine.renderBitmapForPage() internal`

<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Add writePageJpg helper to InboxSyncEngine

**Verifies:** None (infrastructure for AC1.2, functionally tested in Phase 4)

**Files:**
- Modify: `app/src/main/java/com/ethran/notable/io/InboxSyncEngine.kt` — add new function

**Implementation:**

Add a new `suspend` function to the `InboxSyncEngine` object:

```kotlin
private suspend fun writePageJpg(
    exportEngine: ExportEngine,
    pageId: String,
    outputFile: File
) {
    val bitmap = exportEngine.renderBitmapForPage(pageId)
    try {
        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
    } finally {
        bitmap.recycle()
    }
}
```

Key details:
- Takes `ExportEngine`, `pageId`, and output `File` as parameters
- Calls `renderBitmapForPage(pageId)` which is now `internal` (from Task 1)
- Compresses to JPEG at 85% quality (per design spec)
- Recycles the bitmap in a `finally` block to prevent memory leaks even on write failure
- Uses `FileOutputStream.use {}` for automatic stream closing
- Is `private` for now — Phase 4 will call it from within `syncInboxPage()`

**New imports needed** at the top of InboxSyncEngine.kt:
- `android.graphics.Bitmap`
- `java.io.FileOutputStream`

Note: `ExportEngine` import will be needed — `import com.ethran.notable.io.ExportEngine` (same package, but add explicitly if not already imported).

**Verification:**
Run: `./gradlew assembleDebug`
Expected: Build succeeds. The function compiles but is not yet called from anywhere.

**Commit:** `feat: add JPG page rendering helper to InboxSyncEngine`

<!-- END_TASK_2 -->
