package com.pagetime.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Streams a model file; injected so unit tests can fake the network. */
interface ModelDownloader {
    /** Downloads [url] into [target], reporting (downloaded, total) bytes. */
    suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): Result<File>
}

/** Real downloader backed by the app's resilient OkHttp client. */
class OkHttpModelDownloader : ModelDownloader {
    override suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                // No call-timeout cap: OkHttp's callTimeout bounds the WHOLE
                // exchange (headers + body) and a large model file legitimately
                // takes minutes on a slow connection. The client's read timeout
                // still aborts a stalled connection between reads.
                AppHttp.newClient(callTimeoutSeconds = 0L).newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Model download failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Model download returned no body")
                    // Zero when the server does not say; progress is then
                    // reported by bytes so far and the end of the body ends it.
                    val total = body.contentLength().coerceAtLeast(0L)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val source = body.source()
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastProgressAt = 0L
                        while (true) {
                            // Stop once the declared size is reached, for a
                            // server that never ends the body; the store's own
                            // size check catches any mismatch afterwards.
                            if (total > 0 && downloaded >= total) break
                            val read = source.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            // At least every 250 ms, so a slow start does not
                            // look frozen at 0 MB.
                            val elapsed = now - lastProgressAt
                            if (downloaded >= total * PROGRESS_MIN_FRACTION || elapsed >= PROGRESS_MIN_INTERVAL_MS) {
                                onProgress(downloaded, total)
                                lastProgressAt = now
                            }
                        }
                        onProgress(downloaded, total)
                    }
                    target
                }
            }
        }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_MIN_INTERVAL_MS = 250L
        // Also fire on the first ~2% so the UI immediately shows movement.
        const val PROGRESS_MIN_FRACTION = 0.02
    }
}
