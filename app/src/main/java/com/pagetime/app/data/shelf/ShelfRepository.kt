package com.pagetime.app.data.shelf

import com.pagetime.app.data.LibraryRepository
import com.pagetime.app.data.catalog.BookCatalogs
import com.pagetime.app.data.gutenberg.GutendexBook
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.ShelfBookDao
import com.pagetime.app.data.local.ShelfBookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One line of a shelf, as the screen needs it. */
data class ShelfRow(
    val slotId: String,
    val title: String,
    val author: String,
    val note: String?,
    val position: Int,
    val stageLabel: String?,
    val state: ShelfRowState,
)

/**
 * The shelves: lists of books the reader may or may not have.
 *
 * SEEDING IS ADDITIVE AND NEVER OVERWRITES
 *
 * The ladder is shipped data and will change between releases — a note
 * rewritten, an entry added. Seeding therefore inserts and ignores conflicts
 * rather than replacing, because a replace would throw away every resolution
 * the app has done and send it back to the catalogues for the whole list on
 * every update.
 *
 * RESOLUTION IS LAZY AND BOUNDED
 *
 * Sixty entries is sixty searches, and doing them on first open would be a
 * burst of network the reader did not ask for while they are looking at a
 * screen that already has something to show. So entries resolve a few at a
 * time, and an entry nothing had is retried later rather than never — a
 * catalogue that gains a book should eventually be noticed.
 */
