package com.pagetime.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.BookPage
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.internetarchive.InternetArchiveApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.standardebooks.StandardEbooksApi
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.youtube.YouTubeTranscriptFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(
    private val bookDao: BookDao,
    private val downloader: BookDownloader,
    private val gutenbergApi: GutenbergApi,
    private val internetArchiveApi: InternetArchiveApi,
    private val openLibraryApi: OpenLibraryApi,
    private val standardEbooksApi: StandardEbooksApi,
    private val epubParser: EpubParser,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
    private val aiUsageRepository: AiUsageRepository? = null
) {

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    /**
     * The book to resume: the one last saved with a reading position, falling back
     * to the newest download. Previously this was "ORDER BY addedAt DESC", i.e. the
     * most recently DOWNLOADED book — with several test books downloaded, re-entry
     * kept landing in the wrong book at chapter 1, which looked exactly like
     * "position not remembered".
     */
    suspend fun getMostRecentBook(): BookEntity? {
        settingsRepository.lastReadBookId()?.let { id ->
            bookDao.getById(id)?.let { return it }
        }
        return bookDao.getMostRecent()
    }

    suspend fun browseGutenberg(page: Int): BookPage = gutenbergApi.browse(page)

    suspend fun searchGutenberg(query: String, page: Int): BookPage =
        gutenbergApi.search(query, page)

    suspend fun searchInternetArchive(query: String, page: Int): BookPage =
        internetArchiveApi.search(query, page)

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
            // Re-downloading a book must NEVER wipe the reader's position: carry over
            // any existing progress instead of letting REPLACE insert blank defaults.
            val existing = bookDao.getById(id)
            val toSave = if (existing != null) book.copy(
                addedAt = existing.addedAt,
                currentChapterIndex = existing.currentChapterIndex,
                scrollProgress = existing.scrollProgress,
                totalReadingSeconds = existing.totalReadingSeconds
            ) else book
            bookDao.upsert(toSave)
            toSave
        }
    }

    /**
     * Imports an EPUB or plain-text document selected with Android's system picker.
     * The provider URI is copied into app-private storage, so the book remains
     * readable after the user removes the original file or its temporary URI grant.
     */
    suspend fun importLocalBook(uri: Uri): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Imported book"
            val mimeType = resolver.getType(uri).orEmpty().lowercase()
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val format = when {
                extension == "epub" || mimeType == "application/epub+zip" -> "epub"
                extension in setOf("txt", "text", "md") || mimeType.startsWith("text/") -> "txt"
                else -> error("Choose an EPUB or plain-text file")
            }
            val id = "local-${UUID.randomUUID()}"
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val destination = File(booksDir, "$id.$format")
            val input = resolver.openInputStream(uri) ?: error("Could not open the selected file")
            input.use { source ->
                destination.outputStream().use { target -> source.copyTo(target) }
            }
            require(destination.length() > 0) { "The selected file is empty" }

            val metadata = if (format == "epub") {
                // Parsing here both validates the archive and extracts assets for Readium.
                epubParser.parse(destination, File(context.cacheDir, "epub/$id"))
            } else {
                null
            }
            val title = metadata?.title
                ?.takeIf { it.isNotBlank() }
                ?: displayName.substringBeforeLast('.', displayName).ifBlank { "Imported book" }
            val author = metadata?.author?.takeIf { it.isNotBlank() } ?: "Unknown author"
            BookEntity(
                id = id,
                title = title,
                author = author,
                format = format,
                localPath = destination.absolutePath,
                coverUrl = null,
                addedAt = System.currentTimeMillis()
            ).also { bookDao.upsert(it) }
        }
    }

    suspend fun replaceTextTranscript(bookId: String, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val book = bookDao.getById(bookId) ?: error("Book not found")
            require(book.format == "txt") { "Only text transcripts can be replaced" }
            val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected file")
            val edited = input.bufferedReader().use { it.readText() }.trim()
            require(edited.isNotBlank()) { "The selected text file is empty" }
            val original = File(book.localPath)
            val backup = File(original.parentFile, "${original.nameWithoutExtension}.raw.txt")
            if (!backup.exists()) backup.writeText(original.readText())
            original.writeText(edited)
            edited
        }
    }

    suspend fun restoreRawTranscript(bookId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val book = bookDao.getById(bookId) ?: error("Book not found")
            val original = File(book.localPath)
            val backup = File(original.parentFile, "${original.nameWithoutExtension}.raw.txt")
            require(backup.exists()) { "No original transcript backup exists" }
            val raw = backup.readText().trim()
            require(raw.isNotBlank()) { "The original transcript backup is empty" }
            original.writeText(raw)
            raw
        }
    }

    fun rawBackupFile(book: BookEntity): File =
        File(File(book.localPath).parentFile, "${File(book.localPath).nameWithoutExtension}.raw.txt")

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        bookDao.deleteById(book.id)
        runCatching { File(book.localPath).delete() }
        runCatching { File(context.cacheDir, "epub/${book.id}").deleteRecursively() }
    }

    suspend fun updateProgress(id: String, chapter: Int, progress: Float) {
        bookDao.updateProgress(id, chapter, progress)
        // Every position save doubles as "this is the book I'm currently reading",
        // so "continue reading" (reader/last) always resumes the right book.
        settingsRepository.setLastReadBookId(id)
    }

    suspend fun addReadingSeconds(id: String, seconds: Long) =
        bookDao.addReadingSeconds(id, seconds)

    /**
     * Imports a YouTube video transcript as a plain-text book.
     * The transcript text is saved locally so it works offline.
     */
    suspend fun importYouTubeTranscript(url: String): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val fetcher = YouTubeTranscriptFetcher()
            val videoId = fetcher.extractVideoId(url)
                ?: error("Could not find a YouTube video ID in this URL")
            val result = fetcher.fetchTranscript(videoId)
                ?: error("No transcript available for this video. It may not have subtitles.")
            val id = "yt-${UUID.randomUUID()}"
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val destination = File(booksDir, "$id.txt")
            destination.writeText(result.text)
            BookEntity(
                id = id,
                title = result.title,
                author = result.author,
                format = "txt",
                localPath = destination.absolutePath,
                coverUrl = null,
                addedAt = System.currentTimeMillis()
            ).also { bookDao.upsert(it) }
        }
    }

    /**
     * Sends the book's transcript to Gemini for AI-powered reformatting.
     * Replaces the local file with the cleaned-up version.
     * Returns the formatted text, or throws on failure.
     */
    suspend fun reformatTranscriptWithAI(
        bookId: String,
        geminiClient: GeminiLearningClient,
        onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val book = bookDao.getById(bookId) ?: error("Book not found")
            val rawText = File(book.localPath).readText()
            if (rawText.isBlank()) error("Book has no text content")

            val call: suspend () -> String = {
                geminiClient.formatTranscriptWithAI(rawText, book.title, onProgress)
            }
            val formatted = if (aiUsageRepository != null) {
                aiUsageRepository.track(
                    bookId = bookId,
                    operation = AiUsageRepository.OPERATION_REFORMAT,
                    model = geminiClient.currentModel(),
                    inputCharacters = rawText.length,
                    outputItems = { it.length },
                    block = call
                )
            } else {
                call()
            }
            require(formatted.isNotBlank()) { "AI formatting returned empty text" }
            val original = File(book.localPath)
            val backup = File(original.parentFile, "${original.nameWithoutExtension}.raw.txt")
            if (!backup.exists()) {
                backup.writeText(rawText)
            }
            original.writeText(formatted)
            formatted
        }
    }
}
