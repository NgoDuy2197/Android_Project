package com.dsoft.healthassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Turns a profile + the day's (or one) meal into a nutrition "Xem xét" review.
 * Two backends: Google Gemini (online API) or an imported on-device model (MediaPipe LLM).
 * All numbers are estimates — surfaced as such in the UI.
 */
object Ai {
    private val main = Handler(Looper.getMainLooper())

    /** Parsed review. [estimated] = produced by the local rough estimator (no AI). */
    class Review(
        val calories: Int,
        val protein: Int,
        val carb: Int,
        val fat: Int,
        val items: List<Pair<String, Int>>,
        val assessment: String,
        val tips: List<String>,
        val warnings: List<String>,
        val raw: String,
        val estimated: Boolean = false,
    ) {
        /** Normalized JSON (same schema the model returns) for caching, so any source round-trips. */
        fun toJson(): String = JSONObject().apply {
            put("total_calories", calories); put("protein_g", protein); put("carb_g", carb); put("fat_g", fat)
            put("items", org.json.JSONArray().apply {
                items.forEach { put(JSONObject().put("name", it.first).put("calories", it.second)) }
            })
            put("assessment", assessment)
            put("tips", org.json.JSONArray().apply { tips.forEach { put(it) } })
            put("warnings", org.json.JSONArray().apply { warnings.forEach { put(it) } })
        }.toString()
    }

