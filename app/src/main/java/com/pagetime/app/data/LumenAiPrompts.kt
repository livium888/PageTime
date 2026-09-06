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
     * Keeps the END of an over-long passage, because that is where the reader
     * is. Starts on a sentence boundary where one is close by, so the model
     * opens on a whole thought rather than a clipped clause.
     *
     * This used to keep the MIDDLE, on the reasoning that a passage was
     * centred on the reading position. Captures are anchored now — a passage
     * ends at the paragraph the reader pointed at — so the middle is simply
     * the wrong half. When a whole chapter reached this function, keeping the
     * middle handed the model text from halfway through the file: coherent
     * prose about something the reader was nowhere near, which is a far more
     * confusing failure than a truncated passage would have been.
     */
    fun trimPassage(passage: String): String {
        if (passage.length <= MAX_PASSAGE_CHARS) return passage
        val slice = passage.takeLast(MAX_PASSAGE_CHARS)
        // Open on a sentence, but only if one starts early enough that the
        // passage does not lose most of its substance to the search.
        val firstStop = slice.indexOfAny(charArrayOf('.', '!', '?'))
        return if (firstStop in 0 until MAX_PASSAGE_CHARS / 4) {
            slice.substring(firstStop + 1).trim()
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
    /**
     * INSTRUCTIONS FIRST, PASSAGE LAST. This ordering is load-bearing.
     *
     * Every token budget in this stack is an estimate — the app asks LiteRT for
     * 3,072 tokens, and what a given .task bundle actually allows is baked into
     * the file and not reported back. When an input is clamped it is clamped
     * from the END. With the passage first, as this template used to have it,
     * the thing that gets cut is the entire task description: the model is left
     * holding a slab of book text and no instruction, and free-associates. The
     * card then has nothing to do with what a card is supposed to be, which
     * reads as a bad model rather than as a truncated prompt.
     *
     * Ordered this way the same clamp costs the tail of the passage instead —
     * a card about the first half of what was captured, which is a degradation
     * rather than a non-sequitur.
     */
    /**
     * The passage in the worked example, and the card made from it.
     *
     * REVERTED, and the reason is worth more than the change was.
     *
     * This example was replaced with one that demonstrated abstraction: a
     * passage about early printers, and a card reading "New tools imitate what
     * they displace" — no vocabulary in common, the specific thing in and the
     * transferable claim out. The reasoning was sound. The mitochondria card
     * below IS a rewording of its own passage's second sentence, and it does
     * reuse words under a rule forbidding exactly that.
     *
     * Measured on one passage, held constant, it made cards WORSE:
     *
     *   with this example      "Early Homo sapiens migrated vastly from Africa"
     *   with the abstract one  "The relentless drive for territorial control
     *                           led civilizations westward, reshaping
     *                           landscapes and fundamentally altering human
     *                           history."
     *
     * The first is a summary — dull, but specific and true. The second is
     * vague, internally repetitive across all three fields, and factually
     * wrong: those Sapiens went to the Middle East, not westward.
     *
     * WHY, AND THIS IS THE PART TO REMEMBER
     *
     * For a model this small, "abstract" and "vague" are the same direction.
     * A concrete example anchors it to the passage's actual content. An
     * example that demonstrates generalising teaches it to generalise, and
     * what a 1B model produces when it generalises is not insight but
     * adjectives.
     *
     * That is now three prompt interventions measured and three failures — a
     * second worked example, a negative rule, and this. The lever for card
     * quality is not the prompt.
     */
    const val EXAMPLE_PASSAGE: String =
        "The mitochondria is the powerhouse of the cell. It turns nutrients " +
            "into energy that the cell can use."

    const val EXAMPLE_CARD: String =
        """{"front": "Mitochondria convert nutrients into usable energy", """ +
            """"idea": "A cell cannot spend nutrients in the form they arrive in.", """ +
            """"because": "They are converted into a currency it can spend, which """ +
            """is why the organelle is called the cell's powerhouse."}"""

    val DEFAULT_CARD_TEMPLATE: String =
        """
            |Write one permanent note about the passage below, the way a slip
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
            |Passage: $EXAMPLE_PASSAGE
            |Card: $EXAMPLE_CARD
            |
            |Book: "$BOOK_TOKEN"
            |
            |Passage:
            |$PASSAGE_TOKEN
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
            |Name the single most important idea in the passage below, in your
            |own words. front is that idea as a claim, at most 8 words, never a
            |bare topic. idea is one sentence saying what it is. because is one
            |sentence saying why it holds, and is never empty. Never copy the
            |passage. Never mention the book or the text — the note stands
            |alone.
            |
            |Book: "$bookTitle"
            |
            |Passage:
            |${trimPassage(passage)}
            |
            |Reply with ONLY this JSON:
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
            |You have already written this note: "$alreadyFiled"
            |
            |Find a DIFFERENT idea in the passage below. Not that one, and not a
            |rewording of it. front is the new idea as a claim, at most 8 words,
            |never a bare topic. idea is one sentence saying what it is. because
            |is one sentence saying why it holds, and is never empty. Never copy
            |the passage. Never mention the book or the text.
            |
            |Book: "$bookTitle"
            |
            |Passage:
            |${trimPassage(passage)}
            |
            |Reply with ONLY this JSON:
            |{"front": "...", "idea": "...", "because": "..."}
            |{"front": "
            """.trimMargin()
}
