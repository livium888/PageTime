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
     * For "pdf" this is the TEXT lifted out of the document, not the document:
     * PageTime reads a PDF by extracting its text once at import and reading
     * that as a book, so pagination, saved positions, highlights and chunks work
     * on it without any of them knowing a PDF was involved. The document itself
     * is kept beside this file under the same id — see
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
 * True when the paged text reader shows this book, false when Readium does.
 *
 * Everything that is not an EPUB is text this app lays out itself: an imported
 * .txt, a YouTube transcript, or the text extracted from a PDF. Keeping that in
 * one place is what stops "is this a text book?" from being answered two
 * different ways in two screens.
 */
val BookEntity.isReflowedText: Boolean get() = format != "epub"
