package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LumenCardDao
import com.pagetime.app.data.local.LumenCardEntity
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
    val usedAi: Boolean
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
    private val scheduler: Scheduler = Scheduler.builder()
        .desiredRetention(0.9)
        .enableFuzzing(false)
        .build()
) {
    fun observeAll(): Flow<List<LumenCardEntity>> = dao.observeAll()

    fun observeBox(box: Int): Flow<List<LumenCardEntity>> = dao.observeBox(box)

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
     * AI is best-effort: if no key is configured or the call fails, the card is
     * still drafted from the raw passage so capture never blocks reading.
     */
    suspend fun draft(book: BookEntity, passage: String): LumenDraft {
        val clean = passage.trim()
        require(clean.isNotBlank()) { "Nothing to capture — move to a spot with text first" }

        if (geminiClient.hasKey()) {
            try {
                val call: suspend () -> String = {
                    geminiClient.draftLumenCard(clean, book.title)
                }
                val result = if (aiUsageRepository != null) {
                    aiUsageRepository.track(
                        bookId = book.id,
                        operation = AiUsageRepository.OPERATION_LUMEN,
                        model = geminiClient.currentModel(),
                        inputCharacters = clean.length,
                        outputItems = { it.length },
                        block = call
                    )
                } else {
                    call()
                }
                LumenCapture.parseDraft(result)?.let { (front, back) ->
                    return LumenDraft(front, back, clean, usedAi = true)
                }
            } catch (_: Exception) {
                // Fall through to the on-device draft. Capture must never fail.
            }
        }
        val (front, back) = LumenCapture.fallbackDraft(clean)
        return LumenDraft(front, back, clean, usedAi = false)
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
        val address = LumenAddress.nextAddress(
            dao.indexNumbersInBox(box.coerceAtLeast(1)), afterIndex
        )
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
        val behind = behindCardId?.let { dao.get(it) }?.takeIf { it.box == boxNumber }
        val address = LumenAddress.nextAddress(
            dao.indexNumbersInBox(boxNumber),
            behind?.indexNumber
        )
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
                updatedAt = System.currentTimeMillis()
            )
        )
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
     * The literature box: all captured cards grouped by their source book with
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

    /** Parses the Gemini JSON reply ({front, back}); tolerates code fences. */
    fun parseDraft(raw: String): Pair<String, String>? = runCatching {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = JSONObject(cleaned)
        val front = root.optString("front").trim()
        val back = root.optString("back").trim()
        if (front.isBlank()) null else front to back
    }.getOrNull()
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
        val nextChild = nextChildVariant(base, taken)
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
        val siblings = taken.filter { it.length > base.length && it.startsWith(base) }
        return if (base.lastOrNull()?.isLetter() == true) {
            val highest = siblings
                .map { it.substring(base.length) }
                .filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull()
            "$base${(highest ?: 0) + 1}"
        } else {
            val highest = siblings
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

    /** The part after the box's leading number, e.g. "2a7" from "21/2a7". */
    fun relativePart(indexNumber: String): String {
        val idx = indexNumber.indexOf('/')
        return if (idx >= 0) indexNumber.substring(idx + 1) else indexNumber
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
}
