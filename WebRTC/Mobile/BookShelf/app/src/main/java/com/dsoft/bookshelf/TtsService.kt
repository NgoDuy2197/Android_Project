package com.dsoft.bookshelf

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live read-aloud state, observed by the text reader for highlighting. */
object TtsBus {
    data class State(val bookId: String = "", val textBookId: String = "", val playing: Boolean = false,
                     val start: Int = -1, val end: Int = -1, val active: Boolean = false)

    @Volatile var state = State()
        private set
    /** Epoch ms when the sleep timer will stop playback, or 0 if none. */
    @Volatile var sleepAtMs = 0L
    private val listeners = ArrayList<(State) -> Unit>()

    fun set(s: State) {
        state = s
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it(s) } }
    }

    fun addListener(l: (State) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (State) -> Unit) = synchronized(listeners) { listeners -= l }
}

/**
 * Read-aloud foreground service (type mediaPlayback) with a media-style notification.
 * Text is split into sentences, grouped ~3 per utterance and queued with QUEUE_ADD; the position (char offset of the
 * current utterance) is saved per book so "Continue listening" resumes where it stopped.
 */
class TtsService : Service() {
    private var tts: TextToSpeech? = null
    private var session: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private var book: Book? = null
    private var textBook: Book? = null
    private var text: String = ""
    private var chunks: List<Span> = emptyList()
    private var current = 0
    private var queuedUpTo = -1
    private var playing = false
    private var generation = 0
    private var errorsInRow = 0
    private var sleepRunnable: Runnable? = null
    private var sleepAtMs = 0L

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "BookShelfTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stopAll()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(buildNotification())
        if (intent?.action != ACTION_PLAY && book == null && generation == 0) {
            stopAll(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK) ?: return START_NOT_STICKY
                val from = intent.getIntExtra(EXTRA_FROM, -1)
                load(id, from)
            }
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_SLEEP -> setSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_STOP -> stopAll()
            else -> if (book == null) stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try { ServiceCompat.startForeground(this, NOTIF_ID, n, type) } catch (_: Exception) {}
    }

    private fun load(bookId: String, from: Int) {
        val gen = ++generation
        stopSpeaking()
        App.scope.launch(Dispatchers.IO) {
            try {
                val b = Library.get(bookId) ?: throw UserError(getString(R.string.err_book_missing))
                val tb = Library.textSourceOf(b) ?: throw UserError(getString(R.string.err_need_text))
                val t = Files.readText(Library.file(tb))
                if (t.isBlank()) throw UserError(getString(R.string.err_empty_text))
                val cs = TextLogic.utterances(t, maxSentences = 3, maxChars = 400)
                val engine = tts ?: TtsUtil.create(this@TtsService)
                withContext(Dispatchers.Main) {
                    if (gen != generation) return@withContext
                    tts = engine
                    engine.setOnUtteranceProgressListener(listener)
                    TtsUtil.apply(this@TtsService, engine, textSample = t.take(3000))
                    book = b; textBook = tb; text = t; chunks = cs
                    val offset = if (from >= 0) from else Library.state(tb.id).ttsOffset
                    current = TextLogic.indexAt(cs, offset).coerceAtLeast(0)
                    if (cs.isNotEmpty() && current < cs.size && offset >= cs[current].end && current + 1 < cs.size) current++
                    if (from >= 0) savePos()
                    Prefs.setLastBook(this@TtsService, b.id)
                    resume()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    toast(e.message ?: e.javaClass.simpleName)
                    if (book == null) stopAll()
                }
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) {
            val idx = id.substringAfter('#').toIntOrNull() ?: return
            val gen = id.substringBefore('#').toIntOrNull() ?: return
            App.main.post {
                if (gen != generation || !playing) return@post
                errorsInRow = 0
                current = idx
                savePos()
                publish()
            }
        }

        override fun onDone(id: String) {
            val idx = id.substringAfter('#').toIntOrNull() ?: return
            val gen = id.substringBefore('#').toIntOrNull() ?: return
            App.main.post {
                if (gen != generation || !playing) return@post
                if (idx >= chunks.size - 1) {
                    // finished the book
                    current = chunks.size - 1
                    Library.saveState(textBook?.id ?: return@post) { it.copy(ttsOffset = 0) }
                    playing = false
                    publish()
                    stopAll()
                } else {
                    current = idx + 1
                    fillQueue()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(id: String) = onError(id, -1)
        override fun onError(id: String, errorCode: Int) {
            val gen = id.substringBefore('#').toIntOrNull() ?: return
            App.main.post {
                if (gen != generation || !playing) return@post
                errorsInRow++
                if (errorsInRow >= 3) {
                    // e.g. a network voice while offline: stop instead of skipping through the whole book
                    errorsInRow = 0
                    pause()
                    toast(getString(R.string.err_tts_synth, errorCode))
                } else onDone(id)
            }
        }
    }

    private fun fillQueue() {
        val t = tts ?: return
        val base = (Prefs.ttsPitch(this) / 100f).coerceIn(0.3f, 2f)
        val emphasis = Prefs.quoteEmphasis(this)
        val target = minOf(chunks.size - 1, current + 2)
        while (queuedUpTo < target) {
            val i = queuedUpTo + 1
            val s = text.substring(chunks[i].start, chunks[i].end)
            val flushFirst = queuedUpTo < current
            val segs = if (emphasis && TextLogic.hasQuote(s)) TextLogic.quoteSplit(s).filter { it.first.isNotBlank() } else emptyList()
            if (segs.size > 1) {
                // Dialogue in quotes reads a bit higher. Only the final segment carries the chunk's
                // tracking id ("#i"); earlier ones use an id onStart/onDone ignore.
                segs.forEachIndexed { si, (seg, inQ) ->
                    runCatching { t.setPitch((base * if (inQ) 1.18f else 1f).coerceIn(0.3f, 2f)) }
                    val mode = if (flushFirst && si == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val id = if (si == segs.lastIndex) "$generation#$i" else "$generation#s$i-$si"
                    t.speak(seg, mode, Bundle(), id)
                }
                runCatching { t.setPitch(base) }
            } else {
                runCatching { t.setPitch(base) }
                t.speak(s, if (flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "$generation#$i")
            }
            queuedUpTo = i
        }
    }

    private fun resume() {
        if (tts == null || chunks.isEmpty()) return
        if (!requestFocus()) return
        acquireWake()
        playing = true
        generation++
        queuedUpTo = current - 1
        fillQueue()
        publish()
    }

    private fun pause() {
        if (!playing) return
        playing = false
        generation++
        stopSpeaking()
        savePos()
        releaseWake()
        publish()
    }

    private fun skip(delta: Int) {
        if (chunks.isEmpty()) return
        current = (current + delta).coerceIn(0, chunks.size - 1)
        savePos()
        if (playing) {
            generation++
            queuedUpTo = current - 1
            fillQueue()
        }
        publish()
    }

    private fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    /** Auto-stop reading after [minutes] (0 = cancel the timer). */
    private fun setSleep(minutes: Int) {
        sleepRunnable?.let { App.main.removeCallbacks(it) }
        sleepRunnable = null
        sleepAtMs = 0L
        if (minutes > 0) {
            sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
            val r = Runnable { sleepAtMs = 0L; sleepRunnable = null; pause() }
            sleepRunnable = r
            App.main.postDelayed(r, minutes * 60_000L)
        }
        TtsBus.sleepAtMs = sleepAtMs
        publish()
    }

    private fun savePos() {
        val tb = textBook ?: return
        if (chunks.isEmpty()) return
        val off = chunks[current.coerceIn(0, chunks.size - 1)].start
        Library.saveState(tb.id) { it.copy(ttsOffset = off) }
    }

    private fun publish() {
        val b = book
        val tb = textBook
        val span = chunks.getOrNull(current)
        TtsBus.set(TtsBus.State(b?.id ?: "", tb?.id ?: "", playing, span?.start ?: -1, span?.end ?: -1, active = b != null))
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, current.toLong(), 1f)
                .build()
        )
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, b?.title ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build()
        )
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, TtsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val b = book
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, if (b != null && !b.isPdf) TextReaderActivity::class.java else MainActivity::class.java)
                .putExtra(Readers.EXTRA_BOOK, b?.id ?: "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snippet = chunks.getOrNull(current)?.let { text.substring(it.start, it.end).take(120) } ?: getString(R.string.tts_loading)
        val sleepLeft = if (sleepAtMs > 0) ((sleepAtMs - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(0) else 0
        val progress = (if (chunks.isNotEmpty()) " · ${(current + 1) * 100 / chunks.size}%" else "") +
            (if (sleepLeft > 0) " · ⏰ ${sleepLeft}′" else "")
        return NotificationCompat.Builder(this, App.CH_TTS)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle((b?.title ?: getString(R.string.app_name)) + progress)
            .setContentText(snippet)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(pi(ACTION_STOP, 14))
            .addAction(R.drawable.ic_prev, getString(R.string.tts_prev), pi(ACTION_PREV, 10))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.btn_pause else R.string.tts_play), pi(ACTION_TOGGLE, 11)
            )
            .addAction(R.drawable.ic_next, getString(R.string.tts_next), pi(ACTION_NEXT, 12))
            .addAction(R.drawable.ic_stop, getString(R.string.tts_stop), pi(ACTION_STOP, 13))
            .setStyle(MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) App.main.post { pause() }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val res = if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
            am.requestAudioFocus(req)
        } else am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) } else am.abandonAudioFocus(focusListener)
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookShelf:tts").apply { acquire(4 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopAll() {
        if (playing) savePos()
        sleepRunnable?.let { App.main.removeCallbacks(it) }; sleepRunnable = null; sleepAtMs = 0L; TtsBus.sleepAtMs = 0L
        playing = false
        generation++
        stopSpeaking()
        abandonFocus()
        releaseWake()
        TtsBus.set(TtsBus.State())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (playing) savePos()
        generation++
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        session?.release()
        releaseWake()
        TtsBus.set(TtsBus.State())
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 43
        const val ACTION_PLAY = "play"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val ACTION_SLEEP = "sleep"
        const val EXTRA_BOOK = "book"
        const val EXTRA_FROM = "from"
        const val EXTRA_MINUTES = "min"

        /** Sets (minutes > 0) or cancels (0) the auto-stop sleep timer. */
        fun sleep(ctx: Context, minutes: Int) {
            try {
                ctx.startService(Intent(ctx, TtsService::class.java).setAction(ACTION_SLEEP).putExtra(EXTRA_MINUTES, minutes))
            } catch (_: Exception) {}
        }

        /** Starts reading [book] from the saved position, or from char offset [from] of its text. */
        fun play(ctx: Context, book: Book, from: Int = -1) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TtsService::class.java)
                .setAction(ACTION_PLAY).putExtra(EXTRA_BOOK, book.id).putExtra(EXTRA_FROM, from))
        }

        fun command(ctx: Context, action: String) {
            try { ctx.startService(Intent(ctx, TtsService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
