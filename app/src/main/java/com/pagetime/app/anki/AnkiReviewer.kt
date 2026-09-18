package com.pagetime.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri

/**
 * Talks to AnkiDroid's public FlashCardsContract ContentProvider directly,
 * rather than pulling in the official `com.github.ankidroid:Anki-Android:api`
 * artifact from JitPack: that artifact has a known history of missing `.aar`
 * builds, and the surface needed here is a handful of URI paths and column
 * names, copied verbatim from AnkiDroid's own source
 * (api/src/main/java/com/ichi2/anki/FlashCardsContract.kt on the
 * ankidroid/Anki-Android GitHub repo) rather than guessed.
 *
 * WHY THIS EXISTS: PageTime already credits reading time for its own
 * flashcards; this does the same for cards reviewed on the reader's real
 * Anki deck, using the reader's own note types, templates and cards. Reading
 * AnkiDroid's own review log directly, so the reader could keep opening the
 * AnkiDroid app as before, turned out to be impossible: Android refuses SAF
 * folder grants at both the storage root and the "Android" folder itself, so
 * there is no permission that reaches AnkiDroid's Android/data storage —
 * confirmed on-device, not just from documentation. This is the other path:
 * AnkiDroid's own ContentProvider hands over the next due card's rendered
 * question and answer HTML and accepts an ease rating back, so the review
 * itself happens inside PageTime instead.
 *
 * The CSS is fetched separately from the question/answer HTML — a first
 * attempt assumed AnkiDroid's internal `card.css() + card.q()` pattern meant
 * the ContentProvider's own QUESTION/ANSWER columns already included it, and
 * the result was correctly-structured but completely unstyled cards.
 * [nextCard] fetches the note's model id, that model's CSS, and prepends it
 * as a `<style>` block itself.
 */
object AnkiReviewer {

    /** AnkiDroid's own custom permission; must be requested at runtime like any dangerous permission. */
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val AUTHORITY_URI = Uri.parse("content://$AUTHORITY")
    private val SCHEDULE_URI = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
    private val NOTES_URI = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    private val MODELS_URI = Uri.withAppendedPath(AUTHORITY_URI, "models")
    private val DECKS_URI = Uri.withAppendedPath(AUTHORITY_URI, "decks")

    // ReviewInfo ("schedule") columns.
    private const val COL_NOTE_ID = "note_id"
    private const val COL_ORD = "ord"
    private const val COL_EASE = "answer_ease"
    private const val COL_TIME_TAKEN = "time_taken"

    // Note columns.
    private const val COL_MID = "mid"

    // Model columns.
    private const val COL_CSS = "css"

    // Card columns.
    private const val COL_CARD_NAME = "card_name"
    private const val COL_QUESTION = "question"
    private const val COL_ANSWER = "answer"

    // Deck columns.
    private const val COL_DECK_ID = "deck_id"

    data class Card(
        val noteId: Long,
        val ord: Int,
        val cardName: String?,
        /** Rendered HTML with the note's own CSS prepended — see [nextCard]. */
        val question: String,
        val answer: String,
    )

    /**
     * The next due card from any of the reader's Anki decks, or null if
     * nothing is due anywhere. [ReviewInfo.CONTENT_URI] only answers "what's
     * due in this one deck", defaulting to whichever deck AnkiDroid last had
     * selected — not good enough for a feature the reader shouldn't have to
     * remember to point at the right deck first, so this checks every deck
     * AnkiDroid has and returns the first one with something due.
     */
    fun nextCard(context: Context): Card? {
        val resolver = context.contentResolver
        for (deckId in allDeckIds(resolver)) {
            val due = dueCardIn(resolver, deckId) ?: continue
            return buildCard(resolver, due.first, due.second)
        }
        return null
    }

    private fun allDeckIds(resolver: ContentResolver): List<Long> =
        resolver.query(DECKS_URI, null, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_DECK_ID)
            buildList { while (cursor.moveToNext()) add(cursor.getLong(idCol)) }
        } ?: emptyList()

    /** The (noteId, ord) of the next due card in [deckId], or null if none is due there. */
    private fun dueCardIn(resolver: ContentResolver, deckId: Long): Pair<Long, Int>? =
        resolver.query(
            SCHEDULE_URI,
            null,
            "limit=?, deckID=?",
            arrayOf("1", deckId.toString()),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else {
                val noteId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_NOTE_ID))
                val ord = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ORD))
                noteId to ord
            }
        }

    private fun buildCard(resolver: ContentResolver, noteId: Long, ord: Int): Card? {
        val noteUri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
        val css = resolver.query(noteUri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getLong(cursor.getColumnIndexOrThrow(COL_MID))
        }?.let { modelId ->
            val modelUri = Uri.withAppendedPath(MODELS_URI, modelId.toString())
            resolver.query(modelUri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else {
                    val cssIdx = cursor.getColumnIndex(COL_CSS)
                    if (cssIdx >= 0) cursor.getString(cssIdx) else null
                }
            }
        } ?: ""

        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val cardUri = Uri.withAppendedPath(cardsUri, ord.toString())

        return resolver.query(cardUri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val cardNameIdx = cursor.getColumnIndex(COL_CARD_NAME)
            val style = "<style>$css</style>"
            Card(
                noteId = noteId,
                ord = ord,
                cardName = if (cardNameIdx >= 0) cursor.getString(cardNameIdx) else null,
                question = style + cursor.getString(cursor.getColumnIndexOrThrow(COL_QUESTION)),
                answer = style + cursor.getString(cursor.getColumnIndexOrThrow(COL_ANSWER)),
            )
        }
    }

    /** Reports how the reader answered [card]. [ease] is 1 (Again) through 4 (Easy). */
    fun answer(context: Context, card: Card, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put(COL_NOTE_ID, card.noteId)
            put(COL_ORD, card.ord)
            put(COL_EASE, ease)
            put(COL_TIME_TAKEN, timeTakenMs)
        }
        context.contentResolver.update(SCHEDULE_URI, values, null, null)
    }
}
