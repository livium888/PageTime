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
        val found = VideoOwnership.find(library, "newid", "Why we sleep", "yale courses")
        assertEquals("yt-5f3c-uuid", found?.id)
    }

    @Test
    fun `the same title from another channel is a different video`() {
        assertNull(VideoOwnership.find(library, "newid", "Why We Sleep", "Some Other Channel"))
    }

    @Test
    fun `an ordinary book with the same title is not a transcript`() {
        val onlyBook = listOf(OwnedBook("epub-1", "The Roman Economy", "Open University"))
        assertNull(VideoOwnership.find(onlyBook, "x", "The Roman Economy", "Open University"))
    }

    @Test
    fun `a missing channel does not stop a title match`() {
        assertEquals("yt-5f3c-uuid", VideoOwnership.find(library, "n", "Why We Sleep", null)?.id)
        assertEquals("yt-5f3c-uuid", VideoOwnership.find(library, "n", "Why We Sleep", "")?.id)
    }
}
