package com.pagetime.app.data

import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.local.ExplanationDao
import com.pagetime.app.data.local.ExplanationEntity
import com.pagetime.app.data.learning.ConceptRangeMatcher
import com.pagetime.app.data.learning.ExplanationEvaluation
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Drives the Feynman explain-back flow and persists source-grounded evaluations.
 *
 * Grading runs wherever the reader's chosen provider says, through the same
 * router as every other AI ask in the app. It used to call Gemini directly, so
 * the flow accepted an explanation and then threw "Gemini API key is not
 * configured" at anyone without one — including a reader with the offline model
 * installed and working.
 *
 * Like the word lookup and unlike a capture, this fails rather than falling
 * back. A card has a useful non-AI form; there is no non-AI way to tell someone
 * what their explanation missed, and a fabricated mark would be worse than
 * saying no grader is available.
 */
class ExplainBackRepository(
    private val conceptDao: ConceptDao,
    private val explanationDao: ExplanationDao,
    private val geminiClient: GeminiLearningClient,
    private val contextExtractor: LearningContextExtractor,
    private val settingsRepository: SettingsRepository? = null,
    private val localLlmProvider: LlmProvider? = null,
    private val aiUsageRepository: AiUsageRepository? = null
) {
    fun observeExplanations(bookId: String): Flow<List<ExplanationEntity>> =
        explanationDao.observeForBook(bookId)

    /**
     * Concepts that are actually grounded in the given reading-range text.
     * A chapter-span filter alone would surface concepts from unrelated parts
     * of the chapter, so every candidate must also appear in the range itself.
     *
     * Concepts you have never explained come first, then the ones explained
     * longest ago — so the flow rotates through the range instead of always
     * reopening the same concept.
     */
    suspend fun conceptsForRange(bookId: String, chapterIndex: Int, rangeText: String): List<String> {
        val lastExplainedAt = explanationDao.getAllForBook(bookId)
            .groupBy { it.conceptLabel.trim().lowercase() }
            .mapValues { (_, rows) -> rows.maxOf { it.createdAt } }
        return conceptDao.getForBook(bookId)
            .filter { it.firstChapterIndex <= chapterIndex && it.lastChapterIndex >= chapterIndex }
            .filter { ConceptRangeMatcher.isRelevant(it.label, it.sourceQuote, rangeText) }
            .sortedWith(
                compareBy(
                    { lastExplainedAt.containsKey(it.label.trim().lowercase()) },
                    { lastExplainedAt[it.label.trim().lowercase()] ?: 0L }
                )
            )
            .map { it.label }
            .take(5)
    }

    suspend fun submitExplanation(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String?,
        bookTitle: String,
        conceptLabel: String,
        userExplanation: String,
        sourceText: String
    ): ExplanationEvaluation {
        val concept = conceptDao.getForBook(bookId).firstOrNull { it.label == conceptLabel }
        val keyPoints = concept?.description
            ?.split(".", ",", ";")
            ?.map(String::trim)
            ?.filter { it.length in 5..80 }
            ?.take(3)
            ?: emptyList()
        val bestPrior = explanationDao.bestForConcept(bookId, conceptLabel)
        val memoryHint = bestPrior?.let {
            buildString {
                append("Previous best score: ")
                append(it.overallScore?.let { score -> String.format("%.1f", score) } ?: "pending")
                append("/5. ")
                it.whatTheyMissed?.takeIf(String::isNotBlank)?.let { missed ->
                    append("Previous gap: ")
                    append(missed.take(240))
                }
            }
        }.orEmpty()
        val compactKeyPoints = (keyPoints + memoryHint).filter { it.isNotBlank() }.take(4)

        val evaluation = grade(
            bookId = bookId,
            conceptLabel = conceptLabel,
            keyPoints = compactKeyPoints,
            anchor = concept?.sourceQuote,
            sourceText = sourceText,
            userExplanation = userExplanation,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle ?: ""
        )

        val now = System.currentTimeMillis()
        explanationDao.upsert(
            ExplanationEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                conceptLabel = conceptLabel,
                conceptKeyPoints = compactKeyPoints.joinToString("||"),
                userExplanation = userExplanation,
                aiFeedback = buildFeedbackText(evaluation),
                accuracyScore = evaluation.accuracy,
                completenessScore = evaluation.completeness,
                clarityScore = evaluation.clarity,
                overallScore = evaluation.overallScore,
                whatTheyGotRight = evaluation.whatTheyGotRight,
                whatTheyMissed = evaluation.whatTheyMissed,
                suggestedImprovement = evaluation.suggestedImprovement,
                simplerVersion = evaluation.simplerVersion,
                createdAt = now,
                updatedAt = now
            )
        )
        return evaluation
    }

    /**
     * Marks the explanation with whichever grader the reader has configured.
     *
     * The two graders are handed different amounts of the chapter. Gemini takes
     * 12,000 characters because it can; the device spends one token budget on
     * the passage, the reader's answer and the reply together, so the local
     * prompt takes a window centred on the quote the concept came from.
     */
    private suspend fun grade(
        bookId: String,
        conceptLabel: String,
        keyPoints: List<String>,
        anchor: String?,
        sourceText: String,
        userExplanation: String,
        bookTitle: String,
        chapterTitle: String
    ): ExplanationEvaluation {
        val provider = settingsRepository?.llmProvider() ?: LlmProviderKind.GEMINI
        val source = LumenDraftRouter.sourceFor(
            provider = provider,
            geminiConfigured = geminiClient.hasKey(),
            localModelAvailable = localLlmProvider?.isAvailable == true
        )
        return when (source) {
            LumenDraftSource.GEMINI -> geminiClient.evaluateExplanation(
                conceptLabel = conceptLabel,
                keyPoints = keyPoints,
                sourceExcerpt = sourceText.take(12_000),
                userExplanation = userExplanation,
                bookTitle = bookTitle,
                chapterTitle = chapterTitle
            )

            LumenDraftSource.LOCAL -> {
                val local = localLlmProvider ?: error(NO_GRADER)
                val prompt = ExplainBackGrading.prompt(
                    conceptLabel = conceptLabel,
                    keyPoints = keyPoints,
                    source = sourceText,
                    userExplanation = userExplanation,
                    anchor = anchor
                )
                val call: suspend () -> String = {
                    local.generate(
                        LlmRequest(prompt, maxOutputTokens = ExplainBackGrading.REPLY_TOKENS)
                    ).getOrThrow().text
                }
                val raw = aiUsageRepository?.track(
                    bookId = bookId,
                    operation = AiUsageRepository.OPERATION_EXPLAIN,
                    model = LlmProviderKind.OFFLINE.key,
                    inputCharacters = prompt.length,
                    outputItems = { it.length },
                    block = call
                ) ?: call()
                ExplainBackGrading.parse(raw)
                    ?: error("The offline model did not return a usable mark. Try again.")
            }

            LumenDraftSource.FALLBACK -> error(NO_GRADER)
        }
    }

    suspend fun deleteExplanation(id: String) = explanationDao.deleteById(id)

    suspend fun countMastered(bookId: String): Int = explanationDao.countMastered(bookId)

    suspend fun countExplained(bookId: String): Int = explanationDao.countConceptsExplained(bookId)

    private fun buildFeedbackText(ev: ExplanationEvaluation): String {
        val overall = ev.overallScore
        val emoji = when {
            overall >= 4.0f -> "🌟"
            overall >= 3.0f -> "👍"
            else -> "📝"
        }
        return buildString {
            appendLine("$emoji Score: ${String.format("%.1f", overall)}/5.0")
            appendLine()
            if (ev.hasBreakdown) {
                appendLine("Accuracy: ${ev.accuracy}/5")
                appendLine("Completeness: ${ev.completeness}/5")
                appendLine("Clarity: ${ev.clarity}/5")
                appendLine()
            }
            if (ev.whatTheyGotRight.isNotBlank()) {
                appendLine("What you got right:")
                appendLine(ev.whatTheyGotRight)
                appendLine()
            }
            if (ev.whatTheyMissed.isNotBlank()) {
                appendLine("What you missed:")
                appendLine(ev.whatTheyMissed)
                appendLine()
            }
            if (ev.suggestedImprovement.isNotBlank()) {
                appendLine("Suggestion: ${ev.suggestedImprovement}")
                appendLine()
            }
            if (ev.simplerVersion.isNotBlank()) {
                appendLine("A clearer version:")
                appendLine(ev.simplerVersion)
            }
        }
    }

    private companion object {
        const val NO_GRADER =
            "No AI is set up to mark this. Add a Gemini key or install the offline " +
                "model in Settings."
    }
}
