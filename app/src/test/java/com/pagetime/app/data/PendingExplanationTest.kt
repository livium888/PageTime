package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingExplanationTest {

    private fun pending(chapterTitle: String? = "Chapter 3") = ExplainBackRepository.PendingExplanation(
        prompt = "Mark this explanation of \"scarcity\".\n{braces} and \"quotes\"",
        bookId = "book-1",
        chapterIndex = 2,
        chapterTitle = chapterTitle,
        conceptLabel = "Scarcity",
        keyPoints = listOf("wants exceed resources", "forces choices"),
        userExplanation = "It means there is never enough,\nso you have to choose.",
    )

    @Test
    fun `everything survives being stored and read back`() {
        val original = pending()
        val restored = ExplainBackRepository.PendingExplanation.decode(original.encode())!!
        assertEquals(original.prompt, restored.prompt)
        assertEquals(original.userExplanation, restored.userExplanation)
        assertEquals("Scarcity", restored.concept)
        assertTrue(restored.isFor("book-1", 2))
        assertEquals(original.encode(), restored.encode())
    }

    @Test
    fun `a missing chapter title stays missing`() {
        val restored = ExplainBackRepository.PendingExplanation.decode(pending(chapterTitle = null).encode())!!
        assertEquals(pending(chapterTitle = null).encode(), restored.encode())
    }

    @Test
    fun `it is only for the book and chapter it was written in`() {
        val p = pending()
        assertFalse(p.isFor("book-2", 2))
        assertFalse(p.isFor("book-1", 3))
    }

    @Test
    fun `nothing stored, or something unreadable, is nothing pending`() {
        assertNull(ExplainBackRepository.PendingExplanation.decode(null))
        assertNull(ExplainBackRepository.PendingExplanation.decode(""))
        assertNull(ExplainBackRepository.PendingExplanation.decode("not json"))
        assertNull(ExplainBackRepository.PendingExplanation.decode("{\"prompt\":\"only this\"}"))
    }
}
