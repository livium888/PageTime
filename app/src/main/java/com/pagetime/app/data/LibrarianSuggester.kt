package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.SettingsRepository

/**
 * Keeps the Library screen's one daily book suggestion current.
 *
 * Not required for the app to work: with no AI configured, the suggestion
 * still appears — it's just [LibrarianFacts.sentence] verbatim instead of an
 * AI-warmed version of it. There is nothing here that can fail in a way the
 * reader needs to see; a book worth mentioning is either found or it isn't.
 */
class LibrarianSuggester(
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
    private val geminiClient: GeminiLearningClient,
    private val localLlmProvider: LlmProvider? = null,
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

        val fact = LibrarianFacts.sentence(pick, nowMillis)
        val message = tryRephrase(fact) ?: fact
        settingsRepository.saveLibrarianSuggestion(pick.book.id, message, today)
    }

    /** The fact, reworded warmly — or null on any failure, so the caller falls back to the fact itself. */
    private suspend fun tryRephrase(fact: String): String? {
        val provider = settingsRepository.llmProvider()
        val source = LumenDraftRouter.sourceFor(
            provider = provider,
            geminiConfigured = geminiClient.hasKey(),
            localModelAvailable = localLlmProvider?.isAvailable == true,
        )
        val prompt = LibrarianPrompts.rephrase(fact)
        return runCatching {
            when (source) {
                LumenDraftSource.GEMINI -> geminiClient.generateText(prompt, maxOutputTokens = 60)
                LumenDraftSource.LOCAL -> {
                    val local = localLlmProvider ?: return null
                    local.generate(LlmRequest(prompt, maxOutputTokens = 60)).getOrThrow().text
                }
                LumenDraftSource.FALLBACK -> return null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
