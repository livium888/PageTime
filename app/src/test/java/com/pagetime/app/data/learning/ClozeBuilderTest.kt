package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClozeBuilderTest {

    private val words = ClozeBuilder.words("An embedding turns text into a vector, a list of numbers.")

    @Test
    fun `one tapped word becomes the gap`() {
        val cloze = ClozeBuilder.build(words, setOf(1))
        assertEquals("An {{c1::embedding}} turns text into a vector, a list of numbers.", cloze)
        assertEquals("An ……… turns text into a vector, a list of numbers.", ClozeText.blanked(cloze!!))
        assertEquals("embedding", ClozeBuilder.answer(cloze))
    }

    @Test
    fun `punctuation stays outside the gap`() {
        val cloze = ClozeBuilder.build(words, setOf(6))
        assertEquals("An embedding turns text into a {{c1::vector}}, a list of numbers.", cloze)
    }

    @Test
    fun `neighbouring words are hidden together and separate ones separately`() {
        val cloze = ClozeBuilder.build(words, setOf(1, 8, 9, 10))
        assertEquals("An {{c1::embedding}} turns text into a vector, a {{c2::list of numbers}}.", cloze)
        assertEquals("embedding, list of numbers", ClozeBuilder.answer(cloze!!))
    }

    @Test
    fun `nothing chosen or everything chosen is not a card`() {
        assertNull(ClozeBuilder.build(words, emptySet()))
        assertNull(ClozeBuilder.build(words, words.indices.toSet()))
        assertNull(ClozeBuilder.build(words, setOf(99)))
    }

    @Test
    fun `tapping only punctuation hides nothing`() {
        val dashed = ClozeBuilder.words("Memory — unlike storage — fades.")
        assertNull(ClozeBuilder.build(dashed, setOf(1)))
    }

    @Test
    fun `whitespace in the quote does not make empty words`() {
        assertEquals(listOf("two", "words"), ClozeBuilder.words("  two \n\n words  "))
    }
}
