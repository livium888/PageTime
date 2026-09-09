package com.pagetime.app.ui.screens.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing here can tell whether a drawn spine looks like a book — only a
 * device can. What it can tell is that the numbers behind it are stable, in
 * range, and varied, which is where a generated shelf actually goes wrong:
 * every book the same width, or one book invisible because a hash came out
 * negative.
 */
class BookSpinesTest {

    @Test
    fun `the same book always looks the same`() {
        val a = BookSpines.lookFor("Middlemarch", "George Eliot", owned = true)
        val b = BookSpines.lookFor("Middlemarch", "George Eliot", owned = true)
        assertEquals(a, b)
    }

    /** Derived, not stored — so it must not depend on anything but the strings. */
    @Test
    fun `case and surrounding space do not change a spine`() {
        val a = BookSpines.lookFor("Middlemarch", "George Eliot", owned = true)
        val b = BookSpines.lookFor("  middlemarch ", "  GEORGE ELIOT", owned = true)
        assertEquals(a.widthDp, b.widthDp)
        assertEquals(a.hue, b.hue, 0.001f)
        assertEquals(a.lightness, b.lightness, 0.001f)
    }

    /**
     * The point of hashing the author rather than the title: a run of one
     * writer's books reads as a set.
     */
    @Test
    fun `one author's books share a hue`() {
        val one = BookSpines.lookFor("Middlemarch", "George Eliot", owned = true)
        val two = BookSpines.lookFor("Silas Marner", "George Eliot", owned = true)
        assertEquals(one.hue, two.hue, 0.001f)
        assertEquals(one.saturation, two.saturation, 0.001f)
    }

    /** But not identical spines, or the set reads as one long block. */
    @Test
    fun `books by one author still differ from each other`() {
        val one = BookSpines.lookFor("Middlemarch", "George Eliot", owned = true)
        val two = BookSpines.lookFor("Silas Marner", "George Eliot", owned = true)
        assertNotEquals(one.lightness, two.lightness)
    }

    @Test
    fun `different authors generally get different hues`() {
        val hues = listOf(
            "George Eliot", "Herman Melville", "Leo Tolstoy", "Jane Austen",
            "Charles Dickens", "Franz Kafka", "Virginia Woolf", "Homer",
        ).map { BookSpines.lookFor("A Book", it, owned = true).hue }
        assertTrue("hues collapsed: " + hues, hues.distinct().size >= 6)
    }

    // --- Ranges, which is where an invisible book would come from ---

    @Test
    fun `every derived value stays inside its range`() {
        val titles = (1..300).map { "Book number " + it }
        val authors = (1..5).map { "Author " + it }
        for (title in titles) {
            for (author in authors) {
                val look = BookSpines.lookFor(title, author, owned = title.length % 2 == 0)
                assertTrue(
                    "width " + look.widthDp,
                    look.widthDp in BookSpines.MIN_WIDTH_DP..BookSpines.MAX_WIDTH_DP,
                )
                assertTrue("height " + look.heightFraction, look.heightFraction in 0.8f..1.0f)
                assertTrue("hue " + look.hue, look.hue in 0f..360f)
                assertTrue("saturation " + look.saturation, look.saturation in 0f..1f)
                assertTrue("lightness " + look.lightness, look.lightness in 0f..1f)
                assertTrue("bands " + look.bands, look.bands in 0..2)
            }
        }
    }

    /**
     * A shelf where every book is the same width reads as a bar chart. This is
     * the property most likely to break quietly if the hashing is changed.
     */
    @Test
    fun `widths spread across the available range`() {
        val widths = (1..200).map {
            BookSpines.lookFor("Title " + it, "Author", owned = true).widthDp
        }
        assertTrue("only " + widths.distinct().size + " distinct widths", widths.distinct().size >= 12)
    }

    /**
     * Strings chosen to drive the hash negative, which is the classic way a
     * generated spine ends up with no width at all.
     */
    @Test
    fun `a negative hash never produces a negative measurement`() {
        val odd = listOf(
            "￿￿￿",
            "zzzzzzzzzzzzzzzz",
            " ",
            "𝔊𝔯",
        )
        odd.forEach { s ->
            val look = BookSpines.lookFor(s, s, owned = false)
            assertTrue("width " + look.widthDp, look.widthDp >= BookSpines.MIN_WIDTH_DP)
            assertTrue("width " + look.widthDp, look.widthDp <= BookSpines.MAX_WIDTH_DP)
            assertTrue("hue " + look.hue, look.hue >= 0f)
            assertTrue("height " + look.heightFraction, look.heightFraction > 0f)
        }
    }

    @Test
    fun `an empty title or author still yields a drawable spine`() {
        val look = BookSpines.lookFor("", "", owned = true)
        assertTrue(look.widthDp in BookSpines.MIN_WIDTH_DP..BookSpines.MAX_WIDTH_DP)
        assertTrue(look.heightFraction > 0f)
    }

    // --- Ghosts, which are the availability state made visual ---

    @Test
    fun `a book you do not own is a ghost and one you do is not`() {
        assertTrue(BookSpines.lookFor("Ulysses", "James Joyce", owned = false).ghost)
        assertFalse(BookSpines.lookFor("Ulysses", "James Joyce", owned = true).ghost)
    }

    /** Owning a book changes only whether it is filled, never where it sits. */
    @Test
    fun `owning a book does not move it on the shelf`() {
        val ghost = BookSpines.lookFor("Ulysses", "James Joyce", owned = false)
        val solid = BookSpines.lookFor("Ulysses", "James Joyce", owned = true)
        assertEquals(ghost.widthDp, solid.widthDp)
        assertEquals(ghost.heightFraction, solid.heightFraction, 0.0001f)
        assertEquals(ghost.hue, solid.hue, 0.0001f)
    }

    // --- Bands ---

    @Test
    fun `bands sit near the ends and never over the title`() {
        (1..100).forEach { i ->
            val look = BookSpines.lookFor("Title " + i, "Author " + i, owned = true)
            BookSpines.bandPositions(look).forEach { at ->
                assertTrue("band at " + at, at < 0.25f || at > 0.75f)
            }
            assertEquals(look.bands.coerceAtMost(2), BookSpines.bandPositions(look).size)
        }
    }
}
