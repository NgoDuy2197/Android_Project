package com.dsoft.bookshelf

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

object TtsUtil {
    const val GOOGLE_ENGINE = "com.google.android.tts"

    fun hasGoogleEngine(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(GOOGLE_ENGINE, 0); true
    } catch (_: Exception) { false }

    /** Creates a TextToSpeech (Google engine preferred) and waits for init. Must not be called on the main thread. */
    suspend fun create(ctx: Context): TextToSpeech {
        val done = CompletableDeferred<Int>()
        val tts = withContext(Dispatchers.Main) {
            val l = TextToSpeech.OnInitListener { done.complete(it) }
            if (hasGoogleEngine(ctx)) TextToSpeech(ctx.applicationContext, l, GOOGLE_ENGINE)
            else TextToSpeech(ctx.applicationContext, l)
        }
        val status = try { withTimeout(20_000) { done.await() } } catch (e: Exception) { TextToSpeech.ERROR }
        if (status != TextToSpeech.SUCCESS) {
            try { tts.shutdown() } catch (_: Exception) {}
            throw UserError(Locales.wrap(ctx).getString(R.string.err_tts_init))
        }
        return tts
    }

    /** Applies voice / rate / pitch from settings. [voiceName] overrides the saved voice (MP3 jobs keep theirs). */
    fun apply(ctx: Context, tts: TextToSpeech, voiceName: String = Prefs.ttsVoice(ctx), textSample: String = "") {
        var applied = false
        if (voiceName.isNotEmpty()) {
            val v = try { tts.voices?.firstOrNull { it.name == voiceName } } catch (_: Exception) { null }
            if (v != null) applied = tts.setVoice(v) == TextToSpeech.SUCCESS
        }
        if (!applied) {
            val loc = guessLocale(textSample) ?: Locale("vi", "VN")
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc
        }
        tts.setSpeechRate(Prefs.ttsRate(ctx) / 100f)
        tts.setPitch(Prefs.ttsPitch(ctx) / 100f)
    }

    /** Very small script-based guess for the default voice when none is chosen. */
    fun guessLocale(sample: String): Locale? {
        if (sample.isBlank()) return null
        var cjk = 0; var kana = 0; var hangul = 0; var viet = 0; var latin = 0
        for (c in sample.take(3000)) {
            when {
                c in '぀'..'ヿ' -> kana++
                c in '一'..'鿿' -> cjk++
                c in '가'..'힯' -> hangul++
                "ăâđêôơưạảãáàặẳẵắằậẩẫấầẹẻẽéèệểễếềịỉĩíìọỏõóòộổỗốồợởỡớờụủũúùựửữứừỵỷỹýỳ".indexOf(c.lowercaseChar()) >= 0 -> viet++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        return when {
            kana > 10 -> Locale.JAPANESE
            cjk > 20 -> Locale.SIMPLIFIED_CHINESE
            hangul > 10 -> Locale.KOREAN
            viet > 5 -> Locale("vi", "VN")
            latin > 50 -> Locale.US
            else -> null
        }
    }

    fun voiceLabel(v: Voice, networkLabel: String): String {
        val loc = v.locale
        val name = loc.getDisplayName(Locale.getDefault())
        val net = if (v.isNetworkConnectionRequired) " · $networkLabel" else ""
        return "$name — ${v.name}$net"
    }
}
