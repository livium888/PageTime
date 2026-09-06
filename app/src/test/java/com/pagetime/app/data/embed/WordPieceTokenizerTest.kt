package com.pagetime.app.data.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokeniser is the half of on-device retrieval that fails silently. A
 * subtly wrong split still produces valid ids, the model still returns a
 * vector, and similarity search merely gets quietly worse — so the behaviour
 * is pinned here rather than trusted to look right.
 *
 * A small vocabulary instead of the real 30,522-token file: these tests are
 * about the algorithm, and a fixture small enough to read makes it obvious
 * what each case is exercising.
 */
class WordPieceTokenizerTest {

    private val vocabLines = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]",   // 0..3
        "the", "book", "read", "##ing",       // 4..7
        "##er", ".", ",", "cafe",             // 8..11
        "un", "##read", "##able", "'",        // 12..15
    )

    private fun tokenizer(lowercase: Boolean = true) = WordPieceTokenizer(
        vocabulary = WordPieceVocabulary.fromLines(vocabLines.asSequence()),
        lowercase = lowercase,
    )

    @Test
    fun `a line's position in the file is its id`() {
        val vocab = WordPieceVocabulary.fromLines(vocabLines.asSequence())
        assertEquals(0, vocab.id("[PAD]"))
        assertEquals(2, vocab.id("[CLS]"))
        assertEquals(7, vocab.id("##ing"))
        assertEquals(vocabLines.size, vocab.size)
    }

    @Test
    fun `a repeated token keeps its FIRST id`() {
        // Order is the entire contract: keeping the later id would shift a row
        // of the model's embedding matrix and go unnoticed.
        val vocab = WordPieceVocabulary.fromLines(sequenceOf("a", "b", "a"))
        assertEquals(0, vocab.id("a"))
        assertEquals(1, vocab.id("b"))
    }

    @Test
    fun `a known word is one piece`() {
        assertEquals(listOf("the", "book"), tokenizer().tokenize("the book"))
    }

    @Test
    fun `a suffix becomes a continuation piece`() {
        assertEquals(listOf("read", "##ing"), tokenizer().tokenize("reading"))
        assertEquals(listOf("read", "##er"), tokenizer().tokenize("reader"))
    }

    @Test
    fun `greedy matching takes the longest piece it can, front to back`() {
        assertEquals(listOf("un", "##read", "##able"), tokenizer().tokenize("unreadable"))
    }

    @Test
    fun `a word that cannot be covered is unknown ENTIRELY`() {
        // The detail most re-implementations get wrong: "readx" does not become
        // read + [UNK]. One failed position condemns the whole word, because
        // that is what the reference does and the ids must match it.
        assertEquals(listOf("[UNK]"), tokenizer().tokenize("readx"))
    }

    @Test
    fun `punctuation splits off as its own token`() {
        assertEquals(listOf("the", "book", "."), tokenizer().tokenize("the book."))
        assertEquals(listOf("book", ",", "book"), tokenizer().tokenize("book,book"))
    }

    @Test
    fun `an uncased model folds case and strips accents`() {
        assertEquals(listOf("cafe"), tokenizer().tokenize("Café"))
        assertEquals(listOf("the"), tokenizer().tokenize("THE"))
    }

    @Test
    fun `a cased model leaves both alone`() {
        // "The" is not in the fixture, so with casing preserved it cannot match
        // — which is the point: the flag genuinely changes the lookup.
        assertEquals(listOf("[UNK]"), tokenizer(lowercase = false).tokenize("The"))
    }

    @Test
    fun `whitespace of every kind separates`() {
        assertEquals(listOf("the", "book"), tokenizer().tokenize("the \t\n book"))
        assertEquals(listOf("the", "book"), tokenizer().tokenize("the  book"))
    }

    @Test
    fun `encoding wraps the pieces and pads to length`() {
        val encoded = tokenizer().encode("reading", maxLength = 8)
        assertEquals(8, encoded.length)
        // [CLS] read ##ing [SEP] then padding
        assertEquals(listOf(2, 6, 7, 3, 0, 0, 0, 0), encoded.ids)
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 0, 0), encoded.attentionMask)
        assertTrue("single sequence is all segment zero", encoded.tokenTypeIds.all { it == 0 })
    }

    @Test
    fun `truncation leaves room for the special tokens`() {
        // Four words, capped at four positions: two must go so that [CLS] and
        // [SEP] survive. Dropping THEM to fit more words would change the shape
        // of the input the model was trained on.
        val encoded = tokenizer().encode("the book the book", maxLength = 4)
        assertEquals(4, encoded.length)
        assertEquals(2, encoded.ids.first())
        assertEquals(3, encoded.ids.last())
        assertTrue("nothing is padding when full", encoded.attentionMask.all { it == 1 })
    }

    @Test
    fun `an empty string still produces a valid pair of markers`() {
        val encoded = tokenizer().encode("   ", maxLength = 4)
        assertEquals(listOf(2, 3, 0, 0), encoded.ids)
        assertEquals(listOf(1, 1, 0, 0), encoded.attentionMask)
    }

    @Test
    fun `a vocabulary missing a special token is refused, not tolerated`() {
        val problem = runCatching {
            WordPieceTokenizer(WordPieceVocabulary.fromLines(sequenceOf("the", "book")))
        }.exceptionOrNull()
        assertTrue("expected a clear failure, got $problem", problem is IllegalArgumentException)
    }
}
