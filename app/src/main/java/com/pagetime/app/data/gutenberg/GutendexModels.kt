package com.pagetime.app.data.gutenberg

data class GutendexBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val downloadCount: Long,
    val epubUrl: String?,
    val txtUrl: String?,
    val htmlUrl: String?,
    val coverUrl: String?,
    val source: String = "gutenberg"
) {
    val authorName: String
        get() = authors.joinToString(", ").ifBlank { "Unknown author" }
}

/** A single page of the catalog, used for infinite scrolling. */
data class BookPage(
    val books: List<GutendexBook>,
    val hasNextPage: Boolean,
    val total: Long
)
