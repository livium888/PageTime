package com.pagetime.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRulesTest {

    @Test
    fun `the package file is found from the container file`() {
        val xml = """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="x"/></rootfiles></container>"""
        assertEquals("OEBPS/content.opf", CoverRules.opfPath(xml))
        assertNull(CoverRules.opfPath("<container/>"))
    }

    @Test
    fun `an EPUB 3 cover-image property wins`() {
        val opf = """<manifest>
            <item id="c" href="images/front.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="x" href="images/other-cover.png" media-type="image/png"/>
        </manifest>"""
        assertEquals("images/front.jpg", CoverRules.coverHref(opf))
    }

    @Test
    fun `an EPUB 2 meta pointer finds the item, whatever the attribute order`() {
        val opf = """<metadata><meta content="cvr" name="cover"/></metadata><manifest>
            <item media-type="image/jpeg" href="art/c.jpg" id="cvr"/>
            <item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/>
        </manifest>"""
        assertEquals("art/c.jpg", CoverRules.coverHref(opf))
    }

    @Test
    fun `an image named cover is the last resort, and a page is never taken for one`() {
        val named = """<manifest><item id="a" href="cover.jpeg" media-type="image/jpeg"/></manifest>"""
        assertEquals("cover.jpeg", CoverRules.coverHref(named))
        val page = """<manifest><item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/></manifest>"""
        assertNull(CoverRules.coverHref(page))
    }

    @Test
    fun `paths are resolved inside the zip`() {
        assertEquals("OEBPS/images/front.jpg", CoverRules.resolve("OEBPS/content.opf", "images/front.jpg"))
        assertEquals("images/front.jpg", CoverRules.resolve("content.opf", "./images/front.jpg"))
        assertEquals("img/c.jpg", CoverRules.resolve("OEBPS/content.opf", "../img/c.jpg"))
        assertEquals("OEBPS/my cover.jpg", CoverRules.resolve("OEBPS/content.opf", "my%20cover.jpg"))
    }

    @Test
    fun `only a real video id counts`() {
        assertTrue(CoverRules.isVideoId("dQw4w9WgXcQ"))
        assertFalse(CoverRules.isVideoId("5f3c1a2b-9d4e-4c7a-8b1f-0a2b3c4d5e6f"))
        assertFalse(CoverRules.isVideoId("short"))
    }

    @Test
    fun `a catalogue answer must carry the book's own title, and author when known`() {
        val json = """{"docs":[
            {"title":"Cooking for Geeks: Real Science","author_name":["Jeff Potter"],"cover_i":111},
            {"title":"The Food Lab","author_name":["J. Kenji López-Alt"],"cover_i":222},
            {"title":"The Food Lab","author_name":["Someone Else"],"cover_i":333},
            {"title":"The Food Lab Notes","cover_i":444}]}"""
        assertEquals("https://covers.openlibrary.org/b/id/222-M.jpg", CoverRules.openLibraryCover(json, "Food Lab", "Kenji López-Alt"))
        assertEquals("https://covers.openlibrary.org/b/id/111-M.jpg", CoverRules.openLibraryCover(json, "Cooking for Geeks", ""))
        assertNull(CoverRules.openLibraryCover(json, "A Book Not There", ""))
        assertNull(CoverRules.openLibraryCover("not json", "Food Lab", ""))
    }

    @Test
    fun `a video title must match whole, and cover files are told apart by book`() {
        assertTrue(CoverRules.sameVideoTitle("Calculus: Lecture 1", "Calculus - Lecture 1"))
        assertFalse(CoverRules.sameVideoTitle("Calculus: Lecture 1", "Calculus: Lecture 2"))
        assertTrue(CoverRules.isCoverOf("yt-abc", "yt-abc-1700000000000.jpg"))
        assertFalse(CoverRules.isCoverOf("yt-abc", "yt-abc-def-1700000000000.jpg"))
        assertFalse(CoverRules.isCoverOf("yt-ab", "yt-abc-1700000000000.jpg"))
    }
}
