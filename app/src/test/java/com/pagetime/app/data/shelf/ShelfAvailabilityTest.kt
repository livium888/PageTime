package com.pagetime.app.data.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfAvailabilityTest {

    private fun state(
        ownedBookId: String? = null,
        ownedProgress: Float = 0f,
        availability: ShelfAvailability = ShelfAvailability.UNKNOWN,
        source: String? = null,
        catalogBookId: String? = null,
    ) = ShelfRows.stateOf(ownedBookId, ownedProgress, availability, source, catalogBookId)

    /**
     * The rule that stops a dead feed greying out a book already on disk.
     */
    @Test
    fun `a book you own is available whatever the catalogue last said`() {
        val s = state(ownedBookId = "2701", ownedProgress = 0.4f, availability = ShelfAvailability.UNAVAILABLE)
        assertTrue(s is ShelfRowState.Owned)
        assertEquals(0.4f, (s as ShelfRowState.Owned).progress, 0.0001f)
        assertFalse(ShelfRows.isDimmed(s))
    }

    @Test
    fun `a resolved book offers itself for download`() {
        val s = state(availability = ShelfAvailability.AVAILABLE, source = "gutenberg", catalogBookId = "2701")
        assertEquals(ShelfRowState.Downloadable("gutenberg", "2701"), s)
    }

    /**
     * Available with nothing to fetch is a half-finished resolve. Offering a
     * button that cannot work is worse than admitting we are still looking.
     */
    @Test
    fun `available but with no id to fetch is treated as still checking`() {
        assertEquals(ShelfRowState.Checking, state(availability = ShelfAvailability.AVAILABLE))
        assertEquals(
            ShelfRowState.Checking,
            state(availability = ShelfAvailability.AVAILABLE, source = "gutenberg", catalogBookId = "  ")
        )
    }

    @Test
    fun `a book no catalogue has is dimmed and sends the reader elsewhere`() {
        val s = state(availability = ShelfAvailability.UNAVAILABLE)
        assertEquals(ShelfRowState.SourceElsewhere, s)
        assertTrue(ShelfRows.isDimmed(s))
    }

    /** The note is about our catalogues. It must not stray into the law. */
    @Test
    fun `the unavailable note makes no claim about copyright`() {
        val note = ShelfRows.sourceElsewhereNote().lowercase()
        listOf("copyright", "public domain", "free", "legal", "licence", "license").forEach {
            assertFalse("note mentions '$it': $note", note.contains(it))
        }
        assertTrue(note.contains("yourself"))
    }

    @Test
    fun `unchecked entries say nothing either way`() {
        assertEquals(ShelfRowState.Checking, state())
    }

    /**
     * Progress must not be flattered by excluding the books we cannot supply:
     * the goal is the whole ladder.
     */
    @Test
    fun `unavailable entries count as not done`() {
        val states = listOf(
            ShelfRowState.Owned("a", 1f),
            ShelfRowState.Owned("b", 0.5f),
            ShelfRowState.SourceElsewhere,
            ShelfRowState.Downloadable("gutenberg", "1"),
            ShelfRowState.Checking,
        )
        assertEquals(1, ShelfRows.completed(states))
    }

    @Test
    fun `availability keys survive a round trip`() {
        ShelfAvailability.entries.forEach {
            assertEquals(it, ShelfAvailability.fromKey(it.key))
        }
        assertEquals(ShelfAvailability.UNKNOWN, ShelfAvailability.fromKey("nonsense"))
        assertEquals(ShelfAvailability.UNKNOWN, ShelfAvailability.fromKey(null))
    }
}
