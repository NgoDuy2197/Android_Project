package com.dsoft.bookshelf

import java.io.File

/**
 * Dependency-free MOBI / PRC (Mobipocket / old Kindle) -> plain text converter.
 *
 * A MOBI file is a Palm Database (PDB). Record 0 holds the PalmDOC + MOBI headers; records
 * `1..textRecordCount` hold the book body (HTML), optionally PalmDOC-compressed. [parse] walks
 * those records, decompresses them, trims the per-record trailing data, concatenates the HTML
 * and strips it to readable text with [Epub.htmlToText] (shared with the EPUB importer).
 *
 * Supported: uncompressed (1) and PalmDOC/LZ77 (2) compression. HUFF/CDIC (17480) and DRM-encrypted
 * books are not supported and yield `null` so the import fails gracefully (the user sees a message).
 */
object Mobi {
    data class Result(val title: String, val text: String)

    private const val MAX_TEXT = 8 * 1024 * 1024 // 8 MB, same guard as EPUB

    fun parse(file: File): Result? {
        return try {
            val data = file.readBytes()
            if (data.size < 78) return null

            val numRecords = be16(data, 76)
            if (numRecords < 1) return null
            // Record offsets live in the record-info list (8 bytes each) right after the 78-byte PDB header.
            fun recOffset(i: Int): Int = be32(data, 78 + i * 8)
            fun recBounds(i: Int): Pair<Int, Int> {
                val start = recOffset(i)
                val end = if (i + 1 < numRecords) recOffset(i + 1) else data.size
                return start.coerceIn(0, data.size) to end.coerceIn(0, data.size)
            }

            val (r0s, r0e) = recBounds(0)
            if (r0e - r0s < 16) return null
            val rec0 = data.copyOfRange(r0s, r0e)

            val compression = be16(rec0, 0)        // 1 none, 2 PalmDOC, 17480 HUFF/CDIC
            val encryption = be16(rec0, 12)         // 0 none, 1/2 DRM
            val textRecCount = be16(rec0, 8)
            if (encryption != 0) return null
            if (compression == 17480) return null   // HUFF/CDIC not supported

            val hasMobi = rec0.size >= 24 && rec0[16] == 'M'.code.toByte() && rec0[17] == 'O'.code.toByte() &&
                rec0[18] == 'B'.code.toByte() && rec0[19] == 'I'.code.toByte()
            val headerLen = if (hasMobi) be32(rec0, 20) else 0
            val encoding = if (hasMobi && rec0.size >= 32) be32(rec0, 28) else 1252
            val charset = if (encoding == 65001) Charsets.UTF_8 else cp1252()
            // Extra trailing-data flags (multibyte overlap / index entries appended to each text record).
            val extraFlags = if (hasMobi && headerLen >= 0xE4 && rec0.size >= 0xF4) be16(rec0, 0xF2) else 0

            val title = mobiTitle(rec0, charset)

            val sb = StringBuilder()
            val last = minOf(textRecCount, numRecords - 1)
            for (i in 1..last) {
                val (s, e) = recBounds(i)
                if (e <= s) continue
                var rec = data.copyOfRange(s, e)
                rec = trimTrailing(rec, extraFlags)
                val decoded = if (compression == 2) palmDocDecompress(rec) else rec
                if (decoded.isEmpty()) continue
                sb.append(String(decoded, charset))
                if (sb.length > MAX_TEXT) { sb.setLength(MAX_TEXT); break }
            }

            val text = Epub.htmlToText(sb.toString()).trim()
            if (text.isEmpty()) return null
            Result(title, text)
        } catch (_: Exception) {
            null
        }
    }

    /** Book title from the MOBI header's full-name field (offset/len at rec0 0x54/0x58), else the PDB name. */
    private fun mobiTitle(rec0: ByteArray, charset: java.nio.charset.Charset): String {
        try {
            if (rec0.size >= 0x5C) {
                val off = be32(rec0, 0x54)
                val len = be32(rec0, 0x58)
                if (off in 0 until rec0.size && len in 1..1024 && off + len <= rec0.size) {
                    val t = String(rec0, off, len, charset).trim()
                    if (t.isNotEmpty()) return t
                }
            }
        } catch (_: Exception) {}
        return "" // caller falls back to the file name
    }

    // ---- PalmDOC (LZ77) decompression ----
    private fun palmDocDecompress(input: ByteArray): ByteArray {
        var out = ByteArray(input.size * 4 + 16)
        var size = 0
        fun put(b: Byte) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = b
        }
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> put(0)
                c in 1..8 -> { // next c bytes are literal
                    var k = 0
                    while (k < c && i < input.size) { put(input[i]); i++; k++ }
                }
                c in 0x09..0x7F -> put(c.toByte())
                c in 0x80..0xBF -> { // 2-byte length/distance back-reference
                    if (i >= input.size) break
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val pair = ((c and 0x3F) shl 8) or c2
                    val dist = pair shr 3
                    val len = (pair and 0x07) + 3
                    if (dist in 1..size) {
                        var from = size - dist
                        var k = 0
                        while (k < len) { put(out[from]); from++; k++ } // may overlap with freshly written bytes
                    }
                }
                else -> { // 0xC0..0xFF: a space followed by (c xor 0x80)
                    put(' '.code.toByte())
                    put((c xor 0x80).toByte())
                }
            }
        }
        return out.copyOf(size)
    }

    /**
     * Trims the trailing data entries appended to each text record (multibyte-character overlap and
     * inline index bytes), whose presence is described by [extraFlags]. See the MOBI format notes.
     */
    private fun trimTrailing(data: ByteArray, extraFlags: Int): ByteArray {
        var end = data.size
        var flags = extraFlags shr 1
        while (flags != 0) {
            if (flags and 1 != 0) end -= sizeOfTrailingEntry(data, end)
            flags = flags shr 1
        }
        if (extraFlags and 1 != 0 && end > 0) {
            val n = (data[end - 1].toInt() and 0x3) + 1
            end -= n
        }
        return if (end <= 0) ByteArray(0) else if (end >= data.size) data else data.copyOf(end)
    }

    /** Size (incl. the size bytes) of the trailing entry ending at [end], encoded backwards 7 bits/byte. */
    private fun sizeOfTrailingEntry(data: ByteArray, end: Int): Int {
        var num = 0
        val from = maxOf(0, end - 4)
        for (p in from until end) {
            val v = data[p].toInt() and 0xFF
            if (v and 0x80 != 0) num = 0
            num = (num shl 7) or (v and 0x7F)
        }
        return num.coerceIn(0, end)
    }

    private fun be16(b: ByteArray, off: Int): Int =
        if (off + 1 >= b.size) 0 else ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, off: Int): Int {
        if (off + 3 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun cp1252(): java.nio.charset.Charset =
        try { java.nio.charset.Charset.forName("windows-1252") } catch (_: Exception) { Charsets.ISO_8859_1 }
}
