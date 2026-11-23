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

    /**
     * Words, not tokens: a table's `|` and `---` and a heading's `#` are marks,
     * not words, and counting them would let a reply with a lot of formatting
     * pass the length check while missing part of the transcript.
     */
    fun words(text: String): Int =
        text.trim().split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

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
        |You are a professional editor turning a raw transcript into a clean,
        |well-structured document that will be read on a phone. Reply in Markdown.
        |
        |Title: $title
        |
        |CLEAN-UP
        |1. Fix caption artifacts: repeated words, missing punctuation, broken sentences.
        |2. Remove filler words (um, uh, you know) when they add no meaning, and
        |   [Music], [Applause] and other bracket tags.
        |3. Keep the speaker's voice and conversational tone. Do not make it formal.
        |4. Preserve ALL content and meaning. Do not summarize, skip, reorder or invent
        |   anything. The reply should be nearly as long as the transcript.
        |5. If it is a conversation, start each turn with "**Host:**" and "**Guest:**"
        |   (or the names, if they are mentioned). If you cannot tell who is who, use
        |   "**Speaker A:**" and "**Speaker B:**".
        |6. Put paragraphs at natural topic transitions. Keep them short: 2 to 5 sentences.
        |
        |STRUCTURE: use headings so the reader can see how the talk is built
        |- "# " (one only): the talk's overall subject, as the very first line.
        |- "## ": each major part, when the subject clearly changes.
        |- "### ": a distinct topic inside a major part.
        |- "#### " and deeper only if a part is long and truly has a fourth level.
        |- Headings are short (2 to 8 words), name the topic, and are never a full
        |  sentence. Never skip a level (no "###" straight after "#"). Do not put a
        |  heading over every paragraph: a typical hour has 5 to 12 "##" parts.
        |- Put a blank line before and after every heading. No numbering in headings.
        |
        |INLINE FORMATTING (use sparingly, it must stay meaningful)
        |- **Bold**: a key term where the speaker defines it, or a name the talk is
        |  about. Not for whole sentences, and not for more than a few words at a time.
        |- *Italics*: titles of books and films, foreign words, and words the speaker
        |  clearly stresses.
        |- Lists: when the speaker walks through items ("first, second, third", steps,
        |  examples), use "- " bullets, or "1. " if the order matters.
        |- Blockquotes ("> "): only when the speaker reads aloud or quotes a passage.
        |- Tables: only when the speaker compares several things on the same
        |  attributes (for example three options and their costs), as a Markdown table
        |  with a header row, a "| --- | --- |" row and one row per item. The first
        |  column names the item. Keep cells to a few words. Never use a table for
        |  ordinary prose, and never put a conversation in one.
        |
        |DO NOT USE: horizontal rules, HTML, emoji, footnotes, images or links, and
        |nothing that is not in the transcript.
        |
        |OUTPUT: reply with ONLY the formatted Markdown: no preamble, no commentary,
        |no closing remarks, and do not wrap it in a code block.
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
