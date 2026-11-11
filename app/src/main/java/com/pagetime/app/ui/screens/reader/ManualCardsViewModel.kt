package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import com.pagetime.app.data.AiAppChosenReceiver
import com.pagetime.app.data.learning.CopiedReply
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.learning.CardDensity
import com.pagetime.app.data.learning.ManualCardImport
import com.pagetime.app.data.learning.ManualCardPrompt
import com.pagetime.app.data.learning.PromptRejection
import com.pagetime.app.data.learning.RawPrompt
import com.pagetime.app.data.learning.ReadingSpan
import com.pagetime.app.data.learning.SpanMarker
import com.pagetime.app.data.learning.SpanPoint
import com.pagetime.app.data.local.BookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Walks the reader through making cards from the stretch they just read:
 * copy the prompt, paste it into their own LLM, paste the reply back, tick the
 * cards to keep. See docs/decisions/0002-manual-flashcards-via-your-llm.md.
 *
 * EVERYTHING IS RECOMPUTED FROM STORED POSITIONS
 *
 * The reader leaves the app to use their LLM, and Android may kill this
 * process while they are gone. Nothing the next step needs lives only in
 * memory: the marker and, once a prompt is copied, the end of the span that
 * was copied are in the settings store, and the text is cut again from them
 * when the reply arrives.
 */
