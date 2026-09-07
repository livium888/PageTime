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
}
