package com.pagetime.app.data

/**
 * The one prompt [LibrarianSuggester] sends, and the only thing an AI is
 * ever asked to do here: reword an already-true sentence more warmly.
 * Structurally, not just by instruction, this keeps the AI from being the
 * one deciding what's true — it's a stylist, not a source.
 */
object LibrarianPrompts {
    fun rephrase(fact: String): String = """
        |You are a warm, low-key librarian talking to a reader you know well.
        |Reword the sentence below in your own voice — warmer and more
        |personal — without changing what it says or adding any new fact,
        |reason, or claim that isn't already in it. Keep it to one short
        |sentence, no more than 25 words.
        |
        |Sentence: $fact
        |
        |Reply with ONLY your reworded sentence, nothing else.
    """.trimMargin()
}
