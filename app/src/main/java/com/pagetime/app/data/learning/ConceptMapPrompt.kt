package com.pagetime.app.data.learning

import com.pagetime.app.data.JsonInReply
import org.json.JSONArray

/**
 * A concept map from the reader's own AI app: the prompt to take there, and a
 * reader for the reply.
 *
 * THE REPLY IS CHECKED, NOT TRUSTED
 *
 * Every concept and every relationship must quote the text that was sent,
 * word for word. A model asked for a supporting quote will produce a plausible
 * paraphrase of one, and a map built on paraphrases is a map of what the model
 * believes, not of what the book says. Anything whose quote is not in the
 * source is dropped; so is a relationship between concepts that did not
 * themselves survive.
 */
object ConceptMapPrompt {

    const val MAX_CONCEPTS = 12
    const val MAX_RELATIONSHIPS = 24
    const val MIN_CONFIDENCE = 0.55f

    fun build(context: LearningContext, existingConcepts: List<String>): String = """
        |Build a true concept map from the supplied reading, not a hierarchical mind map.
        |Return important concepts and meaningful directed relationships between them.
        |Use ONLY the supplied SOURCE TEXT: never introduce ideas, names, or events that
        |do not appear in it. If SOURCE TEXT contains no substantial teachable idea,
        |return an empty concepts array instead of inventing one.
        |Relationship labels must explain the meaning: causes, supports, contrasts with,
        |depends on, example of, defines, leads to, or related to. Never invent facts.
        |Reuse an existing concept label when the new passage refers to the same idea.
        |Every sourceQuote must be copied exactly from SOURCE TEXT.
        |Existing concepts: ${existingConcepts.take(80).joinToString(", ").ifBlank { "none" }}
        |
        |Reply with ONLY this JSON, in a code block, and nothing else:
        |{
        |  "concepts": [
        |    {"label": "...", "description": "...", "type": "idea", "sourceQuote": "...", "confidence": 0.0}
        |  ],
        |  "relationships": [
        |    {"sourceLabel": "...", "targetLabel": "...", "relationType": "...",
        |     "explanation": "...", "sourceQuote": "...", "confidence": 0.0}
        |  ]
        |}
        |confidence is a number from 0 to 1.
        |
        |BOOK: ${context.bookTitle}
        |CHAPTER: ${context.chapterTitle}
        |SOURCE TEXT:
        |${context.recentText}
    """.trimMargin()

    /**
     * The map in [reply], checked against [sourceText]. Null when the reply
     * holds no JSON object at all, which means the wrong thing was pasted.
     */
    fun parse(reply: String, sourceText: String): ConceptMapGenerationResult? {
        val root = JsonInReply.firstObject(reply) ?: return null
        if (!root.has("concepts") && !root.has("relationships")) return null
        val source = normalize(sourceText)
        val concepts = mutableListOf<GeneratedConcept>()
        val labels = mutableSetOf<String>()
        val conceptArray = root.optJSONArray("concepts") ?: JSONArray()
        for (index in 0 until conceptArray.length().coerceAtMost(MAX_CONCEPTS)) {
            val item = conceptArray.optJSONObject(index) ?: continue
            val concept = GeneratedConcept(
                label = item.optString("label").trim(),
                description = item.optString("description").trim(),
                type = item.optString("type").trim().ifBlank { "idea" },
                sourceQuote = item.optString("sourceQuote").trim(),
                confidence = item.optDouble("confidence", 0.0).toFloat()
            )
            val key = concept.label.lowercase()
            if (concept.label.length in 2..100 && concept.description.length in 8..500 &&
                concept.sourceQuote.length in 12..500 && concept.confidence >= MIN_CONFIDENCE &&
                source.contains(normalize(concept.sourceQuote)) && labels.add(key)
            ) concepts += concept
        }
        val validLabels = concepts.map { it.label.lowercase() }.toSet()
        val relationships = mutableListOf<GeneratedConceptRelationship>()
        val relationshipArray = root.optJSONArray("relationships") ?: JSONArray()
        for (index in 0 until relationshipArray.length().coerceAtMost(MAX_RELATIONSHIPS)) {
            val item = relationshipArray.optJSONObject(index) ?: continue
            val relationship = GeneratedConceptRelationship(
                sourceLabel = item.optString("sourceLabel").trim(),
                targetLabel = item.optString("targetLabel").trim(),
                relationType = item.optString("relationType").trim().ifBlank { "related to" },
                explanation = item.optString("explanation").trim(),
                sourceQuote = item.optString("sourceQuote").trim(),
                confidence = item.optDouble("confidence", 0.0).toFloat()
            )
            if (relationship.sourceLabel.lowercase() in validLabels &&
                relationship.targetLabel.lowercase() in validLabels &&
                relationship.sourceLabel.lowercase() != relationship.targetLabel.lowercase() &&
                relationship.explanation.length >= 8 && relationship.sourceQuote.length >= 12 &&
                relationship.confidence >= MIN_CONFIDENCE &&
                source.contains(normalize(relationship.sourceQuote))
            ) relationships += relationship
        }
        return ConceptMapGenerationResult(concepts, relationships)
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().lowercase()
}
