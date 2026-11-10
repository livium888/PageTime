package com.pagetime.app.data

import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.ExplanationDao
import com.pagetime.app.data.local.ExplanationEntity
import com.pagetime.app.data.learning.ConceptRangeMatcher
import com.pagetime.app.data.learning.ExplanationEvaluation
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Drives the Feynman explain-back flow and stores source-grounded markings.
 *
 * The marking is done by the reader's own AI app: [prepareExplanation] builds
 * the prompt, the reader takes it there, and [recordExplanation] reads the
 * reply they paste back. See [ExplainBackMarking] and [ManualAsk].
 */
class ExplainBackRepository(
    private val conceptDao: ConceptDao,
    private val explanationDao: ExplanationDao,
) {
    fun observeExplanations(bookId: String): Flow<List<ExplanationEntity>> =
        explanationDao.observeForBook(bookId)

    /** One book/chapter/concept from the cross-book queue — see [observeConceptQueue]. */
    data class DueConcept(val bookId: String, val chapterIndex: Int, val conceptLabel: String)

    /** The cross-book explain-back queue: how many concepts are waiting, and which one is next. */
    data class ConceptQueue(val unexplainedCount: Int, val next: DueConcept?)

    /**
     * The single most overdue concept across every book, live. Unlike
     * [conceptsForRange], which only ever looks at one book's currently-open
     * chapter, this is the reader's whole library: with many books, most
     * unexplained concepts live in books that aren't the one currently open,
     * and this is the only thing that ever surfaces them.
     */
    fun observeConceptQueue(): Flow<ConceptQueue> =
        combine(conceptDao.observeAll(), explanationDao.observeAll()) { concepts, explanations ->
            ConceptQueue(
                unexplainedCount = DueConcepts.unexplainedCount(concepts, explanations),
                next = DueConcepts.next(concepts, explanations)?.let {
                    DueConcept(bookId = it.bookId, chapterIndex = it.firstChapterIndex, conceptLabel = it.label)
                }
            )
        }

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

    /**
     * An explanation waiting to be marked: the prompt for the reader's own AI
     * app, and everything needed to store the result once the reply is pasted.
     */
    class PendingExplanation internal constructor(
        val prompt: String,
        internal val bookId: String,
        internal val chapterIndex: Int,
        internal val chapterTitle: String?,
        internal val conceptLabel: String,
        internal val keyPoints: List<String>,
        val userExplanation: String,
    ) {
        /**
         * Stored while the reader is away in their AI app. Android may close
         * PageTime in the meantime, and what would be lost is the one thing in
         * this flow the reader typed themselves.
         */
        fun encode(): String = org.json.JSONObject()
            .put("prompt", prompt)
            .put("bookId", bookId)
            .put("chapterIndex", chapterIndex)
            .put("chapterTitle", chapterTitle ?: org.json.JSONObject.NULL)
            .put("conceptLabel", conceptLabel)
            .put("keyPoints", org.json.JSONArray(keyPoints))
            .put("userExplanation", userExplanation)
            .toString()

        /** Whether this was written for the screen now open. */
        fun isFor(bookId: String, chapterIndex: Int): Boolean =
            this.bookId == bookId && this.chapterIndex == chapterIndex

        val concept: String get() = conceptLabel

        companion object {
            /** Null for a missing or unreadable value: nothing pending, never a crash. */
            fun decode(raw: String?): PendingExplanation? {
                if (raw.isNullOrBlank()) return null
                return try {
                    val obj = org.json.JSONObject(raw)
                    val points = obj.optJSONArray("keyPoints")
                    val pending = PendingExplanation(
                        prompt = obj.getString("prompt"),
                        bookId = obj.getString("bookId"),
                        chapterIndex = obj.getInt("chapterIndex"),
                        chapterTitle = if (obj.isNull("chapterTitle")) null else obj.optString("chapterTitle"),
                        conceptLabel = obj.getString("conceptLabel"),
                        keyPoints = (0 until (points?.length() ?: 0)).map { points!!.optString(it) },
                        userExplanation = obj.getString("userExplanation"),
                    )
                    pending.takeIf { it.prompt.isNotBlank() && it.userExplanation.isNotBlank() }
                } catch (_: org.json.JSONException) {
                    null
                }
            }
        }
    }

    /** Builds the marking prompt. Nothing is stored until a reply is recorded. */
    suspend fun prepareExplanation(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String?,
        bookTitle: String,
        conceptLabel: String,
        userExplanation: String,
        sourceText: String
    ): PendingExplanation {
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
        return PendingExplanation(
            prompt = ExplainBackMarking.prompt(
                conceptLabel = conceptLabel,
                keyPoints = compactKeyPoints,
                source = sourceText,
                userExplanation = userExplanation,
                bookTitle = bookTitle,
                chapterTitle = chapterTitle ?: "",
                anchor = concept?.sourceQuote,
            ),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            conceptLabel = conceptLabel,
            keyPoints = compactKeyPoints,
            userExplanation = userExplanation,
        )
    }

    /**
     * Reads the marking the reader pasted back and stores it.
     *
     * Fails, storing nothing, when the reply holds no usable marking: a
     * fabricated mark would be worse than asking for the reply again.
     */
    suspend fun recordExplanation(pending: PendingExplanation, reply: String): ExplanationEvaluation {
        val evaluation = ExplainBackMarking.parse(reply) ?: error(UNREADABLE)
        val now = System.currentTimeMillis()
        explanationDao.upsert(
            ExplanationEntity(
                id = UUID.randomUUID().toString(),
                bookId = pending.bookId,
                chapterIndex = pending.chapterIndex,
                chapterTitle = pending.chapterTitle,
                conceptLabel = pending.conceptLabel,
                conceptKeyPoints = pending.keyPoints.joinToString("||"),
                userExplanation = pending.userExplanation,
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
        const val UNREADABLE =
            "That reply doesn't hold a marking. Copy the AI's whole reply (the code block) and try again."
    }
}
