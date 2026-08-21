package com.pagetime.app.data

import android.content.Context
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(
    private val bookDao: BookDao,
    private val downloader: BookDownloader,
    private val gutenbergApi: GutenbergApi,
    private val epubParser: EpubParser,
    private val context: Context
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    suspend fun getMostRecentBook(): BookEntity? = bookDao.getMostRecent()

    suspend fun search(query: String): List<GutendexBook> = gutenbergApi.search(query)

    /** Downloads a Gutenberg book (preferring EPUB, falling back to plain text) and imports it. */
    suspend fun downloadBook(g: GutendexBook): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val id = g.id.toString()
            val localPath: String
            val format: String

            if (g.epubUrl != null) {
                val file = downloader.download(g.epubUrl, "$id.epub")
                // Validate it parses and extract now so the reader opens instantly later.
                epubParser.parse(file, File(context.cacheDir, "epub/$id"))
                localPath = file.absolutePath
                format = "epub"
            } else if (g.txtUrl != null) {
                val file = downloader.download(g.txtUrl, "$id.txt")
                localPath = file.absolutePath
                format = "txt"
            } else {
                error("No supported format for this book")
            }

            val book = BookEntity(
                id = id,
                title = g.title,
                author = g.authorName,
                format = format,
                localPath = localPath,
                coverUrl = g.coverUrl,
                addedAt = System.currentTimeMillis()
            )
            bookDao.upsert(book)
            book
        }
    }

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        bookDao.deleteById(book.id)
        runCatching { File(book.localPath).delete() }
        runCatching { File(context.cacheDir, "epub/${book.id}").deleteRecursively() }
    }

    suspend fun updateProgress(id: String, chapter: Int, progress: Float) =
        bookDao.updateProgress(id, chapter, progress)

    suspend fun addReadingSeconds(id: String, seconds: Long) =
        bookDao.addReadingSeconds(id, seconds)
}
