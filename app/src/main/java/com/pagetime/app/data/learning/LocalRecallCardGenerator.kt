package com.pagetime.app.data.learning

/**
 * Creates review cards without a network dependency, following Wozniak's 20 rules
 * of knowledge formulation. Produces a mix of three card types:
 *
 * 1. **Cloze deletions** — the sentence IS the prompt with the key word blanked out.
 *    Rule #1 (Minimize the burden of interpreting the item), Rule #5 (Formulate
 *    cloze deletions). The target word is chosen by semantic position and length,
 *    not blindly by length.
 *
 * 2. **Multiple choice** — tests recognition alongside recall. Distractors are
 *    drawn from the same sentence so they share the same domain. Rule #13
 *    (Use mutually exclusive and confusing answers).
 *
 * 3. **Standard Q&A** — the question pattern is matched to the sentence structure
 *    (definition, cause-effect, contrast, example) for a natural recall prompt.
 *    Rule #4 (Omit superfluous information), Rule #6 (Use keyword clues).
 */
object LocalRecallCardGenerator {

    /** Definition-signal words are poor cloze targets themselves ("is", "called", …). */
    private val SIGNAL_WORDS = setOf("is", "are", "was", "were", "means", "called", "named", "refers")

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

        // Score sentences by how well they support different card types.
        val scored = sentences.map { it to scoreSentence(it) }
            .sortedByDescending { it.second.clozeScore }

        val requested = limit.coerceIn(2, 3)
        val results = mutableListOf<GeneratedLearningCard>()
        val usedSentences = mutableSetOf<String>()

        // Card 1: Cloze — pick the sentence with the best cloze candidate.
        val bestCloze = scored
            .firstOrNull { it.first !in usedSentences && it.second.clozeScore > 0 }
            ?: scored.firstOrNull()
        if (bestCloze != null) {
            results += generateCloze(bestCloze.first, context.chapterTitle)
            usedSentences += bestCloze.first
        }

        // Card 2: Q&A — pick a sentence with a different structure (definition, cause, etc.)
        if (requested >= 2) {
            val bestQa = scored
                .firstOrNull { it.first !in usedSentences && it.second.sentencePattern != Pattern.PLAIN }
                ?: scored.firstOrNull { it.first !in usedSentences }
            if (bestQa != null) {
                results += generateQa(bestQa.first, context.chapterTitle, bestQa.second.sentencePattern)
                usedSentences += bestQa.first
            }
        }

        // Card 3: MCQ — only when there are enough content words for distractors.
        if (requested >= 3) {
            val bestMcq = scored
                .firstOrNull { it.first !in usedSentences && contentWords(it.first).size >= 3 }
            if (bestMcq != null) {
                results += generateMcq(bestMcq.first, context.chapterTitle)
            }
        }

