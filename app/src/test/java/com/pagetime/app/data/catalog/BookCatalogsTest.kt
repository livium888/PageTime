package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.GutenbergApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry that replaced the source enum. Constructing the clients makes no
 * network call, so this runs offline like every other unit test here.
 */
class BookCatalogsTest {

    private val catalogs = BookCatalogs(gutenberg = GutenbergApi())

    @Test
    fun `ids are unique, because a chip is matched by id`() {
        val ids = catalogs.all.map { it.id }
        assertEquals("Two catalogues sharing an id would shadow each other", ids.size, ids.toSet().size)
    }

    @Test
    fun `every catalogue can say what it is`() {
        // The note is what an empty shelf shows. A blank one puts the reader
        // back where they started: a list with nothing in it and no reason.
        catalogs.all.forEach {
            assertTrue("${it.id} has no label", it.label.isNotBlank())
            assertTrue("${it.id} has no note", it.note.isNotBlank())
        }
    }

    @Test
    fun `every catalogue here can be browsed`() {
        // The two search-only catalogues were the OCR ones, and they are gone.
        // What remains browses, so a reader opening Discover with an empty box
        // sees books rather than a prompt.
        assertTrue(catalogs.all.all { it.browsable })
    }

    @Test
    fun `an unknown id falls back to the default rather than crashing`() {
        // Ids are persisted, and this build drops three catalogues. A reader
        // whose last chip was Internet Archive must land somewhere real.
        assertEquals(catalogs.default.id, catalogs.byId("internetarchive").id)
        assertEquals(catalogs.default.id, catalogs.byId("wikisource").id)
        assertEquals(catalogs.default.id, catalogs.byId(null).id)
    }

    @Test
    fun `only human-made catalogues are offered`() {
        // The entry requirement: somebody read the words. OCR of page scans is
        // not readable prose, and no work on this side repairs a file that
        // arrived that way.
        assertEquals(listOf("standardebooks", "gutenberg"), catalogs.all.map { it.id })
    }

    @Test
    fun `the default is the most reliable source`() {
        assertEquals("standardebooks", catalogs.default.id)
    }

    @Test
    fun `an empty shelf distinguishes silence from having nothing to say`() {
        // The distinction the screen was missing. These are separate types so
        // that drawing them the same way has to be a deliberate act.
        // Typed as the interface deliberately: as their own types Kotlin
        // rejects comparing them at all, which is itself the point — they are
        // not interchangeable, and the screen has to choose between them.
        val nothing: CatalogHealth = CatalogHealth.NothingMatched("Gutenberg", "xyzzy", "note")
        val silent: CatalogHealth = CatalogHealth.Unreachable("Gutenberg", "It did not answer.")
        assertNotEquals(nothing, silent)
        assertTrue(silent is CatalogHealth.Unreachable)
        assertTrue(nothing is CatalogHealth.NothingMatched)
    }
}
