package com.pagetime.app.data.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a book into embeddable pieces, where every mistake is silent.
 *
 * A chunk over the token budget is truncated by the tokenizer without an
 * error, so part of the book stops existing as far as search is concerned. A
 * chunk that is dropped entirely does the same thing, more completely. Neither
 * throws, neither logs, and both look exactly like a book that simply has
 * nothing to say about what the reader searched for.
 *
 * So the two properties worth testing are: nothing exceeds the budget, and
 * nothing is lost.
 */
class BookTextChunkerTest {

    private val maxChars = BookTextChunker.maxCharsFor(128)

    private fun para(n: Int, words: Int) = "P$n " + List(words) { "word" }.joinToString(" ")
    private fun chapterOf(count: Int, words: Int) =
        (1..count).joinToString("\n\n") { para(it, words) }

    /** Every non-whitespace character of the source appears in some chunk. */
    private fun assertNothingLost(text: String, chunks: List<BookTextChunker.Chunk>) {
        val covered = HashSet<Int>()
        chunks.forEach { covered.addAll(it.start until it.end) }
        val missing = text.indices.filter { !text[it].isWhitespace() && it !in covered }
        assertTrue(
            "${missing.size} characters of the chapter are in no chunk, so they are " +
                "invisible to search: ${missing.take(20).map { text[it] }}",
            missing.isEmpty(),
        )
    }

    private fun assertWithinBudget(chunks: List<BookTextChunker.Chunk>) {
        val over = chunks.filter { it.text.length > maxChars }
        assertTrue(
            "${over.size} chunks exceed the $maxChars-character budget and would be " +
                "silently truncated by the tokenizer: ${over.map { it.text.length }}",
            over.isEmpty(),
        )
    }

    @Test
    fun `the budget comes from the token limit, not from taste`() {
        // 128 tokens is what OnnxTextEmbedder asks for by default.
        assertEquals(380, BookTextChunker.maxCharsFor(128))
        // And it moves with the limit rather than staying put.
        assertTrue(BookTextChunker.maxCharsFor(256) > BookTextChunker.maxCharsFor(128))
    }

    @Test
    fun `ordinary prose keeps its paragraphs`() {
        val chapter = chapterOf(count = 8, words = 60)
        val chunks = BookTextChunker.chunk(chapter, maxChars)

        assertEquals("one chunk per paragraph", 8, chunks.size)
        assertWithinBudget(chunks)
        assertNothingLost(chapter, chunks)
    }

    /**
     * The case that would otherwise produce thousands of useless vectors. A
     * line of dialogue is a paragraph and carries no retrievable meaning alone.
     */
    @Test
    fun `dialogue is merged into pieces worth a vector`() {
        val dialogue = List(12) { listOf("\"Yes,\" he said.", "\"No.\"", "\"Perhaps tomorrow.\"") }
            .flatten()
            .joinToString("\n\n")

        val chunks = BookTextChunker.chunk(dialogue, maxChars)

        assertTrue(
            "36 one-line paragraphs became ${chunks.size} chunks; they should merge",
            chunks.size < 10,
        )
        chunks.dropLast(1).forEach {
            assertTrue("a merged chunk should clear the minimum", it.text.length >= BookTextChunker.MIN_CHARS)
        }
        assertWithinBudget(chunks)
        assertNothingLost(dialogue, chunks)
    }

    /**
     * The trap. Before this class existed a paragraph like this was handed to
     * the embedder whole, which embedded its first four hundred characters and
     * discarded the rest without a word.
     */
    @Test
    fun `a paragraph too long to embed is split rather than truncated`() {
        val long = "P1 " + (1..60).joinToString(" ") { "Sentence number $it runs on a while." }
        val chunks = BookTextChunker.chunk(long, maxChars)

        assertTrue("a 2,000-character paragraph must yield several chunks", chunks.size > 4)
        assertWithinBudget(chunks)
        assertNothingLost(long, chunks)
    }

    /**
     * A sentence on a chunk boundary belongs to both neighbours or to neither.
     * Neither means the reader searches for the one thing they remember and the
     * app holds no vector containing it.
     */
    @Test
    fun `consecutive chunks of a split paragraph overlap`() {
        val long = "P1 " + (1..60).joinToString(" ") { "Sentence number $it runs on a while." }
        val chunks = BookTextChunker.chunk(long, maxChars)

        val overlaps = chunks.zipWithNext { a, b -> a.end - b.start }
        assertTrue("expected overlapping chunks, got $overlaps", overlaps.all { it > 0 })
    }

    /** Old prose does this: one sentence, hundreds of words, no full stop. */
    @Test
    fun `a run-on longer than the budget still loses nothing`() {
        val runOn = "x".repeat(1_500)
        val chunks = BookTextChunker.chunk(runOn, maxChars)

        assertWithinBudget(chunks)
        assertNothingLost(runOn, chunks)
    }

