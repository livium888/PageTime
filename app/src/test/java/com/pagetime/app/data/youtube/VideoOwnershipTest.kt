package com.pagetime.app.data.youtube

import com.pagetime.app.data.youtube.VideoOwnership.OwnedBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoOwnershipTest {

    private val library = listOf(
        OwnedBook("yt-abc123", "The Roman Economy", "Open University"),
        OwnedBook("yt-5f3c-uuid", "Why We Sleep: a lecture", "Yale Courses"),
        OwnedBook("epub-1", "The Roman Economy", "Open University"),
    )

    @Test
    fun `a video already added is found by its id alone`() {
        assertEquals("yt-abc123", VideoOwnership.find(library, "abc123")?.id)
        assertEquals("yt-abc123", VideoOwnership.idFor("abc123"))
    }

    @Test
    fun `an unknown video is not found, and without a title nothing else is tried`() {
        assertNull(VideoOwnership.find(library, "zzz999"))
    }

    @Test
    fun `a transcript added before ids named their video is found by title and channel`() {
        val found = VideoOwnership.find(library, "newid", "Why we sleep: A Lecture", "yale courses")
        assertEquals("yt-5f3c-uuid", found?.id)
    }

    @Test
    fun `the same title from another channel is a different video`() {
        assertNull(VideoOwnership.find(library, "newid", "Why We Sleep: a lecture", "Some Other Channel"))
    }

    @Test
    fun `an ordinary book with the same title is not a transcript`() {
        val onlyBook = listOf(OwnedBook("epub-1", "The Roman Economy", "Open University"))
        assertNull(VideoOwnership.find(onlyBook, "x", "The Roman Economy", "Open University"))
    }

    @Test
    fun `a missing channel does not stop a title match`() {
        assertEquals("yt-5f3c-uuid", VideoOwnership.find(library, "n", "Why We Sleep: a lecture", null)?.id)
        assertEquals("yt-5f3c-uuid", VideoOwnership.find(library, "n", "Why We Sleep: a lecture", "")?.id)
    }

    @Test
    fun `a video book is covered by its video's picture, an older one by nothing`() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", VideoOwnership.coverFor("yt-dQw4w9WgXcQ", null))
        assertEquals("keep", VideoOwnership.coverFor("yt-dQw4w9WgXcQ", "keep"))
        assertNull(VideoOwnership.coverFor("yt-5f3c1a2b-9d4e-4c7a-8b1f-0a2b3c4d5e6f", null))
        assertNull(VideoOwnership.coverFor("epub-1", null))
    }

    @Test
    fun `lectures in a series are not taken for one another`() {
        val owned = listOf(OwnedBook("yt-AAAAAAAAAAA", "Calculus: Lecture 1", "Open University"))
        assertNull(VideoOwnership.find(owned, "BBBBBBBBBBB", "Calculus: Lecture 2", "Open University"))
        assertEquals("yt-AAAAAAAAAAA", VideoOwnership.find(owned, "CCCCCCCCCCC", "Calculus - Lecture 1!", "Open University")?.id)
    }

    @Test
    fun `videos whose title lookup failed are not taken for one another`() {
        val owned = listOf(OwnedBook("yt-AAAAAAAAAAA", "YouTube Transcript", "YouTube"))
        // A second video that also came back untitled is a different video.
        assertNull(VideoOwnership.find(owned, "BBBBBBBBBBB", "YouTube Transcript", "YouTube"))
        assertNull(VideoOwnership.find(owned, "BBBBBBBBBBB", "A real title", "Some Channel"))
        // And an untitled book is never the match for a real one.
        assertEquals("yt-AAAAAAAAAAA", VideoOwnership.find(owned, "AAAAAAAAAAA", "x", "y")?.id)
    }
}
