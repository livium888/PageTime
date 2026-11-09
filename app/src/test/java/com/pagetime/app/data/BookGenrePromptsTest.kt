package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGenrePromptsTest {

    @Test
    fun `numbered lines are read by number`() {
        val found = BookGenrePrompts.parseAll("1: Fiction\n2: Science\n3: Other", count = 3)
        assertEquals(
            mapOf(1 to BookGenre.FICTION, 2 to BookGenre.SCIENCE, 3 to BookGenre.OTHER),
            found,
        )
    }

    @Test
    fun `chatter, markdown and other separators are tolerated`() {
        val reply = """
            Here you go:
            **1.** Science Fiction
            2) memoir & biography
            - 3 - History.
            Let me know if you need more.
        """.trimIndent()
        val found = BookGenrePrompts.parseAll(reply, count = 3)
        assertEquals(BookGenre.SCIENCE_FICTION, found[1])
        assertEquals(BookGenre.MEMOIR_BIOGRAPHY, found[2])
        assertEquals(BookGenre.HISTORY, found[3])
    }

    @Test
    fun `an invented category or a number outside the list is skipped, not guessed`() {
        val found = BookGenrePrompts.parseAll("1: Cyberpunk noir\n2: Poetry\n9: Fiction", count = 2)
        assertEquals(mapOf(2 to BookGenre.POETRY), found)
    }

    @Test
    fun `the first answer for a number wins`() {
        val found = BookGenrePrompts.parseAll("1: Poetry\n1: Fiction", count = 1)
        assertEquals(mapOf(1 to BookGenre.POETRY), found)
    }

    @Test
    fun `prose with no numbered lines yields nothing`() {
        assertTrue(BookGenrePrompts.parseAll("I think they are mostly fiction.", count = 5).isEmpty())
    }

    @Test
    fun `the prompt numbers every book and lists the categories`() {
        val prompt = BookGenrePrompts.classifyAll(listOf("Dune" to "Frank Herbert", "Untitled notes" to ""))
        assertTrue(prompt.contains("1. Dune — Frank Herbert"))
        assertTrue(prompt.contains("2. Untitled notes"))
        assertTrue(prompt.contains("Science Fiction"))
    }

    @Test
    fun `a long library is cut to what one prompt can carry`() {
        val many = (1..100).map { "Book $it" to "Author" }
        val prompt = BookGenrePrompts.classifyAll(many)
        assertTrue(prompt.contains("${BookGenrePrompts.MAX_BOOKS}. Book ${BookGenrePrompts.MAX_BOOKS}"))
        assertTrue(!prompt.contains("${BookGenrePrompts.MAX_BOOKS + 1}. Book"))
    }
}