    val GEMINI_MODELS = listOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-1.5-flash",
    )

    private const val TAG = "HA_AI"

    /** Runs the review on a background thread; [cb] is posted on the main thread. */
    fun review(ctx: Context, scope: String, mealsText: String, cb: (Review?, String?) -> Unit) {
        Thread {
            try { val r = reviewBlocking(ctx, scope, mealsText); main.post { cb(r, null) } }
            catch (t: Throwable) { Log.e(TAG, "review failed", t); main.post { cb(null, friendly(t)) } }
        }.start()
    }

    /**
     * Synchronous review — throws on failure (including [Error] such as OOM, which a plain
     * `catch (Exception)` would miss and leave the UI spinning forever). Used by [ReviewQueue].
     */
    fun reviewBlocking(ctx: Context, scope: String, mealsText: String): Review {
        val prefs = Prefs(ctx)
        val profile = Store.loadProfile(ctx)
        if (mealsText.isBlank()) throw IllegalStateException(tr("Chưa có món ăn nào để xem xét.", "No food to review yet."))
        val prompt = buildPrompt(profile, scope, mealsText)
        val t0 = System.currentTimeMillis()
        DiagLog.i(TAG, "Bắt đầu xem xét — chế độ=${prefs.engine}, phạm vi=$scope, prompt ${prompt.length} ký tự")
        val raw = when (prefs.engine) {
            "offline" -> OfflineClient.run(ctx, prefs, prompt)
            else -> GeminiClient.run(prefs, prompt)
        }
        DiagLog.i(TAG, "Có phản hồi sau ${System.currentTimeMillis() - t0}ms (${raw.length} ký tự)")
        val parsed = parse(raw)
        if (parsed == null) {
            DiagLog.w(TAG, "Không đọc được JSON. Output thô (${raw.length} ký tự): ${raw.take(800).replace("\n", " ")}")
            return Review(0, 0, 0, 0, emptyList(),
                tr("Model trả về dữ liệu không đọc được (JSON lỗi). Mở “Nhật ký chẩn đoán” để xem output thô, hoặc Thử lại / dùng Gemini.",
                    "The model returned unreadable data (bad JSON). Open the diagnostic log for the raw output, or try again / use Gemini."),
                emptyList(), emptyList(), raw)
        }
        DiagLog.i(TAG, "Đã đọc JSON: ${parsed.calories} kcal, ${parsed.items.size} món")
        return parsed
    }

    /** Turn any failure (Exception or Error) into a short Vietnamese message. */
    fun friendly(t: Throwable): String = when (t) {
        is OutOfMemoryError -> tr("Hết bộ nhớ khi nạp/chạy model — model quá lớn cho máy này. Thử model nhỏ hơn (q4) hoặc dùng Gemini.",
            "Out of memory loading/running the model — too large for this device. Try a smaller model (q4) or use Gemini.")
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    /** Free a loaded offline model (call after importing a different one). */
    fun resetOffline() = OfflineClient.reset()

    /** Quick connectivity/sanity check for one engine; [cb] on the main thread (output, error). */
    fun test(ctx: Context, engine: String, cb: (String?, String?) -> Unit) {
        val prefs = Prefs(ctx)
        Thread {
            try {
                val out = if (engine == "offline") OfflineClient.run(ctx, prefs, "Trả lời ngắn: xin chào")
                else GeminiClient.run(prefs, "Trả lời đúng một từ: OK")
                main.post { cb(out.trim().take(120), null) }
            } catch (t: Throwable) {
                Log.e(TAG, "test($engine) failed", t)
                main.post { cb(null, friendly(t)) }
            }
        }.start()
    }

    fun buildPrompt(p: Profile, scope: String, mealsText: String): String {
        val goalLine = when (p.goal) {
            "lose" -> "giảm cân xuống ${fmt(p.targetWeightKg)}kg (khoảng ${fmt(p.weeklyRateKg)}kg/tuần)"
            "gain" -> "tăng cân lên ${fmt(p.targetWeightKg)}kg (khoảng ${fmt(p.weeklyRateKg)}kg/tuần)"
            else -> "giữ cân quanh ${fmt(p.targetWeightKg)}kg"
        }
        val notes = if (p.notes.isBlank()) "không có" else p.notes
        return """
Bạn là chuyên gia dinh dưỡng. Ước tính dinh dưỡng cho $scope và nhận xét theo hồ sơ người dùng.

HỒ SƠ:
- Giới tính: ${if (p.gender == "female") "nữ" else "nam"}, tuổi ${p.age}
- Cao ${fmt(p.heightCm)} cm, nặng ${fmt(p.weightKg)} kg, BMI ${fmt(p.bmi())} (${p.bmiLabel()})
- Mức vận động: ${p.activityLabel()}
- Nhu cầu duy trì (TDEE): ~${p.tdee()} kcal/ngày; mục tiêu calo: ~${p.targetCalories()} kcal/ngày
- Mục tiêu: $goalLine
- Thông tin thêm: $notes

MÓN ĂN ($scope):
$mealsText

Hãy ước tính và CHỈ trả về một JSON THUẦN (không bọc trong ```), đúng các khoá sau
(số là kcal/gram không kèm chữ, phần văn bản bằng tiếng Việt):
{"total_calories": <số>, "protein_g": <số>, "carb_g": <số>, "fat_g": <số>,
 "items": [{"name": "<tên món thật>", "calories": <số>}],
 "assessment": "<nhận xét ngắn so với mục tiêu calo và sức khoẻ>",
 "tips": ["<gợi ý>"],
 "warnings": ["<cảnh báo nếu có>"]}

QUY TẮC BẮT BUỘC:
- total_calories PHẢI bằng tổng "calories" của tất cả items.
- Điền tên món thật từ danh sách trên, KHÔNG để chữ "tên món".
- Mọi con số là ƯỚC TÍNH. Trả về JSON hợp lệ, đóng đủ ngoặc, KHÔNG thêm chữ nào ngoài JSON.${if (I18n.en) "\n- IMPORTANT: write ALL text values (item names, assessment, tips, warnings) in ENGLISH." else ""}
""".trim()
    }

    /**
     * Tolerant JSON parse. Strips markdown code fences, extracts the first balanced {...} object
     * (repairing a truncated tail — common with small offline models that hit the token limit),
     * then sanity-fixes the numbers (e.g. total_calories = sum of items when the model left it 0).
     */
    fun parse(text: String): Review? = parseInternal(text, 0) ?: salvage(text)

    private fun parseInternal(text: String, depth: Int): Review? {
        val json = extractJsonObject(stripFences(text)) ?: return null
        val j = try {
            JSONObject(json)
        } catch (_: Exception) {
            // second chance: drop trailing commas (",}" / ",]") that org.json rejects
            try { JSONObject(json.replace(Regex(",\\s*([}\\]])"), "$1")) } catch (_: Exception) { return null }
        }
        return try {
            val items = ArrayList<Pair<String, Int>>()
            j.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim().let { if (it.isBlank() || it == "tên món") "Món ăn" else it }
                    items.add(name to looseInt(o, "calories"))
                }
            }
            var cal = looseInt(j, "total_calories")
            var protein = looseInt(j, "protein_g")
            var carb = looseInt(j, "carb_g")
            var fat = looseInt(j, "fat_g")
            val assessment = j.optString("assessment").trim()
            // recover data a previous fallback wrapped into "assessment" (the "0 kcal + raw JSON" bug)
            if (depth < 2 && cal <= 0 && items.isEmpty() && assessment.contains("total_calories"))
                parseInternal(assessment, depth + 1)?.let { return it }
            val itemsSum = items.sumOf { it.second }
            // models sometimes leave total_calories = 0 while filling items — recompute from items
            if (cal <= 0 && itemsSum > 0) cal = itemsSum
            // if macros are all missing but we have calories, split with a typical 20/50/30 ratio
            if (cal > 0 && protein == 0 && carb == 0 && fat == 0) {
                protein = (cal * 0.20 / 4).roundToInt(); carb = (cal * 0.50 / 4).roundToInt(); fat = (cal * 0.30 / 9).roundToInt()
            }
            if (cal <= 0 && items.isEmpty() && assessment.isBlank()) return null // nothing usable
            Review(
                calories = cal, protein = protein, carb = carb, fat = fat,
                items = items,
                assessment = assessment,
                tips = strList(j, "tips"),
                warnings = strList(j, "warnings"),
                raw = text,
            )
        } catch (_: Exception) { null }
    }

    /** Int that tolerates numeric strings and trailing units, e.g. "1995" or "1995 kcal" -> 1995. */
    private fun looseInt(j: JSONObject, key: String): Int {
        val v = j.opt(key) ?: return 0
        (v as? Number)?.let { return it.toInt().coerceAtLeast(0) }
        val m = Regex("-?\\d+").find(v.toString()) ?: return 0
        return (m.value.toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    /**
     * Last-ditch recovery for malformed JSON (small offline models often emit unescaped quotes,
     * missing commas, etc. that a strict parser rejects): pull the numbers / items / assessment
     * out with regexes so the result card shows real data instead of dumping raw JSON.
     */
    private fun salvage(text: String): Review? {
        val s = stripFences(text)
        fun num(key: String): Int =
            Regex("\"?$key\"?\\s*[:=]\\s*\"?(-?\\d{1,6})").find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val items = Regex("\"name\"\\s*:\\s*\"([^\"]{0,80})\"\\s*,\\s*\"calories\"\\s*:\\s*\"?(\\d{1,5})")
            .findAll(s).map { m ->
                val nm = m.groupValues[1].trim().let { if (it.isBlank() || it == "tên món") "Món ăn" else it }
                nm to (m.groupValues[2].toIntOrNull() ?: 0)
            }.toList()
        var cal = num("total_calories")
        var protein = num("protein_g"); var carb = num("carb_g"); var fat = num("fat_g")
        val itemsSum = items.sumOf { it.second }
        if (cal <= 0 && itemsSum > 0) cal = itemsSum
        if (cal <= 0 && items.isEmpty()) return null // nothing worth showing
        if (cal > 0 && protein == 0 && carb == 0 && fat == 0) {
            protein = (cal * 0.20 / 4).roundToInt(); carb = (cal * 0.50 / 4).roundToInt(); fat = (cal * 0.30 / 9).roundToInt()
        }
        val assessment = (
            Regex("\"assessment\"\\s*:\\s*\"(.+?)\"\\s*,\\s*\"(?:tips|warnings)\"", RegexOption.DOT_MATCHES_ALL).find(s)
                ?: Regex("\"assessment\"\\s*:\\s*\"(.+?)\"\\s*\\}", RegexOption.DOT_MATCHES_ALL).find(s)
            )?.groupValues?.get(1)?.trim()?.replace("\\\"", "\"")?.replace("\\n", " ") ?: ""
        DiagLog.i(TAG, "Cứu dữ liệu bằng regex (JSON lỗi): $cal kcal, ${items.size} món")
        return Review(cal, protein, carb, fat, items, assessment, strListLoose(s, "tips"), strListLoose(s, "warnings"), text)
    }

    /** Pull a "key": ["a","b"] string array out of malformed JSON by regex. */
    private fun strListLoose(s: String, key: String): List<String> {
        val block = Regex("\"$key\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]{1,300})\"").findAll(block).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    }

    /** Remove a leading ```json / ``` fence and a trailing ``` so plain JSON is left. */
    private fun stripFences(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', s.removePrefix("```")) // drop the opening ```json line
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
        }
        return s.trim()
    }

    /**
     * Returns the first balanced {...} object as a string. String-aware (braces inside quotes are
     * ignored); if the model cut off mid-JSON it closes the open strings/arrays/objects so the
     * result still parses — with whatever data arrived. Returns null if there's no '{' at all.
     */
    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        val stack = ArrayDeque<Char>()
        var inStr = false; var esc = false
        val sb = StringBuilder()
        for (i in start until s.length) {
            val c = s[i]; sb.append(c)
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeLast(); if (stack.isEmpty()) return sb.toString() }
            }
        }
        if (inStr) sb.append('"') // close a truncated string value
        // drop a dangling trailing "," or ":" so the appended closers yield valid JSON
        while (sb.isNotEmpty() && (sb.last().isWhitespace() || sb.last() == ',' || sb.last() == ':')) sb.deleteCharAt(sb.length - 1)
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return sb.toString()
    }

    /** When JSON can't be read at all: show the model's text without the code fence. */
    fun cleanForDisplay(text: String): String = stripFences(text).ifBlank { text.trim() }

    private fun strList(j: JSONObject, key: String): List<String> {
        val arr = j.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
}

