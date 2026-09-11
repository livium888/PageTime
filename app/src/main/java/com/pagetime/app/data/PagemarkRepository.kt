package com.pagetime.app.data

import com.pagetime.app.data.local.PagemarkDao
import com.pagetime.app.data.local.PagemarkEntity
import com.pagetime.app.data.local.PendingReaderSource
import com.pagetime.app.data.local.SettingsRepository
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Incremental reading: chunks of a book, their priorities, and their FSRS
 * re-read schedules.
 *
 * The scheduler is the same one learning cards and Lumen training use
 * (desired retention 0.9, no fuzzing), so a chunk closed with the same
 * rating as a flashcard comes back on the same calendar. A chunk is not a
 * different kind of memory; it is a bigger card.
 */
class PagemarkRepository(
    private val dao: PagemarkDao,
    private val settingsRepository: SettingsRepository,
    private val scheduler: Scheduler = Scheduler.builder()
        .desiredRetention(0.9)
        .enableFuzzing(false)
        .build()
) {

    fun observeForBook(bookId: String): Flow<List<PagemarkEntity>> = dao.observeForBook(bookId)

    /** Every chunk, for the reading queue screen. */
    fun observeAll(): Flow<List<PagemarkEntity>> = dao.observeAll()

    fun observeDueCount(now: () -> Long = { System.currentTimeMillis() }): Flow<Int> =
        dao.observeDueCount(now())

    suspend fun get(id: String): PagemarkEntity? = dao.get(id)

    /**
     * Due chunks for a review sitting, by how overdue they are.
     *
     * [nowMillis] should be the sitting's threshold (cards use the sixteen-
     * hour lookahead), so a chunk due this evening is offered while the
     * reader is here rather than demanding a second trip.
     */
    suspend fun dueChunks(nowMillis: Long): List<PagemarkEntity> = dao.dueChunks(nowMillis)

    /**
     * Starts a chunk at the reader's current position.
     *
     * If another chunk in this book is mid-read, it is suspended first —
     * silently and without a rating, because the reader did not close it,
     * they moved on. At most one READING chunk per book survives this call.
     */
    suspend fun startChunk(
        bookId: String,
        startLocatorJson: String?,
        startFraction: Float,
        title: String? = null
    ): PagemarkEntity {
        dao.openChunk(bookId)?.let { open ->
            dao.upsert(PagemarkSession.suspend(open))
        }
        val now = System.currentTimeMillis()
        val count = dao.countForBook(bookId)
        val chunk = PagemarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            title = title?.takeIf { it.isNotBlank() } ?: PagemarkSession.nextTitle(count),
            startLocatorJson = startLocatorJson,
            startFraction = startFraction.coerceIn(0f, 1f),
            endLocatorJson = null,
            endFraction = 0f,
            state = PagemarkSession.State.READING.name,
            priority = PagemarkSession.DEFAULT_PRIORITY,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(chunk)
        return chunk
    }

    /** Pauses the reading chunk without judging it. */
    suspend fun suspendChunk(id: String) {
        val chunk = dao.get(id) ?: return
        dao.upsert(PagemarkSession.suspend(chunk))
    }

    /**
     * Closes the chunk at the position it reached and schedules its re-read.
     *
     * Returns the next due time, or null if the chunk no longer exists.
     */
    suspend fun closeChunk(
        id: String,
        rating: Int,
        endLocatorJson: String?,
        endFraction: Float,
        now: Instant = Instant.now()
    ): Long? {
        val chunk = dao.get(id) ?: return null
        val oldCard = chunk.fsrsCardJson?.let { FsrsCardCodec.fromJson(it) }
            ?: Card.builder().due(now).build()
        val result = scheduler.reviewCard(oldCard, PagemarkSession.ratingToFsrs(rating), now, null)
        var card = result.card()
        var nextDue = card.due ?: now.plusSeconds(86_400)
        if (card.due == null) {
            card = Card.builder().due(nextDue).build()
        }
        val closed = PagemarkSession.close(
            chunk = chunk,
            rating = rating,
            endLocatorJson = endLocatorJson,
            endFraction = endFraction,
            nextDueMillis = nextDue.toEpochMilli(),
            now = now.toEpochMilli()
        ).copy(
            fsrsCardJson = FsrsCardCodec.toJson(card)
        )
        dao.upsert(closed)
        return nextDue.toEpochMilli()
    }

    /**
     * Re-opens a chunk (fresh, suspended, or due for re-reading) and aims the
     * reader at its start so "open from the queue" lands on the chunk.
     */
    suspend fun resumeChunk(id: String) {
        val chunk = dao.get(id) ?: return
        // Starting a re-read of one chunk while another is mid-read suspends
        // the open one, exactly like startChunk does.
        dao.openChunk(chunk.bookId)?.let { open ->
            if (open.id != chunk.id) dao.upsert(PagemarkSession.suspend(open))
        }
        dao.upsert(PagemarkSession.begin(chunk))
        settingsRepository.setPendingReaderSource(
            chunk.bookId,
            PendingReaderSource(
                locatorJson = chunk.startLocatorJson,
                fraction = chunk.startFraction
            )
        )
    }

    suspend fun setPriority(id: String, priority: Int) {
        val chunk = dao.get(id) ?: return
        dao.upsert(
            chunk.copy(
                priority = PagemarkSession.clampPriority(priority),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun rename(id: String, title: String) {
        val chunk = dao.get(id) ?: return
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        dao.upsert(chunk.copy(title = trimmed, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }
}