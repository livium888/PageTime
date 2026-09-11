package com.pagetime.app.data.learning

import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.local.LearningCardEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkCardTest {

    private val NOW = 1_700_000_000_000L

    private fun card(
        prompt: String = "What did the opium economy do to the silver supply?",
        answer: String = "It reversed the flow, draining silver out of China.",
        explanation: String? = null,
        sourceFraction: Float = 0.42f
    ): LearningCardEntity? = ChunkCard.create(
        id = "card-1",
        bookId = "b1",
        chapterIndex = 3,
        chapterTitle = "Chapter 4",
        prompt = prompt,
        answer = answer,
        explanation = explanation,
        sourceLocator = "{\"href\":\"ch4\"}",
        sourceFraction = sourceFraction,
        now = NOW
    )

    @Test
    fun `a question with an answer is a card`() {
        val written = card()
        assertNotNull(written)
        assertEquals("card-1", written?.id)
        assertEquals(LearningCardEntity.TYPE_QA, written?.cardType)
    }

    @Test
    fun `a card with no question is not written`() {
        // A question with no answer is a note. A card with no question is
        // nothing at all, and neither can ever be graded.
        assertNull(card(prompt = "   "))
    }

    @Test
    fun `a card with no answer is not written`() {
        assertNull(card(answer = ""))
    }

    @Test
    fun `a kept card is due, so the next sitting asks it`() {
        val written = card()!!
        assertEquals(LearningCardEntity.STATUS_KEPT, written.status)
        assertEquals(NOW, written.dueAt)
        assertEquals(NOW, written.createdAt)
        assertEquals(0, written.reviewCount)
    }

    @Test
    fun `a fresh card is new to the scheduler rather than zeroed`() {
        // null stability and difficulty is how FSRS says "never reviewed". A
        // zeroed card is read as already scheduled, and the first rating then
        // schedules a card it believes has history.
        val parsed = FsrsCardCodec.fromJson(card()!!.fsrsCardJson)
        assertNull(parsed.stability)
        assertNull(parsed.difficulty)
        assertEquals(Instant.ofEpochMilli(NOW), parsed.due)
    }

    @Test
    fun `a reader's own question is never marked as generated`() {
        // generationKey is what stops a chapter being regenerated. A card the
        // reader wrote must not make the app believe the chapter was covered.
        val written = card()!!
        assertFalse(written.generatedByAi)
        assertNull(written.generationKey)
    }

    @Test
    fun `the card remembers where in the book it came from`() {
        val written = card(sourceFraction = 0.42f)!!
        assertEquals("{\"href\":\"ch4\"}", written.sourceLocator)
        assertEquals(0.42f, written.sourceFraction)
        assertEquals(3, written.chapterIndex)
        assertEquals("Chapter 4", written.chapterTitle)
    }

    @Test
    fun `a source position is clamped to somewhere in the book`() {
        assertEquals(1f, card(sourceFraction = 2f)!!.sourceFraction)
        assertEquals(0f, card(sourceFraction = -1f)!!.sourceFraction)
    }

    @Test
    fun `a blank explanation is stored as nothing`() {
        assertNull(card(explanation = "   ")!!.explanation)
        assertEquals("a note", card(explanation = " a note ")!!.explanation)
    }

    @Test
    fun `leading and trailing whitespace never reaches the card`() {
        val written = card(prompt = "  Q?  ", answer = "  A.  ")!!
        assertEquals("Q?", written.prompt)
        assertEquals("A.", written.answer)
    }
}
