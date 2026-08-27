package com.pagetime.app.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches YouTube video transcripts using YouTube's innertube API.
 *
 * No API key is needed — YouTube's innertube endpoint is publicly accessible
 * for any video that has subtitles (manual or auto-generated).
 *
 * The fetched transcript is converted to clean, readable text and saved
 * as a plain-text book in PageTime's library.
 */
class YouTubeTranscriptFetcher(private val okHttpClient: OkHttpClient? = null) {

    private val client: OkHttpClient by lazy {
        okHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Result of fetching a YouTube transcript.
     */
    data class TranscriptResult(
        val title: String,
        val author: String,
        val text: String
    )

    /**
     * Fetches the transcript for a YouTube video, including the real
     * video title via YouTube's oEmbed endpoint.
     *
     * @param videoId The YouTube video ID (11 characters).
     * @param language Preferred transcript language code (defaults to "en").
     * @return The transcript as readable text, or null if unavailable.
     */
    suspend fun fetchTranscript(
        videoId: String,
        language: String = "en"
    ): TranscriptResult? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Get available caption tracks via innertube player endpoint.
            val captionTracks = getCaptionTracks(videoId)
            if (captionTracks.isEmpty()) return@withContext null

            // Step 2: Fetch the timed text for EVERY track that matches the
            // preferred language and merge them. YouTube sometimes splits long
            // transcripts across several tracks with the same language code, or
            // pairs a short preview track with the full one — fetching only a
            // single track would silently drop large stretches of content.
            val tracks = selectTracks(captionTracks, language)
            if (tracks.isEmpty()) return@withContext null
            val segments = tracks.flatMap { track ->
                runCatching { fetchTrackSegments(track) }.getOrDefault(emptyList())
            }
            val merged = mergeSegments(segments)
            if (merged.isEmpty()) return@withContext null
            val text = formatTranscript(merged)
            if (text.isBlank()) return@withContext null

            // Step 4: Fetch the real title + author from YouTube's oEmbed endpoint.
            val (title, author) = fetchVideoMetadata(videoId)
            TranscriptResult(
                title = title,
                author = author,
                text = text
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Uses YouTube's innertube /player endpoint to get the list of available
     * caption tracks for a video.
     *
     * This mirrors how the community youtube-transcript-api library works:
     * 1. Fetch the watch page HTML and extract the INNERTUBE_API_KEY it embeds.
     * 2. POST to /youtubei/v1/player?key=... with the ANDROID client context
     *    (the WEB client no longer returns caption tracks reliably).
     * 3. Read captionTracks from the response.
     */
    private fun getCaptionTracks(videoId: String): List<JSONObject> {
        val apiKey = extractInnertubeApiKey(videoId)
        val body = JSONObject()
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", "20.10.38")
                )
            )
            .put("videoId", videoId)
            .toString()

        val url = if (apiKey.isBlank()) {
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        } else {
            "https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false"
        }
        val responseBody = fetchUrl(url, body)
        val json = JSONObject(responseBody)

        val captions = json.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return emptyList()

        return (0 until captions.length()).map { captions.getJSONObject(it) }
    }

