package com.dsoft.vocab_widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Android TextToSpeech with the right voice per locale ("zh-CN", "en-US",
 * "ja-JP", "vi-VN"). [speakOnce] spins up a temporary engine (widget clicks);
 * the app keeps one engine alive via [Speaker] instances.
 */
class Speaker(context: Context) {
    private val appCtx = context.applicationContext
    private val pending = mutableListOf<() -> Unit>()

    /** 0 = initialising, 1 = ready, -1 = engine failed. Main thread only. */
    private var state = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        state = if (status == TextToSpeech.SUCCESS) 1 else -1
        pending.forEach { it() }
        pending.clear()
    }

    /** Calls [result] with "ok", "missing" or "error". */
    fun speak(text: String, locale: String, result: (String) -> Unit, onDone: (() -> Unit)? = null) {
        val run = run@{
            if (state != 1) {
                result("error")
                return@run
            }
            val r = tts.setLanguage(Locale.forLanguageTag(locale))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                result("missing")
                onDone?.invoke()
                return@run
            }
            tts.setSpeechRate(rate(appCtx))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { onDone?.invoke() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { onDone?.invoke() }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vocab")
            result("ok")
        }
        if (state == 0) pending.add(run) else run()
    }

    fun shutdown() = tts.shutdown()

    /** Renders [text] to a WAV file (online voices download once), then calls [done] with success. */
    fun synthesize(text: String, locale: String, out: File, done: (Boolean) -> Unit) {
        val run = run@{
            if (state != 1) {
                done(false)
                return@run
            }
            val r = tts.setLanguage(Locale.forLanguageTag(locale))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                done(false)
                return@run
            }
            tts.setSpeechRate(rate(appCtx))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done(out.length() > 0) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { done(false) }
            })
            out.parentFile?.mkdirs()
            if (tts.synthesizeToFile(text, null, out, "file") != TextToSpeech.SUCCESS) done(false)
        }
        if (state == 0) pending.add(run) else run()
    }

    companion object {
        private const val PREFS = "vocab_tts"

        private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "tts")

        /** Cache file for this text/voice/speed. */
        private fun cacheFile(ctx: Context, text: String, locale: String): File {
            val key = "$locale|${rate(ctx)}|$text"
            val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(cacheDir(ctx), "$hash.wav")
        }

        /** Drops cached audio (called when the widget moves to another word). */
        fun clearCache(ctx: Context) {
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
        }

        /**
         * Widget speech: plays the cached WAV instantly if this word was spoken
         * before, otherwise synthesizes it to the cache first (then plays).
         * [finished] runs after playback (or on failure / timeout).
         */
        fun speakCached(context: Context, text: String, locale: String, finished: () -> Unit) {
            val main = Handler(Looper.getMainLooper())
            var closed = false
            var player: MediaPlayer? = null
            var speaker: Speaker? = null
            val close = {
                if (!closed) {
                    closed = true
                    main.post {
                        player?.release()
                        speaker?.shutdown()
                        finished()
                    }
                }
            }
            main.postDelayed({ close() }, 9000)
            val file = cacheFile(context, text, locale)
            fun play() {
                try {
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        )
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { close() }
                        prepare()
                        start()
                    }
                } catch (_: Exception) {
                    file.delete()
                    close()
                }
            }
            if (file.length() > 0) {
                play()
                return
            }
            speaker = Speaker(context)
            speaker!!.synthesize(text, locale, file) { ok ->
                main.post {
                    if (ok) {
                        play()
                    } else {
                        // Synthesis failed (no voice / engine quirk): speak directly instead.
                        speaker?.speak(text, locale, { r ->
                            if (r != "ok") close()
                        }, onDone = { close() })
                    }
                }
            }
        }

        /** Speech rate shared by the app and widgets (1.0 = engine default). */
        fun rate(ctx: Context): Float = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("rate", 0.8f)

        fun setRate(ctx: Context, rate: Float) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("rate", rate.coerceIn(0.3f, 1.5f)).apply()
        }

        /** One-shot speech for widget clicks; cleans up after itself. */
        fun speakOnce(context: Context, text: String, locale: String, finished: () -> Unit) {
            val main = Handler(Looper.getMainLooper())
            var speaker: Speaker? = null
            var closed = false
            val close = {
                if (!closed) {
                    closed = true
                    main.post {
                        speaker?.shutdown()
                        finished()
                    }
                }
            }
            main.postDelayed({ close() }, 9000) // receivers get ~10 s
            speaker = Speaker(context)
            speaker.speak(text, locale, { r ->
                if (r != "ok") {
                    if (r == "missing") {
                        main.post {
                            android.widget.Toast.makeText(
                                context, "Máy chưa có giọng đọc $locale", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    close()
                }
            }, onDone = { close() })
        }
    }
}
