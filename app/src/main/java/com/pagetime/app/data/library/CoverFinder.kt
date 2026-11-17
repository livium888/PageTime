package com.pagetime.app.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.LibraryRepository
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.isReadiumBook
import com.pagetime.app.data.recommend.ReaderPicks
import com.pagetime.app.data.youtube.VideoOwnership
import com.pagetime.app.data.youtube.YouTubeSearchApi
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds a cover for a book that is already in the library.
 *
 * Tried from the most trustworthy source to the least: the book's own cover
 * (inside the EPUB, or the first page of the PDF), then, for a video
 * transcript, the video's picture, then Open Library. Each outside answer
 * must carry the book's own title ([CoverRules]) because a wrong cover is
 * worse than none.
 *
 * Text pasted in by the reader has no outside identity, so it is never looked
 * up: asking a catalogue about "Notes" would return somebody else's book.
 */
class CoverFinder(
    private val context: Context,
    private val bookDao: BookDao,
    private val library: LibraryRepository,
    private val youtube: YouTubeSearchApi,
    private val client: OkHttpClient = AppHttp.newClient(callTimeoutSeconds = 30L),
) {
    data class Outcome(val tried: Int, val found: Int)

    /** Can a cover be looked for at all? Pasted text cannot. */
    fun canLookUp(book: BookEntity): Boolean = !book.id.startsWith("paste-")

    /** Books that have no cover and could be given one. */
    fun missing(books: List<BookEntity>): List<BookEntity> =
        books.filter { it.coverUrl == null && canLookUp(it) }

    /** One book. [replace] looks again even when it already has a cover. */
    suspend fun find(book: BookEntity, replace: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!canLookUp(book) || (book.coverUrl != null && !replace)) return@withContext null
        val cover = runCatching { lookUp(book) }.getOrNull() ?: return@withContext null
        if (cover == book.coverUrl) return@withContext null
        bookDao.updateCover(book.id, cover)
        cover
    }

    /** Every book without a cover, one after the other, reporting as it goes. */
    suspend fun findAll(books: List<BookEntity>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        val todo = missing(books)
        var found = 0
        todo.forEachIndexed { index, book ->
            onProgress(index, todo.size)
            if (find(book) != null) found++
        }
        onProgress(todo.size, todo.size)
        return Outcome(todo.size, found)
    }

    private suspend fun lookUp(book: BookEntity): String? {
        // A video book carries its video in its id.
        if (book.id.startsWith("yt-")) {
            val videoId = book.id.removePrefix("yt-")
            if (CoverRules.isVideoId(videoId)) return VideoOwnership.thumbnailFor(videoId)
            return videoThumbnailByTitle(book)
        }
        if (book.format == "epub") embeddedEpubCover(book)?.let { return it }
        if (book.format == "pdf") firstPageOfPdf(book)?.let { return it }
        return openLibraryCover(book)
    }

    /**
     * A transcript from before video books were named for their video: ask
     * YouTube for the title, and accept only a result with the same title (and
     * channel, when the book knows it).
     */
    private suspend fun videoThumbnailByTitle(book: BookEntity): String? {
        val (results, _) = youtube.search("${book.title} ${book.author}".trim())
        val channelKey = ReaderPicks.titleKey(book.author)
        val match = results.firstOrNull { result ->
            CoverRules.sameTitle(book.title, result.title) &&
                (channelKey.isBlank() || ReaderPicks.titleKey(result.channelName) == channelKey)
        } ?: return null
        return VideoOwnership.thumbnailFor(match.videoId)
    }

    private fun openLibraryCover(book: BookEntity): String? {
        val url = "https://openlibrary.org/search.json".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("title", book.title.substringBefore(':'))
            ?.apply { if (book.author.isNotBlank()) addQueryParameter("author", book.author) }
            ?.addQueryParameter("limit", "10")
            ?.addQueryParameter("fields", "title,author_name,cover_i")
            ?.build() ?: return null
        val body = client.newCall(Request.Builder().url(url).get().build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        } ?: return null
        return CoverRules.openLibraryCover(body, book.title, book.author)
    }

    /** The cover a publisher put inside the EPUB, saved as a small picture of its own. */
    private fun embeddedEpubCover(book: BookEntity): String? {
        val file = File(book.localPath)
        if (!file.exists()) return null
        ZipFile(file).use { zip ->
            fun text(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().readText() }
            val opfPath = CoverRules.opfPath(text("META-INF/container.xml") ?: return null) ?: return null
            val href = CoverRules.coverHref(text(opfPath) ?: return null) ?: return null
            val entry = zip.getEntry(CoverRules.resolve(opfPath, href)) ?: return null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return saveScaled(book.id, bytes)
        }
    }

    /** The first page of the PDF the book came from, as its cover. */
    private fun firstPageOfPdf(book: BookEntity): String? {
        val pdf = library.pdfSourceFile(book)?.takeIf { it.exists() } ?: return null
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val width = 600
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return saveJpeg(book.id, bitmap)
                }
            }
        }
    }

    private fun saveScaled(bookId: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 800) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return saveJpeg(bookId, bitmap)
    }

    private fun saveJpeg(bookId: String, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        // A fresh name each time, so a replaced cover is not served from the image cache.
        dir.listFiles { f -> f.name.startsWith("$bookId-") }?.forEach { it.delete() }
        val out = File(dir, "$bookId-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return "file://${out.absolutePath}"
    }
}
