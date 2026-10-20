package com.dsoft.autotap

import android.content.Context
import java.io.File

/**
 * Persists all scenarios as JSON in filesDir/scenarios.json with an atomic
 * write (temp file + rename) so a crash mid-write never corrupts the list.
 */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, FILE)
    private val tmp = File(ctx.filesDir, "$FILE.tmp")

    fun load(): MutableList<Scenario> = try {
        if (!file.exists()) mutableListOf()
        else ScenarioJson.decodeList(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        mutableListOf()
    }

    @Synchronized
    fun save(list: List<Scenario>) {
        try {
            tmp.writeText(ScenarioJson.encodeList(list), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // Fallback: copy bytes if rename across the same dir somehow fails.
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun byId(id: String): Scenario? = load().firstOrNull { it.id == id }

    /** Looks a scenario up by id first, then by case-insensitive name. */
    fun find(nameOrId: String): Scenario? {
        val list = load()
        return list.firstOrNull { it.id == nameOrId }
            ?: list.firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }
    }

    fun upsert(s: Scenario) {
        val list = load()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        save(list)
    }

    fun delete(id: String) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }

    companion object {
        private const val FILE = "scenarios.json"
        fun newId(): String = "s" + System.currentTimeMillis().toString(36) + (0..999).random()
    }
}
