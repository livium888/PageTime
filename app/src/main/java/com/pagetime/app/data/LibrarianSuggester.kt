package com.pagetime.app.data

import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.SettingsRepository

/**
 * Keeps the Library screen's one daily book suggestion current.
 *
 * The suggestion is [LibrarianFacts.sentence] as written: a plain fact about a
 * book on the shelf. Nothing here can fail in a way the reader needs to see; a
 * book worth mentioning is either found or it isn't.
 */
class LibrarianSuggester(
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
) {
    /** Called once per Library screen visit; a no-op once today's suggestion already exists. */
    suspend fun refreshIfNeeded(today: Long, nowMillis: Long = System.currentTimeMillis()) {
        val existing = settingsRepository.currentLibrarianSuggestion()
        if (!LibrarianPicks.needsRefresh(existing?.shownEpochDay, today)) return

        val pick = LibrarianPicks.choose(bookDao.getAll())
        if (pick == null) {
            settingsRepository.clearLibrarianSuggestion()
            return
        }

        settingsRepository.saveLibrarianSuggestion(pick.book.id, LibrarianFacts.sentence(pick, nowMillis), today)
    }
}
