package com.pagetime.app.data

import com.pagetime.app.data.learning.ExplanationEvaluation
import org.json.JSONObject

/**
 * Marking a reader's explanation with their own AI app: the prompt to take
 * there, and a reader for the reply.
 *
 * The reply is asked for as JSON because three scores and four short texts do
 * not survive being picked out of prose. Chat apps wrap JSON in a code fence
 * and add a sentence before or after it, so [parse] looks for the first whole
 * object anywhere in the reply rather than expecting the reply to be one.
 */
object ExplainBackMarking {

    const val MAX_SOURCE_CHARS = 6_000
    const val MAX_EXPLANATION_CHARS = 4_000

    fun prompt(
        conceptLabel: String,
        keyPoints: List<String>,
        source: String,
        userExplanation: String,
        bookTitle: String,
        chapterTitle: String,
        anchor: String? = null,
    ): String {
        val passage = sourceWindow(source, anchor)
        val points = keyPoints.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "the main idea" }
        return """
            |You are marking a reader's understanding of a concept from a book.
            |Be encouraging but honest. The goal is to help the reader truly understand,
            |not just memorize.
            |
            |Book: $bookTitle
            |Chapter: $chapterTitle
            |Concept: $conceptLabel
            |Key aspects the reader should cover: $points
            |
            |Source text (use it to judge accuracy; do not add facts from outside it):
            |$passage
            |
            |Reader's explanation:
            |${userExplanation.trim().take(MAX_EXPLANATION_CHARS)}
            |
            |Mark it on three dimensions, each from 1 to 5:
            |1. ACCURACY: Are the facts correct?
            |2. COMPLETENESS: Did they cover the key aspects?
            |3. CLARITY: Could a 12-year-old follow this explanation?
            |
            |Reply with ONLY this JSON, in a code block, and nothing else:
            |{
            |  "accuracy": <1-5>,
            |  "completeness": <1-5>,
            |  "clarity": <1-5>,
            |  "whatTheyGotRight": "<specific points they explained correctly>",
            |  "whatTheyMissed": "<what they missed or got wrong>",
            |  "suggestedImprovement": "<one specific suggestion>",
            |  "simplerVersion": "<a clear, simple 2-3 sentence version of the ideal explanation>"
            |}
        """.trimMargin()
    }

    /** The text around [anchor] when the source is too long to send whole. */
    fun sourceWindow(source: String, anchor: String?): String {
        val text = source.trim()
        if (text.length <= MAX_SOURCE_CHARS) return text
        val at = anchor?.trim()?.takeIf { it.length >= 12 }?.let { text.indexOf(it) } ?: -1
        if (at < 0) return text.takeLast(MAX_SOURCE_CHARS).trimStart()
        val start = (at - MAX_SOURCE_CHARS / 2).coerceIn(0, text.length - MAX_SOURCE_CHARS)
        return text.substring(start, start + MAX_SOURCE_CHARS).trim()
    }

    /** The marks in [reply], or null when it holds no usable marking. */
    fun parse(reply: String): ExplanationEvaluation? {
        val json = JsonInReply.firstObject(reply) ?: return null
        val accuracy = score(json, "accuracy") ?: return null
        val completeness = score(json, "completeness") ?: return null
        val clarity = score(json, "clarity") ?: return null
        return ExplanationEvaluation(
            accuracy = accuracy,
            completeness = completeness,
            clarity = clarity,
            overallScore = (accuracy + completeness + clarity) / 3f,
            whatTheyGotRight = json.optString("whatTheyGotRight", "").trim(),
            whatTheyMissed = json.optString("whatTheyMissed", "").trim(),
            suggestedImprovement = json.optString("suggestedImprovement", "").trim(),
            simplerVersion = json.optString("simplerVersion", "").trim(),
        )
    }

    /** A score the model actually gave, held to the 1–5 scale. Missing is not 3. */
    private fun score(json: JSONObject, key: String): Int? {
        if (!json.has(key)) return null
        val value = json.optDouble(key, Double.NaN)
        if (value.isNaN()) return null
        return Math.round(value).toInt().coerceIn(1, 5)
    }
}
