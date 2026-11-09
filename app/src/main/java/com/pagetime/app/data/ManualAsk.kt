package com.pagetime.app.data

/**
 * A question for the reader's own AI app, and how to read its reply.
 *
 * PageTime never calls a model. A feature that needs one builds the [prompt],
 * the reader takes it to whichever AI app they use (copy or share), and pastes
 * the reply back. [read] turns that reply into the feature's result, or fails
 * with a message the reader can act on.
 *
 * Both halves are plain values, so each feature's prompt and parser can be
 * tested without a screen, a clipboard or a network.
 */
class ManualAsk<T>(
    /** What the dialog is titled, e.g. the word being explained. */
    val heading: String,
    val prompt: String,
    val read: (reply: String) -> Result<T>,
) {
    fun <R> map(transform: (T) -> R): ManualAsk<R> =
        ManualAsk(heading, prompt) { reply -> read(reply).map(transform) }
}
