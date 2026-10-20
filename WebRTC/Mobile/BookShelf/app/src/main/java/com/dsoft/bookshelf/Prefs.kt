package com.dsoft.bookshelf

import android.content.Context
import android.content.SharedPreferences

/** All user settings (SharedPreferences). */
object Prefs {
    private fun sp(c: Context): SharedPreferences = (c.applicationContext ?: c).getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun edit(c: Context, block: SharedPreferences.Editor.() -> Unit) = sp(c).edit().apply(block).apply()

    // Appearance
    fun theme(c: Context) = sp(c).getInt("theme", ThemeMode.SYSTEM)
    fun setTheme(c: Context, v: Int) = edit(c) { putInt("theme", v) }
    /** "vi" (default), "en", "zh", "ja" */
    fun lang(c: Context): String = sp(c).getString("lang", "vi") ?: "vi"
    fun setLang(c: Context, v: String) = edit(c) { putString("lang", v) }
    /** Activities recreate themselves when this changes. */
    fun styleStamp(c: Context) =
        "${theme(c)}|${lang(c)}|${shelfBgOn(c)}|${shelfBgBlur(c)}|${shelfBgDim(c)}|${Covers.shelfBgFile(c).lastModified()}"

    // Library
    fun sort(c: Context) = sp(c).getInt("sort", 0) // 0 recent, 1 title, 2 added
    fun setSort(c: Context, v: Int) = edit(c) { putInt("sort", v) }
    fun autoOpen(c: Context) = sp(c).getBoolean("auto_open", true)
    fun setAutoOpen(c: Context, v: Boolean) = edit(c) { putBoolean("auto_open", v) }
    fun lastBook(c: Context): String = sp(c).getString("last_book", "") ?: ""
    fun setLastBook(c: Context, id: String) = edit(c) { putString("last_book", id) }

    // Text reader
    fun fontSize(c: Context) = sp(c).getInt("font_size", 19)
    fun setFontSize(c: Context, v: Int) = edit(c) { putInt("font_size", v) }
    /** line spacing multiplier x100 */
    fun lineSpacing(c: Context) = sp(c).getInt("line_spacing", 150)
    fun setLineSpacing(c: Context, v: Int) = edit(c) { putInt("line_spacing", v) }
    fun serif(c: Context) = sp(c).getBoolean("serif", true)
    fun setSerif(c: Context, v: Boolean) = edit(c) { putBoolean("serif", v) }
    fun margin(c: Context) = sp(c).getInt("margin", 20)
    fun setMargin(c: Context, v: Int) = edit(c) { putInt("margin", v) }
    fun pdfNight(c: Context) = sp(c).getBoolean("pdf_night", false)
    fun setPdfNight(c: Context, v: Boolean) = edit(c) { putBoolean("pdf_night", v) }
    /** Opacity of the on-screen page buttons in the PDF reader, 0..100 % (default 40). */
    fun navOpacity(c: Context) = sp(c).getInt("nav_opacity", 40)
    fun setNavOpacity(c: Context, v: Int) = edit(c) { putInt("nav_opacity", v) }
    fun keepScreenOn(c: Context) = sp(c).getBoolean("keep_on", true)
    fun setKeepScreenOn(c: Context, v: Boolean) = edit(c) { putBoolean("keep_on", v) }

    // Blue-light filter + dim (in-app only)
    fun filterOn(c: Context) = sp(c).getBoolean("filter_on", false)
    fun setFilterOn(c: Context, v: Boolean) = edit(c) { putBoolean("filter_on", v) }
    /** 0..80 % */
    fun filterStrength(c: Context) = sp(c).getInt("filter_strength", 30)
    fun setFilterStrength(c: Context, v: Int) = edit(c) { putInt("filter_strength", v) }
    /** 0..100 (yellow -> deep orange) */
    fun filterWarmth(c: Context) = sp(c).getInt("filter_warmth", 50)
    fun setFilterWarmth(c: Context, v: Int) = edit(c) { putInt("filter_warmth", v) }
    fun dimOn(c: Context) = sp(c).getBoolean("dim_on", false)
    fun setDimOn(c: Context, v: Boolean) = edit(c) { putBoolean("dim_on", v) }
    /** 1..100 % screen brightness inside the app */
    fun dimLevel(c: Context) = sp(c).getInt("dim_level", 40)
    fun setDimLevel(c: Context, v: Int) = edit(c) { putInt("dim_level", v) }

