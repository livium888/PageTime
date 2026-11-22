package com.pagetime.app.ui.screens.reader

import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.AiAppChosenReceiver
import com.pagetime.app.data.learning.SpanPoint
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.talk.TalkItThrough
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Talk it through": a Socratic conversation about the stretch just read, held
 * in the reader's own AI app, timed here, and credited from a receipt.
 * See [TalkItThrough] for the rules.
 *
 * The stretch is the one the cards use (from the last marker to the page the
 * reader is on), read without moving the marker, so making cards afterwards
 * still covers the same text.
 */
class TalkViewModel(
    app: Application,
    private val bookId: String,
) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val settings = container.settingsRepository
    private val extractor = container.learningContextExtractor

    enum class Step { CHOOSE, TALKING, RESULT }

    data class UiState(
        val step: Step = Step.CHOOSE,
        val busy: Boolean = false,
        val sectionLabel: String = "",
        val words: Int = 0,
        val questions: Int = 1,
        /** A roundtable of thinkers with the reader as a member, or one tutor. */
        val style: TalkItThrough.Style = TalkItThrough.Style.ROUNDTABLE,
        /** Thinkers the reader wants at the table; empty leaves the choice to the AI. */
        val guests: String = "",
        /** The reader's own wording, when they have edited the prompt. */
        val edited: String? = null,
        val basePrompt: String = "",
        val startedAt: Long = 0L,
        val code: String = "",
        val aiAppLabel: String? = null,
        val credit: TalkItThrough.Credit? = null,
        val receipt: TalkItThrough.Receipt? = null,
        val notice: String? = null,
        val error: String? = null,
    ) {
        val prompt: String get() = edited ?: basePrompt
    }

    private val _state = MutableStateFlow(UiState(busy = true))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var book: BookEntity? = null
    /** The Talks list entry being talked through, removed once it has earned its credit. */
    private var waitingId: String? = null
    private var passage = ""
    private var highlights: List<String> = emptyList()
    private var concepts: List<String> = emptyList()
    private var triedClip: String? = null

    /** Reads the stretch once, as the sheet opens, or resumes a conversation already sent. */
    fun open(b: BookEntity, startHere: SpanPoint?, endHere: SpanPoint?) {
        waitingId = null
        begin(b) {
            val marker = settings.spanMarker(bookId)
            (marker?.start ?: startHere) to (marker?.pendingEnd ?: endHere)
        }
    }

    /** A finished chapter from the Talks list, cut from its own first point to its last. */
    fun openFor(b: BookEntity, waiting: com.pagetime.app.data.talk.TalkQueue.Waiting) {
        waitingId = waiting.id
        begin(b) { waiting.startPoint() to waiting.endPoint() }
    }

    private fun begin(b: BookEntity, points: suspend () -> Pair<SpanPoint?, SpanPoint?>) {
        book = b
        _state.value = UiState(busy = true)
        viewModelScope.launch {
            val aiApp = aiAppLabel()
            val pending = TalkItThrough.decode(settings.talkPending(bookId))
            val (start, end) = points()
            if (start == null || end == null) {
                _state.value = UiState(error = "Turn to a page of the book first, then try again.")
                return@launch
            }
            val slice = extractor.readingSpan(b, start, end, 0)
            highlights = runCatching {
                container.highlightRepository.observeForBook(bookId).first().map { it.quote }
            }.getOrDefault(emptyList())
            concepts = runCatching {
                container.database.conceptDao().getForBook(bookId).map { it.label }
            }.getOrDefault(emptyList())
            passage = slice.text
            if (slice.backwards || slice.tooShort) {
                _state.value = UiState(
                    error = if (slice.tooShort) "There is not enough text since your last marker to talk about yet."
                    else "Turn to a page after your last marker first."
                )
                return@launch
            }
            val label = listOfNotNull(start.chapterTitle, end.chapterTitle).distinct()
                .joinToString(" to ").ifBlank { "the part you just read" }
            val questions = TalkItThrough.questionsFor(slice.words)
            _state.value = UiState(
                sectionLabel = label,
                words = slice.words,
                questions = questions,
                aiAppLabel = aiApp,
            ).let { fresh ->
                if (pending != null) fresh.copy(step = Step.TALKING, startedAt = pending.startedAt, code = pending.code)
                    .withPrompt()
                else fresh.copy(code = TalkItThrough.newCode()).withPrompt()
            }
        }
    }

    private fun UiState.withPrompt(): UiState = copy(
        basePrompt = TalkItThrough.buildPrompt(
            bookTitle = book?.title.orEmpty(),
            sectionLabel = sectionLabel,
            passage = passage,
            highlights = highlights,
            concepts = concepts,
            questions = questions,
            code = code,
            style = style,
            guests = guests,
        )
    )

    fun setStyle(style: TalkItThrough.Style) = _state.update { it.copy(style = style, edited = null).withPrompt() }
    fun setGuests(guests: String) = _state.update { it.copy(guests = guests.take(120), edited = null).withPrompt() }
    fun setQuestions(n: Int) = _state.update { it.copy(questions = n.coerceIn(1, 3), edited = null).withPrompt() }
    fun edit(text: String) = _state.update { it.copy(edited = text) }
    fun resetEdits() = _state.update { it.copy(edited = null) }

    private suspend fun aiAppLabel(): String? {
        val (pkg, _) = runCatching { settings.aiApp() }.getOrNull() ?: return null
        val pm = getApplication<Application>().packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** The prompt leaves, and the stopwatch starts. */
    private fun started(notice: String) {
        val now = System.currentTimeMillis()
        val s = _state.value
        viewModelScope.launch {
            settings.setTalkPending(bookId, TalkItThrough.encode(TalkItThrough.Pending(s.code, now, s.sectionLabel)))
        }
        triedClip = null
        _state.update { it.copy(step = Step.TALKING, startedAt = now, notice = notice, error = null) }
    }

    fun copyPrompt() {
        getApplication<Application>().getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Prompt", _state.value.prompt))
        started("Copied. Paste it into your AI app and send it.")
    }

    private fun sendIntent(prompt: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, prompt)
    }

    fun send(context: Context) {
        viewModelScope.launch {
            val target = runCatching { settings.aiApp() }.getOrNull()
            if (target == null) { share(context); return@launch }
            val opened = runCatching {
                context.startActivity(
                    sendIntent(_state.value.prompt)
                        .setComponent(ComponentName(target.first, target.second))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (!opened) { runCatching { settings.forgetAiApp() }; share(context); return@launch }
            started("Sent.")
        }
    }

    fun chooseAnotherApp(context: Context) {
        viewModelScope.launch {
            runCatching { settings.forgetAiApp() }
            _state.update { it.copy(aiAppLabel = null) }
            share(context)
        }
    }

    fun share(context: Context) {
        val app = getApplication<Application>()
        runCatching {
            val chosen = PendingIntent.getBroadcast(
                app, 0, Intent(app, AiAppChosenReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            context.startActivity(
                Intent.createChooser(sendIntent(_state.value.prompt), "Send to your AI app", chosen.intentSender)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            started("Sent.")
        }.onFailure {
            _state.update { s -> s.copy(error = "Couldn't open the share sheet. Use Copy instead.") }
        }
    }

    /** A receipt that was shared to the app, pasted, or picked up from the clipboard. */
    fun receipt(text: String) {
        val s = _state.value
        TalkItThrough.parseReceipt(text, s.code)
            .onSuccess { r ->
                val elapsed = ((System.currentTimeMillis() - s.startedAt) / 1000).coerceAtLeast(0)
                val credit = TalkItThrough.credit(elapsed, r)
                viewModelScope.launch {
                    if (credit.seconds > 0) container.balanceManager.creditTalkedThrough(credit.seconds)
                    settings.setTalkPending(bookId, null)
                    // Done: a chapter from the list leaves it.
                    waitingId?.let { id ->
                        val list = com.pagetime.app.data.talk.TalkQueue.decode(settings.talkWaiting.first())
                        settings.setTalkWaiting(
                            com.pagetime.app.data.talk.TalkQueue.encode(
                                com.pagetime.app.data.talk.TalkQueue.remove(list, id)
                            )
                        )
                    }
                }
                _state.update { it.copy(step = Step.RESULT, receipt = r, credit = credit, notice = null, error = null) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message, notice = null) } }
    }

    fun pickUpCopiedReceipt(context: Context) {
        val s = _state.value
        if (s.step != Step.TALKING) return
        val clip = clipText(context)
        if (clip.isBlank() || clip == triedClip || clip.trim() == s.prompt.trim()) return
        if (!clip.contains("RECEIPT", ignoreCase = true) && !clip.contains(s.code, ignoreCase = true)) return
        triedClip = clip
        receipt(clip)
    }

    fun pasteReceipt(context: Context) {
        val clip = clipText(context)
        if (clip.isBlank()) _state.update { it.copy(error = "The clipboard is empty. Copy the AI's receipt first.") }
        else receipt(clip)
    }

    private fun clipText(context: Context): String =
        context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

    /** Gives up the conversation: nothing is credited. */
    fun cancel() {
        viewModelScope.launch { settings.setTalkPending(bookId, null) }
        _state.update { it.copy(step = Step.CHOOSE, startedAt = 0L, notice = null, error = null) }
    }
}

class TalkViewModelFactory(
    private val app: Application,
    private val bookId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TalkViewModel(app, bookId) as T
}
