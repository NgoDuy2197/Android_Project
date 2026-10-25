package com.dsoft.odiary

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Per-user Google Drive sync (scope drive.file). Stores a REAL file/folder tree inside an "ODiary"
 * folder — e.g. ODiary/2026/10/25/day.json + photos — NOT a zip.
 *
 * Push is driven by the [Outbox] (a durable JSON queue of changed/deleted paths), so a sync costs
 * O(number of changes), never O(all data). Pull (download remote changes) is a separate pass used
 * only on manual sync / first login / restore. day.json downloads are merged so events aren't lost.
 */
object Drive {
    private const val SCOPE_RAW = "https://www.googleapis.com/auth/drive.file"
    private const val TOKEN_SCOPE = "oauth2:$SCOPE_RAW"
    private const val FOLDER = "ODiary"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SKEW = 2000L // mtime comparison tolerance (ms)

    // Shorter timeouts so a dead network fails fast (and reports) instead of hanging ~2 minutes.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    /** relativeDirPath ("" = ODiary root) -> Drive folder id */
    private val folderCache = HashMap<String, String>()
    private val lock = Any()   // serialize syncs (manual + auto) so folderCache isn't corrupted

    class RecoverableException(val intent: Intent) : Exception()
    private class AuthExpired : IOException("401")

