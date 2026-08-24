package com.pagetime.app.data.gutenberg

/** A result from any official public-domain catalog used by PageTime. */
data class CatalogBook(
    val book: GutendexBook,
    val sourceLabel: String
)
