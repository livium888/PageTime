package com.pagetime.app.data

/**
 * Prompt copy for the on-device LLM's Lumen card capture. Both attempts share
 * the same output shape ({"front": "...", "back": "..."}) so the tolerant
 * parser in [LumenCapture.parseDraft] handles either one.
 *
 * Small models weigh the most recent tokens most, so the passage comes FIRST
 * and the instruction LAST, and the reply is primed with the opening of the
 * expected JSON so the model completes it instead of continuing the book text.
 *
 * What the prompts ask for is a permanent note in Luhmann's sense, because that
 * is what makes a slip box worth keeping:
 *  - One idea per note. A note that holds two thoughts can be filed behind
 *    neither of them.
 *  - Written in the reader's own words. A copied sentence is a photocopy, not
 *    a thought, and it teaches nothing when it resurfaces.
 *  - Standing on its own. The note has to make sense years later with the book
 *    long forgotten, so it never says "the passage" or "the author" — the
 *    source is already kept beside the card, in the quote and the locator.
 *  - Stated as a claim, not a topic. "Fiction lets strangers cooperate" can be
 *    agreed with, argued against and linked to; "Fiction" can only be filed.
 *
 * The worked example carries more of this than the rules do — a small model
 * imitates a sample far more reliably than it follows an instruction — so the
 * example's front is deliberately a claim rather than a label.
 *
 * The note comes back as two fields, "idea" and "because", rather than one
 * "back" of two sentences. Asking a 1B model for a length is asking it to
 * count, which it does not do; asking it to fill a named slot is asking it to
 * continue a pattern, which is the only thing it does. The rule "write two
 * sentences" produced one sentence for as long as it was a rule. The schema is
 * what makes the second one arrive, and [LumenCapture.parseDraft] joins them
 * back into the note. A single "back" is still accepted, so a prompt a reader
 * tailored before this keeps working.
 */
object LumenAiPrompts {
    /**
     * Longest passage a capture prompt may carry. The on-device runtime spends
     * one token budget on input and output together, so an unbounded passage
     * pushes the input past that budget — where MediaPipe aborts the process
     * instead of returning an error. Keeping the passage bounded keeps every
     * normal capture inside the budget with room for the reply.
     *
     * Sized to leave real headroom rather than to spend the budget: at 3,072
     * tokens a capture has about 9,400 characters of input, and this cap plus
     * the instructions asks for well under three quarters of it. The margin is
     * the point — the estimate is a character count, not a tokeniser, and the
     * penalty for being wrong is the process rather than the answer.
     */
    const val MAX_PASSAGE_CHARS = 5_400

