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
    private var savedDate: String? = null
    private var savedMealId: Long? = null
    private var diagTail: TextView? = null
    private val onQueue = { runOnUiThread { rerender() } }
    private val onDiag = { runOnUiThread { updateDiagTail() } }

    /** Re-draw whichever state this screen is showing (saved day / saved meal / live job). */
    private fun rerender() = when {
        savedDate != null -> showSavedDay()
        savedMealId != null -> showSavedMeal()
        else -> bindJob()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()

        jobId = intent.getLongExtra("jobId", 0)

        // Decide what to show WITHOUT generating: prefer an in-flight job, then an already-saved
        // result. Only enqueue a fresh AI run when nothing is pending-approval or already approved.
        if (jobId == 0L) resolveTarget()
        if (jobId == 0L && savedDate != null) { setupSavedDay(savedDate!!); return }
        if (jobId == 0L && savedMealId != null) { setupSavedMeal(savedMealId!!); return }

        if (jobId == 0L) {
            val job = buildAndEnqueue() ?: return // finishes itself on error
            jobId = job.id
        }
        val job = ReviewQueue.job(jobId)
        if (job == null) { toast(tr("Không tìm thấy yêu cầu xem xét.", "Review request not found.")); finish(); return }

        val col = page(p, if (job.isDay) tr("Xem xét cả ngày", "Whole-day review") else tr("Xem xét bữa ăn", "Meal review"))
        col.addView(text(job.title, 14f, p.secondary).apply { setPadding(dp(4), 0, 0, dp(8)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        bindJob()
    }

    override fun onResume() {
        super.onResume()
        ReviewQueue.addListener(onQueue); DiagLog.addListener(onDiag)
        rerender()
    }
    override fun onPause() {
        super.onPause()
        ReviewQueue.removeListener(onQueue); DiagLog.removeListener(onDiag)
    }

    /**
     * Point this screen at an existing result instead of generating one: an in-flight job for the
     * target wins, then a saved review. Leaves [jobId]/[savedDate]/[savedMealId] all unset only when
     * there is genuinely nothing yet — in which case onCreate enqueues a new run.
     */
    private fun resolveTarget() {
        val savedExtra = intent.getStringExtra("savedDate")
        if (savedExtra != null && Store.loadDayReview(this, savedExtra).isNotBlank()) { savedDate = savedExtra; return }
        if (intent.hasExtra("mealId")) {
            val mid = intent.getLongExtra("mealId", 0)
            val inflight = ReviewQueue.inFlightForMeal(this, mid)
            when {
                inflight != null -> jobId = inflight.id
                Store.get(this, mid)?.reviewJson?.isNotBlank() == true -> savedMealId = mid
            }
        } else {
            val d = intent.getStringExtra("date") ?: savedExtra ?: Store.today()
            val inflight = ReviewQueue.inFlightForDay(this, d)
            when {
                Store.loadDayReview(this, d).isNotBlank() -> savedDate = d
                inflight != null -> jobId = inflight.id
            }
        }
    }

    private fun setupSavedDay(d: String) {
        val col = page(p, tr("Xem xét cả ngày", "Whole-day review"))
        col.addView(text("${tr("Cả ngày", "Whole day")} · ${Store.prettyDate(d)}", 14f, p.secondary)
            .apply { setPadding(dp(4), 0, 0, dp(8)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        showSavedDay()
    }

    private fun setupSavedMeal(mid: Long) {
        val m = Store.get(this, mid)
        if (m == null || m.reviewJson.isBlank()) { savedMealId = null; toast(tr("Không tìm thấy kết quả đã lưu.", "No saved result found.")); finish(); return }
        val col = page(p, tr("Xem xét bữa ăn", "Meal review"))
        col.addView(text(mealTitle(m), 14f, p.secondary).apply { setPadding(dp(4), 0, 0, dp(8)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        showSavedMeal()
    }

    private fun mealTitle(m: Meal): String = "Bữa ${m.typeLabel().lowercase()}" + if (m.time.isNotBlank()) " · ${m.time}" else ""
    private fun mealScope(m: Meal): String = "bữa ${m.typeLabel().lowercase()}"
    private fun mealText(m: Meal): String = buildString {
        append("Bữa ${m.typeLabel()}")
        if (m.time.isNotBlank()) append(" lúc ${m.time}")
        append(":\n").append(m.items)
        if (m.note.isNotBlank()) append("\nGhi chú: ${m.note}")
    }

    /** Legacy entry: turn mealId/date into a queued job (only reached when nothing exists yet). */
    private fun buildAndEnqueue(): ReviewQueue.Job? {
        if (intent.hasExtra("mealId")) {
            val m = Store.get(this, intent.getLongExtra("mealId", 0))
                ?: run { toast("Không tìm thấy bữa ăn."); finish(); return null }
            if (m.items.isBlank()) { toast("Bữa ăn chưa có món nào."); finish(); return null }
            return ReviewQueue.enqueueMeal(this, m, mealScope(m), mealText(m))
        }
        val d = intent.getStringExtra("date") ?: Store.today()
        if (Store.mealsForDate(this, d).isEmpty()) { toast(tr("Chưa có món ăn nào để xem xét.", "No food to review yet.")); finish(); return null }
        return ReviewQueue.enqueueDay(this, d, "cả ngày", buildDayMealsText(d))
    }

    /** The day's meals (+ water) as the text the AI reviews. */
    private fun buildDayMealsText(d: String): String {
        val meals = Store.mealsForDate(this, d)
        val sb = StringBuilder(meals.joinToString("\n") { m ->
            "• ${m.typeLabel()}${if (m.time.isNotBlank()) " (${m.time})" else ""}: " +
                m.items.replace("\n", ", ") + (if (m.note.isNotBlank()) " [${m.note}]" else "")
        })
        val water = Water.amount(this, d)
        if (water > 0) sb.append("\n\nNước uống trong ngày: $water ml (mục tiêu ${Water.targetMl(this)} ml).")
        return sb.toString()
    }

    private fun bindJob() {
        if (!::body.isInitialized) return
        diagTail = null
        val job = ReviewQueue.job(jobId) ?: run { showMessage(tr("Yêu cầu đã bị xoá khỏi hàng đợi.", "This request was removed from the queue.")); return }
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
        val job = ReviewQueue.job(jobId)
        if (job != null && ReviewQueue.approve(this, jobId)) {
            toast(tr("Đã duyệt & lưu kết quả.", "Approved & saved."))
            // Job đã được bỏ khỏi hàng đợi — chuyển sang xem bản đã lưu (vẫn xem/xem xét lại được).
            if (job.isDay) savedDate = job.date else savedMealId = job.mealId
            jobId = 0
            rerender()
        } else toast(tr("Không duyệt được (hãy chắc đang đúng người dùng).", "Couldn't approve (make sure the right user is active)."))
    }

    private fun openQueue() = startActivity(Intent(this, ReviewQueueActivity::class.java))
    private fun openDiag() = startActivity(Intent(this, DiagLogActivity::class.java))
    private fun engineLabel(e: String) = when (e) {
        "offline" -> tr("model offline", "offline model")
        "local" -> tr("ước tính thô (không AI)", "rough estimate (no AI)")
        "saved" -> tr("đã lưu", "saved")
        else -> "Gemini"
    }

    /** A previously-approved whole-day review loaded from the store (read-only + re-review). */
    private fun showSavedDay() {
        if (!::body.isInitialized) return
        val d = savedDate ?: return
        val json = Store.loadDayReview(this, d)
        if (json.isBlank()) { savedDate = null; finish(); return }
        val r = Ai.parse(json) ?: Ai.Review(0, 0, 0, 0, emptyList(), Ai.cleanForDisplay(json), emptyList(), emptyList(), json)
        body.removeAllViews()
        body.addView(banner(tr("Đã lưu — kết quả cả ngày đã tính vào calo.", "Saved — the whole-day result counts toward calories."),
            R.drawable.ic_check, p.green), vlp().apply { bottomMargin = dp(12) })
        renderResultCards(r, "saved")
        body.addView(bigIconButton(tr("Xoá & xem xét lại", "Delete & re-review"), R.drawable.ic_refresh, p.accent, p.accent2) {
            Store.clearDayReview(this, d); Store.clearDerivedMealReviews(this, d)
            val job = ReviewQueue.enqueueDay(this, d, "cả ngày", buildDayMealsText(d))
            savedDate = null; jobId = job.id; bindJob()
        }, vlp().apply { topMargin = dp(16) })
    }

    /** A previously-saved single-meal review loaded from the store (read-only + re-review). */
    private fun showSavedMeal() {
        if (!::body.isInitialized) return
        val mid = savedMealId ?: return
        val m = Store.get(this, mid)
        val json = m?.reviewJson.orEmpty()
        if (m == null || json.isBlank()) { savedMealId = null; finish(); return }
        val r = Ai.parse(json) ?: Ai.Review(0, 0, 0, 0, emptyList(), Ai.cleanForDisplay(json), emptyList(), emptyList(), json)
        val fromDay = runCatching { org.json.JSONObject(json).optBoolean("from_day", false) }.getOrDefault(false)
        body.removeAllViews()
        body.addView(banner(
            if (fromDay) tr("Đã lưu — tách từ xem xét cả ngày, đã tính vào calo.", "Saved — split from the whole-day review, counted toward calories.")
            else tr("Đã lưu — kết quả bữa này đã tính vào calo.", "Saved — this meal result counts toward calories."),
            R.drawable.ic_check, p.green), vlp().apply { bottomMargin = dp(12) })
        renderResultCards(r, "saved")
        body.addView(bigIconButton(tr("Xoá & xem xét lại", "Delete & re-review"), R.drawable.ic_refresh, p.accent, p.accent2) {
            m.reviewJson = ""; m.reviewAt = 0; Store.upsert(this, m)
            val job = ReviewQueue.enqueueMeal(this, m, mealScope(m), mealText(m))
            savedMealId = null; jobId = job.id; bindJob()
        }, vlp().apply { topMargin = dp(16) })
    }

    // ---- states ----
    private fun showProcessing(job: ReviewQueue.Job) {
        body.removeAllViews()
        val waiting = job.status == ReviewQueue.Status.QUEUED
        body.addView(card(p).apply {
            setPadding(dp(18), dp(22), dp(18), dp(22)); gravity = Gravity.CENTER_HORIZONTAL
            addView(WalkProgressView(this@ReviewActivity, p).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)).apply { bottomMargin = dp(10) }
            })
            addView(text(if (waiting) tr("Đang chờ trong hàng đợi…", "Waiting in the queue…") else tr("Đang phân tích bằng ${engineLabel(job.engine)}…", "Analyzing with ${engineLabel(job.engine)}…"),
                16f, p.text, 500).apply { gravity = Gravity.CENTER })
            addView(text(tr("Có thể thoát ra — kết quả vẫn chạy nền và lưu trong hàng đợi. Model offline lần đầu nạp có thể lâu.",
                "You can leave — it keeps running in the background and stays in the queue. The offline model can be slow on first load."),
                13f, p.secondary).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        })

        // live diagnostic tail — so the user can SEE which step it's on / where it stalls
        body.addView(sectionHeader(p, tr("Đang chạy ở bước", "Current step")))
        val tail = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE; setTextColor(p.secondary); setLineSpacing(0f, 1.1f)
        }
        diagTail = tail
        body.addView(card(p).apply { setPadding(dp(12), dp(12), dp(12), dp(12)); addView(tail) })
        updateDiagTail()

        body.addView(bigIconButton(tr("Xem hàng đợi", "View queue"), R.drawable.ic_list, p.indigo, p.blue) { openQueue() },
            vlp().apply { topMargin = dp(12) })
        body.addView(bigButton(tr("Huỷ yêu cầu này", "Cancel this request"), p.red, p.pink) { ReviewQueue.cancel(jobId); bindJob() },
            vlp().apply { topMargin = dp(10) })
    }

    private fun updateDiagTail() {
        val tail = diagTail ?: return
        val lines = DiagLog.snapshot().takeLast(8)
        tail.text = if (lines.isEmpty()) tr("(chưa có log)", "(no log yet)") else lines.joinToString("\n") { it.format() }
    }

    private fun showCancelled() {
        body.removeAllViews()
        body.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text(tr("Đã huỷ yêu cầu", "Request cancelled"), 17f, p.text, 700))
            addView(text(tr("Yêu cầu xem xét này đã bị huỷ.", "This review request was cancelled."), 14f, p.secondary).apply { setPadding(0, dp(6), 0, 0) })
        }, vlp().apply { bottomMargin = dp(12) })
        body.addView(bigIconButton(tr("Xem xét lại", "Review again"), R.drawable.ic_refresh, p.accent, p.accent2) { retry() })
    }

    private fun showMessage(msg: String) {
        body.removeAllViews()
        body.addView(card(p).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); addView(text(msg, 15f, p.text)) })
    }

    private fun showError(job: ReviewQueue.Job) {
        body.removeAllViews()
        body.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text(tr("Không xem xét được (Thất bại)", "Couldn't review (Failed)"), 17f, p.red, 700))
            addView(text(job.error.ifBlank { tr("Lỗi không rõ", "Unknown error") }, 14f, p.secondary).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        }, vlp().apply { bottomMargin = dp(12) })
        body.addView(bigIconButton(tr("Thử lại", "Try again"), R.drawable.ic_refresh, p.accent, p.accent2) { retry() }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton(tr("Xem nhật ký chẩn đoán", "View diagnostic log"), R.drawable.ic_list, p.secondary, p.secondary) { openDiag() }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton(tr("Dùng ước tính thô (không AI)", "Use rough estimate (no AI)"), R.drawable.ic_search, p.orange, p.orange) {
            showEstimate(LocalEstimator.estimate(ReviewQueue.job(jobId)?.mealsText ?: ""))
        }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton(tr("Mở Cài đặt AI", "Open AI settings"), R.drawable.ic_settings, p.indigo, p.blue) {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        }, vlp().apply { bottomMargin = dp(10) })
        body.addView(bigIconButton(tr("Xem hàng đợi", "View queue"), R.drawable.ic_list, p.indigo, p.blue) { openQueue() })
    }

    /** A review result with the approve / regenerate actions appropriate to the job's status. */
    private fun showResult(job: ReviewQueue.Job, r: Ai.Review, awaiting: Boolean) {
        body.removeAllViews()

        if (awaiting) body.addView(banner(tr("Có kết quả — xem rồi bấm “Duyệt” để lưu vào bữa ăn.", "Result ready — review it, then tap “Approve” to save."), R.drawable.ic_warning, p.orange),
            vlp().apply { bottomMargin = dp(12) })
        else body.addView(banner(tr("Đã duyệt — kết quả này đã được lưu và tính vào calo.", "Approved — this result is saved and counted."), R.drawable.ic_check, p.green),
            vlp().apply { bottomMargin = dp(12) })

        renderResultCards(r, job.engine)

        if (awaiting) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(bigIconButton(tr("Duyệt & lưu", "Approve & save"), R.drawable.ic_check, p.accent, p.accent2) { approve() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(bigIconButton(tr("Xoá & tạo lại", "Delete & redo"), R.drawable.ic_delete, p.red, p.pink) { retry() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            body.addView(actions, vlp().apply { topMargin = dp(16) })
        } else {
            body.addView(bigIconButton(tr("Xoá & xem xét lại", "Delete & re-review"), R.drawable.ic_refresh, p.accent, p.accent2) { retry() },
                vlp().apply { topMargin = dp(16) })
        }
    }

    /** The LocalEstimator fallback (no job to approve) — just show it with a re-review button. */
    private fun showEstimate(r: Ai.Review) {
        body.removeAllViews()
        body.addView(banner(tr("Ước tính thô theo bảng calo có sẵn — chưa dùng AI.", "Rough estimate from a built-in table — no AI used."), R.drawable.ic_warning, p.orange),
            vlp().apply { bottomMargin = dp(12) })
        renderResultCards(r, "local")
        body.addView(bigIconButton(tr("Xem xét lại bằng AI", "Re-review with AI"), R.drawable.ic_refresh, p.accent, p.accent2) { retry() },
            vlp().apply { topMargin = dp(16) })
    }

    private fun renderResultCards(r: Ai.Review, engine: String) {
        body.addView(card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(tr("Tổng calo (ước tính)", "Total calories (estimated)"), 13f, p.secondary))
            addView(text("${r.calories} kcal", 38f, p.accent, 700).apply { setPadding(0, dp(2), 0, dp(6)) })
            val target = Store.loadProfile(this@ReviewActivity).targetCalories()
            val rem = target - r.calories
            addView(text(if (rem >= 0) tr("≈ $rem kcal nữa là tới mục tiêu ngày (~$target)", "≈ $rem kcal to the daily target (~$target)")
                else tr("Vượt mục tiêu ngày ~${-rem} kcal (~$target)", "~${-rem} kcal over the daily target (~$target)"),
                13.5f, if (rem >= 0) p.secondary else p.orange, 500))
            val macros = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            macros.addView(macroTile(tr("Đạm", "Protein"), "${r.protein}g"))
            macros.addView(macroTile(tr("Tinh bột", "Carbs"), "${r.carb}g"))
            macros.addView(macroTile(tr("Chất béo", "Fat"), "${r.fat}g"))
            addView(macros, vlp())
        }, vlp().apply { bottomMargin = dp(12) })

        // macro split bar
        if (r.protein + r.carb + r.fat > 0) body.addView(MacroBar(this, p, r.protein, r.carb, r.fat),
            vlp().apply { bottomMargin = dp(4) })

        if (r.assessment.isNotBlank()) {
            body.addView(sectionHeader(p, tr("Nhận xét", "Assessment")))
            body.addView(card(p).apply { setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(r.assessment, 15f, p.text).apply { setLineSpacing(0f, 1.2f) }) })
        }

        if (r.items.isNotEmpty()) {
            body.addView(sectionHeader(p, tr("Chi tiết món", "Item breakdown")))
            body.addView(group(p, *r.items.map { (name, kcal) ->
                row(p, name.ifBlank { tr("(món)", "(item)") }, text("$kcal kcal", 14f, p.secondary, 500))
            }.toTypedArray()))
        }

        if (r.tips.isNotEmpty()) {
            body.addView(sectionHeader(p, tr("Gợi ý", "Tips")))
            body.addView(card(p).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                r.tips.forEach { addView(text("• $it", 14.5f, p.text).apply { setPadding(0, dp(5), 0, dp(5)); setLineSpacing(0f, 1.15f) }) }
            })
        }

        if (r.warnings.isNotEmpty()) {
            body.addView(sectionHeader(p, tr("Lưu ý sức khoẻ", "Health notes")))
            body.addView(card(p).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                r.warnings.forEach { addView(text("• $it", 14.5f, p.orange, 500).apply { setPadding(0, dp(5), 0, dp(5)); setLineSpacing(0f, 1.15f) }) }
            })
        }

        body.addView(footnote(p, tr("Nguồn: ${engineLabel(engine)}. Mọi con số là ước tính, không thay thế tư vấn dinh dưỡng/y tế chuyên môn.",
            "Source: ${engineLabel(engine)}. All numbers are estimates, not a substitute for professional nutrition/medical advice.")),
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
