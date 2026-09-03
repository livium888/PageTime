package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.internetarchive.InternetArchiveApi
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry that replaced the source enum. Constructing the clients makes no
 * network call, so this runs offline like every other unit test here.
 */
class BookCatalogsTest {

    private val catalogs = BookCatalogs(
        gutenberg = GutenbergApi(),
        openLibrary = OpenLibraryApi(),
        internetArchive = InternetArchiveApi(),
    )

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
    fun `a catalogue with no browse mode says so instead of failing`() {
        // Internet Archive has no "show me anything" endpoint. That used to
        // live as error("Search Internet Archive by title or author") inside a
        // when-branch, so the only way to discover it was to throw.
        val archive = catalogs.byId("internetarchive")
        assertFalse(archive.browsable)
        assertTrue(catalogs.all.filter { it.id != "internetarchive" }.all { it.browsable })
    }

    @Test
    fun `an unknown id falls back to the default rather than crashing`() {
        // Ids are persisted. A build that drops a catalogue must not leave the
        // reader on a chip that no longer exists.
        assertEquals(catalogs.default.id, catalogs.byId("feedbooks").id)
        assertEquals(catalogs.default.id, catalogs.byId(null).id)
    }

    @Test
    fun `the default is the most reliable source`() {
        assertEquals("standardebooks", catalogs.default.id)
    }

    @Test
    fun `an empty shelf distinguishes silence from having nothing to say`() {
        // The distinction the screen was missing. These are separate types so
        // that drawing them the same way has to be a deliberate act.
        val nothing = CatalogHealth.NothingMatched("Gutenberg", "xyzzy", "note")
        val silent = CatalogHealth.Unreachable("Gutenberg", "It did not answer.")
        assertFalse(nothing == silent)
        assertTrue(silent is CatalogHealth.Unreachable)
        assertTrue(nothing is CatalogHealth.NothingMatched)
    }
}
