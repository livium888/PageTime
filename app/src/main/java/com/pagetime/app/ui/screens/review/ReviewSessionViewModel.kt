package com.pagetime.app.ui.screens.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.LumenRating
import com.pagetime.app.data.local.LumenCardEntity
import com.pagetime.app.data.review.ReviewSession
import com.pagetime.app.data.review.ReviewSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class ReviewUiState(
    val loading: Boolean = true,
    val session: ReviewSessionState = ReviewSessionState(emptyList()),
    val card: LumenCardEntity? = null,
    val revealed: Boolean = false,
    /** What the scheduler decided after the last answer, for a moment's feedback. */
    val lastInterval: String? = null,
)

class ReviewSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as PageTimeApp).container.lumenRepository

    private val _state = MutableStateFlow(ReviewUiState())
    val state = _state.asStateFlow()

    /** Cards held for the whole sitting; the session itself only carries ids. */
    private var cards: Map<String, LumenCardEntity> = emptyMap()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val now = Instant.now()
            // The lookahead is why this asks for a later instant than now: a
            // card falling due this evening should be answered while the
            // reader is here.
            val due = runCatching {
                repository.dueCards(
                    now = Instant.ofEpochMilli(ReviewSession.dueThreshold(now.toEpochMilli())),
                    limit = ReviewSession.MAX_SESSION,
                )
            }.getOrDefault(emptyList())

            cards = due.associateBy { it.id }
            val session = ReviewSession.start(due.map { it.id })
            _state.value = ReviewUiState(
                loading = false,
                session = session,
                card = session.current?.let { cards[it] },
                revealed = false,
            )
        }
    }

    fun reveal() {
        _state.value = _state.value.copy(revealed = true, lastInterval = null)
    }

    /**
     * Records an answer.
     *
     * Two different things happen and they must not be confused: FSRS is told
     * about the rating and decides when this card comes back on a later day,
     * while the sitting decides only whether it comes back in the next few
     * minutes. A failed card does both — it is rescheduled AND requeued.
     */
    fun grade(rating: LumenRating) {
        val current = _state.value.card ?: return
        viewModelScope.launch {
            val nextDue = runCatching { repository.rateTraining(current.id, rating) }.getOrNull()
            val advanced = ReviewSession.grade(_state.value.session, failed = rating == LumenRating.AGAIN)
            _state.value = _state.value.copy(
                session = advanced,
                card = advanced.current?.let { cards[it] },
                revealed = false,
                lastInterval = nextDue?.let { formatNextReview(it) },
            )
        }
    }

    /** Leaves the card for another day: no rating, so the scheduler is untouched. */
    fun skip() {
        val advanced = ReviewSession.skip(_state.value.session)
        _state.value = _state.value.copy(
            session = advanced,
            card = advanced.current?.let { cards[it] },
            revealed = false,
            lastInterval = null,
        )
    }

    fun prompt(card: LumenCardEntity): Pair<String, String> = repository.trainingPrompt(card)
}
