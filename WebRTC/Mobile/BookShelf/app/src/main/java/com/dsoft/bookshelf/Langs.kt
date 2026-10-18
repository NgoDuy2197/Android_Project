package com.dsoft.bookshelf

object Engines {
    const val MLKIT = "mlkit"
    const val GOOGLE = "google"
    const val GEMINI = "gemini"
    val all = listOf(MLKIT, GOOGLE, GEMINI)
}

/** Languages for translation (native names, so they read the same in every UI language). */
object Langs {
    const val AUTO = "auto"
    val codes = listOf("vi", "en", "zh", "ja", "ko", "fr", "de", "es", "ru", "th", "id")
    private val names = mapOf(
        "vi" to "Tiếng Việt", "en" to "English", "zh" to "中文", "ja" to "日本語", "ko" to "한국어",
        "fr" to "Français", "de" to "Deutsch", "es" to "Español", "ru" to "Русский", "th" to "ไทย", "id" to "Bahasa Indonesia",
    )
    private val englishNames = mapOf(
        "vi" to "Vietnamese", "en" to "English", "zh" to "Simplified Chinese", "ja" to "Japanese", "ko" to "Korean",
        "fr" to "French", "de" to "German", "es" to "Spanish", "ru" to "Russian", "th" to "Thai", "id" to "Indonesian",
    )

    fun name(code: String): String = names[code] ?: code
    fun englishName(code: String): String = englishNames[code] ?: code

    /** Code used by translate.googleapis.com */
    fun googleCode(code: String) = when (code) { "zh" -> "zh-CN"; else -> code }

    /** UI languages offered in Settings. */
    val uiLangs = listOf("vi", "en", "zh", "ja")
}
