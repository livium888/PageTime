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
            |You capture index cards for a reader's personal knowledge system.
            |Book: "$bookTitle"
            |
            |Below is a passage the reader highlighted in the act of reading.
            |Write ONE index card for it:
            |
            |1. "front": a sharp title or question naming the single core idea
            |   (max 12 words, no quotes around it).
            |2. "back": 1-2 sentences explaining the idea in plain words, as if
            |   the reader wrote it for their future self. Never copy the passage.
            |
            |If the passage contains clearly separable ideas, pick the most
            |prominent one — one card, one idea.
            |
            |Respond with ONLY a JSON object: {"front": "...", "back": "..."}
            |
            |Passage:
            |$passage
            """.trimMargin()
}
