package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.SettingsRepository

/**
 * Explains a selected term using whichever model the reader has configured.
 *
 * Deliberately not part of [LumenRepository]: a word the reader did not follow
 * is not a slip, and nothing here is filed, scheduled or addressed. It shares
 * only the provider routing, which is pure and already covered by tests.
 *
 * Unlike a capture, this fails rather than falling back. A card has a useful
 * non-AI form — the passage itself — but there is no plain-text version of "what
 * does this mean here", and inventing one would be worse than saying no model
 * is available.
 */
class GlossRepository(
    private val geminiClient: GeminiLearningClient,
    private val settingsRepository: SettingsRepository? = null,
    private val localLlmProvider: LlmProvider? = null,
    private val aiUsageRepository: AiUsageRepository? = null,
) {

    /** Tokens reserved for the answer. Four short fields need far less than a card. */
    private val replyTokens = 256

    suspend fun explain(
        term: String,
        before: String,
        after: String,
        bookTitle: String,
        bookId: String?,
    ): Result<Gloss> {
        WordGloss.termProblem(term)?.let { return Result.failure(IllegalArgumentException(it)) }

        val sentence = WordGloss.sentenceAround(before, term, after)
        val prompt = WordGloss.prompt(term, before, after, bookTitle)
        val provider = settingsRepository?.llmProvider() ?: LlmProviderKind.GEMINI
        val source =
            LumenDraftRouter.sourceFor(
                provider = provider,
                geminiConfigured = geminiClient.hasKey(),
                localModelAvailable = localLlmProvider?.isAvailable == true,
            )

        return when (source) {
            LumenDraftSource.GEMINI ->
                runCatching {
                    val call: suspend () -> String = {
                        geminiClient.draftLumenCardFromPrompt(prompt)
                    }
                    val raw =
                        if (aiUsageRepository != null && bookId != null) {
                            aiUsageRepository.track(
                                bookId = bookId,
                                operation = AiUsageRepository.OPERATION_GLOSS,
                                model = geminiClient.currentModel(),
                                inputCharacters = prompt.length,
                                outputItems = { it.length },
                                block = call,
                            )
                        } else {
                            call()
                        }
                    gloss(term, sentence, raw, LlmProviderKind.GEMINI)
                }

            LumenDraftSource.LOCAL -> {
                val local = localLlmProvider
                    ?: return Result.failure(IllegalStateException(NO_MODEL))
                local.generate(LlmRequest(prompt, maxOutputTokens = replyTokens))
                    .mapCatching { gloss(term, sentence, it.text, LlmProviderKind.OFFLINE) }
            }

            LumenDraftSource.FALLBACK -> Result.failure(IllegalStateException(NO_MODEL))
        }
    }

    private fun gloss(
        term: String,
        sentence: String,
        raw: String,
        kind: LlmProviderKind,
    ): Gloss {
        val parts = WordGloss.parse(raw)?.takeIf { !it.isEmpty }
            ?: throw IllegalStateException("The model had nothing to say about that word.")
        return Gloss(term.trim(), sentence, parts, kind)
    }

    private companion object {
        const val NO_MODEL =
            "No AI is set up. Add a Gemini key or install the offline model in Settings."
    }
}
