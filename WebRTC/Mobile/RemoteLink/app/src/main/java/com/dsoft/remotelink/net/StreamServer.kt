package com.dsoft.remotelink.net

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.dsoft.remotelink.Log2
import com.dsoft.remotelink.Prefs
import com.dsoft.remotelink.capture.ScreenCapturer
import com.dsoft.remotelink.input.InputInjector
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "RustDesk-like" custom server: H.264 video out, input/commands in, over a simple framed TCP
 * protocol. See the Desktop client for the mirror implementation.
 *
 * Frame on the wire: [1 byte type][4 byte BE length][payload].
 *   server->client: 0x01 HELLO(magic+ver+w+h+authFlag) · 0x02 CHALLENGE(16) ·
 *                   0x10 VIDEO_CONFIG(csd) · 0x11 VIDEO([1 flags][8 ptsUs][annexB])
 *   client->server: 0x80 AUTH(32=sha256(nonce+pw)) · 0x81 INPUT(subtype+...) · 0x82 KEEPALIVE
 */
class StreamServer(
    private val capturer: ScreenCapturer,
    private val injector: InputInjector,
    private val realW: Int,
    private val realH: Int,
    private val prefs: Prefs,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var encodeThread: Thread? = null
    @Volatile private var running = false

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var csd: ByteArray? = null
    private var codec: MediaCodec? = null

    private class Client(val socket: Socket, val out: OutputStream) {
        @Volatile var authed = false
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val writeLock = Any()
    }

    fun start() {
        if (running) return
        running = true
        val ss = ServerSocket(prefs.streamPort)
        serverSocket = ss
        acceptThread = Thread { acceptLoop(ss) }.apply { isDaemon = true; name = "stream-accept"; start() }
        encodeThread = Thread { encodeLoop() }.apply { isDaemon = true; name = "stream-encode"; start() }
        Log2.i("StreamServer listening on :${prefs.streamPort}")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        runCatching { codec?.stop(); codec?.release() }; codec = null
        Log2.i("StreamServer stopped")
    }

    // ---- accept + per-client reader ----
    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try { ss.accept() } catch (e: Exception) { if (running) Log2.e("accept", e); break }
            sock.tcpNoDelay = true
            val c = Client(sock, BufferedOutputStream(sock.getOutputStream()))
            clients.add(c)
            Log2.i("stream client ${sock.inetAddress?.hostAddress}")
            Thread { clientLoop(c) }.apply { isDaemon = true; name = "stream-client"; start() }
        }
    }

    private fun writeMsg(c: Client, type: Int, payload: ByteArray) {
        synchronized(c.writeLock) {
            c.out.write(type); c.out.write(Io.i32ToBytes(payload.size)); c.out.write(payload); c.out.flush()
        }
    }

    private fun clientLoop(c: Client) {
        try {
            val ins = c.socket.getInputStream()
            // HELLO
            val authReq = prefs.password.isNotEmpty()
            val hello = ByteArray(0).let {
                val b = java.io.ByteArrayOutputStream()
                b.write("RLNK".toByteArray()); b.write(1)
                b.write(Io.i32ToBytes(capturer.width)); b.write(Io.i32ToBytes(capturer.height))
                b.write(if (authReq) 1 else 0)
                b.toByteArray()
            }
            writeMsg(c, 0x01, hello)
            if (authReq) {
                writeMsg(c, 0x02, c.nonce)
            } else {
                c.authed = true
                sendConfigAndKeyframe(c)
            }
            while (running && !c.socket.isClosed) {
                val type = Io.readU8(ins)
                val len = Io.readI32(ins)
                val payload = ByteArray(len.coerceIn(0, 1 shl 20))
                Io.readFully(ins, payload)
                handleClientMsg(c, type, payload)
            }
        } catch (e: Exception) {
            Log2.i("stream client gone: ${e.message}")
        } finally {
            clients.remove(c); runCatching { c.socket.close() }
        }
    }

    private fun handleClientMsg(c: Client, type: Int, payload: ByteArray) {
        when (type) {
            0x80 -> { // AUTH
                val expect = Io.sha256(c.nonce, prefs.password.toByteArray())
                if (payload.size == 32 && expect.contentEquals(payload)) {
                    c.authed = true; Log2.i("stream client authed"); sendConfigAndKeyframe(c)
                } else { Log2.w("stream auth failed"); runCatching { c.socket.close() } }
            }
            0x81 -> if (c.authed) handleInput(payload)
            0x82 -> {} // keepalive
        }
    }

    private fun sx(xn: Float) = (xn.coerceIn(0f, 1f) * realW)
    private fun sy(yn: Float) = (yn.coerceIn(0f, 1f) * realH)

    private fun handleInput(p: ByteArray) {
        if (p.isEmpty()) return
        val b = java.io.ByteArrayInputStream(p)
        when (Io.readU8(b)) {
            1 -> { // touch: action, x, y, pointerId
                val action = Io.readU8(b); val x = sx(Io.readF32(b)); val y = sy(Io.readF32(b)); val pid = Io.readU8(b)
                when (action) { 0 -> injector.touchDown(x, y, pid); 1 -> injector.touchMove(x, y, pid); else -> injector.touchUp(x, y, pid) }
            }
            2 -> injector.tap(sx(Io.readF32(b)), sy(Io.readF32(b)))
            3 -> injector.swipe(sx(Io.readF32(b)), sy(Io.readF32(b)), sx(Io.readF32(b)), sy(Io.readF32(b)), Io.readI32(b).toLong())
            4 -> injector.key(Io.readI32(b))
            5 -> { val n = Io.readI32(b); val s = ByteArray(n); Io.readFully(b, s); injector.text(String(s, Charsets.UTF_8)) }
            6 -> { val n = Io.readI32(b); val s = ByteArray(n); Io.readFully(b, s); injector.global(String(s, Charsets.UTF_8)) }
            7 -> { val n = Io.readI32(b); val s = ByteArray(n); Io.readFully(b, s); Log2.i("command: ${String(s, Charsets.UTF_8)}") }
        }
    }

    private fun sendConfigAndKeyframe(c: Client) {
        csd?.let { writeMsg(c, 0x10, it) }
        // ask the encoder for a fresh keyframe so the new client can start decoding
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun broadcastVideo(flags: Int, ptsUs: Long, data: ByteArray, off: Int, len: Int) {
        if (clients.isEmpty()) return
        val payload = ByteArray(9 + len)
        payload[0] = flags.toByte()
        System.arraycopy(Io.i64ToBytes(ptsUs), 0, payload, 1, 8)
        System.arraycopy(data, off, payload, 9, len)
        for (c in clients) {
            if (!c.authed) continue
            try { writeMsg(c, 0x11, payload) } catch (e: Exception) { clients.remove(c); runCatching { c.socket.close() } }
        }
    }

    private fun broadcastConfig(data: ByteArray) {
        csd = data
        for (c in clients) if (c.authed) runCatching { writeMsg(c, 0x10, data) }
    }

    // ---- video source ----
    private fun encodeLoop() {
        try {
            if (capturer.feedsEncoderSurface) encodeViaMediaCodec() else forwardH264Stream()
        } catch (e: Exception) { if (running) Log2.e("encodeLoop", e) }
    }

    private fun encodeViaMediaCodec() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, capturer.width, capturer.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, prefs.streamBitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, prefs.streamFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mc.createInputSurface()
        mc.start()
        codec = mc
        capturer.renderTo(surface)

        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = mc.dequeueOutputBuffer(info, 10_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = mc.outputFormat
                val sps = of.getByteBuffer("csd-0"); val pps = of.getByteBuffer("csd-1")
                if (sps != null && pps != null) {
                    val a = ByteArray(sps.remaining()); sps.get(a)
                    val b = ByteArray(pps.remaining()); pps.get(b)
                    broadcastConfig(a + b)
                }
            } else if (idx >= 0) {
                val buf = mc.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        broadcastConfig(bytes)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        broadcastVideo(if (key) 1 else 0, info.presentationTimeUs, bytes, 0, bytes.size)
                    }
                }
                mc.releaseOutputBuffer(idx, false)
            }
        }
    }

    /** Root path: pump the screenrecord H.264 bytes to clients, re-opening when it ends. */
    private fun forwardH264Stream() {
        val buf = ByteArray(64 * 1024)
        while (running) {
            val ins: InputStream? = capturer.openH264Stream(prefs.streamFps, prefs.streamBitrateMbps * 1_000_000)
            if (ins == null) { Log2.e("root h264 stream unavailable"); Thread.sleep(1000); continue }
            try {
                while (running) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) broadcastVideo(0, 0, buf, 0, n)
                }
            } catch (e: Exception) { Log2.i("h264 stream ended: ${e.message}") } finally { runCatching { ins.close() } }
        }
    }

    fun clientCount(): Int = clients.count { it.authed }
}
