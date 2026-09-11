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
        reviewCount: Int = 0
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
        createdAt = 100L,
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
}