package com.pagetime.app.data

/**
 * Prompt copy for the on-device LLM's Lumen card capture. Both attempts share
 * the same output shape ({"front": "...", "back": "..."}) so the tolerant
 * parser in [LumenCapture.parseDraft] handles either one.
 *
 * Small models weigh the most recent tokens most, so the passage comes FIRST
 * and the instruction LAST, and the reply is primed with the opening of the
 * expected JSON so the model completes it instead of continuing the book text.
 */
object LumenAiPrompts {
    fun cardDraft(
        passage: String,
        bookTitle: String,
    ): String =
        """
            |Book: "$bookTitle"
            |
            |Passage:
            |$passage
            |
            |Above is a passage from the book. Name its single most important
            |idea in your own words. Do NOT copy any sentence from the passage.
            |
            |Rules:
            |- front: a short title or question naming the idea, at most 8
            |  words, no quotation marks.
            |- back: 1-2 sentences explaining the idea in your own words, as
            |  if the reader wrote it for their future self.
            |- If the passage has several ideas, pick the most important one.
            |
            |Example:
            |Passage: The mitochondria is the powerhouse of the cell. It turns
            |nutrients into energy that the cell can use.
            |Card: {"front": "Mitochondria's role", "back": "The mitochondria
            |converts nutrients into usable energy, which is why it is called
            |the powerhouse of the cell."}
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"front": "...", "back": "..."}
            |{"front": "
            """.trimMargin()

    /**
     * Shorter, starker second attempt used when the first reply was unusable
     * (unparseable or a verbatim copy of the passage). Fewer instructions
     * means more of the output budget goes to the card itself.
     */
    fun cardDraftStrict(
        passage: String,
        bookTitle: String,
    ): String =
        """
            |Book: "$bookTitle"
            |
            |Passage:
            |$passage
            |
            |Name the single most important idea in your own words. Never copy
            |the passage. front is a short title (at most 8 words). back is
            |1-2 sentences explaining the idea. Reply with ONLY this JSON:
            |{"front": "...", "back": "..."}
            |{"front": "
            """.trimMargin()
}