class ManualCardsViewModel(
    app: Application,
    private val bookId: String,
) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val settings = container.settingsRepository
    private val extractor = container.learningContextExtractor
    private val cardDao = container.database.learningCardDao()

    enum class Step { SPAN, REVIEW, DONE }

    data class ReviewCard(val raw: RawPrompt, val keep: Boolean)

    data class UiState(
        val step: Step = Step.SPAN,
        val busy: Boolean = false,
        /** The sheet found no marker and set one at this page. */
        val markerJustSet: Boolean = false,
        val slice: ReadingSpan.Slice? = null,
        /** How many cards the reader wants asked for; see [CardDensity]. */
        val density: CardDensity = CardDensity.DEFAULT,
        val fromLabel: String? = null,
        val toLabel: String? = null,
        val endAdjust: Int = 0,
        /** A prompt was copied and its reply has not been imported yet. */
        val awaitingReply: Boolean = false,
        /** Gentle confirmation of what just happened. */
        val notice: String? = null,
        /** Something the reader has to fix before going on. */
        val error: String? = null,
        val review: List<ReviewCard> = emptyList(),
        val leftOut: List<Pair<RawPrompt, PromptRejection>> = emptyList(),
        val problems: List<String> = emptyList(),
        val added: Int = 0,
        /** The name of the app prompts were last sent to, for a one-tap button; null if none yet. */
        val aiAppLabel: String? = null,
    ) {
        val cardsAsked: Int get() = ManualCardPrompt.cardTarget(slice?.words ?: 0, density)
        val chosen: Int get() = review.count { it.keep }
    }

    private val _state = MutableStateFlow(UiState(busy = true))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var book: BookEntity? = null
    private var hereStart: SpanPoint? = null
    private var hereEnd: SpanPoint? = null
    private var marker: SpanMarker? = null

    /**
     * Opens the sheet at the reader's current position.
     *
     * [here] is taken once, when the sheet opens: the reader is looking at one
     * page, and a span that kept moving as they turned pages behind the sheet
     * would be one they could not read the edges of.
     */
    fun open(book: BookEntity, startHere: SpanPoint?, endHere: SpanPoint?) {
        this.book = book
        hereStart = startHere
        hereEnd = endHere
        _state.value = UiState(busy = true)
        viewModelScope.launch {
            var current = settings.spanMarker(bookId)
            var justSet = false
            if (current == null && startHere != null) {
                // The reader's session start was never recorded (the book was
                // opened before this feature, or the position was not known
                // yet). Start here rather than refuse; nothing is lost, the
                // span just begins now.
                current = settings.ensureSpanMarker(bookId, startHere, System.currentTimeMillis())
                justSet = true
            }
            marker = current
            if (current == null || endHere == null && current.pendingEnd == null) {
                _state.value = UiState(error = "Turn to a page of the book first, then try again.")
                return@launch
            }
            recompute(markerJustSet = justSet)
        }
    }

    /** Cuts the text again from the stored positions and refreshes what the sheet shows. */
    private suspend fun recompute(markerJustSet: Boolean = false, notice: String? = null) {
        val b = book ?: return
        val m = marker ?: return
        val pending = m.pendingEnd
        val end = pending ?: hereEnd ?: return
        val adjust = if (pending != null) m.pendingAdjust else _state.value.endAdjust
        val slice = extractor.readingSpan(b, m.start, end, adjust)
        val density = runCatching { settings.cardDensity() }.getOrDefault(CardDensity.DEFAULT)
        val aiAppLabel = aiAppLabel()
        _state.update {
            it.copy(
                aiAppLabel = aiAppLabel,
                step = Step.SPAN,
                busy = false,
                slice = slice,
                density = density,
                fromLabel = m.start.chapterTitle,
                toLabel = end.chapterTitle,
                endAdjust = adjust,
                awaitingReply = pending != null,
                markerJustSet = markerJustSet,
                notice = notice,
                error = null,
            )
        }
    }

    /** Moves the end one paragraph either way; only before the prompt is copied. */
    fun nudgeEnd(delta: Int) {
        if (_state.value.awaitingReply) return
        _state.update { it.copy(endAdjust = (it.endAdjust + delta).coerceIn(-MAX_NUDGE, MAX_NUDGE), busy = true) }
        viewModelScope.launch { recompute() }
    }

    /** Begins the span at this page, dropping the text above it. */
    fun startFromHere() {
        val start = hereStart ?: return
        viewModelScope.launch {
            val fresh = SpanMarker(start, System.currentTimeMillis())
            settings.saveSpanMarker(bookId, fresh)
            marker = fresh
            _state.update { it.copy(endAdjust = 0, busy = true) }
            recompute(notice = "Start moved to this page.")
        }
    }

    /** Throws away a copied-but-unanswered prompt so the span follows the page again. */
    fun startOver() {
        val m = marker ?: return
        viewModelScope.launch {
            val cleared = m.copy(pendingEnd = null, pendingAdjust = 0)
            settings.saveSpanMarker(bookId, cleared)
            marker = cleared
            _state.update { it.copy(endAdjust = 0, busy = true) }
            recompute()
        }
    }

    /** The span can be sent: something to ask about, not too much, not backwards. */
    private fun sendable(slice: ReadingSpan.Slice?): Boolean =
        slice != null && !slice.backwards && !slice.tooShort && !slice.tooLong

    private suspend fun buildPrompt(): String? {
        val b = book ?: return null
        val slice = _state.value.slice
        if (!sendable(slice)) return null
        val label = listOfNotNull(_state.value.fromLabel, _state.value.toLabel)
            .distinct().joinToString(" to ").ifBlank { "the part you just read" }
        return ManualCardPrompt.build(
            bookTitle = b.title,
            chapterLabel = label,
            span = slice!!.text,
            template = runCatching { settings.chapterPromptTemplate() }.getOrNull(),
            density = _state.value.density,
        )
    }

    /** Remembers what was copied, so the reply can be matched to it later. */
    private suspend fun rememberCopied() {
        val m = marker ?: return
        val end = m.pendingEnd ?: hereEnd ?: return
        val withPending = m.withPending(end, _state.value.endAdjust)
        settings.saveSpanMarker(bookId, withPending)
        marker = withPending
    }

    fun copyPrompt() {
        viewModelScope.launch {
            val prompt = buildPrompt() ?: return@launch
            val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Card prompt", prompt))
            rememberCopied()
            recompute(notice = "Copied. Paste it into your LLM app and send it.")
        }
    }

    /** The remembered AI app's name, or null if there is none or it has been uninstalled. */
    private suspend fun aiAppLabel(): String? {
        val (pkg, _) = runCatching { settings.aiApp() }.getOrNull() ?: return null
        val pm = getApplication<Application>().packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun sendIntent(prompt: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, prompt)
    }

    /**
     * Opens the share sheet, and remembers the app that is picked from it so
     * the next prompt can go there with one tap. See [AiAppChosenReceiver].
     */
    fun sharePrompt(context: Context) {
        viewModelScope.launch {
            val prompt = buildPrompt() ?: return@launch
            rememberCopied()
            runCatching {
                val app = getApplication<Application>()
                val chosen = PendingIntent.getBroadcast(
                    app,
                    0,
                    Intent(app, AiAppChosenReceiver::class.java),
                    // Mutable, because the system adds the chosen app to it.
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                context.startActivity(
                    Intent.createChooser(sendIntent(prompt), "Send to your AI app", chosen.intentSender)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                _state.update { s -> s.copy(error = "Couldn't open the share sheet. Use Copy instead.") }
            }
            recompute(notice = SENT_NOTICE)
        }
    }

    /** Sends the prompt straight to the app used last time; the share sheet if that fails. */
    fun sendToAiApp(context: Context) {
        viewModelScope.launch {
            val target = runCatching { settings.aiApp() }.getOrNull()
            if (target == null) {
                sharePrompt(context)
                return@launch
            }
            val prompt = buildPrompt() ?: return@launch
            rememberCopied()
            val opened = runCatching {
                context.startActivity(
                    sendIntent(prompt)
                        .setComponent(ComponentName(target.first, target.second))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (!opened) {
                // Uninstalled, or it no longer takes shared text. Ask again.
                runCatching { settings.forgetAiApp() }
                sharePrompt(context)
                return@launch
            }
            recompute(notice = SENT_NOTICE)
        }
    }

    /** The reader wants a different app from now on. */
    fun chooseAnotherAiApp(context: Context) {
        viewModelScope.launch {
            runCatching { settings.forgetAiApp() }
            sharePrompt(context)
        }
    }

    private var triedClip: String? = null

    /**
     * Called when the reader comes back to the sheet while a reply is awaited:
     * if they copied the reply, read it without being asked. Silent when the
     * clipboard holds anything else.
     */
    fun pickUpCopiedReply(context: Context) {
        val s = _state.value
        if (s.step != Step.SPAN || !s.awaitingReply || s.busy) return
        val clip = context.getSystemService(ClipboardManager::class.java) ?: return
        // The description says whether it is text without reading the text.
        if (clip.primaryClipDescription?.hasMimeType("text/*") != true) return
        val text = runCatching {
            clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()
        if (!CopiedReply.worthReading(text, s.slice?.text.orEmpty(), triedClip)) return
        triedClip = text
        reviewReply(text, quiet = true)
    }

    fun pasteReply(context: Context) {
        val clip = context.getSystemService(ClipboardManager::class.java)
        val text = clip?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
            .orEmpty()
        reviewReply(text)
    }

    /** Public so a test or a future text box can feed a reply in directly. */
    fun reviewReply(reply: String, quiet: Boolean = false) {
        if (reply.isBlank()) {
            if (quiet) return
            _state.update { it.copy(error = "The clipboard is empty. Copy the model's reply first.") }
            return
        }
        val b = book ?: return
        val m = marker ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val end = m.pendingEnd ?: hereEnd
            if (end == null) {
                _state.update { it.copy(busy = false, error = "Turn to a page of the book first.") }
                return@launch
            }
            val adjust = if (m.pendingEnd != null) m.pendingAdjust else _state.value.endAdjust
            val slice = extractor.readingSpan(b, m.start, end, adjust)
            val existing = runCatching { cardDao.promptsForBook(b.id) }.getOrDefault(emptyList())
            val review = ManualCardImport.review(reply, slice.text, existing)
            when {
                // Picked up unasked and it was not the cards: say nothing.
                quiet && (!review.sawJson || !review.foundAnything) -> _state.update { it.copy(busy = false) }
                !review.sawJson -> _state.update {
                    it.copy(
                        busy = false,
                        error = "That doesn't look like the cards. Copy the model's whole reply " +
                            "(the code block) and try again.",
                    )
                }
                !review.foundAnything -> _state.update {
                    it.copy(
                        busy = false,
                        error = "No cards in that reply." +
                            (review.problems.firstOrNull()?.let { p -> " $p" } ?: ""),
                    )
                }
                else -> _state.update {
                    it.copy(
                        step = Step.REVIEW,
                        busy = false,
                        review = review.accepted.map { raw -> ReviewCard(raw, keep = true) },
                        leftOut = review.leftOut,
                        problems = review.problems,
                    )
                }
            }
        }
    }

    fun toggle(index: Int) {
        _state.update { s ->
            s.copy(review = s.review.mapIndexed { i, c -> if (i == index) c.copy(keep = !c.keep) else c })
        }
    }

    fun backToSpan() {
        _state.update { it.copy(step = Step.SPAN, busy = true, error = null) }
        viewModelScope.launch { recompute() }
    }

    /** Stores the ticked cards and moves the start past what they were made from. */
    fun addChosen() {
        val b = book ?: return
        val m = marker ?: return
        val chosen = _state.value.review.filter { it.keep }.map { it.raw }
        if (chosen.isEmpty()) {
            _state.update { it.copy(error = "Tick at least one card to add.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val end = m.pendingEnd ?: hereEnd ?: return@launch
            val adjust = if (m.pendingEnd != null) m.pendingAdjust else _state.value.endAdjust
            val slice = extractor.readingSpan(b, m.start, end, adjust)
            val now = System.currentTimeMillis()
            val cards = ManualCardImport.cards(
                chosen = chosen,
                bookId = b.id,
                chapterIndex = end.chapterIndex,
                chapterTitle = end.chapterTitle,
                endFraction = end.fraction,
                now = now,
            )
            runCatching { cardDao.insertAll(cards) }
                .onFailure {
                    _state.update { s -> s.copy(busy = false, error = "Couldn't save the cards. Try again.") }
                    return@launch
                }
            // The next span starts where this one ended — exactly, so the last
            // paragraph is not taken twice. If the text could not be cut again
            // there is nothing verified to hand over to, so the marker stays
            // where it was; the copied span is still forgotten, so the sheet
            // does not go on claiming it is waiting for a reply.
            val advanced = slice.next?.let { m.advancedTo(it, now) }
                ?: m.copy(pendingEnd = null, pendingAdjust = 0)
            settings.saveSpanMarker(bookId, advanced)
            marker = advanced
            _state.update { it.copy(step = Step.DONE, busy = false, added = cards.size) }
        }
    }

    private companion object {
        const val MAX_NUDGE = 20
        const val SENT_NOTICE = "When it replies, copy the reply and come back. It will be picked up here."
    }
}

class ManualCardsViewModelFactory(
    private val app: Application,
    private val bookId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ManualCardsViewModel(app, bookId) as T
}
