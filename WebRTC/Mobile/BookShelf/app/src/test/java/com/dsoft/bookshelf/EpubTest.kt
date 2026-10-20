package com.dsoft.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTest {

    @Test fun stripsTagsAndKeepsText() {
        val html = "<p>Hello <b>world</b></p>"
        assertEquals("Hello world", Epub.htmlToText(html))
    }

    @Test fun paragraphsBecomeSeparateLines() {
        val html = "<p>First paragraph.</p><p>Second paragraph.</p>"
        assertEquals("First paragraph.\n\nSecond paragraph.", Epub.htmlToText(html))
    }

    @Test fun brBecomesNewline() {
        val html = "Line one<br/>Line two<br>Line three"
        assertEquals("Line one\nLine two\nLine three", Epub.htmlToText(html))
    }

    @Test fun headingsAndDivsBreak() {
        val html = "<h1>Title</h1><div>Body text</div>"
        assertEquals("Title\n\nBody text", Epub.htmlToText(html))
    }

    @Test fun scriptAndStyleRemoved() {
        val html = "<style>.a{color:red}</style><p>Keep</p><script>var x=1;</script>"
        assertEquals("Keep", Epub.htmlToText(html))
    }

    @Test fun decodesNamedEntities() {
        val html = "<p>Tom &amp; Jerry &lt;3 &quot;quoted&quot; it&#39;s</p>"
        assertEquals("Tom & Jerry <3 \"quoted\" it's", Epub.htmlToText(html))
    }

    @Test fun decodesNumericEntities() {
        val html = "<p>A&#66;C and hex &#x44;</p>" // &#66; = B, &#x44; = D
        assertEquals("ABC and hex D", Epub.htmlToText(html))
    }

    @Test fun nbspBecomesSpaceAndCollapses() {
        val html = "<p>a&nbsp;&nbsp;b</p>"
        assertEquals("a b", Epub.htmlToText(html))
    }

    @Test fun collapsesBlankLines() {
        val html = "<p>One</p><p></p><p></p><p>Two</p>"
        val out = Epub.htmlToText(html)
        assertEquals("One\n\nTwo", out)
        assertFalse(out.contains("\n\n\n"))
    }

    @Test fun fullDocumentDropsHead() {
        val html = """
            <html><head><title>Ignore me</title></head>
            <body><h2>Chapter</h2><p>The quick brown fox.</p></body></html>
        """.trimIndent()
        val out = Epub.htmlToText(html)
        assertFalse(out.contains("Ignore me"))
        assertTrue(out.contains("Chapter"))
        assertTrue(out.contains("The quick brown fox."))
    }

    @Test fun malformedUnclosedTagDoesNotCrash() {
        val html = "<p>partial <b>bold text"
        assertEquals("partial bold text", Epub.htmlToText(html))
    }
}
