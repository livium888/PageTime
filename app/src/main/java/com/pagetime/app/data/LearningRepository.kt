package com.pagetime.app.data

import androidx.room.withTransaction
import com.pagetime.app.data.local.AppDatabase
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.local.LearningReviewLogDao
import com.pagetime.app.data.local.LearningReviewLogEntity
import com.pagetime.app.data.local.LearningGenerationDao
import com.pagetime.app.data.local.LearningGenerationEntity
import com.pagetime.app.data.local.PendingReaderSource
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.learning.AiGenerationResult
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Review ratings exposed by the UI without leaking the Java library into Compose. */
enum class LearningRating(val value: Int, val label: String) {
    AGAIN(1, "Again"),
    HARD(2, "Hard"),
    GOOD(3, "Good"),
    EASY(4, "Easy");

    fun toFsrs(): Rating = when (this) {
        AGAIN -> Rating.AGAIN
        HARD -> Rating.HARD
        GOOD -> Rating.GOOD
        EASY -> Rating.EASY
    }

    companion object {
        fun fromValue(value: Int): LearningRating = entries.first { it.value == value }
    }
}

data class LearningStats(
    val totalCards: Int = 0,
    val dueCards: Int = 0,
    val successfulReviews: Int = 0
)

data class ReviewOutcome(
    val card: LearningCardEntity,
    val nextDue: Instant,
    val intervalDays: Long,
    val rating: LearningRating
)

class LearningRepository(
    private val database: AppDatabase,
    private val cardDao: LearningCardDao,
    private val reviewLogDao: LearningReviewLogDao,
    private val generationDao: LearningGenerationDao,
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
    private val geminiClient: GeminiLearningClient,
    private val contextExtractor: LearningContextExtractor,
    private val aiUsageRepository: AiUsageRepository? = null,
    private val scheduler: Scheduler = Scheduler.builder()
        .desiredRetention(0.9)
        .enableFuzzing(false)
        .build()
) {
    fun observeCards(): Flow<List<LearningCardEntity>> = cardDao.observeAll()

    fun observeCardsForBook(bookId: String): Flow<List<LearningCardEntity>> =
        cardDao.observeForBook(bookId)

    fun observeStats(now: () -> Instant = { Instant.now() }): Flow<LearningStats> = combine(
        cardDao.observeAll(),
        reviewLogDao.observeRecent(Int.MAX_VALUE)
    ) { cards, reviews ->
        val current = now()
        LearningStats(
            totalCards = cards.size,
            dueCards = cards.count { isDue(it, current) },
            successfulReviews = reviews.count { it.rating >= LearningRating.GOOD.value }
        )
    }

    suspend fun createCard(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String?,
        prompt: String,
        answer: String,
        explanation: String?,
        sourceLocator: String?,
        sourceFraction: Float?,
        now: Instant = Instant.now(),
        cardType: String = "qa",
        mcqOptions: List<String>? = null
    ): LearningCardEntity {
        require(prompt.isNotBlank()) { "A question is required" }
        require(answer.isNotBlank()) { "An answer is required" }
        require(bookDao.getById(bookId) != null) { "Book not found" }
        val card = Card.builder().due(now).build()
        return LearningCardEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            topic = null,
            prompt = prompt.trim(),
            answer = answer.trim(),
            explanation = explanation?.trim()?.takeIf { it.isNotEmpty() },
            sourceLocator = sourceLocator,
            sourceFraction = sourceFraction?.coerceIn(0f, 1f),
            sourceQuote = null,
            fsrsCardJson = FsrsCardCodec.toJson(card),
            createdAt = now.toEpochMilli(),
            updatedAt = now.toEpochMilli(),
            cardType = cardType,
            mcqOptions = mcqOptions?.let { serializeOptions(it) }
        ).also { cardDao.upsert(it) }
    }

    suspend fun deleteCard(cardId: String) = cardDao.deleteById(cardId)

    /**
     * Card generation is disabled. The app now uses the Explain Back
     * chat-based learning method instead of auto-generated MCQ cards.
     * Existing cards are still reviewed via FSRS, but no new ones are created.
     */
    suspend fun generateCardsForChapter(
        bookId: String,
        chapterIndex: Int,
        locatorJson: String?,
        textFraction: Float?,
        force: Boolean = false
    ): AiGenerationResult {
        return AiGenerationResult(emptyList(), contextChapterCount = 0, usedCharacters = 0)
    }

    suspend fun getCard(cardId: String): LearningCardEntity? = cardDao.getById(cardId)

    suspend fun getBookTitle(bookId: String): String? = bookDao.getById(bookId)?.title

    suspend fun prepareSource(card: LearningCardEntity) {
        settingsRepository.setPendingReaderSource(
            card.bookId,
            PendingReaderSource(card.sourceLocator, card.sourceFraction)
        )
    }

    suspend fun dueCards(now: Instant = Instant.now(), limit: Int = 20): List<LearningCardEntity> =
        cardDao.getAll()
            .filter { isDue(it, now) }
            .sortedBy { FsrsCardCodec.fromJson(it.fsrsCardJson).due }
            .take(limit.coerceAtLeast(1))

    suspend fun reviewCard(
        cardId: String,
        rating: LearningRating,
        now: Instant = Instant.now()
    ): ReviewOutcome {
        var outcome: ReviewOutcome? = null
        database.withTransaction {
            val existing = cardDao.getById(cardId) ?: error("Learning card not found")
            val oldCard = FsrsCardCodec.fromJson(existing.fsrsCardJson)
            val oldDue = oldCard.due
            val previousReview = oldCard.lastReview
            val result = scheduler.reviewCard(oldCard, rating.toFsrs(), now, null)
            val newCard = result.card()
            val nextDue = newCard.due ?: now.plus(Duration.ofDays(1))
            val persistedCard = if (newCard.due == null) {
                Card(newCard).apply { due = nextDue }
            } else {
                newCard
            }
            val intervalDays = Duration.between(now, nextDue).toDays().coerceAtLeast(0)
            val updated = existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(persistedCard),
                updatedAt = now.toEpochMilli(),
                lastRating = rating.value,
                reviewCount = existing.reviewCount + 1
            )
            cardDao.upsert(updated)
            reviewLogDao.insert(
                LearningReviewLogEntity(
                    cardId = existing.id,
                    bookId = existing.bookId,
                    reviewedAt = now.toEpochMilli(),
                    rating = rating.value,
                    scheduledDays = intervalDays,
                    elapsedDays = previousReview?.let { Duration.between(it, now).toDays() } ?: 0,
                    wasDue = oldDue == null || !oldDue.isAfter(now)
                )
            )
            outcome = ReviewOutcome(
                card = updated,
                nextDue = nextDue,
                intervalDays = intervalDays,
                rating = rating
            )
        }
        return requireNotNull(outcome)
    }

    suspend fun isDue(cardId: String, now: Instant = Instant.now()): Boolean =
        cardDao.getById(cardId)?.let { isDue(it, now) } ?: false

    private fun isDue(card: LearningCardEntity, now: Instant): Boolean =
        LearningPolicy.isDue(FsrsCardCodec.fromJson(card.fsrsCardJson).due, now)

    private fun serializeOptions(options: List<String>): String =
        JSONArray(options).toString()

}
