package com.pagetime.app.data.download

import android.content.Context
import com.pagetime.app.data.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Download failed (${response.code})")
            val body = response.body ?: throw RuntimeException("Empty download response")
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dest
    }
}
