package com.pagetime.app.data.learning

/**
 * How many cards to ask an AI for out of a stretch of reading.
 *
 * FEWER IS THE POINT
 *
 * A card the reader keeps is rehearsed on a widening schedule for months, so
 * every extra card is a standing cost, and the ones added to reach a quota are
 * the ones nobody wanted: a minor name, a passing number, an example standing
 * in for the idea. A model asked for a lot of cards writes a lot of cards; asked
 * for the few worth keeping it has to choose. Wozniak's rules and Matuschak's
 * "focused" principle both come down to the same advice: fewer, sharper
 * prompts, each about one idea.
 *
 * The count is a ceiling and the prompt says so. These numbers set the ceiling
 * from the length of what was read.
 */
enum class CardDensity(
    val key: String,
    val label: String,
    val description: String,
    private val wordsPerCard: Int,
    private val minCards: Int,
    private val maxCards: Int,
) {
    FEW(
        key = "few",
        label = "Only the best",
        description = "About one card per 500 words. For reading you mostly want to keep the main ideas of.",
        wordsPerCard = 500,
        minCards = 2,
        maxCards = 8,
    ),
    NORMAL(
        key = "normal",
        label = "Balanced",
        description = "About one card per 300 words.",
        wordsPerCard = 300,
        minCards = 3,
        maxCards = 12,
    ),
    MANY(
        key = "many",
        label = "Thorough",
        description = "About one card per 150 words. Dense study material; expect more to untick.",
        wordsPerCard = 150,
        minCards = 3,
        maxCards = 20,
    );

    /** The most cards to ask for from [words] words of reading. */
    fun target(words: Int): Int = (words / wordsPerCard).coerceIn(minCards, maxCards)

    companion object {
        val DEFAULT = NORMAL

        fun fromKey(key: String?): CardDensity = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
