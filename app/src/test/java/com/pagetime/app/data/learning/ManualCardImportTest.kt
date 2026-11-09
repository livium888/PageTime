package com.pagetime.app.data.learning

import com.pagetime.app.data.local.LearningCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCardImportTest {

    private val span =
        "Napoleon signed the Berlin Decree in November 1806, declaring the British Isles under blockade. " +
            "Merchants across Europe nonetheless kept trading through neutral ports, and smuggling flourished " +
            "along the Baltic coast. By 1810 the blockade was starving continental industries of raw materials " +
            "more than it was hurting Britain, whose navy kept its own sea lanes open and found new markets in " +
            "South America."

    private val good = """{"passageIndex":0,"type":"qa","prompt":"Why did the blockade hurt continental industries more than Britain?","answer":"It cut them off from raw materials while Britain found other markets.","explanation":"A blockade only bites if the target cannot trade elsewhere, and Britain could redirect its exports across the Atlantic while Europe lost its imports.","sourceQuote":"the blockade was starving continental industries of raw materials more than it was hurting Britain"}"""

    private val cloze = """{"passageIndex":0,"type":"cloze","prompt":"Napoleon signed the Berlin Decree in {{c1::November 1806}}, declaring the British Isles under blockade.","answer":"November 1806","explanation":"The decree launched the Continental System, the economic war that shaped the rest of his reign.","sourceQuote":"Napoleon signed the Berlin Decree in November 1806, declaring the British Isles under blockade."}"""

    private val invented = """{"passageIndex":0,"type":"qa","prompt":"Who commanded the British fleet at Trafalgar?","answer":"Nelson","explanation":"He led the line of battle that ended French naval hopes of invading Britain in 1805.","sourceQuote":"Nelson commanded the British fleet at Trafalgar in 1805"}"""

    @Test
    fun `good cards pass and an invented one is left out with its reason`() {
        val review = ManualCardImport.review("```json\n{\"prompts\":[$good,$cloze,$invented]}\n```", span)
        assertEquals(2, review.accepted.size)
        assertEquals(1, review.leftOut.size)
        assertEquals(PromptRejection.QUOTE_NOT_IN_PASSAGE, review.leftOut.single().second)
        assertEquals("Who commanded the British fleet at Trafalgar?", review.leftOut.single().first.prompt)
    }

    @Test
    fun `a reply that is not cards at all says so`() {
        val review = ManualCardImport.review("I'm sorry, I can't do that.", span)
        assertFalse(review.sawJson)
        assertFalse(review.foundAnything)
    }

    @Test
    fun `the same card pasted twice is only kept once`() {
        val review = ManualCardImport.review("[$good,$good]", span)
        assertEquals(1, review.accepted.size)
        assertEquals(PromptRejection.DUPLICATE, review.leftOut.single().second)
    }

    @Test
    fun `chosen cards become kept due-now cards that are never marked as AI generated`() {
        val review = ManualCardImport.review("[$good,$cloze]", span)
        var n = 0
        val cards = ManualCardImport.cards(
            chosen = review.accepted,
            bookId = "book-1",
            chapterIndex = 3,
            chapterTitle = "The Blockade",
            endFraction = 0.4f,
            now = 5_000L,
            newId = { "id-${n++}" },
        )
        assertEquals(2, cards.size)
        cards.forEach {
            assertEquals(LearningCardEntity.STATUS_KEPT, it.status)
            assertEquals(5_000L, it.dueAt)
            assertFalse(it.generatedByAi)
            assertNull(it.generationKey)
            assertEquals("book-1", it.bookId)
            assertEquals(3, it.chapterIndex)
        }
        assertEquals(listOf("id-0", "id-1"), cards.map { it.id })
        assertEquals(LearningCardEntity.TYPE_QA, cards[0].cardType)
        assertEquals(LearningCardEntity.TYPE_CLOZE, cards[1].cardType)
        assertTrue(cards[1].prompt.contains("{{c1::November 1806}}"))
    }

    @Test
    fun `nothing chosen makes no cards`() {
        assertTrue(
            ManualCardImport.cards(emptyList(), "b", 0, null, 0f, 1L).isEmpty()
        )
    }
}
