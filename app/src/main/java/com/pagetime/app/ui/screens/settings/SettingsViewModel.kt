package com.pagetime.app.ui.screens.settings

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.learning.CardDensity
import com.pagetime.app.data.LumenAiPrompts
import com.pagetime.app.data.LumenCapture
import com.pagetime.app.data.review.ReviewReminderWorker
import com.pagetime.app.data.learning.ChapterPromptText
import com.pagetime.app.data.embed.EmbeddingModelStatus
import com.pagetime.app.data.embed.EmbeddingModelStore
import com.pagetime.app.data.embed.EmbeddingSelfTest
import com.pagetime.app.data.embed.OnnxTextEmbedder
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container

    val balanceSeconds = container.balanceManager.browseBalanceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalReadingSeconds = container.balanceManager.totalReadingSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val ratio = container.balanceManager.ratio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0)

    /**
     * The access gate, live.
     *
     * Collected whether or not the gate is switched on, because the reading
     * count is the thing the reader has to be able to trust BEFORE it starts
     * locking them out of their phone. Showing it only once the gate is
     * enabled would mean the first time anyone sees the number is the first
     * time it can hurt them.
     */
    /** What was actually read today — the honest report, not the counter. */
    val readInLastDay = container.usageRepository.readingInLastDay()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Emergency unlocks spent this week.
     *
     * Shown because it is the only honest diagnostic the reader gets. Someone
     * on twelve a week is living on the hatch rather than the front door, and
     * by design they will not notice that on their own.
     */
    val emergencyThisWeek = container.usageRepository.emergencyUnlocksThisWeek()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val gate = container.balanceManager.gate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateState.Unknown)

    val externalReadingEnabled = container.balanceManager.externalReadingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val externalReadingDailyCapSeconds = container.balanceManager.externalReadingDailyCapSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3_600L)

    init {
        // A wind-down that finished while the app was closed leaves a stored
        // switch reading "on" for a gate that is off. Settling it here is the
        // one place the reader would notice the contradiction.
        viewModelScope.launch { container.balanceManager.settleWindDownIfElapsed() }
    }

    val aiSettings = container.settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.pagetime.app.data.local.AiSettings())

    val helpEnabled = container.settingsRepository.settings
        .map { it.helpEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val embeddingModelStatus =
        container.embeddingModelStore.status
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                EmbeddingModelStatus.NotDownloaded,
            )

    /** Lines from the last self-test, or empty when it has not been run. */
    private val _embeddingSelfTest = MutableStateFlow<List<String>>(emptyList())
    val embeddingSelfTest: StateFlow<List<String>> = _embeddingSelfTest.asStateFlow()

    private val _embeddingSelfTestRunning = MutableStateFlow(false)
    val embeddingSelfTestRunning: StateFlow<Boolean> = _embeddingSelfTestRunning.asStateFlow()

    /** Cards with no vector yet, refreshed on demand rather than observed. */
    private val _embeddingPending = MutableStateFlow(0)
    val embeddingPending: StateFlow<Int> = _embeddingPending.asStateFlow()

    private val _embeddingIndexing = MutableStateFlow(false)
    val embeddingIndexing: StateFlow<Boolean> = _embeddingIndexing.asStateFlow()

    /** How much text a capture hands the model. See SettingsRepository. */
    private val _cardDensity = MutableStateFlow(CardDensity.DEFAULT)
    val cardDensity: StateFlow<CardDensity> = _cardDensity.asStateFlow()

    fun setCardDensity(value: CardDensity) {
        viewModelScope.launch {
            container.settingsRepository.setCardDensity(value)
            _cardDensity.value = value
        }
    }

    private val _captureChars = MutableStateFlow(LumenCapture.PASSAGE_TARGET_CHARS)
    val captureChars: StateFlow<Int> = _captureChars.asStateFlow()

    fun setCaptureChars(value: Int) {
        viewModelScope.launch {
            container.settingsRepository.setLumenCaptureChars(value)
            _captureChars.value = value
        }
    }

    fun refreshEmbeddingPending() {
        viewModelScope.launch {
            _embeddingPending.value =
                runCatching { container.cardEmbeddingIndexer.pendingCount() }.getOrDefault(0)
        }
    }

    /**
     * Drains the backfill queue in batches, updating the count as it goes.
     *
     * A loop of bounded batches rather than one pass over everything: a reader
     * who has kept a slip box for a year has hundreds of cards, each needing an
     * inference, and the count moving is the difference between "working" and
     * "frozen". Stops when a batch stores nothing, so a card the model cannot
     * embed cannot spin this forever.
     */
    fun indexAllCards() {
        if (_embeddingIndexing.value) return
        viewModelScope.launch {
            _embeddingIndexing.value = true
            try {
                while (true) {
                    val done = runCatching { container.cardEmbeddingIndexer.backfill() }
                        .getOrDefault(0)
                    _embeddingPending.value =
                        runCatching { container.cardEmbeddingIndexer.pendingCount() }
                            .getOrDefault(0)
                    if (done == 0 || _embeddingPending.value == 0) break
                }
            } finally {
                _embeddingIndexing.value = false
            }
        }
    }

    fun downloadEmbeddingModel() {
        viewModelScope.launch {
            container.embeddingModelStore.download()
            refreshEmbeddingPending()
        }
    }

    fun deleteEmbeddingModel() {
        viewModelScope.launch {
            container.embeddingModelStore.delete()
            _embeddingSelfTest.value = emptyList()
            _embeddingPending.value = 0
        }
    }

    /**
     * Proves the whole chain — tokenise, run, pool, normalise — against the
     * weights actually on this phone.
     *
     * Off the main thread: this loads a 22 MB model into the native runtime
     * and runs it nine times. On the main thread that is a frozen screen and,
     * on a slow device, an ANR.
     *
     * The embedder is closed in a finally. It holds a native session, and
     * leaking one per tap would eventually take the app down with an error
     * that names none of this.
     */
    fun runEmbeddingSelfTest() {
        if (_embeddingSelfTestRunning.value) return
        viewModelScope.launch {
            _embeddingSelfTestRunning.value = true
            _embeddingSelfTest.value = emptyList()
            val lines = withContext(Dispatchers.Default) {
                val store = container.embeddingModelStore
                val tokenizer = store.tokenizer()
                if (tokenizer == null) {
                    listOf("No embedding model is installed, so there is nothing to test.")
                } else {
                    var embedder: OnnxTextEmbedder? = null
                    try {
                        // Held in a non-null local as well as the nullable one:
                        // the local is what the probes call, the nullable is
                        // what the finally closes, and neither job wants the
                        // other's type.
                        val runner = OnnxTextEmbedder(store.modelFile, tokenizer)
                        embedder = runner
                        EmbeddingSelfTest.summarize(EmbeddingSelfTest.run { runner.embed(it) })
                    } catch (error: Throwable) {
                        listOf("The embedder could not start: ${error.message ?: error}")
                    } finally {
                        runCatching { embedder?.close() }
                    }
                }
            }
            _embeddingSelfTest.value = lines
            _embeddingSelfTestRunning.value = false
        }
    }

    fun setRatio(value: Double) {
        viewModelScope.launch { container.balanceManager.setRatio(value) }
    }

    fun setGateSwitchedOn(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setGateSwitchedOn(on) }
    }

    // Through the balance manager rather than straight to storage: the fence
    // on lowering the target lives there, with the lock it has to consult.
    fun setDailyTargetSeconds(seconds: Long) {
        viewModelScope.launch { container.balanceManager.setDailyTargetSeconds(seconds) }
    }

    fun setExternalReadingEnabled(value: Boolean) {
        viewModelScope.launch { container.balanceManager.setExternalReadingEnabled(value) }
    }

    fun setExternalReadingDailyCap(value: Long) {
        viewModelScope.launch { container.balanceManager.setExternalReadingDailyCap(value) }
    }

    fun setAiAnalysisLevel(level: com.pagetime.app.data.local.AiAnalysisLevel) {
        viewModelScope.launch { container.settingsRepository.setAiAnalysisLevel(level) }
    }

    /**
     * The capture prompt as the reader would edit it: their own when they have
     * tailored one, otherwise the built-in text so the editor opens on
     * something real to change rather than a blank field.
     */
    private val _lumenPrompt = MutableStateFlow(LumenAiPrompts.DEFAULT_CARD_TEMPLATE)
    val lumenPrompt: StateFlow<String> = _lumenPrompt.asStateFlow()

    /** True when the stored prompt is the reader's, not the shipped one. */
    private val _lumenPromptIsCustom = MutableStateFlow(false)
    val lumenPromptIsCustom: StateFlow<Boolean> = _lumenPromptIsCustom.asStateFlow()

    /**
     * The chapter flashcard instructions as the reader would edit them: theirs
     * when they have written some, otherwise the shipped text, so the editor
     * opens on something real to change rather than a blank field.
     */
    private val _chapterPrompt = MutableStateFlow(ChapterPromptText.DEFAULT_TEMPLATE)
    val chapterPrompt: StateFlow<String> = _chapterPrompt.asStateFlow()

    /** True when the stored instructions are the reader's, not the shipped ones. */
    private val _chapterPromptIsCustom = MutableStateFlow(false)
    val chapterPromptIsCustom: StateFlow<Boolean> = _chapterPromptIsCustom.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = container.settingsRepository.lumenPromptTemplate()
            if (stored != null) {
                _lumenPrompt.value = stored
                _lumenPromptIsCustom.value = true
            }
            container.settingsRepository.chapterPromptTemplate()?.let { stored ->
                _chapterPrompt.value = stored
                _chapterPromptIsCustom.value = true
            }
            _reviewReminders.value = container.settingsRepository.reviewReminders()
            _captureChars.value = container.settingsRepository.lumenCaptureChars()
            _cardDensity.value = container.settingsRepository.cardDensity()
        }
    }

    /** Saves a tailored prompt. Rejected templates never reach storage. */
    fun setLumenPrompt(template: String) {
        if (LumenAiPrompts.templateProblem(template) != null) return
        viewModelScope.launch {
            val custom = template != LumenAiPrompts.DEFAULT_CARD_TEMPLATE
            container.settingsRepository.setLumenPromptTemplate(if (custom) template else null)
            _lumenPrompt.value = template
            _lumenPromptIsCustom.value = custom
        }
    }

    /** Drops the reader's prompt so app updates to the built-in one apply again. */
    fun resetLumenPrompt() {
        viewModelScope.launch {
            container.settingsRepository.setLumenPromptTemplate(null)
            _lumenPrompt.value = LumenAiPrompts.DEFAULT_CARD_TEMPLATE
            _lumenPromptIsCustom.value = false
        }
    }

    /** Saves tailored instructions. Rejected templates never reach storage. */
    fun setChapterPrompt(template: String) {
        if (ChapterPromptText.templateProblem(template) != null) return
        viewModelScope.launch {
            val custom = template != ChapterPromptText.DEFAULT_TEMPLATE
            container.settingsRepository.setChapterPromptTemplate(if (custom) template else null)
            _chapterPrompt.value = template
            _chapterPromptIsCustom.value = custom
        }
    }

    /** Drops the reader's instructions so app updates to the built-in ones apply. */
    fun resetChapterPrompt() {
        viewModelScope.launch {
            container.settingsRepository.setChapterPromptTemplate(null)
            _chapterPrompt.value = ChapterPromptText.DEFAULT_TEMPLATE
            _chapterPromptIsCustom.value = false
        }
    }

    fun setHelpEnabled(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHelpEnabled(value) }
    }

    /**
     * Whether the app may tell the reader when a sitting is worth having.
     *
     * The decision of WHEN is not here — it is in ReviewReminder, which waits
     * while waiting is cheap and speaks when the delay starts costing real
     * memories. This is only the reader's permission to be spoken to at all.
     *
     * Starts true to match the stored default, so the switch does not show as
     * off for the moment before DataStore is read and then flip under the
     * reader's eyes.
     */
    private val _reviewReminders = MutableStateFlow(true)
    val reviewReminders: StateFlow<Boolean> = _reviewReminders.asStateFlow()

    fun setReviewReminders(value: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setReviewReminders(value)
            _reviewReminders.value = value
            val context = getApplication<android.app.Application>()
            if (value) {
                ReviewReminderWorker.schedule(context)
            } else {
                // Cancelled rather than left running and told to stay quiet.
                // Periodic work that wakes up only to decide it has nothing to
                // say is still the reader's battery.
                ReviewReminderWorker.cancel(context)
            }
        }
    }
}
