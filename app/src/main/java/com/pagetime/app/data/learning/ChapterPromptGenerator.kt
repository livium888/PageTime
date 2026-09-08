package com.pagetime.app.data.learning

import com.pagetime.app.data.AiUsageRepository
import com.pagetime.app.data.FsrsCardCodec
import com.pagetime.app.data.embed.ChapterTopics
import com.pagetime.app.data.embed.TopicPassage
import com.pagetime.app.data.local.BookChunkEmbeddingDao
import com.pagetime.app.data.local.ChapterPassageDao
import com.pagetime.app.data.local.ChapterPassageEntity
import com.pagetime.app.data.local.PassageOutcome
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.LearningCardEntity
import io.github.openspacedrepetition.Card
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Turning a chapter into flashcards the reader is offered while reading.
 *
 * THE WHOLE PIPELINE, IN ORDER
 *
 * The chapter is already chunked and embedded by the search index. Those
 * vectors choose a handful of distinct, on-topic passages. Only those passages
 * go to the model — a few hundred tokens rather than a whole chapter, which is
 * both much cheaper and much better grounded. What comes back is checked
 * locally, and what survives is written down as PENDING.
 *
 * NOTHING IS A CARD UNTIL A PERSON SAYS SO
 *
 * A generated prompt is stored before it is approved, because generating a
 * chapter costs an API call and losing the batch when the reader closes the
 * book would mean paying for it twice. But a pending row is never scheduled
 * and never appears in a review session — that is enforced in the DAO's SQL,
 * not here.
 *
 * WHY A CHAPTER IS ONLY EVER GENERATED ONCE
 *
 * generationKey is a hash of the passages actually sent. Reopening a chapter
 * costs nothing and returns the same prompts; re-indexing the book with a
 * different model changes the passages and so changes the key, which is the
 * correct time to generate again.
 *
 * DENSITY
 *
 * One passage per ~400 words, up to three prompts from each — which is the
 * spacing Quantum Country itself uses, and roughly five times what this
 * generator produced when it was first written. The earlier caution was
 * defensible and still wrong: four cards from a chapter is too few to tell
 * whether any of this works, so the medium could never be evaluated at the
 * density it was designed for.
 *
 * The quality argument did not go away. It moved to [ChapterPromptRules],
 * where a bad prompt can actually be thrown out, rather than being expressed
 * as a refusal to generate.
 *
 * A LONG CHAPTER IS SEVERAL REQUESTS
 *
 * Two dozen passages is too much to ask for in one response. The chapter is
 * batched, one sifter runs across all of the batches so duplicates cannot slip
 * between them, and a batch that fails costs only its own prompts.
 */
