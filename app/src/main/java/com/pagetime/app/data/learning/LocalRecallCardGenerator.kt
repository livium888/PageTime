package com.pagetime.app.data.learning

/**
 * Creates lightweight active-recall cards without a network dependency. The card
 * blanks a meaningful word from a real sentence, so the answer is always grounded
 * in the passage the reader just completed.
 */
object LocalRecallCardGenerator {
    fun generate(context: LearningContext, limit: Int = 2): List<GeneratedLearningCard> {
        val normalizedText = context.recentText.replace(Regex("\\s+"), " ").trim()
        val sentences = normalizedText
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter { sentence ->
                sentence.length in 50..360 && sentence.split(" ").size >= 9
            }
            .ifEmpty {
                normalizedText
                    .chunked(240)
                    .map { it.trim() }
                    .filter { chunk -> chunk.length >= 50 && chunk.split(" ").size >= 9 }
            }
            .distinctBy { it.lowercase() }
            .take(limit.coerceAtLeast(1))

        return sentences.mapNotNull { sentence ->
            val words = sentence.split(" ")
            val candidate = words
                .mapIndexed { index, word -> index to word.trim(',', '.', ';', ':', '!', '?', '"', '\'') }
                .filter { (index, word) ->
                    index in 2 until (words.lastIndex - 1) &&
                        word.length >= 6 &&
                        word.all { it.isLetter() }
                }
                .maxByOrNull { (_, word) -> word.length }
                ?: return@mapNotNull null

            val (index, answer) = candidate
            val masked = words.toMutableList().apply { this[index] = "_____" }.joinToString(" ")
            GeneratedLearningCard(
                topic = context.chapterTitle,
                question = "Complete this idea from ${context.chapterTitle}: $masked",
                answer = answer,
                explanation = "Compare your answer with the original sentence: $sentence",
                sourceQuote = sentence,
                confidence = 0.75f
            )
        }
    }
}
