package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LearningRating
import com.pagetime.app.data.LearningRepository
import com.pagetime.app.data.LearningStats
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.domain.BalanceManager
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Anki-style per-button preview: what the schedule becomes for each rating. */
data class IntervalPreview(
    val rating: LearningRating,
    /** Compact caption, e.g. "10m", "2d". */
    val label: String,
)

class ReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PageTimeApp).container
    private val repository: LearningRepository = container.learningRepository
    private val balanceManager: BalanceManager = container.balanceManager

    private val _cards = MutableStateFlow<List<LearningCardEntity>>(emptyList())
    val cards = _cards.asStateFlow()

    private val _bookTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val bookTitles = _bookTitles.asStateFlow()

    private val _revealed = MutableStateFlow(false)
    val revealed = _revealed.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _stats = MutableStateFlow(LearningStats())
    val stats = _stats.asStateFlow()

    private val _sourceToOpen = MutableStateFlow<LearningCardEntity?>(null)
    val sourceToOpen = _sourceToOpen.asStateFlow()

    /** Browse seconds earned per correct review; drives the "+30 s" note. */
    val flashcardRewardSeconds: StateFlow<Long> =
        balanceManager.flashcardRewardSeconds
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30L)

    /**
     * Next-interval previews for the four rating buttons of the current card.
     * Anki computes the same thing: simulate the scheduler for each rating
     * against the card's current state without persisting anything.
     */
    private val _intervalPreviews = MutableStateFlow<Map<LearningRating, IntervalPreview>>(emptyMap())
    val intervalPreviews = _intervalPreviews.asStateFlow()

    // Prevent two quick taps from trying to review the same card concurrently.
    private var ratingInProgress = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _stats.value = repository.observeStats().first()
            val due = repository.dueCards(limit = 20)
            _cards.value = due
            _bookTitles.value = due.mapNotNull { card ->
                repository.getBookTitle(card.bookId)?.let { card.bookId to it }
            }.toMap()
            _revealed.value = false
            refreshPreviews()
            _loading.value = false
        }
    }

    fun reveal() {
        _revealed.value = true
    }

    fun openSource(card: LearningCardEntity) {
        viewModelScope.launch {
            repository.prepareSource(card)
            _sourceToOpen.value = card
        }
    }

    fun clearSourceToOpen() {
        _sourceToOpen.value = null
    }

    fun rate(rating: LearningRating) {
        val card = _cards.value.firstOrNull() ?: return
        if (ratingInProgress) return
        ratingInProgress = true
        viewModelScope.launch {
            try {
                val outcome = repository.reviewCard(card.id, rating)
                // Correct recall (HARD/GOOD/EASY) earns the configured bonus;
                // AGAIN earns nothing — guessing can never mint browse time.
                balanceManager.earnFromFlashcard(ratingCorrect = rating != LearningRating.AGAIN)
                _message.value =
                    "${rating.label} saved" +
                        (if (rating != LearningRating.AGAIN) {
                            val reward = balanceManager.flashcardReward()
                            if (reward > 0) " · +${reward}s" else ""
                        } else "") +
                        ". Next review: ${formatNextReview(outcome.nextDue)}."
                _stats.value = repository.observeStats().first()
                _cards.value = _cards.value.drop(1)
                _revealed.value = false
                refreshPreviews()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // Never let a malformed legacy card or a scheduler/database error
                // crash the Activity. Keep the card visible so the user can retry.
                _message.value = "Couldn't save that review. Please try again."
            } finally {
                ratingInProgress = false
            }
        }
    }

    fun deleteCard(cardId: String) {
        viewModelScope.launch {
            repository.deleteCard(cardId)
            refresh()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun refreshPreviews() {
        val card = _cards.value.firstOrNull() ?: run {
            _intervalPreviews.value = emptyMap()
            return
        }
        viewModelScope.launch {
            val now = Instant.now()
            val previews = LearningRating.entries.associateWith { rating ->
                val nextDue = repository.previewNextDue(card.id, rating, now)
                IntervalPreview(
                    rating = rating,
                    label = if (nextDue != null) formatIntervalShort(nextDue, now) else "",
                )
            }
            _intervalPreviews.value = previews
        }
    }
}
