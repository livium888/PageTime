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
    val sourceFormat: String,
    /** Topics already covered by existing cards in this book — used by the
     *  prompt to avoid generating duplicates. */
    val existingCardTopics: List<String> = emptyList()
)

data class AiGenerationResult(
    val cards: List<GeneratedLearningCard>,
    val contextChapterCount: Int,
    val usedCharacters: Int
)

/**
 * AI evaluation of a Feynman-style explanation.  Each score is 1–5.
 */
data class ExplanationEvaluation(
    val accuracy: Int,
    val completeness: Int,
    val clarity: Int,
    val whatTheyGotRight: String,
    val whatTheyMissed: String,
    val suggestedImprovement: String,
    val simplerVersion: String
) {
    val overallScore: Float get() = (accuracy + completeness + clarity) / 3f
}
