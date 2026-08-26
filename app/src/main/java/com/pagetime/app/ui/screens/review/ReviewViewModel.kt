package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LearningRating
import com.pagetime.app.data.LearningRepository
import com.pagetime.app.data.LearningStats
import com.pagetime.app.data.local.LearningCardEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: LearningRepository = (app as PageTimeApp).container.learningRepository

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

    /** For MCQ cards: the option the user tapped (null = none selected yet). */
    private val _selectedMcqOption = MutableStateFlow<String?>(null)
    val selectedMcqOption = _selectedMcqOption.asStateFlow()

    /** For MCQ cards: whether the user's selection was correct (null = not yet checked). */
    private val _mcqResult = MutableStateFlow<Boolean?>(null)
    val mcqResult = _mcqResult.asStateFlow()

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
            _selectedMcqOption.value = null
            _mcqResult.value = null
            _loading.value = false
        }
    }

    fun reveal() {
        _revealed.value = true
    }

    /** Called when user taps an MCQ option — check correctness and reveal. */
    fun selectMcqOption(option: String) {
        val card = _cards.value.firstOrNull() ?: return
        _selectedMcqOption.value = option
        _mcqResult.value = option.equals(card.answer, ignoreCase = true)
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
                _message.value = "${rating.label}. Next review in ${formatInterval(outcome.intervalDays)}."
                _stats.value = repository.observeStats().first()
                _cards.value = _cards.value.drop(1)
                _revealed.value = false
                _selectedMcqOption.value = null
                _mcqResult.value = null
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

    fun clearMessage() {
        _message.value = null
    }

    private fun formatInterval(days: Long): String = when {
        days <= 0 -> "less than a day"
        days == 1L -> "1 day"
        else -> "$days days"
    }
}