class ChapterPromptGenerator(
    private val chunkDao: BookChunkEmbeddingDao,
    private val cardDao: LearningCardDao,
    /**
     * Which embedding model the chapter's index was built with.
     *
     * A supplier rather than the EmbeddingModelStore itself: the generator
     * needs one string from it, and depending on the whole store dragged in a
     * download directory and a model downloader that cannot be constructed
     * in a unit test — which is a large part of why none of this had tests.
     */
    private val embeddingModelId: () -> String?,
    private val gemini: ChapterPromptWriter,
    private val usage: AiUsageRepository? = null,
    /**
     * Where each passage's fate is recorded.
     *
     * Optional so the generator can still be constructed without it, but a
     * chapter generated with this null is one nobody can explain afterwards.
     */
    private val passageDao: ChapterPassageDao? = null,
) {

    /** Whether this chapter could produce prompts at all. */
    suspend fun isReady(book: BookEntity, chapterIndex: Int): Boolean {
        if (!gemini.hasKey()) return false
        val model = embeddingModelId() ?: return false
        return chunkDao.forChapter(book.id, model, chapterIndex).isNotEmpty()
    }

    /** Prompts already offered for this chapter and not yet judged. */
    suspend fun pending(book: BookEntity, chapterIndex: Int): List<LearningCardEntity> =
        runCatching { cardDao.pendingForChapter(book.id, chapterIndex) }.getOrDefault(emptyList())

    /**
     * Generates this chapter's prompts, or returns the ones it already has.
     *
     * [onStage] reports what is happening so the reader is not left looking at
     * a spinner during a network call they are paying for.
     */
    suspend fun generate(
        book: BookEntity,
        chapterIndex: Int,
        chapterTitle: String?,
        /**
         * Skips the "already generated" shortcut.
         *
         * Set when the reader explicitly asks for new questions. The key is a
         * hash of the passages, so a chapter whose text has not changed keeps
         * the same key forever — which is right for an accidental second tap
         * and wrong for a deliberate request.
         */
        force: Boolean = false,
        /**
         * Only these chunk ordinals, chosen by the reader.
         *
         * Empty means the whole chapter. When set, the instructions switch from
         * "write up to three per passage" to "write at least one for every
         * passage here" — the reader has already decided these are worth
         * remembering, and omitting is no longer the safe answer.
         */
        onlyOrdinals: Set<Int> = emptySet(),
        onStage: (Stage) -> Unit = {},
    ): Result {
        val model = embeddingModelId() ?: return Result(Outcome.NOT_INDEXED)
        if (!gemini.hasKey()) return Result(Outcome.NO_KEY)

        val rows = runCatching { chunkDao.forChapter(book.id, model, chapterIndex) }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return Result(Outcome.NOT_INDEXED)

        onStage(Stage(Phase.CHOOSING))
        val all = ChapterTopics.select(rows)
        val topics = if (onlyOrdinals.isEmpty()) all else all.filter { it.ordinal in onlyOrdinals }
        if (topics.isEmpty()) return Result(Outcome.NOTHING_IN_CHAPTER)
        val insist = onlyOrdinals.isNotEmpty()

        val key = generationKey(model, topics)
        // Already generated. Whatever the reader did with them — kept, skipped,
        // or not yet judged — this chapter is not paid for twice.
        if (!force && !insist && runCatching { cardDao.countForGeneration(book.id, key) }.getOrDefault(0) > 0) {
            val existing = pending(book, chapterIndex)
            return Result(
                Outcome.ALREADY_MADE,
                existing,
                asked = topics.size,
                offered = existing.size,
            )
        }

        // A chapter is several requests now, not one.
        //
        // At Quantum Country's density a long chapter selects two dozen
        // passages, and putting all of them in one call asks the model for
        // seventy prompts in a single response — which on a thinking model is
        // the shortest route to MAX_TOKENS, where the reasoning budget eats
        // the entire output allowance and nothing comes back at all.
        //
        // Batching costs one extra copy of the instructions per request. It
        // buys two things worth more than that: no single response has to be
        // enormous, and a chapter whose third request fails still keeps the
        // prompts from the first two, rather than losing everything the reader
        // just paid for.
        val batches = topics.chunked(PASSAGES_PER_REQUEST)

        // One sifter for the whole chapter. A duplicate arriving in batch three
        // is exactly as bad as one in batch one, and worse in practice because
        // nothing later will look at it again.
        val sifter = PromptSifter()

        // Prompts are paired with their passage object here rather than by
        // index. Each batch numbers its passages from zero, so a returned
        // passageIndex is batch-local; carrying the TopicPassage itself means
        // there is no global index to get wrong, and misfiling a prompt by one
        // passage would surface every question in the wrong place.
        val accepted = mutableListOf<Pair<RawPrompt, TopicPassage>>()
        var offered = 0
        var rejected = 0
        var failedBatches = 0
        var failureDetail: String? = null
        /** How many prompts each rule threw out, for the reader's summary. */
        val rejections = mutableMapOf<PromptRejection, Int>()
        /** The first rule that refused each passage. */
        val refusals = mutableMapOf<Int, PromptRejection>()
        /** Passages that were in a request which never came back. */
        val unreached = mutableSetOf<Int>()

        for ((index, batch) in batches.withIndex()) {
            onStage(Stage(Phase.WRITING, index + 1, batches.size))
            val passages = batch.map { it.text }
            val raws = try {
                // Logged like every other Gemini call, so the biggest new
                // consumer of the reader's quota is not the one thing the usage
                // screen cannot see.
                val call: suspend () -> List<RawPrompt> = {
                    gemini.generateChapterPrompts(
                        bookTitle = book.title,
                        chapterTitle = chapterTitle ?: "Chapter ${chapterIndex + 1}",
                        passages = passages,
                        insist = insist,
                    )
                }
                if (usage != null) {
                    usage.track(
                        bookId = book.id,
                        operation = AiUsageRepository.OPERATION_CHAPTER_PROMPTS,
                        model = gemini.currentModel(),
                        inputCharacters = passages.sumOf { it.length },
                        outputItems = { it.size },
                        block = call,
                    )
                } else {
                    call()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Not swallowed, and not fatal to the chapter. An HTTP error, a
                // refused key, a timeout and a malformed response are four
                // different problems; turning them all into "the model returned
                // nothing" is the silence this feature has been fixed for once
                // already. One bad batch out of four is now a partial result
                // with a reason attached, not a lost chapter.
                failedBatches++
                unreached += batch.map { it.ordinal }
                if (failureDetail == null) {
                    failureDetail = error.message?.take(300)?.ifBlank { null }
                        ?: error::class.simpleName
                }
                continue
            }
            offered += raws.size

            onStage(Stage(Phase.CHECKING, index + 1, batches.size))
            val verdict = sifter.sift(raws, passages)
            rejected += verdict.rejected.size
            for (raw in verdict.accepted) {
                val topic = batch.getOrNull(raw.passageIndex) ?: continue
                accepted += raw to topic
            }
            // Why each passage produced nothing, kept rather than counted.
            // A total tells the reader a chapter disappointed them; a reason
            // tells them whether the model declined or the rules refused, and
            // those have completely different fixes.
            for ((raw, reason) in verdict.rejected) {
                val topic = batch.getOrNull(raw.passageIndex) ?: continue
                rejections.merge(reason, 1, Int::plus)
                refusals.putIfAbsent(topic.ordinal, reason)
            }
        }

        // Every request failed: this is a failure, not an empty chapter.
        if (failedBatches == batches.size) {
            return Result(
                Outcome.REQUEST_FAILED,
                asked = topics.size,
                batches = batches.size,
                failedBatches = failedBatches,
                detail = failureDetail,
                rejections = rejections.toMap(),
            )
        }
        if (offered == 0) {
            recordPassages(book, chapterIndex, key, topics, emptyList(), refusals, unreached)
            return Result(
                Outcome.MODEL_RETURNED_NOTHING,
                asked = topics.size,
                batches = batches.size,
                failedBatches = failedBatches,
                detail = failureDetail,
                rejections = rejections.toMap(),
            )
        }
        if (accepted.isEmpty()) {
            recordPassages(book, chapterIndex, key, topics, accepted, refusals, unreached)
            return Result(
                Outcome.ALL_REJECTED,
                asked = topics.size,
                offered = offered,
                rejected = rejected,
                batches = batches.size,
                failedBatches = failedBatches,
                detail = failureDetail,
                rejections = rejections.toMap(),
            )
        }

        val now = System.currentTimeMillis()
        val fresh = FsrsCardCodec.toJson(Card.builder().build())
        val cards = accepted.map { (raw, topic) ->
            LearningCardEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                prompt = raw.prompt.trim(),
                answer = raw.answer.trim(),
                explanation = null,
                sourceLocator = null,
                // Where the passage ENDS, not where it starts: a prompt that
                // appears as the reader arrives at the paragraph is asking the
                // question before they have read the answer.
                sourceFraction = topic.endProgression,
                sourceQuote = raw.sourceQuote.trim(),
                cardType = if (raw.isCloze) {
                    LearningCardEntity.TYPE_CLOZE
                } else {
                    LearningCardEntity.TYPE_QA
                },
                fsrsCardJson = fresh,
                createdAt = now,
                updatedAt = now,
                generatedByAi = true,
                generationKey = key,
                status = LearningCardEntity.STATUS_PENDING,
                dueAt = null,
            )
        }

        runCatching { cardDao.insertAll(cards) }
        recordPassages(book, chapterIndex, key, topics, accepted, refusals, unreached)
        return Result(
            Outcome.MADE,
            cards = cards,
            asked = topics.size,
            offered = offered,
            rejected = rejected,
            batches = batches.size,
            failedBatches = failedBatches,
            detail = failureDetail,
            rejections = rejections.toMap(),
        )
    }

    /**
     * Writes down what became of every passage that was sent.
     *
     * Never allowed to fail the generation: the cards are what the reader
     * paid for, and an explanation is worth less than the thing being
     * explained.
     */
    private suspend fun recordPassages(
        book: BookEntity,
        chapterIndex: Int,
        key: String,
        topics: List<TopicPassage>,
        accepted: List<Pair<RawPrompt, TopicPassage>>,
        refusals: Map<Int, PromptRejection>,
        unreached: Set<Int>,
    ) {
        val dao = passageDao ?: return
        val made = accepted.groupingBy { it.second.ordinal }.eachCount()
        val now = System.currentTimeMillis()
        val rows = topics.map { topic ->
            val cards = made[topic.ordinal] ?: 0
            val refusal = refusals[topic.ordinal]
            ChapterPassageEntity(
                bookId = book.id,
                chapterIndex = chapterIndex,
                ordinal = topic.ordinal,
                startOffset = topic.startOffset,
                endOffset = topic.endOffset,
                text = topic.text,
                progression = topic.endProgression,
                cardsMade = cards,
                outcome = when {
                    cards > 0 -> PassageOutcome.USED
                    topic.ordinal in unreached -> PassageOutcome.REQUEST_FAILED
                    refusal != null -> PassageOutcome.REJECTED
                    // The model was handed this passage and wrote nothing about
                    // it. Worth distinguishing sharply from a rejection: this
                    // one is usually the instructions' fault, not the text's.
                    else -> PassageOutcome.MODEL_SKIPPED
                }.name,
                detail = refusal?.reason,
                generationKey = key,
                updatedAt = now,
            )
        }
        runCatching { dao.upsertAll(rows) }
    }

    /** What became of every passage the last generation sent. */
    suspend fun passages(book: BookEntity, chapterIndex: Int): List<ChapterPassageEntity> =
        runCatching { passageDao?.forChapter(book.id, chapterIndex).orEmpty() }
            .getOrDefault(emptyList())

    /**
     * Writes cards for the passages the reader picked out by hand.
     *
     * Additive: nothing is deleted, because the reader is asking for MORE from
     * passages that produced none, not for a different set of questions.
     *
     * The instructions switch from a ceiling to a floor — a person looked at
     * this exact paragraph and said they wanted to remember it, so declining is
     * no longer an acceptable answer from the model.
     */
    suspend fun generateForPassages(
        book: BookEntity,
        chapterIndex: Int,
        chapterTitle: String?,
        ordinals: Set<Int>,
        onStage: (Stage) -> Unit = {},
    ): Result {
        if (ordinals.isEmpty()) return Result(Outcome.NOTHING_IN_CHAPTER)
        return generate(
            book = book,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            force = true,
            onlyOrdinals = ordinals,
            onStage = onStage,
        )
    }

    /**
     * The reader accepts a prompt: it becomes a card and is due now.
     *
     * Due immediately rather than tomorrow, deliberately. The first retrieval
     * is the one that decides whether the memory survives at all, and it is
     * cheapest while the passage is still on screen.
     */
    suspend fun keep(cardId: String, now: Instant = Instant.now()) {
        val existing = cardDao.get(cardId) ?: return
        cardDao.upsert(
            existing.copy(
                status = LearningCardEntity.STATUS_KEPT,
                dueAt = now.toEpochMilli(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * The reader rejects a prompt.
     *
     * Kept as a skipped row rather than deleted, so the chapter is not
     * regenerated and the same rejected prompt offered back.
     */
    suspend fun skip(cardId: String) {
        runCatching {
            cardDao.setStatus(
                cardId,
                LearningCardEntity.STATUS_SKIPPED,
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * Asks for a fresh set of questions for a chapter.
     *
     * Costs another API call, which is why it is never automatic — a change to
     * the instructions could otherwise silently invalidate every chapter in
     * every book and spend the reader's quota re-answering questions they were
     * happy with.
     *
     * Cards the reader KEPT survive. Those are theirs, with review history
     * attached; this replaces what they have not judged, and adds to what they
     * have.
     */
    suspend fun regenerate(
        book: BookEntity,
        chapterIndex: Int,
        chapterTitle: String?,
        onStage: (Stage) -> Unit = {},
    ): Result {
        runCatching { cardDao.deleteUnkeptForChapter(book.id, chapterIndex) }
        return generate(book, chapterIndex, chapterTitle, force = true, onStage = onStage)
    }

    /**
     * What the generator is doing, and how far through it is.
     *
     * The batch numbers are not decoration. A chapter used to be one request
     * of a few seconds; at this density it is several, and a reader watching
     * "Writing questions…" for half a minute with no movement has no way to
     * tell a slow call from a hung one.
     */
    data class Stage(
        val phase: Phase,
        /** 1-based, or 0 before the requests start. */
        val batch: Int = 0,
        val batches: Int = 0,
    )

    enum class Phase { CHOOSING, WRITING, CHECKING }

    /**
     * Why a generation produced what it produced.
     *
     * Returning an empty list for every failure was the original sin here: no
     * key, no index, a refused request and a batch the rules threw out all
     * looked identical to the reader, which is to say they all looked like
     * nothing happening. A reason costs one enum and is the difference between
     * a feature that is broken and one that is explaining itself.
     */
    enum class Outcome {
        MADE,
        ALREADY_MADE,
        NO_KEY,
        NOT_INDEXED,
        NOTHING_IN_CHAPTER,
        MODEL_RETURNED_NOTHING,
        ALL_REJECTED,
        /** The call itself failed. [Result.detail] says how. */
        REQUEST_FAILED,
    }

    data class Result(
        val outcome: Outcome,
        val cards: List<LearningCardEntity> = emptyList(),
        /**
         * How many passages were sent.
         *
         * The whole chain is reported — asked, offered, kept — because
         * "1 question ready" out of 5 passages and out of 1 passage are very
         * different outcomes with very different fixes, and the reader cannot
         * tell them apart from the number that survived.
         */
        val asked: Int = 0,
        /** How many the model offered, before the rules were applied. */
        val offered: Int = 0,
        val rejected: Int = 0,
        /** How many requests the chapter was split into. */
        val batches: Int = 0,
        /**
         * How many of those requests failed.
         *
         * A chapter can now half-succeed. Reporting only what survived would
         * make "12 questions ready" from four good batches and from two good
         * batches and two failures look identical, and only one of those is
         * worth tapping again.
         */
        val failedBatches: Int = 0,
        /**
         * The actual failure, verbatim, for the reader to copy to whoever can
         * act on it. A summary invented here would lose the one thing worth
         * having.
         */
        val detail: String? = null,
        /**
         * Which rules threw prompts out, and how many each.
         *
         * "9 failed the checks" says a chapter disappointed the reader. "7 of
         * them quoted something not in the passage" says what to do about it,
         * and they are one map apart.
         */
        val rejections: Map<PromptRejection, Int> = emptyMap(),
    ) {
        /** The rules that did the most damage, worst first. */
        val topRejections: List<Pair<PromptRejection, Int>>
            get() = rejections.entries.sortedByDescending { it.value }
                .map { it.key to it.value }
    }

    private companion object {

        /**
         * Passages per request.
         *
         * Small enough that no single response has to carry more than about
         * two dozen prompts — the regime where a thinking model spends its
         * whole output allowance on reasoning and returns nothing — and large
         * enough that the instructions, which are the same every time and
         * longer than the passages, are not re-sent more often than necessary.
         */
        const val PASSAGES_PER_REQUEST = 8
        /**
         * Identity of one generation: the model that produced the vectors, plus
         * the passages actually sent.
         *
         * Hashing the passages rather than the chapter text means re-indexing
         * with a different embedding model — which would choose different
         * passages — correctly counts as a different generation, while merely
         * reopening the chapter does not.
         */
        fun generationKey(model: String, topics: List<TopicPassage>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(model.toByteArray())
            for (topic in topics) {
                digest.update(topic.text.toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
