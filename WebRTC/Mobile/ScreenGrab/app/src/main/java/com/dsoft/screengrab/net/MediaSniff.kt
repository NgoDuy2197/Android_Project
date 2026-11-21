package com.dsoft.screengrab.net

/** Decides whether a request/response is a downloadable video/audio and files it in [MediaCatalog]. */
object MediaSniff {

    private val FILE_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a", "aac", "flac", "ogg", "wav", "3gp")
    // Stream *segments* — parts of an HLS/DASH stream; we download the playlist, not each part, so skip.
    private val SEGMENT_EXT = setOf("ts", "m4s")
    private val REPLAY_HEADERS = setOf("referer", "cookie", "user-agent", "origin", "authorization", "x-forwarded-for")

    fun inspect(method: String, url: String, host: String, contentType: String, size: Long, reqHeaders: Map<String, String>) {
        if (!method.equals("GET", true) && !method.equals("HEAD", true)) return
        val ct = contentType.lowercase()
        val ext = extensionOf(url)

        if (ext in SEGMENT_EXT || ct.contains("video/mp2t") || ct.contains("iso.segment")) return

        val kind: MediaItem.Kind = when {
            ext == "m3u8" || ct.contains("mpegurl") -> MediaItem.Kind.HLS
            ext == "mpd" || ct.contains("dash+xml") -> MediaItem.Kind.DASH
            ext in FILE_EXT || ct.startsWith("video/") || ct.startsWith("audio/") -> MediaItem.Kind.FILE
            else -> return
        }

        val headers = reqHeaders.filterKeys { it in REPLAY_HEADERS }
        MediaCatalog.offer(url, kind, host, titleOf(url, host), contentType, size, headers)
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase()
    }

    private fun titleOf(url: String, host: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val clean = name.replace('_', ' ').replace('-', ' ').trim()
        return if (clean.length in 1..60 && !clean.equals("index", true) &&
            !clean.equals("master", true) && !clean.equals("playlist", true)) clean else host
    }
}
