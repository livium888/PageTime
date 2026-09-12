package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    /** "epub", "txt", or "pdf". */
    val format: String,
    /**
     * The file the reader opens.
     *
     * For "pdf" this is the EPUB generated from the document, not the document:
     * PageTime reads a PDF by lifting its text out, page by page, and writing it
     * back out as a book Readium lays out — so pagination, saved positions,
     * highlights and chunks work without any of them knowing a PDF was involved.
     * The document itself is kept beside this file under the same id — see
     * [com.pagetime.app.data.LibraryRepository.pdfSourceFile].
     */
    val localPath: String,
    val coverUrl: String?,
    val addedAt: Long,
    val currentChapterIndex: Int = 0,
    val scrollProgress: Float = 0f,
    val totalReadingSeconds: Long = 0
)

/**
 * True when Readium renders this book, false when the app lays it out itself.
 *
 * A PDF counts, because a PDF is converted to an EPUB at import and read as one
 * from then on. The file is what decides it rather than the format, so a PDF
 * imported before that conversion existed keeps reading as text until it is
 * rebuilt — which is what makes the upgrade safe to run in the background.
 */
val BookEntity.isReadiumBook: Boolean
    get() = format == "epub" || (format == "pdf" && localPath.endsWith(".epub", ignoreCase = true))

/**
 * True when the paged text reader shows this book, false when Readium does.
 *
 * Everything that is not an EPUB is text this app lays out itself: an imported
 * .txt, a YouTube transcript, or a PDF that has not been converted yet. Keeping
 * that in one place is what stops "is this a text book?" from being answered
 * two different ways in two screens.
 */
val BookEntity.isReflowedText: Boolean get() = !isReadiumBook
