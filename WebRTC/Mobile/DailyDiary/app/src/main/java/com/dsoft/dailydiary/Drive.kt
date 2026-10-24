package com.dsoft.dailydiary

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Google Drive sync via the Drive v3 REST API.
 *
 * Auth: Google Identity [AuthorizationRequest] with the `drive.file` scope — the app only ever sees
 * files it created itself (the "DailyDiary Backup" folder), not the rest of the user's Drive.
 * Requires an *Android* OAuth client (package name + SHA-1) in Google Cloud Console; see README.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER_NAME = "DailyDiary Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    /** Older backups beyond this count are deleted after each upload. */
    private const val KEEP = 10

    data class RemoteFile(val id: String, val name: String, val size: Long, val created: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** If consent is still needed, the result has a pendingIntent the Activity must launch. */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request()).await()

    fun resultFromIntent(context: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Silent token (background worker). Null when the user must (re)consent in the UI. */
    suspend fun silentToken(context: Context): String? =
        runCatching { authorize(context).takeIf { !it.hasResolution() }?.accessToken }.getOrNull()

    suspend fun revoke(token: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke?token=$token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun accountEmail(token: String): String = withContext(Dispatchers.IO) {
        call(token, Request.Builder().url("$API/about?fields=user(emailAddress)")).let {
            JSONObject(it).getJSONObject("user").optString("emailAddress")
        }
    }

    /** Builds a fresh zip of the whole diary and uploads it into the app folder. */
    suspend fun uploadBackup(context: Context, token: String): RemoteFile = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zip = File(context.cacheDir, "DailyDiary_$stamp.zip")
        try {
            Backup.exportToFile(zip)
            val folder = folderId(token)

            // Resumable upload: multipart is capped at 5 MB, a diary with photos easily exceeds that.
            val meta = JSONObject().put("name", zip.name).put("parents", listOf(folder).toJsonArray())
            val start = Request.Builder()
                .url("$UPLOAD?uploadType=resumable")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", "application/zip")
                .header("X-Upload-Content-Length", zip.length().toString())
                .post(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
            val session = http.newCall(start).execute().use { r ->
                check(r)
                r.header("Location") ?: error("Drive không trả về upload session")
            }
            val put = Request.Builder()
                .url(session)
                .header("Authorization", "Bearer $token")
                .put(zip.asRequestBody("application/zip".toMediaType()))
                .build()
            val created = http.newCall(put).execute().use { r -> check(r); JSONObject(r.body!!.string()) }
            prune(token, folder)
            RemoteFile(created.getString("id"), zip.name, zip.length(), "")
        } finally {
            zip.delete()
        }
    }

    suspend fun listBackups(token: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        listIn(token, folderId(token))
    }

    /** Downloads [file] to cache and restores it. */
    suspend fun restore(context: Context, token: String, file: RemoteFile, replace: Boolean): Backup.Result =
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "drive_restore.zip")
            try {
                val req = Request.Builder()
                    .url("$API/files/${file.id}?alt=media")
                    .header("Authorization", "Bearer $token")
                    .build()
                http.newCall(req).execute().use { r ->
                    check(r)
                    tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                tmp.inputStream().use { Backup.import(it, replace) }
            } finally {
                tmp.delete()
            }
        }

    // ---- helpers ----

    private fun folderId(token: String): String {
        val q = "name='$FOLDER_NAME' and mimeType='$FOLDER_MIME' and trashed=false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id)")
            .build()
        val found = JSONObject(call(token, Request.Builder().url(url))).getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")

        val body = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())
        return JSONObject(call(token, Request.Builder().url("$API/files?fields=id").post(body))).getString("id")
    }

    private fun listIn(token: String, folder: String): List<RemoteFile> {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folder' in parents and trashed=false")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("fields", "files(id,name,size,createdTime)")
            .build()
        val arr = JSONObject(call(token, Request.Builder().url(url))).getJSONArray("files")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteFile(o.getString("id"), o.getString("name"), o.optString("size").toLongOrNull() ?: 0, o.optString("createdTime"))
        }
    }

    private fun prune(token: String, folder: String) {
        listIn(token, folder).drop(KEEP).forEach { f ->
            runCatching { call(token, Request.Builder().url("$API/files/${f.id}").delete()) }
        }
    }

    private fun call(token: String, b: Request.Builder): String =
        http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use { r ->
            check(r); r.body?.string().orEmpty()
        }

    private fun check(r: Response) {
        if (!r.isSuccessful) throw DriveException(r.code, r.peekBody(2048).string())
    }

    private fun List<String>.toJsonArray() = org.json.JSONArray(this)

    class DriveException(val code: Int, body: String) : Exception("Drive HTTP $code: $body")
}
