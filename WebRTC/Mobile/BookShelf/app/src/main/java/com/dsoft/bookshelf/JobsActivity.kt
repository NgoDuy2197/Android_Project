package com.dsoft.bookshelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** All jobs (extract / translate / MP3) with live progress and Continue / Pause / Cancel / Open result. */
class JobsActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private var filterBook: String? = null
    private val cards = HashMap<String, CardViews>()
    private var shownIds: List<String> = emptyList()
    private val listener: (Job?) -> Unit = { j -> if (j != null && cards[j.id]?.lastStatus == j.status) update(j) else rebuild() }

    private class CardViews(val root: View, val bar: ProgressBar, val progress: TextView, val statusText: TextView,
                            val buttons: LinearLayout, var lastStatus: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filterBook = intent.getStringExtra(Readers.EXTRA_BOOK)
        val col = page(p, getString(R.string.jobs_title), getString(R.string.back))
        filterBook?.let { id ->
            Library.get(id)?.let { b -> col.addView(footnote(p, getString(R.string.jobs_for_book, b.title)).apply { setPadding(dp(4), 0, 0, dp(8)) }) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, vlp())
        col.addView(footnote(p, getString(R.string.jobs_footer)))
        if (filterBook != null) {
            col.addView(pillButton(getString(R.string.jobs_show_all), p.fill, p.blue) {
                startActivity(Intent(this, JobsActivity::class.java)); finish()
            }, vlp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        }
        rebuild()
        JobManager.addListener(listener)
    }

    override fun onDestroy() {
        JobManager.removeListener(listener)
        super.onDestroy()
    }

    private fun jobs() = JobManager.all().filter { filterBook == null || it.bookId == filterBook }

    private fun rebuild() {
        val js = jobs()
        list.removeAllViews()
        cards.clear()
        shownIds = js.map { it.id }
        if (js.isEmpty()) {
            list.addView(card(p).apply {
                addView(text(getString(R.string.jobs_empty), 15f, p.secondary).apply {
                    setPadding(dp(16), dp(22), dp(16), dp(22)); gravity = Gravity.CENTER
                }, vlp())
            }, vlp())
            return
        }
        js.forEach { j ->
            val cv = makeCard(j)
            cards[j.id] = cv
            list.addView(cv.root, vlp().apply { bottomMargin = dp(12) })
            update(j)
        }
    }

    private fun makeCard(j: Job): CardViews {
        val root = card(p).apply { setPadding(dp(16), dp(14), dp(16), dp(12)) }
        val (iconRes, color) = when (j.type) {
            JobType.EXTRACT -> R.drawable.ic_doc_text to p.teal
            JobType.TRANSLATE -> R.drawable.ic_translate to p.indigo
            else -> R.drawable.ic_headphones to p.orange
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                val pad = dp(6); setPadding(pad, pad, pad, pad)
                background = rounded(color, dpf(9))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(j.title, 16f, p.text, 600).apply { maxLines = 2 })
                addView(text(detailLine(j), 12.5f, p.secondary).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        val bar = progressBar(p)
        root.addView(bar, vlp().apply { topMargin = dp(12) })
        val progress = text("", 13f, p.text, 500).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(progress)
        val status = text("", 13f, p.secondary).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.15f) }
        root.addView(status)
        val buttons = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }, vlp())
        return CardViews(root, bar, progress, status, buttons, j.status)
    }

    private fun detailLine(j: Job): String = when (j.type) {
        JobType.TRANSLATE -> {
            val engine = when (j.engine) { Engines.GOOGLE -> getString(R.string.engine_google); Engines.GEMINI -> getString(R.string.engine_gemini); else -> getString(R.string.engine_mlkit) }
            val src = if (j.src == Langs.AUTO) getString(R.string.lang_auto) else Langs.name(j.src)
            "$engine · $src → ${Langs.name(j.tgt)}"
        }
        JobType.AUDIO -> "MP3 · ${j.unitChars.takeIf { it > 0 } ?: 64} kbps"
        else -> getString(R.string.extract_detail)
    }

    private fun update(j: Job) {
        val cv = cards[j.id] ?: return
        cv.lastStatus = j.status
        cv.bar.isIndeterminate = j.status == JobStatus.RUNNING && j.total <= 0
        cv.bar.progress = (j.fraction * 1000).toInt()
        cv.progress.text = "${JobService.unitLabel(this, j)} · ${j.percent}%" +
            if (j.skipped > 0) " · " + getString(R.string.skipped_units, j.skipped) else ""
        val (label, color) = when (j.status) {
            JobStatus.RUNNING -> getString(R.string.st_running) to p.blue
            JobStatus.QUEUED -> getString(R.string.st_queued) to p.secondary
            JobStatus.PAUSED -> getString(R.string.st_paused) to p.orange
            JobStatus.ERROR -> getString(R.string.st_error) to p.red
            JobStatus.DONE -> getString(R.string.st_done) to p.green
            else -> getString(R.string.st_cancelled) to p.secondary
        }
        val extra = listOfNotNull(
            j.message.takeIf { it.isNotEmpty() && j.status == JobStatus.RUNNING },
            j.error.takeIf { it.isNotEmpty() && (j.status == JobStatus.ERROR || j.status == JobStatus.PAUSED) },
        ).joinToString("\n")
        val time = DateUtils.getRelativeTimeSpanString(j.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        cv.statusText.text = "● $label · $time" + if (extra.isNotEmpty()) "\n$extra" else ""
        cv.statusText.setTextColor(color)

        cv.buttons.removeAllViews()
        fun add(res: Int, c: Int, tc: Int = android.graphics.Color.WHITE, f: () -> Unit) =
            cv.buttons.addView(pillButton(getString(res), c, tc, f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        if (j.canResume) add(R.string.btn_continue, p.blue) { JobManager.resume(this, j.id) }
        if (j.canResume && j.type == JobType.TRANSLATE && j.engine == Engines.MLKIT && j.src != Langs.AUTO)
            add(R.string.btn_download_model, p.fill, p.blue) { downloadModelDialog(j) }
        if (j.canPause) add(R.string.btn_pause, p.fill, p.text) { JobManager.pause(j.id) }
        if (!j.isFinished) add(R.string.btn_cancel, p.fill, p.red) { confirmCancel(j) }
        if (hasResult(j)) add(R.string.btn_open_result, p.green) { openResult(j) }
        if (j.type == JobType.AUDIO && j.status == JobStatus.DONE) add(R.string.btn_share, p.fill, p.blue) { share(j) }
        if (j.isFinished) add(R.string.btn_remove, p.fill, p.secondary) { JobManager.remove(j.id) }
    }

    private fun confirmCancel(j: Job) {
        dialogBuilder(p).setTitle(R.string.btn_cancel).setMessage(R.string.cancel_confirm)
            .setPositiveButton(R.string.btn_cancel) { _, _ -> JobManager.cancel(j.id) }
            .setNegativeButton(R.string.back, null).show()
    }

    /** Explicit model download with its own progress popup (ML Kit gives no fine progress: shown indeterminate). */
    private fun downloadModelDialog(j: Job) {
        val langs = MlKitModels.requiredTags(j.src, j.tgt).joinToString(", ") { Langs.name(it) }
        val status = text(getString(R.string.model_download_ready, langs), 14f, p.text).apply { setLineSpacing(0f, 1.2f) }
        val spinner = android.widget.ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        val cellCheck = android.widget.CheckBox(this).apply {
            text = getString(R.string.allow_cellular)
            setTextColor(p.text); textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(p.blue)
            isChecked = j.cell
        }
        val col = dialogColumn().apply {
            addView(status, vlp())
            addView(spinner, vlp().apply { topMargin = dp(14) })
            addView(cellCheck, vlp().apply { topMargin = dp(10) })
        }
        val dlg = dialogBuilder(p).setTitle(R.string.download_model).setView(col)
            .setPositiveButton(R.string.download_model, null)
            .setNegativeButton(R.string.back, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val allowCell = cellCheck.isChecked
                if (allowCell && !j.cell) JobManager.put(JobManager.get(j.id)?.copy(cell = true) ?: return@setOnClickListener)
                status.text = getString(R.string.model_downloading)
                spinner.visibility = View.VISIBLE
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                cellCheck.isEnabled = false
                uiScope.launch {
                    val err = withContext(Dispatchers.IO) {
                        try { MlKitModels.download(j.src, j.tgt, wifiOnly = !allowCell); null }
                        catch (e: Exception) { e.cause?.message ?: e.message ?: e.javaClass.simpleName }
                    }
                    spinner.visibility = View.GONE
                    if (err == null) {
                        status.text = getString(R.string.model_ready)
                        toast(R.string.model_ready)
                        dlg.dismiss()
                        JobManager.resume(this@JobsActivity, j.id)
                    } else {
                        status.text = getString(R.string.err_model_download, err)
                        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        cellCheck.isEnabled = true
                    }
                }
            }
        }
        dlg.show()
    }

    private fun hasResult(j: Job) = when (j.type) {
        JobType.AUDIO -> j.status == JobStatus.DONE && (j.outUri.isNotEmpty() || File(j.outPath).exists())
        else -> j.outBookId.isNotEmpty() && Library.get(j.outBookId) != null && j.index > 0
    }

    private fun audioUri(j: Job): Uri = if (j.outUri.isNotEmpty()) Uri.parse(j.outUri)
    else FileProvider.getUriForFile(this, "$packageName.files", File(j.outPath))

    private fun openResult(j: Job) {
        if (j.type == JobType.AUDIO) {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(audioUri(j), "audio/mpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.btn_open_result)))
            } catch (_: Exception) { toast(R.string.err_no_player) }
        } else {
            Library.get(j.outBookId)?.let { Readers.open(this, it) }
        }
    }

    private fun share(j: Job) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, audioUri(j)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.btn_share)))
        } catch (_: Exception) {}
    }
}
