package com.pagetime.app.data

import com.pagetime.app.data.local.PagemarkEntity
import io.github.openspacedrepetition.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagemarkSessionTest {

    private val NOW = 1_000_000L

    private fun chunk(
        id: String = "c1",
        state: PagemarkSession.State = PagemarkSession.State.QUEUED,
        priority: Int = PagemarkSession.DEFAULT_PRIORITY,
        dueAt: Long? = null,
        reviewCount: Int = 0,
        createdAt: Long = 100L
    ) = PagemarkEntity(
        id = id,
        bookId = "b1",
        title = "Chunk $id",
        startLocatorJson = null,
        startFraction = 0.1f,
        endLocatorJson = null,
        endFraction = 0f,
        state = state.name,
        priority = priority,
        fsrsCardJson = null,
        dueAt = dueAt,
        reviewCount = reviewCount,
        createdAt = createdAt,
        updatedAt = 100L
    )

    // region Transitions

    @Test
    fun `begin opens any chunk for reading`() {
        listOf(
            PagemarkSession.State.QUEUED,
            PagemarkSession.State.SUSPENDED,
            PagemarkSession.State.DONE
        ).forEach { state ->
            val opened = PagemarkSession.begin(chunk(state = state), now = NOW)
            assertEquals(
                "a ${state.name} chunk must open for reading",
                PagemarkSession.State.READING.name,
                opened.state
            )
            assertEquals(NOW, opened.updatedAt)
        }
    }

    @Test
    fun `opening a due chunk clears the due time it is now satisfying`() {
        // Otherwise the chunk is READing and due at once, and the queue draws
        // it in both groups.
        val opened = PagemarkSession.begin(
            chunk(state = PagemarkSession.State.DONE, dueAt = NOW - 500),
            now = NOW
        )
        assertEquals(null, opened.dueAt)
    }

    @Test
    fun `a chunk being read is never listed twice`() {
        val reading = chunk("a", PagemarkSession.State.READING, dueAt = NOW - 500)
        val ordered = PagemarkSession.orderForQueue(listOf(reading), NOW)
        assertEquals(listOf("a"), ordered.map { it.id })
    }

    @Test
    fun `suspend only pauses a reading chunk`() {
        val suspended = PagemarkSession.suspend(chunk(state = PagemarkSession.State.READING, dueAt = 500L), now = NOW)
        assertEquals(PagemarkSession.State.SUSPENDED.name, suspended.state)
        // A suspended chunk is not scheduled; it just waits its turn.
        assertEquals(null, suspended.dueAt)

        val queued = chunk(state = PagemarkSession.State.QUEUED)
        assertEquals(queued, PagemarkSession.suspend(queued, now = NOW))
    }

    @Test
    fun `close records the end, the rating and the next due time`() {
        val closed = PagemarkSession.close(
            chunk = chunk(state = PagemarkSession.State.READING),
            rating = PagemarkSession.GOOD,
            endLocatorJson = null,
            endFraction = 0.5f,
            nextDueMillis = 900_000L,
            now = NOW
        )
        assertEquals(PagemarkSession.State.DONE.name, closed.state)
        assertEquals(0.5f, closed.endFraction)
        assertEquals(900_000L, closed.dueAt)
        assertEquals(1, closed.reviewCount)
        assertEquals(PagemarkSession.GOOD, closed.lastRating)
    }

    // endregion

    // region Ratings

    @Test
    fun `offered ratings are the reading chair's three`() {
        assertEquals(listOf(PagemarkSession.AGAIN, PagemarkSession.HARD, PagemarkSession.GOOD), PagemarkSession.OFFERED_RATINGS)
    }

    @Test
    fun `ratings map onto the FSRS scheduler`() {
        assertEquals(Rating.AGAIN, PagemarkSession.ratingToFsrs(PagemarkSession.AGAIN))
        assertEquals(Rating.HARD, PagemarkSession.ratingToFsrs(PagemarkSession.HARD))
        assertEquals(Rating.GOOD, PagemarkSession.ratingToFsrs(PagemarkSession.GOOD))
    }

    @Test
    fun `an unknown rating never reaches Easy`() {
        // Easy is not on offer seconds after reading the passage, so nothing
        // that slips through a UI bug should be able to mint a long holiday.
        assertEquals(Rating.GOOD, PagemarkSession.ratingToFsrs(4))
        assertEquals(Rating.GOOD, PagemarkSession.ratingToFsrs(99))
    }

    // endregion

    // region Actionability and due

    @Test
    fun `queued suspended and reading chunks are always actionable`() {
        listOf(
            PagemarkSession.State.QUEUED,
            PagemarkSession.State.SUSPENDED,
            PagemarkSession.State.READING
        ).forEach { state ->
            assertTrue("a ${state.name} chunk must be actionable", PagemarkSession.isActionable(chunk(state = state), NOW))
        }
    }

    @Test
    fun `a done chunk is actionable only once its re-read is due`() {
        val notDue = chunk(state = PagemarkSession.State.DONE, dueAt = NOW + 10_000)
        assertFalse(PagemarkSession.isActionable(notDue, NOW))

        val due = chunk(state = PagemarkSession.State.DONE, dueAt = NOW - 1)
        assertTrue(PagemarkSession.isActionable(due, NOW))
    }

    @Test
    fun `due count ignores the chunk the reader is already in`() {
        val items = listOf(
            chunk("reading", PagemarkSession.State.READING, dueAt = NOW - 100),
            chunk("due", PagemarkSession.State.DONE, dueAt = NOW - 100),
            chunk("later", PagemarkSession.State.DONE, dueAt = NOW + 100)
        )
        assertEquals(1, PagemarkSession.dueCount(items, NOW))
    }

    // endregion

    // region Ordering

    @Test
    fun `the queue leads with the reading chunk`() {
        val reading = chunk("reading", PagemarkSession.State.READING)
        val queued = chunk("queued")
        val ordered = PagemarkSession.orderForQueue(listOf(queued, reading), NOW)
        assertEquals(listOf("reading", "queued"), ordered.map { it.id })
    }

    @Test
    fun `due re-reads come before fresh and suspended chunks`() {
        val due = chunk("due", PagemarkSession.State.DONE, dueAt = NOW - 10)
        val fresh = chunk("fresh", PagemarkSession.State.QUEUED, priority = 5)
        val ordered = PagemarkSession.orderForQueue(listOf(fresh, due), NOW)
        assertEquals(listOf("due", "fresh"), ordered.map { it.id })
    }

    @Test
    fun `waiting chunks order by priority then age`() {
        val high = chunk("high", PagemarkSession.State.QUEUED, priority = 5, createdAt = 200L)
        val low = chunk("low", PagemarkSession.State.SUSPENDED, priority = 1, createdAt = 100L)
        val olderSame = chunk("older-same", PagemarkSession.State.QUEUED, priority = 3, createdAt = 100L)
        val newerSame = chunk("newer-same", PagemarkSession.State.QUEUED, priority = 3, createdAt = 300L)
        val ordered = PagemarkSession.orderForQueue(listOf(newerSame, low, olderSame, high), NOW)
        assertEquals(listOf("high", "older-same", "newer-same", "low"), ordered.map { it.id })
    }

    @Test
    fun `finished chunks that are not due come last`() {
        val scheduled = chunk("scheduled", PagemarkSession.State.DONE, dueAt = NOW + 100)
        val queued = chunk("queued", PagemarkSession.State.QUEUED)
        val ordered = PagemarkSession.orderForQueue(listOf(scheduled, queued), NOW)
        assertEquals(listOf("queued", "scheduled"), ordered.map { it.id })
    }

    // endregion

    // region Priorities and titles

    @Test
    fun `priority clamps into one to five`() {
        assertEquals(1, PagemarkSession.clampPriority(0))
        assertEquals(5, PagemarkSession.clampPriority(9))
        assertEquals(3, PagemarkSession.clampPriority(3))
    }

    @Test
    fun `next title numbers chunks from the count`() {
        assertEquals("Chunk 1", PagemarkSession.nextTitle(0))
        assertEquals("Chunk 4", PagemarkSession.nextTitle(3))
    }

    @Test
    fun `unknown stored state reads as queued`() {
        val unknown = chunk().copy(state = "GONE")
        assertEquals(PagemarkSession.State.QUEUED, PagemarkSession.stateOf(unknown))
    }

    // endregion

    // region One action, not two

    /** The default chunk here runs 10%–50% of the book. */
    private fun covers(
        spanStart: Float,
        spanEnd: Float,
        chunkStart: Float = 0.10f,
        chunkEnd: Float = 0.50f
    ) = PagemarkSession.coversSpan(
        startFraction = chunkStart,
        endFraction = chunkEnd,
        spanStartFraction = spanStart,
        spanEndFraction = spanEnd
    )

    @Test
    fun `finishing short of the end of the book opens the next chunk`() {
        assertTrue(PagemarkSession.opensNextChunk(0f))
        assertTrue(PagemarkSession.opensNextChunk(0.42f))
    }

    @Test
    fun `finishing at the end of the book opens nothing`() {
        // A chunk opened on the last page could never be read, so the queue
        // would carry a ghost that no sitting can ever clear.
        assertFalse(PagemarkSession.opensNextChunk(PagemarkSession.END_OF_BOOK))
        assertFalse(PagemarkSession.opensNextChunk(1f))
    }

    @Test
    fun `a page overlapping the chunk's span is inside it`() {
        assertTrue(covers(0.20f, 0.30f))
        // Half a page of overlap counts: the reader is in the chunk.
        assertTrue(covers(0.29f, 0.31f))
    }

    @Test
    fun `a page that only touches the chunk's edge is outside it`() {
        // The page ends exactly where the chunk starts, or starts exactly
        // where it ends. Neither page is read as part of the chunk.
        assertFalse(covers(0.00f, 0.10f))
        assertFalse(covers(0.50f, 0.60f))
    }

    @Test
    fun `an unfinished chunk covers nothing until the reader's position is its end`() {
        // A chunk still being read has no end on its row (0), and a span that
        // ends where it starts covers nothing — which is why the reader screen
        // passes the reader's own position as the end.
        assertFalse(covers(0.60f, 0.70f, chunkEnd = 0f))
        assertTrue(covers(0.60f, 0.70f, chunkEnd = 0.65f))
    }

    @Test
    fun `a finished chunk reads as a span and an unfinished one from its start`() {
        assertEquals("34% \u2192 41%", PagemarkSession.spanLabel(0.34f, 0.41f))
        assertEquals("from 34%", PagemarkSession.spanLabel(0.34f, 0f))
        // An end that has not moved past the start is not an end at all.
        assertEquals("from 50%", PagemarkSession.spanLabel(0.5f, 0.5f))
    }

    @Test
    fun `a span label stays inside the book even for a stray fraction`() {
        assertEquals("0% \u2192 100%", PagemarkSession.spanLabel(-0.5f, 2f))
    }

    @Test
    fun `a due chunk says when it comes back rather than that it is scheduled`() {
        assertEquals("Not scheduled", PagemarkSession.dueLabel(null, NOW))
        assertEquals("Due now", PagemarkSession.dueLabel(NOW, NOW))
        assertEquals("Due now", PagemarkSession.dueLabel(NOW - 60_000L, NOW))
        // Never "Back in 0 min" a second after finishing a chunk.
        assertEquals("Back in 1 min", PagemarkSession.dueLabel(NOW + 30_000L, NOW))
        assertEquals("Back in 5 min", PagemarkSession.dueLabel(NOW + 5 * 60_000L, NOW))
        assertEquals("Back in 1 h", PagemarkSession.dueLabel(NOW + 60 * 60_000L, NOW))
        assertEquals("Back in 3 h", PagemarkSession.dueLabel(NOW + 3 * 3_600_000L, NOW))
        assertEquals("Back in 3 days", PagemarkSession.dueLabel(NOW + 3 * 86_400_000L, NOW))
    }

    // endregion
}