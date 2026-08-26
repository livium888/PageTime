package com.pagetime.app.data.learning

/**
 * Local concept map generator that extracts ideas and their relationships from
 * reading text without any network dependency.
 *
 * Improvements over the earlier adjacent-sentence approach:
 * - **Keyword extraction**: concepts are labelled from the most meaningful words
 *   in each sentence (frequency + position + proper-noun cues), not the longest word.
 * - **Relationship typing**: context clues ("is", "causes", "however", "for example")
 *   determine the edge label (defines, causes, contrasts with, example of).
 * - **Cross-sentence linking**: sentences that share keywords are connected even when
 *   they are not adjacent, producing a real concept map rather than a linear chain.
 */
object LocalConceptMapGenerator {

    private val STOP_WORDS = setOf(
        "about", "above", "after", "again", "also", "another", "any", "because",
        "before", "being", "between", "both", "came", "come", "could", "does",
        "done", "down", "during", "each", "every", "first", "from", "going",
        "good", "great", "have", "here", "into", "just", "know", "last", "left",
        "like", "long", "made", "make", "many", "might", "more", "most", "much",
        "must", "never", "next", "only", "other", "over", "part", "place",
        "point", "right", "said", "same", "shall", "should", "since", "small",
        "some", "still", "such", "take", "than", "that", "their", "them", "then",
        "there", "these", "they", "thing", "think", "this", "those", "through",
        "time", "under", "upon", "very", "want", "well", "were", "what", "when",
        "where", "which", "while", "will", "with", "would", "your"
    )

    fun generate(context: LearningContext, limit: Int = 8): ConceptMapGenerationResult {
        val normalizedText = context.recentText
            .replace(Regex("\\s+"), " ")
            .trim()
        val sentences = normalizedText
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter { it.length in 55..350 && it.split(" ").size >= 9 }
            .distinctBy { it.lowercase() }
            .take(limit.coerceAtLeast(2))

        if (sentences.size < 2) {
            // Not enough material for a meaningful map.
            return ConceptMapGenerationResult(
                concepts = sentences.mapIndexed { index, sentence ->
                    buildConcept(sentence, index)
                },
                relationships = emptyList()
            )
        }

        // Build concept for each sentence.
        val concepts = sentences.mapIndexed { index, sentence ->
            buildConcept(sentence, index)
        }

        // Build relationships: both adjacent AND cross-sentence (shared keywords).
        val relationships = buildRelationships(concepts, sentences)

        return ConceptMapGenerationResult(concepts, relationships)
    }

    // ── Concept building ────────────────────────────────────────────────────

    private fun buildConcept(sentence: String, index: Int): GeneratedConcept {
        val keywords = extractKeywords(sentence)
        val label = buildLabel(keywords, sentence, index)
        val type = detectConceptType(sentence)

        return GeneratedConcept(
            label = label,
            description = sentence,
            type = type,
            sourceQuote = sentence,
            confidence = conceptConfidence(sentence, keywords)
        )
    }

    private fun detectConceptType(sentence: String): String {
        val lower = sentence.lowercase()
        if (Regex("\\b\\w+\\s+(is|are|was|were)\\b").containsMatchIn(lower)) return "definition"
        if (Regex("\\b(causes?|leads? to|results? in|produces?)\\b").containsMatchIn(lower)) return "causation"
        if (Regex("\\b(but|however|although|unlike|whereas)\\b").containsMatchIn(lower)) return "contrast"
        if (Regex("\\b(for example|for instance|such as|including)\\b").containsMatchIn(lower)) return "example"
        if (Regex("\\b(must|should|need to|required|necessary|essential)\\b").containsMatchIn(lower)) return "principle"
        return "idea"
    }

    // ── Keyword extraction ──────────────────────────────────────────────────

