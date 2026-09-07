package com.pagetime.app.data.embed

import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing what a chapter is about.
 *
 * The failure this has to be tested against is not a crash — it is returning
 * five passages that are all about the same thing, which looks like a working
 * feature right up until the reader notices they have been asked the same
 * question five times.
 */
class ChapterTopicsTest {

    private fun row(
        id: Int,
        vector: FloatArray,
        startOffset: Int,
        endOffset: Int,
        chapterIndex: Int = 0,
        dimensions: Int = vector.size,
    ) = BookChunkEmbeddingEntity(
        bookId = "book",
        chapterIndex = chapterIndex,
        ordinal = id,
        startOffset = startOffset,
        endOffset = endOffset,
        text = "chunk $id",
        model = "test/1",
        dimensions = dimensions,
        vector = EmbeddingMath.toBytes(vector),
        indexedAt = 0L,
    )

    @Test
    fun `a chapter with nothing in it has nothing to say`() {
        assertTrue(ChapterTopics.select(emptyList()).isEmpty())
    }

    /**
     * Three clusters, three picks, one from each. This is the whole point:
     * nearest-to-centre alone would take three from whichever cluster sits
     * closest to the middle.
     */
    @Test
    fun `distinct ideas are picked over near duplicates`() {
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(1, floatArrayOf(0.99f, 0.141f, 0f), 500, 900),
            row(2, floatArrayOf(0.99f, 0f, 0.141f), 1000, 1400),
            row(3, floatArrayOf(0f, 1f, 0f), 1500, 1900),
            row(4, floatArrayOf(0.141f, 0.99f, 0f), 2000, 2400),
            row(5, floatArrayOf(0f, 0f, 1f), 2500, 2900),
            row(6, floatArrayOf(0f, 0.141f, 0.99f), 3000, 3400),
        )
        val picked = ChapterTopics.select(rows, count = 3)
        assertEquals(3, picked.size)
        // One from the x cluster, one from y, one from z.
        assertEquals(listOf(500, 2000, 3000), picked.map { it.startOffset })
    }

    /**
     * The pair that proves the diversity term earns its place: the SAME three
     * passages, selected two ways. Pure centrality takes both halves of one
     * idea; the real setting takes one of those and then the other idea.
     */
    @Test
    fun `centrality alone would return the same idea twice`() {
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(1, floatArrayOf(0.995f, 0.0998f, 0f), 500, 900),
            row(2, floatArrayOf(0.6f, 0.8f, 0f), 1000, 1400),
        )
        val greedy = ChapterTopics.select(rows, count = 2, lambda = 1f)
        assertEquals(listOf(0, 500), greedy.map { it.startOffset })

        val balanced = ChapterTopics.select(rows, count = 2)
        assertEquals(listOf(500, 1000), balanced.map { it.startOffset })
    }

    @Test
    fun `two chunks sharing text are one idea`() {
        // Chunks overlap by 80 characters by design, so the neighbour of a
        // chosen passage is largely the same words.
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(1, floatArrayOf(1f, 0f, 0f), 320, 700),
            row(2, floatArrayOf(0f, 1f, 0f), 800, 1200),
        )
        val picked = ChapterTopics.select(rows, count = 2)
        assertEquals(listOf(0, 800), picked.map { it.startOffset })
    }

    @Test
    fun `a short chapter gives back what it has, not what was asked for`() {
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(1, floatArrayOf(0f, 1f, 0f), 500, 900),
        )
        assertEquals(2, ChapterTopics.select(rows, count = 5).size)
    }

    @Test
    fun `passages come back in reading order`() {
        val rows = listOf(
            row(0, floatArrayOf(0f, 0f, 1f), 2000, 2400),
            row(1, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(2, floatArrayOf(0f, 1f, 0f), 1000, 1400),
        )
        val picked = ChapterTopics.select(rows, count = 3)
        assertEquals(listOf(0, 1000, 2000), picked.map { it.startOffset })
    }

    @Test
    fun `a vector of another width is left out of the average`() {
        // Averaging a four-wide vector into a three-wide centroid is not an
        // error anything raises; it is a centroid that describes nothing.
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 400),
            row(1, floatArrayOf(0f, 1f, 0f), 500, 900),
            row(2, floatArrayOf(1f, 1f, 1f, 1f), 1000, 1400),
        )
        val picked = ChapterTopics.select(rows, count = 3)
        assertEquals(listOf(0, 500), picked.map { it.startOffset })
    }

    @Test
    fun `a passage carries where it sits so a prompt can meet the reader there`() {
        val rows = listOf(row(7, floatArrayOf(1f, 0f, 0f), 1200, 1600, chapterIndex = 4))
        val only = ChapterTopics.select(rows, count = 1).single()
        assertEquals(4, only.chapterIndex)
        assertEquals(7, only.ordinal)
        assertEquals(1200, only.startOffset)
        assertEquals(1600, only.endOffset)
        assertEquals("chunk 7", only.text)
    }

    @Test
    fun `chapter length decides how many passages it is worth`() {
        // Quantum Country's spacing: a review area every few hundred words.
        // A 2,000-word chapter and a 12,000-word chapter are not both worth
        // four passages.
        assertEquals(4, ChapterTopics.countFor(0))
        assertEquals(4, ChapterTopics.countFor(11_000))
        assertEquals(10, ChapterTopics.countFor(27_000))
        assertEquals(24, ChapterTopics.countFor(66_000))
        assertEquals(24, ChapterTopics.countFor(200_000))
    }

    @Test
    fun `density is in the range the mnemonic medium actually uses`() {
        // The reason this file changed at all. Orbit's author documentation
        // describes review areas interleaved "every few hundred words", each
        // holding several prompts. Assert the property rather than the
        // constant, so tuning CHARS_PER_TOPIC cannot quietly walk us back to
        // the sparse version without failing here.
        //
        // ~6 characters per word, so a 30,000-character chapter is ~5,000
        // words and should carry a prompt every 200 words or better.
        val chapterChars = 30_000
        val words = chapterChars / 6
        val ceiling = ChapterTopics.promptCeilingFor(chapterChars)
        val wordsPerPrompt = words / ceiling
        assertTrue(
            "one prompt per $wordsPerPrompt words is sparser than the medium",
            wordsPerPrompt <= 200,
        )
    }

    @Test
    fun `the ceiling is passages times prompts per passage`() {
        assertEquals(
            ChapterTopics.countFor(40_000) * ChapterTopics.PROMPTS_PER_PASSAGE,
            ChapterTopics.promptCeilingFor(40_000),
        )
    }
}
