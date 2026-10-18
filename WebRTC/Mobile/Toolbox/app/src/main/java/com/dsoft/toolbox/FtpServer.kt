package com.dsoft.toolbox

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Minimal FTP server (RFC 959 + common extensions: UTF8, PASV/EPSV, MLSD,
 * SIZE, MDTM, REST). Every client sees [root] as "/" and can never leave it.
 * Works with Windows Explorer, FileZilla, WinSCP, Total Commander...
 */
class FtpServer(
    root: File,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val readOnly: Boolean,
    private val onEvent: (String) -> Unit,
    private val onFileChanged: (File) -> Unit,
) {
    private val root: File = root.canonicalFile
    @Volatile private var server: ServerSocket? = null
    private val sessions = Collections.synchronizedSet(HashSet<Session>())

    val clientCount: Int get() = sessions.size

    /** Binds the port; throws if it is taken. */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        thread(name = "ftp-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                val sess = Session(c)
                sessions += sess
                thread(name = "ftp-client", isDaemon = true) {
                    try { sess.run() } finally { sessions -= sess }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(sessions) { sessions.toList() }.forEach { it.close() }
    }

    private inner class Session(private val ctrl: Socket) {
        private val input = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.UTF_8))
        private val out = BufferedWriter(OutputStreamWriter(ctrl.getOutputStream(), Charsets.UTF_8))
        private val who = ctrl.inetAddress?.hostAddress ?: "?"
        private var loggedIn = false
        private var userName = ""
        private var cwd = "/"
        private var pasv: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var renameFrom: File? = null
        private var rest = 0L

        fun close() {
            runCatching { ctrl.close() }
            runCatching { pasv?.close() }
        }

        private fun reply(code: Int, msg: String) {
            out.write("$code $msg\r\n")
            out.flush()
        }

        fun run() {
            ctrl.soTimeout = 30 * 60_000
            onEvent("$who đã kết nối")
            try {
                reply(220, "Toolbox FTP ready")
                while (true) {
                    val line = input.readLine() ?: break
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.US)
                    val arg = if (sp < 0) "" else line.substring(sp + 1)
                    if (!handle(cmd, arg)) break
                }
            } catch (_: IOException) {
            } finally {
                close()
                onEvent("$who đã ngắt kết nối")
            }
        }

        /** Returns false to end the session. */
        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> {
                    userName = arg
                    if (user.isEmpty()) { loggedIn = true; reply(230, "Logged in") } else reply(331, "Password required")
                }
                "PASS" -> if (user.isEmpty() || (userName == user && arg == pass)) {
                    loggedIn = true
                    reply(230, "Logged in")
                } else {
                    loggedIn = false
                    reply(530, "Login incorrect")
                    onEvent("$who sai mật khẩu")
                }
                "QUIT" -> { reply(221, "Bye"); return false }
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> {
                    out.write("211-Features:\r\n UTF8\r\n MLSD\r\n MLST type*;size*;modify*;\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n PASV\r\n")
                    reply(211, "End")
                }
                "OPTS", "NOOP", "TYPE", "MODE", "STRU", "ALLO", "CLNT" -> reply(200, "OK")
                else -> if (!loggedIn) reply(530, "Please login") else authed(cmd, arg)
            }
            return true
        }

        private fun authed(cmd: String, arg: String) {
            when (cmd) {
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is current directory")
                "CWD", "XCWD" -> {
                    val v = norm(arg)
                    if (file(v).isDirectory) { cwd = v; reply(250, "OK") } else reply(550, "No such directory")
                }
                "CDUP", "XCUP" -> { cwd = norm(".."); reply(250, "OK") }
                "PASV" -> {
                    val ps = openPasv()
                    val ip = localIpFor().replace('.', ',')
                    reply(227, "Entering Passive Mode ($ip,${ps.localPort shr 8},${ps.localPort and 255})")
                }
                "EPSV" -> reply(229, "Entering Extended Passive Mode (|||${openPasv().localPort}|)")
                "PORT" -> {
                    val n = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (n.size != 6) { reply(501, "Bad PORT"); return }
                    setActive(InetSocketAddress(n.take(4).joinToString("."), n[4] * 256 + n[5]))
                    reply(200, "PORT OK")
                }
                "EPRT" -> {
                    val parts = arg.split(arg.firstOrNull() ?: '|')
                    val p = parts.getOrNull(3)?.toIntOrNull()
                    if (p == null) { reply(501, "Bad EPRT"); return }
                    setActive(InetSocketAddress(parts[2], p))
                    reply(200, "EPRT OK")
                }
                "LIST", "NLST", "MLSD" -> list(cmd, arg)
                "MLST" -> {
                    val f = file(norm(arg))
                    if (!f.exists()) { reply(550, "Not found"); return }
                    out.write("250-Listing\r\n ${facts(f)} ${norm(arg)}\r\n")
                    reply(250, "End")
                }
                "SIZE" -> file(norm(arg)).let { if (it.isFile) reply(213, it.length().toString()) else reply(550, "Not a file") }
                "MDTM" -> file(norm(arg)).let { if (it.exists()) reply(213, mdtm(it.lastModified())) else reply(550, "Not found") }
                "REST" -> {
                    rest = arg.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
                    reply(350, "Restarting at $rest")
                }
                "RETR" -> retr(arg)
                "STOR", "APPE" -> stor(cmd, arg)
                "DELE" -> write {
                    val v = norm(arg)
                    val f = file(v)
                    if (f.isFile && f.delete()) { changed(f); reply(250, "Deleted") } else reply(550, "Delete failed")
                }
                "RMD", "XRMD" -> write {
                    val v = norm(arg)
                    val f = file(v)
                    if (v != "/" && f.isDirectory && f.delete()) reply(250, "Removed") else reply(550, "Remove failed (not empty?)")
                }
                "MKD", "XMKD" -> write {
                    val v = norm(arg)
                    if (file(v).mkdirs()) reply(257, "\"${v.replace("\"", "\"\"")}\" created") else reply(550, "Create failed")
                }
                "RNFR" -> write {
                    val v = norm(arg)
                    val f = file(v)
                    if (v != "/" && f.exists()) { renameFrom = f; reply(350, "Ready for RNTO") } else reply(550, "Not found")
                }
                "RNTO" -> write {
                    val from = renameFrom
                    renameFrom = null
                    val to = file(norm(arg))
                    if (from != null && from.renameTo(to)) { changed(from); changed(to); reply(250, "Renamed") } else reply(550, "Rename failed")
                }
                "ABOR" -> reply(226, "OK")
                "STAT" -> reply(211, "Toolbox FTP, ${sessions.size} client(s)")
                "SITE" -> reply(200, "OK")
                else -> reply(502, "Not implemented")
            }
        }

        private inline fun write(block: () -> Unit) {
            if (readOnly) reply(550, "Read-only server") else block()
        }

        // ---- paths: virtual "/a/b" <-> File under root; ".." can never climb above "/" ----

        private fun norm(p: String): String {
            val virtual = if (p.startsWith("/")) p else "$cwd/$p"
            val parts = ArrayList<String>()
            for (seg in virtual.split('/', '\\')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
            return "/" + parts.joinToString("/")
        }

        private fun file(v: String) = if (v == "/") root else File(root, v.substring(1))

        // ---- data connection ----

        private fun openPasv(): ServerSocket {
            runCatching { pasv?.close() }
            active = null
            return ServerSocket(0).also { pasv = it }
        }

        private fun setActive(a: InetSocketAddress) {
            runCatching { pasv?.close() }
            pasv = null
            active = a
        }

        /** IPv4 the client used to reach us (what it must connect back to). */
        private fun localIpFor(): String {
            (ctrl.localAddress as? Inet4Address)?.hostAddress?.let { return it }
            val raw = ctrl.localAddress?.address
            if (raw != null && raw.size == 16) { // IPv4-mapped IPv6
                return InetAddress.getByAddress(raw.copyOfRange(12, 16)).hostAddress ?: "127.0.0.1"
            }
            return Net.addresses().firstOrNull()?.second ?: "127.0.0.1"
        }

        private fun openData(): Socket? = try {
            val ps = pasv
            if (ps != null) {
                ps.soTimeout = 20_000
                ps.accept().also { runCatching { ps.close() }; pasv = null }
            } else active?.let { Socket().apply { connect(it, 15_000) } }
        } catch (_: IOException) {
            null
        }

        private fun transfer(block: (Socket) -> Unit) {
            reply(150, "Opening data connection")
            val s = openData() ?: run { reply(425, "Can't open data connection"); return }
            try {
                s.use(block)
                reply(226, "Transfer complete")
            } catch (_: IOException) {
                reply(426, "Transfer aborted")
            }
        }

        // ---- commands with data ----

        private fun list(cmd: String, arg: String) {
            var a = arg.trim()
            while (a.startsWith("-")) a = a.substringAfter(' ', "").trim() // "LIST -la"
            val target = file(norm(a))
            if (!target.exists()) { reply(550, "Not found"); return }
            val items = if (target.isDirectory) {
                (target.listFiles() ?: emptyArray()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            } else listOf(target)
            transfer { s ->
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                for (f in items) {
                    w.write(when (cmd) {
                        "NLST" -> f.name
                        "MLSD" -> "${facts(f)} ${f.name}"
                        else -> lsLine(f)
                    })
                    w.write("\r\n")
                }
                w.flush()
            }
        }

        private fun retr(arg: String) {
            val f = file(norm(arg))
            val offset = rest
            rest = 0
            if (!f.isFile) { reply(550, "Not a file"); return }
            transfer { s ->
                FileInputStream(f).use { inp ->
                    if (offset > 0) inp.channel.position(offset)
                    inp.copyTo(s.getOutputStream(), BUF)
                }
            }
        }

        private fun stor(cmd: String, arg: String) {
            val offset = rest
            rest = 0
            if (readOnly) { reply(550, "Read-only server"); return }
            val f = file(norm(arg))
            if (f.isDirectory) { reply(550, "Is a directory"); return }
            f.parentFile?.mkdirs()
            transfer { s ->
                RandomAccessFile(f, "rw").use { raf ->
                    val start = if (cmd == "APPE") raf.length() else offset
                    raf.setLength(start)
                    raf.seek(start)
                    val buf = ByteArray(BUF)
                    val inp = s.getInputStream()
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                    }
                }
            }
            changed(f)
            onEvent("$who tải lên: ${f.name}")
        }

        private fun changed(f: File) = runCatching { onFileChanged(f) }
    }

    companion object {
        private const val BUF = 64 * 1024
        private const val HALF_YEAR = 180L * 24 * 3600 * 1000

        private fun lsLine(f: File): String {
            val t = f.lastModified()
            val recent = Math.abs(System.currentTimeMillis() - t) < HALF_YEAR
            val date = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US).format(Date(t))
            val perm = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val size = if (f.isDirectory) 4096 else f.length()
            return String.format(Locale.US, "%s 1 owner group %13d %s %s", perm, size, date, f.name)
        }

        private fun facts(f: File): String {
            val type = if (f.isDirectory) "type=dir;" else "type=file;size=${f.length()};"
            return "${type}modify=${mdtm(f.lastModified())};"
        }

        private fun mdtm(t: Long): String =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    }
}
