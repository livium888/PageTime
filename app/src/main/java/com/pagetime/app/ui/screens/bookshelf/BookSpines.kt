package com.pagetime.app.ui.screens.bookshelf

import kotlin.math.abs

/**
 * How one book looks on a shelf, derived entirely from its title and author.
 *
 * WHY SPINES AND NOT COVERS
 *
 * Most of what is on these shelves is not owned. Most of the ladder, and
 * nearly all of an author shelf for a living writer. Those books have no
 * cover — not a poor one, none — so a cover-based shelf would be six real
 * books and forty grey rectangles.
 *
 * A spine can be generated for anything that has a title and an author, which
 * is everything. It also sidesteps the quality problem: Gutenberg's covers are
 * inconsistent and often absent while Standard Ebooks' are uniform and
 * excellent, and the two together look like a jumble sale.
 *
 * EVERYTHING HERE IS DERIVED, NOTHING IS STORED
 *
 * A shelf that stored its own colours would need a migration to change how it
 * looks, and would drift out of step with the books as they are renamed or
 * re-imported. Deriving means the same book is always the same spine, on any
 * device, with nothing written down.
 *
 * WHY THE HASH IS WRITTEN OUT
 *
 * String.hashCode() is specified and stable, but it is also easy to take the
 * remainder of a negative number and get a negative index — which here would
 * be an invisible book. This is a few lines and cannot surprise anyone.
 */
data class SpineLook(
    /** Across the shelf, in dp. Varied so a shelf does not read as a bar chart. */
    val widthDp: Int,
    /** Fraction of the shelf's height. Books are not uniform. */
    val heightFraction: Float,
    /** 0..360. Taken from the AUTHOR, so one writer's books share a family. */
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    /** Horizontal rules near the ends, as real spines nearly always have. */
    val bands: Int,
    /**
     * Drawn as an outline rather than a solid.
     *
     * The availability state made visual: two solid books and twenty ghosts
     * says what an author shelf holds without a word of text.
     */
    val ghost: Boolean,
)

object BookSpines {

    /**
     * Deliberately narrow, muted and dark.
     *
     * The obvious thing is to spread hue across the whole wheel at full
     * saturation, which produces a shelf of highlighter pens. Cloth and
     * leather bindings sit in a small range of desaturated, deep colours, and
     * keeping inside it is most of what makes a drawn shelf read as a library
     * rather than a toy.
     */
    private const val MIN_SATURATION = 0.18f
    private const val MAX_SATURATION = 0.42f
    private const val MIN_LIGHTNESS = 0.22f
    private const val MAX_LIGHTNESS = 0.42f

    const val MIN_WIDTH_DP = 26
    const val MAX_WIDTH_DP = 46

    private const val MIN_HEIGHT_FRACTION = 0.86f
    private const val MAX_HEIGHT_FRACTION = 1.0f

    fun lookFor(title: String, author: String, owned: Boolean): SpineLook {
        val titleSeed = hash(title.lowercase().trim())
        val authorSeed = hash(author.lowercase().trim())

        return SpineLook(
            // Width from the title, so two books by one author are not twins.
            widthDp = MIN_WIDTH_DP + pick(titleSeed, 1, MAX_WIDTH_DP - MIN_WIDTH_DP + 1),
            heightFraction = MIN_HEIGHT_FRACTION +
                fraction(titleSeed, 2) * (MAX_HEIGHT_FRACTION - MIN_HEIGHT_FRACTION),
            hue = fraction(authorSeed, 1) * 360f,
            saturation = MIN_SATURATION +
                fraction(authorSeed, 2) * (MAX_SATURATION - MIN_SATURATION),
            // Lightness varies by TITLE within the author's hue, so a run of
            // one writer's books reads as a set rather than as one long block.
            lightness = MIN_LIGHTNESS +
                fraction(titleSeed, 3) * (MAX_LIGHTNESS - MIN_LIGHTNESS),
            bands = pick(titleSeed, 4, 3),
            ghost = !owned,
        )
    }

    /**
     * Where a band sits, as a fraction of the spine's height.
     *
     * Near the ends and never in the middle, which is where the title goes.
     */
    fun bandPositions(look: SpineLook): List<Float> = when (look.bands) {
        0 -> emptyList()
        1 -> listOf(0.16f)
        else -> listOf(0.16f, 0.84f)
    }

    /** FNV-1a, which is short, stable, and never negative. */
    internal fun hash(value: String): Int {
        var h = -2128831035 // 2166136261 as a signed Int
        for (c in value) {
            h = h xor c.code
            h *= 16777619
        }
        return h
    }

    /** A bounded value from [seed], varied by [salt] so one seed gives many. */
    internal fun pick(seed: Int, salt: Int, bound: Int): Int {
        if (bound <= 1) return 0
        val mixed = hash("$seed:$salt")
        return abs(mixed.toLong()).toInt() % bound
    }

    /** The same, as 0..1. */
    internal fun fraction(seed: Int, salt: Int): Float =
        pick(seed, salt, 10_000) / 10_000f
}