    /**
     * The passage is centered on the reading position, so when it has to be
     * shortened the middle is what the reader is actually looking at. Ends on
     * a sentence boundary where one is close by, so the model sees whole
     * thoughts rather than a clipped clause.
     */
    fun trimPassage(passage: String): String {
        if (passage.length <= MAX_PASSAGE_CHARS) return passage
        val start = (passage.length - MAX_PASSAGE_CHARS) / 2
        val slice = passage.substring(start, start + MAX_PASSAGE_CHARS)
        val lastStop = slice.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastStop >= MAX_PASSAGE_CHARS / 2) {
            slice.take(lastStop + 1).trim()
        } else {
            slice.trim()
        }
    }

    /** Placeholder replaced with the captured passage, after trimming. */
    const val PASSAGE_TOKEN = "{{passage}}"

    /** Placeholder replaced with the book's title. */
    const val BOOK_TOKEN = "{{book}}"

    /**
     * The prompt shipped with the app, and the text a reader edits when they
     * tailor capture in Settings.
     */
    val DEFAULT_CARD_TEMPLATE: String =
        """
            |Book: "$BOOK_TOKEN"
            |
            |Passage:
            |$PASSAGE_TOKEN
            |
            |Write one permanent note about the passage above, the way a slip
            |box keeps a thought: a single idea, in your own words, that still
            |makes sense years from now with the book long forgotten.
            |
            |Rules:
            |- front: the idea stated as a claim, at most 8 words. A claim, not
            |  a topic label: "Fiction lets strangers cooperate", never
            |  "Fiction".
            |- idea: ONE sentence saying what the idea is, as your own thought
            |  rather than a report about a book.
            |- because: ONE sentence saying why it holds, or what follows from
            |  it. Never leave this empty.
            |- Never copy a phrase from the passage.
            |- Never mention the passage, the book, the author, or "the text".
            |  The note has to stand on its own.
            |- One idea only. If the passage holds several, take the one that
            |  matters most.
            |
            |Example:
            |Passage: The mitochondria is the powerhouse of the cell. It turns
            |nutrients into energy that the cell can use.
            |Card: {"front": "Mitochondria convert nutrients into usable energy",
            |"idea": "A cell cannot spend nutrients in the form they arrive in.",
            |"because": "They are converted into a currency it can spend, which
            |is why the organelle is called the cell's powerhouse."}
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"front": "...", "idea": "...", "because": "..."}
            |{"front": "
            """.trimMargin()

    /**
     * Fills a template's placeholders. The passage is trimmed here rather than
     * by the caller, so a hand-written template is bounded by the same cap as
     * the built-in one and cannot push the prompt past the model's budget.
     */
    fun render(template: String, passage: String, bookTitle: String): String =
        template
            .replace(BOOK_TOKEN, bookTitle)
            .replace(PASSAGE_TOKEN, trimPassage(passage))

    /**
     * Why [template] cannot be used, or null when it is fine. A template
     * without the passage placeholder would send the model instructions about
     * a passage it never sees, so that one is refused rather than warned about.
     */
    fun templateProblem(template: String): String? =
        when {
            template.isBlank() -> "The prompt is empty."
            !template.contains(PASSAGE_TOKEN) ->
                "The prompt must contain $PASSAGE_TOKEN, or the model never sees the passage."
            else -> null
        }

    /**
     * Tokens [template] would cost on the widest capture the reader can make.
     * Compared against [LlmTokenBudget.inputBudget] this is what says whether a
     * tailored prompt still leaves the model room to answer.
     */
    fun worstCaseTokens(template: String): Int =
        LlmTokenBudget.estimateTokens(
            render(template, "w".repeat(MAX_PASSAGE_CHARS), "A Reasonably Long Book Title")
        )

    fun cardDraft(
        passage: String,
        bookTitle: String,
        template: String = DEFAULT_CARD_TEMPLATE,
    ): String = render(template, passage, bookTitle)

    /**
     * Shorter, starker second attempt used when the first reply was unusable.
     * Fewer instructions means more of the budget goes to the card itself, and
     * a small model that ignored the long rules sometimes obeys the short ones.
     */
    fun cardDraftStrict(
        passage: String,
        bookTitle: String,
    ): String =
        """
            |Book: "$bookTitle"
            |
            |Passage:
            |${trimPassage(passage)}
            |
            |Name the single most important idea in your own words. front is
            |that idea as a claim, at most 8 words, never a bare topic. idea is
            |one sentence saying what it is. because is one sentence saying why
            |it holds, and is never empty. Never copy the passage. Never mention
            |the book or the text — the note stands alone. Reply with ONLY this
            |JSON:
            |{"front": "...", "idea": "...", "because": "..."}
            |{"front": "
            """.trimMargin()

    /**
     * The retry used when the first card repeated one the reader already has.
     * The passage a capture carries is wider than a page, so two captures near
     * each other legitimately contain the same dominant idea; naming it and
     * asking for a different one is the only way to get a second card that is
     * worth filing. Stated as the idea to avoid rather than a rule about
     * repetition, which a small model can act on.
     */
    fun cardDraftDifferent(
        passage: String,
        bookTitle: String,
        alreadyFiled: String,
    ): String =
        """
            |Book: "$bookTitle"
            |
            |Passage:
            |${trimPassage(passage)}
            |
            |You have already written this note: "$alreadyFiled"
            |
            |Find a DIFFERENT idea in the passage. Not that one, and not a
            |rewording of it. front is the new idea as a claim, at most 8 words,
            |never a bare topic. idea is one sentence saying what it is. because
            |is one sentence saying why it holds, and is never empty. Never copy
            |the passage. Never mention the book or the text. Reply with ONLY
            |this JSON:
            |{"front": "...", "idea": "...", "because": "..."}
            |{"front": "
            """.trimMargin()
}
