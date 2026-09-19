package com.pagetime.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray

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
 *
 * Cards that reference an image or sound file are skipped entirely, never
 * shown or graded — see [NextCardResult.OnlyUnsupportedMediaDue] for why.
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

    // Diagnostic only, for tracking down why grading a specific card
    // silently fails inside AnkiDroid's own code (it catches its own
    // scheduling exception and still reports success — see AnkiReviewScreen's
    // diagnostics panel). type: 0=new, 1=learning, 2=review, 3=relearning.
    // original_deck_id is non-zero when the card currently sits in a
    // filtered/custom-study deck, which Anki's scheduler grades differently.
    private const val COL_TYPE = "type"
    private const val COL_ORIGINAL_DECK_ID = "original_deck_id"

    // Deck columns.
    private const val COL_DECK_ID = "deck_id"

    // ReviewInfo's own list of image/sound filenames a card's question and
    // answer reference — see [DueEntry.hasMedia].
    private const val COL_MEDIA_FILES = "media_files"

    /** How many due cards per deck to look through for one without media before giving up on that deck. */
    private const val SCAN_LIMIT = 50

    data class Card(
        val noteId: Long,
        val ord: Int,
        val cardName: String?,
        /** Rendered HTML with the note's own CSS prepended — see [nextCard]. */
        val question: String,
        val answer: String,
        /** Diagnostic only — see [COL_TYPE]/[COL_ORIGINAL_DECK_ID]. */
        val debugType: Int?,
        val debugOriginalDeckId: Long?,
    )

    sealed class NextCardResult {
        data class Found(val card: Card) : NextCardResult()
        object NoneDue : NextCardResult()

        /**
         * Every card due right now references an image or sound file.
         * AnkiDroid's ContentProvider has no read path for media — [AnkiMedia]
         * (the "media" URI) only supports insert(), confirmed against
         * AnkiDroid's own CardContentProvider source, and no other exported
         * provider exists that could serve one back by filename — so a card
         * like this can never render correctly here. Rather than show a
         * broken WebView (an Image Occlusion mask floating over a missing
         * photo, say), this is surfaced as its own state and never graded:
         * grading a card whose content the reader couldn't actually see would
         * both corrupt AnkiDroid's own scheduling for it and pay a reward for
         * an answer never shown.
         */
        object OnlyUnsupportedMediaDue : NextCardResult()
    }

    private data class DueEntry(val noteId: Long, val ord: Int, val hasMedia: Boolean)

    /**
     * The next due card from any of the reader's Anki decks that doesn't
     * depend on media, or a result explaining why there isn't one.
     * [ReviewInfo.CONTENT_URI] only answers "what's due in this one deck",
     * defaulting to whichever deck AnkiDroid last had selected — not good
     * enough for a feature the reader shouldn't have to remember to point at
     * the right deck first, so this checks every deck AnkiDroid has.
     */
    fun nextCard(context: Context): NextCardResult {
        val resolver = context.contentResolver
        var sawMediaCard = false
        for (deckId in allDeckIds(resolver)) {
            // Ask for exactly one first — the same shape AnkiDroid's own
            // reviewer effectively uses, and the one confirmed end-to-end
            // (grading it genuinely advances the queue). Only widen to a
            // bigger batch, to look past a media card, when that one entry
            // actually needs it; grading a card fetched as part of a larger
            // batch stopped advancing the queue on-device, for reasons not
            // fully understood, so the wider query stays scoped to search
            // only, never to the card actually handed back for grading.
            var entries = dueEntriesIn(resolver, deckId, 1)
            if (entries.size == 1 && entries[0].hasMedia) {
                entries = dueEntriesIn(resolver, deckId, SCAN_LIMIT)
            }
            for (entry in entries) {
                if (entry.hasMedia) {
                    sawMediaCard = true
                    continue
                }
                val card = buildCard(resolver, entry.noteId, entry.ord) ?: continue
                return NextCardResult.Found(card)
            }
        }
        return if (sawMediaCard) NextCardResult.OnlyUnsupportedMediaDue else NextCardResult.NoneDue
    }

    private fun allDeckIds(resolver: ContentResolver): List<Long> =
        resolver.query(DECKS_URI, null, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_DECK_ID)
            buildList { while (cursor.moveToNext()) add(cursor.getLong(idCol)) }
        } ?: emptyList()

    /** Up to [limit] due cards in [deckId], each flagged for whether it needs media we can't fetch. */
    private fun dueEntriesIn(resolver: ContentResolver, deckId: Long, limit: Int): List<DueEntry> =
        resolver.query(
            SCHEDULE_URI,
            null,
            "limit=?, deckID=?",
            arrayOf(limit.toString(), deckId.toString()),
            null
        )?.use { cursor ->
            val noteIdIdx = cursor.getColumnIndexOrThrow(COL_NOTE_ID)
            val ordIdx = cursor.getColumnIndexOrThrow(COL_ORD)
            val mediaIdx = cursor.getColumnIndex(COL_MEDIA_FILES)
            buildList {
                while (cursor.moveToNext()) {
                    val hasMedia = mediaIdx >= 0 &&
                        runCatching { JSONArray(cursor.getString(mediaIdx)).length() > 0 }.getOrDefault(false)
                    add(DueEntry(cursor.getLong(noteIdIdx), cursor.getInt(ordIdx), hasMedia))
                }
            }
        } ?: emptyList()

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

        // Card's own DEFAULT_PROJECTION (used whenever projection is null)
        // is only _ID/NOTE_ID/CARD_ORD/CARD_NAME/DECK_ID/QUESTION/ANSWER/FLAGS
        // — TYPE and ORIGINAL_DECK_ID exist on the resource and are fully
        // supported by the provider, but never come back unless explicitly
        // asked for. Confirmed on-device: querying with null projection
        // showed both as missing (getColumnIndex returning -1), not "0" or
        // some other real value — this was never a filtered-deck answer, it
        // was an empty answer.
        val cardProjection = arrayOf(COL_CARD_NAME, COL_QUESTION, COL_ANSWER, COL_TYPE, COL_ORIGINAL_DECK_ID)
        return resolver.query(cardUri, cardProjection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val cardNameIdx = cursor.getColumnIndex(COL_CARD_NAME)
            val typeIdx = cursor.getColumnIndex(COL_TYPE)
            val origDeckIdx = cursor.getColumnIndex(COL_ORIGINAL_DECK_ID)
            val style = "<style>$css</style>"
            // Anki's own reviewer always renders a card's fields inside an
            // element carrying class="card" — the templates' own CSS relies
            // on that wrapper existing (this note type's .card rule is where
            // its dark background and light text colors actually live).
            // Confirmed on-device: without it, those colors still apply via
            // inherited CSS variables, but with no matching dark background
            // underneath them, leaving pale text on WebView's plain white.
            fun wrapped(html: String) = "$style<div class=\"card\">$html</div>"
            Card(
                noteId = noteId,
                ord = ord,
                cardName = if (cardNameIdx >= 0) cursor.getString(cardNameIdx) else null,
                question = wrapped(cursor.getString(cursor.getColumnIndexOrThrow(COL_QUESTION))),
                answer = wrapped(cursor.getString(cursor.getColumnIndexOrThrow(COL_ANSWER))),
                debugType = if (typeIdx >= 0) cursor.getInt(typeIdx) else null,
                debugOriginalDeckId = if (origDeckIdx >= 0) cursor.getLong(origDeckIdx) else null,
            )
        }
    }

    /**
     * Reports how the reader answered [card]. [ease] is 1 (Again) through 4
     * (Easy). AnkiDroid's own update() silently skips (returns 0, throws
     * nothing) if it can't match the card by noteId+ord rather than raising
     * an error, so a caller that ignores the return value has no way to
     * tell a real grade from a no-op — check it and fail loudly instead.
     */
    fun answer(context: Context, card: Card, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put(COL_NOTE_ID, card.noteId)
            put(COL_ORD, card.ord)
            put(COL_EASE, ease)
            put(COL_TIME_TAKEN, timeTakenMs)
        }
        val rows = context.contentResolver.update(SCHEDULE_URI, values, null, null)
        check(rows > 0) { "AnkiDroid didn't record this answer (note ${card.noteId}, ord ${card.ord})" }
    }
}
