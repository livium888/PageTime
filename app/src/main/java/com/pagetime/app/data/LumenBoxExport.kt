package com.pagetime.app.data

import com.pagetime.app.data.local.LumenCardEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lossless box backup: every field of every slip, including the evolution
 * snippets, explicit links, keywords, hub flags, and FSRS training state, in
 * one JSON document. The same format reads back ([fromJson]) so a backup can
 * restore the box exactly — the address of every slip is preserved verbatim,
 * because references between slips depend on addresses never changing.
 */
object LumenBoxExport {
    const val FORMAT = "pagetime-lumen-box"
    const val VERSION = 1

    fun toJson(cards: List<LumenCardEntity>): String {
        val root =
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exportedAt", System.currentTimeMillis())
        val array = JSONArray()
        for (card in cards) {
            array.put(cardToJson(card))
        }
        root.put("cards", array)
        return root.toString()
    }

    /** Parses a backup document; null when it is not a Lumen box backup. */
    fun fromJson(json: String): List<LumenCardEntity>? =
        runCatching {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT) return null
            val array = root.optJSONArray("cards") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::cardFromJson)
            }
        }.getOrNull()

    private fun cardToJson(card: LumenCardEntity): JSONObject {
        val o =
            JSONObject()
                .put("id", card.id)
                .put("bookId", card.bookId)
                .put("box", card.box)
                .put("indexNumber", card.indexNumber)
                .put("front", card.front)
                .put("back", card.back)
                .put("quote", card.quote)
                .put("sourceLocatorJson", card.sourceLocatorJson ?: JSONObject.NULL)
                .put("sourceChapterIndex", card.sourceChapterIndex ?: JSONObject.NULL)
                .put("sourceFraction", card.sourceFraction.toDouble())
                .put("snippetsJson", card.snippetsJson)
                .put("isHub", card.isHub)
                .put("linksJson", card.linksJson)
                .put("keywords", card.keywords)
                .put("fsrsCardJson", card.fsrsCardJson ?: JSONObject.NULL)
                .put("dueAt", card.dueAt ?: JSONObject.NULL)
                .put("reviewCount", card.reviewCount)
                .put("lastRating", card.lastRating ?: JSONObject.NULL)
                .put("createdAt", card.createdAt)
                .put("updatedAt", card.updatedAt)
        return o
    }

    private fun cardFromJson(o: JSONObject): LumenCardEntity =
        LumenCardEntity(
            id = o.optString("id"),
            bookId = o.optString("bookId"),
            box = o.optInt("box", 1),
            indexNumber = o.optString("indexNumber"),
            front = o.optString("front"),
            back = o.optString("back"),
            quote = o.optString("quote"),
            sourceLocatorJson = if (o.isNull("sourceLocatorJson")) null else o.optString("sourceLocatorJson"),
            sourceChapterIndex = if (o.isNull("sourceChapterIndex")) null else o.optInt("sourceChapterIndex"),
            sourceFraction = o.optDouble("sourceFraction", 0.0).toFloat(),
            snippetsJson = o.optString("snippetsJson", "[]"),
            isHub = o.optBoolean("isHub", false),
            linksJson = o.optString("linksJson", "[]"),
            keywords = o.optString("keywords"),
            fsrsCardJson = if (o.isNull("fsrsCardJson")) null else o.optString("fsrsCardJson"),
            dueAt = if (o.isNull("dueAt")) null else o.optLong("dueAt"),
            reviewCount = o.optInt("reviewCount", 0),
            lastRating = if (o.isNull("lastRating")) null else o.optInt("lastRating"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
}
