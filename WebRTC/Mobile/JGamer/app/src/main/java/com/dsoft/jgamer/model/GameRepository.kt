package com.dsoft.jgamer.model

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Library + recent list, backed by a JSON index. Imported ROMs are copied into
 * filesDir/roms/<system>/ so playback never depends on a volatile content URI.
 * All IO is guarded so a bad file skips instead of crashing.
 */
class GameRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val romsDir = File(app.filesDir, "roms").apply { runCatching { mkdirs() } }
    private val indexFile = File(app.filesDir, "library.json")
    private val entries = mutableListOf<GameEntry>()

    init { load() }

    fun all(): List<GameEntry> = entries.sortedByDescending { it.addedAt }

    fun bySystem(system: GameSystem): List<GameEntry> =
        entries.filter { it.systemId == system.id }.sortedBy { it.title.lowercase() }

    fun recent(limit: Int = 20): List<GameEntry> =
        entries.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(limit)

    fun byId(id: String): GameEntry? = entries.firstOrNull { it.id == id }

    fun mostRecent(): GameEntry? = entries.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }

    fun markPlayed(id: String, now: Long) {
        byId(id)?.let { it.lastPlayedAt = now; it.playCount++; save() }
    }

    fun rename(id: String, title: String) {
        byId(id)?.let { it.title = title.trim().ifBlank { it.title }; save() }
    }

    fun remove(id: String) {
        val e = byId(id) ?: return
        runCatching { File(e.localPath).takeIf { it.exists() }?.delete() }
        entries.remove(e); save()
    }

    /** Import a ROM stream into a system's folder. Returns entry or null (never throws). */
    fun import(input: InputStream, displayName: String, system: GameSystem, now: Long): GameEntry? =
        runCatching {
            val clean = displayName.substringAfterLast('/').substringAfterLast('\\')
            val base = clean.substringBeforeLast('.').ifBlank { "game" }
            val ext = clean.substringAfterLast('.', "bin")

            val dest: File
            val id: String
            if (system.zipIsRom) {
                // ARCADE: the core identifies the romset by the ORIGINAL zip name
                // (e.g. mslug.zip). Never rename it, or FBNeo/MAME report
                // "Romset is unknown". All arcade zips share one folder so BIOS /
                // parent sets can sit alongside and be found by the core.
                val dir = File(romsDir, "arcade").apply { mkdirs() }
                dest = File(dir, clean)
                id = ("arcade_" + base).replace(Regex("[^A-Za-z0-9._-]"), "_").take(110)
            } else {
                val dir = File(romsDir, system.id).apply { mkdirs() }
                id = "${system.id}_${base}_${now}".replace(Regex("[^A-Za-z0-9._-]"), "_").take(110)
                dest = File(dir, "$id.$ext")
            }
            dest.outputStream().use { out -> input.copyTo(out) }

            // Re-import of the same romset updates in place (keeps play history).
            entries.firstOrNull { it.id == id }?.let { save(); return@runCatching it }
            val entry = GameEntry(id, prettify(base), system.id, dest.absolutePath, now)
            entries.add(entry); save()
            entry
        }.onFailure { Log.e(TAG, "import failed: $displayName", it) }.getOrNull()

    fun importFromUri(context: Context, uri: Uri, name: String, system: GameSystem, now: Long): GameEntry? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { import(it, name, system, now) }
        }.getOrNull()

    /**
     * Import for a specific system. If the file is a .zip, the first entry whose
     * extension matches [system] is auto-extracted and imported. Returns null if
     * nothing matched (e.g. wrong system / empty zip). Never throws.
     */
    fun importForSystem(context: Context, uri: Uri, name: String, system: GameSystem, now: Long): GameEntry? =
        runCatching {
            // Arcade: the .zip IS the romset — copy it whole, never extract.
            if (system.zipIsRom) {
                return@runCatching if (GameSystem.matchesSystem(name, system))
                    context.contentResolver.openInputStream(uri)?.use { import(it, name, system, now) }
                else null
            }
            if (name.lowercase().endsWith(".zip")) {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            if (!e.isDirectory && GameSystem.matchesSystem(e.name, system)) {
                                val inner = e.name.substringAfterLast('/').substringAfterLast('\\')
                                return@runCatching import(zis, inner, system, now)
                            }
                            e = zis.nextEntry
                        }
                        null
                    }
                }
            } else if (GameSystem.matchesSystem(name, system)) {
                context.contentResolver.openInputStream(uri)?.use { import(it, name, system, now) }
            } else {
                null
            }
        }.getOrNull()

    private fun prettify(name: String) =
        name.replace('_', ' ').replace('-', ' ').trim()
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Untitled" }

    private fun load() {
        entries.clear()
        runCatching {
            if (!indexFile.exists()) return
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) runCatching { entries.add(GameEntry.fromJson(arr.getJSONObject(i))) }
        }.onFailure { Log.w(TAG, "index load failed", it) }
    }

    private fun save() {
        runCatching {
            val arr = JSONArray(); entries.forEach { arr.put(it.toJson()) }
            indexFile.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "index save failed", it) }
    }

    companion object {
        private const val TAG = "GameRepository"
        @Volatile private var instance: GameRepository? = null
        fun get(context: Context): GameRepository =
            instance ?: synchronized(this) { instance ?: GameRepository(context).also { instance = it } }
    }
}
