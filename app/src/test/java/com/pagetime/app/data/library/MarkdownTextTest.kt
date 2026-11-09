package com.pagetime.app.data.library

import com.pagetime.app.data.library.MarkdownText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    private fun styled(c: MarkdownText.Converted, kind: Kind): List<String> =
        c.spans.filter { it.kind == kind }.map { c.text.substring(it.start, it.end) }

    @Test
    fun `plain text is left exactly as it was`() {
        val plain = "A first paragraph.\n\nA second one, with 2 * 3 = 6 and a snake_case_name."
        val c = MarkdownText.convert(plain)
        assertEquals(plain, c.text)
        assertTrue(c.spans.isEmpty())
    }

    @Test
    fun `a heading loses its marks and is styled as a heading`() {
        val c = MarkdownText.convert("# The Title\nBody text.")
        assertEquals("The Title\n\nBody text.", c.text)
        assertEquals(listOf("The Title"), styled(c, Kind.HEADING))
    }

    @Test
    fun `a heading in the middle is set apart by blank lines`() {
        val c = MarkdownText.convert("Before.\n## Section ##\nAfter.")
        assertEquals("Before.\n\nSection\n\nAfter.", c.text)
        assertEquals(listOf("Section"), styled(c, Kind.HEADING))
    }

    @Test
    fun `bold and italic lose their marks and keep their ranges`() {
        val c = MarkdownText.convert("This is **bold**, this is *italic* and __this__ too.")
        assertEquals("This is bold, this is italic and this too.", c.text)
        assertEquals(listOf("bold", "this"), styled(c, Kind.BOLD))
        assertEquals(listOf("italic"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `three marks are both bold and italic`() {
        val c = MarkdownText.convert("***both***")
        assertEquals("both", c.text)
        assertEquals(listOf("both"), styled(c, Kind.BOLD))
        assertEquals(listOf("both"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `emphasis inside emphasis keeps both`() {
        val c = MarkdownText.convert("**Host:** said *very* clearly")
        assertEquals("Host: said very clearly", c.text)
        assertEquals(listOf("Host:"), styled(c, Kind.BOLD))
        assertEquals(listOf("very"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `an unpaired or spaced asterisk is not emphasis`() {
        assertEquals("5 * 3 and a footnote*", MarkdownText.convert("5 * 3 and a footnote*").text)
        assertEquals("* not a pair", MarkdownText.convert("\\* not a pair").text)
    }

    @Test
    fun `underscores inside words are left alone`() {
        val c = MarkdownText.convert("call my_function_name here")
        assertEquals("call my_function_name here", c.text)
        assertTrue(c.spans.isEmpty())
    }

    @Test
    fun `links keep their text and drop the address`() {
        val c = MarkdownText.convert("See [the **docs**](https://example.com/a) and ![a chart](x.png).")
        assertEquals("See the docs and a chart.", c.text)
        assertEquals(listOf("docs"), styled(c, Kind.BOLD))
    }

    @Test
    fun `square brackets that are not a link are kept`() {
        assertEquals("As noted [1] earlier.", MarkdownText.convert("As noted [1] earlier.").text)
    }

    @Test
    fun `bullets become dots, quotes lose their mark, rules disappear`() {
        val c = MarkdownText.convert("- one\n* two\n  + nested\n\n> quoted\n\n---\n\nend")
        assertEquals("• one\n• two\n  • nested\n\nquoted\n\nend", c.text)
    }

    @Test
    fun `numbered lists are kept as written`() {
        assertEquals("1. first\n2. second", MarkdownText.convert("1. first\n2. second").text)
    }

    @Test
    fun `inline code loses its ticks and is not read for emphasis`() {
        assertEquals("use a*b*c here", MarkdownText.convert("use `a*b*c` here").text)
    }

    @Test
    fun `a fenced block is kept verbatim without its fences`() {
        val c = MarkdownText.convert("Before\n```kotlin\nval x = a * b * c\n# not a heading\n```\nAfter")
        assertEquals("Before\nval x = a * b * c\n# not a heading\nAfter", c.text)
        assertTrue(c.spans.isEmpty())
    }

    @Test
    fun `windows line endings and runs of blank lines are normalised`() {
        assertEquals("a\n\nb", MarkdownText.convert("a\r\n\r\n\r\n\r\nb\r\n").text)
    }

    @Test
    fun `every span points at the text it styles`() {
        val c = MarkdownText.convert("# Title\n\nIntro with **bold** text.\n\n## Next\n\n- an *item*")
        for (span in c.spans) {
            assertTrue(span.start >= 0 && span.end <= c.text.length && span.end > span.start)
        }
        assertEquals(listOf("Title", "Next"), styled(c, Kind.HEADING))
        assertEquals(listOf("bold"), styled(c, Kind.BOLD))
        assertEquals(listOf("item"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `the title is the first heading, else the first line, cut at a word`() {
        assertEquals("Real Title", MarkdownText.suggestTitle(MarkdownText.convert("intro\n\n# Real Title\n\nbody")))
        assertEquals("Just a line", MarkdownText.suggestTitle(MarkdownText.convert("\n\nJust a line\nmore")))
        assertEquals("Pasted text", MarkdownText.suggestTitle(MarkdownText.convert("   ")))
        val long = MarkdownText.suggestTitle(MarkdownText.convert("word ".repeat(40)))
        assertTrue(long.length <= 61 && long.endsWith("…"))
    }

    @Test
    fun `spans survive a round trip and are dropped when the text changed`() {
        val c = MarkdownText.convert("# Title\n\nSome **bold** text.")
        val stored = MarkdownText.encode(c.text.length, c.spans)
        assertEquals(c.spans, MarkdownText.decode(stored, c.text.length))
        assertTrue(MarkdownText.decode(stored, c.text.length + 5).isEmpty())
        assertTrue(MarkdownText.decode("not json", c.text.length).isEmpty())
        assertTrue(MarkdownText.decode(null, c.text.length).isEmpty())
    }

    @Test
    fun `page spans are clipped to the page and counted from its start`() {
        val spans = listOf(
            MarkdownText.StyleSpan(5, 15, Kind.BOLD),
            MarkdownText.StyleSpan(40, 50, Kind.ITALIC),
        )
        assertEquals(
            listOf(MarkdownText.StyleSpan(0, 5, Kind.BOLD)),
            MarkdownText.pageSpans(spans, pageStart = 10, pageEnd = 30),
        )
    }
}
