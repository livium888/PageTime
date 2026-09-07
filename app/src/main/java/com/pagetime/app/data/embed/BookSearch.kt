package com.pagetime.app.data.embed

import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import com.pagetime.app.data.local.BookEntity

/** One passage a search found, and where in the book it sits. */
data class BookSearchHit(
    val chapterIndex: Int,
    val ordinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val similarity: Float,
    /**
     * Length of the chapter this passage came from, in characters.
     *
     * Carried on the hit because it is the difference between a result the
     * reader can open and a result they can only read: a reader navigates by
     * position within a chapter, and an offset means nothing without the
     * length it is an offset into. It costs nothing to compute — the ranking
     * already holds every chunk of the chapter — and it is not knowable later
     * without loading the book.
     */
    val chapterChars: Int = 0,
) {
    /** Where in its chapter this passage begins, as a fraction, for jumping to it. */
    val progression: Float
        get() = if (chapterChars > 0) (startOffset.toFloat() / chapterChars).coerceIn(0f, 1f) else 0f
}

/**
 * Ranking search results, kept apart from the native runtime so it can be
 * tested.
 *
 * Two things here are easy to get wrong and neither throws: returning the same
 * passage twice, and returning twenty paragraphs about nothing.
 */
object BookSearchRanking {

    /**
     * Below this a hit is noise rather than a weak answer.
     *
     * A nearest-neighbour search always has a nearest neighbour. Searching a
     * novel for a subject it never mentions would otherwise return its twenty
     * least-unrelated paragraphs, which teaches the reader that search does not
     * work — where "nothing in this book" is both true and useful.
     *
     * A first estimate, and the honest thing to say about it: the number that
     * belongs here comes from watching real searches over real books, not from
     * reasoning. It is deliberately low, so the failure is a weak result rather
     * than a wrongly empty one.
     */
    const val MIN_SIMILARITY = 0.25f

    /**
     * Hits are also dropped once they fall this far below the best one.
     *
     * Absolute thresholds are a property of the model and travel badly; a
     * fraction of the best result for THIS query does not. A sharp match makes
     * the bar high and returns a few precise answers; a vague query makes it
     * low and returns more.
     */
    const val RELATIVE_FLOOR = 0.65f

    /**
     * Best passages first, without the same passage twice.
     *
     * Chunks deliberately overlap, so a sentence near a boundary lives in two
     * of them and a query matching that sentence matches both. Shown as two
     * results they look like two findings; they are one, and the reader
     * discovers this by tapping both and arriving at the same paragraph.
     * Overlapping hits collapse to the strongest.
     */
    fun rank(
        query: FloatArray,
        rows: List<BookChunkEmbeddingEntity>,
        limit: Int = 20,
        minimum: Float = MIN_SIMILARITY,
        relativeFloor: Float = RELATIVE_FLOOR,
    ): List<BookSearchHit> {
        if (query.isEmpty() || rows.isEmpty()) return emptyList()

        // The last chunk of a chapter ends where the chapter ends, so the
        // chunks already in memory know each chapter's length and nothing
        // needs to re-read the book to find out.
        val chapterChars = HashMap<Int, Int>()
        for (row in rows) {
            val known = chapterChars[row.chapterIndex] ?: 0
            if (row.endOffset > known) chapterChars[row.chapterIndex] = row.endOffset
        }

        val scored = rows.mapNotNull { row ->
            // A vector of another width cannot be compared, and comparing it
            // anyway is the silent wrongness the model column exists to stop.
            if (row.dimensions != query.size) return@mapNotNull null
            val similarity =
                EmbeddingMath.cosineSimilarity(query, EmbeddingMath.fromBytes(row.vector))
            if (similarity < minimum) null
            else BookSearchHit(
                chapterIndex = row.chapterIndex,
                ordinal = row.ordinal,
                startOffset = row.startOffset,
                endOffset = row.endOffset,
                text = row.text,
                similarity = similarity,
                chapterChars = chapterChars[row.chapterIndex] ?: 0,
            )
        }.sortedByDescending { it.similarity }

        if (scored.isEmpty()) return emptyList()

        val floor = scored.first().similarity * relativeFloor
        val kept = mutableListOf<BookSearchHit>()
        for (hit in scored) {
            if (hit.similarity < floor) break
            if (kept.any { it.overlaps(hit) }) continue
            kept += hit
            if (kept.size >= limit) break
        }
        return kept
    }

    /** Same chapter, and character ranges that touch. */
    private fun BookSearchHit.overlaps(other: BookSearchHit): Boolean =
        chapterIndex == other.chapterIndex &&
            startOffset < other.endOffset &&
            other.startOffset < endOffset
}

/**
 * Finding a passage in a book by what it means.
 *
 * ONE BOOK AT A TIME, FOR NOW
 *
 * A book's vectors are loaded whole and compared in memory: a few thousand
 * rows, a cosine over 384 floats each, well under a second, where querying
 * SQLite per comparison would pay its cost thousands of times.
 *
 * That is also the reason this searches one book rather than the shelf. A
 * 300-page book is roughly 5 MB of vectors and text; ten of them at once is
 * not something to hold in memory on a phone. Searching everything is worth
 * building and needs paging, which is a different piece of work.
 */
class BookSearcher(
    private val dao: BookChunkEmbeddingDao,
    private val store: EmbeddingModelStore,
) {

    /** Whether this book can be searched by meaning yet. */
    suspend fun isSearchable(book: BookEntity): Boolean {
        val model = store.modelId() ?: return false
        return dao.countForBook(book.id, model) > 0
    }

    /**
     * Passages in [book] closest in meaning to [query], best first.
     *
     * Returns nothing rather than throwing when the model is missing, the book
     * is not indexed, or the query will not embed. Search failing is a search
     * with no results, not an error thrown at someone who is reading.
     */
    suspend fun search(book: BookEntity, query: String, limit: Int = 20): List<BookSearchHit> {
        val text = query.trim()
        if (text.isBlank()) return emptyList()
        val model = store.modelId() ?: return emptyList()
        val tokenizer = store.tokenizer() ?: return emptyList()

        val rows = dao.forBook(book.id, model)
        if (rows.isEmpty()) return emptyList()

        var embedder: OnnxTextEmbedder? = null
        val vector = try {
            val runner = OnnxTextEmbedder(store.modelFile, tokenizer)
            embedder = runner
            runner.embed(text)
        } catch (_: Exception) {
            return emptyList()
        } finally {
            runCatching { embedder?.close() }
        }

        return BookSearchRanking.rank(vector, rows, limit)
    }
}
