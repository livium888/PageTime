package com.pagetime.app.data.youtube

import com.pagetime.app.data.BookGenre
import com.pagetime.app.data.youtube.ReadableVideos.Candidate
import com.pagetime.app.data.youtube.ReadableVideos.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableVideosTest {

    private fun video(
        id: String,
        title: String,
        channel: String = "Channel $id",
        duration: String = "30:00",
        description: String = "",
        views: String = "120K views",
    ) = Candidate(id, title, channel, duration, description, views)

    @Test
    fun `lengths and views are read as YouTube writes them`() {
        assertEquals(47 * 60 + 12, ReadableVideos.seconds("47:12"))
        assertEquals(3600 + 2 * 60 + 3, ReadableVideos.seconds("1:02:03"))
        assertNull(ReadableVideos.seconds(""))
        assertNull(ReadableVideos.seconds("LIVE"))
        assertEquals(1_200_000L, ReadableVideos.views("1.2M views"))
        assertEquals(45_000L, ReadableVideos.views("45K views"))
        assertEquals(1_234L, ReadableVideos.views("1,234 views"))
        assertNull(ReadableVideos.views("No views"))
    }

    @Test
    fun `length is shown as what the reader will read`() {
        val pick = ReadableVideos.shelf(listOf(video("a", "A history lecture", duration = "30:00"))).single()
        // 30 minutes at 150 words a minute, read at 240.
        assertEquals(4_500, pick.words)
        assertEquals(19, pick.readMinutes)
        assertEquals("about 4,500 words · 19 min read", ReadableVideos.lengthLabel(pick))
    }

    @Test
    fun `only videos that fit are on a shelf`() {
        val shelf = ReadableVideos.shelf(
            listOf(
                video("short", "A short lecture", duration = "3:10"),
                video("fits", "A fitting lecture", duration = "45:00"),
                video("long", "A very long lecture", duration = "4:12:00"),
                video("live", "A live lecture", duration = ""),
            )
        )
        assertEquals(listOf("fits"), shelf.map { it.candidate.videoId })
        assertEquals(Fit.TOO_LONG, ReadableVideos.fit(4 * 3600 + 12 * 60))
        assertEquals(Fit.FITS, ReadableVideos.fit(ReadableVideos.MAX_SECONDS))
        assertEquals(80 * 60, ReadableVideos.MAX_SECONDS)
    }

    @Test
    fun `music, reactions and compilations are not offered, however popular`() {
        val shelf = ReadableVideos.shelf(
            listOf(
                video("m", "Artist - Song (Official Video)", views = "900M views"),
                video("r", "Reacting to the debate", views = "5M views"),
                video("c", "Top 10 science facts", views = "3M views"),
                video("ok", "Lecture on the Roman economy", views = "8K views"),
            )
        )
        assertEquals(listOf("ok"), shelf.map { it.candidate.videoId })
    }

    @Test
    fun `lectures, talks and interviews come before the unclassified, and views do not order anything`() {
        val shelf = ReadableVideos.shelf(
            listOf(
                video("other", "Thoughts on Rome", views = "50M views"),
                video("talk", "A talk about Rome", views = "5K views"),
                video("interview", "Interview with a historian", views = "2K views"),
            )
        )
        assertEquals(setOf("talk", "interview"), shelf.take(2).map { it.candidate.videoId }.toSet())
        assertEquals("other", shelf.last().candidate.videoId)
    }

    @Test
    fun `clickbait is marked down`() {
        assertTrue(ReadableVideos.clickbait("You won't believe what this lecturer says"))
        assertTrue(ReadableVideos.clickbait("THE TRUTH ABOUT SLEEP THEY HIDE FROM YOU"))
        assertFalse(ReadableVideos.clickbait("The Roman economy: a lecture"))
        val shelf = ReadableVideos.shelf(
            listOf(
                video("bait", "SHOCKING lecture on sleep!!"),
                video("plain", "A lecture on sleep"),
            )
        )
        assertEquals("plain", shelf.first().candidate.videoId)
    }

    @Test
    fun `one video per channel, no repeated titles, nothing already in the library`() {
        val shelf = ReadableVideos.shelf(
            listOf(
                video("1", "Lecture one", channel = "Open University"),
                video("2", "Lecture two", channel = "open university"),
                video("3", "A talk on Rome", channel = "History Hub"),
                video("4", "A Talk on Rome!", channel = "Another Channel"),
                video("5", "Owned lecture", channel = "Third Channel"),
            ),
            ownedTitles = listOf("Owned lecture"),
        )
        assertEquals(2, shelf.size)
        assertEquals(setOf("1", "3"), shelf.map { it.candidate.videoId }.toSet())
    }

    @Test
    fun `a channel the reader has read to the end is ranked up and says so`() {
        val shelf = ReadableVideos.shelf(
            listOf(
                video("a", "A lecture on Rome", channel = "Stranger"),
                video("b", "A lecture on Greece", channel = "Yale Courses"),
            ),
            knownChannels = setOf("Yale Courses"),
        )
        assertEquals("b", shelf.first().candidate.videoId)
        assertEquals("Lecture · a channel you've read before", shelf.first().why)
    }

    @Test
    fun `a typed search hides nothing but puts what reads well first and labels the rest`() {
        val results = ReadableVideos.search(
            listOf(
                video("music", "Artist - Song (Official Video)"),
                video("long", "Three hour talk", duration = "3:00:00"),
                video("good", "A lecture"),
            )
        )
        assertEquals(3, results.size)
        assertEquals("good", results.first().candidate.videoId)
        assertNull(ReadableVideos.caution(results.first()))
        assertTrue(ReadableVideos.caution(results.first { it.candidate.videoId == "music" })!!.contains("not speech"))
        assertTrue(ReadableVideos.caution(results.first { it.candidate.videoId == "long" })!!.contains("Too long"))
    }

    @Test
    fun `shelves come from the reader's own genres, then a general one`() {
        val plans = ReadableVideos.plans(
            listOf(BookGenre.HISTORY, BookGenre.OTHER, BookGenre.SCIENCE, BookGenre.FICTION, BookGenre.FANTASY)
        )
        assertEquals(listOf("History", "Science", "Writers on writing", "Talks and lectures"), plans.map { it.title })
        // No search is a bait phrase.
        plans.flatMap { it.queries }.forEach { assertFalse(it, ReadableVideos.clickbait(it)) }
        assertEquals(listOf("Talks and lectures"), ReadableVideos.plans(emptyList()).map { it.title })
    }
}
