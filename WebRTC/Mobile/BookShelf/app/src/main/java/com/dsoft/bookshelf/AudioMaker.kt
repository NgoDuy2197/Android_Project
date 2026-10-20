package com.dsoft.bookshelf

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Text -> one MP3 file. Each chunk (a few sentences) is synthesized to WAV with TextToSpeech.synthesizeToFile,
 * encoded to MP3 frames with jump3r and appended to the single output file; progress is saved after each chunk.
 * When finished the file is copied to Music/BookShelf (MediaStore, Android 10+).
 */
object AudioMaker {

    fun outputDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "audio"), "BookShelf").apply { mkdirs() }

    fun run(ctx: Context, start: Job, ctl: JobCtl) {
        var job = start
        val book = Library.get(job.bookId) ?: throw UserError(ctx.getString(R.string.err_book_missing))
        if (book.isPdf && JobManager.forBook(book.id).any { it.type == JobType.EXTRACT && !it.isFinished })
            throw UserError(ctx.getString(R.string.err_wait_extract))
        val srcBook = Library.textSourceOf(book) ?: throw UserError(ctx.getString(R.string.err_need_text))
        val text = Files.readText(Library.file(srcBook))
        if (text.isBlank()) throw UserError(ctx.getString(R.string.err_empty_text))

        if (job.outPath.isEmpty()) {
            var f = File(outputDir(ctx), TextLogic.safeFileName(book.title) + ".mp3")
            if (f.exists()) f = File(outputDir(ctx), TextLogic.safeFileName(book.title) + "_" + job.id.takeLast(5) + ".mp3")
            job = ctl.save(job.copy(outPath = f.absolutePath, outLen = 0, index = 0))
        }
        val out = File(job.outPath)
        Files.truncate(out, job.outLen)

        val chunks = TextLogic.utterances(text, maxSentences = 8, maxChars = 1500)
        job = ctl.save(job.withTotal(chunks.size, JobManager.now()))

        val latches = ConcurrentHashMap<String, CountDownLatch>()
        val errors = ConcurrentHashMap<String, Int>()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { latches[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { errors[id] = -1; latches[id]?.countDown() }
            override fun onError(id: String, errorCode: Int) { errors[id] = errorCode; latches[id]?.countDown() }
        }
        ctl.message(job.id, ctx.getString(R.string.job_tts_init))
        var tts: TextToSpeech = createTts(ctx, job.engine, text, listener)
        fun recreate() {
            try { tts.shutdown() } catch (_: Exception) {}
            tts = createTts(ctx, job.engine, text, listener)
        }

        val wav = File(ctx.cacheDir, "chunk_${job.id}.wav")
        val kbps = job.unitChars.takeIf { it in 32..192 } ?: 64
        val basePitch = Prefs.ttsPitch(ctx) / 100f
        val emphasis = Prefs.quoteEmphasis(ctx)
        Log.i(TAG, "run start job=${job.id} chunks=${chunks.size} from=${job.index} voice=${job.engine} kbps=$kbps emphasis=$emphasis")

        /** Synthesizes one text segment to PCM at [pitch], with the freeze-recovery retry loop. null = empty audio. */
        fun synthPcm(i: Int, label: String, seg: String, pitch: Float): Pair<WavInfo, ShortArray>? {
            var lastErr = ""
            for (attempt in 0 until MAX_ATTEMPTS) {
                ctl.checkStop(job.id)
                if (attempt > 0) { ctl.message(job.id, ctx.getString(R.string.job_synth_retry, i + 1, attempt + 1)); recreate() }
                runCatching { tts.setPitch(pitch.coerceIn(0.3f, 2.0f)) }
                val uid = "${job.id}_${label}_$attempt"
                val latch = CountDownLatch(1)
                latches[uid] = latch
                errors.remove(uid)
                wav.delete()
                try {
                    val rc = tts.synthesizeToFile(seg, Bundle(), wav, uid)
                    if (rc != TextToSpeech.SUCCESS) { lastErr = ctx.getString(R.string.err_tts_synth, rc); continue }
                    // ColorOS/Hans freezes the Google TTS process after a few utterances, so onDone never fires.
                    // Detect that fast (short timeout) and recreate the engine on the next attempt, which wakes it.
                    var done = false
                    var waited = 0L
                    while (waited < SYNTH_TIMEOUT_MS) {
                        if (latch.await(300, TimeUnit.MILLISECONDS)) { done = true; break }
                        waited += 300
                        if (JobManager.shouldStop(job.id)) { runCatching { tts.stop() }; throw StopSignal() }
                    }
                    if (!done) { Log.w(TAG, "chunk $i seg $label TIMEOUT ${waited}ms (TTS likely frozen)"); runCatching { tts.stop() }; lastErr = ctx.getString(R.string.err_tts_timeout); continue }
                    val err = errors.remove(uid)
                    if (err != null) { Log.w(TAG, "chunk $i seg $label onError code=$err"); lastErr = ctx.getString(R.string.err_tts_synth, err); continue }
                    return if (wav.length() > 44) Wav.read(wav) else null
                } catch (e: StopSignal) {
                    throw e
                } catch (e: Throwable) {
                    lastErr = (e.message ?: e.javaClass.simpleName)
                } finally {
                    latches.remove(uid)
                }
            }
            Log.w(TAG, "chunk $i seg $label FAILED after $MAX_ATTEMPTS attempts: $lastErr")
            throw UserError(ctx.getString(R.string.err_tts_chunk_failed, i + 1, lastErr))
        }

        try {
            for (i in job.index until chunks.size) {
                ctl.checkStop(job.id)
                ctl.message(job.id, ctx.getString(R.string.job_synth_chunk, i + 1, chunks.size))
                val s = text.substring(chunks[i].start, chunks[i].end)
                // Split into dialogue / narration runs so quotes can be read with a higher pitch.
                val segs = if (emphasis && TextLogic.hasQuote(s)) TextLogic.quoteSplit(s) else listOf(s to false)
                Log.d(TAG, "chunk $i len=${s.length} segs=${segs.size} stopReq=${JobManager.shouldStop(job.id)}")
                var sr = 0
                var chn = 1
                val parts = ArrayList<ShortArray>(segs.size)
                segs.forEachIndexed { si, (seg, inQuote) ->
                    if (seg.isNotBlank()) {
                        val pitch = basePitch * if (inQuote) QUOTE_PITCH_MUL else 1f
                        synthPcm(i, "${i}_$si", seg, pitch)?.let { (info, pcm) ->
                            sr = info.sampleRate; chn = info.channels.coerceIn(1, 2); parts.add(pcm)
                        }
                    }
                }
                val bytes = if (parts.isEmpty()) ByteArray(0) else {
                    val all = ShortArray(parts.sumOf { it.size })
                    var off = 0
                    for (pp in parts) { System.arraycopy(pp, 0, all, off, pp.size); off += pp.size }
                    val bos = ByteArrayOutputStream(all.size / 4 + 1024)
                    Mp3.encode(all, sr.coerceAtLeast(16000), chn, kbps, bos)
                    bos.toByteArray()
                }
                val newLen = if (bytes.isEmpty()) out.length() else Files.append(out, bytes)
                job = ctl.save(job.advance(newLen, JobManager.now()))
                Log.d(TAG, "chunk $i done mp3+=${bytes.size} total=$newLen status=${job.status}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "run exiting at index=${job.index} via ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            wav.delete()
            try { tts.shutdown() } catch (_: Exception) {}
        }
        ctl.message(job.id, ctx.getString(R.string.job_publishing))
        val uri = try { publish(ctx, out, book.title) } catch (_: Exception) { "" }
        // Add the finished audiobook to the shelf as its own AUDIO item (disc cover).
        var outBookId = job.outBookId
        if (Library.get(outBookId) == null) {
            outBookId = Library.createAudioBook("${book.title} (MP3)", book.id, out.absolutePath, uri).id
        } else {
            Library.update(outBookId) { it.copy(audioPath = out.absolutePath, audioUri = uri) }
        }
        ctl.save(job.copy(outUri = uri, outBookId = outBookId).done(JobManager.now()))
    }

    private const val MAX_ATTEMPTS = 8          // short timeouts + engine recreate recover from Hans freezes
    private const val SYNTH_TIMEOUT_MS = 10_000L
    private const val QUOTE_PITCH_MUL = 1.18f   // dialogue in quotes reads a bit higher
    private const val TAG = "BookShelfAudio"

    private fun createTts(ctx: Context, voice: String, text: String, l: UtteranceProgressListener): TextToSpeech {
        val tts = runBlocking { TtsUtil.create(ctx) }
        tts.setOnUtteranceProgressListener(l)
        TtsUtil.apply(ctx, tts, voice, text.take(3000))
        return tts
    }

    /** Copies the finished MP3 to Music/BookShelf via MediaStore (Android 10+). Returns the content Uri or "". */
    private fun publish(ctx: Context, f: File, title: String): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BookShelf")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return ""
        cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri.toString()
    }
}
