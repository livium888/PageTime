package com.pagetime.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Writes notes into the reader's AnkiDroid, through the same public
 * FlashCardsContract provider [AnkiReviewer] reads from. Names and columns are
 * taken from AnkiDroid's own source (`FlashCardsContract.kt` and
 * `AddContentApi.kt` in ankidroid/Anki-Android), the way AnkiReviewer's are.
 *
 * Only ever adds. Nothing here edits or deletes a note, so what the reader
 * does to their Anki collection afterwards is theirs.
 */
object AnkiExporter {

    private const val ANKI_PACKAGE = "com.ichi2.anki"
    private val AUTHORITY_URI = Uri.parse("content://com.ichi2.anki.flashcards")
    private val NOTES_URI = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    private val MODELS_URI = Uri.withAppendedPath(AUTHORITY_URI, "models")
    private val DECKS_URI = Uri.withAppendedPath(AUTHORITY_URI, "decks")

    private const val FIELD_SEPARATOR = '\u001f'

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ANKI_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, AnkiReviewer.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** One note to send: its fields in [AnkiNotes.FIELDS] order, and its tags. */
    data class Note(val id: String, val fields: Array<String>, val tags: Set<String>)

    /**
     * Sends [notes] and returns the ids that were added. Stops at the first
     * failure and returns what got in before it, so nothing is sent twice and
     * nothing is marked sent that was not.
     */
    fun send(context: Context, notes: List<Note>): List<String> {
        if (notes.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val deckId = findOrCreateDeck(resolver) ?: return emptyList()
        val modelId = findOrCreateModel(resolver) ?: return emptyList()
        val sent = mutableListOf<String>()
        for (note in notes) {
            val ok = runCatching { addNote(resolver, modelId, deckId, note) }.getOrDefault(false)
            if (!ok) break
            sent += note.id
        }
        return sent
    }

    private fun findOrCreateDeck(resolver: ContentResolver): Long? {
        resolver.query(DECKS_URI, null, null, null, null)?.use { c ->
            val id = c.getColumnIndex("deck_id")
            val name = c.getColumnIndex("deck_name")
            if (id >= 0 && name >= 0) {
                while (c.moveToNext()) if (c.getString(name) == AnkiNotes.DECK_NAME) return c.getLong(id)
            }
        }
        val values = ContentValues().apply { put("deck_name", AnkiNotes.DECK_NAME) }
        return resolver.insert(DECKS_URI, values)?.lastPathSegment?.toLongOrNull()
    }

    private fun findOrCreateModel(resolver: ContentResolver): Long? {
        resolver.query(MODELS_URI, null, null, null, null)?.use { c ->
            val id = c.getColumnIndex("_id")
            val name = c.getColumnIndex("name")
            if (id >= 0 && name >= 0) {
                while (c.moveToNext()) if (c.getString(name) == AnkiNotes.MODEL_NAME) return c.getLong(id)
            }
        }
        val values = ContentValues().apply {
            put("name", AnkiNotes.MODEL_NAME)
            put("field_names", AnkiNotes.FIELDS.joinToString(FIELD_SEPARATOR.toString()))
            put("num_cards", 1)
            put("css", AnkiNotes.CSS)
        }
        val modelUri = resolver.insert(MODELS_URI, values) ?: return null
        val template = Uri.withAppendedPath(Uri.withAppendedPath(modelUri, "templates"), "0")
        val templateValues = ContentValues().apply {
            put("card_template_name", "Card 1")
            put("question_format", AnkiNotes.QUESTION_FORMAT)
            put("answer_format", AnkiNotes.ANSWER_FORMAT)
        }
        resolver.update(template, templateValues, null, null)
        return modelUri.lastPathSegment?.toLongOrNull()
    }

    private fun addNote(resolver: ContentResolver, modelId: Long, deckId: Long, note: Note): Boolean {
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", note.fields.joinToString(FIELD_SEPARATOR.toString()))
            put("tags", note.tags.joinToString(" ") { it.replace(" ", "_") })
        }
        val noteUri = resolver.insert(NOTES_URI, values) ?: return false
        // A new note's cards start in the default deck; move them to ours.
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        resolver.query(cardsUri, null, null, null, null)?.use { c ->
            val ord = c.getColumnIndex("ord")
            while (ord >= 0 && c.moveToNext()) {
                val cardUri = Uri.withAppendedPath(cardsUri, c.getString(ord))
                resolver.update(cardUri, ContentValues().apply { put("deck_id", deckId) }, null, null)
            }
        } ?: return false
        return true
    }
}
