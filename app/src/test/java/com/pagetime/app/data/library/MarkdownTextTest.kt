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

    private fun levels(c: MarkdownText.Converted): List<Pair<String, Int>> =
        c.spans.filter { it.kind == Kind.HEADING }.map { c.text.substring(it.start, it.end) to it.level }

    @Test
    fun `every heading level from one to six is kept`() {
        val md = (1..6).joinToString("\n\n") { "${"#".repeat(it)} Level $it" }
        assertEquals((1..6).map { "Level $it" to it }, levels(MarkdownText.convert(md)))
    }

    @Test
    fun `seven hashes is not a heading`() {
        val c = MarkdownText.convert("####### nope")
        assertTrue(c.spans.none { it.kind == Kind.HEADING })
    }

    @Test
    fun `heading levels survive the stored file and old files read as level one`() {
        val c = MarkdownText.convert("# A\n\n### B\n\nbody **bold**")
        val stored = MarkdownText.encode(c.text.length, c.spans)
        assertEquals(c.spans, MarkdownText.decode(stored, c.text.length))
        val old = """{"length":5,"spans":[[0,5,"HEADING"],[0,2,"BOLD"]]}"""
        val read = MarkdownText.decode(old, 5)
        assertEquals(1, read.first { it.kind == Kind.HEADING }.level)
        assertEquals(0, read.first { it.kind == Kind.BOLD }.level)
    }

    @Test
    fun `page spans keep their level`() {
        val c = MarkdownText.convert("Intro.\n\n## Part\n\nText.")
        val heading = c.spans.first { it.kind == Kind.HEADING }
        val page = MarkdownText.pageSpans(c.spans, heading.start - 2, c.text.length)
        assertEquals(2, page.first { it.kind == Kind.HEADING }.level)
    }

    @Test
    fun `a table becomes one small block per row, first cell as the title`() {
        val md = "| Era | Years | Notable |\n| --- | :---: | --- |\n| Bronze | 3300-1200 | Writing |\n| Iron | 1200-600 | |\n\nAfter."
        val c = MarkdownText.convert(md)
        assertEquals("Bronze\nYears: 3300-1200\nNotable: Writing\n\nIron\nYears: 1200-600\n\nAfter.", c.text)
        assertEquals(listOf("Bronze", "Iron"), styled(c, Kind.BOLD))
        assertEquals(listOf("Years", "Notable", "Years"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `a table without outer pipes and with markup in its cells`() {
        val md = "Option | Cost\n--- | ---\n**Rent** | \\| 900 \\|\nBuy | *a lot*"
        val c = MarkdownText.convert(md)
        assertEquals("Rent\nCost: | 900 |\n\nBuy\nCost: a lot", c.text)
        assertEquals(listOf("Rent", "Buy"), styled(c, Kind.BOLD))
    }

    @Test
    fun `a line with a pipe but no divider under it is ordinary text`() {
        val c = MarkdownText.convert("either a | b works\n\nnext")
        assertEquals("either a | b works\n\nnext", c.text)
    }

    @Test
    fun `a table sits between paragraphs and does not swallow them`() {
        val c = MarkdownText.convert("Before.\n| A | B |\n|---|---|\n| x | y |\nAfter the table.")
        assertEquals("Before.\n\nx\nB: y\n\nAfter the table.", c.text)
    }

    @Test
    fun `a quotation is set in italics`() {
        val c = MarkdownText.convert("He said:\n\n> to be or not to be\n\nand left.")
        assertEquals("He said:\n\nto be or not to be\n\nand left.", c.text)
        assertEquals(listOf("to be or not to be"), styled(c, Kind.ITALIC))
    }

    @Test
    fun `sections are the two shallowest levels in use`() {
        val c = MarkdownText.convert("# Talk\n\n## One\n\n#### deep\n\n## Two\n\n### sub")
        val names = MarkdownText.sectionHeadings(c.spans).map { c.text.substring(it.start, it.end) }
        assertEquals(listOf("Talk", "One", "Two"), names)
        val onlyDeep = MarkdownText.convert("## A\n\n### B\n\n#### C")
        assertEquals(2, MarkdownText.sectionHeadings(onlyDeep.spans).size)
        assertTrue(MarkdownText.sectionHeadings(emptyList()).isEmpty())
    }

    @Test
    fun `a realistic reply from an AI app converts cleanly and every span is in range`() {
        val md = """
            # The Roman Economy

            **Host:** Welcome back. Today: *money*.

            ## Coins

            ### The denarius

            Silver, at first. The **debasement** came later:

            - Nero cut the silver
            - Severus cut it more

            > "The army's pay was the empire's budget."

            ## Compared

            | Period | Silver | Effect |
            |---|---|---|
            | Augustus | 95% | Stable |
            | Severus | 50% | Inflation |
        """.trimIndent()
        val c = MarkdownText.convert(md)
        assertEquals(listOf("The Roman Economy" to 1, "Coins" to 2, "The denarius" to 3, "Compared" to 2), levels(c))
        assertTrue(c.spans.all { it.start >= 0 && it.end <= c.text.length && it.end > it.start })
        assertTrue(!c.text.contains("|") && !c.text.contains("#") && !c.text.contains("**"))
    }
}
