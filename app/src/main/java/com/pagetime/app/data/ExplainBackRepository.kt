package com.pagetime.app.data

import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.ExplanationDao
import com.pagetime.app.data.local.ExplanationEntity
import com.pagetime.app.data.learning.ConceptRangeMatcher
import com.pagetime.app.data.learning.ExplanationEvaluation
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Drives the Feynman explain-back flow and persists source-grounded evaluations. */
class ExplainBackRepository(
    private val conceptDao: ConceptDao,
    private val explanationDao: ExplanationDao,
    private val geminiClient: GeminiLearningClient,
    private val contextExtractor: LearningContextExtractor
) {
    fun observeExplanations(bookId: String): Flow<List<ExplanationEntity>> =
        explanationDao.observeForBook(bookId)

    /**
     * Concepts that are actually grounded in the given reading-range text.
     * A chapter-span filter alone would surface concepts from unrelated parts
     * of the chapter, so every candidate must also appear in the range itself.
     */
    suspend fun conceptsForRange(bookId: String, chapterIndex: Int, rangeText: String): List<String> =
        conceptDao.getForBook(bookId)
            .filter { it.firstChapterIndex <= chapterIndex && it.lastChapterIndex >= chapterIndex }
            .filter { ConceptRangeMatcher.isRelevant(it.label, it.sourceQuote, rangeText) }
            .map { it.label }
            .take(5)

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

        val evaluation = geminiClient.evaluateExplanation(
            conceptLabel = conceptLabel,
            keyPoints = compactKeyPoints,
            sourceExcerpt = sourceText.take(12_000),
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
            appendLine("Accuracy: ${ev.accuracy}/5")
            appendLine("Completeness: ${ev.completeness}/5")
            appendLine("Clarity: ${ev.clarity}/5")
            appendLine()
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
}
