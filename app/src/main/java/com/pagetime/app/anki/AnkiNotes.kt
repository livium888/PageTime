package com.pagetime.app.anki

import com.pagetime.app.data.learning.ClozeText
import com.pagetime.app.data.local.LearningCardEntity
import org.json.JSONArray

/**
 * What a PageTime card becomes in Anki, and which cards are sent.
 *
 * Plain values with no Android in them, so the shape of a note and the rule
 * for what is sent can be tested without AnkiDroid on the machine.
 *
 * Anki's own interface cannot create a cloze note type, so a cloze card is
 * sent as a front and back: the sentence with the gap, and the sentence whole.
 */
object AnkiNotes {

    const val DECK_NAME = "PageTime"
    const val MODEL_NAME = "PageTime card"

    /** The note type's fields, in the order [fieldsFor] fills them. */
    val FIELDS = arrayOf("Front", "Back", "Source", "Book", "Chapter")

    const val QUESTION_FORMAT = "{{Front}}"
    const val ANSWER_FORMAT =
        "{{FrontSide}}<hr id=answer>{{Back}}" +
            "{{#Source}}<div class=\"src\">“{{Source}}”</div>{{/Source}}" +
            "<div class=\"from\">{{Book}}{{#Chapter}} · {{Chapter}}{{/Chapter}}</div>"

    const val CSS =
        ".card{font-family:serif;font-size:20px;text-align:left;line-height:1.5;padding:12px}" +
            ".src{margin-top:16px;font-size:15px;color:#666;font-style:italic}" +
            ".from{margin-top:8px;font-size:13px;color:#888}"

    fun escape(text: String): String =
        text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    /** Front, Back, Source, Book, Chapter. */
    fun fieldsFor(card: LearningCardEntity, bookTitle: String): Array<String> {
        val (front, back) = when {
            card.cardType == LearningCardEntity.TYPE_CLOZE ->
                escape(ClozeText.blanked(card.prompt)) to escape(ClozeText.filled(card.prompt))
            card.cardType == "mcq" -> {
                val options = runCatching {
                    val array = JSONArray(card.mcqOptions.orEmpty())
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
                val list = if (options.isEmpty()) "" else
                    "<ol type=\"A\">" + options.joinToString("") { "<li>${escape(it)}</li>" } + "</ol>"
                (escape(card.prompt) + list) to escape(card.answer)
            }
            else -> escape(card.prompt) to
                (escape(card.answer) + card.explanation?.takeIf { it.isNotBlank() }
                    ?.let { "<br><br>" + escape(it) }.orEmpty())
        }
        return arrayOf(
            front,
            back,
            escape(card.sourceQuote.orEmpty()),
            escape(bookTitle),
            escape(card.chapterTitle.orEmpty()),
        )
    }

    /** Anki tags cannot hold spaces; the book becomes one tag, and every card carries `pagetime`. */
    fun tagsFor(bookTitle: String): Set<String> {
        val book = bookTitle.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_').take(40)
        return setOfNotNull("pagetime", book.takeIf { it.isNotEmpty() })
    }

    /**
     * Which cards to send: accepted, made after sending was switched on (the
     * cards the reader already had stay where they are), and not sent before.
     */
    fun toSend(cards: List<LearningCardEntity>, since: Long, alreadySent: Set<String>): List<LearningCardEntity> =
        if (since <= 0L) emptyList() else cards.filter {
            it.status == LearningCardEntity.STATUS_KEPT && it.createdAt >= since && it.id !in alreadySent
        }

    // The record of what was sent, kept as one string.

    fun encodeSent(ids: Collection<String>): String = ids.joinToString(",")

    fun decodeSent(raw: String?): Set<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
}
