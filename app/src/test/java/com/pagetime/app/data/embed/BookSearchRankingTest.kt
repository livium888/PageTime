package com.pagetime.app.data.embed

import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Ranking search results, where — as everywhere in this stack — the mistakes
 * do not throw.
 *
 * A nearest-neighbour search always returns neighbours. Rank a query against a
 * book that never mentions it and you get the book's least-unrelated
 * paragraphs, presented with the same confidence as a real answer. Rank
 * overlapping chunks and you get the same paragraph twice, presented as two
 * findings. Compare a vector against one of another width and you get a
 * number. None of it is an exception; all of it is a reader concluding that
 * search does not work.
 */
class BookSearchRankingTest {

    private val query = floatArrayOf(1f, 0f, 0f)

    /**
     * A row whose cosine against [query] is exactly [similarity].
     *
     * With a query of (1, 0, 0), the vector (c, √(1−c²), 0) has cosine c, so
     * every case below can name the similarity it means to test instead of
     * hoping some vector lands near it.
     */
    private fun row(
        similarity: Float,
        chapterIndex: Int = 0,
        ordinal: Int = 0,
        startOffset: Int = 0,
        endOffset: Int = 400,
        dimensions: Int = 3,
        vector: FloatArray = floatArrayOf(similarity, sqrt(1f - similarity * similarity), 0f),
    ) = BookChunkEmbeddingEntity(
        bookId = "book",
        chapterIndex = chapterIndex,
        ordinal = ordinal,
        startOffset = startOffset,
        endOffset = endOffset,
        text = "chapter $chapterIndex chunk $ordinal",
        model = "test/1",
        dimensions = dimensions,
        vector = EmbeddingMath.toBytes(vector),
        indexedAt = 0L,
    )

    @Test
    fun `nothing to search returns nothing`() {
        assertTrue(BookSearchRanking.rank(FloatArray(0), listOf(row(1f))).isEmpty())
        assertTrue(BookSearchRanking.rank(query, emptyList()).isEmpty())
    }

    @Test
    fun `a book with nothing to say returns nothing rather than its best guess`() {
        val hits = BookSearchRanking.rank(query, listOf(row(0.10f), row(0.05f)))
        assertTrue("Noise was returned as a result: $hits", hits.isEmpty())
    }

    @Test
    fun `a vector of another width is skipped instead of compared`() {
        // The model column exists to catch exactly this. A four-wide vector
        // scored against a three-wide query is the silent wrongness that
        // survives a model change.
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(similarity = 1f, ordinal = 0, dimensions = 4, vector = FloatArray(4) { 1f }),
                row(similarity = 0.9f, ordinal = 1, startOffset = 500, endOffset = 900),
            ),
        )
        assertEquals(1, hits.size)
        assertEquals(1, hits.first().ordinal)
    }

    @Test
    fun `results come back strongest first`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(0.8f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(1.0f, ordinal = 1, startOffset = 500, endOffset = 900),
                row(0.9f, ordinal = 2, startOffset = 1000, endOffset = 1400),
            ),
        )
        assertEquals(listOf(1, 2, 0), hits.map { it.ordinal })
    }

    /**
     * The pair that shows the floor is relative and not absolute: the SAME
     * 0.5 hit is dropped beside a perfect match and kept beside a mediocre
     * one. A sharp query answers precisely; a vague one answers broadly.
     */
    @Test
    fun `a middling hit is dropped next to a strong one`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(1.0f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(0.9f, ordinal = 1, startOffset = 500, endOffset = 900),
                row(0.5f, ordinal = 2, startOffset = 1000, endOffset = 1400),
            ),
        )
        assertEquals(listOf(0, 1), hits.map { it.ordinal })
    }

    @Test
    fun `the same middling hit is kept when nothing beats it`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(0.6f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(0.5f, ordinal = 1, startOffset = 500, endOffset = 900),
            ),
        )
        assertEquals(listOf(0, 1), hits.map { it.ordinal })
    }

    @Test
    fun `overlapping chunks collapse to the strongest`() {
        // Chunks overlap by design, so a sentence near a boundary lives in
        // two of them and matches twice. Two results, one paragraph.
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(0.9f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(1.0f, ordinal = 1, startOffset = 320, endOffset = 700),
            ),
        )
        assertEquals(1, hits.size)
        assertEquals(1, hits.first().ordinal)
        assertEquals(1.0f, hits.first().similarity, 1e-4f)
    }

    @Test
    fun `chunks that merely touch are two results`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(1.0f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(0.9f, ordinal = 1, startOffset = 400, endOffset = 800),
            ),
        )
        assertEquals(listOf(0, 1), hits.map { it.ordinal })
    }

    @Test
    fun `the same offsets in different chapters are different passages`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(1.0f, chapterIndex = 0, ordinal = 0),
                row(0.9f, chapterIndex = 1, ordinal = 0),
            ),
        )
        assertEquals(listOf(0, 1), hits.map { it.chapterIndex })
    }

    /**
     * Dropping the middle of three does not drop the third: a chunk kept only
     * because the one bridging it to the winner was discarded is still a
     * different paragraph, and the reader should see it.
     */
    @Test
    fun `a passage past the discarded overlap still survives`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(1.00f, ordinal = 0, startOffset = 0, endOffset = 400),
                row(0.95f, ordinal = 1, startOffset = 320, endOffset = 700),
                row(0.90f, ordinal = 2, startOffset = 650, endOffset = 1000),
            ),
        )
        assertEquals(listOf(0, 2), hits.map { it.ordinal })
    }

    /**
     * A result the reader can open. An offset into a chapter is not a place
     * until you know how long the chapter is, and the chunks already loaded
     * for the search are the only thing that knows without re-reading the book.
     */
    @Test
    fun `a hit knows where in its chapter it sits`() {
        val hits = BookSearchRanking.rank(
            query,
            listOf(
                row(1.0f, ordinal = 0, startOffset = 500, endOffset = 900),
                row(0.9f, ordinal = 1, startOffset = 820, endOffset = 1200),
                // A different chapter's length must not leak into this one.
                row(0.95f, chapterIndex = 1, ordinal = 0, startOffset = 0, endOffset = 9000),
            ),
        )
        val first = hits.first { it.chapterIndex == 0 }
        assertEquals(1200, first.chapterChars)
        assertEquals(500f / 1200f, first.progression, 1e-4f)

        val other = hits.first { it.chapterIndex == 1 }
        assertEquals(9000, other.chapterChars)
        assertEquals(0f, other.progression, 1e-4f)
    }

    @Test
    fun `a passage with no chapter length is not sent to a place it cannot know`() {
        val hit = BookSearchHit(
            chapterIndex = 0,
            ordinal = 0,
            startOffset = 4_000,
            endOffset = 4_400,
            text = "",
            similarity = 1f,
        )
        assertEquals(0f, hit.progression, 0f)
    }

    @Test
    fun `no more results than asked for`() {
        val rows = (0 until 5).map {
            row(1.0f, ordinal = it, startOffset = it * 500, endOffset = it * 500 + 400)
        }
        assertEquals(2, BookSearchRanking.rank(query, rows, limit = 2).size)
    }

    @Test
    fun `a hit carries the text so a result can be shown without the book`() {
        val hits = BookSearchRanking.rank(query, listOf(row(1.0f, chapterIndex = 3, ordinal = 7)))
        assertEquals("chapter 3 chunk 7", hits.single().text)
        assertEquals(3, hits.single().chapterIndex)
        assertEquals(0, hits.single().startOffset)
        assertEquals(400, hits.single().endOffset)
    }
}