    /**
     * Extracts meaningful keywords from a sentence using frequency, position,
     * and capitalization cues. Returns a deduplicated list sorted by relevance.
     */
    private fun extractKeywords(sentence: String): List<String> {
        val words = sentence.split(" ")
            .map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')', '—', '-') }
            .filter { it.isNotEmpty() }

        data class Keyword(val word: String, val score: Double)

        val candidates = words.mapIndexedNotNull { index, raw ->
            val word = raw.trim()
            if (word.length < 4 || !word.all { it.isLetter() || it == '-' }) return@mapIndexedNotNull null
            if (word.lowercase() in STOP_WORDS) return@mapIndexedNotNull null

            var score = 0.0
            // Length: longer words are more specific.
            score += when {
                word.length >= 8 -> 3.0
                word.length >= 6 -> 2.0
                else -> 1.0
            }
            // Position: first few words are usually the subject.
            if (index < 4) score += 3.0 - index
            // Proper noun (capitalized mid-sentence): likely a named concept.
            if (index > 0 && word[0].isUpperCase() && words.getOrNull(index - 1)?.lastOrNull() != '.') {
                score += 4.0
            }
            // Words right before definition signals are likely the concept being defined.
            if (index < words.size - 2) {
                val next = words[index + 1].lowercase().trim(',', '.', ';')
                if (next in setOf("is", "are", "was", "were", "means")) score += 3.0
            }

            Keyword(word, score)
        }

        return candidates
            .sortedByDescending { it.score }
            .take(4)
            .map { it.word }
    }

    private fun buildLabel(keywords: List<String>, sentence: String, index: Int): String {
        if (keywords.isEmpty()) return "Idea ${index + 1}"
        // Use top 1-2 keywords as the concept label.
        val primary = keywords[0].replaceFirstChar { it.uppercase() }
        return if (keywords.size >= 2 && keywords[1].lowercase() != keywords[0].lowercase()) {
            val secondary = keywords[1].replaceFirstChar { it.uppercase() }
            "$primary $secondary"
        } else {
            primary
        }
    }

    private fun conceptConfidence(sentence: String, keywords: List<String>): Float {
        var conf = 0.55f
        // More keywords = richer concept.
        conf += keywords.size * 0.03f
        // Definition/cause sentences are more reliable.
        val lower = sentence.lowercase()
        if (Regex("\\b(is|are|was|were|means)\\b").containsMatchIn(lower)) conf += 0.08f
        if (Regex("\\b(causes?|leads? to|results? in)\\b").containsMatchIn(lower)) conf += 0.06f
        return conf.coerceAtMost(0.95f)
    }

    // ── Relationship building ───────────────────────────────────────────────

    private fun buildRelationships(
        concepts: List<GeneratedConcept>,
        sentences: List<String>
    ): List<GeneratedConceptRelationship> {
        val relationships = mutableListOf<GeneratedConceptRelationship>()

        // 1. Adjacent-sentence relationships (with typed edges).
        for (i in 0 until sentences.size - 1) {
            val source = concepts[i]
            val target = concepts[i + 1]
            val rel = inferRelationship(sentences[i], sentences[i + 1], source, target)
            relationships += rel
        }

        // 2. Cross-sentence relationships (shared keywords, non-adjacent).
        val conceptKeywords = sentences.map { extractKeywords(it).map(String::lowercase).toSet() }
        for (i in sentences.indices) {
            for (j in i + 2 until sentences.size) {
                val shared = conceptKeywords[i].intersect(conceptKeywords[j])
                if (shared.size >= 2) {
                    val source = concepts[i]
                    val target = concepts[j]
                    // Don't duplicate an existing relationship.
                    val alreadyLinked = relationships.any {
                        (it.sourceLabel == source.label && it.targetLabel == target.label) ||
                            (it.sourceLabel == target.label && it.targetLabel == source.label)
                    }
                    if (!alreadyLinked) {
                        val sharedTerms = shared.take(2).joinToString(" and ")
                        relationships += GeneratedConceptRelationship(
                            sourceLabel = source.label,
                            targetLabel = target.label,
                            relationType = "related to",
                            explanation = "Both discuss $sharedTerms.",
                            sourceQuote = source.sourceQuote,
                            confidence = (0.55f + shared.size * 0.05f).coerceAtMost(0.90f)
                        )
                    }
                }
            }
        }

        // 3. Limit to the strongest relationships.
        return relationships
            .sortedByDescending { it.confidence }
            .take(24)
    }

    private fun inferRelationship(
        sentenceA: String,
        sentenceB: String,
        source: GeneratedConcept,
        target: GeneratedConcept
    ): GeneratedConceptRelationship {
        val lowerA = sentenceA.lowercase()
        val lowerB = sentenceB.lowercase()

        // Check sentence B for relationship signals.
        return when {
            // "therefore / thus / consequently" in B → A leads to B
            Regex("\\b(therefore|thus|consequently|as a result|hence)\\b").containsMatchIn(lowerB) ->
                relationship(source, target, "leads to",
                    "The text moves from ${source.label} to ${target.label} as a consequence.",
                    source.sourceQuote, 0.65f)

            // "however / but / although" in B → A contrasts with B
            Regex("\\b(however|but|although|unlike|whereas|yet|despite)\\b").containsMatchIn(lowerB) ->
                relationship(source, target, "contrasts with",
                    "The text contrasts ${source.label} with ${target.label}.",
                    source.sourceQuote, 0.65f)

            // "for example / for instance / such as" in B → A is exemplified by B
            Regex("\\b(for example|for instance|such as|including|e\\.g\\.)\\b").containsMatchIn(lowerB) ->
                relationship(source, target, "exemplified by",
                    "The text gives ${target.label} as an instance of ${source.label}.",
                    source.sourceQuote, 0.68f)

            // "because / since / due to" in B → B depends on A
            Regex("\\b(because|since|due to|owing to|thanks to)\\b").containsMatchIn(lowerB) ->
                relationship(source, target, "supports",
                    "${source.label} provides support for ${target.label}.",
                    source.sourceQuote, 0.62f)

            // Default: related to
            else -> relationship(source, target, "related to",
                "These ideas appear in close reading context.",
                source.sourceQuote, 0.55f)
        }
    }

    private fun relationship(
        source: GeneratedConcept,
        target: GeneratedConcept,
        type: String,
        explanation: String,
        quote: String,
        confidence: Float
    ): GeneratedConceptRelationship {
        return GeneratedConceptRelationship(
            sourceLabel = source.label,
            targetLabel = target.label,
            relationType = type,
            explanation = explanation,
            sourceQuote = quote,
            confidence = confidence
        )
    }
}
