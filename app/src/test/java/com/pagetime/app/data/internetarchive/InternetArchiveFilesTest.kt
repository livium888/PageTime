package com.pagetime.app.data.internetarchive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two questions that decide whether a book can be downloaded at all.
 *
 * Open Library used to answer both by assuming: it built
 * archive.org/download/<id>/<id>.epub from the identifier and offered every
 * result. That produced a shelf full of books whose downloads answered 403 or
 * 404, which is exactly what the reader saw.
 */
class InternetArchiveFilesTest {

    private val files = InternetArchiveFiles()

    private fun metadata(json: String) = JSONObject(json.trimIndent())

    @Test
    fun `an item with a real EPUB gives its real file name`() {
        // Not "<id>.epub". Archive file names rarely match the identifier, so a
        // guessed name is a 404 even when an EPUB is sitting right there.
        val result = files.downloadsIn(
            "alicesadventures00carr",
            metadata(
                """
                {"files":[
                  {"name":"alice_djvu.txt","size":"120000"},
                  {"name":"alicesadventures.epub","size":"450000"}
                ]}
                """
            )
        )
        assertNotNull(result)
        assertEquals(
            "https://archive.org/download/alicesadventures00carr/alicesadventures.epub",
            result!!.epubUrl
        )
        assertEquals(
            "https://archive.org/download/alicesadventures00carr/alice_djvu.txt",
            result.txtUrl
        )
    }

    @Test
    fun `a lending book is not offered`() {
        // Controlled digital lending: one copy at a time, files locked. Every
        // download answers 403 and no account changes that, so the honest thing
        // is to never list it.
        assertNull(
            files.downloadsIn(
                "somemodernnovel",
                metadata(
                    """
                    {"metadata":{"access-restricted-item":"true"},
                     "files":[{"name":"book.epub","size":"400000"}]}
                    """
                )
            )
        )
    }

    @Test
    fun `the inlibrary collection is the same restriction seen from the other side`() {
        assertNull(
            files.downloadsIn(
                "borrowable",
                metadata(
                    """
                    {"metadata":{"collection":["inlibrary","printdisabled"]},
                     "files":[{"name":"book.epub","size":"400000"}]}
                    """
                )
            )
        )
    }

    @Test
    fun `a scan-only item is not offered`() {
        // The archive's usual shape: page images, a derived text layer, a PDF,
        // and no EPUB. Most of what a search returns looks like this.
        assertNull(
            files.downloadsIn(
                "scannedbook",
                metadata(
                    """
                    {"files":[
                      {"name":"scannedbook.pdf","size":"9000000"},
                      {"name":"scannedbook_jp2.zip","size":"80000000"},
                      {"name":"scannedbook_djvu.txt","size":"200000"}
                    ]}
                    """
                )
            )
        )
    }

    @Test
    fun `a zero-length file is not a file`() {
        assertNull(
            files.downloadsIn(
                "emptyish",
                metadata("""{"files":[{"name":"book.epub","size":"0"}]}""")
            )
        )
    }

    @Test
    fun `a file name with spaces is still a usable link`() {
        val result = files.downloadsIn(
            "spaced",
            metadata("""{"files":[{"name":"The Book.epub","size":"1000"}]}""")
        )
        assertEquals("https://archive.org/download/spaced/The%20Book.epub", result!!.epubUrl)
    }

    @Test
    fun `an unrestricted item with no metadata block is still readable`() {
        // Absence of the restriction marker means unrestricted; treating a
        // missing metadata object as restricted would hide good books.
        assertNotNull(
            files.downloadsIn(
                "plain",
                metadata("""{"files":[{"name":"plain.epub","size":"1000"}]}""")
            )
        )
    }
}
