package com.pagetime.app.data.embed

import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Where a book's index has got to. */
data class IndexProgress(val chaptersDone: Int, val chaptersTotal: Int) {
    val complete: Boolean get() = chaptersTotal > 0 && chaptersDone >= chaptersTotal
}

/**
 * Turns a book into vectors, one chapter at a time.
 *
 * ONE CHAPTER AT A TIME, WHICH IS THE WHOLE DESIGN
 *
 * A novel does not want to be a single string in memory, indexing it takes
 * minutes, and a phone will interrupt those minutes — a call, a swipe away,
 * the process being reclaimed. So each chapter is read, chunked, embedded and
 * WRITTEN before the next one starts. An interruption costs the chapter in
 * flight and nothing else, and the next run resumes from where the rows stop
 * rather than from the beginning.
 *
 * That is also why progress is counted in chapters. A count that only moved
 * when a whole book finished would sit still for three minutes and look
 * broken.
 *
 * ONE SESSION FOR THE BOOK
 *
 * Opening an ONNX session maps the model and allocates native arenas — tens of
 * milliseconds, which has no business being paid once per chapter across
 * forty. It is opened once and closed in a finally, because a leaked native
 * handle per indexing run eventually takes the app down with an error naming
 * none of this.
 */
class BookIndexer(
    private val dao: BookChunkEmbeddingDao,
    private val store: EmbeddingModelStore,
    private val chapterCount: suspend (BookEntity) -> Int,
    private val chapterText: suspend (BookEntity, Int) -> String,
) {

    fun modelId(): String? = store.modelId()

    /** How far this book's index has got, or null when no model is installed. */
    suspend fun progressFor(book: BookEntity): IndexProgress? {
        val model = modelId() ?: return null
        val total = chapterCount(book)
        val last = dao.lastIndexedChapter(book.id, model)
        return IndexProgress(chaptersDone = last?.plus(1) ?: 0, chaptersTotal = total)
    }

    /**
     * Indexes [book] from wherever it left off.
     *
     * Returns the progress reached. Stops early and returns what it managed
     * when the model is missing or the text cannot be read — an index is a
     * convenience, and failing to build one must never be an error the reader
     * has to deal with while trying to read.
     *
     * Cancellation is honoured between chapters and propagates, so navigating
     * away stops the work rather than leaving it running.
     */
    suspend fun index(
        book: BookEntity,
        onProgress: (IndexProgress) -> Unit = {},
    ): IndexProgress {
        val model = modelId() ?: return IndexProgress(0, 0)
        val tokenizer = store.tokenizer() ?: return IndexProgress(0, 0)
        val total = chapterCount(book)
        if (total <= 0) return IndexProgress(0, 0)

        val startAt = (dao.lastIndexedChapter(book.id, model)?.plus(1)) ?: 0
        if (startAt >= total) return IndexProgress(total, total)

        var done = startAt
        var embedder: OnnxTextEmbedder? = null
        try {
            val runner = OnnxTextEmbedder(store.modelFile, tokenizer)
            embedder = runner
            val maxChars = BookTextChunker.maxCharsFor(EMBED_TOKENS)

            for (chapter in startAt until total) {
                currentCoroutineContext().ensureActive()

                val text = chapterText(book, chapter)
                val chunks = BookTextChunker.chunk(text, maxChars)
                val now = System.currentTimeMillis()

                val rows = chunks.mapIndexedNotNull { ordinal, piece ->
                    // Per chunk, not per chapter: one paragraph the model
                    // dislikes should cost its own vector, not the other
                    // hundred in the chapter.
                    val vector = runCatching { runner.embed(piece.text) }.getOrNull()
                        ?: return@mapIndexedNotNull null
                    BookChunkEmbeddingEntity(
                        bookId = book.id,
                        chapterIndex = chapter,
                        ordinal = ordinal,
                        startOffset = piece.start,
                        endOffset = piece.end,
                        text = piece.text,
                        model = model,
                        dimensions = vector.size,
                        vector = EmbeddingMath.toBytes(vector),
                        indexedAt = now,
                    )
                }

                // Written before moving on, so an interruption costs this
                // chapter and nothing before it. A chapter with no text still
                // counts as done, or the index would stall on a title page
                // forever.
                if (rows.isNotEmpty()) dao.insertAll(rows)
                done = chapter + 1
                onProgress(IndexProgress(done, total))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unreadable book, a runtime that would not start, a full disk.
            // Reported by the progress returned rather than by an exception.
        } finally {
            runCatching { embedder?.close() }
        }
        return IndexProgress(done, total)
    }

    suspend fun delete(book: BookEntity) = dao.deleteForBook(book.id)

    private companion object {
        /**
         * Must match [OnnxTextEmbedder]'s default. The chunker sizes its pieces
         * from this, and a chunk built for a larger budget than the embedder
         * actually uses is silently truncated — the failure this whole path is
         * arranged to prevent.
         */
        const val EMBED_TOKENS = 128
    }
}
