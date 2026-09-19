package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookGenreTest {

    @Test
    fun `matches the exact label`() {
        assertEquals(BookGenre.SCIENCE_FICTION, BookGenre.parse("Science Fiction"))
    }

    @Test
    fun `is case and whitespace insensitive`() {
        assertEquals(BookGenre.POETRY, BookGenre.parse("  poetry  "))
        assertEquals(BookGenre.MEMOIR_BIOGRAPHY, BookGenre.parse("memoir & biography"))
    }

    @Test
    fun `tolerates a trailing period, a model's favorite way to not follow instructions`() {
        assertEquals(BookGenre.FICTION, BookGenre.parse("Fiction."))
    }

    @Test
    fun `also matches the enum's own name, not just its label`() {
        assertEquals(BookGenre.SELF_HELP, BookGenre.parse("self help"))
    }

    @Test
    fun `an answer outside the list is left unclassified, not guessed at`() {
        assertNull(BookGenre.parse("Cyberpunk Noir Romance"))
        assertNull(BookGenre.parse(""))
        assertNull(BookGenre.parse("I'm not sure, possibly literary fiction?"))
    }
}