/** A key failed for a quota/auth reason — the caller should fall back to the next key. */
private class KeyRetryException(msg: String) : Exception(msg)

/** Google Gemini Generative Language API (REST). Tries each key in order, skipping to the next on quota/key errors. */
private object GeminiClient {
    fun run(prefs: Prefs, prompt: String): String {
        val keys = prefs.geminiKeyList()
        if (keys.isEmpty()) throw IllegalStateException(tr("Chưa nhập Gemini API key (vào Cài đặt AI).", "No Gemini API key yet (open AI settings)."))
        val model = prefs.geminiModel.ifBlank { "gemini-2.5-flash" }
        var lastErr: String? = null
        for ((i, key) in keys.withIndex()) {
            try {
                DiagLog.i("HA_AI", "Gemini: thử key #${i + 1}/${keys.size} (model $model)")
                val out = request(key, model, prompt)
                DiagLog.i("HA_AI", "Gemini: key #${i + 1} OK")
                return out
            } catch (e: KeyRetryException) {
                lastErr = "key #${i + 1}: ${e.message}"
                DiagLog.w("HA_AI", "Gemini: key #${i + 1} lỗi (${e.message}) → chuyển key kế tiếp")
                // quota / invalid key -> try the next one
            }
        }
        throw java.io.IOException(tr("Tất cả ${keys.size} Gemini key đều lỗi (quota/không hợp lệ). Lỗi cuối — $lastErr",
            "All ${keys.size} Gemini key(s) failed (quota/invalid). Last error — $lastErr"))
    }

