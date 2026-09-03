package com.pagetime.app.data

/**
 * The same sentence, said in words a reader can follow.
 *
 * The word lookup answers "what does this mean"; this answers "what is this
 * sentence saying", which is the other half of the same problem for someone
 * still learning English. A long sentence full of subordinate clauses can be
 * opaque even when every word in it is known, and looking the words up one at a
 * time never puts it back together.
 *
 * It is also the safest thing a small model can be asked to do. Rewriting text
 * it was handed is a transformation rather than a recall, the original is on
 * the page beside the answer, and a reader can see for themselves whether the
 * two say the same thing. Nothing here depends on the model knowing anything.
 */
object PlainEnglish {

    /**
     * Longest selection worth rewriting: a long sentence or a short paragraph.
     * Past this the reader has selected something to capture as a card, and a
     * rewrite of half a page would be a summary — which is a different ask, and
     * one a model this size does badly.
     */
    const val MAX_PASSAGE_CHARS = 600

    /** Shortest worth the wait. A few words are a lookup, not a sentence. */
    const val MIN_PASSAGE_CHARS = 12

    /** Longest any single field is kept. */
    const val MAX_FIELD_CHARS = 700

    /** Tokens reserved for the reply: the rewrite, plus a line of hard words. */
    const val REPLY_TOKENS = 384

    /** Why [passage] cannot be rewritten, or null when it can. */
    fun passageProblem(passage: String): String? {
        val trimmed = passage.trim()
        return when {
            trimmed.isBlank() -> "Nothing is selected."
            trimmed.length < MIN_PASSAGE_CHARS ->
                "Select a whole sentence — for a single word, use Explain here."
            trimmed.length > MAX_PASSAGE_CHARS ->
                "That is too long to say again in full. Select a sentence or two."
            else -> null
        }
    }

    fun prompt(passage: String, bookTitle: String): String {
        val text = normalize(passage).take(MAX_PASSAGE_CHARS)
        return """
            |From "$bookTitle":
            |"$text"
            |
            |A reader could not follow that sentence. They may still be learning
            |English. Say the same thing in words they can follow.
            |
            |Fill in these two fields:
            |- plain: the same meaning, in short simple sentences. Keep
            |  everything the original says — you are making it easier to read,
            |  not shorter. Break one long sentence into several. Use the
            |  simplest word that still means the same thing.
            |- words: the one or two hardest words from the original, each with
            |  a two or three word meaning, written as "word = meaning" and
            |  separated by semicolons. Leave it empty if nothing in it is hard.
            |
            |Rules:
            |- Never add a fact that is not in the original.
            |- Never leave a part of it out.
            |- Never explain what it means or why it matters. Just say it again,
            |  more simply.
            |
            |Example:
            |"Notwithstanding the inclement weather, the expedition proceeded."
            |{"plain": "The weather was bad. Even so, the expedition went on.",
            |"words": "notwithstanding = even so; inclement = bad, stormy"}
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"plain": "...", "words": "..."}
            |{"plain": "
            """.trimMargin()
    }

    /**
     * The reply as a rewrite, or null when nothing usable came back.
     *
     * Falls back to treating the whole reply as the rewrite: asked to say a
     * sentence more simply, a model that answers with a bare sentence and no
     * JSON has done the job. That is only safe because the fallback here is a
     * plain restatement rather than a claim about anything — the word lookup
     * needs the stricter test, since prose there could be a definition of
     * something the model made up.
     */
    fun parse(raw: String): PlainParts? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isBlank()) return null

        val answered = FIELDS.any {
            LumenCapture.jsonStringValue(cleaned, it, truncated = true) != null
        }
        if (answered) {
            val plain = field(cleaned, "plain") ?: return null
            return PlainParts(plain = plain, words = field(cleaned, "words"))
        }
        val prose = field(cleaned.trim('{', '}', '"'), null) ?: return null
        return PlainParts(plain = prose, words = null)
    }

    private val FIELDS = listOf("plain", "words")

    /** With [key] null, cleans [source] itself rather than a field inside it. */
    private fun field(source: String, key: String?): String? {
        val value =
            if (key == null) source
            else LumenCapture.jsonStringValue(source, key, truncated = true) ?: return null
        var text = normalize(value).trim('"', '\'', ' ')
        if (text.isBlank()) return null
        text = text.replaceFirstChar { it.uppercaseChar() }
        if (text.length <= MAX_FIELD_CHARS) return text
        val cut = text.take(MAX_FIELD_CHARS).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (cut > MAX_FIELD_CHARS / 2) text.take(cut + 1) else text.take(MAX_FIELD_CHARS).trimEnd() + "…"
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}

/** A passage said again simply, and the hard words in it. */
data class PlainParts(val plain: String, val words: String?)

/** A rewrite of a passage the reader could not follow. */
data class PlainReading(
    val passage: String,
    val parts: PlainParts,
    val source: LlmProviderKind?,
)
