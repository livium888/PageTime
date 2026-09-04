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
    val source: String = "gutenberg",
    val language: String = "en"
) {
    val authorName: String
        get() = authors.joinToString(", ").ifBlank { "Unknown author" }
}

/** A single page of the catalog, used for infinite scrolling. */
data class BookPage(
    val books: List<GutendexBook>,
    val hasNextPage: Boolean,
    val total: Long,
    /**
     * How many entries the source offered before this app filtered them.
     *
     * A catalogue that returns thirty books of which none can be downloaded is
     * not the same as one that matched nothing, and both used to arrive here as
     * an empty list. Keeping the pre-filter count is what lets the shelf tell
     * the reader which happened — and lets a filter that has quietly started
     * rejecting everything be seen rather than guessed at.
     */
    val considered: Int = 0
)
