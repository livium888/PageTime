package com.pagetime.app.data.learning

/**
 * Creates review cards without a network dependency, following Wozniak's 20 rules
 * of knowledge formulation. This fallback produces **multiple-choice questions only**
 * — one card per meaningfully different concept in the reading window.
 *
 * Quality rules (aligned with the Gemini prompt):
 * - Test central definitions, mechanisms, cause/effect, and meaningful contrasts.
 * - Distractors are drawn from the same sentence so they share the same domain.
 * - The correct answer must be among the options.
 * - rule #13 (Use mutually exclusive and confusing answers).
 */
object LocalRecallCardGenerator {

    private val STOP_WORDS = setOf(
        "about", "above", "after", "again", "also", "another", "any", "because",
        "before", "being", "between", "both", "came", "come", "could", "does",
        "done", "down", "during", "each", "every", "first", "from", "going",
        "good", "great", "have", "here", "into", "just", "know", "last", "left",
        "like", "long", "made", "make", "many", "might", "more", "most", "much",
        "must", "never", "next", "only", "other", "over", "part", "place",
        "point", "right", "said", "same", "shall", "should", "since", "small",
        "some", "something", "still", "such", "take", "than", "that", "their",
        "them", "then", "there", "these", "they", "thing", "think", "this",
        "those", "through", "time", "under", "upon", "very", "want", "well",
        "were", "what", "when", "where", "which", "while", "will", "with",
        "would", "your"
    )

    fun generate(context: LearningContext, limit: Int = 3): List<GeneratedLearningCard> {
        val normalizedText = context.recentText.replace(Regex("\\s+"), " ").trim()
        val sentences = normalizedText
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter { it.length in 40..400 && it.split(" ").size >= 8 && !isLikelyTrivial(it) }
            .ifEmpty {
                normalizedText
                    .chunked(280)
                    .map { it.trim() }
                    .filter { it.length >= 40 && it.split(" ").size >= 8 }
            }
            .distinctBy { it.lowercase() }
            .take(8)

        if (sentences.isEmpty()) return emptyList()

        // Score sentences by how well they support MCQ generation:
        // prefer definition / cause-effect / contrast sentences over plain ones.
        val scored = sentences.map { it to scoreSentence(it) }
            .sortedByDescending { it.second }

        val requested = limit.coerceIn(2, 5)
        val results = mutableListOf<GeneratedLearningCard>()
        val usedSentences = mutableSetOf<String>()

        for (cardIndex in 0 until requested) {
            val best = scored.firstOrNull { it.first !in usedSentences && contentWords(it.first).size >= 3 }
                ?: break
            results += generateMcq(best.first, context.chapterTitle)
            usedSentences += best.first
        }

        return results
    }

    // ── Sentence scoring ────────────────────────────────────────────────────

    /**
     * Returns a simple quality score: higher means the sentence is more likely
     * to yield a meaningful MCQ question.
     */
    private fun scoreSentence(sentence: String): Double {
        val lower = sentence.lowercase()
        var score = 0.0
        // Definition sentences are ideal for MCQ.
        if (Regex("\\b\\w+\\s+(is|are|was|were|means|refers to)\\b").containsMatchIn(lower))
            score += 4.0
        // Cause-effect.
        if (Regex("\\b(causes?|leads? to|results? in|produces?|enables?)\\b").containsMatchIn(lower))
            score += 3.0
        // Contrast / meaningful comparison.
        if (Regex("\\b(but|however|although|unlike|in contrast|whereas|yet|despite)\\b").containsMatchIn(lower))
            score += 3.0
        // Longer sentences have richer content words for distractors.
        score += (contentWords(sentence).size.coerceAtMost(6) * 0.5)
        return score
    }

    // ── MCQ generation ──────────────────────────────────────────────────────

    private fun contentWords(sentence: String): List<String> {
        return sentence.split(" ")
            .map { it.trim(',', '.', ';', ':', '!', '?', '\'', '"', '(', ')') }
            .filter {
                it.length in 5..20 &&
                    it.all { c -> c.isLetter() } &&
                    it.lowercase() !in STOP_WORDS &&
                    // Skip mid-sentence proper nouns (they make poor blanked-out answers).
                    !(it.length > 1 && it[0].isUpperCase() && it.drop(1).all { c -> c.isLowerCase() })
            }
            .distinctBy { it.lowercase() }
    }

    private fun generateMcq(sentence: String, chapterTitle: String): GeneratedLearningCard {
        val words = contentWords(sentence)
        val answer = words.maxByOrNull { it.length } ?: words.firstOrNull() ?: "concept"

        // Distractors: other content words from the same sentence, similar length.
        val distractors = words
            .filter { !it.equals(answer, ignoreCase = true) }
            .sortedBy { kotlin.math.abs(it.length - answer.length) }
            .take(3)
            .ifEmpty {
                words.filter { !it.equals(answer, ignoreCase = true) }.take(3)
            }

        // Fallback fillers if not enough in-sentence distractors.
        val padded = distractors.toMutableList()
        val fillers = listOf(
            "principle", "mechanism", "process", "factor", "element",
            "phenomenon", "structure", "hypothesis", "framework", "equilibrium"
        )
        while (padded.size < 3) {
            val filler = fillers.firstOrNull { f -> padded.none { it.equals(f, ignoreCase = true) } } ?: break
            padded += filler
        }

        val options = (listOf(answer) + padded).shuffled()

        // Blank out the answer term in the sentence.
        val blanked = sentence.replace(
            Regex("\\b${Regex.escape(answer)}\\b", RegexOption.IGNORE_CASE),
            "______"
        )

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = blanked,
            answer = answer,
            explanation = "From the text: $sentence",
            sourceQuote = sentence,
            confidence = 0.72f,
            cardType = "mcq",
            mcqOptions = options
        )
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private fun isLikelyTrivial(sentence: String): Boolean {
        val lower = sentence.lowercase()
        val meaningfulSignal = listOf(
            "because", "therefore", "means", "defined", "important", "principle",
            "causes", "leads", "depends", "however", "rather", "unlike", "must",
            " is ", " are ", " was ", " were ", " for example ", " such as "
        ).any { lower.contains(it) }
        val distinctWords = sentence.split(" ").map { it.lowercase() }.distinct().size
        return !meaningfulSignal && distinctWords < 10
    }
}