    @Test
    fun `offsets point back at the text they came from`() {
        val chapter = chapterOf(count = 5, words = 60)
        BookTextChunker.chunk(chapter, maxChars).forEach {
            assertEquals(
                "a chunk's offsets must locate it, or a search result jumps to the wrong place",
                it.text,
                chapter.substring(it.start, it.end).trim(),
            )
        }
    }

    @Test
    fun `a mixed chapter loses nothing`() {
        val chapter = buildString {
            append(chapterOf(count = 4, words = 60))
            append("\n\n")
            append(List(9) { "\"Quite.\"" }.joinToString("\n\n"))
            append("\n\n")
            append("P9 " + (1..40).joinToString(" ") { "Sentence $it goes on." })
        }
        val chunks = BookTextChunker.chunk(chapter, maxChars)
        assertWithinBudget(chunks)
        assertNothingLost(chapter, chunks)
    }

    @Test
    fun `empty input produces nothing rather than an empty chunk`() {
        assertTrue(BookTextChunker.chunk("", maxChars).isEmpty())
        assertTrue(BookTextChunker.chunk("   \n\n  \n ", maxChars).isEmpty())
    }

    // Reported from the device
    // ========================
    //
    // The coverage sheet showed the passages actually being sent to the model.
    // They read: "eillance are no longer something we encounter…",
    // "ophisticated and has been deployed…", "ading corporations.", and one
    // that was, in full, "privacy." Those went to the embedder as well, so
    // search has been running on vectors of word fragments too.

    @Test
    fun `no chunk begins in the middle of a word`() {
        // The overlap stepped back a fixed 80 CHARACTERS from the previous
        // cut, which lands wherever it lands. Every chunk after the first of a
        // long paragraph began mid-word.
        val paragraph = (1..40).joinToString(" ") {
            "Surveillance of communications and sophisticated tracking by " +
                "corporations reshaped expectations of privacy in period $it."
        }
        val chunks = BookTextChunker.chunk(paragraph)
        assertTrue("expected a split paragraph", chunks.size > 3)

        for (chunk in chunks) {
            // Either the chunk starts the text, or what precedes it is
            // whitespace — never a letter, which would mean a severed word.
            val clean = chunk.start == 0 || paragraph[chunk.start - 1].isWhitespace()
            assertTrue(
                "chunk at ${chunk.start} begins mid-word: ${chunk.text.take(40)}",
                clean,
            )
        }
    }

    @Test
    fun `a short paragraph is never left as a chunk of its own`() {
        // "privacy." was a real chunk. A short paragraph wedged between two
        // that are nearly a full budget could not merge forward without
        // overflowing, so it was flushed alone.
        // The arithmetic matters, and my first attempt at this test quietly
        // did not reproduce the bug: a paragraph that CAN absorb the short one
        // simply absorbs it. The flush-alone path needs a neighbour long
        // enough that merging would overflow the budget.
        //
        // "privacy." is 8 characters plus 2 for the blank line, so the
        // FOLLOWING paragraph must be too long to absorb it — that is the
        // branch that flushed it alone — while the preceding one has room.
        val big = "word ".repeat((maxChars - 5) / 5).trim() + "."
        val small = "word ".repeat(40).trim() + "."
        assertTrue("fixture must fit the budget", big.length <= maxChars)
        assertTrue(
            "the follower must be unable to absorb the short paragraph",
            "privacy.".length + 2 + big.length > maxChars,
        )
        assertTrue(
            "the predecessor must have room for it",
            small.length + 2 + "privacy.".length <= maxChars,
        )
        val text = small + "\n\nprivacy.\n\n" + big
        val chunks = BookTextChunker.chunk(text, maxChars)
        val tiny = chunks.filter { it.text.length < BookTextChunker.MIN_CHARS }
        assertTrue("left standalone fragments: ${tiny.map { it.text }}", tiny.isEmpty())
        // And it is still in there somewhere, because nothing may be dropped.
        assertTrue(chunks.any { it.text.contains("privacy.") })
    }

    @Test
    fun `merging undersized chunks never breaks the embedding budget`() {
        // A guard on the fix rather than a reproduction of a bug: merging
        // undersized chunks backward must never push one over the budget,
        // because over it the embedder truncates silently and the vector then
        // describes a fraction of the text — the exact failure this whole
        // class exists to prevent.
        val max = BookTextChunker.maxCharsFor(128)
        val text = buildString {
            repeat(12) { append("Short one.\n\n") }
            repeat(6) { append("B ".repeat(150).trim() + ".\n\n") }
            repeat(12) { append("Tiny.\n\n") }
        }
        for (chunk in BookTextChunker.chunk(text)) {
            assertTrue(
                "chunk of ${chunk.text.length} exceeds the $max budget",
                chunk.text.length <= max,
            )
        }
    }
}
