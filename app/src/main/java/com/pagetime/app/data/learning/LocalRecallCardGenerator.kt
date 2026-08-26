package com.pagetime.app.data.learning

/**
 * Creates review cards without a network dependency, following Wozniak's 20 rules
 * of knowledge formulation. Produces a mix of three card types:
 *
 * 1. **Cloze deletions** — the sentence IS the prompt with the key word blanked out.
 *    Rule #1 (Minimize the burden of interpreting the item), Rule #2 (Picture
 *    comprehension), Rule #5 (Formulate cloze deletions).
 *
 * 2. **Multiple choice** — tests recognition alongside recall. Rule #13
 *    (Use mutually exclusive and confusing answers).
 *
 * 3. **Standard Q&A** — active recall of the meaning behind a passage.
 *    Rule #4 (Omit superfluous information), Rule #6 (Use keyword clues).
 */
object LocalRecallCardGenerator {

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

        // Collect a pool of meaningful words from the text for distractor generation.
        val wordPool = normalizedText
            .split(Regex("\\s+"))
            .map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'') }
            .filter { it.length >= 5 && it.all { c -> c.isLetter() } }
            .distinctBy { it.lowercase() }
            .toList()

        val requested = limit.coerceIn(2, 3)
        val results = mutableListOf<GeneratedLearningCard>()
        // Keep checkpoints small: one cloze, one direct recall, and an MCQ only
        // when the passage contains enough meaningful alternatives.
        results += generateCloze(sentences[0], context.chapterTitle)
        if (requested >= 2 && sentences.size >= 2) {
            results += generateQa(sentences[1], context.chapterTitle)
        }
        if (requested >= 3 && sentences.size >= 3 && wordPool.size >= 4) {
            results += generateMcq(sentences[2], wordPool, context.chapterTitle)
        }
        return results.take(requested)
    }

    private fun isLikelyTrivial(sentence: String): Boolean {
        val lower = sentence.lowercase()
        val meaningfulSignal = listOf(
            "because", "therefore", "means", "defined", "important", "principle",
            "causes", "leads", "depends", "however", "rather", "unlike", "must",
            " is ", " are ", " was ", " were "
        ).any { lower.contains(it) }
        val distinctWords = sentence.split(" ").map { it.lowercase() }.distinct().size
        return !meaningfulSignal && distinctWords < 10
    }

    /**
     * Cloze deletion: present the sentence with one important word blanked out.
     * Wozniak Rule #5: "Formulate cloze deletions."
     * The prompt IS the sentence with the answer removed — minimal interpretation burden.
     */
    private fun generateCloze(sentence: String, chapterTitle: String): GeneratedLearningCard {
        val words = sentence.split(" ")
        val candidate = words
            .mapIndexed { index, word -> index to word.trim(',', '.', ';', ':', '!', '?', '"', '\'') }
            .filter { (index, word) ->
                index in 2 until (words.lastIndex - 1) &&
                    word.length >= 5 && word.all { it.isLetter() }
            }
            .maxByOrNull { (_, word) -> word.length }
            ?: words.firstOrNull { it.length >= 5 }?.let { words.indexOf(it) to it }
            ?: (words.size / 2 to words.getOrElse(words.size / 2) { "unknown" })

        val (index, answer) = candidate
        val clozeText = words.toMutableList().apply { this[index] = "{{c1::$answer}}" }.joinToString(" ")

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = clozeText,
            answer = answer,
            explanation = "The complete sentence from the text: $sentence",
            sourceQuote = sentence,
            confidence = 0.80f,
            cardType = "cloze"
        )
    }

    /**
     * Multiple choice: present the correct answer alongside plausible distractors.
     * Wozniak Rule #13: "Use mutually exclusive and confusing answers."
     * Rule #14: "Use images, acronyms, and mnemonics to assist in cloze deletion."
     */
    private fun generateMcq(sentence: String, wordPool: List<String>, chapterTitle: String): GeneratedLearningCard {
        val words = sentence.split(" ")
        val answer = words
            .filter { it.length >= 5 && it.all { c -> c.isLetter() } }
            .distinct()
            .maxByOrNull { it.length }
            ?: words.getOrElse(3) { "this" }

        // Generate 3 distractors from the word pool (not the correct answer).
        val distractors = wordPool
            .filter { !it.equals(answer, ignoreCase = true) && it.length in (answer.length - 3)..(answer.length + 3) }
            .shuffled()
            .take(3)
            .ifEmpty {
                // Fallback: grab any words from the text
                wordPool.filter { !it.equals(answer, ignoreCase = true) }.shuffled().take(3)
            }

        val options = (listOf(answer) + distractors).shuffled()

        // Build a cloze prompt showing the sentence with the answer blanked.
        val clozeSentence = sentence.replace(
            Regex("\\b${Regex.escape(answer)}\\b", RegexOption.IGNORE_CASE),
            "______"
        )

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = clozeSentence,
            answer = answer,
            explanation = "The original sentence: $sentence",
            sourceQuote = sentence,
            confidence = 0.75f,
            cardType = "mcq",
            mcqOptions = options
        )
    }

    /**
     * Standard Q&A: extracts a meaningful concept or relationship from the sentence.
     * Wozniak Rule #3: "Learn selectively — choose information worth learning."
     * Rule #8: "Delete redundant, trivial, or well-known material."
     * Rule #10: "Use mnemonics sparingly and only for the most important facts."
     */
    private fun generateQa(sentence: String, chapterTitle: String): GeneratedLearningCard {
        val keyPhrase = extractKeyPhrase(sentence)
        val question = "What does the text say about: $keyPhrase?"
        val answer = sentence.split(Regex("(?<=[.!?])\\s+")).firstOrNull() ?: sentence

        return GeneratedLearningCard(
            topic = chapterTitle,
            question = question,
            answer = answer,
            explanation = "This comes directly from the passage: $sentence",
            sourceQuote = sentence,
            confidence = 0.70f,
            cardType = "qa"
        )
    }

    /**
     * Extract the most meaningful phrase from a sentence — prefer the subject and
     * its key relationship or action. This avoids trivial questions.
     * Wozniak Rule #4: "Omit superfluous information."
     */
    private fun extractKeyPhrase(sentence: String): String {
        val words = sentence.split(" ")
        // Take the first 3-8 words up to a comma or semicolon, or just the first clause.
        val clauseBreak = sentence.indexOfFirst { it in ";:—" }
        val effective = if (clauseBreak > 10) sentence.substring(0, clauseBreak) else sentence

        return effective
            .split(" ")
            .take(8)
            .joinToString(" ")
            .trimEnd(',', '.', ';', ':', '!', '?')
    }
}
