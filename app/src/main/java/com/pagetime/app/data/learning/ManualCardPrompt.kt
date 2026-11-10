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
     * The most cards to ask for, from the length of what was read and how many
     * the reader wants. See [CardDensity].
     */
    fun cardTarget(words: Int, density: CardDensity = CardDensity.DEFAULT): Int =
        density.target(words)

    fun build(
        bookTitle: String,
        chapterLabel: String,
        span: String,
        template: String? = null,
        density: CardDensity = CardDensity.DEFAULT,
    ): String {
        val target = cardTarget(ReadingSpan.words(span), density)
        val instructions = ChapterPromptText.build(
            bookTitle = bookTitle,
            chapterTitle = chapterLabel,
            passages = listOf(span.trim()),
            perPassage = target,
            insist = true,
            template = template,
            howManyText = howMany(target),
        )
        return instructions.trimEnd() + "\n\n" + REPLY_FORMAT
    }

    /**
     * Quality before quantity, in words a model will follow.
     *
     * The wording for hand-picked passages elsewhere says "write at least one
     * for every passage and do not skip a thin one", which is right for a
     * paragraph a person underlined and wrong for a stretch of several pages:
     * it asks for filler. Here the reader chose a stretch, not each idea in
     * it, so choosing the ideas is the model's job — and doing that well means
     * leaving most of what was read without a card.
     */
    internal fun howMany(target: Int): String =
        """
            HOW MANY CARDS

            Write AT MOST $target cards, and fewer is better. Write a card only
            for something the reader would still want to know in a year: the
            ideas the author's argument depends on, definitions that later
            ideas build on, causes and consequences that explain why things
            happened. Leave out examples, anecdotes, names, dates and numbers
            unless the point depends on them.

            If only two ideas deserve a card, return two. Never add cards to
            reach the maximum. A reader who has to untick weak cards has been
            given more work, not more value.
        """.trimIndent()

    /** What the reader sees explaining the reply, for the paste step. */
    const val REPLY_HINT =
        "Copy the model's whole reply, code block and all. Extra text around it is fine."

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
