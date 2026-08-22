package com.pagetime.app.data.download

import android.content.Context
import com.pagetime.app.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class BookDownloader(
    private val context: Context,
    // No callTimeout: EPUB/TXT downloads are large and total time depends on
    // connection speed. Read-timeout still aborts stalled transfers.
    private val client: OkHttpClient = AppHttp.newClient()
) {
    private val booksDir: File
        get() = File(context.filesDir, "books").apply { mkdirs() }

    /** Downloads [url] into filesDir/books and returns the local file. */
    suspend fun download(url: String, destinationName: String): File = withContext(Dispatchers.IO) {
        val dest = File(booksDir, destinationName)
        if (dest.exists() && dest.length() > 0) return@withContext dest

        val request = Request.Builder().url(url).get().build()

        // Project Gutenberg's file servers are heavily rate-limited and frequently
        // answer with 503/502/429 ("try again later"). The download isn't even
        // attempted again until the user taps Get a second time. Retry transient
        // responses and network failures a few times with backoff before giving up.
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            // Wipe any partial file from a previous (failed) attempt before retrying
            // so we never hand back a truncated book.
            if (dest.exists()) dest.delete()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body ?: throw RuntimeException("Empty download response")
                        body.byteStream().use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (dest.length() > 0) return@withContext dest
                        lastError = RuntimeException("Downloaded file was empty")
                        return@use
                    }
                    val code = response.code
                    if (isTransient(code)) {
                        lastError = RuntimeException("Gutenberg is busy ($code). Retrying…")
                        return@use
                    }
                    throw RuntimeException("Download failed ($code)")
                }
            } catch (e: IOException) {
                // Network-level timeout / connection reset — retryable.
                lastError = RuntimeException("Couldn't reach Project Gutenberg — check your connection", e)
            }
            attempt++
            if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
        }
        throw lastError ?: RuntimeException("Download failed")
    }

    private fun isTransient(code: Int): Boolean =
        code == 429 || code == 502 || code == 503 || code == 504 || code >= 500

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 900L
    }
}
