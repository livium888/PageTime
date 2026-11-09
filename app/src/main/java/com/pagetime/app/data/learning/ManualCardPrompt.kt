package com.pagetime.app.data.learning

/**
 * The message the reader sends to their own chat model: the card-writing rules,
 * the text they read, and the shape of the reply.
 *
 * THE RULES ARE NOT REWRITTEN HERE
 *
 * [ChapterPromptText] already holds the card-writing instructions, tuned
 * against the checks in [ChapterPromptRules] (one idea per card, no lists, no
 * answers inside questions, a verbatim source quote). Writing a second set for
 * the manual flow would mean two sets drifting apart, and the checks would
 * quietly reject cards the new wording invited. This builds on the same text,
 * including a reader's own edit of it in Settings.
 *
 * WHAT IS DIFFERENT FROM THE GEMINI REQUEST
 *
 * Gemini is held to a JSON schema by the API. A chat window is not, so the
 * reply format has to be spelled out and, because the reply will be copied off
 * a phone screen, made easy to copy: one code block, nothing outside it.
 *
 * The span is sent as ONE numbered passage. The reader chose it, so every part
 * of it is fair game, and a single passage means a single place for the quote
 * check to look.
 */
object ManualCardPrompt {

    /**
     * How many cards to ask for: about one idea per 150 words, never fewer
     * than three or more than twenty. A reply much longer than that is where a
     * chat model starts to repeat itself or run out of room.
     */
    fun cardTarget(words: Int): Int = (words / WORDS_PER_CARD).coerceIn(MIN_CARDS, MAX_CARDS)

    fun build(
        bookTitle: String,
        chapterLabel: String,
        span: String,
        template: String? = null,
    ): String {
        val target = cardTarget(ReadingSpan.words(span))
        val instructions = ChapterPromptText.build(
            bookTitle = bookTitle,
            chapterTitle = chapterLabel,
            passages = listOf(span.trim()),
            perPassage = target,
            // The reader chose this span, so omitting is the wrong default.
            insist = true,
            template = template,
        )
        return instructions.trimEnd() + "\n\n" + REPLY_FORMAT
    }

    /** What the reader sees explaining the reply, for the paste step. */
    const val REPLY_HINT =
        "Copy the model's whole reply, code block and all. Extra text around it is fine."

    private const val WORDS_PER_CARD = 150
    private const val MIN_CARDS = 3
    private const val MAX_CARDS = 20

    /**
     * Appended after everything else, because instructions placed last are the
     * ones a model follows most reliably.
     */
    internal val REPLY_FORMAT: String =
        """
            HOW TO REPLY

            Reply with exactly ONE JSON code block and nothing outside it, in
            this shape:

            ```json
            {"prompts":[
              {"passageIndex":0,"type":"qa","prompt":"...","answer":"...","explanation":"...","sourceQuote":"..."}
            ]}
            ```

            - Use straight double quotes (") for the JSON itself. Escape any
              double quote inside a value as \".
            - passageIndex is always 0.
            - For a cloze card use "type":"cloze". Put {{c1::the hidden words}}
              inside prompt, make answer exactly the hidden words, and make
              sourceQuote the whole sentence with nothing hidden.
            - Every sourceQuote must be copied from the passage character for
              character. A card whose quote is not in the passage is discarded.
        """.trimIndent()
}
