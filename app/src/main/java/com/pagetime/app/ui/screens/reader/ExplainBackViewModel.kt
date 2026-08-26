package com.pagetime.app.ui.screens.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.ExplainBackRepository
import com.pagetime.app.data.local.ExplanationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the Feynman explain-back chat flow.
 *
 * Given a book and chapter, this ViewModel:
 *  1. Loads concepts from the concept map for that chapter
 *  2. Shows the current concept
 *  3. Sends the user's explanation to Gemini for evaluation
 *  4. Stores the result
 *  5. Advances to the next concept
 */
class ExplainBackViewModel(
    application: Application,
    private val bookId: String,
    private val chapterIndex: Int,
    private val bookTitle: String,
    private val chapterTitle: String
) : AndroidViewModel(application) {

    private val container = (application as PageTimeApp).container
    private val repository: ExplainBackRepository = container.explainBackRepository

    private val _concepts = MutableStateFlow<List<String>>(emptyList())
    val concepts: StateFlow<List<String>> = _concepts.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentConceptIndex = MutableStateFlow(0)
    val currentConceptIndex: StateFlow<Int> = _currentConceptIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    private val _explanationHistory = MutableStateFlow<List<ExplanationEntity>>(emptyList())
    val explanationHistory: StateFlow<List<ExplanationEntity>> = _explanationHistory.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val currentConcept: String
        get() = _concepts.value.getOrElse(_currentConceptIndex.value) { "" }

    val totalConcepts: Int
        get() = _concepts.value.size

    init {
        // Load concepts for this chapter from the concept map.
        viewModelScope.launch {
            try {
                _concepts.value = repository.conceptsForChapter(bookId, chapterIndex)
            } catch (_: Throwable) {
                // Concept map may not exist yet — user can still type freely.
                _concepts.value = emptyList()
            }
            // Load existing explanations for this book.
            _explanationHistory.value = repository.observeExplanations(bookId).first()
        }
    }

    fun submitExplanation(text: String) {
        if (text.isBlank() || _isLoading.value) return

        val concept = currentConcept
        if (concept.isBlank()) return

        // Add user message.
        _messages.value = _messages.value + ChatMessage(
            text = text,
            isUser = true
        )

        _isLoading.value = true

        viewModelScope.launch {
            try {
                // Get the source text for context.
                val context = container.learningContextExtractor.extract(
                    container.libraryRepository.getBookById(bookId)!!,
                    chapterIndex
                )

                val evaluation = repository.submitExplanation(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    bookTitle = bookTitle,
                    conceptLabel = concept,
                    userExplanation = text,
                    sourceText = context.recentText
                )

                // Build the AI feedback message.
                val feedbackText = buildString {
                    append("Accuracy: ${evaluation.accuracy}/5 · Completeness: ${evaluation.completeness}/5 · Clarity: ${evaluation.clarity}/5")
                    append("\n\n")
                    append(evaluation.whatTheyGotRight)
                    if (evaluation.whatTheyMissed.isNotBlank()) {
                        append("\n\nWhat you missed: ${evaluation.whatTheyMissed}")
                    }
                    if (evaluation.suggestedImprovement.isNotBlank()) {
                        append("\n\n💡 ${evaluation.suggestedImprovement}")
                    }
                    if (evaluation.simplerVersion.isNotBlank()) {
                        append("\n\n📖 A clearer version:\n${evaluation.simplerVersion}")
                    }
                }

                _messages.value = _messages.value + ChatMessage(
                    text = feedbackText,
                    isUser = false,
                    isAi = true,
                    score = evaluation.overallScore
                )

                // Reload history.
                _explanationHistory.value = repository.observeExplanations(bookId).first()

            } catch (error: Throwable) {
                _messages.value = _messages.value + ChatMessage(
                    text = "Sorry, I couldn't evaluate that. ${error.message ?: "Try again."}",
                    isUser = false,
                    isAi = true
                )
                _error.value = error.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun nextConcept() {
        val nextIndex = _currentConceptIndex.value + 1
        if (nextIndex >= _concepts.value.size) {
            _isFinished.value = true
            return
        }
        _currentConceptIndex.value = nextIndex
        // Clear messages for the new concept.
        _messages.value = emptyList()
    }

    fun revise() {
        // Keep existing messages, just allow new input.
        // The user types a new explanation for the same concept.
    }
}

class ExplainBackViewModelFactory(
    private val app: Application,
    private val bookId: String,
    private val chapterIndex: Int,
    private val bookTitle: String,
    private val chapterTitle: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ExplainBackViewModel(app, bookId, chapterIndex, bookTitle, chapterTitle) as T
}
