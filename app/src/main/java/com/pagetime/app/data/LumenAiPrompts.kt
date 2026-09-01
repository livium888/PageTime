package com.pagetime.app.data

/**
 * Prompt copy shared by Gemini and the offline model, so both providers draft
 * a Lumen card with the exact same instructions and output shape
 * ({"front": "...", "back": "..."}).
 */
object LumenAiPrompts {
    fun cardDraft(
        passage: String,
        bookTitle: String,
    ): String =
        """
            |You write ONE index card for a reader's personal knowledge box.
            |Book: "$bookTitle"
            |
            |Read the passage and name its single most important idea.
            |
            |Rules:
            |- front: a short title or question naming the idea, at most 8 words,
            |  no quotation marks.
            |- back: 1-2 sentences explaining the idea in your own words, as if
            |  the reader wrote it for their future self.
            |- Do NOT copy sentences from the passage. Use your own words.
            |- If the passage has several ideas, pick the most important one.
            |
            |Example:
            |Passage: The mitochondria is the powerhouse of the cell. It turns
            |nutrients into energy that the cell can use.
            |Card: {"front": "Mitochondria's role", "back": "The mitochondria
            |converts nutrients into usable energy, which is why it is called the
            |powerhouse of the cell."}
            |
            |Reply with ONLY the JSON object, nothing else:
            |{"front": "...", "back": "..."}
            |
            |Passage:
            |$passage
            """.trimMargin()
}
