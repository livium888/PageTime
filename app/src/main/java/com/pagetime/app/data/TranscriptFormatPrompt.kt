package com.pagetime.app.data

/**
 * Tidying a raw transcript with the reader's own AI app: the prompt to take
 * there, and a check on the reply before it replaces the text.
 *
 * THE REPLY IS CHECKED FOR LENGTH BECAUSE IT REPLACES THE BOOK
 *
 * Formatting legitimately removes filler words and caption noise, so the reply
 * is shorter than the transcript. But a chat app with a reply limit stops
 * mid-transcript, and one that misreads the request returns a summary; either
 * would silently replace an hour of text with a page of it. A reply under
 * [MIN_KEPT_FRACTION] of the original's words is refused and the original
 * kept.
 */
object TranscriptFormatPrompt {

    /** Over this, one prompt is more than a chat app will format in one reply. */
    const val MAX_WORDS = 12_000

    /** A formatted transcript keeps at least this share of the original's words. */
    const val MIN_KEPT_FRACTION = 0.6

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Why [raw] cannot be sent, or null when it can. */
    fun problem(raw: String): String? {
        val count = words(raw)
        return when {
            count == 0 -> "This book has no text to format."
            count > MAX_WORDS ->
                "This is about $count words, too long for an AI app to format in one reply " +
                    "(about $MAX_WORDS at most)."
            else -> null
        }
    }

    fun build(title: String, raw: String): String = """
        |You are a professional editor formatting a raw transcript into a clean,
        |readable book-like document.
        |
        |Title: $title
        |
        |RULES:
        |1. Fix caption artifacts: repeated words, missing punctuation, broken sentences.
        |2. If it is a conversation, label speaker turns with "**Host:**" and "**Guest:**"
        |   (or names if they are mentioned). If you cannot tell who is who, use
        |   "**Speaker A:**" and "**Speaker B:**".
        |3. Organize into clear paragraphs at natural topic transitions.
        |4. Add a topic heading when the subject changes, as a single line starting with "# ".
        |5. Remove filler words (um, uh, you know) when they add no meaning.
        |6. Keep the conversational tone. Do not make it formal.
        |7. Preserve ALL content and meaning. Do not summarize, skip, reorder or invent anything.
        |8. Remove [Music], [Applause] and other bracket tags.
        |9. Reply with ONLY the formatted text: no preamble, no commentary, no code block.
        |
        |Here is the raw transcript:
        |
        |${raw.trim()}
    """.trimMargin()

    /** The formatted text in [reply], or why it cannot replace [raw]. */
    fun accept(reply: String, raw: String): Result<String> {
        val text = stripFence(reply).trim()
        if (text.isEmpty()) {
            return Result.failure(IllegalStateException("That reply is empty. Copy the AI's whole reply and try again."))
        }
        val kept = words(text).toDouble() / words(raw).coerceAtLeast(1)
        if (kept < MIN_KEPT_FRACTION) {
            return Result.failure(
                IllegalStateException(
                    "That reply is much shorter than the transcript, so it is a summary or was cut off. " +
                        "The original was kept. Ask your AI to continue, then copy the whole thing."
                )
            )
        }
        return Result.success(text)
    }

    /** Drops one surrounding ``` fence, if the whole reply sits inside it. */
    private fun stripFence(reply: String): String {
        val trimmed = reply.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.length < 6) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return trimmed
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3)
    }
}