    // TTS
    fun ttsVoice(c: Context): String = sp(c).getString("tts_voice", "") ?: ""
    fun setTtsVoice(c: Context, v: String) = edit(c) { putString("tts_voice", v) }
    /** x100 */
    fun ttsRate(c: Context) = sp(c).getInt("tts_rate", 100)
    fun setTtsRate(c: Context, v: Int) = edit(c) { putInt("tts_rate", v) }
    fun ttsPitch(c: Context) = sp(c).getInt("tts_pitch", 100)
    fun setTtsPitch(c: Context, v: Int) = edit(c) { putInt("tts_pitch", v) }
    fun mp3Kbps(c: Context) = sp(c).getInt("mp3_kbps", 64)
    fun setMp3Kbps(c: Context, v: Int) = edit(c) { putInt("mp3_kbps", v) }

    // Translation
    // Default to ML Kit on-device (offline) translation, as the user prefers.
    fun engine(c: Context): String = sp(c).getString("tr_engine", Engines.MLKIT) ?: Engines.MLKIT

    /** Parallel worker threads for offline (ML Kit) translation. 0 (default) or 1 = sequential. */
    fun trThreads(c: Context): Int = sp(c).getInt("tr_threads", 0).coerceIn(0, MAX_TR_THREADS)
    fun setTrThreads(c: Context, v: Int) = edit(c) { putInt("tr_threads", v.coerceIn(0, MAX_TR_THREADS)) }
    const val MAX_TR_THREADS = 8

    /** Read dialogue inside quotation marks with a slightly higher pitch (TTS + MP3). */
    fun quoteEmphasis(c: Context) = sp(c).getBoolean("quote_emphasis", true)
    fun setQuoteEmphasis(c: Context, v: Boolean) = edit(c) { putBoolean("quote_emphasis", v) }

    // Custom shelf background image (the shelf turns to glass to show it through).
    fun shelfBgOn(c: Context) = sp(c).getBoolean("shelf_bg_on", false)
    fun setShelfBgOn(c: Context, v: Boolean) = edit(c) { putBoolean("shelf_bg_on", v) }
    fun shelfBgBlur(c: Context) = sp(c).getInt("shelf_bg_blur", 8)       // 0..25
    fun setShelfBgBlur(c: Context, v: Int) = edit(c) { putInt("shelf_bg_blur", v.coerceIn(0, 25)) }
    fun shelfBgDim(c: Context) = sp(c).getInt("shelf_bg_dim", -20)        // -80 dark .. +80 bright
    fun setShelfBgDim(c: Context, v: Int) = edit(c) { putInt("shelf_bg_dim", v.coerceIn(-80, 80)) }
    fun setEngine(c: Context, v: String) = edit(c) { putString("tr_engine", v) }
    fun geminiKey(c: Context): String = sp(c).getString("gemini_key", "") ?: ""
    fun setGeminiKey(c: Context, v: String) = edit(c) { putString("gemini_key", v) }
    fun wifiOnly(c: Context) = sp(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, v: Boolean) = edit(c) { putBoolean("wifi_only", v) }
    fun trSource(c: Context): String = sp(c).getString("tr_src", Langs.AUTO) ?: Langs.AUTO
    fun setTrSource(c: Context, v: String) = edit(c) { putString("tr_src", v) }
    fun trTarget(c: Context): String = sp(c).getString("tr_tgt", "vi") ?: "vi"
    fun setTrTarget(c: Context, v: String) = edit(c) { putString("tr_tgt", v) }

    // OCR: 0 auto, 1 latin, 2 chinese, 3 japanese
    fun ocrScript(c: Context) = sp(c).getInt("ocr_script", 0)
    fun setOcrScript(c: Context, v: Int) = edit(c) { putInt("ocr_script", v) }
}
