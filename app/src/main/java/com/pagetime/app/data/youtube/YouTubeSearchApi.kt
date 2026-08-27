package com.pagetime.app.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Searches YouTube for videos.
 *
 * Uses the innertube search endpoint (the same JSON API the YouTube website
 * calls) — no API key required. When a YouTube Data API v3 key is available,
 * that is preferred because it can filter to videos with closed captions.
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
     * @param pageToken For Data API mode, the token for the next page.
     * @param apiKey Optional YouTube Data API v3 key. If null, uses the
     *   key-free innertube search endpoint.
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
            searchViaInnertube(query)
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
     * Search using YouTube's innertube /search endpoint — the same JSON API
     * the YouTube website itself calls. No API key needed.
     */
    private fun searchViaInnertube(query: String): Pair<List<SearchResult>, String?> {
        val body = JSONObject()
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", "2.20240726.01.00")
                )
            )
            .put("query", query)
            .toString()

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val jsonBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("YouTube search failed (${response.code})")
            response.body?.string() ?: throw RuntimeException("Empty response from YouTube")
        }

        val results = parseSearchResults(jsonBody)
        return Pair(results, null)
    }

    /**
     * Parse video results from a YouTube innertube search JSON response.
     * The response mirrors the shape of the old ytInitialData blob:
     * contents.twoColumnSearchResultsRenderer.primaryContents.
     *   sectionListRenderer.contents[].itemSectionRenderer.contents[].
     *   videoRenderer
     */
    private fun parseSearchResults(jsonStr: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
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
            // Malformed or unexpected response — return what we have so far.
        }

        return results
    }
}
