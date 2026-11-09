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
        ModelOption("Gemma 3 1B IT · q4 " + tr("(nhẹ, khuyến nghị)", "(light, recommended)"), "~555 MB",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
            "gemma3-1b-it-int4.task", "https://huggingface.co/litert-community/Gemma3-1B-IT"),
        ModelOption("Gemma 3 1B IT · q8 " + tr("(chi tiết hơn)", "(more detailed)"), "~1.07 GB",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task?download=true",
            "Gemma3-1B-IT_q8.task", "https://huggingface.co/litert-community/Gemma3-1B-IT"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, tr("Cài đặt", "Settings"))

        // ----- language + theme -----
        col.addView(sectionHeader(p, tr("Ngôn ngữ", "Language")))
        col.addView(Segmented(this, p, listOf("Tiếng Việt", "English"), if (prefs.lang == "en") 1 else 0) {
            val v = if (it == 1) "en" else "vi"
            if (v != prefs.lang) { prefs.lang = v; recreate() }
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        col.addView(sectionHeader(p, tr("Giao diện (theme)", "Theme")))
        val themeKeys = listOf("de_thuong", "khoe", "nang_dong", "thoai_mai", "diu_nhe")
        val themeLabels = listOf(tr("Dễ thương", "Cute"), tr("Khoẻ mạnh", "Healthy"), tr("Năng động", "Active"),
            tr("Thoải mái", "Relaxed"), tr("Dịu nhẹ", "Calm"))
        col.addView(Segmented(this, p, themeLabels, themeKeys.indexOf(prefs.theme).coerceAtLeast(0)) {
            val v = themeKeys[it]
            if (v != prefs.theme) { prefs.theme = v; recreate() }
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        col.addView(sectionHeader(p, tr("Chế độ AI khi “Xem xét”", "AI engine for “Review”")))
        col.addView(Segmented(this, p, listOf(tr("Offline (trên máy)", "Offline (on-device)"), tr("Gemini (online)", "Gemini (online)")),
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
        body.addView(group(p, row(p, tr("Nhật ký chẩn đoán AI", "AI diagnostic log"), chevron(p)) {
            startActivity(Intent(this, DiagLogActivity::class.java))
        }), vlp().apply { topMargin = dp(18) })
        body.addView(footnote(p, tr("Xem AI chạy/đứng ở bước nào khi “Xem xét” (nạp model, sinh phản hồi, lỗi…).",
            "See where the AI is or stalls during a review (model load, response, errors…).")))
        body.addView(footnote(p, tr("Có thể đổi chế độ bất cứ lúc nào ở trên. “Xem xét” sẽ dùng chế độ đang chọn.",
            "Switch engine anytime above. A review uses the selected engine.")),
            vlp().apply { topMargin = dp(10) })
    }

    // ---------- Gemini ----------
    private fun renderGemini() {
        body.addView(sectionHeader(p, tr("Gemini API key", "Gemini API key")))
        val keysField = EditText(this).apply {
            setText(prefs.geminiKeyList().joinToString("\n"))
            hint = tr("Dán API key — mỗi dòng một key…", "Paste API keys — one per line…")
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
        body.addView(footnote(p, tr("Nhập nhiều key, mỗi dòng một key — app tự chuyển sang key kế tiếp khi key hiện tại hết quota hoặc lỗi.",
            "Enter several keys, one per line — the app switches to the next when one hits its quota or fails.") +
            if (n > 0) tr(" Đang có $n key.", " $n key(s) saved.") else ""))
        body.addView(group(p, row(p, tr("Lấy API key (Google AI Studio)", "Get an API key (Google AI Studio)"), chevron(p)) {
            openUrl("https://aistudio.google.com/apikey")
        }), vlp().apply { topMargin = dp(6) })

        body.addView(sectionHeader(p, tr("Model", "Model")))
        // The text field is the source of truth: it saves on every keystroke, so a typed custom
        // model name takes effect immediately. The segmented buttons just fill the field.
        val modelField = EditText(this).apply {
            setText(prefs.geminiModel)
            setTextColor(p.text); setHintTextColor(p.secondary)
            hint = "vd: gemini-2.5-flash"
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            gravity = Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { prefs.geminiModel = text.toString().trim() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        body.addView(Segmented(this, p, Ai.GEMINI_MODELS, Ai.GEMINI_MODELS.indexOf(prefs.geminiModel)) {
            modelField.setText(Ai.GEMINI_MODELS[it]); modelField.setSelection(modelField.text.length)
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4); topMargin = dp(6) })
        body.addView(footnote(p, tr("Chọn nhanh ở trên, hoặc gõ tên model tuỳ ý bên dưới — tên vừa gõ sẽ được dùng ngay.",
            "Pick one above, or type any model name below — the typed name is used right away.")))
        body.addView(card(p).apply { addView(modelField) }, vlp().apply { topMargin = dp(6) })

        statusLine()
        body.addView(bigIconButton(tr("Kiểm tra Gemini", "Test Gemini"), R.drawable.ic_check, p.indigo, p.blue) {
            prefs.geminiKeys = keysField.text.toString(); runTest("gemini")
        }, vlp().apply { topMargin = dp(14) })
        body.addView(footnote(p, tr("Gemini chạy online, cần mạng và API key của bạn. Dữ liệu bữa ăn sẽ được gửi tới Google khi “Xem xét”.",
            "Gemini runs online and needs internet + your API key. Meal data is sent to Google when you review.")))
    }

    // ---------- Offline ----------
    private fun renderOffline() {
        body.addView(sectionHeader(p, tr("Model trên máy (.task)", "On-device model (.task)")))
        val name = prefs.offlineModelName.ifBlank { tr("Chưa chọn model", "No model selected") }
        val ok = prefs.offlineModelPath.isNotBlank() && File(prefs.offlineModelPath).exists()
        body.addView(group(p,
            row(p, tr("Model hiện tại", "Current model"), text(if (ok) name else tr("Chưa có", "None"), 14f, if (ok) p.green else p.secondary, 600)),
            row(p, tr("Import từ tệp (.task)…", "Import from file (.task)…"), chevron(p)) { pickModel() },
        ))
        if (ok) {
            val sizeMb = File(prefs.offlineModelPath).length() / (1024 * 1024)
            body.addView(footnote(p, tr("Đã lưu trong bộ nhớ app (~${sizeMb} MB). Chạy hoàn toàn offline.",
                "Stored in app storage (~${sizeMb} MB). Runs fully offline.")))
        }

        // ---- download models ----
        body.addView(sectionHeader(p, tr("Tải model về máy", "Download a model")))
        body.addView(group(p, *models.map { modelRow(it) }.toTypedArray()))
        body.addView(footnote(p, tr("Gemma là model có kiểm soát (gated): lần đầu cần dán HF token bên dưới, hoặc bấm “Mở trang” để tải thủ công rồi “Import từ tệp”.",
            "Gemma is gated: first time, paste an HF token below, or tap “Open page” to download manually then “Import from file”.")))

        val tokenField = inlineField(p, prefs.hfToken, wide = true) { prefs.hfToken = it.trim() }.apply {
            gravity = Gravity.START; setTextColor(p.text); hint = tr("HF token (hf_…) — nếu model cần đăng nhập", "HF token (hf_…) — if the model needs login"); setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        body.addView(card(p).apply { addView(tokenField) }, vlp().apply { topMargin = dp(6) })
        body.addView(group(p, row(p, tr("Lấy HF token", "Get an HF token"), chevron(p)) { openUrl("https://huggingface.co/settings/tokens") }),
            vlp().apply { topMargin = dp(6) })

        val urlField = inlineField(p, "", wide = true) {}.apply {
            gravity = Gravity.START; setTextColor(p.text); hint = tr("Hoặc dán link .task bất kỳ để tải…", "Or paste any .task link to download…"); setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        body.addView(card(p).apply { addView(urlField) }, vlp().apply { topMargin = dp(10) })
        body.addView(bigIconButton(tr("Tải từ link", "Download from link"), R.drawable.ic_download, p.indigo, p.blue) {
            startDownload(urlField.text.toString().trim(), "", null)
        }, vlp().apply { topMargin = dp(8) })

        dlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dlBox)

        // ---- length + test ----
        body.addView(sectionHeader(p, tr("Giới hạn độ dài (max tokens)", "Output length (max tokens)")))
        val mt = inlineField(p, prefs.offlineMaxTokens.toString(), numeric = true) {
            it.toIntOrNull()?.let { v -> prefs.offlineMaxTokens = v.coerceIn(256, 4096) }
        }
        body.addView(group(p, row(p, "Tokens", mt)))

        statusLine()
        body.addView(bigIconButton(tr("Kiểm tra model", "Test model"), R.drawable.ic_check, p.indigo, p.blue) { runTest("offline") }, vlp().apply { topMargin = dp(14) })
        body.addView(footnote(p, tr(
            "Cần model định dạng MediaPipe LLM (.task). Model càng nhỏ chạy càng nhanh nhưng nhận xét kém chi tiết hơn [ước tính]. Máy cần RAM khá (khuyến nghị ≥ 6–8GB).",
            "Needs a MediaPipe LLM model (.task). Smaller models run faster but review less thoroughly [estimate]. Needs decent RAM (≥ 6–8GB recommended).")))
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
        if (url.isBlank()) { toast(tr("Chưa có link để tải.", "No link to download.")); return }
        val box = dlBox ?: return
        box.removeAllViews()
        val status = text(tr("Bắt đầu tải…", "Starting download…"), 13.5f, p.secondary, 500).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        box.addView(status)
        val cancel = bigButton(tr("Huỷ tải", "Cancel download"), p.red, p.pink) { ModelDownloader.cancel() }
        box.addView(cancel, vlp().apply { topMargin = dp(8) })

        ModelDownloader.download(this, url, prefs.hfToken.trim(), suggestedName,
            onProgress = { d, t ->
                status.text = if (t > 0) "${tr("Đang tải", "Downloading")}: ${d * 100 / t}%  (${mb(d)} / ${mb(t)} MB)" else "${tr("Đang tải", "Downloading")}: ${mb(d)} MB…"
            },
            onDone = { r ->
                box.removeAllViews()
                if (r.ok) { toast(r.message); render() }
                else {
                    box.addView(text(r.message, 13.5f, if (r.needsAuth) p.orange else p.red, 500)
                        .apply { setPadding(dp(4), dp(12), dp(4), 0); setLineSpacing(0f, 1.15f) })
                    if (opt != null) box.addView(bigIconButton(tr("Mở trang để tải", "Open page to download"), R.drawable.ic_open_in_new, p.indigo, p.blue) {
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
        statusView?.text = tr("Đang kiểm tra…", "Testing…")
        statusView?.setTextColor(p.secondary)
        Ai.test(this, engine) { out, err ->
            if (out != null) { statusView?.text = tr("OK — model phản hồi: “$out”", "OK — model replied: “$out”"); statusView?.setTextColor(p.green) }
            else { statusView?.text = "${tr("Lỗi", "Error")}: $err"; statusView?.setTextColor(p.red) }
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast(tr("Không mở được: $url", "Couldn't open: $url")) }
    }

    // ---- model import (SAF, copied into the app's files dir) ----
    private fun pickModel() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT,
            )
        } catch (_: Exception) { toast(tr("Không mở được trình chọn tệp", "Couldn't open the file picker")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = queryName(uri) ?: "model.task"
        statusView?.text = tr("Đang sao chép model vào máy… (có thể mất vài phút với model lớn)", "Copying the model in… (can take minutes for large models)")
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
                runOnUiThread { toast(tr("Đã import: $name", "Imported: $name")); render() }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "${tr("Lỗi import", "Import error")}: ${e.message}"; statusView?.setTextColor(p.red) }
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
