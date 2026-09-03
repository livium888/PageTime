package com.pagetime.app.data.catalog

import com.pagetime.app.data.gutenberg.BookPage
import kotlinx.coroutines.CancellationException

/**
 * A catalogue with a second way of reaching the same books.
 *
 * Project Gutenberg is the case this exists for. The app reaches it through
 * gutendex.com, a helpful third-party mirror of the catalogue rather than
 * anything Gutenberg runs — so when that one host is down or has moved, a
 * library of seventy thousand books disappears from the app, and its own site
 * is sitting there serving a perfectly good OPDS feed the whole time.
 *
 * Depending on one host for a source that has two is a choice worth undoing.
 * The preferred route is still tried first; the second is used when the first
 * fails or returns nothing at all, which covers both a host that is down and a
 * host that has quietly stopped having anything to say.
 */
class FallbackCatalog(
    private val preferred: BookCatalog,
    private val backup: BookCatalog,
    override val id: String = preferred.id,
    override val label: String = preferred.label,
    override val note: String = preferred.note,
) : BookCatalog {

    override val browsable: Boolean get() = preferred.browsable || backup.browsable

    override suspend fun browse(page: Int): BookPage =
        either({ preferred.browse(page) }, { backup.browse(page) })

    override suspend fun search(query: String, page: Int): BookPage =
        either({ preferred.search(query, page) }, { backup.search(query, page) })

    /**
     * The first route that produces books.
     *
     * An empty page from the first route is treated as a reason to try the
     * second, not as an answer — but if the second also comes back empty, the
     * first one's empty page is what the reader sees, so a genuine "no matches"
     * still reads as a search that found nothing rather than as a failure.
     */
    private suspend fun either(
        first: suspend () -> BookPage,
        second: suspend () -> BookPage,
    ): BookPage {
        val firstResult = runCatching { first() }
        firstResult.getOrNull()?.takeIf { it.books.isNotEmpty() }?.let { return it }
        firstResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }

        return runCatching { second() }.getOrNull()?.takeIf { it.books.isNotEmpty() }
            ?: firstResult.getOrElse { throw it }
    }
}
