package com.pagetime.app.data.learning

/**
 * A fill-in-the-blank card made by hand from a sentence the reader highlighted.
 *
 * The reader taps the words to hide. No model is involved: choosing which word
 * carries the idea is the reader's own judgement of what is worth remembering,
 * and it works with no AI app and no connection.
 *
 * Pure, so the rules for what gets hidden can be tested without a screen.
 */
object ClozeBuilder {

    /** More words than this is a passage, not a sentence to blank a word of. */
    const val MAX_WORDS = 120

    private val SPACE = Regex("\\s+")

    /** The quote as the words the reader can tap, at most [MAX_WORDS] of them. */
    fun words(quote: String): List<String> =
        quote.trim().split(SPACE).filter { it.isNotEmpty() }.take(MAX_WORDS)

    /**
     * The stored card text, with each run of chosen words as one deletion.
     *
     * Words next to each other are hidden together, since a term is often more
     * than one word. Punctuation around a hidden word stays visible: the gap is
     * the term, not its comma. Null when nothing is chosen, or everything is,
     * because a sentence with no gap asks nothing and one that is all gap gives
     * the reader nothing to answer from.
     */
    fun build(words: List<String>, chosen: Set<Int>): String? {
        val picked = chosen.filter { it in words.indices }.toSortedSet()
        if (picked.isEmpty() || picked.size == words.size) return null
        val out = StringBuilder()
        var number = 0
        var i = 0
        while (i < words.size) {
            if (out.isNotEmpty()) out.append(' ')
            if (i !in picked) {
                out.append(words[i])
                i++
                continue
            }
            var j = i
            while (j + 1 < words.size && j + 1 in picked) j++
            val run = words.subList(i, j + 1).joinToString(" ")
            val lead = run.takeWhile { !it.isLetterOrDigit() }
            val trail = run.takeLastWhile { !it.isLetterOrDigit() }
            val core = run.substring(lead.length, (run.length - trail.length).coerceAtLeast(lead.length))
            if (core.isEmpty()) {
                // Only punctuation was tapped; there is nothing to hide.
                out.append(run)
            } else {
                number++
                out.append(lead).append("{{c").append(number).append("::").append(core).append("}}").append(trail)
            }
            i = j + 1
        }
        return out.toString().takeIf { number > 0 }
    }

    /** What the card's answer is: the hidden words, in order. */
    fun answer(cloze: String): String = ClozeText.deletions(cloze).joinToString(", ")
}
