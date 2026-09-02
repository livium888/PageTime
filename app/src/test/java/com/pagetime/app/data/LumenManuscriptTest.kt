package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenManuscriptTest {

    private fun card(id: String, front: String, back: String = "", keywords: String = "") =
        LumenCardEntity(
            id = id,
            bookId = "book",
            box = 1,
            indexNumber = id,
            front = front,
            back = back,
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            keywords = keywords,
            createdAt = 0,
            updatedAt = 0
        )

    @Test
    fun `gather returns cards that speak to the question`() {
        val cards = listOf(
            card("21", "Institutions preserve social behavior"),
            card("22", "Cooking techniques and seasonal vegetables"),
            card("23", "Institutions and power over time")
        )

        val result = LumenManuscript.gather("How do institutions preserve behavior?", cards)

        assertEquals(listOf("21", "23"), result.map { it.id })
    }

    @Test
    fun `gather pulls the whole line behind a match`() {
        val cards = listOf(
            card("21", "Institutions preserve behavior"),
            card("21a", "Rituals repeat the institutional pattern"),
            card("21a1", "Ritual repetition as enforcement"),
            card("22", "Cooking techniques and vegetables")
        )

        val result = LumenManuscript.gather("institutions ritual", cards)

        // 21 matches first (more term hits), then its whole thread.
        assertEquals(listOf("21", "21a", "21a1"), result.map { it.id })
    }

    @Test
    fun `blank question gathers nothing`() {
        val cards = listOf(card("21", "Institutions preserve behavior"))
        assertTrue(LumenManuscript.gather("", cards).isEmpty())
        assertTrue(LumenManuscript.gather("  ", cards).isEmpty())
    }

    @Test
    fun `gather is bounded`() {
        val cards = (1..60).map { card("$it", "Institutions preserve social behavior") }
        val result = LumenManuscript.gather("institutions behavior", cards, limit = 12)
        assertTrue(result.size <= 12)
    }

    @Test
    fun `render emits question roles addresses and draft`() {
        val entries = listOf(
            ManuscriptEntry(card("21a", "Rituals repeat the pattern", back = "The ritual re-enacts the rule."), LumenRole.CLAIM),
            ManuscriptEntry(card("22", "Institutions persist"), LumenRole.EVIDENCE)
        )

        val text = LumenManuscript.render("Do institutions persist?", entries, "Yes, through repetition.")

        assertTrue(text.contains("Question: Do institutions persist?"))
        assertTrue(text.contains("1. [Claim] 21a — Rituals repeat the pattern"))
        assertTrue(text.contains("2. [Evidence] 22 — Institutions persist"))
        assertTrue(text.contains("Draft:"))
        assertTrue(text.contains("Yes, through repetition."))
    }

    @Test
    fun `render handles empty manuscript and unassigned roles`() {
        val empty = LumenManuscript.render("Question?", emptyList(), "")
        assertTrue(empty.contains("no slips gathered yet"))

        val untyped = LumenManuscript.render("", listOf(ManuscriptEntry(card("21", "Institutions"))), "")
        assertTrue(untyped.contains("1. 21 — Institutions"))
        assertTrue(!untyped.contains("["))
    }
}
