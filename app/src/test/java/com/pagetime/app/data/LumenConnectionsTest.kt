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
}
