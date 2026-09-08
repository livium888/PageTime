package com.pagetime.app.data.learning

import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.BookChunkEmbeddingEntity
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ChapterPassageDao
import com.pagetime.app.data.local.ChapterPassageEntity
import com.pagetime.app.data.local.PassageOutcome
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
    private fun chunks(count: Int, ordinalBase: Int = 0): List<BookChunkEmbeddingEntity> =
        (0 until count).map { i ->
            val v = FloatArray(3)
            v[i % 3] = 1f
            BookChunkEmbeddingEntity(
                bookId = "b",
                chapterIndex = 0,
                ordinal = ordinalBase + i,
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
        /** Whether the reader hand-picked the passages in each request. */
        val insisted = mutableListOf<Boolean>()
        override fun hasKey() = true
        override fun currentModel() = "gemini-2.5-flash"
        override suspend fun generateChapterPrompts(
            bookTitle: String,
            chapterTitle: String,
            passages: List<String>,
            insist: Boolean,
        ): List<RawPrompt> {
            sentPassages += passages
            insisted += insist
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

    private fun generator(
        rows: List<BookChunkEmbeddingEntity>,
        writer: Writer,
        passages: Passages = Passages(),
    ) = ChapterPromptGenerator(Chunks(rows), Cards(), { model }, writer, null, passages)

    /** In-memory record of what became of each passage. */
    private class Passages : ChapterPassageDao {
        val rows = mutableListOf<ChapterPassageEntity>()
        override suspend fun upsertAll(passages: List<ChapterPassageEntity>) {
            passages.forEach { p ->
                rows.removeAll { it.ordinal == p.ordinal }
                rows += p
            }
        }
        override suspend fun deleteForChapter(bookId: String, chapterIndex: Int) {
            rows.removeAll { it.bookId == bookId && it.chapterIndex == chapterIndex }
        }
        override suspend fun forChapter(bookId: String, chapterIndex: Int) =
            rows.sortedBy { it.startOffset }
        override fun observeForChapter(
            bookId: String,
            chapterIndex: Int,
        ): Flow<List<ChapterPassageEntity>> = flowOf(rows)
        override suspend fun deleteForBook(bookId: String) = rows.clear()
    }

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

    // What became of each passage
    // ===========================
    //
    // Reported from the device: 24 passages sent, 2 cards back, and no way to
    // find out why. The counts existed; the reasons were thrown away at the
    // moment they were known.

    @Test
    fun `a passage the model ignored is recorded as skipped, not rejected`() {
        // The distinction that matters. A rejection is the passage's fault or
        // the model's; being ignored entirely is usually the instructions'.
        val passages = Passages()
        val writer = Writer(mutableListOf(Result.success(listOf(promptFor(0, 0)))))
        runBlocking { generator(chunks(4), writer, passages).generate(book(), 0, "One") }

        val used = passages.rows.filter { it.outcome == PassageOutcome.USED.name }
        val skipped = passages.rows.filter { it.outcome == PassageOutcome.MODEL_SKIPPED.name }
        assertEquals(1, used.size)
        assertEquals(3, skipped.size)
        assertEquals(1, used.single().cardsMade)
    }

    @Test
    fun `a rejected passage keeps the rule that refused it`() {
        val passages = Passages()
        // A quote that is nowhere in the passage: the check the whole pipeline
        // rests on, and the likeliest reason a chapter comes back thin.
        val invented = promptFor(0, 0).copy(sourceQuote = "a sentence from another book entirely")
        val writer = Writer(mutableListOf(Result.success(listOf(invented))))
        val result = runBlocking {
            generator(chunks(4), writer, passages).generate(book(), 0, "One")
        }

        val row = passages.rows.first { it.ordinal == 0 }
        assertEquals(PassageOutcome.REJECTED.name, row.outcome)
        assertEquals(PromptRejection.QUOTE_NOT_IN_PASSAGE.reason, row.detail)
        // And the summary can now name the rule rather than just count it.
        assertEquals(
            PromptRejection.QUOTE_NOT_IN_PASSAGE,
            result.topRejections.first().first,
        )
    }

    @Test
    fun `passages in a failed request are not blamed on the model`() {
        val passages = Passages()
        val writer = Writer(
            mutableListOf(
                Result.success(listOf(promptFor(0, 0))),
                Result.failure(IllegalStateException("HTTP 503")),
            )
        )
        runBlocking { generator(chunks(16), writer, passages).generate(book(), 0, "One") }

        val unreached = passages.rows.filter { it.outcome == PassageOutcome.REQUEST_FAILED.name }
        // The second batch of eight never arrived; saying the model declined
        // them would send the reader looking for a problem in their book.
        assertEquals(8, unreached.size)
    }

    @Test
    fun `asking for chosen passages sends only those, and insists`() {
        val passages = Passages()
        val writer = Writer(mutableListOf(Result.success(listOf(promptFor(0, 4)))))
        runBlocking {
            generator(chunks(16), writer, passages)
                .generateForPassages(book(), 0, "One", setOf(4, 9))
        }
        assertEquals(1, writer.sentPassages.size)
        assertEquals(2, writer.sentPassages.single().size)
        // The floor, not the ceiling: a person picked these.
        assertEquals(listOf(true), writer.insisted)
    }

    @Test
    fun `a normal generation does not insist`() {
        val writer = Writer()
        runBlocking { generator(chunks(4), writer).generate(book(), 0, "One") }
        assertEquals(listOf(false), writer.insisted)
    }

    @Test
    fun `asking again for chosen passages is never blocked as already generated`() {
        // The shortcut that stops an accidental second tap must not stop a
        // deliberate "no, I want a card for this one".
        val passages = Passages()
        val writer = Writer(
            mutableListOf(
                Result.success(listOf(promptFor(0, 4))),
                Result.success(listOf(promptFor(0, 4).copy(prompt = "How heavy is widget 4?"))),
            )
        )
        val generator = generator(chunks(16), writer, passages)
        runBlocking {
            generator.generateForPassages(book(), 0, "One", setOf(4))
            val second = generator.generateForPassages(book(), 0, "One", setOf(4))
            assertEquals(ChapterPromptGenerator.Outcome.MADE, second.outcome)
        }
        assertEquals(2, writer.sentPassages.size)
    }

    @Test
    fun `regenerating replaces the record instead of stacking on it`() {
        // Reported from the device: a sheet claiming 45 passages for a chapter
        // that can send at most 24, with duplicate rows and stale mid-sentence
        // text among them.
        //
        // Upserting by ordinal is not enough. An ordinal identifies a chunk
        // within ONE index, and re-indexing renumbers them, so the previous
        // index's rows sat beside the new ones and the reader saw both
        // generations at once.
        val passages = Passages()

        // First generation, over one index.
        runBlocking {
            generator(chunks(8), Writer(), passages).generate(book(), 0, "One")
        }
        val first = passages.rows.size
        assertTrue("expected a first record", first > 0)

        // Re-indexed: same chapter, different chunk boundaries and ordinals.
        // The same chapter after re-indexing: identical text, renumbered
        // chunks, which is exactly what a chunker change produces.
        val renumbered = chunks(8, ordinalBase = 100)
        runBlocking {
            generator(renumbered, Writer(), passages).generate(book(), 0, "One")
        }

        assertEquals(
            "stale rows survived: ${passages.rows.size} for a chapter of $first",
            first,
            passages.rows.size,
        )
        assertTrue(
            "the record should describe the current index",
            passages.rows.all { it.ordinal >= 100 },
        )
    }

    @Test
    fun `a targeted re-ask leaves the rest of the record alone`() {
        // The other direction. Asking again for six passages must not erase
        // what is known about the other eighteen.
        val passages = Passages()
        runBlocking {
            val g = generator(chunks(16), Writer(), passages)
            g.generate(book(), 0, "One")
            val before = passages.rows.size
            g.generateForPassages(book(), 0, "One", setOf(passages.rows.first().ordinal))
            assertEquals(before, passages.rows.size)
        }
    }
}
