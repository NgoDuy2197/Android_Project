package com.dsoft.bookshelf

/**
 * Pure text helpers (no Android dependencies) - unit tested on the JVM.
 * Spans are half-open [start, end) offsets into the original text.
 */
data class Span(val start: Int, val end: Int) {
    val length get() = end - start
    operator fun contains(offset: Int) = offset in start until end
}

object TextLogic {
    /** Marks that set apart dialogue / asides: straight & curly quotes, guillemets, and round brackets. */
    const val QUOTE_CHARS = "\"“”«»‘’‹›()（）"
    private const val QUOTE_OPEN = "“«‘‹(（"
    private const val QUOTE_CLOSE = "”»’›)）"

    fun hasQuote(s: CharSequence) = s.any { it in QUOTE_CHARS }

    /**
     * Splits [text] into consecutive runs tagged whether they are inside a quotation, so dialogue can be
     * read with a different pitch. Curly/guillemet quotes use open/close; the straight " toggles.
     */
    fun quoteSplit(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var inQ = false
        fun flush() { if (sb.isNotEmpty()) { out.add(sb.toString() to inQ); sb.setLength(0) } }
        for (c in text) {
            when {
                c in QUOTE_OPEN -> { flush(); inQ = true; sb.append(c) }
                c in QUOTE_CLOSE -> { sb.append(c); flush(); inQ = false }
                c == '"' -> { if (inQ) { sb.append(c); flush(); inQ = false } else { flush(); inQ = true; sb.append(c) } }
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }

    private const val TERMINATORS = ".!?。！？…"
    private const val CJK_TERMINATORS = "。！？…"
    private const val CLOSERS = "\"'”’»)]}）」』】》"

    private fun isTerminator(c: Char) = TERMINATORS.indexOf(c) >= 0

    /**
     * Splits [text] into sentences. A sentence ends after a run of terminators
     * (`. ! ? 。 ！ ？ …`, plus trailing closing quotes/brackets) or at a newline.
     * A '.' only ends a sentence when followed by whitespace / end / a closer (so "3.14" and "v1.2" stay intact).
     * Returned spans are trimmed of surrounding whitespace; blank sentences are dropped.
     */
    fun splitSentences(text: CharSequence): List<Span> {
        val out = ArrayList<Span>()
        val n = text.length
        var start = 0
        var i = 0
        fun emit(s: Int, e: Int) {
            var a = s
            var b = e
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b > a) out += Span(a, b)
        }
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                emit(start, i)
                i++
                start = i
                continue
            }
            if (isTerminator(c)) {
                var j = i
                while (j < n && isTerminator(text[j])) j++
                while (j < n && CLOSERS.indexOf(text[j]) >= 0) j++
                val cjk = CJK_TERMINATORS.indexOf(c) >= 0 || (j > i && CJK_TERMINATORS.indexOf(text[j - 1]) >= 0)
                val boundary = j >= n || text[j].isWhitespace() || cjk || c == '!' || c == '?'
                if (boundary) {
                    emit(start, j)
                    start = j
                }
                i = j
                continue
            }
            i++
        }
        emit(start, n)
        return out
    }

    /**
     * Groups consecutive sentences into utterances of at most [maxSentences] sentences and about [maxChars] chars.
     * A single sentence longer than [maxChars] is cut at a comma/space near the limit.
     */
    fun groupSentences(text: CharSequence, sentences: List<Span>, maxSentences: Int = 3, maxChars: Int = 400): List<Span> {
        val out = ArrayList<Span>()
        var curStart = -1
        var curEnd = -1
        var count = 0
        fun flush() {
            if (curStart >= 0) out += Span(curStart, curEnd)
            curStart = -1; curEnd = -1; count = 0
        }
        for (s in sentences) {
            if (s.length > maxChars) {
                flush()
                out += hardSplit(text, s, maxChars)
                continue
            }
            if (curStart >= 0 && (count >= maxSentences || s.end - curStart > maxChars)) flush()
            if (curStart < 0) curStart = s.start
            curEnd = s.end
            count++
        }
        flush()
        return out
    }

    /** Convenience: sentence split + grouping. */
    fun utterances(text: CharSequence, maxSentences: Int = 3, maxChars: Int = 400) =
        groupSentences(text, splitSentences(text), maxSentences, maxChars)

    /** Cuts [s] into pieces <= [maxChars], preferring to cut after a comma-like char, then after a space. */
    fun hardSplit(text: CharSequence, s: Span, maxChars: Int): List<Span> {
        val out = ArrayList<Span>()
        var a = s.start
        while (a < s.end) {
            if (s.end - a <= maxChars) {
                out += Span(a, s.end); break
            }
            val limit = a + maxChars
            var cut = -1
            var k = limit
            while (k > a + maxChars / 2) {
                val ch = text[k - 1]
                if (ch == ',' || ch == ';' || ch == ':' || ch == '，' || ch == '、' || ch == '；') { cut = k; break }
                k--
            }
            if (cut < 0) {
                k = limit
                while (k > a + maxChars / 2) {
                    if (text[k - 1].isWhitespace()) { cut = k; break }
                    k--
                }
            }
            if (cut < 0) cut = limit
            var e = cut
            while (e > a && text[e - 1].isWhitespace()) e--
            if (e > a) out += Span(a, e)
            a = cut
            while (a < s.end && text[a].isWhitespace()) a++
        }
        return out
    }

    /**
     * Contiguous batches covering the whole text [0, len) for translation. Breaks at paragraph ends ("\n"),
     * else at sentence ends, else at whitespace; each batch is <= [maxChars] unless no break point exists.
     * Concatenating all batches gives back the original text exactly.
     */
    fun batches(text: CharSequence, maxChars: Int): List<Span> {
        require(maxChars > 0)
        val n = text.length
        val out = ArrayList<Span>()
        var start = 0
        while (start < n) {
            if (n - start <= maxChars) {
                out += Span(start, n); break
            }
            val limit = start + maxChars
            var cut = lastBreak(text, start, limit) { i -> text[i - 1] == '\n' }
            if (cut < 0) cut = lastBreak(text, start, limit) { i ->
                val c = text[i - 1]
                (isTerminator(c) || CLOSERS.indexOf(c) >= 0) && (i >= n || text[i].isWhitespace() || CJK_TERMINATORS.indexOf(c) >= 0)
            }
            if (cut < 0) cut = lastBreak(text, start, limit) { i -> text[i - 1].isWhitespace() }
            if (cut < 0) cut = limit
            // keep trailing whitespace/newlines with the batch they follow
            while (cut < n && cut - start < maxChars && (text[cut] == '\n' || text[cut] == ' ')) cut++
            out += Span(start, cut)
            start = cut
        }
        return out
    }

    private inline fun lastBreak(text: CharSequence, start: Int, limit: Int, isBreak: (Int) -> Boolean): Int {
        var i = minOf(limit, text.length)
        while (i > start + 1) {
            if (isBreak(i)) return i
            i--
        }
        return -1
    }

    /**
     * Contiguous display paragraphs for the text reader: split after each '\n' (blank lines are merged into the
     * preceding paragraph), and paragraphs longer than [maxLen] are split with [batches].
     */
    fun paragraphs(text: CharSequence, maxLen: Int = 1500): List<Span> {
        val n = text.length
        val out = ArrayList<Span>()
        var start = 0
        var i = 0
        while (i < n) {
            if (text[i] == '\n') {
                var j = i + 1
                while (j < n && (text[j] == '\n' || text[j] == '\r' || text[j] == ' ' || text[j] == '\t') &&
                    restIsBlankLine(text, j)) j++
                addPara(text, start, j, maxLen, out)
                start = j
                i = j
                continue
            }
            i++
        }
        if (start < n) addPara(text, start, n, maxLen, out)
        return out
    }

    private fun restIsBlankLine(text: CharSequence, from: Int): Boolean {
        var k = from
        while (k < text.length && text[k] != '\n') {
            if (!text[k].isWhitespace()) return false
            k++
        }
        return true
    }

    private fun addPara(text: CharSequence, s: Int, e: Int, maxLen: Int, out: MutableList<Span>) {
        if (e - s <= maxLen) out += Span(s, e)
        else for (b in batches(text.subSequence(s, e), maxLen)) out += Span(s + b.start, s + b.end)
    }

    /** Index of the span containing [offset] (or the last span starting before it); -1 if empty. */
    fun indexAt(spans: List<Span>, offset: Int): Int {
        if (spans.isEmpty()) return -1
        var lo = 0
        var hi = spans.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (spans[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Start offset of the sentence containing [offset] (used by "read from here"). */
    fun sentenceStartAt(text: CharSequence, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        var i = o
        while (i > 0) {
            val c = text[i - 1]
            if (c == '\n') break
            if (isTerminator(c) && (i >= text.length || text[i].isWhitespace() || CJK_TERMINATORS.indexOf(c) >= 0)) break
            i--
        }
        while (i < text.length && text[i].isWhitespace() && i < o) i++
        return i
    }

    /** Splits a chunk into (leading whitespace, core, trailing whitespace) so translations keep the layout. */
    fun padParts(s: String): Triple<String, String, String> {
        var a = 0
        var b = s.length
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        return Triple(s.substring(0, a), s.substring(a, b), s.substring(b))
    }

    private const val JOIN_TERMINATORS = ".!?:;…。！？"

    /** True if [line] ends with a sentence terminator (ignoring trailing whitespace and closing quotes/brackets). */
    fun endsSentence(line: CharSequence): Boolean {
        var i = line.length
        while (i > 0 && line[i - 1].isWhitespace()) i--
        while (i > 0 && CLOSERS.indexOf(line[i - 1]) >= 0) i--
        return i > 0 && JOIN_TERMINATORS.indexOf(line[i - 1]) >= 0
    }

    /**
     * Repairs PDF-extracted text whose every visual line-wrap became a newline. A newline is kept (a real
     * paragraph break) only when the line ends with sentence-ending punctuation, is blank, or is followed by a
     * blank line; otherwise the wrapped line is joined to the next with a single space. Double spaces are collapsed.
     */
    fun joinWrappedLines(text: CharSequence): String {
        val norm = text.toString().replace("\r\n", "\n").replace('\r', '\n')
        val lines = norm.split('\n')
        val sb = StringBuilder(norm.length)
        for (i in lines.indices) {
            sb.append(lines[i].trimEnd())
            if (i == lines.lastIndex) break
            val keep = lines[i].isBlank() || lines[i + 1].isBlank() || endsSentence(lines[i])
            sb.append(if (keep) '\n' else ' ')
        }
        // collapse runs of spaces/tabs (never newlines), and trailing spaces before newlines
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" +\n"), "\n").trim()
    }

    /** Safe file name from a title. */
    fun safeFileName(title: String, max: Int = 60): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return (if (cleaned.isEmpty()) "book" else cleaned).take(max)
    }

    /** Strips a known extension from a file name to make a title. */
    fun titleFromFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0 && name.length - dot <= 5) name.substring(0, dot) else name
        return base.replace('_', ' ').trim().ifEmpty { name }
    }
}
