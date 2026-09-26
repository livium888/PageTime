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
        text: String = "chunk $id",
    ) = BookChunkEmbeddingEntity(
        bookId = "book",
        chapterIndex = chapterIndex,
        ordinal = id,
        startOffset = startOffset,
        endOffset = endOffset,
        text = text,
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

    @Test
    fun `a fragment is never sent as a passage`() {
        // Reported from the device: passages reading "privacy." and "ading
        // corporations." were sent to the model, which wrote nothing about
        // them. Asking a language model to build a flashcard from eight
        // characters costs tokens and can only fail.
        val real = "A".repeat(300)
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 300, text = real),
            row(1, floatArrayOf(0f, 1f, 0f), 400, 408, text = "privacy."),
            row(2, floatArrayOf(0f, 0f, 1f), 500, 800, text = real),
        )
        val chosen = ChapterTopics.select(rows, count = 3)
        assertTrue(
            "a fragment was chosen: ${chosen.map { it.text }}",
            chosen.none { it.text.length < ChapterTopics.MIN_PASSAGE_CHARS },
        )
    }

    @Test
    fun `a chapter of nothing but fragments still yields its best`() {
        // The guard must not turn a short chapter into "there is nothing in
        // this chapter", which would be a worse lie than a weak passage.
        val rows = listOf(
            row(0, floatArrayOf(1f, 0f, 0f), 0, 8, text = "privacy."),
            row(1, floatArrayOf(0f, 1f, 0f), 20, 30, text = "and trade."),
        )
        assertTrue(ChapterTopics.select(rows, count = 2).isNotEmpty())
    }

    // --- What the model is actually handed ---------------------------------

    /**
     * A chapter of known sentences, so a window can be measured against it.
     *
     * Real prose, because the point of these tests is where a window may begin
     * and end: a run of one character cannot show that a boundary was snapped
     * to a word.
     */
    private fun chapter(sentences: Int): String =
        (0 until sentences).joinToString(" ") {
            "Sentence number $it carries one idea about the world."
        }

    /** The chunk the index cut, at [from] and [to] of the same chapter. */
    private fun chunkRow(id: Int, text: String, from: Int, to: Int): BookChunkEmbeddingEntity =
        row(id, unit(id), from, to, text = text.substring(from, to))

    private fun unit(id: Int): FloatArray = when (id % 3) {
        0 -> floatArrayOf(1f, 0f, 0f)
        1 -> floatArrayOf(0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f)
    }

    /**
     * The reported defect: a prompt was written from a sixty-word fragment.
     *
     * The index chunks to the embedding model's budget — 380 characters — which
     * is the right size for a vector and far too small for a question. Asked for
     * up to three prompts from one paragraph, the only honest answer is a
     * lookup, because a paragraph contains no mechanism, cause or contrast to
     * ask about.
     */
    @Test
    fun `a chosen passage arrives with the prose around it`() {
        val text = chapter(160)
        val cut = listOf(0 to 300, 3_000 to 3_300, 6_000 to 6_300)
        val rows = cut.mapIndexed { id, (from, to) -> chunkRow(id, text, from, to) }

        val picked = ChapterTopics.select(rows, count = 3, chapterText = text)
        assertEquals(3, picked.size)
        picked.forEachIndexed { index, passage ->
            val (from, to) = cut[index]
            assertTrue(
                "a ${passage.text.length}-character passage is still a fragment",
                passage.text.length >= 1_500,
            )
            // The idea the vectors picked is still in it, and the offsets still
            // describe the text that was handed over: a prompt surfaces by
            // fraction, and a window that did not line up would surface it in
            // the wrong place in the book.
            assertTrue(passage.text.contains(text.substring(from, to)))
            assertEquals(passage.text, text.substring(passage.startOffset, passage.endOffset))
        }
    }

    /**
     * Overlap would be the same words sent twice, and the same question asked
     * twice for the price of two requests.
     */
    @Test
    fun `two passages never cover the same text`() {
        val text = chapter(160)
        val rows = listOf(
            chunkRow(0, text, 500, 800),
            chunkRow(1, text, 900, 1_200),
        )
        val picked = ChapterTopics.select(rows, count = 2, chapterText = text)
        assertEquals(2, picked.size)
        assertTrue(
            "passages overlap: ${picked.map { it.startOffset..it.endOffset }}",
            picked[0].endOffset <= picked[1].startOffset,
        )
    }

    /**
     * Growing by a third of the shortfall in characters lands wherever it
     * lands, and a window opening on the middle of a word is the defect the
     * chunker was versioned twice to remove. It must not come back through the
     * window.
     */
    @Test
    fun `a widened passage begins and ends on whole words`() {
        val text = chapter(160)
        val rows = listOf(chunkRow(0, text, 1_400, 1_700), chunkRow(1, text, 500, 800))
        val picked = ChapterTopics.select(rows, count = 2, chapterText = text)
        picked.forEach { passage ->
            if (passage.startOffset > 0) {
                assertTrue(
                    "opens mid-word: ${passage.text.take(24)}",
                    text[passage.startOffset].isWhitespace() ||
                        text[passage.startOffset - 1].isWhitespace(),
                )
            }
            if (passage.endOffset < text.length) {
                assertTrue(
                    "ends mid-word: ${passage.text.takeLast(24)}",
                    text[passage.endOffset].isWhitespace() ||
                        text[passage.endOffset - 1].isWhitespace(),
                )
            }
        }
    }

    /**
     * A caller with no chapter to read from — and every existing test — must
     * keep the old behaviour rather than crashing on a null.
     */
    @Test
    fun `with no chapter text the index's own chunks are returned`() {
        val rows = listOf(row(7, floatArrayOf(1f, 0f, 0f), 1200, 1600))
        val only = ChapterTopics.select(rows, count = 1).single()
        assertEquals(1200, only.startOffset)
        assertEquals(1600, only.endOffset)
        assertEquals("chunk 7", only.text)
    }
}
