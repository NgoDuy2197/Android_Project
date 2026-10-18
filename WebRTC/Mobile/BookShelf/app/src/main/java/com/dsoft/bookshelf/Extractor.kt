package com.dsoft.bookshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * PDF -> TEXT book. Page by page: embedded text layer via PDFBox; pages without text are rendered with
 * PdfRenderer and OCR'd with ML Kit (Latin / Chinese / Japanese). Progress is persisted after every page.
 */
object Extractor {
    private const val MIN_TEXT = 15

    fun run(ctx: Context, start: Job, ctl: JobCtl) {
        var job = start
        val book = Library.get(job.bookId) ?: throw UserError(ctx.getString(R.string.err_book_missing))
        if (!book.isPdf) throw UserError(ctx.getString(R.string.err_not_pdf))

        var outBook = Library.get(job.outBookId)
        if (outBook == null) {
            outBook = Library.createTextBook(ctx.getString(R.string.title_extracted, book.title), book.id)
            job = ctl.save(job.copy(outBookId = outBook.id, outLen = 0, index = 0))
        }
        Library.update(book.id) { it.copy(textBookId = outBook.id) }
        val out = Library.file(outBook)
        Files.truncate(out, job.outLen)

        val pfd = ParcelFileDescriptor.open(Library.file(book), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); throw UserError(ctx.getString(R.string.err_pdf_open)) }
        val doc: PDDocument? = try { PDDocument.load(Library.file(book)) } catch (_: Throwable) { null }
        val stripper = try { PDFTextStripper().apply { sortByPosition = true } } catch (_: Throwable) { null }
        val ocr = Ocr(Prefs.ocrScript(ctx))
        try {
            val total = renderer.pageCount
            job = ctl.save(job.withTotal(total, JobManager.now()))
            for (i in job.index until total) {
                ctl.checkStop(job.id)
                var text = ""
                if (doc != null && stripper != null) {
                    text = try {
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        stripper.getText(doc)
                    } catch (_: Throwable) { "" }
                }
                var failed = false
                if (text.trim().length < MIN_TEXT) {
                    ctl.message(job.id, ctx.getString(R.string.job_ocr_page, i + 1))
                    try {
                        val o = ocr.page(renderer, i)
                        if (o.trim().length > text.trim().length) text = o
                    } catch (e: StopSignal) {
                        throw e
                    } catch (_: Throwable) {
                        failed = true
                    }
                }
                // join wrapped lines back into sentences/paragraphs (PDF line-wraps become spaces, not newlines)
                val clean = TextLogic.joinWrappedLines(text)
                val newLen = if (clean.isEmpty()) out.length() else Files.append(out, (clean + "\n\n").toByteArray(Charsets.UTF_8))
                job = ctl.save(if (failed || clean.isEmpty()) job.skip(newLen, JobManager.now()) else job.advance(newLen, JobManager.now()))
            }
            Library.saveState(outBook.id) { it.copy(textLength = 0) }
            ctl.save(job.done(JobManager.now()))
        } finally {
            ocr.close()
            try { doc?.close() } catch (_: Throwable) {}
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    /** Lazily created ML Kit recognizers. script: 0 auto, 1 latin, 2 chinese, 3 japanese. */
    class Ocr(private val script: Int) {
        private var latin: TextRecognizer? = null
        private var chinese: TextRecognizer? = null
        private var japanese: TextRecognizer? = null

        private fun latin() = latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { latin = it }
        private fun chinese() = chinese ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { chinese = it }
        private fun japanese() = japanese ?: TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { japanese = it }

        fun page(renderer: PdfRenderer, index: Int): String {
            val bmp = render(renderer, index)
            try {
                val img = InputImage.fromBitmap(bmp, 0)
                return when (script) {
                    1 -> recognize(latin(), img)
                    2 -> recognize(chinese(), img)
                    3 -> recognize(japanese(), img)
                    else -> {
                        val l = recognize(latin(), img)
                        if (l.trim().length >= 40 && !looksCjkGarbage(l)) l
                        else listOf(l, recognize(chinese(), img), recognize(japanese(), img)).maxByOrNull { it.trim().length } ?: l
                    }
                }
            } finally {
                bmp.recycle()
            }
        }

        private fun looksCjkGarbage(s: String) = s.count { it == '?' } > s.length / 10

        private fun recognize(r: TextRecognizer, img: InputImage): String {
            // Play-services models may still be downloading on first use: retry a few times.
            var lastErr: Throwable? = null
            repeat(6) { attempt ->
                try {
                    return Tasks.await(r.process(img), 90, TimeUnit.SECONDS).text
                } catch (e: ExecutionException) {
                    lastErr = e.cause ?: e
                    val c = e.cause
                    if (c is MlKitException && c.errorCode == MlKitException.UNAVAILABLE) Thread.sleep(5000L * (attempt + 1))
                    else throw lastErr!!
                }
            }
            throw lastErr ?: IllegalStateException("OCR failed")
        }

        private fun render(renderer: PdfRenderer, index: Int): Bitmap {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = (1700f / page.width).coerceAtMost(3f)
                    var w = (page.width * scale).toInt().coerceAtLeast(1)
                    var h = (page.height * scale).toInt().coerceAtLeast(1)
                    if (h > 3000) { w = w * 3000 / h; h = 3000 }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }

        fun close() {
            listOf(latin, chinese, japanese).forEach { try { it?.close() } catch (_: Throwable) {} }
        }
    }
}
