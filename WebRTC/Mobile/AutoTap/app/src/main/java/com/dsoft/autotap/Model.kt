package com.dsoft.autotap

import org.json.JSONArray
import org.json.JSONObject

/**
 * One automation step. Pure data; no Android types so it can be unit-tested on the JVM.
 * [type] is the stable string written to JSON.
 */
data class Step(
    val type: String,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val durationMs: Long = 0,
    val text: String = "",
    /** For TAP_TEXT: which match to tap (0-based). */
    val occurrence: Int = 0,
    /** For TAP_TEXT: only search inside this rect (screen px) when [hasRegion]. */
    val hasRegion: Boolean = false,
    val regionLeft: Int = 0,
    val regionTop: Int = 0,
    val regionRight: Int = 0,
    val regionBottom: Int = 0,
) {
    companion object {
        const val TAP = "TAP"
        const val LONG_PRESS = "LONG_PRESS"
        const val SWIPE = "SWIPE"
        const val WAIT = "WAIT"
        const val TAP_TEXT = "TAP_TEXT"
        const val BACK = "BACK"
        const val HOME = "HOME"
        const val RECENTS = "RECENTS"
        const val INPUT = "INPUT"

        val ALL = listOf(TAP, LONG_PRESS, SWIPE, WAIT, TAP_TEXT, BACK, HOME, RECENTS, INPUT)

        fun tap(x: Int, y: Int) = Step(TAP, x = x, y = y)
        fun longPress(x: Int, y: Int, ms: Long) = Step(LONG_PRESS, x = x, y = y, durationMs = ms)
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = Step(SWIPE, x = x1, y = y1, x2 = x2, y2 = y2, durationMs = ms)
        fun wait(ms: Long) = Step(WAIT, durationMs = ms)
        fun tapText(text: String, occurrence: Int = 0) = Step(TAP_TEXT, text = text, occurrence = occurrence)
        fun input(text: String) = Step(INPUT, text = text)

        fun fromJson(o: JSONObject): Step = Step(
            type = o.optString("type", TAP),
            x = o.optInt("x"),
            y = o.optInt("y"),
            x2 = o.optInt("x2"),
            y2 = o.optInt("y2"),
            durationMs = o.optLong("durationMs"),
            text = o.optString("text", ""),
            occurrence = o.optInt("occurrence"),
            hasRegion = o.optBoolean("hasRegion"),
            regionLeft = o.optInt("regionLeft"),
            regionTop = o.optInt("regionTop"),
            regionRight = o.optInt("regionRight"),
            regionBottom = o.optInt("regionBottom"),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        if (x != 0) put("x", x)
        if (y != 0) put("y", y)
        if (x2 != 0) put("x2", x2)
        if (y2 != 0) put("y2", y2)
        if (durationMs != 0L) put("durationMs", durationMs)
        if (text.isNotEmpty()) put("text", text)
        if (occurrence != 0) put("occurrence", occurrence)
        if (hasRegion) {
            put("hasRegion", true)
            put("regionLeft", regionLeft)
            put("regionTop", regionTop)
            put("regionRight", regionRight)
            put("regionBottom", regionBottom)
        }
    }

    /** Human-readable, Vietnamese summary for list rows. */
    fun describe(): String = when (type) {
        TAP -> "Chạm ($x, $y)"
        LONG_PRESS -> "Giữ ($x, $y) · ${durationMs}ms"
        SWIPE -> "Vuốt ($x,$y)→($x2,$y2) · ${durationMs}ms"
        WAIT -> "Chờ ${durationMs}ms"
        TAP_TEXT -> "Chạm chữ \"$text\"" + if (occurrence > 0) " #${occurrence + 1}" else ""
        BACK -> "Nút Back"
        HOME -> "Nút Home"
        RECENTS -> "Ứng dụng gần đây"
        INPUT -> "Nhập chữ \"$text\""
        else -> type
    }
}

/**
 * An ordered list of steps that runs as a unit. Pure data.
 */
data class Scenario(
    val id: String,
    var name: String,
    val steps: MutableList<Step> = mutableListOf(),
    var repeatCount: Int = 1,
    var defaultDelayMs: Long = 300,
) {
    companion object {
        fun fromJson(o: JSONObject): Scenario {
            val steps = ArrayList<Step>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) steps.add(Step.fromJson(arr.getJSONObject(i)))
            return Scenario(
                id = o.optString("id"),
                name = o.optString("name", "Kịch bản"),
                steps = steps,
                repeatCount = o.optInt("repeatCount", 1),
                defaultDelayMs = o.optLong("defaultDelayMs", 300),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("repeatCount", repeatCount)
        put("defaultDelayMs", defaultDelayMs)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
    }
}

/**
 * Pure JSON <-> model conversion for the whole library. No Android types; unit-tested.
 */
object ScenarioJson {
    fun encode(s: Scenario): String = s.toJson().toString()

    fun decode(json: String): Scenario = Scenario.fromJson(JSONObject(json))

    fun encodeList(list: List<Scenario>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return JSONObject().put("scenarios", arr).toString()
    }

    fun decodeList(json: String): MutableList<Scenario> {
        val out = ArrayList<Scenario>()
        if (json.isBlank()) return out
        val root = JSONObject(json)
        val arr = root.optJSONArray("scenarios") ?: return out
        for (i in 0 until arr.length()) out.add(Scenario.fromJson(arr.getJSONObject(i)))
        return out
    }
}

/**
 * Placeholder substitution for step string fields. Replaces `{{key}}` with the
 * matching value from [params]; unknown placeholders are left untouched. Pure.
 */
object Params {
    private val RE = Regex("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}")

    fun substitute(text: String, params: Map<String, String>): String {
        if (text.isEmpty() || params.isEmpty()) return text
        return RE.replace(text) { m ->
            val key = m.groupValues[1]
            params[key] ?: m.value
        }
    }

    /** Returns a copy of [step] with placeholders in its text field substituted. */
    fun apply(step: Step, params: Map<String, String>): Step =
        if (step.text.isEmpty()) step else step.copy(text = substitute(step.text, params))
}