        return results.take(requested)
    }

    // ── Sentence scoring ────────────────────────────────────────────────────

    private class SentenceScore(
        val clozeScore: Double,
        val sentencePattern: Pattern
    )

    private enum class Pattern { DEFINITION, CAUSE_EFFECT, CONTRAST, EXAMPLE, LIST, PLAIN }

    private fun scoreSentence(sentence: String): SentenceScore {
        val lower = sentence.lowercase()
        val pattern = detectPattern(lower)
        val clozeScore = clozeCandidates(sentence).firstOrNull()?.second ?: 0.0
        return SentenceScore(clozeScore, pattern)
    }

    private fun detectPattern(lower: String): Pattern {
        // Definition: "X is/are/was Y", "X means Y", "X is defined as Y"
        if (Regex("\\b\\w+\\s+(is|are|was|were|means|refers to|is defined as)\\b").containsMatchIn(lower))
            return Pattern.DEFINITION
        // Cause-effect: "X causes Y", "X leads to Y", "X results in Y", "because of X"
        if (Regex("\\b(causes?|leads? to|results? in|produces?|enables?)\\b").containsMatchIn(lower))
            return Pattern.CAUSE_EFFECT
        // Contrast: "but", "however", "although", "unlike", "in contrast"
        if (Regex("\\b(but|however|although|unlike|in contrast|whereas|yet|despite)\\b").containsMatchIn(lower))
            return Pattern.CONTRAST
        // Example: "for example", "for instance", "such as", "e.g."
        if (Regex("\\b(for example|for instance|such as|e\\.g\\.|including)\\b").containsMatchIn(lower))
            return Pattern.EXAMPLE
        // List: has semicolons or "first…second…third"
        if (lower.contains(';') || Regex("\\b(first|second|third|finally)\\b").containsMatchIn(lower))
            return Pattern.LIST
        return Pattern.PLAIN
    }

    // ── Cloze ───────────────────────────────────────────────────────────────

    private fun clozeCandidates(sentence: String): List<Pair<Int, Double>> {
        val words = sentence.split(" ")
        val n = words.size
        if (n < 4) return emptyList()

        return words.mapIndexedNotNull { index, raw ->
            val word = raw.trim(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')')
            if (word.length < 5 || !word.all { it.isLetter() }) return@mapIndexedNotNull null
            if (word.lowercase() in STOP_WORDS) return@mapIndexedNotNull null
            if (word.lowercase() in SIGNAL_WORDS) return@mapIndexedNotNull null
            // Avoid words at the very start or very end of the sentence.
            if (index < 2 || index >= n - 1) return@mapIndexedNotNull null
            // Avoid proper nouns mid-sentence (capitalized and not at start).
            if (index > 0 && word[0].isUpperCase() && words[index - 1].lastOrNull() != '.') return@mapIndexedNotNull null
            // Score: prefer 6-12 letter words, words after definition signals, middle position.
            var score = 0.0
            // Length bonus: 6-12 is the sweet spot.
            score += when {
                word.length in 6..12 -> 3.0
                word.length > 12 -> 2.0
                else -> 1.0
            }
            // Position bonus: middle third.
            val third = n / 3
            if (index in third until third * 2) score += 2.0
            // Definition signal: "X is Y" / "X is the Y" — Y is a good cloze target.
            if (index >= 2) {
                val prev = words[index - 1].lowercase().trim(',', '.', ';')
                val prevPrev = words[index - 2].lowercase().trim(',', '.', ';')
                if (prev in SIGNAL_WORDS || (prev in setOf("the", "a", "an") && prevPrev in SIGNAL_WORDS)) score += 4.0
            }
            index to score
        }.sortedByDescending { it.second }
    }

    private fun generateCloze(sentence: String, chapterTitle: String): GeneratedLearningCard {
        val words = sentence.split(" ")
        val bestIndex = (clozeCandidates(sentence).firstOrNull()?.first ?: (words.size / 2))
            .coerceIn(0, words.lastIndex)
        val answer = words.getOrElse(bestIndex) { "unknown" }

        val clozeText = words.toMutableList().apply {
            this[bestIndex] = "{{c1::$answer}}"
        }.joinToString(" ")

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = clozeText,
            answer = answer,
            explanation = "From the text: $sentence",
            sourceQuote = sentence,
            confidence = 0.80f,
            cardType = "cloze"
        )
    }

    // ── MCQ ─────────────────────────────────────────────────────────────────

    private fun contentWords(sentence: String): List<String> {
        return sentence.split(" ")
            .map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')') }
            .filter {
                it.length in 5..20 &&
                    it.all { c -> c.isLetter() } &&
                    it.lowercase() !in STOP_WORDS &&
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
                // Fallback: any content words from the sentence.
                words.filter { !it.equals(answer, ignoreCase = true) }.take(3)
            }

        // If still not enough, pad with generic but plausible-sounding terms.
        val padded = distractors.toMutableList()
        val fillers = listOf("principle", "mechanism", "process", "factor", "element", "phenomenon")
        while (padded.size < 3) {
            val filler = fillers.firstOrNull { f -> padded.none { it.equals(f, ignoreCase = true) } } ?: break
            padded += filler
        }

        val options = (listOf(answer) + padded).shuffled()

        val clozeSentence = sentence.replace(
            Regex("\\b${Regex.escape(answer)}\\b", RegexOption.IGNORE_CASE),
            "______"
        )

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = clozeSentence,
            answer = answer,
            explanation = "From the text: $sentence",
            sourceQuote = sentence,
            confidence = 0.75f,
            cardType = "mcq",
            mcqOptions = options
        )
    }

    // ── Q&A ─────────────────────────────────────────────────────────────────

    private fun generateQa(sentence: String, chapterTitle: String, pattern: Pattern): GeneratedLearningCard {
        val (question, answer) = when (pattern) {
            Pattern.DEFINITION -> {
                val subject = extractDefinitionSubject(sentence)
                "What is $subject?" to extractDefinitionBody(sentence)
            }
            Pattern.CAUSE_EFFECT -> {
                val subject = extractCauseSubject(sentence)
                "What effect does $subject have?" to extractCauseBody(sentence)
            }
            Pattern.CONTRAST -> {
                val subject = extractContrastSubject(sentence)
                "What contrasts with $subject?" to extractContrastBody(sentence)
            }
            Pattern.EXAMPLE -> {
                val subject = extractExampleSubject(sentence)
                "What is an example of $subject?" to extractExampleBody(sentence)
            }
            Pattern.LIST -> {
                val keyPhrase = extractKeyPhrase(sentence)
                "What are the elements of $keyPhrase?" to sentence
            }
            Pattern.PLAIN -> {
                val keyPhrase = extractKeyPhrase(sentence)
                "What is the significance of $keyPhrase?" to sentence
            }
        }

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = question,
            answer = answer,
            explanation = "From the passage: $sentence",
            sourceQuote = sentence,
            confidence = 0.72f,
            cardType = "qa"
        )
    }

    private fun extractDefinitionSubject(sentence: String): String {
        // "X is Y" → take X (words before "is/are/was")
        val match = Regex("^(.+?)\\s+(?:is|are|was|were|means|refers to)\\b", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)
            ?.split(" ")?.takeLast(3)?.joinToString(" ")
            ?.trimEnd(',', '.', ';')
            ?: extractKeyPhrase(sentence)
    }

    private fun extractDefinitionBody(sentence: String): String {
        val match = Regex("\\b(?:is|are|was|were|means|refers to)\\s+(.+?)(?:[.;]|$)", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)?.trim() ?: sentence
    }

    private fun extractCauseSubject(sentence: String): String {
        val match = Regex("^(.+?)\\s+(?:causes?|leads? to|results? in|produces?|enables?)\\b", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)
            ?.split(" ")?.takeLast(3)?.joinToString(" ")
            ?.trimEnd(',', '.', ';')
            ?: extractKeyPhrase(sentence)
    }

    private fun extractCauseBody(sentence: String): String {
        val match = Regex("\\b(?:causes?|leads? to|results? in|produces?|enables?)\\s+(.+?)(?:[.;]|$)", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)?.trim() ?: sentence
    }

    private fun extractContrastSubject(sentence: String): String {
        val match = Regex("^(.+?)\\s+(?:but|however|although|unlike|whereas|yet)\\b", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)
            ?.split(" ")?.takeLast(3)?.joinToString(" ")
            ?.trimEnd(',', '.', ';')
            ?: extractKeyPhrase(sentence)
    }

    private fun extractContrastBody(sentence: String): String {
        val match = Regex("\\b(?:but|however|although|unlike|whereas|yet)\\s+(.+?)(?:[.;]|$)", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)?.trim() ?: sentence
    }

    private fun extractExampleSubject(sentence: String): String {
        val match = Regex("^(.+?)\\s+(?:for example|for instance|such as|including)\\b", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)
            ?.split(" ")?.takeLast(3)?.joinToString(" ")
            ?.trimEnd(',', '.', ';')
            ?: extractKeyPhrase(sentence)
    }

    private fun extractExampleBody(sentence: String): String {
        val match = Regex("\\b(?:for example|for instance|such as|including)\\s+(.+?)(?:[.;]|$)", RegexOption.IGNORE_CASE)
            .find(sentence)
        return match?.groupValues?.get(1)?.trim() ?: sentence
    }

    private fun extractKeyPhrase(sentence: String): String {
        val clauseBreak = sentence.indexOfFirst { it in ";:—" }
        val effective = if (clauseBreak > 10) sentence.substring(0, clauseBreak) else sentence

        return effective
            .split(" ")
            .take(8)
            .joinToString(" ")
            .trimEnd(',', '.', ';', ':', '!', '?')
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
