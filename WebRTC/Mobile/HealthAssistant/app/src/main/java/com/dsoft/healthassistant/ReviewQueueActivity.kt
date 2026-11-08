package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/** The list of "Xem xét" requests with their status. Tap one to jump to it. */
class ReviewQueueActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private val onQueue = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Hàng đợi xem xét", "Review queue"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content)
        render()
    }

    override fun onResume() { super.onResume(); ReviewQueue.addListener(onQueue); render() }
    override fun onPause() { super.onPause(); ReviewQueue.removeListener(onQueue) }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        val jobs = ReviewQueue.jobs().sortedByDescending { it.createdAt }
        val active = jobs.count {
            it.status == ReviewQueue.Status.QUEUED || it.status == ReviewQueue.Status.RUNNING ||
                it.status == ReviewQueue.Status.AWAITING
        }

        content.addView(text(
            if (jobs.isEmpty()) tr("Chưa có yêu cầu nào. Bấm “Xem xét” ở một bữa hoặc cả ngày để thêm vào đây.",
                "No requests yet. Tap “Review” on a meal or the whole day to add one here.")
            else tr("$active đang xử lý/chờ duyệt · ${jobs.size} tất cả. Chạm một mục để xem.",
                "$active processing/pending · ${jobs.size} total. Tap an item to view."),
            14f, p.secondary).apply { setPadding(dp(4), 0, dp(4), dp(10)); setLineSpacing(0f, 1.15f) })

        if (jobs.isNotEmpty()) {
            content.addView(group(p, *jobs.map { jobRow(it) }.toTypedArray()))
            if (jobs.any { it.status.terminal() })
                content.addView(bigIconButton(tr("Xoá các mục đã xong", "Clear finished"), R.drawable.ic_delete, p.red, p.pink) {
                    ReviewQueue.clearFinished()
                }, vlp().apply { topMargin = dp(16) })
        }
    }

    private fun ReviewQueue.Status.terminal() =
        this == ReviewQueue.Status.APPROVED || this == ReviewQueue.Status.ERROR || this == ReviewQueue.Status.CANCELLED

    private fun jobRow(job: ReviewQueue.Job): LinearLayout {
        val (badge, badgeColor) = when (job.status) {
            ReviewQueue.Status.QUEUED -> tr("Đang chờ", "Queued") to p.secondary
            ReviewQueue.Status.RUNNING -> tr("Đang xử lý…", "Processing…") to p.orange
            ReviewQueue.Status.AWAITING -> tr("Chờ duyệt", "To approve") to p.blue
            ReviewQueue.Status.APPROVED -> ((Ai.parse(job.resultJson)?.calories?.let { "$it kcal ✓" }) ?: tr("Đã duyệt", "Approved")) to p.green
            ReviewQueue.Status.ERROR -> tr("Thất bại", "Failed") to p.red
            ReviewQueue.Status.CANCELLED -> tr("Đã huỷ", "Cancelled") to p.secondary
        }
        val tileColors = when (job.status) {
            ReviewQueue.Status.APPROVED -> intArrayOf(p.green, p.teal)
            ReviewQueue.Status.AWAITING -> intArrayOf(p.blue, p.indigo)
            ReviewQueue.Status.ERROR -> intArrayOf(p.red, p.pink)
            ReviewQueue.Status.RUNNING -> intArrayOf(p.orange, p.orange)
            else -> intArrayOf(p.secondary, p.secondary)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60); setPadding(dp(14), dp(8), dp(12), dp(8))
            background = pressable(null, p.ripple)
            setOnClickListener {
                startActivity(Intent(this@ReviewQueueActivity, ReviewActivity::class.java).putExtra("jobId", job.id))
            }
            addView(ImageView(context).apply {
                setImageResource(if (job.isDay) R.drawable.ic_search else R.drawable.ic_restaurant)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val pd = dp(7); setPadding(pd, pd, pd, pd)
                background = gradient(dpf(10), tileColors[0], tileColors[1]); clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(job.title, 16f, p.text, 500).apply { maxLines = 1 })
                addView(text("${engineLabel(job.engine)} · $badge", 13f, badgeColor, 500).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron(p))
        }
    }

    private fun engineLabel(e: String) = when (e) { "offline" -> tr("Model offline", "Offline model"); else -> "Gemini" }
}
