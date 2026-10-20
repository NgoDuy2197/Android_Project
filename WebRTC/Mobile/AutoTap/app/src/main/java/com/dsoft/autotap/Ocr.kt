package com.dsoft.autotap

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR used by the TAP_TEXT step: find the screen location of a piece
 * of text with ML Kit's default Latin recognizer.
 */
object Ocr {

    /**
     * Returns the center (x, y) in screenshot pixels of the [occurrence]-th text
     * element whose text contains [target] (case-insensitive, trimmed), or null.
     * Blocking — must be called off the main thread.
     */
    fun findTextCenter(bmp: Bitmap, target: String, occurrence: Int, region: Rect? = null): Pair<Float, Float>? {
        val want = target.trim().lowercase()
        if (want.isEmpty()) return null
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
            // Match on the finest granularity (elements), fall back to lines, then blocks.
            val boxes = ArrayList<Rect>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        if (el.text.trim().lowercase().contains(want)) el.boundingBox?.let { boxes.add(it) }
                    }
                    if (line.text.trim().lowercase().contains(want)) line.boundingBox?.let { boxes.add(it) }
                }
                if (block.text.trim().lowercase().replace("\n", " ").contains(want)) block.boundingBox?.let { boxes.add(it) }
            }
            val filtered = if (region != null) boxes.filter { region.contains(it.centerX(), it.centerY()) } else boxes
            // De-duplicate overlapping boxes, keep source order (top-left reading order already).
            val unique = filtered.distinctBy { "${it.left},${it.top},${it.right},${it.bottom}" }
            val chosen = unique.getOrNull(occurrence) ?: unique.firstOrNull() ?: return null
            chosen.exactCenterX() to chosen.exactCenterY()
        } catch (_: Exception) {
            null
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
