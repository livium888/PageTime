package com.pagetime.app.data

import com.pagetime.app.data.local.AiUsageDao
import com.pagetime.app.data.local.AiUsageEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stores auditable request metadata so AI usage is visible instead of mysterious. */
class AiUsageRepository(private val dao: AiUsageDao) {

    suspend fun <T> track(
        bookId: String,
        operation: String,
        model: String,
        inputCharacters: Int,
        outputItems: (T) -> Int,
        secondaryItems: (T) -> Int = { 0 },
        block: suspend () -> T
    ): T {
        val startedAt = System.currentTimeMillis()
        val id = dao.insert(
            AiUsageEntity(
                bookId = bookId,
                operation = operation,
                model = model,
                status = STATUS_PENDING,
                inputCharacters = inputCharacters.coerceAtLeast(0),
                createdAt = startedAt
            )
        )
        return try {
            val result = block()
            dao.complete(
                id = id,
                status = STATUS_SUCCESS,
                outputItems = outputItems(result).coerceAtLeast(0),
                secondaryItems = secondaryItems(result).coerceAtLeast(0),
                completedAt = System.currentTimeMillis()
            )
            result
        } catch (error: Throwable) {
            dao.complete(
                id = id,
                status = STATUS_FAILED,
                outputItems = 0,
                secondaryItems = 0,
                completedAt = System.currentTimeMillis()
            )
            throw error
        }
    }

    val stats: Flow<AiUsageStats> = dao.observeAll().map { events ->
        AiUsageStats.from(events)
    }

    companion object {
        const val OPERATION_CARDS = "cards"
        const val OPERATION_CONCEPTS = "concepts"
        const val OPERATION_REFORMAT = "reformat"
        const val STATUS_PENDING = "pending"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

data class AiUsageStats(
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val cardCalls: Int = 0,
    val conceptCalls: Int = 0,
    val reformatCalls: Int = 0,
    val cardsGenerated: Int = 0,
    val conceptsFound: Int = 0,
    val relationshipsFound: Int = 0,
    val inputCharacters: Long = 0,
    val todayCalls: Int = 0,
    val todayInputCharacters: Long = 0,
    val todayCardsGenerated: Int = 0,
    val lastCallAt: Long? = null
) {
    /** Input tokens are only an estimate; Gemini's billing tokenizer is not exposed here. */
    val estimatedInputTokens: Long get() = inputCharacters / CHARS_PER_TOKEN
    val todayEstimatedInputTokens: Long get() = todayInputCharacters / CHARS_PER_TOKEN

    companion object {
        private const val CHARS_PER_TOKEN = 4L

        fun from(events: List<AiUsageEntity>, now: Instant = Instant.now()): AiUsageStats {
            val todayStart = now.atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val trackedOps = setOf(
                AiUsageRepository.OPERATION_CARDS,
                AiUsageRepository.OPERATION_CONCEPTS,
                AiUsageRepository.OPERATION_REFORMAT
            )
            val analyzed = events.filter { it.operation in trackedOps }
            val today = analyzed.filter { it.createdAt >= todayStart }
            return AiUsageStats(
                totalCalls = analyzed.size,
                successfulCalls = analyzed.count { it.status == AiUsageRepository.STATUS_SUCCESS },
                failedCalls = analyzed.count { it.status == AiUsageRepository.STATUS_FAILED },
                cardCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_CARDS },
                conceptCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_CONCEPTS },
                reformatCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_REFORMAT },
                cardsGenerated = analyzed
                    .filter { it.operation == AiUsageRepository.OPERATION_CARDS }
                    .sumOf { it.outputItems },
                conceptsFound = analyzed
                    .filter { it.operation == AiUsageRepository.OPERATION_CONCEPTS }
                    .sumOf { it.outputItems },
                relationshipsFound = analyzed
                    .filter { it.operation == AiUsageRepository.OPERATION_CONCEPTS }
                    .sumOf { it.secondaryItems },
                inputCharacters = analyzed.sumOf { it.inputCharacters.toLong() },
                todayCalls = today.size,
                todayInputCharacters = today.sumOf { it.inputCharacters.toLong() },
                todayCardsGenerated = today
                    .filter { it.operation == AiUsageRepository.OPERATION_CARDS }
                    .sumOf { it.outputItems },
                lastCallAt = analyzed.maxOfOrNull { it.createdAt }
            )
        }
    }
}