    fun signInClient(ctx: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(SCOPE_RAW)).build()
        return GoogleSignIn.getClient(ctx, gso)
    }

    fun account(ctx: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(ctx)
    fun email(ctx: Context): String? = account(ctx)?.email
    fun signedIn(ctx: Context): Boolean = account(ctx) != null
    fun signOut(ctx: Context, done: () -> Unit) { folderCache.clear(); signInClient(ctx).signOut().addOnCompleteListener { done() } }

    // ---- token ----
    private fun freshToken(ctx: Context): String {
        val acc = account(ctx)?.account ?: throw IllegalStateException("Chưa đăng nhập Google")
        return try { GoogleAuthUtil.getToken(ctx, acc, TOKEN_SCOPE) }
        catch (e: UserRecoverableAuthException) { throw RecoverableException(e.intent ?: throw e) }
    }

    // ---- REST helpers ----
    private fun get(url: String, token: String): Response =
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private class DFile(val id: String, val mtime: Long, val isFolder: Boolean)

    // ---- folders ----
    private fun ensureRoot(token: String): String {
        folderCache[""]?.let { return it }
        val q = enc("mimeType='$FOLDER_MIME' and name='$FOLDER' and trashed=false")
        get("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name)&spaces=drive", token).use { r ->
            val body = r.body?.string() ?: ""
            if (r.code == 401) throw AuthExpired()
            if (r.isSuccessful) {
                val files = JSONObject(body).optJSONArray("files")
                if (files != null && files.length() > 0) { folderCache[""] = files.getJSONObject(0).getString("id"); return folderCache[""]!! }
            } else throw IOException("list root: ${r.code} $body")
        }
        folderCache[""] = createFolder(token, null, FOLDER); return folderCache[""]!!
    }

    private fun deleteFile(token: String, id: String) {
        http.newCall(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id")
            .header("Authorization", "Bearer $token").delete().build()).execute().use { r ->
            if (r.code == 401) throw AuthExpired()
        }
    }

    private fun createFolder(token: String, parentId: String?, name: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        http.newCall(Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(meta.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("create folder $name: ${r.code}")
            return JSONObject(r.body!!.string()).getString("id")
        }
    }

    /** Resolve (creating if needed) the Drive folder id for a relative dir like "2026/10/25". */
    private fun folderIdFor(token: String, relDir: String): String {
        if (relDir.isEmpty()) return ensureRoot(token)
        folderCache[relDir]?.let { return it }
        val parent = folderIdFor(token, relDir.substringBeforeLast('/', ""))
        val name = relDir.substringAfterLast('/')
        val id = createFolder(token, parent, name)
        folderCache[relDir] = id
        return id
    }

    /** Recursively list every file under ODiary; returns relativePath -> DFile and primes folderCache. */
    private fun listTree(token: String): Map<String, DFile> {
        val out = HashMap<String, DFile>()
        val rootId = ensureRoot(token)
        fun walk(folderId: String, prefix: String) {
            var pageToken: String? = null
            do {
                val q = enc("'$folderId' in parents and trashed=false")
                var url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=nextPageToken,files(id,name,mimeType,appProperties,modifiedTime)&pageSize=1000&spaces=drive"
                if (pageToken != null) url += "&pageToken=$pageToken"
                get(url, token).use { r ->
                    if (r.code == 401) throw AuthExpired()
                    if (!r.isSuccessful) throw IOException("list tree: ${r.code}")
                    val j = JSONObject(r.body!!.string())
                    val files = j.optJSONArray("files") ?: JSONArray()
                    val subdirs = ArrayList<Pair<String, String>>() // id, rel
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val name = f.getString("name")
                        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (f.getString("mimeType") == FOLDER_MIME) {
                            folderCache[rel] = f.getString("id")
                            out[rel] = DFile(f.getString("id"), 0, true)
                            subdirs.add(f.getString("id") to rel)
                        } else if (prefix.isEmpty() && name.matches(Regex("""\d{4}-\d{2}-\d{2}\.zip"""))) {
                            // legacy per-day zip from the old scheme — clean it up
                            runCatching { deleteFile(token, f.getString("id")) }
                            android.util.Log.d("ODiaryDrive", "deleted legacy zip $name")
                        } else {
                            val mt = f.optJSONObject("appProperties")?.optString("mtime")?.toLongOrNull() ?: 0L
                            out[rel] = DFile(f.getString("id"), mt, false)
                        }
                    }
                    pageToken = j.optString("nextPageToken").ifEmpty { null }
                    subdirs.forEach { walk(it.first, it.second) }
                }
            } while (pageToken != null)
        }
        walk(rootId, "")
        return out
    }

    private fun mimeOf(name: String) = when {
        name.endsWith(".json") -> "application/json"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        else -> "application/octet-stream"
    }

    private fun uploadFile(token: String, rel: String, f: File, existingId: String?) {
        val relDir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val meta = JSONObject().put("appProperties", JSONObject().put("mtime", f.lastModified().toString()))
        if (existingId == null) { meta.put("name", name); meta.put("parents", JSONArray().put(folderIdFor(token, relDir))) }
        val boundary = "odiaryBOUNDARY1a2b3c"
        val body = buildMultipart(meta.toString(), mimeOf(name), f.readBytes(), boundary)
            .toRequestBody("multipart/related; boundary=$boundary".toMediaType())
        val url = if (existingId == null) "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        else "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (existingId == null) req.post(body) else req.patch(body)
        http.newCall(req.build()).execute().use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("upload $rel: ${r.code}")
        }
    }

    private fun downloadFile(token: String, d: DFile, dst: File) {
        get("https://www.googleapis.com/drive/v3/files/${d.id}?alt=media", token).use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("download: ${r.code}")
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".dl")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
            if (d.mtime > 0) dst.setLastModified(d.mtime)
        }
    }

    /** Merge a Drive day.json into the local one (union events) instead of overwriting. */
    private fun downloadMergeDay(token: String, d: DFile, date: String) {
        val bytes = get("https://www.googleapis.com/drive/v3/files/${d.id}?alt=media", token).use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("download day: ${r.code}")
            r.body!!.bytes()
        }
        val remote = try { DayEntry.fromJson(JSONObject(String(bytes))) } catch (_: Exception) { return }
        val local = Store.loadDay(date)
        val remoteIds = remote.events.mapTo(HashSet()) { it.id }
        val localAhead = local.events.any { it.id !in remoteIds }   // local has events Drive lacks -> must push back
        val have = local.events.mapTo(HashSet()) { it.id }
        for (ev in remote.events) if (ev.id !in have) { local.events.add(ev); have.add(ev.id) }
        if (local.label.isEmpty()) local.label = remote.label
        if (local.emoji.isEmpty()) local.emoji = remote.emoji
        if (local.bg.isEmpty()) local.bg = remote.bg
        if (remote.compiled.isNotEmpty() && remote.updatedAt >= local.updatedAt) local.compiled = remote.compiled
        Store.saveDayQuiet(local)                       // applying a download -> don't re-queue by default
        if (localAhead) Outbox.put("${Store.relDir(date)}/day.json")   // only push back if local was ahead
    }

    private fun buildMultipart(meta: String, contentType: String, content: ByteArray, boundary: String): ByteArray {
        val bos = ByteArrayOutputStream()
        fun s(x: String) = bos.write(x.toByteArray(Charsets.UTF_8))
        s("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
        s(meta); s("\r\n")
        s("--$boundary\r\nContent-Type: $contentType\r\n\r\n")
        bos.write(content); s("\r\n--$boundary--")
        return bos.toByteArray()
    }

    private val dayRe = Regex("""^(\d{4})/(\d{2})/(\d{2})/day\.json$""")

    /** Resolve a folder id WITHOUT creating it; null if any segment doesn't exist on Drive. */
    private fun folderIdLookup(token: String, relDir: String): String? {
        if (relDir.isEmpty()) return ensureRoot(token)
        folderCache[relDir]?.let { return it }
        val parent = folderIdLookup(token, relDir.substringBeforeLast('/', "")) ?: return null
        val name = relDir.substringAfterLast('/')
        val q = enc("'$parent' in parents and name='$name' and mimeType='$FOLDER_MIME' and trashed=false")
        get("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive", token).use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("lookup folder: ${r.code}")
            val files = JSONObject(r.body!!.string()).optJSONArray("files")
            if (files != null && files.length() > 0) { val id = files.getJSONObject(0).getString("id"); folderCache[relDir] = id; return id }
        }
        return null
    }

    /** Find a file's Drive id by relative path, or null if it isn't there. */
    private fun findFileId(token: String, rel: String): String? {
        val parent = folderIdLookup(token, rel.substringBeforeLast('/', "")) ?: return null
        val name = rel.substringAfterLast('/')
        val q = enc("'$parent' in parents and name='$name' and trashed=false")
        get("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive", token).use { r ->
            if (r.code == 401) throw AuthExpired()
            if (!r.isSuccessful) throw IOException("find file: ${r.code}")
            val files = JSONObject(r.body!!.string()).optJSONArray("files")
            return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    // ---- public sync API ----
    /**
     * Routine auto-sync pushes the OUTBOX only (cost scales with number of changes, not total data).
     * [pull] = also download remote changes via listTree (manual sync / first login / restore).
     */
    fun sync(ctx: Context, pull: Boolean = false, progress: (Int, Int, String) -> Unit = { _, _, _ -> }, log: (String) -> Unit) {
        synchronized(lock) {
            var token = freshToken(ctx)
            try { doSync(ctx, token, pull, progress, log) }
            catch (e: AuthExpired) { GoogleAuthUtil.clearToken(ctx, token); token = freshToken(ctx); doSync(ctx, token, pull, progress, log) }
        }
    }

    private fun doSync(ctx: Context, token: String, pull: Boolean, progress: (Int, Int, String) -> Unit, log: (String) -> Unit) {
        folderCache.clear()
        ensureRoot(token)
        progress(0, 0, "Đang đồng bộ…")
        val (up, del) = flushOutbox(token, progress)
        var down = 0
        if (pull) { progress(0, 0, "Đang tải về…"); down = pullFromDrive(token, progress) }
        log("Đồng bộ xong — lên $up, xoá $del" + (if (pull) ", về $down" else "") + " tệp.")
    }

    /** Push every queued op; each removed only after success. Returns (uploaded, deleted). */
    private fun flushOutbox(token: String, progress: (Int, Int, String) -> Unit): Pair<Int, Int> {
        val base = Store.root()
        val ops = Outbox.all()
        var up = 0; var del = 0; var i = 0
        for (op in ops) {
            i++
            when (op.op) {
                "put" -> {
                    val f = File(base, op.path)
                    if (f.exists()) { uploadFile(token, op.path, f, findFileId(token, op.path)); up++ }
                    Outbox.remove(op.path)   // removed only after upload succeeded (exception would skip this)
                }
                "del" -> {
                    findFileId(token, op.path)?.let { deleteFile(token, it) }
                    Outbox.remove(op.path); del++
                }
            }
            progress(i, ops.size, op.path.substringAfterLast('/'))
        }
        return up to del
    }

    /** Download remote files missing or newer locally (day.json merged). Returns count. */
    private fun pullFromDrive(token: String, progress: (Int, Int, String) -> Unit): Int {
        val base = Store.root()
        val items = listTree(token).entries.filter { !it.value.isFolder }
        var down = 0; var i = 0
        for ((rel, d) in items) {
            i++
            val lf = File(base, rel)
            if (!lf.exists() || d.mtime > lf.lastModified() + SKEW) {
                val m = dayRe.find(rel)
                if (m != null) downloadMergeDay(token, d, "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")
                else downloadFile(token, d, lf)
                down++
            }
            progress(i, items.size, rel.substringAfterLast('/'))
        }
        return down
    }

    /** Drive storage: Pair(usedBytes, limitBytes); limit 0 = unknown/unlimited. Null on failure. */
    fun quota(ctx: Context): Pair<Long, Long>? {
        return try {
            val token = freshToken(ctx)
            get("https://www.googleapis.com/drive/v3/about?fields=storageQuota", token).use { r ->
                if (!r.isSuccessful) return null
                val q = JSONObject(r.body!!.string()).optJSONObject("storageQuota") ?: return null
                val used = q.optString("usage").toLongOrNull() ?: 0L
                val limit = q.optString("limit").toLongOrNull() ?: 0L
                used to limit
            }
        } catch (_: Exception) { null }
    }
}
