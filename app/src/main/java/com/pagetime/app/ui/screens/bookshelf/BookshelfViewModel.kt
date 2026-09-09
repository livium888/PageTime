package com.pagetime.app.ui.screens.bookshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ShelfBookEntity
import com.pagetime.app.data.shelf.ReadingLadders
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One book as the shelf draws it. */
data class ShelfDisplayBook(
    val key: String,
    val title: String,
    val author: String,
    val owned: Boolean,
    val progress: Float,
    /** Set when it can be opened; null for a book that is not downloaded. */
    val bookId: String?,
)

/** One shelf in the unit. */
data class BookshelfSection(
    val title: String,
    val subtitle: String?,
    val books: List<ShelfDisplayBook>,
)

/**
 * Every shelf at once.
 *
 * WHY IT DOES NOT GO LOOKING FOR ANYTHING
 *
 * The author shelves shown here are the ones that already exist, built when
 * the reader tapped a name. Building one for every author in the library on
 * the way into this screen would be a network call each for writers nobody
 * asked about — the same reasoning that made author lookup a tap rather than
 * something automatic.
 */
class BookshelfViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    val sections = combine(
        container.libraryRepository.observeBooks(),
        container.database.shelfBookDao().observeAll(),
    ) { books, shelfRows ->
        buildSections(books, shelfRows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildSections(
        books: List<BookEntity>,
        shelfRows: List<ShelfBookEntity>,
    ): List<BookshelfSection> {
        val owned = books.associateBy { it.id }
        val sections = mutableListOf<BookshelfSection>()

        val reading = books
            .filter { it.scrollProgress > 0.01f && it.scrollProgress < FINISHED }
            .sortedByDescending { it.totalReadingSeconds }
        if (reading.isNotEmpty()) {
            sections += BookshelfSection(
                title = "Reading now",
                subtitle = null,
                books = reading.map { it.asDisplay() },
            )
        }

        if (books.isNotEmpty()) {
            sections += BookshelfSection(
                title = "Your books",
                subtitle = books.size.toString() + " on the shelf",
                books = books.sortedBy { it.author.lowercase() }.map { it.asDisplay() },
            )
        }

        val byShelf = shelfRows.groupBy { it.shelfId }

        byShelf[ReadingLadders.GREAT_BOOKS_SHELF_ID]?.let { rows ->
            sections += BookshelfSection(
                title = ReadingLadders.GREAT_BOOKS_NAME,
                subtitle = countOwned(rows, owned).toString() + " of " + rows.size + " read",
                books = rows.map { it.asDisplay(owned) },
            )
        }

        byShelf.filterKeys { it.startsWith("author-") }
            .toSortedMap()
            .forEach { (_, rows) ->
                if (rows.isEmpty()) return@forEach
                sections += BookshelfSection(
                    // The author's name as the catalogue spells it, taken from
                    // the rows rather than from the shelf id, which is a slug.
                    title = rows.first().author,
                    subtitle = countOwned(rows, owned).toString() + " of " + rows.size,
                    books = rows.map { it.asDisplay(owned) },
                )
            }

        return sections
    }

    private fun countOwned(rows: List<ShelfBookEntity>, owned: Map<String, BookEntity>): Int =
        rows.count { it.catalogBookId != null && owned.containsKey(it.catalogBookId) }

    private fun BookEntity.asDisplay() = ShelfDisplayBook(
        key = "book-" + id,
        title = title,
        author = author,
        owned = true,
        progress = scrollProgress.coerceIn(0f, 1f),
        bookId = id,
    )

    private fun ShelfBookEntity.asDisplay(owned: Map<String, BookEntity>): ShelfDisplayBook {
        val book = catalogBookId?.let { owned[it] }
        return ShelfDisplayBook(
            key = shelfId + "-" + slotId,
            title = title,
            author = author,
            owned = book != null,
            progress = book?.scrollProgress?.coerceIn(0f, 1f) ?: 0f,
            bookId = book?.id,
        )
    }

    private companion object {
        /** Close enough to the end to call it read. */
        const val FINISHED = 0.95f
    }
}
