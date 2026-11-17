package com.pagetime.app.ui.screens.flashcards

import com.pagetime.app.data.local.LearningCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardSearchTest {

    private fun card(id: String, prompt: String, answer: String = "", book: String = "b1", chapter: String? = null) =
        LearningCardEntity(
            id = id, bookId = book, chapterIndex = 0, chapterTitle = chapter, prompt = prompt, answer = answer,
            explanation = null, sourceLocator = null, sourceFraction = null, fsrsCardJson = "{}",
            createdAt = 0, updatedAt = 0,
        )

    @Test
    fun `every typed word must be somewhere on the card, in any order`() {
        val c = card("1", "What does acid do to pectin?", "It keeps cell walls firm", chapter = "Acid and colour")
        assertTrue(FlashcardListing.matchesQuery(c, "pectin acid"))
        assertTrue(FlashcardListing.matchesQuery(c, "FIRM"))
        assertTrue(FlashcardListing.matchesQuery(c, "colour"))
        assertFalse(FlashcardListing.matchesQuery(c, "pectin sugar"))
        assertTrue(FlashcardListing.matchesQuery(c, "   "))
    }

    @Test
    fun `search and book narrow the groups together`() {
        val cards = listOf(
            card("1", "Acid and pectin", book = "b1"),
            card("2", "Acid in wine", book = "b2"),
            card("3", "Knife grips", book = "b1"),
        )
        val titles = mapOf("b1" to "Vegetables", "b2" to "Wine")
        assertEquals(2, FlashcardListing.group(cards, titles, query = "acid").sumOf { it.cards.size })
        assertEquals(listOf("1"), FlashcardListing.group(cards, titles, query = "acid", bookId = "b1").flatMap { g -> g.cards.map { it.id } })
        assertEquals(2, FlashcardListing.group(cards, titles, bookId = "b1").single().cards.size)
    }
}
