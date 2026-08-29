package com.pagetime.app.data

import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LumenCardDao
import com.pagetime.app.data.local.LumenCardEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
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

/**
 * Luhmann-style index notes ("Lumen cards"): manually captured at the reader's
 * position, drafted by one small AI call, then evolved append-only. The AI is
 * involved only at capture (and never rewrites a saved card); storage and
 * re-encounters stay on-device.
 */
class LumenRepository(
    private val dao: LumenCardDao,
    private val geminiClient: GeminiLearningClient,
    private val aiUsageRepository: AiUsageRepository? = null
) {
    fun observeAll(): Flow<List<LumenCardEntity>> = dao.observeAll()

    fun observeForBook(bookId: String): Flow<List<LumenCardEntity>> = dao.observeForBook(bookId)

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

    suspend fun save(
        book: BookEntity,
        front: String,
        back: String,
        quote: String,
        sourceLocatorJson: String?,
        sourceChapterIndex: Int?,
        sourceFraction: Float
    ): LumenCardEntity {
        val now = System.currentTimeMillis()
        val card = LumenCardEntity(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            front = front.trim().ifBlank { LumenCapture.fallbackDraft(quote).first },
            back = back.trim(),
            quote = quote.trim(),
            sourceLocatorJson = sourceLocatorJson,
            sourceChapterIndex = sourceChapterIndex,
            sourceFraction = sourceFraction.coerceIn(0f, 1f),
            snippetsJson = "[]",
            keywords = LumenCapture.extractKeywords("$front $quote"),
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

    suspend fun delete(cardId: String) = dao.delete(cardId)
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
