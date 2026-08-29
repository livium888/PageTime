package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenConnectionsTest {
    private fun card(id: String, front: String, book: String = "book", box: Int = 1) =
        LumenCardEntity(
            id = id,
            bookId = book,
            box = box,
            indexNumber = id,
            front = front,
            back = "",
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            createdAt = 0,
            updatedAt = 0
        )

    @Test
    fun `shared terms rank before unrelated cards`() {
        val fresh = card("new", "Institutions preserve social behavior")
        val related = card("21a", "Social institutions preserve behavior")
        val unrelated = card("22", "Cooking techniques and seasonal vegetables")

        val result = LumenConnections.rank(fresh, listOf(fresh, unrelated, related))

        assertEquals("21a", result.first().card.id)
        assertTrue(result.first().reasons.any { it.contains("shared term") })
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `ranking is bounded and ignores self`() {
        val fresh = card("new", "Systems create stable patterns")
        val candidates = (1..60).map { card("$it", "Systems create stable patterns") }

        val result = LumenConnections.rank(fresh, listOf(fresh) + candidates, limit = 12)

        assertEquals(12, result.size)
        assertTrue(result.none { it.card.id == "new" })
    }

    @Test
    fun `filing candidates prefer shared terms in the target box`() {
        val cards = listOf(
            card("21a", "Rituals repeat the institutional pattern"),
            card("21b", "Rituals and power", box = 2),
            card("44", "Cooking with seasonal vegetables", book = "other")
        )

        val result = LumenConnections.filingCandidates(
            cards = cards,
            front = "Rituals repeat patterns",
            back = "",
            quote = "",
            bookId = "book",
            box = 1
        )

        // Only box-1 cards may be suggested, best match first.
        assertEquals(listOf("21a"), result.map { it.id })
    }

    @Test
    fun `filing candidates stay within the box and are bounded`() {
        val cards = (1..30).map { card("$it", "Rituals repeat the institutional pattern", box = 1) } +
            card("99", "Rituals repeat the institutional pattern", box = 2)

        val result = LumenConnections.filingCandidates(
            cards = cards,
            front = "Rituals repeat patterns",
            back = "",
            quote = "",
            bookId = "book",
            box = 1,
            limit = 5
        )

        assertEquals(5, result.size)
        assertTrue(result.all { it.box == 1 })
    }

    @Test
    fun `filing candidates return empty when nothing matches`() {
        val cards = listOf(card("21", "Cooking with seasonal vegetables"))
        val result = LumenConnections.filingCandidates(
            cards = cards,
            front = "Rituals repeat patterns",
            back = "",
            quote = "",
            bookId = "other"
        )
        assertTrue(result.isEmpty())
    }
}
