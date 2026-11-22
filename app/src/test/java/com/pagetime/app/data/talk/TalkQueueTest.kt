package com.pagetime.app.data.talk

import com.pagetime.app.data.talk.TalkQueue.Mode
import com.pagetime.app.data.talk.TalkQueue.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkQueueTest {

    private fun w(id: String, book: String = "b1", at: Long = 1L) = Waiting(
        id = id, bookId = book, bookTitle = "Why We Sleep", label = "Chapter 4", words = 2_000,
        finishedAt = at, chapter = 3, startOffset = null, endOffset = null,
    )

    @Test
    fun `a finished chapter is listed unless the reader wants no offers, and short ones never are`() {
        assertTrue(TalkQueue.shouldQueue(Mode.AT_CHAPTER_ENDS, 2_000))
        assertTrue(TalkQueue.shouldQueue(Mode.LIST_ONLY, 2_000))
        assertFalse(TalkQueue.shouldQueue(Mode.ONLY_ASK, 2_000))
        assertFalse(TalkQueue.shouldQueue(Mode.AT_CHAPTER_ENDS, 799))
        assertTrue(TalkQueue.shouldQueue(Mode.AT_CHAPTER_ENDS, 800))
    }

    @Test
    fun `a card appears only in chapter-end mode, once a session, alone, and not on a paused day`() {
        fun offer(
            mode: Mode = Mode.AT_CHAPTER_ENDS, words: Int = 2_000, session: Boolean = false,
            other: Boolean = false, paused: Long = 0L,
        ) = TalkQueue.shouldOfferNow(mode, words, session, other, 0, paused, today = 100L)
        assertTrue(offer())
        assertFalse(offer(mode = Mode.LIST_ONLY))
        assertFalse(offer(mode = Mode.ONLY_ASK))
        assertFalse(offer(session = true))
        assertFalse(offer(other = true))
        assertFalse(offer(paused = 100L))
        assertTrue(offer(paused = 99L))
        assertFalse(offer(words = 500))
    }

    @Test
    fun `two declines in a row pause the cards for the day, one does not`() {
        assertEquals(1 to 0L, TalkQueue.afterDecline(0, 0L, 100L))
        assertEquals(0 to 100L, TalkQueue.afterDecline(1, 0L, 100L))
    }

    @Test
    fun `the list round-trips, is newest first, repeats nothing and is bounded`() {
        val list = TalkQueue.add(TalkQueue.add(emptyList(), w("a", at = 1)), w("b", at = 2))
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(listOf("a", "b"), TalkQueue.add(list, w("a", at = 3)).map { it.id }.sorted())
        assertEquals(2, TalkQueue.add(list, w("a", at = 3)).size)
        assertEquals(list, TalkQueue.decode(TalkQueue.encode(list)))
        assertEquals(emptyList<Waiting>(), TalkQueue.decode("nonsense"))
        var big = emptyList<Waiting>()
        repeat(60) { big = TalkQueue.add(big, w("x$it")) }
        assertEquals(TalkQueue.MAX_WAITING, big.size)
    }

    @Test
    fun `a chapter is cut from its own first point to its last, and a deleted book's entries go`() {
        val text = w("t").copy(startOffset = 1_000, endOffset = 9_000, chapter = 0)
        assertTrue(text.startPoint().snapped)
        assertEquals(1_000, text.startPoint().textOffset)
        assertEquals(9_000, text.endPoint().textOffset)
        assertEquals(listOf("a"), TalkQueue.forBooks(listOf(w("a", "b1"), w("c", "gone")), setOf("b1")).map { it.id })
        assertEquals(listOf("b"), TalkQueue.remove(listOf(w("a"), w("b")), "a").map { it.id })
    }
}
