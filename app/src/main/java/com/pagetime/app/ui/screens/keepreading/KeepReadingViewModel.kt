package com.pagetime.app.ui.screens.keepreading

import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.AiAppChosenReceiver
import com.pagetime.app.data.keepreading.KeepReading
import com.pagetime.app.data.keepreading.KeepReading.Direction
import com.pagetime.app.data.keepreading.KeepReading.Kind
import com.pagetime.app.data.keepreading.KeepReading.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "where next?" sheet: pick a direction, send the prompt to the reader's
 * AI app, read the reply, keep what is wanted.
 *
 * PageTime never calls a model. The reply arrives by being shared to the app,
 * pasted, or picked up from the clipboard when the reader comes back.
 */
class KeepReadingViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = (app as PageTimeApp).container.settingsRepository

    enum class Step { CHOOSE, AWAITING, RESULTS }

    data class UiState(
        val finished: KeepReading.Finished? = null,
        val direction: Direction = Direction.DEEPER,
        val kinds: Set<Kind> = setOf(Kind.BOOK, Kind.VIDEO, Kind.ARTICLE),
        /** The reader's own wording, when they have edited the prompt. */
        val edited: String? = null,
        val step: Step = Step.CHOOSE,
        val suggestions: List<Suggestion> = emptyList(),
        val saved: Set<String> = emptySet(),
        val aiAppLabel: String? = null,
        val notice: String? = null,
        val error: String? = null,
    ) {
        val prompt: String
            get() = edited ?: finished?.let { KeepReading.buildPrompt(it, direction, kinds) }.orEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var triedClip: String? = null

    fun open(finished: KeepReading.Finished) {
        if (_state.value.finished == finished) return
        _state.value = UiState(finished = finished)
        viewModelScope.launch {
            val label = aiAppLabel()
            val saved = runCatching { KeepReading.decode(settings.keepReadingList.first()) }
                .getOrDefault(emptyList()).map { it.title.lowercase() }.toSet()
            _state.update { it.copy(aiAppLabel = label, saved = saved) }
        }
    }

    // Choosing a direction or a kind rebuilds the prompt, so it forgets edits.
    fun setDirection(d: Direction) = _state.update { it.copy(direction = d, edited = null, notice = null) }

    fun toggleKind(k: Kind) = _state.update {
        val next = if (k in it.kinds) it.kinds - k else it.kinds + k
        it.copy(kinds = next.ifEmpty { it.kinds }, edited = null, notice = null)
    }

    fun edit(text: String) = _state.update { it.copy(edited = text) }
    fun resetEdits() = _state.update { it.copy(edited = null) }

    private suspend fun aiAppLabel(): String? {
        val (pkg, _) = runCatching { settings.aiApp() }.getOrNull() ?: return null
        val pm = getApplication<Application>().packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun awaiting(notice: String) =
        _state.update { it.copy(step = Step.AWAITING, notice = notice, error = null) }

    fun copyPrompt() {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Prompt", _state.value.prompt))
        triedClip = null
        awaiting("Copied. Paste it into your AI app and send it. When you come back with its reply copied, it is read automatically.")
    }

    private fun sendIntent(prompt: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, prompt)
    }

    /** One tap to the remembered AI app; the share sheet the first time. */
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
            if (!opened) {
                runCatching { settings.forgetAiApp() }
                share(context)
                return@launch
            }
            triedClip = null
            awaiting("Sent. When you come back with the AI's reply copied, it is read automatically.")
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
            triedClip = null
            awaiting("Sent. When you come back with the AI's reply copied, it is read automatically.")
        }.onFailure {
            _state.update { s -> s.copy(error = "Couldn't open the share sheet. Use Copy instead.") }
        }
    }

    /** A reply that was shared to the app, or typed in. */
    fun reply(text: String) {
        KeepReading.parseReply(text)
            .onSuccess { list ->
                _state.update { it.copy(step = Step.RESULTS, suggestions = list, notice = null, error = null) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message, notice = null) } }
    }

    /** Back from the AI app: read the clipboard if it holds a reply. */
    fun pickUpCopiedReply(context: Context) {
        val s = _state.value
        if (s.step != Step.AWAITING) return
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (!KeepReading.looksLikeReply(clip, s.prompt, triedClip)) return
        triedClip = clip
        reply(clip)
    }

    fun pasteReply(context: Context) {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (clip.isBlank()) {
            _state.update { it.copy(error = "The clipboard is empty. Copy the AI's reply first.") }
        } else reply(clip)
    }

    fun backToChoose() = _state.update { it.copy(step = Step.CHOOSE, error = null, notice = null) }

    fun save(items: List<Suggestion>) {
        viewModelScope.launch {
            val current = KeepReading.decode(settings.keepReadingList.first())
            settings.setKeepReadingList(KeepReading.encode(KeepReading.add(current, items)))
            _state.update { it.copy(saved = it.saved + items.map { s -> s.title.lowercase() }) }
        }
    }

    /** Opens a search for the thing, never the AI's own link. */
    fun find(context: Context, s: Suggestion) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(KeepReading.searchUrl(s))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
