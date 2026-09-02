package com.pagetime.app.data

import android.content.Context
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LumenCardDao
import com.pagetime.app.data.local.LumenCardEntity
import com.pagetime.app.data.local.SettingsRepository
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** A capture in progress: what the user sees and edits in the preview sheet. */
data class LumenDraft(
    val front: String,
    val back: String,
    val quote: String,
    val usedAi: Boolean,
    /**
     * Why the AI draft was not used, when one was attempted and rejected.
     * Null when AI was never tried, or when its card is the one shown.
     */
    val aiRejection: String? = null
)

/** One dated entry in a card's append-only evolution history. */
data class LumenSnippet(
    val text: String,
    val addedAt: Long,
    val fraction: Float? = null
)

/** Review ratings for Lumen training, mirroring the learning-card scale. */
enum class LumenRating(val value: Int, val label: String) {
    AGAIN(1, "Again"),
    HARD(2, "Hard"),
    GOOD(3, "Good"),
    EASY(4, "Easy");

    fun toFsrs(): Rating = when (this) {
        AGAIN -> Rating.AGAIN
        HARD -> Rating.HARD
        GOOD -> Rating.GOOD
        EASY -> Rating.EASY
    }
}

/**
 * Luhmann-style slip box ("Lumen"): manually captured index notes filed in
 * numbered boxes at stable addresses, cross-referenced like Zettel 21/2a7,
 * and optionally trained with the same FSRS scheduler as learning cards.
 * The AI is involved only at capture (and never rewrites a saved card);
 * storage, filing, and re-encounters stay on-device.
 */
