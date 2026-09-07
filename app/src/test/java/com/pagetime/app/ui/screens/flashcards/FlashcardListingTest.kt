package com.pagetime.app.ui.screens.flashcards

import com.pagetime.app.data.local.LearningCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardListingTest {

    private val now = 1_000_000L

    private fun card(
        id: String,
        bookId: String = "b1",
        status: String = LearningCardEntity.STATUS_KEPT,
        dueAt: Long? = null,
    ) = LearningCardEntity(
        id = id,
        bookId = bookId,
        chapterIndex = 0,
        chapterTitle = null,
        prompt = "Why?",
        answer = "Because",
        explanation = null,
        sourceLocator = null,
        sourceFraction = 0.5f,
        fsrsCardJson = "{}",
        createdAt = 0,
        updatedAt = 0,
        status = status,
        dueAt = dueAt,
    )

    @Test
    fun `a card waiting to be judged is new, not due`() {
        // A pending card has no schedule at all. Listing it under "Due" would
        // promise a review session that will never offer it.
        val pending = card("a", status = LearningCardEntity.STATUS_PENDING)
        assertTrue(FlashcardListing.matches(pending, FlashcardFilter.NEW, now))
        assertFalse(FlashcardListing.matches(pending, FlashcardFilter.DUE, now))
        assertFalse(FlashcardListing.matches(pending, FlashcardFilter.KEPT, now))
        assertTrue(FlashcardListing.matches(pending, FlashcardFilter.ALL, now))
    }

    @Test
    fun `due means kept and the time has come`() {
        assertTrue(FlashcardListing.matches(card("a", dueAt = now - 1), FlashcardFilter.DUE, now))
        assertTrue(FlashcardListing.matches(card("b", dueAt = now), FlashcardFilter.DUE, now))
        assertFalse(FlashcardListing.matches(card("c", dueAt = now + 1), FlashcardFilter.DUE, now))
        assertFalse(FlashcardListing.matches(card("d", dueAt = null), FlashcardFilter.DUE, now))
    }

    @Test
    fun `cards are grouped under their book, by title`() {
        val groups = FlashcardListing.group(
            listOf(card("a", bookId = "b1"), card("b", bookId = "b2"), card("c", bookId = "b1")),
            mapOf("b1" to "Zebras", "b2" to "Antelopes"),
            now = now,
        )
        assertEquals(listOf("Antelopes", "Zebras"), groups.map { it.bookTitle })
        assertEquals(2, groups.first { it.bookTitle == "Zebras" }.cards.size)
    }

    /**
     * A deleted book must not silently swallow its cards, or the count on the
     * tab disagrees with the list underneath it.
     */
    @Test
    fun `a card whose book is gone still appears`() {
        val groups = FlashcardListing.group(listOf(card("a", bookId = "ghost")), emptyMap(), now = now)
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().cards.size)
        assertTrue(groups.single().bookTitle.isNotBlank())
    }

    @Test
    fun `filtering applies before grouping so empty books do not appear`() {
        val groups = FlashcardListing.group(
            listOf(
                card("a", bookId = "b1", status = LearningCardEntity.STATUS_PENDING),
                card("b", bookId = "b2", dueAt = now - 1),
            ),
            mapOf("b1" to "One", "b2" to "Two"),
            filter = FlashcardFilter.DUE,
            now = now,
        )
        assertEquals(listOf("Two"), groups.map { it.bookTitle })
    }

    @Test
    fun `counts match what the list will show`() {
        val cards = listOf(
            card("a", status = LearningCardEntity.STATUS_PENDING),
            card("b", dueAt = now - 1),
            card("c", dueAt = now + 5_000),
        )
        assertEquals(3, FlashcardListing.countFor(cards, FlashcardFilter.ALL, now))
        assertEquals(1, FlashcardListing.countFor(cards, FlashcardFilter.NEW, now))
        assertEquals(1, FlashcardListing.countFor(cards, FlashcardFilter.DUE, now))
        assertEquals(2, FlashcardListing.countFor(cards, FlashcardFilter.KEPT, now))
    }
}
