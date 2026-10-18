package com.dsoft.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeTest {
    private val vi = "Tiếng Việt có dấu\r\nDòng hai"

    @Test fun utf8AndCrlf() = assertEquals("Tiếng Việt có dấu\nDòng hai", Files.decode(vi.toByteArray(Charsets.UTF_8)))

    @Test fun utf8Bom() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("abc", Files.decode(b))
    }

    @Test fun utf16LeWithoutBom() {
        val s = "Hello world, this is a UTF-16 little endian text without BOM!!"
        assertEquals(s, Files.decode(s.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun truncatedUtf8TailStillUtf8() {
        val full = "Xin chào thế giới".toByteArray(Charsets.UTF_8)
        val cut = full.copyOf(full.size - 1) // last char "i" is ASCII, cut inside "ớ"? ensure multi-byte cut:
        val multi = "abcớ".toByteArray(Charsets.UTF_8)
        val partial = multi.copyOf(multi.size - 1)
        assertEquals("Xin chào thế giớ", Files.decode(cut))
        assertEquals("abc", Files.decode(partial).take(3))
    }

    @Test fun legacySingleByteFallsBack() {
        val latin1 = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()) // "Café" in windows-125x
        assertEquals("Café", Files.decode(latin1))
    }
}
