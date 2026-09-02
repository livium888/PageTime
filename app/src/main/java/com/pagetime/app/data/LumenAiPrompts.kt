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
    /**
     * Longest passage a capture prompt may carry. The on-device runtime spends
     * one token budget on input and output together, so an unbounded passage
     * pushes the input past that budget — where MediaPipe aborts the process
     * instead of returning an error. Keeping the passage bounded keeps every
     * normal capture inside the budget with room for the reply.
     */
    const val MAX_PASSAGE_CHARS = 2_400

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

    fun cardDraft(
        passage: String,
        bookTitle: String,
    ): String =
        """
            |Book: "$bookTitle"
            |
            |Passage:
            |${trimPassage(passage)}
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
            |${trimPassage(passage)}
            |
            |Name the single most important idea in your own words. Never copy
            |the passage. front is a short title (at most 8 words). back is
            |1-2 sentences explaining the idea. Reply with ONLY this JSON:
            |{"front": "...", "back": "..."}
            |{"front": "
            """.trimMargin()
}
