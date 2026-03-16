package com.ethran.notable.io

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.editor.utils.Pen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlin.test.assertEquals

class Sb1ContainerWriterTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File

    @Before
    fun setup() {
        testFile = tempFolder.newFile("test_container.sb1")
    }

    @After
    fun cleanup() {
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    // ============== AC3.1: Magic bytes, version, and flags ==============

    @Test
    fun testMagicBytesVersionAndFlags() {
        val page = Page(
            id = "page1",
            background = "blank",
            createdAt = Date(1000000000L)
        )

        Sb1ContainerWriter.writeSb1Container(
            file = testFile,
            page = page,
            strokes = emptyList(),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        val buffer = ByteBuffer.wrap(testFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        // Read first 7 bytes: magic (2) + version (1) + flags (4)
        val magic0 = buffer.get().toInt() and 0xFF
        val magic1 = buffer.get().toInt() and 0xFF
        val version = buffer.get().toInt() and 0xFF
        val flags = buffer.int

        assertEquals(0x41, magic0, "Magic byte 0 should be 'A'")
        assertEquals(0x43, magic1, "Magic byte 1 should be 'C'")
        assertEquals(1, version, "Version should be 1")
        assertEquals(0x00000000, flags, "Flags should be 0x00000000")
    }

    // ============== AC3.2: Page metadata ==============

    @Test
    fun testPageMetadata() {
        val viewportW = 1200
        val viewportH = 1600
        val createdAtMs = 1234567890L
        val page = Page(
            id = "page1",
            background = "dotted",
            createdAt = Date(createdAtMs)
        )

        Sb1ContainerWriter.writeSb1Container(
            file = testFile,
            page = page,
            strokes = emptyList(),
            images = emptyList(),
            viewportWidth = viewportW,
            viewportHeight = viewportH
        )

        val buffer = ByteBuffer.wrap(testFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        // Skip header (7 bytes)
        buffer.position(7)

        val readViewportWidth = buffer.int
        val readViewportHeight = buffer.int
        val readContentWidth = buffer.int
        val readContentHeight = buffer.int
        val readBackgroundEnum = buffer.get().toInt() and 0xFF
        val readCreatedAt = buffer.long

        assertEquals(viewportW, readViewportWidth, "Viewport width mismatch")
        assertEquals(viewportH, readViewportHeight, "Viewport height mismatch")
        assertEquals(viewportW, readContentWidth, "Content width should equal viewport when no strokes/images")
        assertEquals(viewportH, readContentHeight, "Content height should equal viewport when no strokes/images")
        assertEquals(1, readBackgroundEnum, "Background enum for 'dotted' should be 1")
        assertEquals(createdAtMs, readCreatedAt, "CreatedAt timestamp mismatch")
    }

    // ============== AC3.3: Stroke metadata and SB1-encoded point data ==============

    @Test
    fun testStrokeMetadataAndPointData() {
        val points = listOf(
            StrokePoint(x = 100f, y = 200f),
            StrokePoint(x = 110f, y = 210f),
            StrokePoint(x = 120f, y = 220f)
        )
        val stroke = Stroke(
            id = "stroke1",
            size = 5.0f,
            pen = Pen.BALLPEN,
            color = 0xFF000000.toInt(),
            maxPressure = 4096,
            top = 200f,
            bottom = 220f,
            left = 100f,
            right = 120f,
            points = points,
            pageId = "page1"
        )

        val page = Page(
            id = "page1",
            background = "blank",
            createdAt = Date(1000000000L)
        )

        Sb1ContainerWriter.writeSb1Container(
            file = testFile,
            page = page,
            strokes = listOf(stroke),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        val buffer = ByteBuffer.wrap(testFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        // Skip header (7 bytes) + metadata (25 bytes) + strokeCount (4 bytes)
        buffer.position(7 + 25 + 4)

        // Read stroke entry
        val metaSize = buffer.short.toInt() and 0xFFFF
        assertEquals(11, metaSize, "MetaSize should be 11")

        val penOrdinal = buffer.get().toInt() and 0xFF
        assertEquals(Pen.BALLPEN.ordinal, penOrdinal, "Pen ordinal should match BALLPEN")

        val readSize = buffer.float
        assertEquals(5.0f, readSize, "Stroke size should match")

        val readColor = buffer.int
        assertEquals(0xFF000000.toInt(), readColor, "Stroke color should match")

        val readMaxPressure = buffer.short.toInt() and 0xFFFF
        assertEquals(4096, readMaxPressure, "Max pressure should match")

        // Read point data size and verify it matches encodeStrokePoints output
        val dataSize = buffer.int
        val pointDataBytes = ByteArray(dataSize)
        buffer.get(pointDataBytes)

        val expectedPointData = encodeStrokePoints(points)
        assertEquals(expectedPointData.size, dataSize, "Point data size should match encodeStrokePoints output")

        // Verify point data matches
        val expectedBuffer = ByteBuffer.wrap(expectedPointData).order(ByteOrder.LITTLE_ENDIAN)
        val actualBuffer = ByteBuffer.wrap(pointDataBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (expectedBuffer.hasRemaining()) {
            assertEquals(
                expectedBuffer.get(),
                actualBuffer.get(),
                "Point data bytes should match encodeStrokePoints output"
            )
        }
    }

    // ============== AC3.4: Stroke count ==============

    @Test
    fun testStrokeCountWithMultipleStrokes() {
        val stroke1 = Stroke(
            id = "stroke1",
            size = 5.0f,
            pen = Pen.BALLPEN,
            color = 0xFF000000.toInt(),
            maxPressure = 4096,
            top = 0f,
            bottom = 100f,
            left = 0f,
            right = 100f,
            points = listOf(StrokePoint(50f, 50f)),
            pageId = "page1"
        )

        val stroke2 = Stroke(
            id = "stroke2",
            size = 3.0f,
            pen = Pen.PENCIL,
            color = 0xFF333333.toInt(),
            maxPressure = 2048,
            top = 200f,
            bottom = 300f,
            left = 200f,
            right = 300f,
            points = listOf(StrokePoint(250f, 250f)),
            pageId = "page1"
        )

        val stroke3 = Stroke(
            id = "stroke3",
            size = 2.0f,
            pen = Pen.MARKER,
            color = 0xFF666666.toInt(),
            maxPressure = 1024,
            top = 400f,
            bottom = 500f,
            left = 400f,
            right = 500f,
            points = listOf(StrokePoint(450f, 450f)),
            pageId = "page1"
        )

        val page = Page(
            id = "page1",
            background = "blank",
            createdAt = Date(1000000000L)
        )

        Sb1ContainerWriter.writeSb1Container(
            file = testFile,
            page = page,
            strokes = listOf(stroke1, stroke2, stroke3),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        val buffer = ByteBuffer.wrap(testFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        // Read strokeCount at offset 36 (7 header + 25 metadata + 4 strokeCount position)
        buffer.position(7 + 25)
        val strokeCount = buffer.int
        assertEquals(3, strokeCount, "Stroke count should be 3")

        // Verify we can read exactly 3 stroke entries
        var strokeIndex = 0
        while (buffer.hasRemaining() && strokeIndex < 3) {
            if (buffer.remaining() < 2) break
            val metaSize = buffer.short.toInt() and 0xFFFF
            assertEquals(11, metaSize, "MetaSize should be 11 for stroke $strokeIndex")

            // Skip pen (1) + size (4) + color (4) + maxPressure (2) = 11 bytes
            buffer.get() // pen
            buffer.float // size
            buffer.int // color
            buffer.short // maxPressure

            // Read dataSize and skip point data
            if (buffer.hasRemaining()) {
                val dataSize = buffer.int
                if (dataSize > 0) {
                    buffer.position(buffer.position() + dataSize)
                }
            }

            strokeIndex++
        }

        assertEquals(3, strokeIndex, "Should have read exactly 3 strokes")

        // Verify buffer is fully consumed (no trailing garbage)
        assertEquals(0, buffer.remaining(), "Buffer should be fully consumed after reading all strokes")
    }

    // ============== AC3.5: Empty stroke list ==============

    @Test
    fun testEmptyStrokeList() {
        val page = Page(
            id = "page1",
            background = "blank",
            createdAt = Date(1000000000L)
        )

        Sb1ContainerWriter.writeSb1Container(
            file = testFile,
            page = page,
            strokes = emptyList(),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        val bytes = testFile.readBytes()

        // Expected size: 7 (header) + 25 (metadata) + 4 (strokeCount) = 36 bytes
        assertEquals(36, bytes.size, "File size should be exactly 36 bytes for empty container")

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(7 + 25)

        val strokeCount = buffer.int
        assertEquals(0, strokeCount, "Stroke count should be 0")
    }

    // ============== computeContentBounds tests ==============

    @Test
    fun testComputeContentBoundsEmpty() {
        val bounds = Sb1ContainerWriter.computeContentBounds(
            strokes = emptyList(),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        assertEquals(1200, bounds.width, "Width should equal viewport when no content")
        assertEquals(1600, bounds.height, "Height should equal viewport when no content")
    }

    @Test
    fun testComputeContentBoundsStrokesSmallerThanViewport() {
        val stroke = Stroke(
            id = "stroke1",
            size = 5.0f,
            pen = Pen.BALLPEN,
            color = 0xFF000000.toInt(),
            maxPressure = 4096,
            top = 0f,
            bottom = 100f,
            left = 0f,
            right = 200f,
            points = listOf(StrokePoint(100f, 50f)),
            pageId = "page1"
        )

        val bounds = Sb1ContainerWriter.computeContentBounds(
            strokes = listOf(stroke),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        // Expected: max(stroke.right + 50, viewport) = max(200 + 50, 1200) = 1200
        // Expected: max(stroke.bottom + 50, viewport) = max(100 + 50, 1600) = 1600
        assertEquals(1200, bounds.width)
        assertEquals(1600, bounds.height)
    }

    @Test
    fun testComputeContentBoundsWithLargeStroke() {
        val stroke = Stroke(
            id = "stroke1",
            size = 5.0f,
            pen = Pen.BALLPEN,
            color = 0xFF000000.toInt(),
            maxPressure = 4096,
            top = 0f,
            bottom = 2000f,
            left = 0f,
            right = 1500f,
            points = listOf(StrokePoint(750f, 1000f)),
            pageId = "page1"
        )

        val bounds = Sb1ContainerWriter.computeContentBounds(
            strokes = listOf(stroke),
            images = emptyList(),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        // Expected: max(1500 + 50, 1200) = 1550
        // Expected: max(2000 + 50, 1600) = 2050
        assertEquals(1550, bounds.width)
        assertEquals(2050, bounds.height)
    }

    @Test
    fun testComputeContentBoundsWithImages() {
        val image = Image(
            id = "img1",
            x = 100,
            y = 200,
            width = 500,
            height = 300,
            uri = "file://test.png",
            pageId = "page1"
        )

        val bounds = Sb1ContainerWriter.computeContentBounds(
            strokes = emptyList(),
            images = listOf(image),
            viewportWidth = 1200,
            viewportHeight = 1600
        )

        // Expected: max(100 + 500 + 50, 1200) = max(650, 1200) = 1200
        // Expected: max(200 + 300 + 50, 1600) = max(550, 1600) = 1600
        assertEquals(1200, bounds.width)
        assertEquals(1600, bounds.height)
    }

    // ============== backgroundToEnum tests ==============

    @Test
    fun testBackgroundToEnumKnownValues() {
        assertEquals(0.toByte(), Sb1ContainerWriter.backgroundToEnum("blank"))
        assertEquals(1.toByte(), Sb1ContainerWriter.backgroundToEnum("dotted"))
        assertEquals(2.toByte(), Sb1ContainerWriter.backgroundToEnum("lined"))
        assertEquals(3.toByte(), Sb1ContainerWriter.backgroundToEnum("squared"))
        assertEquals(4.toByte(), Sb1ContainerWriter.backgroundToEnum("hexed"))
        assertEquals(5.toByte(), Sb1ContainerWriter.backgroundToEnum("inbox"))
    }

    @Test
    fun testBackgroundToEnumUnknownValue() {
        assertEquals(0.toByte(), Sb1ContainerWriter.backgroundToEnum("unknown_background"))
        assertEquals(0.toByte(), Sb1ContainerWriter.backgroundToEnum(""))
        assertEquals(0.toByte(), Sb1ContainerWriter.backgroundToEnum("random_string"))
    }
}
