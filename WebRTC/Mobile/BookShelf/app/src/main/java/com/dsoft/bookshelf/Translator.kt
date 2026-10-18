package com.dsoft.bookshelf

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Quota / rate-limit errors: the job is paused so the user can press "Continue" later. */
class QuotaError(msg: String) : IOException(msg)

/** The ML Kit translation model(s) are not downloaded yet: the job pauses and the user downloads them explicitly. */
class ModelMissingError : RuntimeException()

/** Download / readiness helpers for ML Kit translation models (used by the explicit "Tải mô hình" action). */
object MlKitModels {
    /** English is the pivot language when translating between two non-English languages, so it is needed too. */
    fun requiredTags(src: String, tgt: String): List<String> {
        val set = linkedSetOf(src, tgt)
        if (src != "en" && tgt != "en") set += "en"
        return set.filter { it != Langs.AUTO && TranslateLanguage.fromLanguageTag(it) != null }
    }

    /** True when every required model is already downloaded (no network). */
    fun ready(src: String, tgt: String): Boolean {
        val need = requiredTags(src, tgt)
        if (need.isEmpty()) return true
        return try {
            val have = Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java), 20, TimeUnit.SECONDS)
            val haveTags = have.map { it.language }.toSet()
            need.all { it in haveTags }
        } catch (_: Exception) { false }
    }

    /** Blocking download of the models needed for [src]->[tgt]. Honors [wifiOnly]. Throws on failure. */
    fun download(src: String, tgt: String, wifiOnly: Boolean) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: throw IllegalArgumentException("src $src"))
                .setTargetLanguage(TranslateLanguage.fromLanguageTag(tgt) ?: throw IllegalArgumentException("tgt $tgt"))
                .build()
        )
        try {
            val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            Tasks.await(translator.downloadModelIfNeeded(cond), 30, TimeUnit.MINUTES)
        } finally {
            translator.close()
        }
    }
}

interface TranslateEngine {
    fun translate(text: String): String
    fun close() {}
}

/** Translate job: batches at paragraph/sentence boundaries, output appended to a new TEXT book after every batch. */
object Translator {

    fun run(ctx: Context, start: Job, ctl: JobCtl) {
        var job = start
        val book = Library.get(job.bookId) ?: throw UserError(ctx.getString(R.string.err_book_missing))
        if (book.isPdf && JobManager.forBook(book.id).any { it.type == JobType.EXTRACT && !it.isFinished })
            throw UserError(ctx.getString(R.string.err_wait_extract))
        val srcBook = Library.textSourceOf(book) ?: throw UserError(ctx.getString(R.string.err_need_text))
        val text = Files.readText(Library.file(srcBook))
        if (text.isBlank()) throw UserError(ctx.getString(R.string.err_empty_text))

        var outBook = Library.get(job.outBookId)
        if (outBook == null) {
            outBook = Library.createTextBook("${book.title} (${Langs.name(job.tgt)})", book.id, tag = job.tgt.uppercase())
            job = ctl.save(job.copy(outBookId = outBook.id, outLen = 0, index = 0))
        }
        val out = Library.file(outBook)
        Files.truncate(out, job.outLen)

        var src = job.src
        if (src == Langs.AUTO && job.engine == Engines.MLKIT) {
            ctl.message(job.id, ctx.getString(R.string.job_detecting))
            src = detectLanguage(text) ?: throw UserError(ctx.getString(R.string.err_detect))
            job = ctl.save(job.copy(src = src))
        }
        if (src == job.tgt) throw UserError(ctx.getString(R.string.err_same_lang))

        // ML Kit never auto-waits on a download: if the model is missing, pause and let the user download it.
        if (job.engine == Engines.MLKIT && !MlKitModels.ready(src, job.tgt)) throw ModelMissingError()

        val batches = TextLogic.batches(text, job.unitChars.takeIf { it > 0 } ?: 3000)
        job = ctl.save(job.withTotal(batches.size, JobManager.now()))
        if (job.index >= batches.size) { ctl.save(job.done(JobManager.now())); return }

        val engine: TranslateEngine = when (job.engine) {
            Engines.GOOGLE -> GoogleFreeEngine(src, job.tgt)
            Engines.GEMINI -> GeminiEngine(Prefs.geminiKey(ctx).ifBlank { throw UserError(ctx.getString(R.string.err_no_key)) }, src, job.tgt)
            else -> MlKitEngine(ctx, src, job.tgt, jobId = job.id) { ctl.message(job.id, it) }
        }
        try {
            for (i in job.index until batches.size) {
                ctl.checkStop(job.id)
                val chunk = text.substring(batches[i].start, batches[i].end)
                val (lead, core, trail) = TextLogic.padParts(chunk)
                val tr = if (core.isEmpty()) "" else engine.translate(core)
                val newLen = Files.append(out, (lead + tr + trail).toByteArray(Charsets.UTF_8))
                job = ctl.save(job.advance(newLen, JobManager.now()))
                if (job.engine == Engines.GOOGLE) Thread.sleep(1200) // be gentle with the unofficial endpoint
            }
        } finally {
            engine.close()
        }
        ctl.save(job.done(JobManager.now()))
    }

