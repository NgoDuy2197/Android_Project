package com.dsoft.bookshelf

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Translate / audiobook dialogs (used from the shelf menu and the readers). */
object BookDialogs {

    fun translate(act: BaseActivity, b: Book) {
        val p = act.p
        var engine = Prefs.engine(act)
        var src = Prefs.trSource(act)
        var tgt = Prefs.trTarget(act)
        val col = act.dialogColumn()

        col.addView(act.text(act.getString(R.string.tr_engine), 13f, p.secondary).apply { setPadding(0, act.dp(8), 0, act.dp(4)) })
        val group = RadioGroup(act)
        val labels = mapOf(
            Engines.MLKIT to (R.string.engine_mlkit to R.string.engine_mlkit_note),
            Engines.GOOGLE to (R.string.engine_google to R.string.engine_google_note),
            Engines.GEMINI to (R.string.engine_gemini to R.string.engine_gemini_note),
        )
        val keyField = act.editField(p, Prefs.geminiKey(act), act.getString(R.string.gemini_key_hint), password = true)
        val cellCheck = android.widget.CheckBox(act).apply {
            text = act.getString(R.string.allow_cellular)
            setTextColor(p.text)
            textSize = 14f
            buttonTintList = ColorStateList.valueOf(p.blue)
            isChecked = false
        }
        fun updateVisibility() {
            keyField.visibility = if (engine == Engines.GEMINI) View.VISIBLE else View.GONE
            cellCheck.visibility = if (engine == Engines.MLKIT) View.VISIBLE else View.GONE
        }
        Engines.all.forEachIndexed { i, e ->
            val (t, note) = labels.getValue(e)
            group.addView(RadioButton(act).apply {
                id = 1000 + i
                text = act.getString(t) + "\n" + act.getString(note)
                setTextColor(p.text)
                textSize = 14f
                buttonTintList = ColorStateList.valueOf(p.blue)
                setPadding(act.dp(6), act.dp(6), 0, act.dp(6))
                isChecked = e == engine
                setOnClickListener { engine = e; updateVisibility() }
            })
        }
        col.addView(group)
        col.addView(keyField, vlp())
        col.addView(cellCheck, vlp())
        updateVisibility()

        lateinit var srcRow: TextView
        lateinit var tgtRow: TextView
        fun langLabel(code: String) = if (code == Langs.AUTO) act.getString(R.string.lang_auto) else Langs.name(code)
        srcRow = pickerRow(act, act.getString(R.string.tr_from) + ": " + langLabel(src)) {
            val codes = listOf(Langs.AUTO) + Langs.codes
            act.dialogBuilder(p).setItems(codes.map { langLabel(it) }.toTypedArray()) { _, w ->
                src = codes[w]; srcRow.text = act.getString(R.string.tr_from) + ": " + langLabel(src)
            }.show()
        }
        tgtRow = pickerRow(act, act.getString(R.string.tr_to) + ": " + langLabel(tgt)) {
            act.dialogBuilder(p).setItems(Langs.codes.map { langLabel(it) }.toTypedArray()) { _, w ->
                tgt = Langs.codes[w]; tgtRow.text = act.getString(R.string.tr_to) + ": " + langLabel(tgt)
            }.show()
        }
        col.addView(srcRow, vlp().apply { topMargin = act.dp(10) })
        col.addView(tgtRow, vlp().apply { topMargin = act.dp(6) })
        if (b.isPdf && Library.textSourceOf(b) == null) {
            col.addView(act.text(act.getString(R.string.tr_will_extract), 12.5f, p.orange).apply { setPadding(0, act.dp(10), 0, 0) })
        }
        col.addView(act.text(act.getString(R.string.tr_resume_note), 12.5f, p.secondary).apply {
            setPadding(0, act.dp(10), 0, act.dp(6)); setLineSpacing(0f, 1.15f)
        })

        act.dialogBuilder(p).setTitle(act.getString(R.string.menu_translate_title, b.title))
            .setView(android.widget.ScrollView(act).apply { addView(col) })
            .setPositiveButton(R.string.btn_start) { _, _ ->
                if (engine == Engines.GEMINI) {
                    val k = keyField.text.toString().trim()
                    if (k.isEmpty()) { act.toast(R.string.err_no_key); return@setPositiveButton }
                    Prefs.setGeminiKey(act, k)
                }
                if (src == tgt) { act.toast(R.string.err_same_lang); return@setPositiveButton }
                if (engine == Engines.MLKIT && src == Langs.AUTO) {
                    // ML Kit needs a concrete source: detected by the job with language-id
                }
                Prefs.setEngine(act, engine); Prefs.setTrSource(act, src); Prefs.setTrTarget(act, tgt)
                JobManager.startTranslate(act, b, engine, src, tgt, allowCell = cellCheck.isChecked)
                act.toast(R.string.job_queued)
                act.startActivity(Intent(act, JobsActivity::class.java).putExtra(Readers.EXTRA_BOOK, b.id))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun audiobook(act: BaseActivity, b: Book) {
        val p = act.p
        val col = act.dialogColumn()
        val voice = Prefs.ttsVoice(act).ifEmpty { act.getString(R.string.voice_default) }
        col.addView(act.text(act.getString(R.string.mp3_explain, voice, "Music/BookShelf"), 14f, p.text).apply {
            setLineSpacing(0f, 1.2f); setPadding(0, act.dp(6), 0, act.dp(10))
        })
        val rates = listOf(48, 64, 96, 128)
        var kbps = Prefs.mp3Kbps(act)
        col.addView(act.text(act.getString(R.string.mp3_quality), 13f, p.secondary).apply { setPadding(0, 0, 0, act.dp(6)) })
        col.addView(Segmented(act, p, rates.map { "$it kbps" }, rates.indexOf(kbps).coerceAtLeast(1)) { kbps = rates[it] }, vlp())
        if (b.isPdf && Library.textSourceOf(b) == null) {
            col.addView(act.text(act.getString(R.string.tr_will_extract), 12.5f, p.orange).apply { setPadding(0, act.dp(10), 0, 0) })
        }
        col.addView(act.text(act.getString(R.string.mp3_resume_note), 12.5f, p.secondary).apply {
            setPadding(0, act.dp(10), 0, act.dp(6)); setLineSpacing(0f, 1.15f)
        })
        act.dialogBuilder(p).setTitle(act.getString(R.string.menu_mp3_title, b.title))
            .setView(col)
            .setPositiveButton(R.string.btn_start) { _, _ ->
                Prefs.setMp3Kbps(act, kbps)
                JobManager.startAudio(act, b)
                act.toast(R.string.job_queued)
                act.startActivity(Intent(act, JobsActivity::class.java).putExtra(Readers.EXTRA_BOOK, b.id))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickerRow(act: BaseActivity, label: String, onClick: () -> Unit): TextView =
        act.text("$label  ▾", 15f, act.p.blue, 500).apply {
            setPadding(act.dp(12), act.dp(11), act.dp(12), act.dp(11))
            background = pressable(rounded(act.p.fill, act.dpf(10)), act.p.ripple)
            setOnClickListener { onClick() }
        }

    @Suppress("unused")
    private fun LinearLayout.gap(act: BaseActivity, dp: Int) = addView(View(act), vlp(h = act.dp(dp)))
}
