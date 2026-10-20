package com.dsoft.bookshelf

import java.io.File
import java.util.zip.ZipFile

/**
 * Dependency-free EPUB -> plain text converter.
 *
 * An EPUB is a ZIP container. [parse] opens it with [ZipFile] (random access is needed to read
 * documents in spine order), locates the OPF via `META-INF/container.xml`, walks the reading
 * order and strips each XHTML/HTML document to readable text with [htmlToText].
 *
 * Everything here uses only the JDK (`java.util.zip`, regex) so [htmlToText] is unit-testable
 * off-device. Malformed input yields `null` so the caller can fail that one import gracefully.
 */
object Epub {
    data class Result(val title: String, val text: String)

    /** Hard cap on extracted text to guard against zip-bombs / absurd books. */
    private const val MAX_TEXT = 8 * 1024 * 1024 // 8 MB

    fun parse(file: File): Result? {
        return try {
            ZipFile(file).use { zip ->
                // Map lower-cased entry name -> entry for case-tolerant lookups.
                val entries = HashMap<String, java.util.zip.ZipEntry>()
                val e = zip.entries()
                while (e.hasMoreElements()) {
                    val ze = e.nextElement()
                    if (!ze.isDirectory) entries[ze.name.lowercase()] = ze
                }

                fun readEntry(path: String): ByteArray? {
                    val norm = normalize(path)
                    val ze = entries[norm.lowercase()] ?: return null
                    return zip.getInputStream(ze).use { it.readBytes() }
                }

                val containerBytes = readEntry("META-INF/container.xml") ?: return null
                val opfPath = findOpfPath(bytesToText(containerBytes)) ?: return null
                val opfBytes = readEntry(opfPath) ?: return null
                val opf = bytesToText(opfBytes)

                val title = findTitle(opf)
                val baseDir = opfPath.substringBeforeLast('/', "")

                val manifest = parseManifest(opf) // id -> Item(href, mediaType)
                val spine = parseSpine(opf)       // ordered idrefs

                val sb = StringBuilder()
                for (idref in spine) {
                    val item = manifest[idref] ?: continue
                    if (!isContentDoc(item)) continue
                    val href = item.href.substringBefore('#')
                    val resolved = if (baseDir.isEmpty()) href else "$baseDir/$href"
                    val docBytes = readEntry(resolved) ?: continue
                    val text = htmlToText(bytesToText(docBytes))
                    if (text.isBlank()) continue
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(text)
                    if (sb.length > MAX_TEXT) { sb.setLength(MAX_TEXT); break }
                }

                val out = sb.toString().trim()
                if (out.isEmpty()) return null
                Result(title, out)
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class Item(val href: String, val mediaType: String)

    /** Resolve `..` / `.` and strip a leading slash so names match zip entries. */
    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (seg in path.trimStart('/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun bytesToText(bytes: ByteArray): String = Files.decode(bytes)

    private fun findOpfPath(containerXml: String): String? {
        val m = Regex("""<rootfile[^>]*\bfull-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(containerXml)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun findTitle(opf: String): String {
        val m = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
        return decodeEntities(stripTags(m?.groupValues?.get(1) ?: "")).trim()
    }

    private fun parseManifest(opf: String): Map<String, Item> {
        val out = LinkedHashMap<String, Item>()
        for (m in Regex("""<item\b[^>]*/?>""", RegexOption.IGNORE_CASE).findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            out[id] = Item(decodeEntities(href), attr(tag, "media-type")?.lowercase() ?: "")
        }
        return out
    }

    private fun parseSpine(opf: String): List<String> {
        val spine = Regex("""<spine\b[^>]*>(.*?)</spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
            ?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(spine)
            .mapNotNull { attr(it.value, "idref") }
            .toList()
    }

    private fun isContentDoc(item: Item): Boolean {
        val mt = item.mediaType
        if (mt == "application/xhtml+xml" || mt == "text/html") return true
        if (mt.isNotEmpty()) return false
        // media-type missing: fall back to the extension.
        val ext = item.href.substringAfterLast('.', "").lowercase()
        return ext == "xhtml" || ext == "html" || ext == "htm"
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    // ---------------- HTML -> text (pure, unit-testable) ----------------

    /**
     * Converts an (X)HTML fragment to readable plain text:
     * drops `<script>`/`<style>` blocks, turns block boundaries (`<br>`, `</p>`, `</div>`,
     * headings, list items, etc.) into newlines, removes remaining tags, decodes HTML
     * entities and collapses runs of blank lines.
     */
    fun htmlToText(html: String): String {
        var s = html
        // Remove script/style (and their content) and comments.
        s = Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("""<head\b[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
        s = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(s, " ")

        // Line breaks.
        s = Regex("""<br\b[^>]*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""<hr\b[^>]*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        // Block-closing and heading/list/table-ish tags -> newline.
        s = Regex(
            """</?(p|div|h[1-6]|li|ul|ol|tr|table|section|article|header|footer|blockquote|pre|figure|figcaption|dd|dt|dl)\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).replace(s, "\n")

        // Strip any remaining tags.
        s = stripTags(s)

        // Decode entities.
        s = decodeEntities(s)

        // Normalise whitespace: trim each line, drop runs of >1 blank line.
        val lines = s.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var blanks = 0
        for (raw in lines) {
            val line = raw.replace(' ', ' ').replace(Regex("""[ \t -​]+"""), " ").trim()
            if (line.isEmpty()) {
                blanks++
                if (blanks <= 1 && out.isNotEmpty()) out.append('\n')
            } else {
                blanks = 0
                out.append(line).append('\n')
            }
        }
        return out.toString().trim()
    }

    private fun stripTags(s: String): String = Regex("""<[^>]*>""", RegexOption.DOT_MATCHES_ALL).replace(s, "")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
    )

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""").replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED[body] ?: NAMED[body.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 0..0x10FFFF) try { String(Character.toChars(cp)) } catch (_: Exception) { null } else null
}
