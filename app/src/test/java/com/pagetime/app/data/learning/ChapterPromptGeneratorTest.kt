package com.pagetime.app.data.learning

import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Generating a chapter, now that a chapter is several requests.
 *
 * WHAT IS ACTUALLY WORTH TESTING HERE
 *
 * Not the happy path. The failures that matter are the ones a reader would
 * find first: a chapter losing everything because one request was rate
 * limited, and a question surfacing in the wrong place because passage
 * numbering restarts inside every batch.
 *
 * None of this was reachable before — the generator held a concrete Gemini
 * client with OkHttp inside it, and an embedding store that needs a download
 * directory. Both are now seams.
 */
class ChapterPromptGeneratorTest {

    private val model = "test-model"

    /**
     * Distinct passages, each carrying a sentence a prompt can quote.
     *
     * Spaced 3,000 characters apart deliberately: how many passages a chapter
     * yields is a function of its LENGTH, so a fixture of tightly packed
     * chunks selects only a handful and never reaches a second batch.
     */
    private fun chunks(count: Int): List<BookChunkEmbeddingEntity> =
        (0 until count).map { i ->
            val v = FloatArray(3)
            v[i % 3] = 1f
            BookChunkEmbeddingEntity(
                bookId = "b",
                chapterIndex = 0,
                ordinal = i,
                startOffset = i * 3000,
                endOffset = i * 3000 + 2900,
                text = "Passage number $i states that widget $i weighs $i kilograms exactly.",
                model = model,
                dimensions = 3,
                vector = bytes(v),
                indexedAt = 0L,
            )
        }

    private fun bytes(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * 4)
        v.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun book() = BookEntity(
        id = "b",
        title = "A Book",
        author = "Someone",
        format = "epub",
        localPath = "/tmp/b.epub",
        coverUrl = null,
        addedAt = 0L,
    )

    /**
     * Scripted stand-in for the model.
     *
     * Each call pops the next scripted response — a list of prompts, or a
     * throwable. That is what makes "batch two fails, one and three survive"
     * expressible at all.
     */
    private class Writer(
        private val script: MutableList<Result<List<RawPrompt>>> = mutableListOf(),
    ) : ChapterPromptWriter {
        val sentPassages = mutableListOf<List<String>>()
        override fun hasKey() = true
        override fun currentModel() = "gemini-2.5-flash"
        override suspend fun generateChapterPrompts(
            bookTitle: String,
            chapterTitle: String,
            passages: List<String>,
        ): List<RawPrompt> {
            sentPassages += passages
            return (script.removeFirstOrNull() ?: Result.success(emptyList())).getOrThrow()
        }
    }

    private class Chunks(private val rows: List<BookChunkEmbeddingEntity>) : BookChunkEmbeddingDao {
        override suspend fun forChapter(bookId: String, model: String, chapterIndex: Int) = rows
        override suspend fun forBook(bookId: String, model: String) = rows
        override suspend fun countForBook(bookId: String, model: String) = rows.size
        override suspend fun lastIndexedChapter(bookId: String, model: String): Int? = 0
        override suspend fun indexedBookIds(model: String) = listOf("b")
        override suspend fun insertAll(chunks: List<BookChunkEmbeddingEntity>) = Unit
        override suspend fun deleteForBook(bookId: String) = Unit
        override suspend fun deleteFromOtherModels(model: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun countAll() = rows.size
    }

    private class Cards : LearningCardDao {
        val rows = mutableListOf<LearningCardEntity>()
        override suspend fun pendingForChapter(bookId: String, chapterIndex: Int) =
            rows.filter {
                it.bookId == bookId && it.chapterIndex == chapterIndex &&
                    it.status == LearningCardEntity.STATUS_PENDING
            }
        override suspend fun countForGeneration(bookId: String, generationKey: String) =
            rows.count { it.bookId == bookId && it.generationKey == generationKey }
        override suspend fun dueCards(now: Long, limit: Int) = emptyList<LearningCardEntity>()
        override fun observeDueCount(now: Long): Flow<Int> = flowOf(0)
        override suspend fun get(id: String) = rows.firstOrNull { it.id == id }
        override fun observeKeptForBook(bookId: String): Flow<List<LearningCardEntity>> =
            flowOf(emptyList())
        override fun observeLive(): Flow<List<LearningCardEntity>> = flowOf(rows.toList())
        override suspend fun insertAll(cards: List<LearningCardEntity>) { rows += cards }
        override suspend fun upsert(card: LearningCardEntity) {
            rows.removeAll { it.id == card.id }
            rows += card
        }
        override suspend fun setStatus(id: String, status: String, updatedAt: Long) = Unit
        override suspend fun delete(id: String) { rows.removeAll { it.id == id } }
        override suspend fun deleteUnkeptForChapter(bookId: String, chapterIndex: Int) = Unit
        override suspend fun deleteForBook(bookId: String) = Unit
    }

