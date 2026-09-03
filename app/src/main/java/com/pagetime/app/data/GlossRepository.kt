package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.SettingsRepository

/**
 * Answers the two questions a reader asks about text they pointed at: what does
 * this word mean, and what is this sentence saying.
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
        return ask(
            prompt = WordGloss.prompt(term, before, after, bookTitle),
            replyTokens = replyTokens,
            bookId = bookId,
        ) { raw, kind ->
            val parts = WordGloss.parse(raw)?.takeIf { !it.isEmpty }
                ?: throw IllegalStateException("The model had nothing to say about that word.")
            Gloss(term.trim(), sentence, parts, kind)
        }
    }

    /**
     * Says [passage] again in words the reader can follow.
     *
     * The safest ask in the app: the model is rewriting text it was handed
     * rather than recalling anything, and the original stays on screen beside
     * the answer, so a reader who cannot judge the rewrite on its own can still
     * see whether the two say the same thing.
     */
    suspend fun simplify(
        passage: String,
        bookTitle: String,
        bookId: String?,
    ): Result<PlainReading> {
        PlainEnglish.passageProblem(passage)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val original = passage.trim()
        return ask(
            prompt = PlainEnglish.prompt(original, bookTitle),
            replyTokens = PlainEnglish.REPLY_TOKENS,
            bookId = bookId,
        ) { raw, kind ->
            val parts = PlainEnglish.parse(raw)
                ?: throw IllegalStateException("The model did not manage a simpler version.")
            PlainReading(original, parts, kind)
        }
    }

    /**
     * Runs [prompt] on the reader's chosen provider and hands the reply to
     * [read]. Both asks share this: the routing, the usage tracking, and the
     * refusal to invent an answer when no model is configured.
     */
    private suspend fun <T> ask(
        prompt: String,
        replyTokens: Int,
        bookId: String?,
        read: (raw: String, kind: LlmProviderKind) -> T,
    ): Result<T> {
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
                    read(raw, LlmProviderKind.GEMINI)
                }

            LumenDraftSource.LOCAL -> {
                val local = localLlmProvider
                    ?: return Result.failure(IllegalStateException(NO_MODEL))
                local.generate(LlmRequest(prompt, maxOutputTokens = replyTokens))
                    .mapCatching { read(it.text, LlmProviderKind.OFFLINE) }
            }

            LumenDraftSource.FALLBACK -> Result.failure(IllegalStateException(NO_MODEL))
        }
    }

    private companion object {
        const val NO_MODEL =
            "No AI is set up. Add a Gemini key or install the offline model in Settings."
    }
}
