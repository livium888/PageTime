package com.pagetime.app.data

import com.pagetime.app.data.learning.ExplanationEvaluation

/**
 * Grading an explain-back answer on the device.
 *
 * Explain Back is the app's best learning idea and, until now, the one that
 * needed a network key to work at all: the repository called Gemini directly,
 * and that call opens by refusing when no key is configured. A reader with the
 * offline model installed had a Feynman flow that could take their explanation
 * and never answer it.
 *
 * The task itself suits a small model better than most of what this app asks
 * for. Everything it needs is in the prompt — the passage, and the reader's own
 * words about it — so it is judgement over supplied text rather than recall,
 * which is where a model this size is worth trusting.
 *
 * WHY THERE ARE NO THREE SCORES HERE
 *
 * Gemini returns accuracy, completeness and clarity as separate 1-5 integers.
 * Asking a 1B model for three independent numbers on a five-point scale gets
 * three numbers back, and they are noise: it does not measure, it pattern-
 * matches to whatever numbers appeared in the prompt. Choosing between three
 * named words is a different task, and one it can do — the same reason the
 * card asks for "idea" and "because" instead of "two sentences".
 *
 * So the local grader returns one verdict, which becomes the overall score, and
 * leaves the three sub-scores empty rather than inventing them. A number the
 * model never worked out is worse than no number: it looks like a measurement.
 */
object ExplainBackGrading {

    /**
     * Source text the prompt may carry. Gemini gets 6,000 characters; on the
     * device that plus an explanation would not fit the budget with room to
     * answer, so the window is smaller and, where the concept can be located
     * in it, centred on the concept rather than taken from the end.
     */
    const val MAX_SOURCE_CHARS = 4_000

    /** The reader's own answer. Longer than this is trimmed from the end. */
    const val MAX_EXPLANATION_CHARS = 1_200

    /** Longest any single field of the reply is kept. */
    const val MAX_FIELD_CHARS = 400

    /** Tokens reserved for the reply. Five short fields, none of them a note. */
    const val REPLY_TOKENS = 384

    /**
     * How well the reader explained it. Three named options rather than a
     * scale, because a choice between words is a task a small model can do and
     * a five-point judgement is not.
     */
    enum class Verdict(val key: String, val score: Float) {
        SOLID("solid", 4.5f),
        PARTLY("partly", 3.0f),
        OFF("off", 1.5f),
    }

    /**
     * The slice of [source] the grader should read.
     *
     * Prefers the text around [anchor] — the quote the concept was drawn from —
     * so the model is judging the explanation against the part of the chapter
     * the concept actually came from. Without an anchor it takes the end, which
     * is the most recently read text and the likeliest subject.
     */
    fun sourceWindow(source: String, anchor: String?): String {
        val text = source.trim()
        if (text.length <= MAX_SOURCE_CHARS) return text
        val at = anchor?.trim()?.takeIf { it.length >= 12 }?.let { text.indexOf(it) } ?: -1
        if (at < 0) return text.takeLast(MAX_SOURCE_CHARS).trimStart()
        val start = (at - MAX_SOURCE_CHARS / 2).coerceIn(0, (text.length - MAX_SOURCE_CHARS).coerceAtLeast(0))
        return text.substring(start, (start + MAX_SOURCE_CHARS).coerceAtMost(text.length)).trim()
    }

    fun prompt(
        conceptLabel: String,
        keyPoints: List<String>,
        source: String,
        userExplanation: String,
        anchor: String? = null,
    ): String {
        val passage = sourceWindow(source, anchor)
        val answer = userExplanation.trim().take(MAX_EXPLANATION_CHARS)
        val points = keyPoints.filter { it.isNotBlank() }.take(3)
        val expected =
            if (points.isEmpty()) ""
            else "\nWorth covering: ${points.joinToString("; ")}\n"
        return """
            |From the book:
            |$passage
            |
            |A reader was asked to explain "$conceptLabel" in their own words.
            |$expected
            |This is what they wrote:
            |"$answer"
            |
            |Mark it. Judge only against the passage above — not against
            |anything else you know about the subject.
            |
            |Fill in these five fields:
            |- verdict: one word, exactly one of: ${Verdict.entries.joinToString(", ") { it.key }}.
            |  solid = they have understood it. partly = right in places,
            |  missing something that matters. off = they have the wrong idea.
            |- right: one sentence naming something they genuinely got right.
            |  Be specific about what, not encouraging in general.
            |- missed: one sentence naming the single most important thing they
            |  left out or got wrong. If there is nothing, say so plainly.
            |- better: one thing to do differently, stated as an instruction
            |  they can act on now.
            |- simple: the idea explained well, in two short sentences a
            |  twelve-year-old could follow.
            |
            |Rules:
            |- Never invent a fact the passage does not contain.
            |- Speak to the reader as "you".
            |- Plain words. Short sentences.
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"verdict": "...", "right": "...", "missed": "...", "better": "...", "simple": "..."}
            |{"verdict": "
            """.trimMargin()
    }

    /**
     * The reply as an evaluation, or null when nothing usable came back.
     *
     * A missing verdict is not fatal: a grader that wrote useful prose and
     * dropped the one-word field still told the reader something, and PARTLY
     * is the honest reading of a mark that never arrived. Missing prose with a
     * verdict alone is fatal, because a bare score is exactly the thing this
     * grader is not supposed to produce.
     */
    fun parse(raw: String): ExplanationEvaluation? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isBlank()) return null

        val right = field(cleaned, "right")
        val missed = field(cleaned, "missed")
        val better = field(cleaned, "better")
        val simple = field(cleaned, "simple")
        if (right == null && missed == null && better == null && simple == null) return null

        val verdict = verdictIn(field(cleaned, "verdict")) ?: Verdict.PARTLY
        return ExplanationEvaluation(
            accuracy = null,
            completeness = null,
            clarity = null,
            overallScore = verdict.score,
            whatTheyGotRight = right.orEmpty(),
            whatTheyMissed = missed.orEmpty(),
            suggestedImprovement = better.orEmpty(),
            simplerVersion = simple.orEmpty(),
        )
    }

    /**
     * The verdict named in [value]. Matches on the word appearing anywhere,
     * since a model that was asked for one word routinely returns "partly —
     * they missed the mechanism". OFF is checked as a whole word: "off" is a
     * common substring and matching it loosely would fail every answer that
     * merely mentions being off-topic.
     */
    private fun verdictIn(value: String?): Verdict? {
        val text = value?.lowercase() ?: return null
        return when {
            text.contains("solid") -> Verdict.SOLID
            text.contains("partly") || text.contains("partial") -> Verdict.PARTLY
            Regex("""\boff\b""").containsMatchIn(text) -> Verdict.OFF
            else -> null
        }
    }

    private fun field(source: String, key: String): String? {
        val value = LumenCapture.jsonStringValue(source, key, truncated = true) ?: return null
        var text = value.replace(Regex("\\s+"), " ").trim().trim('"', '\'')
        if (text.isBlank()) return null
        text = text.replaceFirstChar { it.uppercaseChar() }
        if (text.length <= MAX_FIELD_CHARS) return text
        val cut = text.take(MAX_FIELD_CHARS).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (cut > MAX_FIELD_CHARS / 2) text.take(cut + 1) else text.take(MAX_FIELD_CHARS).trimEnd() + "…"
    }
}
