package com.dsoft.bookshelf

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : BaseActivity() {
    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()
    private lateinit var voiceValue: TextView
    private lateinit var modelsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = page(p, getString(R.string.settings_title), getString(R.string.library_title))

        // ---- appearance ----
        col.addView(sectionHeader(p, getString(R.string.sec_appearance)))
        val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SEPIA, ThemeMode.PASTEL, ThemeMode.SYSTEM)
        val themeSeg = Segmented(this, p, listOf(getString(R.string.theme_light), getString(R.string.theme_dark),
            getString(R.string.theme_sepia), getString(R.string.theme_pastel), getString(R.string.theme_system)),
            modes.indexOf(Prefs.theme(this)).coerceAtLeast(0)) {
            Prefs.setTheme(this, modes[it]); recreate()
        }
        val langNames = mapOf("vi" to "Tiếng Việt", "en" to "English", "zh" to "中文", "ja" to "日本語")
        val langValue = valueLabel(p, langNames[Prefs.lang(this)] ?: "Tiếng Việt")
        col.addView(group(p,
            LinearLayout(this).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); addView(themeSeg, LinearLayout.LayoutParams(0, -2, 1f)) },
            row(p, getString(R.string.language), LinearLayout(this).apply { addView(langValue); addView(chevron(p)) }) {
                val codes = Langs.uiLangs
                dialogBuilder(p).setTitle(R.string.language)
                    .setSingleChoiceItems(codes.map { langNames[it] }.toTypedArray(), codes.indexOf(Prefs.lang(this))) { d, w ->
                        d.dismiss()
                        if (codes[w] != Prefs.lang(this)) { Prefs.setLang(this, codes[w]); recreate() }
                    }.show()
            },
        ))

        // ---- reading ----
        col.addView(sectionHeader(p, getString(R.string.sec_reading)))
        col.addView(group(p,
            row(p, getString(R.string.auto_open), iosSwitch(p, Prefs.autoOpen(this)) { Prefs.setAutoOpen(this, it) }, getString(R.string.auto_open_sub)),
            row(p, getString(R.string.keep_screen_on), iosSwitch(p, Prefs.keepScreenOn(this)) { Prefs.setKeepScreenOn(this, it) }),
            row(p, getString(R.string.pdf_night), iosSwitch(p, Prefs.pdfNight(this)) { Prefs.setPdfNight(this, it) }, getString(R.string.pdf_night_sub)),
            sliderRow(p, getString(R.string.nav_opacity), 0, 100, Prefs.navOpacity(this), { "$it%" }) { Prefs.setNavOpacity(this, it) },
            sliderRow(p, getString(R.string.font_size), 12, 34, Prefs.fontSize(this), { "$it sp" }) { Prefs.setFontSize(this, it) },
            sliderRow(p, getString(R.string.line_spacing), 100, 220, Prefs.lineSpacing(this), { String.format(Locale.US, "%.2f", it / 100f) }) { Prefs.setLineSpacing(this, it) },
            sliderRow(p, getString(R.string.margins), 4, 48, Prefs.margin(this), { "$it dp" }) { Prefs.setMargin(this, it) },
            LinearLayout(this).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(Segmented(context, p, listOf(getString(R.string.font_serif), getString(R.string.font_sans)), if (Prefs.serif(context)) 0 else 1) {
                    Prefs.setSerif(context, it == 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            },
        ))

        // ---- eye comfort ----
        col.addView(sectionHeader(p, getString(R.string.sec_comfort)))
        col.addView(group(p,
            row(p, getString(R.string.bluelight), iosSwitch(p, Prefs.filterOn(this)) { Prefs.setFilterOn(this, it); applyComfort() }, getString(R.string.bluelight_sub)),
            sliderRow(p, getString(R.string.bluelight_strength), 0, 80, Prefs.filterStrength(this), { "$it%" }) {
                Prefs.setFilterStrength(this, it); applyComfort()
            },
            sliderRow(p, getString(R.string.bluelight_warmth), 0, 100, Prefs.filterWarmth(this), { "$it" }) {
                Prefs.setFilterWarmth(this, it); applyComfort()
            },
            row(p, getString(R.string.dim), iosSwitch(p, Prefs.dimOn(this)) { Prefs.setDimOn(this, it); applyComfort() }, getString(R.string.dim_sub)),
            sliderRow(p, getString(R.string.dim_level), 1, 100, Prefs.dimLevel(this), { "$it%" }) {
                Prefs.setDimLevel(this, it); applyComfort()
            },
        ))

        // ---- TTS ----
        col.addView(sectionHeader(p, getString(R.string.sec_tts)))
        voiceValue = valueLabel(p, Prefs.ttsVoice(this).ifEmpty { getString(R.string.voice_default) }).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; maxWidth = dp(180)
        }
        val engineLabel = if (TtsUtil.hasGoogleEngine(this)) "Google (com.google.android.tts)" else getString(R.string.tts_engine_default)
        col.addView(group(p,
            row(p, getString(R.string.tts_engine), valueLabel(p, engineLabel).apply { maxWidth = dp(190) }),
            row(p, getString(R.string.tts_voice), LinearLayout(this).apply { addView(voiceValue); addView(chevron(p)) }) { pickVoice() },
            sliderRow(p, getString(R.string.tts_rate), 50, 250, Prefs.ttsRate(this), { String.format(Locale.US, "%.2fx", it / 100f) }) { Prefs.setTtsRate(this, it) },
            sliderRow(p, getString(R.string.tts_pitch), 50, 200, Prefs.ttsPitch(this), { String.format(Locale.US, "%.2f", it / 100f) }) { Prefs.setTtsPitch(this, it) },
            row(p, getString(R.string.tts_test), null) { testVoice() },
        ))
        col.addView(footnote(p, getString(R.string.tts_footer)))

        // ---- translation ----
        col.addView(sectionHeader(p, getString(R.string.sec_translate)))
        val engines = Engines.all
        val engSeg = Segmented(this, p, listOf("ML Kit", "Google", "Gemini"), engines.indexOf(Prefs.engine(this)).coerceAtLeast(0)) {
            Prefs.setEngine(this, engines[it])
        }
        val keyRowValue = valueLabel(p, maskKey(Prefs.geminiKey(this)))
        val tgtValue = valueLabel(p, Langs.name(Prefs.trTarget(this)))
        col.addView(group(p,
            LinearLayout(this).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); addView(engSeg, LinearLayout.LayoutParams(0, -2, 1f)) },
            row(p, getString(R.string.tr_default_target), LinearLayout(this).apply { addView(tgtValue); addView(chevron(p)) }) {
                dialogBuilder(p).setItems(Langs.codes.map { Langs.name(it) }.toTypedArray()) { _, w ->
                    Prefs.setTrTarget(this, Langs.codes[w]); tgtValue.text = Langs.name(Langs.codes[w])
                }.show()
            },
            row(p, getString(R.string.gemini_key), LinearLayout(this).apply { addView(keyRowValue); addView(chevron(p)) }, getString(R.string.gemini_key_sub)) {
                val f = editField(p, Prefs.geminiKey(this), getString(R.string.gemini_key_hint), password = true)
                dialogBuilder(p).setTitle(R.string.gemini_key).setView(dialogColumn().apply { addView(f, vlp()) })
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Prefs.setGeminiKey(this, f.text.toString().trim()); keyRowValue.text = maskKey(Prefs.geminiKey(this))
                    }.setNegativeButton(android.R.string.cancel, null).show()
            },
            row(p, getString(R.string.wifi_only), iosSwitch(p, Prefs.wifiOnly(this)) { Prefs.setWifiOnly(this, it) }, getString(R.string.wifi_only_sub)),
        ))
        col.addView(footnote(p, getString(R.string.engines_footer)))

        col.addView(sectionHeader(p, getString(R.string.mlkit_models)))
        modelsBox = card(p)
        col.addView(modelsBox, vlp())
        col.addView(pillButton(getString(R.string.download_model), p.blue) { downloadModelDialog() },
            vlp(-2).apply { topMargin = dp(10); marginStart = dp(4) })
        refreshModels()

        // ---- OCR ----
        col.addView(sectionHeader(p, getString(R.string.sec_ocr)))
        col.addView(group(p, LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(Segmented(context, p, listOf(getString(R.string.ocr_auto), "Latin", "中文", "日本語"), Prefs.ocrScript(context)) {
                Prefs.setOcrScript(context, it)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }))
        col.addView(footnote(p, getString(R.string.ocr_footer)))

        // ---- about ----
        col.addView(sectionHeader(p, getString(R.string.sec_about)))
        col.addView(group(p,
            row(p, "BookShelf", valueLabel(p, "1.0.0")),
            row(p, getString(R.string.storage_used), valueLabel(p, storageUsed())),
        ))
    }

    private fun maskKey(k: String) = if (k.isEmpty()) getString(R.string.not_set) else "••••" + k.takeLast(4)

    private fun storageUsed(): String {
        val bytes = filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return android.text.format.Formatter.formatShortFileSize(this, bytes)
    }

    // ---- TTS voices ----
    private fun withTts(block: (TextToSpeech) -> Unit) {
        tts?.let { block(it); return }
        uiScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { TtsUtil.create(this@SettingsActivity) }
                tts = t
                block(t)
            } catch (e: Exception) {
                toast(e.message ?: "TTS")
            }
        }
    }

    private fun pickVoice() = withTts { t ->
        val all = try { t.voices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val ui = Locale.getDefault().language
        voices = all.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ if (it.locale.language == "vi") 0 else if (it.locale.language == ui) 1 else 2 },
                { it.locale.displayName }, { it.isNetworkConnectionRequired }, { it.name }))
        if (voices.isEmpty()) { toast(R.string.no_voices); return@withTts }
        val labels = listOf(getString(R.string.voice_default)) + voices.map { TtsUtil.voiceLabel(it, getString(R.string.voice_network)) }
        val cur = voices.indexOfFirst { it.name == Prefs.ttsVoice(this) } + 1
        dialogBuilder(p).setTitle(R.string.tts_voice).setSingleChoiceItems(labels.toTypedArray(), cur) { d, w ->
            val name = if (w == 0) "" else voices[w - 1].name
            Prefs.setTtsVoice(this, name)
            voiceValue.text = name.ifEmpty { getString(R.string.voice_default) }
            d.dismiss()
            testVoice()
        }.show()
    }

    private fun testVoice() = withTts { t ->
        TtsUtil.apply(this, t)
        val sample = when (t.voice?.locale?.language ?: Prefs.lang(this)) {
            "en" -> "Hello! This is how your books will sound."
            "zh" -> "你好！这是朗读书籍的声音。"
            "ja" -> "こんにちは。これは本を読み上げる声です。"
            else -> "Xin chào! Đây là giọng đọc sách của bạn."
        }
        t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    // ---- ML Kit models ----
    private fun refreshModels() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (isDestroyed) return@addOnSuccessListener
                modelsBox.removeAllViews()
                if (models.isEmpty()) {
                    modelsBox.addView(row(p, getString(R.string.no_models)))
                    return@addOnSuccessListener
                }
                models.sortedBy { it.language }.forEachIndexed { i, m ->
                    if (i > 0) modelsBox.addView(separator(p))
                    modelsBox.addView(row(p, Langs.name(m.language), text(getString(R.string.delete), 15f, p.red).apply {
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            RemoteModelManager.getInstance().deleteDownloadedModel(m).addOnCompleteListener { refreshModels() }
                        }
                    }))
                }
            }
            .addOnFailureListener {
                modelsBox.removeAllViews(); modelsBox.addView(row(p, it.message ?: "ML Kit error"))
            }
    }

    private fun downloadModelDialog() {
        val codes = Langs.codes.filter { it != "en" && TranslateLanguage.fromLanguageTag(it) != null }
        dialogBuilder(p).setTitle(R.string.download_model).setItems(codes.map { Langs.name(it) }.toTypedArray()) { _, w ->
            val lang = TranslateLanguage.fromLanguageTag(codes[w]) ?: return@setItems
            val cond = DownloadConditions.Builder().apply { if (Prefs.wifiOnly(this@SettingsActivity)) requireWifi() }.build()
            toast(getString(R.string.downloading_model, Langs.name(codes[w])))
            RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(lang).build(), cond)
                .addOnSuccessListener { toast(R.string.model_ready); refreshModels() }
                .addOnFailureListener { toast(getString(R.string.err_model_download, it.message ?: "")) }
        }.show()
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    @Suppress("unused")
    private fun View.gone() { visibility = View.GONE }
}
