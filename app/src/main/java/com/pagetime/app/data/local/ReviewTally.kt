package com.pagetime.app.data.local

/**
 * How much of what the reader has been shown they actually remembered.
 *
 * [remembered] counts every rating above Again. Hard is a success: the reader
 * produced the answer, and how hard it was is the scheduler's business, not
 * the question of whether the memory was there.
 */
data class ReviewTally(
    val reviews: Int = 0,
    val remembered: Int = 0,
    /** Distinct cards reviewed, so "12 reviews" is not mistaken for 12 cards. */
    val cards: Int = 0,
) {
    /** Null rather than zero when nothing is due yet: no data is not 0%. */
    val recall: Float?
        get() = if (reviews == 0) null else remembered.toFloat() / reviews
}