    private fun request(key: String, model: String, prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().put(JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("responseMimeType", "application/json")
            })
        }.toString()

        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = (c.errorStream ?: c.inputStream)?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull()
                val detail = msg ?: err.take(300)
                if (code == 429 || code == 403 || code == 401 ||
                    (code == 400 && (msg?.contains("API key not valid", true) == true || msg?.contains("API_KEY_INVALID", true) == true)))
                    throw KeyRetryException("HTTP $code: $detail")
                throw java.io.IOException("Gemini HTTP $code: $detail")
            }
            val resp = c.inputStream.bufferedReader().readText()
            val cand = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?: throw java.io.IOException(tr("Gemini không trả về nội dung (có thể bị chặn an toàn).", "Gemini returned no content (possibly a safety block)."))
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString().ifBlank { throw java.io.IOException(tr("Gemini trả về rỗng.", "Gemini returned empty.")) }
        } finally {
            c.disconnect()
        }
    }
}

/** On-device model via MediaPipe LLM Inference. Loads a user-imported .task model once and reuses it. */
private object OfflineClient {
    private var cached: LlmInference? = null
    private var cachedPath: String = ""
    private val lock = Any()

    fun run(ctx: Context, prefs: Prefs, prompt: String): String = synchronized(lock) {
        val path = prefs.offlineModelPath
        if (path.isBlank() || !File(path).exists())
            throw IllegalStateException(tr("Chưa chọn model offline (vào Cài đặt AI → Import model).", "No offline model selected (AI settings → Import model)."))
        val llm = instance(ctx, path, prefs.offlineMaxTokens)
        val t0 = System.currentTimeMillis()
        DiagLog.i("HA_AI", "Offline: đang sinh phản hồi… (prompt ${prompt.length} ký tự)")
        val out = llm.generateResponse(prompt) ?: throw java.io.IOException(tr("Model không trả về nội dung.", "The model returned no content."))
        DiagLog.i("HA_AI", "Offline: sinh xong sau ${System.currentTimeMillis() - t0}ms (${out.length} ký tự)")
        return out
    }

