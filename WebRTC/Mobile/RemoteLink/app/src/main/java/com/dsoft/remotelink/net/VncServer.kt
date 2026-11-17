package com.dsoft.remotelink.net

import com.dsoft.remotelink.Log2
import com.dsoft.remotelink.Prefs
import com.dsoft.remotelink.capture.ScreenCapturer
import com.dsoft.remotelink.input.InputInjector
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal RFB 3.8 (VNC) server: "None" or "VNC password" auth, true-colour 32bpp, Raw encoding
 * with a dirty bounding-box so static screens cost almost nothing. Works with any VNC viewer
 * (TigerVNC, RealVNC, …) and with our own Desktop client. One client at a time is handled per
 * socket; multiple viewers may connect (each gets its own thread/framebuffer state).
 */
class VncServer(
    private val capturer: ScreenCapturer,
    private val injector: InputInjector,
    private val realW: Int,
    private val realH: Int,
    private val prefs: Prefs,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var clients = 0

    private val w get() = capturer.width
    private val h get() = capturer.height

    fun start() {
        if (running) return
        running = true
        val ss = ServerSocket(prefs.vncPort)
        serverSocket = ss
        acceptThread = Thread { acceptLoop(ss) }.apply { isDaemon = true; name = "vnc-accept"; start() }
        Log2.i("VncServer listening on :${prefs.vncPort}")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        Log2.i("VncServer stopped")
    }

    fun clientCount() = clients

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try { ss.accept() } catch (e: Exception) { if (running) Log2.e("vnc accept", e); break }
            sock.tcpNoDelay = true
            Thread { session(sock) }.apply { isDaemon = true; name = "vnc-client"; start() }
        }
    }

    private fun session(sock: Socket) {
        clients++
        Log2.i("vnc client ${sock.inetAddress?.hostAddress}")
        try {
            val ins = sock.getInputStream()
            val out = BufferedOutputStream(sock.getOutputStream())
            if (!handshake(ins, out)) return
            messageLoop(ins, out)
        } catch (e: Exception) {
            Log2.i("vnc client gone: ${e.message}")
        } finally { clients--; runCatching { sock.close() } }
    }

    // ---- handshake ----
    private fun handshake(ins: InputStream, out: OutputStream): Boolean {
        out.write("RFB 003.008\n".toByteArray()); out.flush()
        val ver = ByteArray(12); Io.readFully(ins, ver)

        val needAuth = prefs.password.isNotEmpty()
        if (needAuth) {
            out.write(1); out.write(2); out.flush()             // 1 sec-type: VNC auth (2)
            val sel = Io.readU8(ins)
            if (sel != 2) { out.write(Io.i32ToBytes(1)); out.flush(); return false }
            val challenge = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            out.write(challenge); out.flush()
            val resp = ByteArray(16); Io.readFully(ins, resp)
            val expect = desEncrypt(vncKey(prefs.password), challenge)
            if (!expect.contentEquals(resp)) {
                out.write(Io.i32ToBytes(1)); out.flush(); Log2.w("vnc auth failed"); return false
            }
            out.write(Io.i32ToBytes(0)); out.flush()            // SecurityResult OK
        } else {
            out.write(1); out.write(1); out.flush()             // 1 sec-type: None (1)
            Io.readU8(ins)                                      // client picks it
            out.write(Io.i32ToBytes(0)); out.flush()            // SecurityResult OK (3.8)
        }

        Io.readU8(ins)                                          // ClientInit shared-flag
        // ServerInit
        Io.writeU16(out, w); Io.writeU16(out, h)
        out.write(pixelFormat())
        val name = "RemoteLink".toByteArray()
        out.write(Io.i32ToBytes(name.size)); out.write(name); out.flush()
        return true
    }

    /** 32bpp, depth 24, little-endian, true-colour, red-shift 0 / green 8 / blue 16 → matches RGBA bytes. */
    private fun pixelFormat(): ByteArray = byteArrayOf(
        32, 24, 0, 1,                 // bpp, depth, big-endian=0, true-colour=1
        0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), // red-max, green-max, blue-max (U16 each)
        0, 8, 16,                     // red-shift, green-shift, blue-shift
        0, 0, 0                       // padding
    )

    // ---- main loop ----
    private fun messageLoop(ins: InputStream, out: OutputStream) {
        val cur = ByteArray(w * h * 4)
        val prev = ByteArray(w * h * 4)
        var havePrev = false
        var prevButtons = 0

        while (running) {
            when (Io.readU8(ins)) {
                0 -> { val pf = ByteArray(19); Io.readFully(ins, pf) /* SetPixelFormat: we only serve our format */ }
                2 -> { // SetEncodings
                    Io.readU8(ins); val n = Io.readU16(ins); val skip = ByteArray(n * 4); Io.readFully(ins, skip)
                }
                3 -> { // FramebufferUpdateRequest
                    val incremental = Io.readU8(ins); Io.readU16(ins); Io.readU16(ins); Io.readU16(ins); Io.readU16(ins)
                    val got = waitForFrame(cur, incremental != 0 && havePrev)
                    if (!got) { sendRects(out, emptyList(), cur); continue }
                    val rect: Rect
                    if (!havePrev || incremental == 0) {
                        rect = Rect(0, 0, w, h)
                    } else {
                        val d = dirtyBox(prev, cur)
                        if (d == null) { sendRects(out, emptyList(), cur); continue }
                        rect = d
                    }
                    sendRects(out, listOf(rect), cur)
                    System.arraycopy(cur, 0, prev, 0, cur.size); havePrev = true
                }
                4 -> { // KeyEvent
                    val down = Io.readU8(ins); Io.readU16(ins); val keysym = Io.readI32(ins)
                    if (down == 1) handleKeysym(keysym)
                }
                5 -> { // PointerEvent
                    val buttons = Io.readU8(ins); val x = Io.readU16(ins); val y = Io.readU16(ins)
                    handlePointer(prevButtons, buttons, x, y); prevButtons = buttons
                }
                6 -> { // ClientCutText
                    Io.readU8(ins); Io.readU16(ins); val len = Io.readI32(ins); val t = ByteArray(len); Io.readFully(ins, t)
                }
                else -> { /* unknown; ignore */ }
            }
        }
    }

    private fun waitForFrame(cur: ByteArray, incremental: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + if (incremental) (1000L / prefs.vncFps).coerceIn(40, 1000) else 2000
        while (System.currentTimeMillis() < deadline) {
            if (capturer.latestRgba(cur)) return true
            Thread.sleep(8)
        }
        return capturer.latestRgba(cur)
    }

    private class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun sendRects(out: OutputStream, rects: List<Rect>, cur: ByteArray) {
        out.write(0); out.write(0)                 // msg-type=0, padding
        Io.writeU16(out, rects.size)
        for (r in rects) {
            Io.writeU16(out, r.x); Io.writeU16(out, r.y); Io.writeU16(out, r.w); Io.writeU16(out, r.h)
            out.write(Io.i32ToBytes(0))            // encoding = Raw
            val rowBytes = r.w * 4
            for (yy in r.y until r.y + r.h) {
                out.write(cur, (yy * w + r.x) * 4, rowBytes)
            }
        }
        out.flush()
    }

    private fun dirtyBox(prev: ByteArray, cur: ByteArray): Rect? {
        var minY = -1; var maxY = -1
        val rowBytes = w * 4
        for (y in 0 until h) {
            val o = y * rowBytes
            if (!regionEquals(prev, cur, o, rowBytes)) { if (minY < 0) minY = y; maxY = y }
        }
        if (minY < 0) return null
        var minX = w; var maxX = -1
        for (y in minY..maxY) {
            val base = y * rowBytes
            for (x in 0 until w) {
                val o = base + x * 4
                if (prev[o] != cur[o] || prev[o + 1] != cur[o + 1] || prev[o + 2] != cur[o + 2]) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                }
            }
        }
        if (maxX < 0) return null
        return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun regionEquals(a: ByteArray, b: ByteArray, off: Int, len: Int): Boolean {
        var i = off; val end = off + len
        while (i < end) { if (a[i] != b[i]) return false; i++ }
        return true
    }

    // ---- input mapping ----
    private fun rx(x: Int) = (x.toFloat() * realW / w)
    private fun ry(y: Int) = (y.toFloat() * realH / h)

    private fun handlePointer(prevButtons: Int, buttons: Int, x: Int, y: Int) {
        val wasDown = prevButtons and 1
        val isDown = buttons and 1
        when {
            isDown == 1 && wasDown == 0 -> injector.touchDown(rx(x), ry(y), 0)
            isDown == 1 && wasDown == 1 -> injector.touchMove(rx(x), ry(y), 0)
            isDown == 0 && wasDown == 1 -> injector.touchUp(rx(x), ry(y), 0)
        }
    }

    private fun handleKeysym(keysym: Int) {
        when (keysym) {
            in 0x20..0x7E -> injector.text(keysym.toChar().toString())
            0xFF08 -> injector.key(67)           // BackSpace -> KEYCODE_DEL
            0xFF0D, 0xFF8D -> injector.key(66)   // Return -> ENTER
            0xFF09 -> injector.key(61)           // Tab
            0xFF1B -> injector.global("back")    // Esc
            0xFFFF -> injector.key(112)          // Delete -> FORWARD_DEL
            0xFF50 -> injector.global("home")    // Home
            0xFF51 -> injector.key(21)           // Left
            0xFF52 -> injector.key(19)           // Up
            0xFF53 -> injector.key(22)           // Right
            0xFF54 -> injector.key(20)           // Down
        }
    }

    // ---- VNC DES auth ----
    private fun vncKey(pw: String): ByteArray {
        val raw = pw.toByteArray().copyOf(8)              // pad/truncate to 8
        val key = ByteArray(8)
        for (i in 0 until 8) key[i] = reverseBits(raw[i]) // VNC reverses each byte's bits
        return key
    }

    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF; var r = 0
        for (i in 0 until 8) { r = (r shl 1) or (x and 1); x = x shr 1 }
        return r.toByte()
    }

    private fun desEncrypt(key8: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key8, "DES"))
        return c.doFinal(data)
    }
}