    /** A prompt quoting the passage that this batch numbers [batchLocal]. */
    private fun promptFor(batchLocal: Int, globalPassage: Int) = RawPrompt(
        passageIndex = batchLocal,
        prompt = "How much does widget $globalPassage weigh?",
        answer = "$globalPassage kilograms",
        sourceQuote = "widget $globalPassage weighs $globalPassage kilograms exactly",
        type = RawPrompt.TYPE_QA,
    )

    private fun generator(rows: List<BookChunkEmbeddingEntity>, writer: Writer) =
        ChapterPromptGenerator(Chunks(rows), Cards(), { model }, writer, null)

    @Test
    fun `a long chapter is split into several requests`() {
        val writer = Writer()
        runBlocking { generator(chunks(20), writer).generate(book(), 0, "One") }
        // Twenty passages at eight per request: three calls, not one.
        assertEquals(3, writer.sentPassages.size)
        assertTrue(writer.sentPassages.all { it.size <= 8 })
    }

    @Test
    fun `one failed request does not lose the other batches`() {
        // The reason batching earns its extra tokens. Before this, a single
        // refused request threw away everything the reader had paid for.
        val writer = Writer(
            mutableListOf(
                Result.success(listOf(promptFor(0, 0))),
                Result.failure(IllegalStateException("HTTP 429 rate limited")),
                Result.success(listOf(promptFor(0, 16))),
            )
        )
        val result = runBlocking { generator(chunks(20), writer).generate(book(), 0, "One") }

        assertEquals(ChapterPromptGenerator.Outcome.MADE, result.outcome)
        assertEquals(2, result.cards.size)
        assertEquals(3, result.batches)
        assertEquals(1, result.failedBatches)
        assertTrue("the reason must survive: ${result.detail}", result.detail!!.contains("429"))
    }

    @Test
    fun `every request failing is a failure, not an empty chapter`() {
        val writer = Writer(
            MutableList(3) { Result.failure(IllegalStateException("no network")) }
        )
        val result = runBlocking { generator(chunks(20), writer).generate(book(), 0, "One") }
        assertEquals(ChapterPromptGenerator.Outcome.REQUEST_FAILED, result.outcome)
        assertEquals(3, result.failedBatches)
    }

    @Test
    fun `a prompt is filed against its own passage, not a batch-local one`() {
        // Passage numbering restarts at zero in every batch. Reading the second
        // batch's index 0 as the chapter's passage 0 would surface every
        // question after the first batch at the top of the chapter.
        //
        // Verified by reintroducing that bug: this is the test that fails.
        val writer = Writer(
            mutableListOf(
                Result.success(emptyList()),
                Result.success(listOf(promptFor(0, 8))),
            )
        )
        val result = runBlocking { generator(chunks(16), writer).generate(book(), 0, "One") }
        val card = result.cards.single()
        // Passage 8 of 16 starts at 24,000 of a ~47,900-character chapter, so
        // its end sits past halfway.
        assertTrue("filed at ${card.sourceFraction}", card.sourceFraction!! > 0.4f)
    }

    @Test
    fun `duplicates are caught across batch boundaries`() {
        val writer = Writer(
            mutableListOf(
                Result.success(listOf(promptFor(0, 0))),
                Result.success(listOf(promptFor(0, 0))),
            )
        )
        val result = runBlocking { generator(chunks(16), writer).generate(book(), 0, "One") }
        assertEquals(1, result.cards.size)
        assertEquals(2, result.offered)
        assertEquals(1, result.rejected)
    }
}
