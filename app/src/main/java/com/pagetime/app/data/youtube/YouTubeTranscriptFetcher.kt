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

            // Step 2: Pick the best matching caption track.
            val track = pickTrack(captionTracks, language)
                ?: captionTracks.firstOrNull()
                ?: return@withContext null

            val captionUrl = track.optString("baseUrl", "")
                .replace("&fmt=srv3", "")
            if (captionUrl.isBlank()) return@withContext null

            // Step 3: Fetch the timed text and extract plain text.
            val rawXml = fetchUrl(captionUrl)
            val text = extractTextFromTimedText(rawXml)
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
     * Picks the best caption track for the desired language.
     * Prefers an exact language match, then falls back to any track.
     */
    private fun pickTrack(tracks: List<JSONObject>, language: String): JSONObject? {
        // Exact match (e.g. "en")
        tracks.firstOrNull {
            it.optString("languageCode") == language
        }?.let { return it }

        // Prefix match (e.g. "en" matches "en-US")
        tracks.firstOrNull {
            it.optString("languageCode").startsWith(language)
        }?.let { return it }

        // Prefer manual over auto-generated
        tracks.firstOrNull {
            it.optString("kind") != "asr"
        }?.let { return it }

        return null
    }

    /**
     * Extracts plain text from YouTube's timed text XML format.
     * Each `<text>` element contains a segment of the transcript.
     */
    private fun extractTextFromTimedText(xml: String): String {
        val segments = mutableListOf<String>()
        val pattern = Regex("""<text[^>]*>([^<]*)</text>""")
        for (match in pattern.findAll(xml)) {
            val text = match.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("\n", " ")
                .trim()
            if (text.isNotBlank()) {
                segments.add(text)
            }
        }
        return segments.joinToString("\n\n")
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
