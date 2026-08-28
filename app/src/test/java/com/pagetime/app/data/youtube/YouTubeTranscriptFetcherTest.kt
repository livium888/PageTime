package com.pagetime.app.data.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the transcript assembly pipeline. The critical one is
 * [.manual track is preferred and never mixed with its asr twin]: a video that
 * has BOTH a curated manual track and an auto-generated track used to have both
 * fetched and merged. The two are not time-aligned, so the merge interleaved
 * every sentence twice and the transcript read as garbled / "missing" text —
 * the end of one page never connected to the start of the next.
 */
class YouTubeTranscriptFetcherTest {

    private fun track(languageCode: String, kind: String? = null): JSONObject =
        JSONObject().apply {
            put("languageCode", languageCode)
            put("baseUrl", "https://example.com/captions")
            if (kind != null) put("kind", kind)
        }

    @Test
    fun `manual track is preferred and never mixed with its asr twin`() {
        val fetcher = YouTubeTranscriptFetcher()
        val selected = fetcher.selectTracks(
            listOf(track("en", "asr"), track("en")),
            "en"
        )
        assertEquals(1, selected.size)
        assertEquals("", selected[0].optString("kind"))
    }

    @Test
    fun `regional asr variants are excluded when a manual track exists`() {
        val fetcher = YouTubeTranscriptFetcher()
        val selected = fetcher.selectTracks(
            listOf(track("en", "asr"), track("en-US", "asr"), track("en")),
            "en"
        )
        assertEquals(1, selected.size)
        assertEquals("", selected[0].optString("kind"))
    }

    @Test
    fun `asr tracks are all used when no manual track exists`() {
        val fetcher = YouTubeTranscriptFetcher()
        val selected = fetcher.selectTracks(
            listOf(track("en", "asr"), track("en-US", "asr")),
            "en"
        )
        assertEquals(2, selected.size)
        assertTrue(selected.all { it.optString("kind") == "asr" })
    }

    @Test
    fun `language mismatch falls back to a single curated manual track`() {
        val fetcher = YouTubeTranscriptFetcher()
        val selected = fetcher.selectTracks(
            listOf(track("de", "asr"), track("de")),
            "en"
        )
        assertEquals(1, selected.size)
        assertEquals("", selected[0].optString("kind"))
    }

    @Test
    fun `extractSegments parses realistic timedtext without dropping lines`() {
        val fetcher = YouTubeTranscriptFetcher()
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><transcript>" +
            "<text start=\"0.1\" dur=\"4.72\">- There are decades where nothing happens\nand weeks where decades happen.</text>" +
            "<text start=\"5.78\" dur=\"3.78\">And we have seen decades of progress</text>" +
            "<text start=\"9.62\" dur=\"4.19\">happen in the last nine months.<br>If you&amp;#39;re not recognizing the</text>" +
            "</transcript>"
        val segments = fetcher.extractSegments(xml)
        assertEquals(3, segments.size)
        assertTrue(segments[0].text.contains("decades where nothing happens"))
        assertTrue(segments[0].text.contains("weeks where decades happen"))
        assertTrue(segments[2].text.contains("If you're not recognizing the"))
    }

    @Test
    fun `mergeSegments keeps distinct starts and drops true duplicates`() {
        val fetcher = YouTubeTranscriptFetcher()
        val merged = fetcher.mergeSegments(
            listOf(
                YouTubeTranscriptFetcher.CaptionSegment(4.0, 2.0, "World"),
                YouTubeTranscriptFetcher.CaptionSegment(1.05, 2.0, "Hello there"),
                YouTubeTranscriptFetcher.CaptionSegment(1.0, 2.0, "Hello there")
            )
        )
        assertEquals(2, merged.size)
        assertEquals(1.0, merged[0].start, 0.001)
        assertEquals(4.0, merged[1].start, 0.001)
    }

    @Test
    fun `formatTranscript joins every segment so no text is lost`() {
        val fetcher = YouTubeTranscriptFetcher()
        val segments = (0 until 20).map { i ->
            YouTubeTranscriptFetcher.CaptionSegment(i * 3.0, 2.0, "word$i")
        }
        val out = fetcher.formatTranscript(segments)
        for (i in 0 until 20) {
            assertTrue("word$i must survive formatting", out.contains("word$i"))
        }
        // Adjacent segments flow into one continuous stream, not separate blocks.
        assertTrue(out.contains("word5 word6"))
        assertTrue(out.contains("Chapter 1"))
    }
}
