package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.SettingsRepository

/**
 * Tags each book with a [BookGenre], once, in the background.
 *
 * Not required for the app to work: with no AI configured, [classifyMissing]
 * is a no-op every time it runs, and books simply stay unclassified forever —
 * [BookGenreSummary] already treats that as "nothing to report" rather than
 * an error. There is no urgency here worth a loading spinner or a failure
 * message; it either quietly happens or quietly doesn't.
 */
class BookGenreClassifier(
    private val bookDao: BookDao,
    private val settingsRepository: SettingsRepository,
    private val geminiClient: GeminiLearningClient,
    private val localLlmProvider: LlmProvider? = null,
) {
    /**
     * Classifies up to [limit] books per call, oldest-added first.
     *
     * Capped rather than "every unclassified book at once" so importing a
     * pile of books in one sitting doesn't fire a burst of AI calls the
     * moment the Library screen opens — it catches up a few books per visit
     * instead, the same throttling reasoning as
     * [com.pagetime.app.data.ExplainBackRepository]'s per-chapter concept cap.
     */
    suspend fun classifyMissing(limit: Int = 5) {
        val provider = settingsRepository.llmProvider()
        val source = LumenDraftRouter.sourceFor(
            provider = provider,
            geminiConfigured = geminiClient.hasKey(),
            localModelAvailable = localLlmProvider?.isAvailable == true,
        )
        if (source == LumenDraftSource.FALLBACK) return

        for (book in bookDao.getUnclassified(limit)) {
            val genre = runCatching { classifyOne(book.title, book.author, source) }.getOrNull() ?: continue
            bookDao.updateGenre(book.id, genre.name)
        }
    }

    private suspend fun classifyOne(title: String, author: String, source: LumenDraftSource): BookGenre? {
        val prompt = BookGenrePrompts.classify(title, author)
        val reply = when (source) {
            LumenDraftSource.GEMINI -> geminiClient.generateText(prompt, maxOutputTokens = 16)
            LumenDraftSource.LOCAL -> {
                val local = localLlmProvider ?: return null
                local.generate(LlmRequest(prompt, maxOutputTokens = 16)).getOrNull()?.text ?: return null
            }
            LumenDraftSource.FALLBACK -> return null
        }
        return BookGenre.parse(reply)
    }
}
