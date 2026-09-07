package com.pagetime.app.ui.screens.flashcards

import com.pagetime.app.data.local.LearningCardEntity

/**
 * Which cards the reader is looking at.
 *
 * NEW is offered early deliberately. A card the reader has not judged is the
 * one thing on this screen still waiting on them; everything else is a record
 * of what they already decided.
 */
enum class FlashcardFilter(val label: String) {
    ALL("All"),
    NEW("Not judged"),
    DUE("Due"),
    KEPT("Kept"),
}

/** One book's worth of cards, for a screen that groups by where they came from. */
data class FlashcardGroup(
    val bookId: String,
    val bookTitle: String,
    val cards: List<LearningCardEntity>,
)

/**
 * Turning a flat list of cards into what the screen shows.
 *
 * Pure, because the awkward parts — what counts as due, what to do with a card
 * whose book is gone — are decisions rather than rendering, and worth being
 * able to test without a phone.
 */
object FlashcardListing {

    fun matches(card: LearningCardEntity, filter: FlashcardFilter, now: Long): Boolean =
        when (filter) {
            FlashcardFilter.ALL -> true
            FlashcardFilter.NEW -> card.status == LearningCardEntity.STATUS_PENDING
            FlashcardFilter.KEPT -> card.status == LearningCardEntity.STATUS_KEPT
            // Only a kept card can be due. A pending one has no schedule at
            // all, and listing it under "Due" would promise a review session
            // that will never offer it.
            FlashcardFilter.DUE ->
                card.status == LearningCardEntity.STATUS_KEPT &&
                    card.dueAt != null &&
                    card.dueAt <= now
        }

    /**
     * Groups cards under their book.
     *
     * A card whose book has been deleted keeps a group rather than vanishing:
     * the row is still real, and dropping it silently would make the count on
     * the tab disagree with the list underneath it.
     */
    fun group(
        cards: List<LearningCardEntity>,
        titles: Map<String, String>,
        filter: FlashcardFilter = FlashcardFilter.ALL,
        now: Long = System.currentTimeMillis(),
    ): List<FlashcardGroup> =
        cards
            .filter { matches(it, filter, now) }
            .groupBy { it.bookId }
            .map { (bookId, forBook) ->
                FlashcardGroup(
                    bookId = bookId,
                    bookTitle = titles[bookId] ?: "A book no longer in the library",
                    cards = forBook,
                )
            }
            .sortedBy { it.bookTitle.lowercase() }

    fun countFor(
        cards: List<LearningCardEntity>,
        filter: FlashcardFilter,
        now: Long = System.currentTimeMillis(),
    ): Int = cards.count { matches(it, filter, now) }
}