    private fun instance(ctx: Context, path: String, maxTokens: Int): LlmInference {
        cached?.let {
            if (cachedPath == path) { DiagLog.i("HA_AI", "Offline: dùng lại model đã nạp"); return it }
            it.close(); cached = null
        }
        val sizeMb = File(path).length() / (1024 * 1024)
        val mt = maxTokens.coerceIn(256, 4096)
        DiagLog.i("HA_AI", "Offline: đang nạp model ${File(path).name} (${sizeMb}MB, maxTokens=$mt) — lần đầu có thể lâu")
        val t0 = System.currentTimeMillis()
        val options = LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(mt)
            .build()
        val llm = try {
            LlmInference.createFromOptions(ctx, options)
        } catch (t: Throwable) {
            DiagLog.e("HA_AI", "Offline: NẠP MODEL THẤT BẠI sau ${System.currentTimeMillis() - t0}ms", t)
            throw t
        }
        DiagLog.i("HA_AI", "Offline: nạp model xong sau ${System.currentTimeMillis() - t0}ms")
        cached = llm; cachedPath = path
        return llm
    }

    /** Free the model (e.g. after the user imports a different one). */
    fun reset() = synchronized(lock) { cached?.close(); cached = null; cachedPath = "" }
}

/**
 * Rough offline estimate with no AI model — a small keyword table so the app is useful
 * before any engine is set up. Clearly flagged as a crude estimate in the UI.
 */
object LocalEstimator {
    // kcal per typical serving, matched by keyword (very approximate).
    private val TABLE = linkedMapOf(
        "phở" to 400, "bún bò" to 480, "bún" to 350, "cơm tấm" to 620, "cơm" to 300,
        "bánh mì" to 350, "xôi" to 400, "cháo" to 250, "mì" to 380, "miến" to 300,
        "trứng" to 90, "thịt gà" to 240, "gà" to 240, "thịt bò" to 250, "bò" to 250,
        "thịt heo" to 290, "heo" to 290, "cá" to 200, "tôm" to 100, "đậu" to 150,
        "rau" to 50, "salad" to 120, "canh" to 90, "phô mai" to 110, "sữa" to 150,
        "sữa chua" to 100, "chuối" to 105, "táo" to 95, "cam" to 60, "trà sữa" to 350,
        "cà phê" to 60, "nước ngọt" to 150, "coca" to 140, "bia" to 150, "bánh" to 300,
        "kem" to 250, "khoai" to 160, "pizza" to 285, "hamburger" to 300, "gà rán" to 320,
    )

    fun estimate(mealsText: String): Ai.Review {
        val items = ArrayList<Pair<String, Int>>()
        var total = 0
        mealsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val lower = line.lowercase()
            val hit = TABLE.entries.firstOrNull { lower.contains(it.key) }
            val kcal = hit?.value ?: 250 // unknown food → rough default
            items.add(line to kcal)
            total += kcal
        }
        return Ai.Review(
            calories = total, protein = (total * 0.20 / 4).roundToInt(),
            carb = (total * 0.50 / 4).roundToInt(), fat = (total * 0.30 / 9).roundToInt(),
            items = items,
            assessment = tr("Ước tính thô theo bảng calo có sẵn (chưa dùng AI). Cấu hình Gemini hoặc model offline để có nhận xét chi tiết hơn.",
                "Rough estimate from a built-in table (no AI). Set up Gemini or an offline model for detailed reviews."),
            tips = emptyList(), warnings = emptyList(), raw = "", estimated = true,
        )
    }
}