class ShelfRepository(
    private val dao: ShelfBookDao,
    private val library: LibraryRepository,
    private val catalogs: BookCatalogs,
    private val authors: OpenLibraryAuthors = OpenLibraryAuthors(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** How many entries one resolve pass will look up. */
        const val RESOLVE_BATCH = 6

        /**
         * How long a "nobody has this" answer stands before it is asked again.
         *
         * Catalogues do gain books. Re-asking every launch would be sixty
         * pointless requests a day; never re-asking would freeze a wrong
         * answer forever.
         */
        const val UNAVAILABLE_RETRY_MS = 14L * 24 * 60 * 60 * 1000
    }

    /**
     * The shelf id for one author, derived from the name.
     *
     * Stable and readable, so a shelf survives the app being reinstalled with
     * the same library and can be recognised in a database dump.
     */
    fun authorShelfId(author: String): String =
        "author-" + ShelfMatcher.normalize(author).replace(' ', '-').take(80)

    /**
     * Builds (or refreshes) the shelf of everything one author wrote.
     *
     * Returns what the lookup concluded so the screen can say "several people
     * are called this" rather than showing one of them and hoping. Nothing is
     * written to the shelf unless exactly one author was identified — a
     * bibliography under the wrong name is worse than no bibliography, because
     * the reader has no way to notice.
     */
    suspend fun buildAuthorShelf(author: String): AuthorLookup {
        val shelfId = authorShelfId(author)
        val candidates = authors.searchAuthors(author)
        val lookup = AuthorBibliography.chooseAuthor(author, candidates)
        val found = (lookup as? AuthorLookup.Found)?.author ?: return lookup

        val works = authors.works(found.key)
        if (works.isEmpty()) return AuthorLookup.NotFound

        val rows = works.mapIndexed { index, work ->
            ShelfBookEntity(
                shelfId = shelfId,
                // The Open Library work key where there is one, so a retitled
                // record does not arrive as a second copy of the same book.
                slotId = work.workKey ?: ShelfMatcher.normalize(work.title).take(60),
                title = work.title,
                author = found.name,
                position = index,
                note = work.firstPublishedYear?.toString(),
                addedAt = now(),
            )
        }
        dao.insertAllKeepingExisting(rows)
        return lookup
    }

    /** Every author in the reader's library, in the order they read them. */
    fun observeLibraryAuthors(): Flow<List<String>> =
        library.observeBooks().map { books ->
            books.map { it.author.trim() }
                .filter { it.isNotBlank() && !it.equals("Unknown author", ignoreCase = true) }
                .distinct()
        }

    /**
     * Puts the shipped ladder on its shelf, once.
     *
     * Existing rows are left exactly as they are, so a reader who has resolved
     * and downloaded half the list keeps all of it across an update.
     */
    suspend fun seedGreatBooks() {
        val rows = ReadingLadders.greatBooks.mapIndexed { index, entry ->
            ShelfBookEntity(
                shelfId = ReadingLadders.GREAT_BOOKS_SHELF_ID,
                slotId = entry.key,
                title = entry.title,
                author = entry.author,
                position = index,
                note = entry.note,
                addedAt = now(),
            )
        }
        dao.insertAllKeepingExisting(rows)
    }

    /**
     * The shelf, joined against what the reader actually owns.
     *
     * The join is on catalogue id, which is also the id a downloaded book
     * gets — so owning is an exact lookup rather than a title comparison that
     * would cheerfully pair two different translations.
     */
    fun observeShelf(shelfId: String): Flow<List<ShelfRow>> =
        combine(dao.observeShelf(shelfId), library.observeBooks()) { entries, books ->
            val owned: Map<String, BookEntity> = books.associateBy { it.id }
            // Stage headings belong to the ladder; an author shelf is one
            // career and has no stages, so this map is simply empty for it.
            val stages = ReadingLadders.greatBooks.associate { it.key to it.stage }
            entries.map { row ->
                val book = row.catalogBookId?.let { owned[it] }
                ShelfRow(
                    slotId = row.slotId,
                    title = row.title,
                    author = row.author,
                    note = row.note,
                    position = row.position,
                    stageLabel = stages[row.slotId]?.label,
                    state = ShelfRows.stateOf(
                        ownedBookId = book?.id,
                        ownedProgress = book?.scrollProgress ?: 0f,
                        availability = ShelfAvailability.fromKey(row.availability),
                        catalogSource = row.catalogSource,
                        catalogBookId = row.catalogBookId,
                    ),
                )
            }
        }

    /**
     * Asks the catalogues about a few unresolved entries.
     *
     * Returns how many were looked at, so a caller can keep going while there
     * is work rather than guessing at a number of passes.
     */
    suspend fun resolveSome(
        shelfId: String = ReadingLadders.GREAT_BOOKS_SHELF_ID,
        limit: Int = RESOLVE_BATCH,
    ): Int {
        val pending = dao.needingResolution(
            shelfId = shelfId,
            staleBefore = now() - UNAVAILABLE_RETRY_MS,
            limit = limit,
        )
        val byKey = ReadingLadders.greatBooks.associateBy { it.key }
        for (row in pending) {
            // A ladder slot carries its author aliases; an author shelf row
            // has only what Open Library said, which is enough because the
            // name came from a record rather than from my typing.
            val entry = byKey[row.slotId] ?: ShelfBookRef(row.title, row.author)
            val hit = searchCatalogues(entry)
            dao.recordResolution(
                shelfId = shelfId,
                slotId = row.slotId,
                source = hit?.second,
                catalogBookId = hit?.first?.id?.toString(),
                availability = if (hit == null) {
                    ShelfAvailability.UNAVAILABLE.key
                } else {
                    ShelfAvailability.AVAILABLE.key
                },
                resolvedAt = now(),
            )
        }
        return pending.size
    }

    /**
     * The first catalogue that has this book, and which one it was.
     *
     * A catalogue that throws is skipped rather than allowed to fail the whole
     * pass — one dead feed must not make the entire ladder look unavailable,
     * which is exactly the shape of failure the catalogue health states were
     * added to stop elsewhere.
     */
    private suspend fun searchCatalogues(entry: WantedBook): Pair<GutendexBook, String>? {
        for (catalogue in catalogs.all) {
            val query = "${'$'}{entry.title} ${'$'}{entry.author}"
            val page = runCatching { catalogue.search(query, 1) }.getOrNull() ?: continue
            val match = ShelfMatcher.bestMatch(entry, page.books)
            if (match != null) return match to catalogue.id
        }
        return null
    }

    /** A shelf row as something the matcher can look for. */
    private data class ShelfBookRef(
        override val title: String,
        override val author: String,
        override val authorAliases: List<String> = emptyList(),
    ) : WantedBook

    /** Downloads a resolved shelf entry through the library's normal path. */
    suspend fun download(shelfId: String, slotId: String): Result<BookEntity> {
        val row = dao.shelf(shelfId).firstOrNull { it.slotId == slotId }
            ?: return Result.failure(IllegalStateException("No such shelf entry"))
        val entry: WantedBook = ReadingLadders.greatBooks.firstOrNull { it.key == slotId }
            ?: ShelfBookRef(row.title, row.author)
        // Re-search rather than storing download URLs: a URL cached weeks ago
        // may 404, and the catalogue is the thing that knows.
        val hit = searchCatalogues(entry)
            ?: return Result.failure(IllegalStateException("No catalogue has ${row.title}"))
        return library.downloadBook(hit.first)
    }
}
