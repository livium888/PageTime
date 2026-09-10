package com.pagetime.app.data.review

/**
 * The rules for answering a question in the reading chair, rather than in a
 * review sitting.
 *
 * WHAT WAS WRONG BEFORE
 *
 * A question surfaced mid-chapter offered "Keep it" and "Throw it away". That
 * is curation, not review — it asks whether the card is any good, never
 * whether the reader knows the answer. So the card entered the deck with no
 * history at all, due immediately, and the first thing a review sitting did
 * was ask a question that had been answered ten minutes earlier. The retrieval
 * that matters most, the one that happens while the passage is still warm, was
 * thrown away.
 *
 * Quantum Country does not do this. Its in-text prompts ARE reviews: you
 * answer, you say how it went, and the schedule starts from that. The first
 * encounter is review number one, not a shopping decision.
 *
 * WHY "EASY" IS NOT ON OFFER HERE
 *
 * You have just read the sentence. Of course it was easy — it was on screen
 * twenty seconds ago, and what you are reporting is your eyesight, not your
 * memory. FSRS takes Easy as evidence of a durable memory and will not ask
 * again for days, which is exactly the wrong conclusion to draw from a passage
 * you have not yet forgotten once.
 *
 * So the chair offers three answers and the review sitting offers four. The
 * missing one is not a simplification for beginners; it is a claim the reader
 * is not yet in a position to make.
 */
object FirstReview {

    /** FSRS ratings, as stored in `lastRating`. */
    const val AGAIN = 1
    const val HARD = 2
    const val GOOD = 3
    const val EASY = 4

    /**
     * What the reader may say about a question they met while reading.
     *
     * Ordered worst to best, the way every review UI orders them, so the
     * button in a given position means the same thing in both places.
     */
    val OFFERED_IN_THE_CHAIR: List<Int> = listOf(AGAIN, HARD, GOOD)

    fun offeredInTheChair(rating: Int): Boolean = rating in OFFERED_IN_THE_CHAIR

    /**
     * Pulls a rating back into what the chair can honestly report.
     *
     * A safety net rather than a feature: nothing in the UI offers Easy there,
     * and if something ever does, the schedule should not quietly gain four
     * days because of it.
     */
    fun inTheChair(rating: Int): Int = if (rating >= EASY) GOOD else rating.coerceAtLeast(AGAIN)

    /**
     * Answering a question is how a card is accepted.
     *
     * Every grade keeps it, including Again. A card you could not answer is
     * the most valuable one in the deck — throwing it away for the crime of
     * being hard is how a review deck fills up with things you already know.
     * Discarding stays available, but it is a separate action about the
     * QUESTION, not about your performance on it.
     */
    fun keeps(rating: Int): Boolean = rating >= AGAIN

    /**
     * Whether this row has ever been answered.
     *
     * Both halves matter. Status alone would call a card kept from the
     * flashcard list "already reviewed" when nobody has answered it; the
     * count alone would treat a skipped card as reviewable.
     */
    fun isFirstSighting(status: String, reviewCount: Int): Boolean =
        reviewCount <= 0 && status != STATUS_SKIPPED

    private const val STATUS_SKIPPED = "skipped"
}
