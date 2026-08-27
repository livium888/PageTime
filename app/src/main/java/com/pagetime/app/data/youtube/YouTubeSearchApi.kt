package com.pagetime.app.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Searches YouTube for videos.
 *
 * Uses the YouTube Data API v3 when a key is available, falling back to
 * parsing YouTube's public search results page with JSoup. Both approaches
 * are key-free for basic usage — the Data API key is optional and gives
 * higher-quality structured results.
 *
 * No API key is strictly required: the JSoup fallback always works.
 */
class YouTubeSearchApi(private val okHttpClient: OkHttpClient? = null) {

    private val client: OkHttpClient by lazy {
        okHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A single video search result.
     */
    data class SearchResult(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String,
        val duration: String,
        val description: String
    )

    /**
     * Search YouTube for videos matching [query].
     *
     * @param query The search query.
     * @param pageToken For API mode, the token for the next page (unused for HTML scraping).
     * @param apiKey Optional YouTube Data API v3 key. If null, falls back to HTML scraping.
     * @return A pair of (results list, nextPageToken or null).
     */
    suspend fun search(
        query: String,
        pageToken: String? = null,
        apiKey: String? = null
    ): Pair<List<SearchResult>, String?> = withContext(Dispatchers.IO) {
        if (!apiKey.isNullOrBlank()) {
            searchViaApi(query, apiKey, pageToken)
        } else {
            searchViaHtml(query)
        }
    }

    /**
     * Search using the YouTube Data API v3.
     * Quota cost: 100 units per search request.
     */
    private fun searchViaApi(
        query: String,
        apiKey: String,
        pageToken: String?
    ): Pair<List<SearchResult>, String?> {
        val tokenParam = if (!pageToken.isNullOrBlank()) "&pageToken=$pageToken" else ""
        val url = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&q=$query&type=video&maxResults=20&videoCaption=closedCaption" +
            "&key=$apiKey$tokenParam"

        val request = Request.Builder().url(url).get().build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("YouTube API error ${response.code}")
            response.body?.string() ?: throw RuntimeException("Empty response from YouTube API")
        }

        val json = JSONObject(body)
        val nextPageToken = json.optString("nextPageToken", null)
        val items = json.getJSONArray("items")

        val results = (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val id = item.getJSONObject("id")
            if (id.optString("kind") != "youtube#video") return@mapNotNull null
            val videoId = id.optString("videoId", "")
            if (videoId.isBlank()) return@mapNotNull null
            val snippet = item.getJSONObject("snippet")
            val title = snippet.optString("title", "")
            val channel = snippet.optString("channelTitle", "")
            val description = snippet.optString("description", "")
            val thumbnail = snippet
                .getJSONObject("thumbnails")
                .getJSONObject("high")
                .optString("url", "")
            SearchResult(
                videoId = videoId,
                title = title,
                channelName = channel,
                thumbnailUrl = thumbnail,
                duration = "",
                description = description
            )
        }

        return Pair(results, nextPageToken)
    }

    /**
     * Search by scraping YouTube's public search results page.
     * No API key needed. Parses the HTML with JSoup.
     */
    private fun searchViaHtml(query: String): Pair<List<SearchResult>, String?> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encodedQuery"

        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("YouTube search failed (${response.code})")
            response.body?.string() ?: throw RuntimeException("Empty response from YouTube")
        }

        val results = parseSearchResults(html)
        return Pair(results, null)
    }

    /**
     * Parse YouTube search results from HTML.
     * YouTube embeds video data in a JSON blob inside a <script> tag as
     * ytInitialData. We extract it and parse the relevant fields.
     */
    private fun parseSearchResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // YouTube embeds results in a JSON blob inside a <script> tag
        // as ytInitialData. Extract it.
        val jsonPattern = Regex("var ytInitialData\\s*=\\s*(\\{.+?\\});")
        val match = jsonPattern.find(html) ?: return results
        val jsonStr = match.groupValues[1]

        try {
            val json = JSONObject(jsonStr)
            val contents = json
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (i in 0 until contents.length()) {
                val section = contents.getJSONObject(i)
                if (!section.has("itemSectionRenderer")) continue
                val items = section
                    .getJSONObject("itemSectionRenderer")
                    .getJSONArray("contents")

                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    if (!item.has("videoRenderer")) continue
                    val video = item.getJSONObject("videoRenderer")
                    val videoId = video.optString("videoId", "")
                    if (videoId.isBlank()) continue

                    val title = video
                        .optJSONObject("title")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    val channelRun = video
                        .optJSONObject("ownerText")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                    val channel = channelRun?.optString("text", "") ?: ""

                    val thumbArray = video
                        .optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbnail = thumbArray
                        ?.optJSONObject(thumbArray.length() - 1)
                        ?.optString("url", "") ?: ""

                    val lengthText = video
                        .optJSONObject("lengthText")
                        ?.optString("simpleText", "") ?: ""

                    val descRuns = video
                        .optJSONArray("detailedMetadataSnippets")
                        ?.optJSONObject(0)
                        ?.optJSONObject("snippetText")
                        ?.optJSONArray("runs")
                    val description = if (descRuns != null) {
                        (0 until descRuns.length()).joinToString("") { idx ->
                            descRuns.optJSONObject(idx)?.optString("text", "") ?: ""
                        }
                    } else ""

                    results.add(
                        SearchResult(
                            videoId = videoId,
                            title = title,
                            channelName = channel,
                            thumbnailUrl = thumbnail,
                            duration = lengthText,
                            description = description
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback: try regex-based extraction from the raw HTML
            return parseSearchResultsFallback(html)
        }

        return results
    }

    /**
     * Fallback parser using regex when the JSON structure doesn't match.
     * Extracts video IDs and titles from href attributes and overlay text.
     */
    private fun parseSearchResultsFallback(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        // Match video renderer blocks: videoId, title text, channel
        val videoBlockPattern = Regex(
            "\"videoId\":\"([a-zA-Z0-9_-]{11})\""
        )
        val titlePattern = Regex(
            "\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\""
        )
        val channelPattern = Regex(
            "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\""
        )
        val lengthPattern = Regex(
            "\"lengthText\":\\{\"simpleText\":\"([^\"]+)\""
        )

        // Split by video blocks
        val videoBlocks = html.split("\"videoRenderer\"")

        for (block in videoBlocks.drop(1)) { // skip first (before any videoRenderer)
            val videoId = videoBlockPattern.find(block)?.groupValues?.get(1) ?: continue
            if (videoId in seen) continue
            seen.add(videoId)

            val title = titlePattern.find(block)?.groupValues?.get(1) ?: ""
            val channel = channelPattern.find(block)?.groupValues?.get(1) ?: ""
            val duration = lengthPattern.find(block)?.groupValues?.get(1) ?: ""

            if (title.isNotBlank()) {
                results.add(
                    SearchResult(
                        videoId = videoId,
                        title = title,
                        channelName = channel,
                        thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                        duration = duration,
                        description = ""
                    )
                )
            }
        }

        return results
    }
}
