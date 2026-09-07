package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cloze card as a person sees it.
 *
 * The stored form is the SuperMemo convention and belongs in the database. A
 * card that renders its own markup reads as broken software however good the
 * question underneath it is.
 */
class ClozeTextTest {

    private val sentence =
        "Smuggling was more profitable than {{c1::compliance}}, and Napoleon's allies were the worst."

    @Test
    fun `the reader sees a gap, not the markup`() {
        val shown = ClozeText.blanked(sentence)
        assertFalse(shown.contains("{{"))
        assertFalse(shown.contains("c1::"))
        assertTrue(shown.contains(ClozeText.GAP))
        assertFalse("The answer must not be on the front", shown.contains("compliance"))
    }

    @Test
    fun `revealing puts the sentence back exactly`() {
        assertEquals(
            "Smuggling was more profitable than compliance, and Napoleon's allies were the worst.",
            ClozeText.filled(sentence),
        )
    }

    @Test
    fun `several deletions are all handled`() {
        val many = "The {{c1::Continental System}} closed ports to {{c2::British}} goods."
        assertEquals(listOf("Continental System", "British"), ClozeText.deletions(many))
        assertEquals(
            "The ${ClozeText.GAP} closed ports to ${ClozeText.GAP} goods.",
            ClozeText.blanked(many),
        )
        assertEquals("The Continental System closed ports to British goods.", ClozeText.filled(many))
    }

    @Test
    fun `text with no deletion passes through untouched`() {
        // A qa card must survive being handed to this by mistake rather than
        // being mangled by it.
        val plain = "Why did the Continental System fail?"
        assertFalse(ClozeText.hasDeletion(plain))
        assertEquals(plain, ClozeText.blanked(plain))
        assertEquals(plain, ClozeText.filled(plain))
    }
}
