package com.pagetime.app.data.learning

data class GeneratedLearningCard(
    val topic: String,
    val question: String,
    val answer: String,
    val explanation: String,
    val sourceQuote: String,
    val confidence: Float,
    /** "qa" (default), "cloze", or "mcq" per Wozniak's 20 rules. */
    val cardType: String = "qa",
    /** For MCQ cards: list of answer choices including the correct one. */
    val mcqOptions: List<String>? = null
)

data class LearningContext(
    val bookId: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val recentText: String,
    val sourceFormat: String
)

data class AiGenerationResult(
    val cards: List<GeneratedLearningCard>,
    val contextChapterCount: Int,
    val usedCharacters: Int
)
