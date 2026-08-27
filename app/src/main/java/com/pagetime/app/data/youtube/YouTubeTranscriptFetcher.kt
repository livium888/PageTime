package com.pagetime.app.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.thoroldvix.api.TranscriptApiFactory
import org.thoroldvix.api.YoutubeClient
import org.thoroldvix.api.YoutubeTranscriptApi
import org.thoroldvix.api.formatter.TranscriptFormatters
import java.util.concurrent.TimeUnit

/**
 * Fetches YouTube video transcripts using the public caption endpoint.
 *
 * Uses the `youtube-transcript-api` Java library with a custom OkHttp client
 * (Android doesn't include Java 11 HttpClient). No API key is needed —
 * YouTube's caption tracks are publicly accessible for any video that has
 * subtitles (manual or auto-generated).
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

    private val api: YoutubeTranscriptApi by lazy {
        TranscriptApiFactory.createWithClient(OkHttpYoutubeClient(client))
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
            val content = api.getTranscript(videoId, language)
            val text = TranscriptFormatters.textFormatter().format(content)
            if (text.isBlank()) return@withContext null
            // Fetch the real title + author from YouTube's public oEmbed endpoint.
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

    /**
     * OkHttp-based implementation of the library's YoutubeClient interface.
     * Required because Android doesn't include Java 11 HttpClient.
     */
    private class OkHttpYoutubeClient(private val client: OkHttpClient) : YoutubeClient {
        override fun get(url: String, headers: Map<String, String>): String {
            val requestBuilder = okhttp3.Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: $url")
                }
                return response.body?.string() ?: throw RuntimeException("Empty response from $url")
            }
        }

        override fun post(url: String, json: String): String {
            val body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"),
                json
            )
            val request = okhttp3.Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: $url")
                }
                return response.body?.string() ?: throw RuntimeException("Empty response from $url")
            }
        }
    }
}
