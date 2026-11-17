package com.dsoft.proxyspy

import java.io.InputStream
import java.security.MessageDigest

/** Thông tin rút ra từ ClientHello của app (fingerprint TLS mà server hay dùng để nhận diện). */
class ClientHello(
    val ja3: String,          // chuỗi JA3 gốc
    val ja3Md5: String,       // MD5 của JA3 (fingerprint rút gọn)
    val tlsVersion: String,   // bản TLS cao nhất app đề nghị
    val sni: String,          // tên miền trong SNI
    val alpn: List<String>,   // ALPN app đề nghị (vd h2, http/1.1)
    val cipherCount: Int,
    val extCount: Int,
)

/**
 * Đọc & phân tích bản tin ClientHello thô (trước khi bắt tay TLS) để lấy JA3.
 * JA3 = MD5( version,ciphers,extensions,ellipticCurves,ecPointFormats ) — bỏ qua giá trị GREASE.
 * Đây là thứ ProxySpy KHÔNG lấy được sau khi đã giải mã TLS, nên phải peek byte thô.
 */
object TlsPeek {

    private fun isGrease(v: Int): Boolean =
        (v and 0x0f0f) == 0x0a0a && ((v ushr 8) and 0xff) == (v and 0xff)

    private fun readFully(ins: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var n = 0
        while (n < len) {
            val r = ins.read(buf, off + n, len - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    /** Đọc đúng 1 record TLS (5 byte header + thân). Trả về byte thô để vừa parse vừa replay lại cho SSLSocket. */
    fun readRecord(ins: InputStream): ByteArray? {
        val hdr = ByteArray(5)
        if (!readFully(ins, hdr, 0, 5)) return null
        if ((hdr[0].toInt() and 0xff) != 0x16) return hdr   // không phải handshake → trả nguyên để replay
        val len = ((hdr[3].toInt() and 0xff) shl 8) or (hdr[4].toInt() and 0xff)
        if (len <= 0 || len > 65535) return hdr
        val rec = ByteArray(5 + len)
        System.arraycopy(hdr, 0, rec, 0, 5)
        if (!readFully(ins, rec, 5, len)) return null
        return rec
    }

    /** Parse record ClientHello → JA3. Null nếu không phải ClientHello. */
    fun parse(rec: ByteArray): ClientHello? {
        try {
            if (rec.size < 43) return null
            var p = 0
            fun u8(): Int = rec[p++].toInt() and 0xff
            fun u16(): Int { val v = ((rec[p].toInt() and 0xff) shl 8) or (rec[p + 1].toInt() and 0xff); p += 2; return v }

            if (u8() != 0x16) return null       // record = handshake
            u16()                                // record version
            u16()                                // record length
            if (u8() != 0x01) return null       // handshake = ClientHello
            p += 3                               // handshake length (3 byte)
            val clientVersion = u16()
            p += 32                              // random
            val sidLen = u8(); p += sidLen       // session id

            val csLen = u16()
            val ciphers = ArrayList<Int>()
            var i = 0
            while (i < csLen && p + 2 <= rec.size) { val c = u16(); if (!isGrease(c)) ciphers.add(c); i += 2 }

            val compLen = u8(); p += compLen

            val extTypes = ArrayList<Int>()
            val curves = ArrayList<Int>()
            val pointFmts = ArrayList<Int>()
            var sni = ""
            val alpn = ArrayList<String>()
            var tlsVer = clientVersion

            if (p + 2 <= rec.size) {
                val extTotal = u16()
                val extEnd = minOf(p + extTotal, rec.size)
                while (p + 4 <= extEnd) {
                    val type = u16(); val len = u16()
                    val body = p; p += len
                    if (p > rec.size) break
                    if (!isGrease(type)) extTypes.add(type)
                    when (type) {
                        0x000a -> { // supported_groups (elliptic curves)
                            var q = body + 2; val end = body + len
                            while (q + 2 <= end && q + 2 <= rec.size) {
                                val g = ((rec[q].toInt() and 0xff) shl 8) or (rec[q + 1].toInt() and 0xff)
                                if (!isGrease(g)) curves.add(g); q += 2
                            }
                        }
                        0x000b -> { // ec_point_formats
                            var q = body + 1; val end = body + len
                            while (q < end && q < rec.size) { pointFmts.add(rec[q].toInt() and 0xff); q++ }
                        }
                        0x0000 -> { // SNI
                            if (len >= 5 && body + 5 <= rec.size) {
                                val nameLen = ((rec[body + 3].toInt() and 0xff) shl 8) or (rec[body + 4].toInt() and 0xff)
                                val s = body + 5
                                if (s + nameLen <= rec.size) sni = String(rec, s, nameLen, Charsets.US_ASCII)
                            }
                        }
                        0x0010 -> { // ALPN
                            var q = body + 2; val end = body + len
                            while (q < end && q < rec.size) {
                                val l = rec[q].toInt() and 0xff; q++
                                if (q + l <= rec.size) { alpn.add(String(rec, q, l, Charsets.US_ASCII)); q += l } else break
                            }
                        }
                        0x002b -> { // supported_versions → TLS cao nhất
                            var q = body + 1; val end = body + len; var best = 0
                            while (q + 2 <= end && q + 2 <= rec.size) {
                                val vv = ((rec[q].toInt() and 0xff) shl 8) or (rec[q + 1].toInt() and 0xff)
                                if (!isGrease(vv) && vv > best) best = vv; q += 2
                            }
                            if (best != 0) tlsVer = best
                        }
                    }
                }
            }

            val ja3 = buildString {
                append(clientVersion); append(',')
                append(ciphers.joinToString("-")); append(',')
                append(extTypes.joinToString("-")); append(',')
                append(curves.joinToString("-")); append(',')
                append(pointFmts.joinToString("-"))
            }
            val md5 = MessageDigest.getInstance("MD5").digest(ja3.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
            return ClientHello(ja3, md5, tlsName(tlsVer), sni, alpn, ciphers.size, extTypes.size)
        } catch (_: Exception) { return null }
    }

    private fun tlsName(v: Int) = when (v) {
        0x0304 -> "TLS 1.3"; 0x0303 -> "TLS 1.2"; 0x0302 -> "TLS 1.1"; 0x0301 -> "TLS 1.0"
        else -> "0x%04x".format(v)
    }
}
