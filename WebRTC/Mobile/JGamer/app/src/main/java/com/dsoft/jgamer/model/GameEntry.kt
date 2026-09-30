package com.dsoft.jgamer.model

import org.json.JSONObject

/** One ROM in the library, copied into app-private storage for a stable path. */
data class GameEntry(
    val id: String,
    var title: String,
    val systemId: String,
    val localPath: String,
    var addedAt: Long = 0L,
    var lastPlayedAt: Long = 0L,
    var playCount: Int = 0,
    /** SAF uri of the file it was imported from (to delete the original). */
    var sourceUri: String? = null
) {
    val system: GameSystem get() = GameSystem.fromId(systemId)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("systemId", systemId)
        put("localPath", localPath); put("addedAt", addedAt)
        put("lastPlayedAt", lastPlayedAt); put("playCount", playCount)
        sourceUri?.let { put("sourceUri", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = GameEntry(
            id = o.getString("id"),
            title = o.optString("title", "Untitled"),
            systemId = o.optString("systemId", "nes"),
            localPath = o.getString("localPath"),
            addedAt = o.optLong("addedAt", 0L),
            lastPlayedAt = o.optLong("lastPlayedAt", 0L),
            playCount = o.optInt("playCount", 0),
            sourceUri = o.optString("sourceUri", "").ifBlank { null }
        )
    }
}
