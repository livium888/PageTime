package com.pagetime.app.data

import android.content.Context
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.standardebooks.StandardEbooksApi
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
    private val openLibraryApi: OpenLibraryApi,
    private val standardEbooksApi: StandardEbooksApi,
    private val epubParser: EpubParser,
    private val context: Context
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    suspend fun getMostRecentBook(): BookEntity? = bookDao.getMostRecent()

    suspend fun browseGutenberg(page: Int): BookPage = gutenbergApi.browse(page)

    suspend fun searchGutenberg(query: String, page: Int): BookPage =
        gutenbergApi.search(query, page)

    suspend fun browseOpenLibrary(page: Int): BookPage = openLibraryApi.browse(page = page)

    suspend fun searchOpenLibrary(query: String, page: Int): BookPage =
        openLibraryApi.search(query, page)

    suspend fun browseStandardEbooks(page: Int): BookPage = standardEbooksApi.browse(page)

    suspend fun searchStandardEbooks(query: String, page: Int): BookPage =
        standardEbooksApi.search(query, page)

    /**
     * Downloads a book (preferring EPUB, falling back to plain text) and imports it.
     *
     * Tries every available format URL in order: EPUB first (best reading experience),
     * then plain text as a fallback. If the EPUB downloads but can't be parsed, it's
     * deleted and the text fallback is tried instead — the user always gets a
     * readable book, never a broken download.
     */
    suspend fun downloadBook(g: GutendexBook): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val id = g.id.toString()
            val localPath: String
            val format: String

            val epubUrl = g.epubUrl
            val txtUrl = g.txtUrl

            if (epubUrl != null) {
                val file = downloader.download(epubUrl, "$id.epub")
                // Validate the EPUB by parsing it. If it fails (corrupted, unusual
                // structure, or a 0-byte response that slipped through), fall back to
                // text so the book still lands in the library.
                val parseResult = runCatching {
                    epubParser.parse(file, File(context.cacheDir, "epub/$id"))
                }
                if (parseResult.isSuccess) {
                    localPath = file.absolutePath
                    format = "epub"
                } else if (txtUrl != null) {
                    // EPUB was no good — delete it and grab the plain-text version.
                    file.delete()
                    val txtFile = downloader.download(txtUrl, "$id.txt")
                    localPath = txtFile.absolutePath
                    format = "txt"
                } else {
                    // No text fallback. Save the EPUB anyway; the reader will show a
                    // clear error if it can't be opened.
                    localPath = file.absolutePath
                    format = "epub"
                }
            } else if (txtUrl != null) {
                val file = downloader.download(txtUrl, "$id.txt")
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
