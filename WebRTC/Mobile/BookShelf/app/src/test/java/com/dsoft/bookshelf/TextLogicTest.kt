package com.dsoft.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {
    private fun texts(t: String, spans: List<Span>) = spans.map { t.substring(it.start, it.end) }

    @Test fun splitsLatinSentences() {
        val t = "Xin chào. Bạn khỏe không? Tôi khỏe!  Cảm ơn…"
        assertEquals(listOf("Xin chào.", "Bạn khỏe không?", "Tôi khỏe!", "Cảm ơn…"), texts(t, TextLogic.splitSentences(t)))
    }

    @Test fun keepsDecimalsAndAbbreviationLikeDots() {
        val t = "Giá là 3.14 đồng. Phiên bản v1.2 ổn định."
        assertEquals(listOf("Giá là 3.14 đồng.", "Phiên bản v1.2 ổn định."), texts(t, TextLogic.splitSentences(t)))
    }

    @Test fun splitsCjkWithoutSpaces() {
        val t = "你好。今天天气很好！你呢？日本語です。"
        assertEquals(listOf("你好。", "今天天气很好！", "你呢？", "日本語です。"), texts(t, TextLogic.splitSentences(t)))
    }

    @Test fun newlinesEndSentencesAndBlankLinesDropped() {
        val t = "Chương 1\n\nMở đầu câu chuyện\nDòng hai."
        assertEquals(listOf("Chương 1", "Mở đầu câu chuyện", "Dòng hai."), texts(t, TextLogic.splitSentences(t)))
    }

    @Test fun ellipsisAndClosingQuotes() {
        val t = "Anh nói: \"Đi thôi!\" Rồi im lặng... Hết."
        assertEquals(listOf("Anh nói: \"Đi thôi!\"", "Rồi im lặng...", "Hết."), texts(t, TextLogic.splitSentences(t)))
    }

    @Test fun groupsUpToThreeSentences() {
        val t = "A. B. C. D. E."
        val g = TextLogic.utterances(t, maxSentences = 3, maxChars = 400)
        assertEquals(listOf("A. B. C.", "D. E."), texts(t, g))
    }

    @Test fun groupRespectsMaxChars() {
        val s = "x".repeat(150) + "."
        val t = "$s $s $s"
        val g = TextLogic.utterances(t, maxSentences = 3, maxChars = 400)
        assertEquals(2, g.size)
        assertTrue(g.all { it.length <= 400 })
    }

    @Test fun longSentenceIsHardSplit() {
        val t = (1..200).joinToString(" ") { "từ$it" } + "."
        val g = TextLogic.utterances(t, maxChars = 100)
        assertTrue(g.size > 5)
        assertTrue(g.all { it.length <= 100 })
        // nothing lost (ignoring whitespace)
        assertEquals(t.replace(" ", ""), texts(t, g).joinToString("").replace(" ", ""))
    }

    @Test fun batchesAreContiguousAndBounded() {
        val para = "Đây là một câu khá dài để kiểm tra việc chia lô. Câu thứ hai cũng vậy! "
        val t = (1..80).joinToString("\n") { "Đoạn $it. " + para.repeat(it % 4 + 1) }
        for (max in listOf(200, 500, 2000, 4000)) {
            val b = TextLogic.batches(t, max)
            assertEquals(0, b.first().start)
            assertEquals(t.length, b.last().end)
            for (i in 1 until b.size) assertEquals(b[i - 1].end, b[i].start)
            assertTrue("max=$max", b.all { it.length <= max })
            assertEquals(t, texts(t, b).joinToString(""))
        }
    }

    @Test fun batchesPreferParagraphBoundaries() {
        val t = "a".repeat(50) + "\n" + "b".repeat(50) + "\n" + "c".repeat(50)
        val b = texts(t, TextLogic.batches(t, 110))
        assertEquals("a".repeat(50) + "\n" + "b".repeat(50) + "\n", b[0])
        assertEquals("c".repeat(50), b[1])
    }

    @Test fun batchesWithoutBreaksAreHardCut() {
        val t = "z".repeat(1000)
        val b = TextLogic.batches(t, 300)
        assertEquals(listOf(300, 300, 300, 100), b.map { it.length })
    }

    @Test fun paragraphsCoverTextAndMergeBlankLines() {
        val t = "Một\n\n\nHai\nBa\n" + "x ".repeat(2000)
        val p = TextLogic.paragraphs(t, 1500)
        assertEquals(t, texts(t, p).joinToString(""))
        assertEquals("Một\n\n\n", t.substring(p[0].start, p[0].end))
        assertEquals("Hai\n", t.substring(p[1].start, p[1].end))
        assertTrue(p.all { it.length <= 1500 })
    }

    @Test fun indexAtFindsContainingSpan() {
        val spans = listOf(Span(0, 10), Span(10, 20), Span(25, 30))
        assertEquals(0, TextLogic.indexAt(spans, 0))
        assertEquals(1, TextLogic.indexAt(spans, 15))
        assertEquals(1, TextLogic.indexAt(spans, 22))
        assertEquals(2, TextLogic.indexAt(spans, 99))
        assertEquals(-1, TextLogic.indexAt(emptyList(), 3))
    }

    @Test fun sentenceStartAtFindsBeginning() {
        val t = "Câu một. Câu hai dài hơn. Câu ba."
        val inside = t.indexOf("hai")
        assertEquals(t.indexOf("Câu hai"), TextLogic.sentenceStartAt(t, inside))
        assertEquals(0, TextLogic.sentenceStartAt(t, 3))
    }

    @Test fun padPartsKeepsWhitespace() {
        assertEquals(Triple("\n ", "abc", " \n\n"), TextLogic.padParts("\n abc \n\n"))
    }

    @Test fun joinsWrappedLinesIntoSentences() {
        val t = "Đây là một câu bị\nngắt dòng giữa chừng và\ncâu này kết thúc.\nĐoạn mới bắt đầu ở đây."
        assertEquals(
            "Đây là một câu bị ngắt dòng giữa chừng và câu này kết thúc.\nĐoạn mới bắt đầu ở đây.",
            TextLogic.joinWrappedLines(t)
        )
    }

    @Test fun joinKeepsParagraphBreaksAndTerminators() {
        val t = "Chương một\n\nDòng đầu tiên của đoạn\nnối tiếp dòng này. Rồi hết.\n\nĐoạn hai."
        assertEquals(
            "Chương một\n\nDòng đầu tiên của đoạn nối tiếp dòng này. Rồi hết.\n\nĐoạn hai.",
            TextLogic.joinWrappedLines(t)
        )
    }

    @Test fun joinHandlesClosersCjkColonAndCollapsesSpaces() {
        assertEquals("Anh nói: \"Đi thôi!\"\nTiếp theo.", TextLogic.joinWrappedLines("Anh nói: \"Đi thôi!\"\nTiếp theo."))
        assertEquals("你好。\n下一行", TextLogic.joinWrappedLines("你好。\n下一行"))
        assertEquals("từ một từ hai", TextLogic.joinWrappedLines("từ một  \n  từ hai"))
        assertEquals("Ghi chú:\nnội dung", TextLogic.joinWrappedLines("Ghi chú:\nnội dung"))
        assertEquals("a b", TextLogic.joinWrappedLines("a\r\nb"))
    }

    @Test fun fileNameHelpers() {
        assertEquals("Sách hay", TextLogic.titleFromFileName("Sách_hay.pdf"))
        assertEquals("a_b_c", TextLogic.safeFileName("a/b:c"))
    }

    @Test fun quoteSplitStraight() {
        val r = TextLogic.quoteSplit("He said \"hello\" now.")
        assertEquals(3, r.size)
        assertEquals("He said " to false, r[0])
        assertEquals("\"hello\"" to true, r[1])
        assertEquals(" now." to false, r[2])
        // reassembling the runs must give back the original text
        assertEquals("He said \"hello\" now.", r.joinToString("") { it.first })
    }

    @Test fun quoteSplitCurlyAndNone() {
        val r = TextLogic.quoteSplit("A “xin chào” B")
        assertEquals(true, r.any { it.second && it.first.contains("xin") })
        assertEquals("A “xin chào” B", r.joinToString("") { it.first })
        assertEquals(false, TextLogic.hasQuote("khong co ngoac"))
        assertEquals(listOf("no quotes" to false), TextLogic.quoteSplit("no quotes"))
    }
}
