package com.ethran.notable.io

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.encodeStrokePoints
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AC01 SB1 Container Writer — Wraps SB1-encoded strokes with page-level metadata.
 *
 * Container format:
 * - Header (7 bytes): magic 'AC', version 1, flags 0x00000000
 * - Page metadata (25 bytes): viewport dimensions, content bounds, background enum, createdAt
 * - Stroke data: count + stroke entries
 *
 * Each stroke entry contains:
 * - metaSize (2 bytes): fixed value 11
 * - Stroke metadata (11 bytes): pen, size, color, maxPressure
 * - Point data: verbatim SB1-encoded bytes from encodeStrokePoints()
 */
object Sb1ContainerWriter {

    // ============== Public Data Classes ==============

    data class ContentBounds(val width: Int, val height: Int)

    // ============== Background Enum Mapping ==============

    fun backgroundToEnum(background: String): Byte {
        return when (background) {
            "blank" -> 0
            "dotted" -> 1
            "lined" -> 2
            "squared" -> 3
            "hexed" -> 4
            "inbox" -> 5
            else -> 0  // default to blank
        }
    }

    // ============== Content Bounds Computation ==============

    fun computeContentBounds(
        strokes: List<Stroke>,
        images: List<Image>,
        viewportWidth: Int,
        viewportHeight: Int
    ): ContentBounds {
        // If no strokes and no images, return viewport dimensions
        if (strokes.isEmpty() && images.isEmpty()) {
            return ContentBounds(viewportWidth, viewportHeight)
        }

        // Compute max right/bottom from stroke bounds
        val strokeRight = strokes.maxOfOrNull { it.right.toInt() } ?: 0
        val strokeBottom = strokes.maxOfOrNull { it.bottom.toInt() } ?: 0

        // Compute max right/bottom from image bounds
        val imageRight = images.maxOfOrNull { it.x + it.width } ?: 0
        val imageBottom = images.maxOfOrNull { it.y + it.height } ?: 0

        // Combine and add padding
        val rawWidth = maxOf(strokeRight, imageRight) + 50
        val rawHeight = maxOf(strokeBottom, imageBottom) + 50

        // Apply minimum viewport dimensions
        val width = rawWidth.coerceAtLeast(viewportWidth)
        val height = rawHeight.coerceAtLeast(viewportHeight)

        return ContentBounds(width, height)
    }

    // ============== Main Writer ==============

    fun writeSb1Container(
        file: File,
        page: Page,
        strokes: List<Stroke>,
        images: List<Image>,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        // Compute content bounds
        val contentBounds = computeContentBounds(strokes, images, viewportWidth, viewportHeight)

        // Create byte array output stream to collect all data
        val buffer = ByteBuffer.allocate(estimateTotalSize(strokes))
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // ========== Header (7 bytes) ==========
        buffer.put('A'.code.toByte())
        buffer.put('C'.code.toByte())
        buffer.put(1.toByte())  // version
        buffer.putInt(0x00000000)  // flags

        // ========== Page metadata (25 bytes) ==========
        buffer.putInt(viewportWidth)
        buffer.putInt(viewportHeight)
        buffer.putInt(contentBounds.width)
        buffer.putInt(contentBounds.height)
        buffer.put(backgroundToEnum(page.background))
        buffer.putLong(page.createdAt.time)

        // ========== Stroke data ==========
        buffer.putInt(strokes.size)  // strokeCount

        for (stroke in strokes) {
            // Encode point data first to get its size
            val pointData = encodeStrokePoints(stroke.points)

            // Stroke metadata: fixed metaSize of 11
            buffer.putShort(11.toShort())

            // Stroke metadata fields (11 bytes total)
            buffer.put(stroke.pen.ordinal.toByte())
            buffer.putFloat(stroke.size)
            buffer.putInt(stroke.color)
            buffer.putShort(stroke.maxPressure.toUShort().toShort())

            // Point data
            buffer.putInt(pointData.size)
            buffer.put(pointData)
        }

        // Trim to actual size and write to file
        buffer.flip()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        FileOutputStream(file).use { fos ->
            fos.write(data)
        }
    }

    // ============== Helper ==============

    private fun estimateTotalSize(strokes: List<Stroke>): Int {
        // Conservative estimate: header (7) + metadata (25) + strokeCount (4)
        // + per-stroke: metaSize (2) + metadata (11) + dataSize (4) + ~500 bytes avg
        val baseSize = 7 + 25 + 4
        val perStrokeEstimate = 2 + 11 + 4 + 500
        return baseSize + (strokes.size * perStrokeEstimate)
    }
}
