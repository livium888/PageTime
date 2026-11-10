package com.pagetime.app.data

import kotlin.math.ceil

/**
 * How large a capture prompt is allowed to be.
 *
 * This began as the token budget of an on-device model, where an oversized
 * prompt aborted the whole process. There is no on-device model any more, but
 * the bound stays useful: a prompt is carried through a clipboard into a chat
 * box, and a passage far beyond a page is a capture that was not meant. Prompt
 * templates are checked against it so an edit cannot make every capture too
 * large to send. The arithmetic is unchanged, so the tests that pin it still
 * hold.
 */
object LlmTokenBudget {
    /**
     * Input and output share one budget of this many tokens.
     *
     * 3,072 leaves room for a passage of about a page plus the instructions,
     * and for the two asks that carry two texts at once rather than one:
     * explain-back, which holds the source and the reader's own explanation
     * together, and the plain-English rewrite.
     */
    const val MAX_TOKENS = 3_072

    /** English runs ~4 chars/token; 3.5 keeps the estimate conservative. */
    private const val CHARS_PER_TOKEN = 3.5

    /**
     * Cheap upper bound on the tokens [text] occupies. Deliberately
     * pessimistic: better to call a prompt too large than to let one through.
     */
    fun estimateTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()

    /** Tokens left for the prompt once [maxOutputTokens] are reserved for the reply. */
    fun inputBudget(maxOutputTokens: Int): Int = MAX_TOKENS - maxOutputTokens

    fun fits(prompt: String, maxOutputTokens: Int): Boolean =
        estimateTokens(prompt) <= inputBudget(maxOutputTokens)
}
