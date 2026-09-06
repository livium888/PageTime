package com.pagetime.app.data.embed

import com.pagetime.app.data.local.LumenCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision in the indexer that cannot be checked by running it: WHICH
 * TEXT represents a card.
 *
 * Nothing throws if this is wrong. Vectors still get built, search still
 * returns neighbours, and the neighbours are simply the wrong ones — cards
 * grouped by which page they came from instead of by what they say.
 */
class CardEmbeddingIndexerTest {

    private fun card(front: String, back: String, quote: String) =
        LumenCardEntity(
            id = "id",
            bookId = "b",
            box = 1,
            indexNumber = "1",
            front = front,
            back = back,
            quote = quote,
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            createdAt = 0,
            updatedAt = 0,
        )

    @Test
    fun `the note is what gets embedded`() {
        val text = CardEmbeddingIndexer.embeddableText(
            card(front = "Fiction lets strangers cooperate", back = "Because a shared story", quote = "x")
        )
        assertTrue(text.contains("Fiction lets strangers cooperate"))
        assertTrue(text.contains("Because a shared story"))
    }

    /**
     * The point of the whole choice. Two cards captured from the same page
     * carry nearly the same quote; including it would make them near-identical
     * however different the reader's two thoughts about that page were, which
     * is exactly backwards for a slip box.
     */
    @Test
    fun `the source passage is excluded`() {
        val quote = "Augustine held that error is a defect of the will, not of the intellect."
        val text = CardEmbeddingIndexer.embeddableText(
            card(front = "Mistakes are chosen", back = "Wanting the wrong thing precedes it", quote = quote)
        )
        assertFalse(text.contains("Augustine"))
        assertFalse(text.contains(quote))
    }

    @Test
    fun `two cards from one passage do not read as the same text`() {
        val quote = "The same long passage, shared by both cards, word for word."
        val first = CardEmbeddingIndexer.embeddableText(
            card("Mistakes are chosen", "Wanting the wrong thing precedes it", quote)
        )
        val second = CardEmbeddingIndexer.embeddableText(
            card("Authority outlives its reasons", "Habit keeps a rule after its case is gone", quote)
        )
        assertFalse(first == second)
    }

    @Test
    fun `an empty back does not leave a dangling separator`() {
        assertEquals("Just the front", CardEmbeddingIndexer.embeddableText(card("Just the front", "", "q")))
    }

    @Test
    fun `a card with no text of its own embeds nothing`() {
        assertEquals("", CardEmbeddingIndexer.embeddableText(card("  ", "", "a long quote")))
    }
}
