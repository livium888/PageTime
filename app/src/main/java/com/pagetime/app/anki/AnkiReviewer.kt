package com.pagetime.app.anki

import android.content.ContentValues
import android.content.Context
import android.net.Uri

/**
 * TEMPORARY EXPERIMENTAL — see the "Anki test reviewer" entry in Settings.
 *
 * Talks to AnkiDroid's public FlashCardsContract ContentProvider directly,
 * rather than pulling in the official `com.github.ankidroid:Anki-Android:api`
 * artifact from JitPack: that artifact has a known history of missing `.aar`
 * builds, and the surface needed here is a handful of URI paths and column
 * names, copied verbatim from AnkiDroid's own source
 * (api/src/main/java/com/ichi2/anki/FlashCardsContract.kt on the
 * ankidroid/Anki-Android GitHub repo) rather than guessed.
 *
 * WHY THIS EXISTS: after confirming Android will not grant any app — not
 * even one holding MANAGE_EXTERNAL_STORAGE — a folder handle that reaches
 * into AnkiDroid's own Android/data storage, reading AnkiDroid's review log
 * directly is off the table. This is the other path: AnkiDroid's own
 * ContentProvider hands over the next due card's rendered question and
 * answer HTML (the reader's own note type and template already applied) and
 * accepts an ease rating back.
 *
 * The CSS is NOT included in that HTML — a first attempt assumed it was,
 * from AnkiDroid's own internal `card.css() + card.q()` pattern, and the
 * result was correctly-structured but completely unstyled cards. The
 * ContentProvider keeps styling separate: [nextCard] fetches the note's
 * model id from the note row, that model's CSS from a second query, and
 * prepends it as a `<style>` block itself before returning the card.
 *
 * The open question this exists to answer is whether cards that lean on
 * AnkiDroid's JS bridge still work reasonably without that bridge present.
 */
object AnkiReviewer {

    /** AnkiDroid's own custom permission; must be requested at runtime like any dangerous permission. */
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val AUTHORITY_URI = Uri.parse("content://$AUTHORITY")
    private val SCHEDULE_URI = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
    private val NOTES_URI = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    private val MODELS_URI = Uri.withAppendedPath(AUTHORITY_URI, "models")

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

    data class Card(
        val noteId: Long,
        val ord: Int,
        val cardName: String?,
        /** Rendered HTML with the note's own CSS prepended — see [nextCard]. */
        val question: String,
        val answer: String,
    )

    /**
     * The next due card from AnkiDroid's currently-selected deck, or null if
     * none is due. Deliberately not aggregated across every deck yet — that
     * is a real gap for later, not solved here, since the immediate question
     * is whether rendering and JS behavior work at all on a handful of real
     * cards.
     */
    fun nextCard(context: Context): Card? {
        val resolver = context.contentResolver
        val due = resolver.query(SCHEDULE_URI, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val noteId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_NOTE_ID))
            val ord = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ORD))
            noteId to ord
        } ?: return null
        val (noteId, ord) = due

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