class LumenRepository(
    private val dao: LumenCardDao,
    private val geminiClient: GeminiLearningClient,
    private val aiUsageRepository: AiUsageRepository? = null,
    private val bookDao: com.pagetime.app.data.local.BookDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val localLlmProvider: LlmProvider? = null,
    private val debugLog: (String) -> Unit = {},
    private val modelStore: () -> LumenModelStore = { throw UnsupportedOperationException("modelStore not provided") },
    private val captureDiagContext: () -> Context = { throw UnsupportedOperationException("captureDiagContext not provided") },
    private val scheduler: Scheduler = Scheduler.builder()
        .desiredRetention(0.9)
        .enableFuzzing(false)
        .build()
) {
    fun diagContext(): Context = captureDiagContext()
    fun observeAll(): Flow<List<LumenCardEntity>> = dao.observeAll()

    fun observeBox(box: Int): Flow<List<LumenCardEntity>> = dao.observeBox(box)

    /** Structure maps (hub notes) across every box, newest first. */
    fun observeHubs(): Flow<List<LumenCardEntity>> = dao.observeHubs()

    fun observeDueCount(now: () -> Long = { System.currentTimeMillis() }): Flow<Int> =
        dao.observeDueCount(now())

    suspend fun get(cardId: String): LumenCardEntity? = dao.get(cardId)

    suspend fun cardsByIds(ids: List<String>): List<LumenCardEntity> = dao.getByIds(ids)

    suspend fun boxRange(): IntRange {
        val min = dao.minBox() ?: return 1..1
        val max = dao.maxBox() ?: return 1..1
        return min..max
    }

    /**
     * Drafts a card from the passage around the current reading position.
     * The source is chosen by the user's AI provider setting: Gemini, the
     * offline model, or the plain on-device draft. AI is always best-effort —
     * if nothing is configured or a call fails, the card is still drafted from
     * the raw passage so capture never blocks reading.
     */
    suspend fun draft(
        book: BookEntity,
        passage: String,
    ): LumenDraft {
        val clean = passage.trim()
        require(clean.isNotBlank()) { "Nothing to capture — move to a spot with text first" }

        val provider = settingsRepository?.llmProvider() ?: LlmProviderKind.GEMINI
        val source =
            LumenDraftRouter.sourceFor(
                provider = provider,
                geminiConfigured = geminiClient.hasKey(),
                localModelAvailable = localLlmProvider?.isAvailable == true,
            )
        when (source) {
            LumenDraftSource.LOCAL -> {
                val local = localLlmProvider ?: return fallbackDraft(clean)
                val state = CaptureDiagnostic.evaluate(local, modelStore())
                CaptureDiagnostic.recordPreCapture(
                    context = captureDiagContext(),
                    modelState = state,
                    captureKind = "LumenCard",
                    passage = clean,
                )
                if (state != CaptureDiagnostic.ModelState.ready) {
                    CaptureDiagnostic.recordFailure(
                        context = captureDiagContext(),
                        captureKind = "LumenCard",
                        reason = "Not attempting offline model: ${state}"
                    )
                    return fallbackDraft(clean)
                }
                val startedAt = System.currentTimeMillis()
                val outcome =
                    LumenLocalDraft.generate(
                        call = { request -> local.generate(request) },
                        passage = clean,
                        bookTitle = book.title,
                        debugLog = debugLog,
                        onPromptBuilt = { prompt ->
                            CaptureDiagnostic.recordGenerating(
                                context = captureDiagContext(),
                                captureKind = "LumenCard",
                                passage = clean,
                                prompt = prompt,
                                replyTokens = LumenLocalDraft.REPLY_TOKENS,
                            )
                        },
                    )
                // How long inference really takes is the budget for tuning the
                // passage cap: a bigger passage buys a better card and costs
                // seconds, and neither is knowable without measuring it.
                CaptureDiagnostic.recordInference(
                    context = captureDiagContext(),
                    captureKind = "LumenCard",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attempts = outcome.attempts,
                    usedAi = outcome.card != null,
                    rejection = outcome.rejection?.name,
                )
                val card = outcome.card
                if (card != null) {
                    return LumenDraft(card.first, card.second, clean, usedAi = true)
                }
                CaptureDiagnostic.recordFailure(
                    context = captureDiagContext(),
                    captureKind = "LumenCard",
                    reason = "Offline model returned unusable draft; used fallback",
                )
                return fallbackDraft(clean, outcome.rejection?.explanation)
            }
            LumenDraftSource.GEMINI -> {
                try {
                    val call: suspend () -> String = {
                        geminiClient.draftLumenCard(clean, book.title)
                    }
                    val result =
                        if (aiUsageRepository != null) {
                            aiUsageRepository.track(
                                bookId = book.id,
                                operation = AiUsageRepository.OPERATION_LUMEN,
                                model = geminiClient.currentModel(),
                                inputCharacters = clean.length,
                                outputItems = { it.length },
                                block = call,
                            )
                        } else {
                            call()
                        }
                    val parsed =
                        LumenCapture.parseDraft(result)?.takeIf { (front, _) ->
                            !LumenCapture.isPassageEcho(front, clean)
                        }
                    if (parsed != null) {
                        return LumenDraft(parsed.first, parsed.second, clean, usedAi = true)
                    }
                } catch (_: Exception) {
                    // Fall through to the on-device draft. Capture must never fail.
                }
            }
            LumenDraftSource.FALLBACK -> Unit
        }
        return fallbackDraft(clean)
    }

    private fun fallbackDraft(clean: String, aiRejection: String? = null): LumenDraft {
        val (front, back) = LumenCapture.fallbackDraft(clean)
        return LumenDraft(front, back, clean, usedAi = false, aiRejection = aiRejection)
    }

    /**
     * Saves a new card, filing it at the next free address in [box]. The
     * address is stable for the card's lifetime — like Luhmann's Zetteln,
     * cards are never renumbered when neighbors move.
     */
    suspend fun save(
        book: BookEntity,
        front: String,
        back: String,
        quote: String,
        sourceLocatorJson: String?,
        sourceChapterIndex: Int?,
        sourceFraction: Float,
        box: Int = 1,
        afterIndex: String? = null
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        val existingIndexes = dao.indexNumbersInBox(box.coerceAtLeast(1))
        val resolvedAfterIndex = LumenAddress.resolveExisting(existingIndexes, afterIndex)
        val address = LumenAddress.nextAddress(existingIndexes, resolvedAfterIndex)
        val card = LumenCardEntity(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            box = box.coerceAtLeast(1),
            indexNumber = address,
            front = front.trim().ifBlank { LumenCapture.fallbackDraft(quote).first },
            back = back.trim(),
            quote = quote.trim(),
            sourceLocatorJson = sourceLocatorJson,
            sourceChapterIndex = sourceChapterIndex,
            sourceFraction = sourceFraction.coerceIn(0f, 1f),
            snippetsJson = "[]",
            linksJson = "[]",
            keywords = LumenCapture.extractKeywords("$front $quote"),
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(card)
        return card
    }

    /**
     * Saves a note written directly in the slip box (not captured from the
     * reader) — Luhmann wrote at his desk too. Filed at the next free address
     * in [box], or directly behind [behindCardId] when given.
     */
    suspend fun saveManual(box: Int, front: String, back: String, behindCardId: String? = null): LumenCardEntity {
        val now = System.currentTimeMillis()
        val boxNumber = box.coerceAtLeast(1)
        val existingIndexes = dao.indexNumbersInBox(boxNumber)
        val behind = behindCardId?.let { dao.get(it) }?.takeIf { it.box == boxNumber }
        val resolvedBehind = behind?.let { LumenAddress.resolveExisting(existingIndexes, it.indexNumber) }
        val address = LumenAddress.nextAddress(existingIndexes, resolvedBehind)
        val card = LumenCardEntity(
            id = UUID.randomUUID().toString(),
            bookId = "",
            box = box.coerceAtLeast(1),
            indexNumber = address,
            front = front.trim(),
            back = back.trim(),
            quote = "",
            sourceLocatorJson = null,
            sourceChapterIndex = null,
            sourceFraction = 0f,
            snippetsJson = "[]",
            linksJson = "[]",
            keywords = LumenCapture.extractKeywords("$front $back"),
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(card)
        return card
    }

    /** Appends a dated snippet — evolution is additive, never a rewrite. */
    suspend fun addContext(cardId: String, text: String, fraction: Float?) {
        val existing = dao.get(cardId) ?: return
        val snippetText = text.trim()
        if (snippetText.isBlank()) return
        val snippets = LumenCapture.snippetsFromJson(existing.snippetsJson) +
            LumenSnippet(snippetText, System.currentTimeMillis(), fraction)
        dao.upsert(
            existing.copy(
                snippetsJson = LumenCapture.snippetsToJson(snippets),
                keywords = LumenCapture.extractKeywords(
                    listOf(existing.front, existing.quote, snippetText).joinToString(" ")
                ),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateText(cardId: String, front: String, back: String) {
        val existing = dao.get(cardId) ?: return
        dao.upsert(
            existing.copy(
                front = front.trim(),
                back = back.trim(),
                keywords = LumenCapture.extractKeywords("$front $back ${existing.quote}"),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Marks or unmarks a card as a structure map (hub note). The card itself
     * does not change — the flag only tells the Register to surface it as an
     * entry point into the cluster it links to.
     */
    suspend fun setHub(cardId: String, isHub: Boolean) {
        val existing = dao.get(cardId) ?: return
        if (existing.isHub == isHub) return
        dao.upsert(
            existing.copy(
                isHub = isHub,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Re-files a card directly behind another card in the same box. */
    suspend fun fileBehind(cardId: String, behindCardId: String) {
        val card = dao.get(cardId) ?: return
        val behind = dao.get(behindCardId) ?: return
        if (card.id == behind.id || card.box != behind.box) return
        val existingIndexes = dao.indexNumbersInBox(card.box).filterNot { it == card.indexNumber }
        val resolvedBehind = LumenAddress.resolveExisting(existingIndexes, behind.indexNumber)
        val address = LumenAddress.nextAddress(existingIndexes, resolvedBehind)
        dao.upsert(card.copy(indexNumber = address, updatedAt = System.currentTimeMillis()))
    }

    /** Removes a card while leaving every other stable address untouched. */
    suspend fun deleteWithLinks(cardId: String) {
        val card = dao.get(cardId) ?: return
        val linkedIds = LumenCapture.linksFromJson(card.linksJson)
        linkedIds.forEach { otherId ->
            dao.get(otherId)?.let { other ->
                dao.upsert(
                    other.copy(
                        linksJson = LumenCapture.linksToJson(
                            LumenCapture.linksFromJson(other.linksJson) - cardId
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        dao.delete(cardId)
    }

    /** Moves a card (with everything filed behind it) to another slip box. */
    suspend fun moveToBox(cardId: String, targetBox: Int) {
        val existing = dao.get(cardId) ?: return
        val box = targetBox.coerceAtLeast(1)
        if (box == existing.box) return
        // Keep the relative position: same branch suffix, new box prefix.
        val relative = LumenAddress.relativePart(existing.indexNumber)
        val address = LumenAddress.nextAddress(dao.indexNumbersInBox(box), relative)
        dao.upsert(
            existing.copy(
                box = box,
                indexNumber = address,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Links this card to another; the relation is bidirectional. */
    suspend fun link(cardId: String, otherId: String) {
        if (cardId == otherId) return
        val a = dao.get(cardId) ?: return
        val b = dao.get(otherId) ?: return
        val aLinks = LumenCapture.linksFromJson(a.linksJson)
        val bLinks = LumenCapture.linksFromJson(b.linksJson)
        val now = System.currentTimeMillis()
        if (otherId !in aLinks) {
            dao.upsert(
                a.copy(
                    linksJson = LumenCapture.linksToJson(aLinks + otherId),
                    updatedAt = now
                )
            )
        }
        if (cardId !in bLinks) {
            dao.upsert(
                b.copy(
                    linksJson = LumenCapture.linksToJson(bLinks + cardId),
                    updatedAt = now
                )
            )
        }
    }

    /** Removes the link between two cards (from both sides). */
    suspend fun unlink(cardId: String, otherId: String) {
        val a = dao.get(cardId) ?: return
        val b = dao.get(otherId) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            a.copy(
                linksJson = LumenCapture.linksToJson(
                    LumenCapture.linksFromJson(a.linksJson) - otherId
                ),
                updatedAt = now
            )
        )
        dao.upsert(
            b.copy(
                linksJson = LumenCapture.linksToJson(
                    LumenCapture.linksFromJson(b.linksJson) - cardId
                ),
                updatedAt = now
            )
        )
    }

    suspend fun delete(cardId: String) {
        dao.delete(cardId)
    }

    /**
     * Puts a card into training: schedules its first FSRS review from now.
     */
    suspend fun startTraining(cardId: String, now: Instant = Instant.now()) {
        val existing = dao.get(cardId) ?: return
        if (existing.fsrsCardJson != null) return
        val card = Card.builder().due(now).build()
        dao.upsert(
            existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(card),
                dueAt = (card.due ?: now.plusSeconds(60)).toEpochMilli(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun stopTraining(cardId: String) {
        val existing = dao.get(cardId) ?: return
        dao.upsert(
            existing.copy(
                fsrsCardJson = null,
                dueAt = null,
                reviewCount = 0,
                lastRating = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun dueCards(now: Instant = Instant.now(), limit: Int = 20): List<LumenCardEntity> =
        dao.dueCards(now.toEpochMilli(), limit.coerceAtLeast(1))

    /**
     * Applies a training rating with the same FSRS scheduler used for learning
     * cards. Returns the next due time, or null if the card was not in training.
     */
    suspend fun rateTraining(
        cardId: String,
        rating: LumenRating,
        now: Instant = Instant.now()
    ): Instant? {
        val existing = dao.get(cardId) ?: return null
        val oldJson = existing.fsrsCardJson ?: return null
        val oldCard = FsrsCardCodec.fromJson(oldJson)
        val result = scheduler.reviewCard(oldCard, rating.toFsrs(), now, null)
        var persisted = result.card()
        var nextDue = persisted.due ?: now.plusSeconds(86_400)
        if (persisted.due == null) {
            persisted = Card.builder().due(nextDue).build()
        }
        dao.upsert(
            existing.copy(
                fsrsCardJson = FsrsCardCodec.toJson(persisted),
                dueAt = nextDue.toEpochMilli(),
                reviewCount = existing.reviewCount + 1,
                lastRating = rating.value,
                updatedAt = System.currentTimeMillis()
            )
        )
        return nextDue
    }

    /** Training prompt for a card: front, with back as the revealed answer. */
    fun trainingPrompt(card: LumenCardEntity): Pair<String, String> =
        card.front to card.back.ifBlank { card.quote }

    /**
     * Lossless backup of the whole box as JSON: every slip with its snippets,
     * links, keywords, hub flag, and FSRS state, addresses verbatim.
     */
    suspend fun exportJson(): String {
        val all = dao.observeAll().first()
        return LumenBoxExport.toJson(all)
    }

    /** The literature box: all captured cards grouped by their source book with
     * real titles, one bibliographic slip per source. Pure-local — no AI.
     */
    suspend fun sources(): List<LumenSources.Source> {
        val all = dao.observeAll().first()
        if (all.none { it.bookId.isNotBlank() }) return emptyList()
        val bookIds = all.map { it.bookId }.filter { it.isNotBlank() }.distinct()
        val books = bookDao?.let { bd ->
            bookIds.mapNotNull { bd.getById(it) }
        } ?: emptyList()
        val metas = books.map { LumenSources.BookMeta(it.id, it.title, it.author) }
        return LumenSources.group(all, metas)
    }
}

/**
 * Pure helpers for Lumen capture: bounded reading windows, keyword extraction,
 * snippet (de)serialization, and draft parsing. Android-free for unit tests.
 */
object LumenCapture {
    /** ~250 words on each side of the position (~5.5 chars/word). */
    const val DEFAULT_RADIUS_CHARS = 1_375

    /**
     * The passage around [offset], trimmed outward to whole-sentence boundaries
     * where one exists within a small slack, clamped to the text bounds.
     */
    fun captureWindow(fullText: String, offset: Int?, radiusChars: Int = DEFAULT_RADIUS_CHARS): String {
        if (fullText.isBlank()) return ""
        val center = (offset ?: 0).coerceIn(0, fullText.length)
        val start = (center - radiusChars).coerceAtLeast(0)
        val end = (center + radiusChars).coerceAtMost(fullText.length)
        // Snap edges to whole sentences, but only when the radius actually cut
        // the text — a clamped edge already sits at the text boundary, and
        // snapping there would drop the first/last sentence.
        val from = if (start == 0) 0 else findBoundary(fullText, start, minOf(start + 100, end), last = false)
        val to = if (end == fullText.length) fullText.length
            else findBoundary(fullText, maxOf(end - 100, from), end, last = true)
        if (from >= to) return fullText.substring(start, end).trim()
        return fullText.substring(from, to).trim()
    }

    /**
     * First (or last) sentence boundary in [from, to). A boundary is .!? or a
     * newline followed by whitespace or end-of-text.
     */
    private fun findBoundary(text: String, from: Int, to: Int, last: Boolean): Int {
        var best = if (last) to else from
        var i = from
        while (i < to && i < text.length) {
            val c = text[i]
            val isBoundary = (c == '.' || c == '!' || c == '?' || c == '\n') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            if (isBoundary) {
                best = (i + 1).coerceAtMost(text.length)
                if (!last) return best
            }
            i++
        }
        return best
    }

    private val STOP_WORDS = setOf(
        "the", "and", "that", "with", "this", "have", "from", "they", "will",
        "would", "there", "their", "what", "about", "which", "when", "were",
        "been", "into", "than", "then", "some", "more", "very", "just", "only",
        "also", "because", "could", "should", "these", "those", "your", "them"
    )

    /** Top significant words, lowercased, space-separated — for local matching. */
    fun extractKeywords(text: String, max: Int = 12): String =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
            )
            .take(max)
            .joinToString(" ") { it.key }

    fun snippetsToJson(snippets: List<LumenSnippet>): String {
        val array = JSONArray()
        for (snippet in snippets) {
            val o = JSONObject()
                .put("text", snippet.text)
                .put("addedAt", snippet.addedAt)
            snippet.fraction?.let { o.put("fraction", it.toDouble()) }
            array.put(o)
        }
        return array.toString()
    }

    fun snippetsFromJson(json: String): List<LumenSnippet> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            LumenSnippet(
                text = o.optString("text"),
                addedAt = o.optLong("addedAt"),
                fraction = if (o.has("fraction")) o.optDouble("fraction").toFloat() else null
            )
        }
    }.getOrDefault(emptyList())

    fun linksToJson(links: List<String>): String {
        val array = JSONArray()
        for (link in links) array.put(link)
        return array.toString()
    }

    fun linksFromJson(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val s = array.optString(i)
            s.ifBlank { null }
        }
    }.getOrDefault(emptyList())

    /**
     * On-device draft used when Gemini is unavailable: the first whole sentence
     * becomes the front, the back stays empty for the user to fill in.
     */
    fun fallbackDraft(passage: String): Pair<String, String> {
        val flat = passage.replace(Regex("\\s+"), " ").trim()
        val firstSentence = flat
            .split(Regex("(?<=[.!?]) "))
            .firstOrNull { it.trim().length >= 12 }
            ?.trim()
            ?: flat.take(80)
        val front = if (firstSentence.length > 90) firstSentence.take(87).trimEnd() + "…" else firstSentence
        return front to ""
    }

    /**
     * Parses the model's reply into (front, back). Accepts strict JSON, JSON
     * wrapped in prose or code fences, a plain "Front:"/"Back:" label
     * format, and truncated/sloppy JSON — small on-device models rarely emit
     * textbook JSON, and landing a real card is better than silently degrading
     * to the raw-passage draft. Returns null only when nothing usable is
     * present.
     */
    fun parseDraft(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        val cleaned =
            raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        // 1. The JSON object, whether it is the whole reply or buried in prose.
        val root =
            runCatching { JSONObject(cleaned) }.getOrNull()
                ?: run {
                    val start = cleaned.indexOf('{')
                    val end = cleaned.lastIndexOf('}')
                    if (start in 0 until end) {
                        runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
                    } else {
                        null
                    }
                }
        if (root != null) {
            val front = cleanField(root.optString("front"), maxLength = 120)
            if (front.isBlank()) return null
            return front to cleanField(root.optString("back"), maxLength = 400)
        }

        // 2. Labeled lines: "Front: ..." / "Back: ..." (small models often
        //    answer in plain text when they cannot produce JSON).
        val frontMarker = Regex("(?i)\\bfront\\s*[:\\-]").find(cleaned)
        if (frontMarker != null) {
            val frontStart = frontMarker.range.last + 1
            val backMarker =
                Regex("(?i)\\bback\\s*[:\\-]").find(cleaned, frontStart)
            if (backMarker != null) {
                val front = cleanField(cleaned.substring(frontStart, backMarker.range.first), maxLength = 120)
                val back = cleanField(cleaned.substring(backMarker.range.last + 1), maxLength = 400)
                if (front.isNotBlank()) return front to back
            }
        }

        // 3. Truncated or sloppy JSON: the token cap cut the reply mid-object
        //    or quotes got mangled. Extracting the string values directly
        //    salvages everything the model did manage to write.
        val frontRaw = jsonStringValue(cleaned, "front", truncated = true) ?: return null
        val front = cleanField(frontRaw, maxLength = 120)
        if (front.isBlank()) return null
        val frontMatch =
            Regex("""(?i)["']front["']\s*:\s*["']""").find(cleaned) ?: return null
        val remainder = cleaned.substring(frontMatch.range.last + 1)
        val backRaw =
            jsonStringValue(remainder, "back", truncated = true).orEmpty()
        return front to cleanField(backRaw, maxLength = 400)
    }

    /**
     * Extracts a JSON string value for [key] from [source] even when the
     * enclosing object is broken. [truncated] also allows a value cut off at
     * the end of the reply (no closing quote). Returns null when the key is
     * absent. Handles double- and single-quoted keys and values.
     */
    private fun jsonStringValue(source: String, key: String, truncated: Boolean): String? {
        val full =
            Regex("""(?i)["']$key["']\s*:\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""")
                .find(source)
        if (full != null) {
            return unescapeJson(full.groupValues[1].ifEmpty { full.groupValues[2] })
        }
        if (!truncated) return null
        val cut =
            Regex("""(?i)["']$key["']\s*:\s*(["'])(.*)$""", RegexOption.DOT_MATCHES_ALL)
                .find(source)
                ?: return null
        return unescapeJson(cut.groupValues[2])
    }

    private fun unescapeJson(value: String): String =
        value.replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")

    /**
     * True when [front] is a long verbatim chunk of [passage] — the model
     * echoing the source instead of writing its own idea. Only exact,
     * contiguous text of at least [minLength] chars counts, so paraphrases
     * and short quoted terms pass. Used to reject copy-paste cards.
     */
    fun isPassageEcho(front: String, passage: String, minLength: Int = 24): Boolean {
        val normalizedFront = normalizeWhitespace(front).lowercase()
        if (normalizedFront.length < minLength) return false
        return normalizeWhitespace(passage).lowercase().contains(normalizedFront)
    }

    private fun normalizeWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    /** Trims quotes/emphasis markers, collapses whitespace, and caps length. */
    private fun cleanField(value: String, maxLength: Int): String {
        val cleaned =
            value.trim()
                .trim('"', '\'', '*', '`')
                .replace(Regex("\\s+"), " ")
                .trim()
        if (cleaned.length <= maxLength) return cleaned
        return cleaned.take(maxLength - 1).trimEnd() + "…"
    }
}

/**
 * Pure driver for the on-device model's capture attempts. Tries the full
 * prompt once, then one short retry when the reply was unusable (unparseable
 * or a verbatim copy of the passage). A small model's first attempt is
 * sometimes a dud — a second, stricter chance is cheap and lands a real card
 * instead of the raw-passage draft. Android-free for unit tests.
 */
object LumenLocalDraft {
    /** Tokens reserved for the reply; the rest of the budget belongs to the prompt. */
    const val REPLY_TOKENS = 384

    /** Why a reply from the on-device model could not be used, in the reader's words. */
    enum class Rejection(val explanation: String) {
        NO_REPLY("the model didn't answer"),
        UNPARSEABLE("the model's reply wasn't a usable card"),
        PASSAGE_ECHO("the model copied the passage instead of writing its own card"),
    }

    data class Outcome(
        val card: Pair<String, String>?,
        val rejection: Rejection?,
        val attempts: Int,
    )

    private data class Attempt(val card: Pair<String, String>?, val rejection: Rejection?)

    suspend fun generate(
        call: suspend (LlmRequest) -> Result<LlmResult>,
        passage: String,
        bookTitle: String,
        debugLog: (String) -> Unit = {},
        onPromptBuilt: (String) -> Unit = {},
    ): Outcome {
        suspend fun attempt(prompt: String): Attempt {
            // Reported from here so the diagnostic records the prompt actually
            // sent, including the stricter retry below.
            onPromptBuilt(prompt)
            val raw =
                runCatching { call(LlmRequest(prompt, maxOutputTokens = REPLY_TOKENS)) }
                    .getOrNull()
                    ?.getOrNull()
                    ?.text
                    ?: return Attempt(null, Rejection.NO_REPLY)
            val parsed =
                LumenCapture.parseDraft(raw)
                    ?: run {
                        debugLog("discarded local draft (unparseable): ${raw.take(240)}")
                        return Attempt(null, Rejection.UNPARSEABLE)
                    }
            if (LumenCapture.isPassageEcho(parsed.first, passage)) {
                debugLog("discarded local draft (passage echo): ${raw.take(240)}")
                return Attempt(null, Rejection.PASSAGE_ECHO)
            }
            return Attempt(parsed, null)
        }

        val first = attempt(LumenAiPrompts.cardDraft(passage, bookTitle))
        if (first.card != null) return Outcome(first.card, null, attempts = 1)

        // One stricter retry. This was disabled while every request reloaded the
        // 521 MB model, where a second load could exhaust the phone. The model
        // is resident now, so a retry costs one more inference and no reload —
        // and a small model's first answer is often a dud worth re-asking.
        val second = attempt(LumenAiPrompts.cardDraftStrict(passage, bookTitle))
        return Outcome(
            card = second.card,
            rejection = if (second.card == null) second.rejection ?: first.rejection else null,
            attempts = 2,
        )
    }
}

/**
 * Luhmann-style addressing. A card's address is stable for its lifetime. New
 * cards continue the line (21 → 22) or insert directly behind a slip with a
 * letter (21 → 21a), the way Luhmann physically filed his Zetteln.
 */
object LumenAddress {
    /** One segment of an address: numeric prefix + letter suffix ("2a7"). */
    private data class Segment(val num: Int, val suffix: String)

    private fun parseSegment(s: String): Segment {
        val firstLetter = s.indexOfFirst { !it.isDigit() }
        return when {
            firstLetter == -1 -> Segment(s.toIntOrNull() ?: Int.MAX_VALUE, "")
            firstLetter == 0 -> Segment(-1, s)
            else -> Segment(s.take(firstLetter).toIntOrNull() ?: Int.MAX_VALUE, s.substring(firstLetter))
        }
    }

    /**
     * Luhmann shelf order: 21 < 21a < 21a1 < 21a2 < 21b < 22 < 23 < 210.
     * Segments compare numerically when both are numbers; letters sort after
     * their number and in alphabetical order.
     */
    val COMPARATOR: Comparator<String> = Comparator { a, b ->
        val pa = a.split('/')
        val pb = b.split('/')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val sa = parseSegment(pa.getOrElse(i) { "" })
            val sb = parseSegment(pb.getOrElse(i) { "" })
            if (sa.num != sb.num) return@Comparator sa.num.compareTo(sb.num)
            if (sa.suffix != sb.suffix) return@Comparator sa.suffix.compareTo(sb.suffix)
        }
        0
    }

    /**
     * The next free address in a box. With [afterIndex], the new card is filed
     * directly behind that card, exactly like Luhmann inserting a slip: the
     * next card in the line continues numerically (21 → 22), and a sub-note
     * branches with a letter (21 → 21a; 21a → 21a1). Existing slips are never
     * renumbered, so references stay valid.
     */
    fun nextAddress(existing: List<String>, afterIndex: String?): String {
        val taken = existing.toSet()
        if (afterIndex.isNullOrBlank()) {
            // Continue the line after the numerically largest top-level slip.
            var n = (taken.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1
            while (true) {
                val candidate = "$n"
                if (candidate !in taken) return candidate
                n++
            }
        }
        // Insert directly behind afterIndex.
        val base = afterIndex ?: return nextAddress(existing, null)
        // Children alternate by depth, like Luhmann's real addresses (21/2a7):
        // behind a number-ending slip the child is lettered (21 → 21a, 21b),
        // behind a letter-ending slip the child is numbered (21a → 21a1).
        val canonicalBase = taken.firstOrNull { normalize(it) == normalize(base) } ?: base
        val nextChild = nextChildVariant(canonicalBase, taken)
        if (nextChild != null) return nextChild
        // 26 direct letters exhausted: branch deeper (21z → 21z1).
        var m = 1
        while (true) {
            val candidate = "${base}z$m"
            if (candidate !in taken) return candidate
            m++
        }
    }

    /**
     * The next free child address of [base]: letters after numbers (21 → 21a,
     * 21b → 21c), numbers after letters (21a → 21a1, 21a2 → 21a3). Returns
     * null when the alphabet is exhausted.
     */
    private fun nextChildVariant(base: String, taken: Set<String>): String? {
        // Only direct children belong to this insertion point. Descendants such
        // as 1a1 must not be mistaken for another child of 1a (or of 1).
        val normalizedBase = normalize(base)
        val directChildren = taken.filter { isDirectChildOf(normalize(it), normalizedBase) }
        return if (base.lastOrNull()?.isLetter() == true) {
            val highest = directChildren
                .map { it.substring(base.length) }
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull()
            "$base${(highest ?: 0) + 1}"
        } else {
            val highest = directChildren
                .map { it.substring(base.length) }
                .singleOrNull { it.length == 1 && it[0] in 'a'..'z' }
                ?: directChildren
                    .map { it.substring(base.length) }
                    .filter { it.length == 1 && it[0] in 'a'..'z' }
                    .maxOrNull()
            when (highest) {
                null -> base + "a"
                "z" -> null
                else -> base + (highest[0] + 1)
            }
        }
    }

    private fun isDirectChildOf(candidate: String, base: String): Boolean {
        if (!candidate.startsWith(base) || candidate.length <= base.length) return false
        val remainder = candidate.substring(base.length)
        return if (base.lastOrNull()?.isLetter() == true) {
            remainder.isNotEmpty() && remainder.all(Char::isDigit)
        } else {
            remainder.length == 1 && remainder[0] in 'a'..'z'
        }
    }

    /** Normalizes picker input so 1A, 1a, and 1a1 resolve to the stored address. */
    fun normalize(address: String): String = address.trim().lowercase()

    /** Resolves a user-selected address case-insensitively against persisted cards. */
    fun resolveExisting(existing: List<String>, selected: String?): String? {
        val normalized = selected?.let(::normalize)?.takeIf { it.isNotBlank() } ?: return null
        return existing.firstOrNull { normalize(it) == normalized }
    }

    /** The part after the box's leading number, e.g. "2a7" from "21/2a7". */
    fun relativePart(indexNumber: String): String {
        val idx = indexNumber.indexOf('/')
        return if (idx >= 0) indexNumber.substring(idx + 1) else indexNumber
    }

    /**
     * Cards in true Luhmann shelf order (21 → 21a → 21a1 → 21b → 22 → 210).
     * Files straight into the list the way Luhmann physically stacked slips —
     * never the lexicographic string order a database would give you (which
     * would put 10 before 2 and scatter deep branches). Room/DAO string sorts
     * can't do this, so any long-lived box view should route through here.
     */
    fun shelfOrder(cards: List<LumenCardEntity>): List<LumenCardEntity> =
        cards.sortedWith(compareBy(COMPARATOR) { it.indexNumber })

    /**
     * How deep [indexNumber] sits in its line: the number of cards above it
     * that are its proper ancestors. A main slip (21, 22…) has depth 0, a
     * direct child letter is depth 1 (21a), a grandchild 2 (21a1), and so on.
     * Blank addresses and Luhmann siblings (210 is not a child of 21) are
     * handled exactly like [isDescendantOf]. Used to reveal the branch tree in
     * the slip-box list instead of a flat count.
     */
    fun branchDepth(
        indexNumber: String,
        all: List<LumenCardEntity>
    ): Int {
        val address = indexNumber.trim()
        if (address.isEmpty()) return 0
        return all.count { other ->
            val anc = other.indexNumber.trim()
            anc.isNotEmpty() && anc != address && isDescendantOf(address, anc)
        }
    }

    /**
     * True when [candidate] is a strict descendant of [ancestor] in the same
     * line: 21a and 21a1 descend from 21, but 210 does NOT descend from 21
     * (Luhmann's numbers are not decimals — 210 is a sibling of 21, filed
     * after it). The character right after the ancestor prefix must be a
     * letter or digit that begins a *child* address, not a continuation of
     * the ancestor's own number.
     */
    fun isDescendantOf(candidate: String, ancestor: String): Boolean {
        if (candidate.length <= ancestor.length) return false
        if (!candidate.startsWith(ancestor)) return false
        val next = candidate[ancestor.length]
        // A digit here would extend the ancestor's trailing number (21 → 210);
        // only letters or a digit following a letter start a child.
        return if (ancestor.lastOrNull()?.isDigit() == true) {
            next.isLetter()
        } else {
            true
        }
    }

    /**
     * The line a card sits on, top-down: its ancestor addresses with each
     * step labelled by that card's front line, ending with [indexNumber]
     * itself. [all] lets each step resolve to a real card's title; ancestors
     * that are only implied (their parent slip was deleted) are skipped, so
     * the branch is shown as it actually exists. Used when filing so picking
     * "behind" a card reads as branching a line (21 → 21a → 21a1), not just
     * choosing a bare address. A blank address yields an empty list.
     */
    fun threadPath(
        indexNumber: String,
        all: List<LumenCardEntity>
    ): List<Pair<String, String>> {
        val address = indexNumber.trim()
        if (address.isEmpty()) return emptyList()
        val byAddress = all.associateBy { it.indexNumber.trim() }
        val ancestors = all
            .mapNotNull { it.indexNumber.trim().takeIf(String::isNotEmpty) }
            .filter { it != address && isDescendantOf(address, it) }
            .distinct()
            .sortedBy { it.length }
        return (ancestors + address).map { addr ->
            addr to (byAddress[addr]?.front ?: "")
        }
    }
}
