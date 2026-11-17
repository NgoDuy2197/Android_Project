package com.dsoft.remotelink.net

import java.io.InputStream
import java.io.OutputStream

/** Binary IO helpers shared by both servers (big-endian on the wire). */
object Io {
    fun readFully(ins: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var n = off
        val end = off + len
        while (n < end) {
            val r = ins.read(buf, n, end - n)
            if (r < 0) throw java.io.EOFException("stream closed")
            n += r
        }
    }

    fun readU8(ins: InputStream): Int {
        val b = ins.read(); if (b < 0) throw java.io.EOFException(); return b
    }

    fun readU16(ins: InputStream): Int {
        val a = readU8(ins); val b = readU8(ins); return (a shl 8) or b
    }

    fun readI32(ins: InputStream): Int {
        val a = ByteArray(4); readFully(ins, a); return bytesToI32(a, 0)
    }

    fun readF32(ins: InputStream): Float = Float.fromBits(readI32(ins))

    fun bytesToI32(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 24) or ((a[o + 1].toInt() and 0xFF) shl 16) or
        ((a[o + 2].toInt() and 0xFF) shl 8) or (a[o + 3].toInt() and 0xFF)

    fun i32ToBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    fun i64ToBytes(v: Long): ByteArray = byteArrayOf(
        (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    fun writeU16(out: OutputStream, v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    fun writeI32(out: OutputStream, v: Int) { out.write(i32ToBytes(v)) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }
}
