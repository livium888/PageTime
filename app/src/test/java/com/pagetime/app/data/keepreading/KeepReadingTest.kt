package com.pagetime.app.data.keepreading

import com.pagetime.app.data.keepreading.KeepReading.Direction
import com.pagetime.app.data.keepreading.KeepReading.Finished
import com.pagetime.app.data.keepreading.KeepReading.Kind
import com.pagetime.app.data.keepreading.KeepReading.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepReadingTest {

    private val finished = Finished(
        title = "Why We Sleep", author = "Matthew Walker", what = "book",
        highlights = listOf("adenosine builds up the longer you are awake", "REM sleep and emotional memory"),
        concepts = listOf("sleep debt", "circadian rhythm"),
        owned = listOf("Sapiens"),
    )

    @Test
    fun `the prompt carries what was read, the direction, the formats and what to skip`() {
        val p = KeepReading.buildPrompt(finished, Direction.OPPOSITE, setOf(Kind.BOOK, Kind.VIDEO))
        assertTrue(p.contains("“Why We Sleep” by Matthew Walker (book)"))
        assertTrue(p.contains("adenosine builds up"))
        assertTrue(p.contains("sleep debt, circadian rhythm"))
        assertTrue(p.contains("THE OPPOSITE VIEW"))
        assertTrue(p.contains("books, videos"))
        assertTrue(p.contains("Skip what I already have: Sapiens"))
        assertTrue(p.contains("Title | creator | kind"))
    }

    @Test
    fun `a prompt for something with nothing marked still reads well`() {
        val p = KeepReading.buildPrompt(Finished("A pasted text", "", "text"), Direction.DEEPER, emptySet())
        assertFalse(p.contains("My highlights"))
        assertFalse(p.contains("Skip what"))
        assertTrue(p.contains("books, videos, articles, pdfs and papers"))
    }

    @Test
    fun `a reply is read line by line, however it is numbered or bolded`() {
        val reply = """
            Here are some ideas:
            1. **The Promise of Sleep** | William Dement | book | The founder of sleep medicine | https://example.com/x
            2) REM sleep, explained | Kurzgesagt | YouTube video | Clear and short |
            - Sleep myths, a review | J. Smith | paper | The sceptical view | not a link
            Hope this helps!
        """.trimIndent()
        val list = KeepReading.parseReply(reply).getOrThrow()
        assertEquals(listOf("The Promise of Sleep", "REM sleep, explained", "Sleep myths, a review"), list.map { it.title })
        assertEquals(listOf(Kind.BOOK, Kind.VIDEO, Kind.PDF), list.map { it.kind })
        assertEquals("https://example.com/x", list[0].link)
        assertEquals("", list[2].link)
    }

    @Test
    fun `a markdown table header and divider are not suggestions`() {
        val reply = "| Title | Creator | Kind | Why |\n|---|---|---|---|\n| Deep Work | Cal Newport | book | focus |"
        val list = KeepReading.parseReply(reply).getOrThrow()
        assertEquals(listOf("Deep Work"), list.map { it.title })
    }

    @Test
    fun `a reply with no list fails with a way forward`() {
        val failure = KeepReading.parseReply("Sure! Try reading more about sleep.").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("Title | creator | kind"))
    }

    @Test
    fun `the clipboard is picked up only when it looks like a reply`() {
        val prompt = KeepReading.buildPrompt(finished, Direction.DEEPER, setOf(Kind.BOOK))
        val reply = "1. A | B | book | c |\n2. D | E | video | f |"
        assertTrue(KeepReading.looksLikeReply(reply, prompt, null))
        assertFalse(KeepReading.looksLikeReply(prompt, prompt, null))
        assertFalse(KeepReading.looksLikeReply(reply, prompt, reply))
        assertFalse(KeepReading.looksLikeReply("https://example.com", prompt, null))
        assertFalse(KeepReading.looksLikeReply("one | line only", prompt, null))
    }

    @Test
    fun `find it is a search, never the AI's link`() {
        val v = Suggestion("REM sleep", "Kurzgesagt", Kind.VIDEO, "", "https://evil.example")
        assertTrue(KeepReading.searchUrl(v).startsWith("https://www.youtube.com/results?search_query=REM+sleep+Kurzgesagt"))
        assertTrue(KeepReading.searchUrl(v.copy(kind = Kind.BOOK)).startsWith("https://openlibrary.org/search"))
    }

    @Test
    fun `the saved list round-trips and does not repeat a title`() {
        val a = Suggestion("Deep Work", "Cal Newport", Kind.BOOK, "focus", "")
        val b = Suggestion("deep work", "x", Kind.BOOK, "", "")
        val c = Suggestion("Flow", "Csikszentmihalyi", Kind.BOOK, "", "")
        val merged = KeepReading.add(listOf(a), listOf(b, c))
        assertEquals(listOf("Deep Work", "Flow"), merged.map { it.title })
        assertEquals(merged, KeepReading.decode(KeepReading.encode(merged)))
        assertEquals(emptyList<Suggestion>(), KeepReading.decode("not json"))
    }
}
