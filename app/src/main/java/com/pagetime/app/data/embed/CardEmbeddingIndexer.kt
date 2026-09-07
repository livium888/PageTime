package com.pagetime.app.data.embed

import com.pagetime.app.data.local.CardEmbeddingDao
import com.pagetime.app.data.local.CardEmbeddingEntity
import com.pagetime.app.data.local.LumenCardEntity

/** A card and how close it is to whatever was searched for. */
data class ScoredCard(val card: LumenCardEntity, val similarity: Float)

/**
 * Keeps the vector table in step with the slip box, and answers "what else
 * says this?".
 *
 * WHAT GETS EMBEDDED, WHICH IS THE ONE DECISION THAT MATTERS HERE
 *
 * The card's front and back. NOT the quote.
 *
 * The quote is the book's words; the card is the reader's idea about them. Two
 * cards captured from the same page carry nearly the same quote and would come
 * out as near-duplicates however different the notes on them are — which is
 * precisely backwards, because the whole point of a slip box is that one
 * passage can yield several unrelated thoughts. Embedding the note keeps the
 * comparison about what the reader meant.
 *
 * WHY BRUTE FORCE AND NOT A VECTOR INDEX
 *
 * sqlite-vec and its relatives need a SQLite compiled with extension loading,
 * and Android's bundled one is built with OMIT_LOAD_EXTENSION — using them
 * means shipping a second SQLite. Against that: a slip box is hundreds to a few
 * thousand cards, and a cosine over 384 floats is a few hundred
 * multiplications. Comparing against three thousand cards is around a million
 * of them, which is under a millisecond. An index would be machinery earning
 * nothing until the box is orders of magnitude larger than any real one.
 */
class CardEmbeddingIndexer(
    private val embeddingDao: CardEmbeddingDao,
    private val store: EmbeddingModelStore,
) {

    /**
     * What identifies the vectors currently being produced.
     *
     * Delegated to the store so cards and book text cannot drift into
     * differently-named versions of the same space. See
     * [EmbeddingModelStore.modelId].
     */
    fun modelId(): String? = store.modelId()

    /** How many cards this model has not seen yet. */
    suspend fun pendingCount(): Int {
        val model = modelId() ?: return 0
        return embeddingDao.countAwaitingEmbedding(model)
    }

    /**
     * Embeds [cards] and stores their vectors, returning how many landed.
     *
     * One ONNX session for the whole batch rather than one per card: opening a
     * session maps the model and allocates native arenas, which is tens of
     * milliseconds that has no business being paid three hundred times during
     * a backfill.
     *
     * Returns 0 rather than throwing when no model is installed. An absent
     * vector degrades search; it must never be able to fail a card save.
     */
    suspend fun index(cards: List<LumenCardEntity>): Int {
        if (cards.isEmpty()) return 0
        val model = modelId() ?: return 0
        val tokenizer = store.tokenizer() ?: return 0

        var stored = 0
        var embedder: OnnxTextEmbedder? = null
        try {
            val runner = OnnxTextEmbedder(store.modelFile, tokenizer)
            embedder = runner
            for (card in cards) {
                val text = embeddableText(card)
                if (text.isBlank()) continue
                // Per card, not per batch: one card whose text upsets the model
                // should cost that card's vector, not the other 299.
                val vector = runCatching { runner.embed(text) }.getOrNull() ?: continue
                embeddingDao.put(
                    CardEmbeddingEntity(
                        cardId = card.id,
                        model = model,
                        dimensions = vector.size,
                        vector = EmbeddingMath.toBytes(vector),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                stored++
            }
        } catch (_: Exception) {
            // No model, an unreadable model, a runtime that would not start.
            // Reported by the count returned rather than by an exception, for
            // the same reason as above.
        } finally {
            runCatching { embedder?.close() }
        }
        return stored
    }

    /**
     * Embeds up to [batch] cards that have no vector yet, oldest first.
     *
     * Bounded on purpose. A reader who has been keeping a slip box for a year
     * and installs the model today should not have the app disappear into a
     * thousand inferences; called repeatedly, this drains the queue in chunks
     * that each stay interruptible.
     */
    suspend fun backfill(batch: Int = DEFAULT_BATCH): Int {
        val model = modelId() ?: return 0
        val waiting = embeddingDao.cardsAwaitingEmbedding(model, batch)
        return index(waiting)
    }

    /**
     * The cards closest in meaning to [text], best first.
     *
     * [minimum] exists because a nearest-neighbour search always returns
     * neighbours. With nothing related in the box the closest card is still
     * *a* card, and offering it as a connection teaches the reader that these
     * suggestions are noise. Better to return nothing than something arbitrary.
     */
    suspend fun similarTo(
        text: String,
        candidates: List<LumenCardEntity>,
        excludeCardId: String? = null,
        limit: Int = 5,
        minimum: Float = DEFAULT_MINIMUM,
    ): List<ScoredCard> {
        if (text.isBlank() || candidates.isEmpty()) return emptyList()
        val model = modelId() ?: return emptyList()
        val tokenizer = store.tokenizer() ?: return emptyList()

        val stored = embeddingDao.allForModel(model).associateBy { it.cardId }
        if (stored.isEmpty()) return emptyList()

        var embedder: OnnxTextEmbedder? = null
        val query = try {
            val runner = OnnxTextEmbedder(store.modelFile, tokenizer)
            embedder = runner
            runner.embed(text)
        } catch (_: Exception) {
            return emptyList()
        } finally {
            runCatching { embedder?.close() }
        }

        return candidates.asSequence()
            .filter { it.id != excludeCardId }
            .mapNotNull { card ->
                val row = stored[card.id] ?: return@mapNotNull null
                // A vector of the wrong width cannot be compared, and comparing
                // it anyway is the silent-wrongness this table's model column
                // exists to prevent. Skipped, not coerced.
                if (row.dimensions != query.size) return@mapNotNull null
                ScoredCard(card, EmbeddingMath.cosineSimilarity(query, EmbeddingMath.fromBytes(row.vector)))
            }
            .filter { it.similarity >= minimum }
            .sortedByDescending { it.similarity }
            .take(limit)
            .toList()
    }

    companion object {
        const val DEFAULT_BATCH = 32

        /**
         * Below this, two notes are not really about the same thing.
         *
         * Calibrated against what the self-test measures rather than picked
         * from the air: unrelated pairs of book-register sentences sit well
         * under this with all-MiniLM-L6-v2, and paraphrases sit well above it.
         * It is a floor for "worth showing the reader", not a claim about
         * meaning.
         */
        const val DEFAULT_MINIMUM = 0.45f

        /**
         * Front and back, which is the note itself. See the class comment for
         * why the quote is deliberately left out.
         */
        fun embeddableText(card: LumenCardEntity): String =
            listOf(card.front.trim(), card.back.trim())
                .filter { it.isNotEmpty() }
                .joinToString("\n")
    }
}
