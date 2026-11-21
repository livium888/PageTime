package com.pagetime.app.anki

import android.content.Context
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.LearningCardDao
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps AnkiDroid supplied with the cards the reader makes from now on.
 *
 * Watches the cards and, while sending is on, adds each newly kept one to the
 * reader's AnkiDroid, once. AnkiDroid then syncs to AnkiWeb on its own terms,
 * which is what puts them on the reader's computer; PageTime cannot start that
 * sync. Cards made before sending was switched on stay where they are.
 */
class AnkiPusher(
    private val context: Context,
    private val cards: LearningCardDao,
    private val books: BookDao,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _problem = MutableStateFlow<String?>(null)
    /** Why the last attempt did not send everything, or null. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val nudge = MutableStateFlow(0)

    /** Try again now, for the "Send now" button after a problem. */
    fun retry() { nudge.value += 1 }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(settings.ankiSendSince, cards.observeLive(), settings.ankiSentIds, nudge) { since, all, sent, _ ->
                Triple(since, all, AnkiNotes.decodeSent(sent))
            }
                // Cards arrive in bursts (a whole chapter kept at once).
                .debounce(1_500)
                .collectLatest { (since, all, sent) -> pushOnce(since, all, sent) }
        }
    }

    private suspend fun pushOnce(
        since: Long,
        all: List<com.pagetime.app.data.local.LearningCardEntity>,
        sent: Set<String>,
    ) {
        if (since <= 0L) { _problem.value = null; return }
        val due = AnkiNotes.toSend(all, since, sent)
        if (due.isEmpty()) { _problem.value = null; return }
        when {
            !AnkiExporter.isInstalled(context) -> { _problem.value = "AnkiDroid is not installed."; return }
            !AnkiExporter.hasPermission(context) -> {
                _problem.value = "PageTime is not allowed to use AnkiDroid. Switch sending off and on to ask again."
                return
            }
        }
        val titles = runCatching { books.getAll().associate { it.id to it.title } }.getOrDefault(emptyMap())
        val notes = due.map { card ->
            val title = titles[card.bookId].orEmpty()
            AnkiExporter.Note(card.id, AnkiNotes.fieldsFor(card, title), AnkiNotes.tagsFor(title))
        }
        val added = runCatching { AnkiExporter.send(context, notes) }.getOrDefault(emptyList())
        if (added.isNotEmpty()) {
            val now = AnkiNotes.decodeSent(settings.ankiSentIds.first()) + added
            settings.setAnkiSentIds(AnkiNotes.encodeSent(now))
            // Anki holds them now: they leave PageTime's own review queue, so
            // a card is reviewed in one place and never twice.
            val stamp = System.currentTimeMillis()
            added.forEach { id -> runCatching {
                cards.setStatus(id, com.pagetime.app.data.local.LearningCardEntity.STATUS_ANKI, stamp)
            } }
        }
        _problem.value = if (added.size < notes.size) {
            "Could not reach AnkiDroid. Open it once, then tap Send now."
        } else null
    }
}
