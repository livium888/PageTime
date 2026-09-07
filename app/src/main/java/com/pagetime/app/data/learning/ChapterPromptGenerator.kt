package com.pagetime.app.data.learning

import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.embed.ChapterTopics
import com.pagetime.app.data.embed.EmbeddingModelStore
import com.pagetime.app.data.embed.TopicPassage
import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import io.github.openspacedrepetition.Card
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Turning a chapter into flashcards the reader is offered while reading.
 *
 * THE WHOLE PIPELINE, IN ORDER
 *
 * The chapter is already chunked and embedded by the search index. Those
 * vectors choose a handful of distinct, on-topic passages. Only those passages
 * go to the model — a few hundred tokens rather than a whole chapter, which is
 * both much cheaper and much better grounded. What comes back is checked
 * locally, and what survives is written down as PENDING.
 *
 * NOTHING IS A CARD UNTIL A PERSON SAYS SO
 *
 * A generated prompt is stored before it is approved, because generating a
 * chapter costs an API call and losing the batch when the reader closes the
 * book would mean paying for it twice. But a pending row is never scheduled
 * and never appears in a review session — that is enforced in the DAO's SQL,
 * not here.
 *
 * WHY A CHAPTER IS ONLY EVER GENERATED ONCE
 *
 * generationKey is a hash of the passages actually sent. Reopening a chapter
 * costs nothing and returns the same prompts; re-indexing the book with a
 * different model changes the passages and so changes the key, which is the
 * correct time to generate again.
 *
 * EVERY FAILURE IS AN EMPTY LIST
 *
 * No key, no index, a refused request, a chapter with nothing in it: all of
 * them return no prompts. Someone in the middle of a book should never be
 * handed an error about a feature they did not ask for.
 */
class ChapterPromptGenerator(
    private val chunkDao: BookChunkEmbeddingDao,
    private val cardDao: LearningCardDao,
    private val store: EmbeddingModelStore,
    private val gemini: GeminiLearningClient,
) {

    /** Whether this chapter could produce prompts at all. */
    suspend fun isReady(book: BookEntity, chapterIndex: Int): Boolean {
        if (!gemini.hasKey()) return false
        val model = store.modelId() ?: return false
        return chunkDao.forChapter(book.id, model, chapterIndex).isNotEmpty()
    }

    /** Prompts already offered for this chapter and not yet judged. */
    suspend fun pending(book: BookEntity, chapterIndex: Int): List<LearningCardEntity> =
        runCatching { cardDao.pendingForChapter(book.id, chapterIndex) }.getOrDefault(emptyList())

    /**
     * Generates this chapter's prompts, or returns the ones it already has.
     *
     * [onStage] reports what is happening so the reader is not left looking at
     * a spinner during a network call they are paying for.
     */
    suspend fun generate(
        book: BookEntity,
        chapterIndex: Int,
        chapterTitle: String?,
        onStage: (Stage) -> Unit = {},
    ): List<LearningCardEntity> {
        val model = store.modelId() ?: return emptyList()
        if (!gemini.hasKey()) return emptyList()

        val rows = runCatching { chunkDao.forChapter(book.id, model, chapterIndex) }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()

        onStage(Stage.CHOOSING)
        val topics = ChapterTopics.select(rows)
        if (topics.isEmpty()) return emptyList()

        val key = generationKey(model, topics)
        // Already generated. Whatever the reader did with them — kept, skipped,
        // or not yet judged — this chapter is not paid for twice.
        if (runCatching { cardDao.countForGeneration(book.id, key) }.getOrDefault(0) > 0) {
            return pending(book, chapterIndex)
        }

        onStage(Stage.WRITING)
        val raws = runCatching {
            gemini.generateChapterPrompts(
                bookTitle = book.title,
                chapterTitle = chapterTitle ?: "Chapter ${chapterIndex + 1}",
                passages = topics.map { it.text },
            )
        }.getOrDefault(emptyList())
        if (raws.isEmpty()) return emptyList()

        onStage(Stage.CHECKING)
        val verdict = ChapterPromptRules.sift(raws, topics.map { it.text })
        if (verdict.accepted.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val fresh = FsrsCardCodec.toJson(Card.builder().build())
        val cards = verdict.accepted.mapNotNull { raw ->
            val topic = topics.getOrNull(raw.passageIndex) ?: return@mapNotNull null
            LearningCardEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                prompt = raw.prompt.trim(),
                answer = raw.answer.trim(),
                explanation = null,
                sourceLocator = null,
                // Where in the chapter this idea lives, which is where the
                // prompt will meet the reader.
                sourceFraction = topic.progression,
                sourceQuote = raw.sourceQuote.trim(),
                fsrsCardJson = fresh,
                createdAt = now,
                updatedAt = now,
                generatedByAi = true,
                generationKey = key,
                status = LearningCardEntity.STATUS_PENDING,
                dueAt = null,
            )
        }
        if (cards.isEmpty()) return emptyList()

        runCatching { cardDao.insertAll(cards) }
        return cards
    }

    /**
     * The reader accepts a prompt: it becomes a card and is due now.
     *
     * Due immediately rather than tomorrow, deliberately. The first retrieval
     * is the one that decides whether the memory survives at all, and it is
     * cheapest while the passage is still on screen.
     */
    suspend fun keep(cardId: String, now: Instant = Instant.now()) {
        val existing = cardDao.get(cardId) ?: return
        cardDao.upsert(
            existing.copy(
                status = LearningCardEntity.STATUS_KEPT,
                dueAt = now.toEpochMilli(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * The reader rejects a prompt.
     *
     * Kept as a skipped row rather than deleted, so the chapter is not
     * regenerated and the same rejected prompt offered back.
     */
    suspend fun skip(cardId: String) {
        runCatching {
            cardDao.setStatus(
                cardId,
                LearningCardEntity.STATUS_SKIPPED,
                System.currentTimeMillis(),
            )
        }
    }

    /** Throws away a chapter's generated prompts so it can be generated again. */
    suspend fun regenerate(book: BookEntity, chapterIndex: Int) {
        runCatching { cardDao.deleteGeneratedForChapter(book.id, chapterIndex) }
    }

    enum class Stage { CHOOSING, WRITING, CHECKING }

    private companion object {
        /**
         * Identity of one generation: the model that produced the vectors, plus
         * the passages actually sent.
         *
         * Hashing the passages rather than the chapter text means re-indexing
         * with a different embedding model — which would choose different
         * passages — correctly counts as a different generation, while merely
         * reopening the chapter does not.
         */
        fun generationKey(model: String, topics: List<TopicPassage>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(model.toByteArray())
            for (topic in topics) {
                digest.update(topic.text.toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
