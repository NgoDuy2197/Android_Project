package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shows one review [ReviewQueue.Job]: live "đang xử lý" state while it runs (with a diagnostic
 * tail so you can see which step it's on), then the result to approve. Opened either with a
 * "jobId", or with "mealId"/"date" (which it enqueues — reusing the in-flight job for that target,
 * so tapping twice never doubles a request).
 */
class ReviewActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var body: LinearLayout
    private var jobId: Long = 0
    private var diagTail: TextView? = null
    private val onQueue = { runOnUiThread { bindJob() } }
    private val onDiag = { runOnUiThread { updateDiagTail() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()

        jobId = intent.getLongExtra("jobId", 0)
        if (jobId == 0L) {
            val job = buildAndEnqueue() ?: return // finishes itself on error
            jobId = job.id
        }
        val job = ReviewQueue.job(jobId)
        if (job == null) { toast("Không tìm thấy yêu cầu xem xét."); finish(); return }

        val col = page(p, if (job.isDay) "Xem xét cả ngày" else "Xem xét bữa ăn")
        col.addView(text(job.title, 14f, p.secondary).apply { setPadding(dp(4), 0, 0, dp(8)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        bindJob()
    }

    override fun onResume() {
        super.onResume()
        ReviewQueue.addListener(onQueue); DiagLog.addListener(onDiag); bindJob()
    }
    override fun onPause() {
        super.onPause()
        ReviewQueue.removeListener(onQueue); DiagLog.removeListener(onDiag)
    }

    /** Legacy entry: turn mealId/date into a queued job. */
    private fun buildAndEnqueue(): ReviewQueue.Job? {
        if (intent.hasExtra("mealId")) {
            val m = Store.get(this, intent.getLongExtra("mealId", 0))
                ?: run { toast("Không tìm thấy bữa ăn."); finish(); return null }
            val scopeLabel = "bữa ${m.typeLabel().lowercase()}"
            val mealsText = buildString {
                append("Bữa ${m.typeLabel()}")
                if (m.time.isNotBlank()) append(" lúc ${m.time}")
                append(":\n").append(m.items)
                if (m.note.isNotBlank()) append("\nGhi chú: ${m.note}")
            }
            if (m.items.isBlank()) { toast("Bữa ăn chưa có món nào."); finish(); return null }
            return ReviewQueue.enqueueMeal(this, m, scopeLabel, mealsText)
        }
        val d = intent.getStringExtra("date") ?: Store.today()
        val meals = Store.mealsForDate(this, d)
        val mealsText = meals.joinToString("\n") { m ->
            "• ${m.typeLabel()}${if (m.time.isNotBlank()) " (${m.time})" else ""}: " +
                m.items.replace("\n", ", ") + (if (m.note.isNotBlank()) " [${m.note}]" else "")
        }
        if (mealsText.isBlank()) { toast("Chưa có món ăn nào để xem xét."); finish(); return null }
        return ReviewQueue.enqueueDay(this, d, "cả ngày", mealsText)
    }

    private fun bindJob() {
        if (!::body.isInitialized) return
        diagTail = null
        val job = ReviewQueue.job(jobId) ?: run { showMessage("Yêu cầu đã bị xoá khỏi hàng đợi."); return }
        when (job.status) {
            ReviewQueue.Status.QUEUED, ReviewQueue.Status.RUNNING -> showProcessing(job)
            ReviewQueue.Status.AWAITING -> showResult(job, parsed(job), awaiting = true)
            ReviewQueue.Status.APPROVED -> showResult(job, parsed(job), awaiting = false)
            ReviewQueue.Status.ERROR -> showError(job)
            ReviewQueue.Status.CANCELLED -> showCancelled()
        }
    }

    private fun parsed(job: ReviewQueue.Job): Ai.Review =
        Ai.parse(job.resultJson) ?: Ai.Review(0, 0, 0, 0, emptyList(), Ai.cleanForDisplay(job.resultJson), emptyList(), emptyList(), job.resultJson)

    /** Re-run: delete the old job and enqueue a fresh one for the same target. */
    private fun retry() {
        val job = ReviewQueue.job(jobId) ?: return
        ReviewQueue.delete(job.id)
        val newJob = if (job.isDay) ReviewQueue.enqueueDay(this, job.date, job.scopeLabel, job.mealsText)
        else Store.get(this, job.mealId)?.let { ReviewQueue.enqueueMeal(this, it, job.scopeLabel, job.mealsText) }
        if (newJob != null) { jobId = newJob.id; bindJob() } else toast("Không tìm thấy bữa ăn để xem lại.")
    }

    private fun approve() {
        if (ReviewQueue.approve(this, jobId)) { toast("Đã duyệt & lưu kết quả."); bindJob() }
        else toast("Không duyệt được (hãy chắc đang đúng người dùng).")
    }

    private fun openQueue() = startActivity(Intent(this, ReviewQueueActivity::class.java))
    private fun openDiag() = startActivity(Intent(this, DiagLogActivity::class.java))
    private fun engineLabel(e: String) = when (e) { "offline" -> "model offline"; "local" -> "ước tính thô (không AI)"; else -> "Gemini" }

    // ---- states ----
    private fun showProcessing(job: ReviewQueue.Job) {
        body.removeAllViews()
        val waiting = job.status == ReviewQueue.Status.QUEUED
        body.addView(card(p).apply {
            setPadding(dp(18), dp(22), dp(18), dp(22)); gravity = Gravity.CENTER_HORIZONTAL
            addView(WalkProgressView(this@ReviewActivity, p).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)).apply { bottomMargin = dp(10) }
            })
            addView(text(if (waiting) "Đang chờ trong hàng đợi…" else "Đang phân tích bằng ${engineLabel(job.engine)}…",
                16f, p.text, 500).apply { gravity = Gravity.CENTER })
            addView(text("Có thể thoát ra — kết quả vẫn chạy nền và lưu trong hàng đợi. Model offline lần đầu nạp có thể lâu.",
                13f, p.secondary).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        })

        // live diagnostic tail — so the user can SEE which step it's on / where it stalls
        body.addView(sectionHeader(p, "Đang chạy ở bước"))
        val tail = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE; setTextColor(p.secondary); setLineSpacing(0f, 1.1f)
        }
        diagTail = tail
        body.addView(card(p).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); addView(tail) })
        updateDiagTail()

        body.addView(bigIconButton("Xem hàng đợi", R.drawable.ic_list, p.indigo, p.blue) { openQueue() },
            vlp().apply { topMargin = dp(12) })
        body.addView(bigButton("Huỷ yêu cầu này", p.red, p.pink) { ReviewQueue.cancel(jobId); bindJob() },
            vlp().apply { topMargin = dp(10) })
    }

    private fun updateDiagTail() {
        val tail = diagTail ?: return
        val lines = DiagLog.snapshot().takeLast(8)
        tail.text = if (lines.isEmpty()) "(chưa có log)" else lines.joinToString("\n") { it.format() }
    }

    private fun showCancelled() {
        body.removeAllViews()
        body.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text("Đã huỷ yêu cầu", 17f, p.text, 700))
            addView(text("Yêu cầu xem xét này đã bị huỷ.", 14f, p.secondary).apply { setPadding(0, dp(6), 0, 0) })
        }, vlp().apply { bottomMargin = dp(12) })
        body.addView(bigIconButton("Xem xét lại", R.drawable.ic_refresh, p.green, p.teal) { retry() })
    }

    private fun showMessage(msg: String) {
        body.removeAllViews()
        body.addView(card(p).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); addView(text(msg, 15f, p.text)) })
    }

    private fun showError(job: ReviewQueue.Job) {
        body.removeAllViews()
        body.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text("Không xem xét được (Thất bại)", 17f, p.red, 700))
            addView(text(job.error.ifBlank { "Lỗi không rõ" }, 14f, p.secondary).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        }, vlp().apply { bottomMargin = dp(12) })
        body.addView(bigIconButton("Thử lại", R.drawable.ic_refresh, p.green, p.teal) { retry() }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton("Xem nhật ký chẩn đoán", R.drawable.ic_list, p.secondary, p.secondary) { openDiag() }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton("Dùng ước tính thô (không AI)", R.drawable.ic_search, p.orange, p.orange) {
            showEstimate(LocalEstimator.estimate(ReviewQueue.job(jobId)?.mealsText ?: ""))
        }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton("Mở Cài đặt AI", R.drawable.ic_settings, p.indigo, p.blue) {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton("Xem hàng đợi", R.drawable.ic_list, p.indigo, p.blue) { openQueue() })
    }

    /** A review result with the approve / regenerate actions appropriate to the job's status. */
    private fun showResult(job: ReviewQueue.Job, r: Ai.Review, awaiting: Boolean) {
        body.removeAllViews()

        if (awaiting) body.addView(banner("Có kết quả — xem rồi bấm “Duyệt” để lưu vào bữa ăn.", R.drawable.ic_warning, p.orange),
            vlp().apply { bottomMargin = dp(12) })
        else body.addView(banner("Đã duyệt — kết quả này đã được lưu và tính vào calo.", R.drawable.ic_check, p.green),
            vlp().apply { bottomMargin = dp(12) })

        renderResultCards(r, job.engine)

        if (awaiting) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(bigIconButton("Duyệt & lưu", R.drawable.ic_check, p.green, p.teal) { approve() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(bigIconButton("Xoá & tạo lại", R.drawable.ic_delete, p.red, p.pink) { retry() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            body.addView(actions, vlp().apply { topMargin = dp(16) })
        } else {
            body.addView(bigIconButton("Xoá & xem xét lại", R.drawable.ic_refresh, p.green, p.teal) { retry() },
                vlp().apply { topMargin = dp(16) })
        }
    }

    /** The LocalEstimator fallback (no job to approve) — just show it with a re-review button. */
    private fun showEstimate(r: Ai.Review) {
        body.removeAllViews()
        body.addView(banner("Ước tính thô theo bảng calo có sẵn — chưa dùng AI.", R.drawable.ic_warning, p.orange),
            vlp().apply { bottomMargin = dp(12) })
        renderResultCards(r, "local")
        body.addView(bigIconButton("Xem xét lại bằng AI", R.drawable.ic_refresh, p.green, p.teal) { retry() },
            vlp().apply { topMargin = dp(16) })
    }

    private fun renderResultCards(r: Ai.Review, engine: String) {
        body.addView(card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text("Tổng calo (ước tính)", 13f, p.secondary))
            addView(text("${r.calories} kcal", 38f, p.green, 700).apply { setPadding(0, dp(2), 0, dp(6)) })
            val target = Store.loadProfile(this@ReviewActivity).targetCalories()
            val rem = target - r.calories
            addView(text(if (rem >= 0) "≈ $rem kcal nữa là tới mục tiêu ngày (~$target)" else "Vượt mục tiêu ngày ~${-rem} kcal (~$target)",
                13.5f, if (rem >= 0) p.secondary else p.orange, 500))
            val macros = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            macros.addView(macroTile("Đạm", "${r.protein}g"))
            macros.addView(macroTile("Tinh bột", "${r.carb}g"))
            macros.addView(macroTile("Chất béo", "${r.fat}g"))
            addView(macros, vlp())
        }, vlp().apply { bottomMargin = dp(12) })

        // macro split bar
        if (r.protein + r.carb + r.fat > 0) body.addView(MacroBar(this, p, r.protein, r.carb, r.fat),
            vlp().apply { bottomMargin = dp(4) })

        if (r.assessment.isNotBlank()) {
            body.addView(sectionHeader(p, "Nhận xét"))
            body.addView(card(p).apply { setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(r.assessment, 15f, p.text).apply { setLineSpacing(0f, 1.2f) }) })
        }

        if (r.items.isNotEmpty()) {
            body.addView(sectionHeader(p, "Chi tiết món"))
            body.addView(group(p, *r.items.map { (name, kcal) ->
                row(p, name.ifBlank { "(món)" }, text("$kcal kcal", 14f, p.secondary, 500))
            }.toTypedArray()))
        }

        if (r.tips.isNotEmpty()) {
            body.addView(sectionHeader(p, "Gợi ý"))
            body.addView(card(p).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                r.tips.forEach { addView(text("• $it", 14.5f, p.text).apply { setPadding(0, dp(5), 0, dp(5)); setLineSpacing(0f, 1.15f) }) }
            })
        }

        if (r.warnings.isNotEmpty()) {
            body.addView(sectionHeader(p, "Lưu ý sức khoẻ"))
            body.addView(card(p).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                r.warnings.forEach { addView(text("• $it", 14.5f, p.orange, 500).apply { setPadding(0, dp(5), 0, dp(5)); setLineSpacing(0f, 1.15f) }) }
            })
        }

        body.addView(footnote(p, "Nguồn: ${engineLabel(engine)}. Mọi con số là ước tính, không thay thế tư vấn dinh dưỡng/y tế chuyên môn."),
            vlp().apply { topMargin = dp(16) })
    }

    private fun banner(msg: String, iconRes: Int, color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = rounded((color and 0x00FFFFFF) or 0x22000000, dpf(12))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(icon(iconRes, color, 18).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        addView(text(msg, 13.5f, color, 500).apply { setLineSpacing(0f, 1.1f) })
    }

    private fun macroTile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(text(value, 18f, p.text, 700))
        addView(text(label, 12f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
    }
}
