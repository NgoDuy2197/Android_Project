package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** One downloadable on-device model option. */
private class ModelOption(val title: String, val sizeLabel: String, val url: String, val fileName: String, val page: String)

/** Choose + configure the AI engine: Gemini (key + model) or an imported / downloaded on-device model. */
class AiSettingsActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private var statusView: TextView? = null
    private var dlBox: LinearLayout? = null
    private var geminiKeysField: EditText? = null

    private val models = listOf(
        ModelOption("Gemma 3 1B IT · q4 (nhẹ, khuyến nghị)", "~555 MB",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            "gemma3-1b-it-int4.task", "https://huggingface.co/litert-community/Gemma3-1B-IT"),
        ModelOption("Gemma 3 1B IT · q8 (chi tiết hơn)", "~1.07 GB",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task?download=true",
            "Gemma3-1B-IT_q8.task", "https://huggingface.co/litert-community/Gemma3-1B-IT"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "Cài đặt AI")

        col.addView(sectionHeader(p, "Chế độ AI khi “Xem xét”"))
        col.addView(Segmented(this, p, listOf("Offline (trên máy)", "Gemini (online)"),
            if (prefs.engine == "offline") 0 else 1) {
            prefs.engine = if (it == 0) "offline" else "gemini"; render()
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        render()
    }

    override fun onPause() {
        super.onPause()
        geminiKeysField?.let { prefs.geminiKeys = it.text.toString() } // persist keys even if focus never left
    }

    private fun render() {
        body.removeAllViews()
        if (prefs.engine == "offline") renderOffline() else renderGemini()
        body.addView(group(p, row(p, "Nhật ký chẩn đoán AI", chevron(p)) {
            startActivity(Intent(this, DiagLogActivity::class.java))
        }), vlp().apply { topMargin = dp(18) })
        body.addView(footnote(p, "Xem AI chạy/đứng ở bước nào khi “Xem xét” (nạp model, sinh phản hồi, lỗi…)."))
        body.addView(footnote(p, "Có thể đổi chế độ bất cứ lúc nào ở trên. “Xem xét” sẽ dùng chế độ đang chọn."),
            vlp().apply { topMargin = dp(10) })
    }

    // ---------- Gemini ----------
    private fun renderGemini() {
        body.addView(sectionHeader(p, "Gemini API key"))
        val keysField = EditText(this).apply {
            setText(prefs.geminiKeyList().joinToString("\n"))
            hint = "Dán API key — mỗi dòng một key…"
            background = null
            setTextColor(p.text); setHintTextColor(p.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 2
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnFocusChangeListener { _, has -> if (!has) prefs.geminiKeys = text.toString() }
        }
        geminiKeysField = keysField
        body.addView(card(p).apply { addView(keysField) })
        val n = prefs.geminiKeyList().size
        body.addView(footnote(p, "Nhập nhiều key, mỗi dòng một key — app tự chuyển sang key kế tiếp khi key hiện tại hết quota hoặc lỗi." +
            if (n > 0) " Đang có $n key." else ""))
        body.addView(group(p, row(p, "Lấy API key (Google AI Studio)", chevron(p)) {
            openUrl("https://aistudio.google.com/apikey")
        }), vlp().apply { topMargin = dp(6) })

        body.addView(sectionHeader(p, "Model"))
        val modelField = inlineField(p, prefs.geminiModel, wide = true) { prefs.geminiModel = it.trim() }.apply {
            gravity = Gravity.START; setTextColor(p.text); hint = "vd: gemini-2.5-flash"; setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val sel = Ai.GEMINI_MODELS.indexOf(prefs.geminiModel)
        body.addView(Segmented(this, p, Ai.GEMINI_MODELS, sel) {
            prefs.geminiModel = Ai.GEMINI_MODELS[it]; modelField.setText(prefs.geminiModel)
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4); topMargin = dp(6) })
        body.addView(footnote(p, "Chọn nhanh ở trên hoặc gõ tên model tuỳ ý bên dưới (nếu Google ra model mới)."))
        body.addView(card(p).apply { addView(modelField) }, vlp().apply { topMargin = dp(6) })

        statusLine()
        body.addView(bigIconButton("Kiểm tra Gemini", R.drawable.ic_check, p.indigo, p.blue) {
            prefs.geminiKeys = keysField.text.toString(); runTest("gemini")
        }, vlp().apply { topMargin = dp(14) })
        body.addView(footnote(p, "Gemini chạy online, cần mạng và API key của bạn. Dữ liệu bữa ăn sẽ được gửi tới Google khi “Xem xét”."))
    }

    // ---------- Offline ----------
    private fun renderOffline() {
        body.addView(sectionHeader(p, "Model trên máy (.task)"))
        val name = prefs.offlineModelName.ifBlank { "Chưa chọn model" }
        val ok = prefs.offlineModelPath.isNotBlank() && File(prefs.offlineModelPath).exists()
        body.addView(group(p,
            row(p, "Model hiện tại", text(if (ok) name else "Chưa có", 14f, if (ok) p.green else p.secondary, 600)),
            row(p, "Import từ tệp (.task)…", chevron(p)) { pickModel() },
        ))
        if (ok) {
            val sizeMb = File(prefs.offlineModelPath).length() / (1024 * 1024)
            body.addView(footnote(p, "Đã lưu trong bộ nhớ app (~${sizeMb} MB). Chạy hoàn toàn offline."))
        }

        // ---- download models ----
        body.addView(sectionHeader(p, "Tải model về máy"))
        body.addView(group(p, *models.map { modelRow(it) }.toTypedArray()))
        body.addView(footnote(p, "Gemma là model có kiểm soát (gated): lần đầu cần dán HF token bên dưới, hoặc bấm “Mở trang” để tải thủ công rồi “Import từ tệp”."))

        val tokenField = inlineField(p, prefs.hfToken, wide = true) { prefs.hfToken = it.trim() }.apply {
            gravity = Gravity.START; setTextColor(p.text); hint = "HF token (hf_…) — nếu model cần đăng nhập"; setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        body.addView(card(p).apply { addView(tokenField) }, vlp().apply { topMargin = dp(6) })
        body.addView(group(p, row(p, "Lấy HF token", chevron(p)) { openUrl("https://huggingface.co/settings/tokens") }),
            vlp().apply { topMargin = dp(6) })

        val urlField = inlineField(p, "", wide = true) {}.apply {
            gravity = Gravity.START; setTextColor(p.text); hint = "Hoặc dán link .task bất kỳ để tải…"; setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        body.addView(card(p).apply { addView(urlField) }, vlp().apply { topMargin = dp(10) })
        body.addView(bigIconButton("Tải từ link", R.drawable.ic_download, p.indigo, p.blue) {
            startDownload(urlField.text.toString().trim(), "", null)
        }, vlp().apply { topMargin = dp(8) })

        dlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dlBox)

        // ---- length + test ----
        body.addView(sectionHeader(p, "Giới hạn độ dài (max tokens)"))
        val mt = inlineField(p, prefs.offlineMaxTokens.toString(), numeric = true) {
            it.toIntOrNull()?.let { v -> prefs.offlineMaxTokens = v.coerceIn(256, 4096) }
        }
        body.addView(group(p, row(p, "Tokens", mt)))

        statusLine()
        body.addView(bigIconButton("Kiểm tra model", R.drawable.ic_check, p.indigo, p.blue) { runTest("offline") }, vlp().apply { topMargin = dp(14) })
        body.addView(footnote(p,
            "Cần model định dạng MediaPipe LLM (.task). Model càng nhỏ chạy càng nhanh nhưng nhận xét kém chi tiết hơn [ước tính]. " +
            "Máy cần RAM khá (khuyến nghị ≥ 6–8GB)."))
    }

    private fun modelRow(opt: ModelOption): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pressable(null, p.ripple)
        setOnClickListener { startDownload(opt.url, opt.fileName, opt) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(opt.title, 15.5f, p.text, 500))
            addView(text(opt.sizeLabel, 13f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_download, p.blue, 24))
    }

    private fun startDownload(url: String, suggestedName: String, opt: ModelOption?) {
        if (url.isBlank()) { toast("Chưa có link để tải."); return }
        val box = dlBox ?: return
        box.removeAllViews()
        val status = text("Bắt đầu tải…", 13.5f, p.secondary, 500).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        box.addView(status)
        val cancel = bigButton("Huỷ tải", p.red, p.pink) { ModelDownloader.cancel() }
        box.addView(cancel, vlp().apply { topMargin = dp(8) })

        ModelDownloader.download(this, url, prefs.hfToken.trim(), suggestedName,
            onProgress = { d, t ->
                status.text = if (t > 0) "Đang tải: ${d * 100 / t}%  (${mb(d)} / ${mb(t)} MB)" else "Đang tải: ${mb(d)} MB…"
            },
            onDone = { r ->
                box.removeAllViews()
                if (r.ok) { toast(r.message); render() }
                else {
                    box.addView(text(r.message, 13.5f, if (r.needsAuth) p.orange else p.red, 500)
                        .apply { setPadding(dp(4), dp(12), dp(4), 0); setLineSpacing(0f, 1.15f) })
                    if (opt != null) box.addView(bigIconButton("Mở trang để tải", R.drawable.ic_open_in_new, p.indigo, p.blue) {
                        openUrl(opt.page)
                    }, vlp().apply { topMargin = dp(8) })
                }
            })
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun statusLine() {
        statusView = text("", 13.5f, p.secondary).apply { setPadding(dp(4), dp(10), dp(4), 0) }
        body.addView(statusView)
    }

    private fun runTest(engine: String) {
        statusView?.text = "Đang kiểm tra…"
        statusView?.setTextColor(p.secondary)
        Ai.test(this, engine) { out, err ->
            if (out != null) { statusView?.text = "OK — model phản hồi: “$out”"; statusView?.setTextColor(p.green) }
            else { statusView?.text = "Lỗi: $err"; statusView?.setTextColor(p.red) }
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("Không mở được: $url") }
    }

    // ---- model import (SAF, copied into the app's files dir) ----
    private fun pickModel() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT,
            )
        } catch (_: Exception) { toast("Không mở được trình chọn tệp") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = queryName(uri) ?: "model.task"
        statusView?.text = "Đang sao chép model vào máy… (có thể mất vài phút với model lớn)"
        statusView?.setTextColor(p.secondary)
        Thread {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // keep only one imported model to save space
                val out = File(dir, sanitize(name))
                contentResolver.openInputStream(uri)!!.use { inp -> out.outputStream().use { inp.copyTo(it, 1 shl 20) } }
                prefs.offlineModelPath = out.absolutePath
                prefs.offlineModelName = name
                Ai.resetOffline()
                runOnUiThread { toast("Đã import: $name"); render() }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "Lỗi import: ${e.message}"; statusView?.setTextColor(p.red) }
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (_: Exception) { null }

    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object { private const val REQ_IMPORT = 1 }
}
