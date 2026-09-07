package com.pagetime.app.data.learning

/**
 * The instructions sent to the model for one chapter.
 *
 * WHY THIS IS ITS OWN FILE
 *
 * It used to be a raw string inline in the client, and it shipped with every
 * template marker escaped as a LITERAL dollar sign — so the model was sent
 * "BOOK: $bookTitle" and, worse, "PASSAGES: $numbered" with no passages in it
 * at all. The request never carried the book. Nothing could catch that, because
 * a prompt built inside a suspend function behind a network call is not
 * reachable by any test.
 *
 * Pulled out here it is a pure function over its inputs, and the test can
 * simply assert that the passages are in it.
 */
internal object ChapterPromptText {

    fun build(bookTitle: String, chapterTitle: String, passages: List<String>): String {
        val numbered = passages.mapIndexed { index, text ->
            "[$index]\n$text"
        }.joinToString("\n\n")

        return """
            Write ONE recall question for each numbered passage below, from a book
            the reader is part-way through.

            Each question must:
            - ask about the WORLD, not about the text. Never "what does this passage
              say", "what does the author argue", or any question that stops making
              sense once the book is closed.
            - test ONE idea. If a passage holds two, pick the more important one.
            - be answerable in a few words. An answer longer than a sentence is not
              something anyone recalls.
            - require remembering rather than recognising. Do not put the answer, or
              a near-synonym of it, into the question.
            - use the book's own vocabulary for the things it names.

            sourceQuote must be copied from that passage CHARACTER FOR CHARACTER —
            the sentence the answer comes from. Do not paraphrase it, shorten it, or
            tidy it. A quote that is not literally in the passage causes the whole
            prompt to be discarded.

            passageIndex is the number in brackets above the passage you used.

            If a passage carries no idea worth remembering — it is scene-setting,
            a transition, or pure narrative — omit it entirely. Returning four good
            prompts is better than five with a weak one.

            BOOK: $bookTitle
            CHAPTER: $chapterTitle

            PASSAGES:
            $numbered
        """.trimIndent()
    }
}
