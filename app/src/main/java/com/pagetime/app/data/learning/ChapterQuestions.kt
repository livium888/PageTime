package com.pagetime.app.data.learning

import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import java.time.Instant

/**
 * Questions an earlier version wrote for a chapter and left waiting for the
 * reader to judge.
 *
 * Nothing creates new ones: cards now come from "Make cards from this
 * stretch", where the reader's own AI app writes them. This only lists what is
 * still pending and records the reader's verdict on each.
 */
class ChapterQuestions(
    private val cardDao: LearningCardDao,
) {
    /** Questions offered for this chapter and not yet judged. */
    suspend fun pending(book: BookEntity, chapterIndex: Int): List<LearningCardEntity> =
        runCatching { cardDao.pendingForChapter(book.id, chapterIndex) }.getOrDefault(emptyList())

    /**
     * The reader keeps a question.
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
     * The reader rejects a question.
     *
     * Kept as a skipped row rather than deleted, so the same rejected question
     * is not offered back.
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
}