    /** ML Kit language-id on a sample of the text; returns a code from [Langs.codes] or null. */
    fun detectLanguage(text: String): String? {
        val sample = text.trim().take(1500)
        val id = LanguageIdentification.getClient()
        try {
            val tag = Tasks.await(id.identifyLanguage(sample), 30, TimeUnit.SECONDS)
            if (tag == "und") return null
            val base = tag.substringBefore('-')
            return base.takeIf { it in Langs.codes }
        } finally {
            id.close()
        }
    }
}

/**
 * (a) ML Kit on-device translation. The model is checked beforehand ([MlKitModels.ready]); if it is missing the
 * job pauses with [ModelMissingError] and the user downloads it explicitly, so this engine never waits on a download.
 */
class MlKitEngine(ctx: Context, src: String, tgt: String, jobId: String, status: (String) -> Unit) : TranslateEngine {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(src) ?: throw UserError("ML Kit: $src?"))
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(tgt) ?: throw UserError("ML Kit: $tgt?"))
            .build()
    )

    init {
        // Model already present: this completes immediately. Never require Wi-Fi / download here.
        try {
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            translator.close()
            throw ModelMissingError()
        }
        status("")
    }

    override fun translate(text: String): String {
        // translate line by line so paragraph breaks survive
        return text.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) line
            else try {
                Tasks.await(translator.translate(line), 2, TimeUnit.MINUTES)
            } catch (e: ExecutionException) {
                throw IOException(e.cause?.message ?: "ML Kit error", e.cause)
            }
        }
    }

    override fun close() = translator.close()
}

/** (b) Unofficial free Google endpoint (translate.googleapis.com, client=gtx). Rate-limited: backs off on HTTP 429. */
class GoogleFreeEngine(private val src: String, private val tgt: String) : TranslateEngine {
    override fun translate(text: String): String {
        var delay = 8_000L
        repeat(4) { attempt ->
            val conn = (URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=" +
                (if (src == Langs.AUTO) "auto" else Langs.googleCode(src)) + "&tl=" + Langs.googleCode(tgt) + "&dt=t&ie=UTF-8&oe=UTF-8")
                .openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BookShelf")
                conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
                val code = conn.responseCode
                if (code == 429 || code == 503) {
                    if (attempt == 3) throw QuotaError("HTTP $code (Google: too many requests)")
                    Thread.sleep(delay); delay *= 3
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONArray(i) ?: continue
                    if (!seg.isNull(0)) sb.append(seg.optString(0))
                }
                return sb.toString()
            } finally {
                conn.disconnect()
            }
        }
        throw QuotaError("HTTP 429")
    }
}

/** (c) Gemini API (user key). */
class GeminiEngine(private val key: String, private val src: String, private val tgt: String) : TranslateEngine {
    override fun translate(text: String): String {
        val from = if (src == Langs.AUTO) "the detected source language" else Langs.englishName(src)
        val prompt = "Translate the following text from $from into ${Langs.englishName(tgt)}. " +
            "Output only the translation, no explanations. Preserve line breaks and paragraph structure exactly.\n\n$text"
        val req = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        var delay = 5_000L
        repeat(3) { attempt ->
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 429) throw QuotaError("Gemini HTTP 429: " + errorText(conn))
                if (code == 503 || code == 500) {
                    if (attempt == 2) throw IOException("Gemini HTTP $code")
                    Thread.sleep(delay); delay *= 2
                    return@repeat
                }
                if (code != 200) throw IOException("Gemini HTTP $code: " + errorText(conn))
                val o = JSONObject(conn.inputStream.bufferedReader().readText())
                val cands = o.optJSONArray("candidates") ?: throw IOException("Gemini: no candidates")
                val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IOException("Gemini: empty (" + cands.getJSONObject(0).optString("finishReason") + ")")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                return sb.toString().trim('\n')
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Gemini failed")
    }

    private fun errorText(c: HttpURLConnection) = try {
        val s = c.errorStream?.bufferedReader()?.readText() ?: ""
        JSONObject(s).optJSONObject("error")?.optString("message")?.take(200) ?: s.take(200)
    } catch (_: Exception) { "" }
}
