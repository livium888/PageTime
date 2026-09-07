package com.pagetime.app.data.embed

import kotlin.math.floor

/**
 * Cutting a book into pieces small enough to embed and large enough to mean
 * something.
 *
 * THE TRAP THIS EXISTS TO CLOSE
 *
 * [WordPieceTokenizer.encode] truncates at the token limit it is given, and
 * [OnnxTextEmbedder] asks for 128. Hand it a 1,200-character paragraph and it
 * embeds the first four hundred characters and silently discards the rest. No
 * exception, no warning: a vector of the right shape describing a third of the
 * text, and two thirds of the book simply absent from search forever.
 *
 * So the chunk budget is DERIVED from the embedder's token limit rather than
 * chosen to look tidy. If that limit changes, this changes with it.
 *
 * THE OTHER HALF: TOO SMALL IS ALSO WRONG
 *
 * A line of dialogue — "Yes," he said — is a paragraph, and on its own it
 * carries no retrievable meaning at all. A novel would otherwise yield
 * thousands of vectors that match everything weakly and nothing usefully. Short
 * paragraphs are merged with their neighbours until they are worth a vector.
 *
 * WHY SENTENCES ARE THE UNIT THAT MOVES
 *
 * Paragraph boundaries are where meaning changes, so chunks prefer to end at
 * one. But a paragraph longer than the budget has to be split somewhere, and
 * splitting mid-sentence produces a vector for half a thought. Sentences are
 * the largest unit that always fits, so they are what gets packed.
 */
object BookTextChunker {

    /**
     * A piece of a chapter, with where it came from.
     *
     * [start] and [end] index the chapter text the chunk was cut from, so a
     * search result can send the reader back to the exact spot rather than to
     * the chapter's beginning.
     */
    data class Chunk(val text: String, val start: Int, val end: Int)

    /**
     * Characters per token, matching [com.pagetime.app.data.LlmTokenBudget].
     * Deliberately pessimistic: over-estimating costs a slightly smaller chunk,
     * under-estimating costs silent truncation, which is the whole problem.
     */
    private const val CHARS_PER_TOKEN = 3.5

    /**
     * Room left for the tokenizer's own [CLS] and [SEP], plus the slack that
     * keeps an unusual word — a long name, a number, a foreign term, each of
     * which costs more tokens than its characters suggest — from pushing a
     * chunk over the edge.
     */
    private const val TOKEN_HEADROOM = 0.85

    /**
     * The largest chunk that survives [tokenBudget] tokens without truncation.
     *
     * At the embedder's default of 128 this is 380 characters, which is a long
     * paragraph or two short ones.
     */
    fun maxCharsFor(tokenBudget: Int): Int =
        floor(tokenBudget * CHARS_PER_TOKEN * TOKEN_HEADROOM).toInt()

    /**
     * Below this a chunk is not worth a vector of its own and is merged with
     * what follows. Roughly a sentence and a half.
     */
    const val MIN_CHARS = 120

    /**
     * How much of the previous chunk a split paragraph repeats.
     *
     * A sentence that lands exactly on a boundary belongs to neither side
     * without this, and is then findable by nothing — the reader searches for
     * the one idea they remember and the app has no vector containing it.
     */
    const val OVERLAP_CHARS = 80

    /**
     * Cuts [text] into embeddable pieces.
     *
     * Nothing is discarded: every character of the input appears in at least
     * one chunk. That is the property worth testing, because losing a
     * paragraph here removes it from search with nothing anywhere to say so.
     */
    fun chunk(
        text: String,
        maxChars: Int = maxCharsFor(128),
        minChars: Int = MIN_CHARS,
        overlapChars: Int = OVERLAP_CHARS,
    ): List<Chunk> {
        require(maxChars > minChars) {
            "A chunk budget of $maxChars cannot hold the $minChars-character minimum."
        }
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        var bufferStart = -1
        var bufferEnd = -1

        fun flush() {
            if (bufferStart < 0) return
            val piece = text.substring(bufferStart, bufferEnd)
            if (piece.isNotBlank()) chunks += Chunk(piece.trim(), bufferStart, bufferEnd)
            bufferStart = -1
            bufferEnd = -1
        }

        for ((paraStart, paraEnd) in paragraphRanges(text)) {
            val paragraphLength = paraEnd - paraStart

            // A paragraph that fits: keep it whole where possible, and only
            // join it to the buffer when what is buffered is still too small to
            // stand alone. Merging beyond that would blur two ideas together.
            if (paragraphLength <= maxChars) {
                val buffered = if (bufferStart < 0) 0 else bufferEnd - bufferStart
                val joinable = buffered in 1 until minChars &&
                    paraEnd - bufferStart <= maxChars
                if (bufferStart < 0) {
                    bufferStart = paraStart
                    bufferEnd = paraEnd
                } else if (joinable) {
                    bufferEnd = paraEnd
                } else {
                    flush()
                    bufferStart = paraStart
                    bufferEnd = paraEnd
                }
                if (bufferEnd - bufferStart >= minChars) flush()
                continue
            }

            // A paragraph too long to embed. Anything already buffered ends
            // here, then the paragraph is packed sentence by sentence.
            flush()
            chunks += splitLongParagraph(text, paraStart, paraEnd, maxChars, overlapChars)
        }
        flush()
        return chunks
    }

    /**
     * Packs one over-long paragraph into overlapping chunks at sentence
     * boundaries, falling back to a hard cut for a sentence that is itself
     * longer than the budget — which does happen, in older prose especially.
     */
    private fun splitLongParagraph(
        text: String,
        from: Int,
        to: Int,
        maxChars: Int,
        overlapChars: Int,
    ): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var cursor = from
        while (cursor < to) {
            val limit = minOf(cursor + maxChars, to)
            val cut =
                if (limit >= to) to
                else lastSentenceEnd(text, cursor, limit) ?: lastSpace(text, cursor, limit) ?: limit
            val piece = text.substring(cursor, cut)
            if (piece.isNotBlank()) out += Chunk(piece.trim(), cursor, cut)
            if (cut >= to) break
            // Step back by the overlap so a sentence on the boundary lives in
            // both neighbours, never in neither. Never step back past the start
            // of what was just emitted, or this loops forever.
            cursor = maxOf(cut - overlapChars, cursor + 1)
        }
        return out
    }

    /** Paragraph ranges, blank-line separated, skipping empty runs. */
    private fun paragraphRanges(text: String): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < text.length) {
            val breakAt = text.indexOf("\n\n", index)
            val end = if (breakAt < 0) text.length else breakAt
            if (text.substring(index, end).isNotBlank()) ranges += index to end
            if (breakAt < 0) break
            index = breakAt + 2
            while (index < text.length && text[index] == '\n') index++
        }
        return ranges
    }

    /** End of the last sentence that finishes at or before [limit], or null. */
    private fun lastSentenceEnd(text: String, from: Int, limit: Int): Int? {
        var best: Int? = null
        var i = from
        while (i < limit - 1) {
            if (text[i] in SENTENCE_ENDINGS && text[i + 1].isWhitespace()) best = i + 1
            i++
        }
        return best?.takeIf { it > from }
    }

    private fun lastSpace(text: String, from: Int, limit: Int): Int? {
        var i = limit - 1
        while (i > from) {
            if (text[i].isWhitespace()) return i
            i--
        }
        return null
    }

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?')
}