    /**
     * Fetches the video's watch page and extracts the INNERTUBE_API_KEY that
     * YouTube embeds in the page. Used as the `key` param on innertube calls.
     */
    private fun extractInnertubeApiKey(videoId: String): String {
        return try {
            val html = fetchUrl("https://www.youtube.com/watch?v=$videoId")
            val pattern = Regex("\"INNERTUBE_API_KEY\":\\s*\"([a-zA-Z0-9_-]+)\"")
            pattern.find(html)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Fetches raw text from a URL via GET (or POST when [postBody] is set).
     */
    private fun fetchUrl(url: String, postBody: String? = null): String {
        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        if (postBody != null) {
            requestBuilder
                .post(postBody.toRequestBody("application/json".toMediaType()))
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: $url")
            }
            return response.body?.string() ?: throw RuntimeException("Empty response from $url")
        }
    }

    /**
     * Selects every caption track that matches the preferred language.
     * Exact language matches first, then prefix matches ("en" matches
     * "en-US"), then any manual (non-auto-generated) track, then all tracks.
     * All selected tracks are fetched and merged so nothing is lost.
     */
    private fun selectTracks(tracks: List<JSONObject>, language: String): List<JSONObject> {
        val exact = tracks.filter { it.optString("languageCode") == language }
        if (exact.isNotEmpty()) return exact
        val prefix = tracks.filter { it.optString("languageCode").startsWith(language) }
        if (prefix.isNotEmpty()) return prefix
        val manual = tracks.filter { it.optString("kind") != "asr" }
        if (manual.isNotEmpty()) return manual
        return tracks
    }

    /**
     * Fetches the timed text for a single caption track and parses it into
     * timed segments. Handles both the XML format and YouTube's srv3 JSON.
     */
    private fun fetchTrackSegments(track: JSONObject): List<CaptionSegment> {
        // Strip fmt=srv3 when it appears anywhere in the query string so the
        // default XML format is returned (srv3 JSON is still parsed as a
        // fallback if the URL cannot be rewritten).
        val captionUrl = track.optString("baseUrl", "")
            .replace("?fmt=srv3", "?")
            .replace("&fmt=srv3", "")
        if (captionUrl.isBlank()) return emptyList()
        val raw = fetchUrl(captionUrl)
        return extractSegments(raw)
    }

    /**
     * Extracts timed caption segments from a caption track response.
     * Handles both YouTube's XML format (`<text start=...>`) and the srv3
     * JSON format (events with tStartMs + segs).
     */
    private fun extractSegments(raw: String): List<CaptionSegment> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return extractSrv3Json(trimmed)
        }
        // Normalize stray line breaks inside captions so the element regex
        // never splits a caption in half.
        val xml = trimmed.replace("<br>", " ").replace("<br/>", " ")
        val segments = mutableListOf<CaptionSegment>()
        val pattern = Regex("""<text\b([^>]*)>([^<]*)</text>""")
        val startAttr = Regex("""start="([\d.]+)""")
        val durAttr = Regex("""dur="([\d.]+)""")
        for (match in pattern.findAll(xml)) {
            val attrs = match.groupValues[1]
            val rawText = match.groupValues[2]
            val start = startAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val dur = durAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 2.0
            val text = cleanSegment(rawText)
            if (text.isNotBlank()) {
                segments += CaptionSegment(start, dur, text)
            }
        }
        return segments
    }

    /** Parses YouTube's srv3 JSON transcript format. */
    private fun extractSrv3Json(json: String): List<CaptionSegment> {
        return try {
            val root = JSONObject(json)
            val events = root.optJSONArray("events") ?: return emptyList()
            val segments = mutableListOf<CaptionSegment>()
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val startMs = event.optLong("tStartMs", -1)
                if (startMs < 0) continue
                val segs = event.optJSONArray("segs") ?: continue
                val rawText = (0 until segs.length()).joinToString("") { idx ->
                    segs.getJSONObject(idx).optString("utf8", "")
                }
                val text = cleanSegment(rawText)
                if (text.isNotBlank()) {
                    segments += CaptionSegment(startMs / 1000.0, 2.0, text)
                }
            }
            segments
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Merges segments fetched from multiple caption tracks: sorts by start
     * time and drops true duplicates (same start instant) — preview tracks
     * repeat the same lines as the full track. Distinct lines are all kept,
     * so content from split tracks is reassembled in the right order.
     */
    private fun mergeSegments(all: List<CaptionSegment>): List<CaptionSegment> {
        if (all.size <= 1) return all
        val sorted = all.sortedWith(compareBy({ it.start }, { -it.text.length }))
        val merged = mutableListOf<CaptionSegment>()
        var lastStart = Double.NEGATIVE_INFINITY
        for (segment in sorted) {
            if (segment.start - lastStart < 0.1) continue
            merged += segment
            lastStart = segment.start
        }
        return merged
    }

    /**
     * Cleans a single caption line: decodes entities, drops YouTube's
     * bracket sound tags ([Music], [Applause], …), collapses ASR word
     * repetitions ("I I I" → "I"), and normalizes whitespace.
     */
    private fun cleanSegment(raw: String): String {
        var text = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\n", " ")
        // YouTube ASR marks non-speech as [Music], [Applause], [Laughter], …
        text = SOUND_TAG_PATTERN.replace(text, "")
        // Instrumental cues like [♪♪♪] carry no words — drop the whole tag.
        text = MUSIC_TAG_PATTERN.replace(text, " ")
        // Music-note glyphs (♪ ♫) wrap song lyrics; strip them, keep the words.
        text = MUSIC_GLYPH_PATTERN.replace(text, " ")
        // Auto-captions repeat a word when the speaker emphasizes or stumbles
        // ("I I I", "the the", "from from"). Collapse any 2+ run to one.
        text = REPEATED_WORD_PATTERN.replace(text, "$1")
        // ASR glues a short number to the preceding word ("spending8" → "spending 8").
        text = WORD_DIGIT_PATTERN.replace(text, "$1 $2")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Turns timed caption segments into a book-like transcript.
     *
     * Captions are joined into one continuous flow of prose. The ONLY paragraph
     * break is a new speaker turn (`>>` marker). No word-count or pause-based
     * breaks: ASR text has no sentence punctuation, so any timed break lands
     * mid-sentence and makes the next page start with a stray fragment — which
     * reads as "missing pages". With no artificial breaks, the reader simply
     * paginates the flow and sentences continue across page turns like a real
     * book. A `Chapter N — m:ss` heading is emitted only at speaker-turn breaks
     * roughly every 15 minutes.
     */
    private fun formatTranscript(segments: List<CaptionSegment>): String {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var paragraphStart = 0.0
        var chapterIndex = 0
        var nextChapterAt = 0.0

        fun flushParagraph() {
            if (current.isNotBlank()) {
                val body = current.toString().trim()
                if (paragraphStart >= nextChapterAt) {
                    paragraphs += "Chapter ${chapterIndex + 1} — ${formatTimestamp(paragraphStart)}"
                    paragraphs += body
                    chapterIndex++
                    nextChapterAt = paragraphStart + CHAPTER_INTERVAL_SECONDS
                } else {
                    paragraphs += body
                }
                current.setLength(0)
            }
        }

        for (segment in segments) {
            val isSpeakerTurn = segment.text.startsWith(">>")
            if (current.isNotBlank() && isSpeakerTurn) {
                flushParagraph()
            }
            if (current.isBlank()) paragraphStart = segment.start
            if (current.isNotBlank() && !isSpeakerTurn) current.append(' ')
            current.append(segment.text)
        }
        flushParagraph()
        return paragraphs.joinToString("\n\n")
    }

    /** Converts seconds to a compact `m:ss` (or `h:mm:ss`) timestamp. */
    private fun formatTimestamp(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        } else {
            "$m:${s.toString().padStart(2, '0')}"
        }
    }

    private data class CaptionSegment(
        val start: Double,
        val duration: Double,
        val text: String
    )

    private companion object {
        val SOUND_TAG_PATTERN = Regex(
            """\[(?i)(music|applause|laughter|cheering|audience|crowd|singing|noise|sound)[^\]]*]"""
        )
        val MUSIC_TAG_PATTERN = Regex("""\[[♪♫][^\]]*]""")
        val MUSIC_GLYPH_PATTERN = Regex("[♪♫]")
        val REPEATED_WORD_PATTERN = Regex("""\b(\w+)(?: \1)+\b""")
        val WORD_DIGIT_PATTERN = Regex("""([a-zA-Z])(\d)""")
        /** A chapter marker is emitted only at speaker-turn breaks, ~15 min apart. */
        const val CHAPTER_INTERVAL_SECONDS = 900.0
    }

    /**
     * Fetches video title and author name from YouTube's oEmbed endpoint.
     * No API key required — this is a public, unauthenticated endpoint.
     */
    private fun fetchVideoMetadata(videoId: String): Pair<String, String> {
        return try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = okhttp3.Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val title = json.optString("title", "YouTube Transcript")
                    val authorName = json.optString("author_name", "YouTube")
                    Pair(title, authorName)
                } else {
                    null
                }
            } ?: Pair("YouTube Transcript", "YouTube")
        } catch (e: Exception) {
            Pair("YouTube Transcript", "YouTube")
        }
    }

    /**
     * Attempts to extract a video ID from various YouTube URL formats:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/embed/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     * - https://m.youtube.com/watch?v=VIDEO_ID
     */
    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        // Direct short URL: youtu.be/ID
        val shortMatch = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""").find(trimmed)
        if (shortMatch != null) return shortMatch.groupValues[1]
        // Standard URL with v= parameter
        val watchMatch = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(trimmed)
        if (watchMatch != null) return watchMatch.groupValues[1]
        // Embed or shorts URL: /embed/ID or /shorts/ID
        val pathMatch = Regex("""/(?:embed|shorts)/([a-zA-Z0-9_-]{11})""").find(trimmed)
        if (pathMatch != null) return pathMatch.groupValues[1]
        // Bare 11-char video ID
        if (trimmed.matches(Regex("""[a-zA-Z0-9_-]{11}"""))) return trimmed
        return null
    }

    /** Checks whether a URL looks like a YouTube link. */
    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }
}
