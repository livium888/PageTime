package com.pagetime.app.ui.screens.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two marks on a shelf that carry meaning: the bookmark and the author.
 *
 * Neither can be checked by looking at them here, so what is checked is the
 * part that would be wrong on a device in a way nobody would report: a ribbon
 * that covers the whole spine or none of it, and an author's name drawn on a
 * spine too short to hold it, which would push the title off the book.
 */
class ShelfDecorTest {

    // --- The bookmark ---

    @Test
    fun `a book that has not been started has no bookmark`() {
        assertEquals(0f, ShelfDecor.ribbonFraction(0f), 0.0001f)
        assertEquals(0f, ShelfDecor.ribbonFraction(-1f), 0.0001f)
    }

    /** A finished book has no bookmark in it. That is what the ribbon means. */
    @Test
    fun `a finished book has no bookmark`() {
        assertEquals(0f, ShelfDecor.ribbonFraction(0.95f), 0.0001f)
        assertEquals(0f, ShelfDecor.ribbonFraction(1f), 0.0001f)
    }

    @Test
    fun `the bookmark gets longer the further in the reader is`() {
        var previous = 0f
        var progress = 0.02f
        while (progress < ShelfDecor.FINISHED) {
            val ribbon = ShelfDecor.ribbonFraction(progress)
            assertTrue("ribbon shrank at $progress", ribbon >= previous)
            previous = ribbon
            progress += 0.01f
        }
        assertTrue("the ribbon never grew", previous > 0f)
    }

    /**
     * The failure this is really guarding: a ribbon the length of the spine
     * reads as a painted stripe, and one a pixel long is invisible on a phone.
     */
    @Test
    fun `a bookmark is always visible and never covers the spine`() {
        var progress = 0.01f
        while (progress < ShelfDecor.FINISHED) {
            val ribbon = ShelfDecor.ribbonFraction(progress)
            assertTrue("too short at $progress: $ribbon", ribbon >= 0.15f)
            assertTrue("too long at $progress: $ribbon", ribbon <= 0.75f)
            progress += 0.01f
        }
    }

    @Test
    fun `only a book in progress is in progress`() {
        assertTrue(ShelfDecor.isInProgress(0.5f))
        assertFalse(ShelfDecor.isInProgress(0f))
        assertFalse(ShelfDecor.isInProgress(0.95f))
        assertFalse(ShelfDecor.isInProgress(1f))
    }

    /**
     * The shelf grouping and the bookmark have to be halves of one rule, or a
     * book sorts under "Reading now" and shows no bookmark.
     */
    @Test
    fun `a book filed as reading is exactly a book with a bookmark`() {
        assertTrue(ShelfDecor.isInProgress(0.3f))
        assertTrue(ShelfDecor.ribbonFraction(0.3f) > 0f)
        assertFalse(ShelfDecor.isInProgress(ShelfDecor.FINISHED))
        assertEquals(0f, ShelfDecor.ribbonFraction(ShelfDecor.FINISHED), 0.0001f)
    }

    // --- The author ---

    @Test
    fun `no author means no author on the spine`() {
        assertNull(ShelfDecor.spineAuthor(132, "Middlemarch", ""))
        assertNull(ShelfDecor.spineAuthor(132, "Middlemarch", "   "))
    }

    @Test
    fun `a short title and a short name fit together`() {
        assertEquals("James Joyce", ShelfDecor.spineAuthor(130, "Ulysses", "James Joyce"))
    }

    @Test
    fun `a long name gives way to the surname`() {
        assertEquals("Eliot", ShelfDecor.spineAuthor(120, "Middlemarch", "George Eliot"))
    }

    @Test
    fun `a spine too short for either gets only the title`() {
        assertNull(ShelfDecor.spineAuthor(80, "Middlemarch", "George Eliot"))
    }

    /** Nothing to shorten, and no room for it: the title keeps the spine. */
    @Test
    fun `a one-word name that does not fit is dropped`() {
        assertNull(ShelfDecor.spineAuthor(60, "Middlemarch", "Homer"))
    }

    @Test
    fun `a taller spine never loses an author it had`() {
        val names = listOf("Homer", "George Eliot", "Fyodor Dostoevsky", "Jane Austen")
        val titles = listOf("Ulysses", "Middlemarch", "The Odyssey")
        for (title in titles) {
            for (author in names) {
                var seen = false
                for (height in 60..140) {
                    val resolved = ShelfDecor.spineAuthor(height, title, author)
                    if (resolved != null) seen = true
                    if (seen) {
                        assertTrue(
                            "$title / $author dropped at $height",
                            resolved != null,
                        )
                    }
                }
            }
        }
    }

    /** The surname is a shortening, never a rewrite. */
    @Test
    fun `what is drawn is always part of the name given`() {
        val names = listOf("Homer", "George Eliot", "Fyodor Dostoevsky", "  Ursula K. Le Guin ")
        for (name in names) {
            for (height in 60..140) {
                val drawn = ShelfDecor.spineAuthor(height, "Middlemarch", name) ?: continue
                assertTrue(
                    "\"$drawn\" is not in \"$name\"",
                    name.trim().contains(drawn),
                )
            }
        }
    }
}
