package com.ethran.notable.io

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for InboxSyncEngine.generateMarkdown()
 * Verifies AC2.1, AC2.2, AC2.3, AC2.4 and future-proofing for multi-page embeds.
 */
class InboxSyncEngineMarkdownTest {

    /**
     * AC2.1: Frontmatter contains created date, tags, pages, source
     */
    @Test
    fun testGenerateMarkdownFrontmatterWithTags() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = listOf("meeting-notes"),
            content = "Hello world"
        )

        // Verify frontmatter structure
        assertContains(result, "created: \"[[2025-03-15]]\"")
        assertContains(result, "tags:")
        assertContains(result, "- meeting-notes")
        assertContains(result, "pages: 1")
        assertContains(result, "source: aragonite")
    }

    /**
     * AC2.4: Page with no tags produces frontmatter without tags field
     */
    @Test
    fun testGenerateMarkdownFrontmatterNoTags() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = emptyList(),
            content = "text"
        )

        // Verify tags field is NOT present
        assertFalse(result.contains("tags:"), "Frontmatter should not contain 'tags:' when tags list is empty")
        // But pages and source should still be there
        assertContains(result, "pages: 1")
        assertContains(result, "source: aragonite")
    }

    /**
     * AC2.2: Body ends with horizontal rule separator followed by image embed
     */
    @Test
    fun testGenerateMarkdownImageEmbed() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = listOf("test"),
            content = "Test content"
        )

        // Verify the separator and embed format
        assertContains(result, "\n---\n![[page-1.jpg]]\n")
    }

    /**
     * AC2.3: Content with [[wiki links]] and #tags passes through unchanged
     */
    @Test
    fun testGenerateMarkdownPreservesAnnotations() {
        val contentWithAnnotations = "Meeting with [[John Smith]] about #project-alpha and [[Q1 goals]]"
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = emptyList(),
            content = contentWithAnnotations
        )

        // Verify content is preserved exactly as is
        assertContains(result, "[[John Smith]]")
        assertContains(result, "#project-alpha")
        assertContains(result, "[[Q1 goals]]")
    }

    /**
     * Multi-page future-proofing: pages parameter generates multiple embeds
     */
    @Test
    fun testGenerateMarkdownMultiplePages() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = listOf("notes"),
            content = "Multi-page content",
            pages = 3
        )

        // Verify all page embeds are present
        assertContains(result, "![[page-1.jpg]]")
        assertContains(result, "![[page-2.jpg]]")
        assertContains(result, "![[page-3.jpg]]")

        // Verify they appear in order
        val page1Index = result.indexOf("![[page-1.jpg]]")
        val page2Index = result.indexOf("![[page-2.jpg]]")
        val page3Index = result.indexOf("![[page-3.jpg]]")
        assertEquals(true, page1Index < page2Index && page2Index < page3Index,
            "Page embeds should appear in ascending order")
    }

    /**
     * Default parameters: pages=1, source="aragonite"
     */
    @Test
    fun testGenerateMarkdownDefaults() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = emptyList(),
            content = "Default test"
        )

        assertContains(result, "pages: 1")
        assertContains(result, "source: aragonite")
    }

    /**
     * Custom source parameter
     */
    @Test
    fun testGenerateMarkdownCustomSource() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = emptyList(),
            content = "Custom source test",
            source = "custom-source"
        )

        assertContains(result, "source: custom-source")
    }

    /**
     * Multiple tags in frontmatter
     */
    @Test
    fun testGenerateMarkdownMultipleTags() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = listOf("tag1", "tag2", "tag3"),
            content = "Multiple tags test"
        )

        assertContains(result, "- tag1")
        assertContains(result, "- tag2")
        assertContains(result, "- tag3")
    }

    /**
     * Content trimming: leading/trailing whitespace removed
     */
    @Test
    fun testGenerateMarkdownTrimsContent() {
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = emptyList(),
            content = "  \n  Trimmed content  \n  "
        )

        // Content should be trimmed (no extra leading/trailing whitespace in the body)
        assertContains(result, "Trimmed content")
        assertFalse(result.contains("  \n  Trimmed"))
    }

    /**
     * Complex frontmatter and body with real-world HWR example
     */
    @Test
    fun testGenerateMarkdownComplexContent() {
        val hwrContent = "Discussed project roadmap. [[Q1 goals]]: focus on #core-features and #testing. Action items: [[John]] to finalize spec, [[Sarah]] to set up CI."
        val result = InboxSyncEngine.generateMarkdown(
            createdDate = "2025-03-15",
            tags = listOf("meeting-notes", "urgent"),
            content = hwrContent,
            pages = 2
        )

        // Verify full structure
        assertContains(result, "created: \"[[2025-03-15]]\"")
        assertContains(result, "pages: 2")
        assertContains(result, "source: aragonite")
        assertContains(result, "- meeting-notes")
        assertContains(result, "- urgent")

        // Content preserved
        assertContains(result, "[[Q1 goals]]")
        assertContains(result, "#core-features")
        assertContains(result, "#testing")
        assertContains(result, "[[John]]")
        assertContains(result, "[[Sarah]]")

        // Multiple page embeds
        assertContains(result, "![[page-1.jpg]]")
        assertContains(result, "![[page-2.jpg]]")
    }
}
