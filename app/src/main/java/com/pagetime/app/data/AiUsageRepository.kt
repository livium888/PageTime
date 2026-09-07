package com.pagetime.app.data

import com.pagetime.app.data.local.AiUsageDao
import com.pagetime.app.data.local.AiUsageEntity
import com.pagetime.app.data.learning.GeminiUsageSink
import kotlinx.coroutines.withContext
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
        // The sink travels with this call and no other, so two requests in
        // flight cannot have their tokens attributed to each other's row.
        val sink = GeminiUsageSink()
        return try {
            val result = withContext(sink) { block() }
            dao.complete(
                id = id,
                status = STATUS_SUCCESS,
                outputItems = outputItems(result).coerceAtLeast(0),
                secondaryItems = secondaryItems(result).coerceAtLeast(0),
                completedAt = System.currentTimeMillis()
            )
            // Left null when the API reported nothing — the on-device model, or
            // a response with no usage block. Null is "not measured"; zero would
            // be "measured, and free".
            sink.usage?.let { usage ->
                dao.recordTokens(
                    id = id,
                    promptTokens = usage.promptTokens,
                    outputTokens = usage.outputTokens,
                    thinkingTokens = usage.thinkingTokens,
                    cachedTokens = usage.cachedTokens,
                    totalTokens = usage.totalTokens,
                )
            }
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
        const val OPERATION_CONCEPTS = "concepts"
        const val OPERATION_REFORMAT = "reformat"
        const val OPERATION_LUMEN = "lumen"
        const val OPERATION_GLOSS = "gloss"
        const val OPERATION_EXPLAIN = "explain"
        const val OPERATION_CHAPTER_PROMPTS = "chapter_prompts"
        const val STATUS_PENDING = "pending"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

data class AiUsageStats(
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val conceptCalls: Int = 0,
    val reformatCalls: Int = 0,
    val lumenCalls: Int = 0,
    val glossCalls: Int = 0,
    val explainCalls: Int = 0,
    val conceptsFound: Int = 0,
    val relationshipsFound: Int = 0,
    val inputCharacters: Long = 0,
    val todayCalls: Int = 0,
    val todayInputCharacters: Long = 0,
    val lastCallAt: Long? = null,
    /**
     * Tokens as the API reported them, over the calls that reported any.
     *
     * These replace an estimate that divided characters by four and counted no
     * output whatsoever. On a generation call the output is a large share of
     * the bill, and on gemini-2.5-flash the model's own reasoning is billed as
     * output too — so the old number was not merely imprecise, it was low.
     */
    val promptTokens: Long = 0,
    val outputTokens: Long = 0,
    val thinkingTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val todayTotalTokens: Long = 0,
    /** Calls that reported nothing, so the totals above can be honest about coverage. */
    val unmeasuredCalls: Int = 0
) {
    val hasMeasuredTokens: Boolean get() = totalTokens > 0

    companion object {

        fun from(events: List<AiUsageEntity>, now: Instant = Instant.now()): AiUsageStats {
            val todayStart = now.atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val trackedOps = setOf(
                AiUsageRepository.OPERATION_CONCEPTS,
                AiUsageRepository.OPERATION_REFORMAT,
                AiUsageRepository.OPERATION_LUMEN,
                AiUsageRepository.OPERATION_GLOSS,
                AiUsageRepository.OPERATION_EXPLAIN,
                AiUsageRepository.OPERATION_CHAPTER_PROMPTS
            )
            val analyzed = events.filter { it.operation in trackedOps }
            val today = analyzed.filter { it.createdAt >= todayStart }
            return AiUsageStats(
                totalCalls = analyzed.size,
                successfulCalls = analyzed.count { it.status == AiUsageRepository.STATUS_SUCCESS },
                failedCalls = analyzed.count { it.status == AiUsageRepository.STATUS_FAILED },
                conceptCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_CONCEPTS },
                reformatCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_REFORMAT },
                lumenCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_LUMEN },
                glossCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_GLOSS },
                explainCalls = analyzed.count { it.operation == AiUsageRepository.OPERATION_EXPLAIN },
                conceptsFound = analyzed
                    .filter { it.operation == AiUsageRepository.OPERATION_CONCEPTS }
                    .sumOf { it.outputItems },
                relationshipsFound = analyzed
                    .filter { it.operation == AiUsageRepository.OPERATION_CONCEPTS }
                    .sumOf { it.secondaryItems },
                inputCharacters = analyzed.sumOf { it.inputCharacters.toLong() },
                todayCalls = today.size,
                todayInputCharacters = today.sumOf { it.inputCharacters.toLong() },
                lastCallAt = analyzed.maxOfOrNull { it.createdAt },
                promptTokens = analyzed.sumOf { (it.promptTokens ?: 0).toLong() },
                outputTokens = analyzed.sumOf { (it.outputTokens ?: 0).toLong() },
                thinkingTokens = analyzed.sumOf { (it.thinkingTokens ?: 0).toLong() },
                cachedTokens = analyzed.sumOf { (it.cachedTokens ?: 0).toLong() },
                totalTokens = analyzed.sumOf { (it.totalTokens ?: 0).toLong() },
                todayTotalTokens = today.sumOf { (it.totalTokens ?: 0).toLong() },
                // Successful calls the API told us nothing about: the on-device
                // model, and every row written before this was measured. Named
                // so a total can say what it does not cover.
                unmeasuredCalls = analyzed.count {
                    it.status == AiUsageRepository.STATUS_SUCCESS && it.totalTokens == null
                }
            )
        }
    }
}
