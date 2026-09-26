package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LumenStrayNotesTest {

    private fun card(id: String, links: List<String> = emptyList(), createdAt: Long = 0L) = LumenCardEntity(
        id = id,
        bookId = "",
        box = 1,
        indexNumber = id,
        front = id,
        back = "",
        quote = "",
        sourceLocatorJson = null,
        sourceChapterIndex = null,
        sourceFraction = 0f,
        linksJson = LumenCapture.linksToJson(links),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `an empty box has nothing stray`() {
        val queue = LumenStrayNotes.queue(emptyList())
        assertEquals(0, queue.count)
        assertNull(queue.oldest)
    }

    @Test
    fun `a card with no links is stray, a linked one is not`() {
        val stray = card("1")
        val linked = card("2", links = listOf("3"))
        val queue = LumenStrayNotes.queue(listOf(stray, linked))
        assertEquals(1, queue.count)
        assertEquals("1", queue.oldest?.id)
    }

    @Test
    fun `the oldest stray card is offered first`() {
        val newer = card("new", createdAt = 200L)
        val older = card("old", createdAt = 100L)
        val queue = LumenStrayNotes.queue(listOf(newer, older))
        assertEquals(2, queue.count)
        assertEquals("old", queue.oldest?.id)
    }

    @Test
    fun `a link in either direction still counts as connected`() {
        // linksJson only needs to be non-empty on the card's own side — this
        // mirrors LumenRepository.link(), which writes both sides, but a
        // stray check should not assume the other side is always in sync.
        val onlyMentionsOther = card("1", links = listOf("2"))
        val queue = LumenStrayNotes.queue(listOf(onlyMentionsOther))
        assertEquals(0, queue.count)
    }
}
