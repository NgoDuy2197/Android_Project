package com.dsoft.autotap

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Manual editor for one scenario: edit name/repeat/delay and the step list. */
class ScenarioEditorActivity : android.app.Activity() {
    private lateinit var p: Palette
    private lateinit var store: Store
    private lateinit var scenario: Scenario
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        store = Store(this)
        val id = intent.getStringExtra("id")
        val s = id?.let { store.byId(it) }
        if (s == null) { finish(); return }
        scenario = s
        build()
    }

    override fun onResume() {
        super.onResume()
        AutoClickService.listener = { runOnUiThread { build() } }
        // Re-load in case Record mode appended steps while we were away.
        store.byId(scenario.id)?.let { scenario = it }
        build()
    }

    override fun onPause() {
        super.onPause()
        AutoClickService.listener = null
    }

    private fun save() = store.upsert(scenario)

    private fun build() {
        content = page(p, "Sửa kịch bản")

        // ---- scenario settings ----
        content.addView(group(p,
            row(p, "Tên", inlineField(p, scenario.name, wide = true) { scenario.name = it.ifBlank { "Kịch bản" }; save() }),
            row(p, "Lặp lại (số lần)", inlineField(p, scenario.repeatCount.toString(), numeric = true) {
                scenario.repeatCount = it.toIntOrNull()?.coerceIn(1, 100000) ?: 1; save()
            }),
            row(p, "Chờ mặc định giữa bước (ms)", inlineField(p, scenario.defaultDelayMs.toString(), numeric = true) {
                scenario.defaultDelayMs = it.toLongOrNull()?.coerceIn(0, 60000) ?: 300; save()
            }),
        ))

        // ---- actions ----
        content.addView(sectionHeader(p, "Chạy"))
        content.addView(group(p,
            row(p, if (AutoClickService.running) "■ Dừng" else "▶ Chạy thử", null) { testRun() },
        ))

        // ---- steps ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionHeader(p, "Các bước (${scenario.steps.size})"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(circleButton(p, R.drawable.ic_add, p.blue, 34) { addStepDialog() })
        }
        content.addView(header)

        if (scenario.steps.isEmpty()) {
            content.addView(group(p, row(p, "Chưa có bước nào. Chạm + để thêm, hoặc Ghi thao tác.", null, null)))
        } else {
            val g = card(p)
            scenario.steps.forEachIndexed { i, step ->
                if (i > 0) g.addView(separator(p))
                g.addView(stepRow(i, step))
            }
            content.addView(g)
        }

        content.addView(bigButton("Ghi thao tác vào kịch bản", p.pink, p.purple) { record() },
            vlp().apply { topMargin = dp(18) })
        content.addView(footnote(p, "Ghi thao tác: lớp phủ sẽ ghi lại các lần chạm/vuốt của bạn. " +
            "Khi ghi, ứng dụng bên dưới KHÔNG phản hồi — bạn chỉ đang chỉ ra vị trí; lúc chạy AutoTap mới thực sự bấm."))
    }

    private fun stepRow(index: Int, step: Step): View {
        val num = text("${index + 1}", 13f, p.secondary, 700).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val info = text(step.describe(), 16f, p.text).apply {
            background = pressable(null, p.ripple)
            setOnClickListener { editStep(index, step) }
        }
        val up = circleButton(p, R.drawable.ic_up, p.secondary, 32) { move(index, -1) }
        val down = circleButton(p, R.drawable.ic_down, p.secondary, 32) { move(index, +1) }
        val del = circleButton(p, R.drawable.ic_trash, p.red, 32) { scenario.steps.removeAt(index); save(); build() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(8), dp(4), dp(6), dp(4))
            addView(num)
            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(up); addView(down); addView(del)
        }
    }

    private fun move(index: Int, dir: Int) {
        val j = index + dir
        if (j < 0 || j >= scenario.steps.size) return
        val t = scenario.steps[index]
        scenario.steps[index] = scenario.steps[j]
        scenario.steps[j] = t
        save(); build()
    }

    // ---- add / edit step ----

    private val typeLabels = linkedMapOf(
        Step.TAP to "Chạm (x, y)",
        Step.LONG_PRESS to "Giữ lâu (x, y)",
        Step.SWIPE to "Vuốt / kéo",
        Step.WAIT to "Chờ (ms)",
        Step.TAP_TEXT to "Chạm theo chữ (OCR)",
        Step.INPUT to "Nhập chữ",
        Step.BACK to "Nút Back",
        Step.HOME to "Nút Home",
        Step.RECENTS to "Ứng dụng gần đây",
    )

    private fun addStepDialog() {
        val types = typeLabels.keys.toList()
        val labels = typeLabels.values.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Thêm bước")
            .setItems(labels) { _, which -> configureStep(types[which], null) }
            .show()
    }

    private fun editStep(index: Int, step: Step) = configureStep(step.type, index)

    private fun num(value: Long): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(if (value == 0L) "" else value.toString())
        setTextColor(p.text)
    }

    private fun labeled(label: String, field: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(2))
        addView(text(label, 13f, p.secondary))
        addView(field)
    }

    private fun configureStep(type: String, editIndex: Int?) {
        val old = editIndex?.let { scenario.steps[it] }
        // instant types
        if (type == Step.BACK || type == Step.HOME || type == Step.RECENTS) {
            commit(Step(type), editIndex); return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        when (type) {
            Step.TAP -> {
                val fx = num((old?.x ?: 0).toLong()); val fy = num((old?.y ?: 0).toLong())
                box.addView(labeled("X", fx)); box.addView(labeled("Y", fy))
                box.addView(pickButton(fx, fy))
                show("Chạm (x, y)", box) { commit(Step.tap(fx.int(), fy.int()), editIndex) }
            }
            Step.LONG_PRESS -> {
                val fx = num((old?.x ?: 0).toLong()); val fy = num((old?.y ?: 0).toLong())
                val fd = num(old?.durationMs ?: 800)
                box.addView(labeled("X", fx)); box.addView(labeled("Y", fy)); box.addView(labeled("Thời gian giữ (ms)", fd))
                box.addView(pickButton(fx, fy))
                show("Giữ lâu", box) { commit(Step.longPress(fx.int(), fy.int(), fd.long()), editIndex) }
            }
            Step.SWIPE -> {
                val fx = num((old?.x ?: 0).toLong()); val fy = num((old?.y ?: 0).toLong())
                val fx2 = num((old?.x2 ?: 0).toLong()); val fy2 = num((old?.y2 ?: 0).toLong())
                val fd = num(old?.durationMs ?: 300)
                box.addView(labeled("X bắt đầu", fx)); box.addView(labeled("Y bắt đầu", fy))
                box.addView(pickButton(fx, fy))
                box.addView(labeled("X kết thúc", fx2)); box.addView(labeled("Y kết thúc", fy2))
                box.addView(pickButton(fx2, fy2))
                box.addView(labeled("Thời gian (ms)", fd))
                show("Vuốt / kéo", box) { commit(Step.swipe(fx.int(), fy.int(), fx2.int(), fy2.int(), fd.long()), editIndex) }
            }
            Step.WAIT -> {
                val fd = num(old?.durationMs ?: 500)
                box.addView(labeled("Chờ (ms)", fd))
                show("Chờ", box) { commit(Step.wait(fd.long()), editIndex) }
            }
            Step.TAP_TEXT -> {
                val ft = EditText(this).apply { setText(old?.text ?: ""); setTextColor(p.text) }
                val fo = num((old?.occurrence ?: 0).toLong())
                box.addView(labeled("Chữ cần tìm (hỗ trợ {{tham_số}})", ft))
                box.addView(labeled("Lần xuất hiện thứ (0 = đầu tiên)", fo))
                show("Chạm theo chữ (OCR)", box) {
                    commit(Step.tapText(ft.text.toString().trim(), fo.int()), editIndex)
                }
            }
            Step.INPUT -> {
                val ft = EditText(this).apply { setText(old?.text ?: ""); setTextColor(p.text) }
                box.addView(labeled("Nội dung (hỗ trợ {{tham_số}})", ft))
                show("Nhập chữ", box) { commit(Step.input(ft.text.toString()), editIndex) }
            }
        }
    }

    private fun pickButton(fx: EditText, fy: EditText): View =
        text("Chọn trên màn hình", 15f, p.blue, 500).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                if (!Access.canOverlay(this@ScenarioEditorActivity)) {
                    toast("Cần quyền hiển thị trên ứng dụng khác")
                    Access.requestOverlay(this@ScenarioEditorActivity)
                    return@setOnClickListener
                }
                PointPickerService.onPicked = { x, y ->
                    runOnUiThread { fx.setText(x.toString()); fy.setText(y.toString()); toast("Đã chọn ($x, $y)") }
                }
                PointPickerService.start(this@ScenarioEditorActivity)
                toast("Chạm vào vị trí mong muốn…")
                moveTaskToBack(true)
            }
        }

    private fun show(title: String, view: View, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ -> onOk() }
            .show()
    }

    private fun commit(step: Step, editIndex: Int?) {
        if (editIndex != null) scenario.steps[editIndex] = step else scenario.steps.add(step)
        save(); build()
    }

    private fun EditText.int(): Int = text.toString().trim().toIntOrNull() ?: 0
    private fun EditText.long(): Long = text.toString().trim().toLongOrNull() ?: 0

    private fun record() {
        if (!Access.canOverlay(this)) { toast("Cần quyền hiển thị trên ứng dụng khác"); Access.requestOverlay(this); return }
        RecordService.start(this, scenario.id)
        moveTaskToBack(true)
    }

    private fun testRun() {
        if (AutoClickService.running) { AutoClickService.stop(); toast("Đã dừng"); build(); return }
        if (!AutoClickService.isEnabled()) {
            toast("Hãy bật dịch vụ Trợ năng AutoTap trước")
            Access.openAccessibilitySettings(this)
            return
        }
        if (scenario.steps.isEmpty()) { toast("Chưa có bước nào"); return }
        AutoClickService.run(scenario)
        toast("Đang chạy… mở ứng dụng đích")
        moveTaskToBack(true)
    }
}
