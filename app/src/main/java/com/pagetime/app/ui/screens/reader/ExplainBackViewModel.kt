package com.pagetime.app.ui.screens.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.ExplainBackRepository
import com.pagetime.app.data.local.ExplanationEntity
import com.pagetime.app.data.learning.LearningContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives the Feynman explain-back flow and keeps its history available locally. */
class ExplainBackViewModel(
    application: Application,
    private val bookId: String,
    private val chapterIndex: Int,
    private val bookTitle: String,
    private val chapterTitle: String,
    private val currentLocatorJson: String? = null,
    private val currentTextOffset: Int? = null
) : AndroidViewModel(application) {
    private val container = (application as PageTimeApp).container
    private val repository: ExplainBackRepository = container.explainBackRepository

    private val _concepts = MutableStateFlow<List<String>>(emptyList())
    val concepts: StateFlow<List<String>> = _concepts.asStateFlow()

    private val _conceptsLoading = MutableStateFlow(true)
    val conceptsLoading: StateFlow<Boolean> = _conceptsLoading.asStateFlow()

    private val _needsConceptGeneration = MutableStateFlow(false)
    val needsConceptGeneration: StateFlow<Boolean> = _needsConceptGeneration.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentConceptIndex = MutableStateFlow(0)
    val currentConceptIndex: StateFlow<Int> = _currentConceptIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    private val _awaitingRestatement = MutableStateFlow(false)
    val awaitingRestatement: StateFlow<Boolean> = _awaitingRestatement.asStateFlow()

    private val _requestsUsed = MutableStateFlow(0)
    val requestsUsed: StateFlow<Int> = _requestsUsed.asStateFlow()

    private var evaluationsForConcept = 0

    private val _explanationHistory = MutableStateFlow<List<ExplanationEntity>>(emptyList())
    val explanationHistory: StateFlow<List<ExplanationEntity>> = _explanationHistory.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val currentConcept: String
        get() = _concepts.value.getOrElse(_currentConceptIndex.value) { "" }

    val totalConcepts: Int
        get() = _concepts.value.size

    init {
        loadConcepts()
    }

    fun retryConcepts() {
        _error.value = null
        loadConcepts()
    }

    private fun loadConcepts() {
        viewModelScope.launch {
            _conceptsLoading.value = true
            try {
                val context = extractLearningContext()
                val concepts = repository.conceptsForRange(bookId, chapterIndex, context.recentText)
                _concepts.value = concepts
                _needsConceptGeneration.value = concepts.isEmpty()
                _explanationHistory.value = repository.observeExplanations(bookId).first()
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                _error.value = throwable.message ?: "Could not load learning concepts"
            } finally {
                _conceptsLoading.value = false
            }
        }
    }

    /**
     * Bounded learning window: checkpoint → current position, falling back to
     * the start of the chapter when no checkpoint exists. Extraction touches
     * book files, so it must leave the main dispatcher.
     */
    private suspend fun extractLearningContext(): LearningContext =
        withContext(Dispatchers.IO) {
            val book = container.libraryRepository.getBook(bookId)
                ?: error("Book is no longer available")
            val checkpoint = container.settingsRepository.learningCheckpoint()
            container.learningContextExtractor.extract(
                book = book,
                chapterIndex = chapterIndex,
                checkpoint = checkpoint,
                currentLocatorJson = currentLocatorJson,
                currentTextOffset = currentTextOffset
            )
        }

    fun createLearningConcept() {
        // Explicit user action: available even when grounded concepts already
        // exist, so the reader can always ask for a fresh concept from the
        // current range instead of being stuck with the stored ones.
        if (_conceptsLoading.value) return
        viewModelScope.launch {
            _conceptsLoading.value = true
            _needsConceptGeneration.value = false
            _error.value = null
            try {
                val context = extractLearningContext()
                if (context.recentText.length < MIN_GENERATION_CHARACTERS) {
                    error("Read a little more before creating a learning concept")
                }
                val result = container.conceptMapRepository.generateForLearningRange(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    checkpoint = container.settingsRepository.learningCheckpoint(),
                    currentLocatorJson = currentLocatorJson,
                    currentTextOffset = currentTextOffset
                )
                val grounded = repository.conceptsForRange(bookId, chapterIndex, context.recentText)
                _concepts.value = grounded.ifEmpty {
                    result.concepts.map { it.label }.filter(String::isNotBlank)
                }
                if (_concepts.value.isEmpty()) error("No substantial concept was found in this reading range")
                // Start fresh on the newly generated concept.
                _currentConceptIndex.value = 0
                _messages.value = emptyList()
                _awaitingRestatement.value = false
                _isFinished.value = false
                evaluationsForConcept = 0
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                _error.value = throwable.message ?: "Could not create a learning concept"
                _needsConceptGeneration.value = true
            } finally {
                _conceptsLoading.value = false
            }
        }
    }

    fun submitExplanation(text: String) {
        if (text.isBlank() || _isLoading.value || evaluationsForConcept >= MAX_EVALUATIONS_PER_CONCEPT) return
        val concept = currentConcept
        if (concept.isBlank()) return

        _messages.value += ChatMessage(text = text, isUser = true)
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val context = extractLearningContext()
                if (context.recentText.isBlank()) {
                    error("There is no readable text available for this chapter yet")
                }
                val evaluation = repository.submitExplanation(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    bookTitle = bookTitle,
                    conceptLabel = concept,
                    userExplanation = text,
                    sourceText = context.recentText
                )
                evaluationsForConcept += 1
                _requestsUsed.value += 1
                val feedbackText = buildString {
                    // Only a grader that scored the three dimensions gets to
                    // show three numbers. The on-device grader returns one
                    // verdict instead of inventing a breakdown it never worked
                    // out, and printing "null/5" would be worse than either.
                    if (evaluation.hasBreakdown) {
                        append("Accuracy: ${evaluation.accuracy}/5 · Completeness: ${evaluation.completeness}/5 · Clarity: ${evaluation.clarity}/5")
                        append("\n\n")
                    }
                    append(evaluation.whatTheyGotRight)
                    if (evaluation.whatTheyMissed.isNotBlank()) append("\n\nWhat you missed: ${evaluation.whatTheyMissed}")
                    if (evaluation.suggestedImprovement.isNotBlank()) append("\n\n💡 ${evaluation.suggestedImprovement}")
                    if (evaluation.simplerVersion.isNotBlank()) append("\n\n📖 A clearer version:\n${evaluation.simplerVersion}")
                }
                val followUp = if (evaluation.overallScore < 4.0f) {
                    "Now restate the idea in your own words, focusing on this one improvement: ${evaluation.suggestedImprovement.ifBlank { "include the central cause-and-effect relationship" }}"
                } else {
                    "You have a solid explanation. In one sentence, restate the core idea as simply as possible."
                }
                _messages.value += ChatMessage(
                    text = "$feedbackText\n\n$followUp",
                    isUser = false,
                    isAi = true,
                    score = evaluation.overallScore
                )
                _awaitingRestatement.value = true
                _explanationHistory.value = repository.observeExplanations(bookId).first()
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                _error.value = throwable.message ?: "The AI could not evaluate this explanation"
                _messages.value += ChatMessage(
                    text = "Sorry, I couldn't evaluate that. ${throwable.message ?: "Try again."}",
                    isUser = false,
                    isAi = true
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            repository.deleteExplanation(id)
            _explanationHistory.value = repository.observeExplanations(bookId).first()
        }
    }

    fun nextConcept() {
        _awaitingRestatement.value = false
        val nextIndex = _currentConceptIndex.value + 1
        if (nextIndex >= _concepts.value.size) {
            _isFinished.value = true
        } else {
            _currentConceptIndex.value = nextIndex
            evaluationsForConcept = 0
            _messages.value = emptyList()
        }
    }

    fun revise() {
        if (evaluationsForConcept >= MAX_EVALUATIONS_PER_CONCEPT) return
        _awaitingRestatement.value = false
        _messages.value = _messages.value.filterNot { it.isAi }.takeLast(1)
    }

    companion object {
        private const val MAX_EVALUATIONS_PER_CONCEPT = 2
        private const val MIN_GENERATION_CHARACTERS = 240
    }
}

class ExplainBackViewModelFactory(
    private val app: Application,
    private val bookId: String,
    private val chapterIndex: Int,
    private val bookTitle: String,
    private val chapterTitle: String,
    private val currentLocatorJson: String? = null,
    private val currentTextOffset: Int? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ExplainBackViewModel(
            app,
            bookId,
            chapterIndex,
            bookTitle,
            chapterTitle,
            currentLocatorJson,
            currentTextOffset
        ) as T
}
