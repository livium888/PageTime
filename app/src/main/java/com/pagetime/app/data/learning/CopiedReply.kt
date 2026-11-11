package com.pagetime.app.data.learning

/**
 * Whether what is on the clipboard is worth reading as the AI's reply.
 *
 * When the reader comes back from their AI app with the reply copied, the cards
 * sheet picks it up without being asked. That only works if it stays quiet
 * about everything else a clipboard holds: the prompt it copied itself, a link,
 * a text message. Anything that passes here is still checked card by card
 * against the text that was sent; this only decides whether to look.
 */
object CopiedReply {

    /** How much of the sent text is compared to recognise the prompt coming back. */
    private const val PROMPT_PROBE = 160

    fun worthReading(clip: String, sentText: String, alreadyTried: String?): Boolean {
        val text = clip.trim()
        if (text.isEmpty() || text == alreadyTried?.trim()) return false
        // A reply is a list of cards as JSON; without a brace there is none.
        if ('{' !in text) return false
        // The prompt carries the whole stretch that was read. A reply quotes
        // sentences from it, but never opens with a long run of it.
        val probe = sentText.trim().take(PROMPT_PROBE)
        if (probe.length >= 40 && probe in text) return false
        return true
    }
}
