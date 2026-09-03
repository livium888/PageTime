package com.pagetime.app.data

/**
 * What a word means *here*.
 *
 * Android already has "Define" in the text-selection menu, and a small model is
 * worse than a dictionary at dictionary work: definitions and etymologies are
 * recalled facts, which is exactly where a 1B model invents most confidently
 * and where the reader has no way to catch it. So this deliberately does not
 * ask for a definition.
 *
 * What a dictionary cannot do is say which of five senses is meant in the
 * sentence in front of you. That answer is grounded in text the model was
 * handed rather than recalled from training, which is both the more useful
 * question and the one least likely to be fabricated. The rules below rule out
 * origins and etymology for the same reason: they are unbounded by the passage.
 */
object WordGloss {

    /** Longest selection worth explaining; beyond this it is a passage, not a term. */
    const val MAX_TERM_CHARS = 120

    /** Context kept either side. Readium hands back about 200 characters each way. */
    const val MAX_CONTEXT_CHARS = 240

    /** Longest explanation kept, so a rambling reply cannot fill the screen. */
    const val MAX_GLOSS_CHARS = 400

    /**
     * Why [term] cannot be explained, or null when it can. A selection spanning
     * a paragraph is a capture, not a word to look up.
     */
    fun termProblem(term: String): String? {
        val trimmed = term.trim()
        return when {
            trimmed.isBlank() -> "Nothing is selected."
            trimmed.length > MAX_TERM_CHARS ->
                "That is a passage rather than a term — capture it as a card instead."
            else -> null
        }
    }

    /**
     * The sentence the term sits in, rebuilt from the selection's surroundings,
     * for showing beside the answer. The reader can then check the explanation
     * against the words it claims to be explaining, which is the whole defence
     * against a confident wrong answer.
     */
    fun sentenceAround(before: String, term: String, after: String): String {
        val lead = before.takeLast(MAX_CONTEXT_CHARS).substringAfterLast(". ").trimStart()
        val tail = after.take(MAX_CONTEXT_CHARS).let { rest ->
            val stop = rest.indexOfFirst { it == '.' || it == '!' || it == '?' }
            if (stop >= 0) rest.take(stop + 1) else rest
        }
        return normalize("$lead$term$tail")
    }

    fun prompt(term: String, before: String, after: String, bookTitle: String): String {
        val context = normalize(
            before.takeLast(MAX_CONTEXT_CHARS) + " ⟦" + term.trim() + "⟧ " +
                after.take(MAX_CONTEXT_CHARS)
        )
        return """
            |Book: "$bookTitle"
            |
            |Passage, with the reader's selection marked ⟦like this⟧:
            |$context
            |
            |The reader does not know what ⟦${term.trim()}⟧ means here.
            |
            |Say, in at most three sentences:
            |- what it means in THIS passage, in plain words
            |- why the writer used it here, or what it is doing in the sentence
            |
            |Rules:
            |- Explain the sense used here. If the word has other senses, ignore
            |  them; the reader is looking at this one.
            |- Never give etymology, origins, or which language it came from.
            |- Never list several senses. One reading, the one on the page.
            |- If the passage does not make the meaning clear, say exactly that
            |  rather than choosing a meaning it does not support.
            |- Plain prose. No headings, no bullets, no quotation marks.
            """.trimMargin()
    }

    /**
     * The model's answer, tidied for display. Small models like to open with
     * "Sure!" or restate the question, and both are noise above an answer the
     * reader is trying to read quickly.
     */
    fun cleanGloss(raw: String): String? {
        var text = raw.trim()
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .trim('"')
        LEAD_INS.forEach { lead ->
            if (text.startsWith(lead, ignoreCase = true)) {
                text = text.removeRange(0, lead.length).trimStart(' ', ':', ',', '-', '—')
            }
        }
        text = normalize(text)
        if (text.isBlank()) return null
        // Stripping "In this passage," leaves the answer starting mid-sentence,
        // which reads as a fragment even though nothing is missing.
        text = text.replaceFirstChar { it.uppercaseChar() }
        if (text.length <= MAX_GLOSS_CHARS) return text
        // Cut at a sentence end rather than mid-word, so a long answer reads as
        // finished rather than truncated.
        val cut = text.take(MAX_GLOSS_CHARS).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (cut > MAX_GLOSS_CHARS / 2) text.take(cut + 1) else text.take(MAX_GLOSS_CHARS).trimEnd() + "…"
    }

    private val LEAD_INS = listOf(
        "Sure!", "Sure,", "Certainly!", "Certainly,", "Of course!",
        "Here is the explanation", "Here's the explanation",
        "In this passage,", "In this context,",
    )

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}

/** An explanation of a selected term, with the sentence it was read in. */
data class Gloss(
    val term: String,
    val sentence: String,
    val explanation: String,
    val source: LlmProviderKind?,
)
