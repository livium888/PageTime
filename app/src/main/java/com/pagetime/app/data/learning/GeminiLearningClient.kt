package com.pagetime.app.data.learning

import com.pagetime.app.BuildConfig
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GeminiLearningClient(
    private val settingsRepository: SettingsRepository,
    private val endpointBase: String = "https://generativelanguage.googleapis.com/v1beta"
) {
    private val buildTimeApiKey: String = BuildConfig.GEMINI_API_KEY

    val isConfigured: Boolean
        get() = currentApiKey().isNotBlank()

    fun currentModel(): String = settingsRepository.geminiModel()

    fun hasUserKey(): Boolean = settingsRepository.geminiApiKey() != null

    fun saveUserApiKey(value: String) {
        settingsRepository.setGeminiApiKey(value)
    }

    fun clearUserApiKey() {
        settingsRepository.clearGeminiApiKey()
    }

    fun setModel(model: String) {
        settingsRepository.setGeminiModel(model)
    }

    suspend fun listGenerationModels(): List<GeminiModel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<GeminiModel>()
        var pageToken: String? = null
        repeat(MAX_MODEL_PAGES) {
                val url = buildString {
                append(endpointBase)
                append("/models")
                pageToken?.let {
                    append("?pageToken=")
                    append(URLEncoder.encode(it, Charsets.UTF_8.name()))
                }
            }
            val raw = executeWithRetry(
                Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", currentApiKey())
                    .get()
                    .build()
            )
            val root = JSONObject(raw)
            all += parseModels(root)
            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
            if (pageToken == null) return@withContext all.distinctBy { it.id }
        }
        all.distinctBy { it.id }
    }

    suspend fun testConnection(): GeminiConnectionResult {
        val models = listGenerationModels()
        val selected = chooseModel(models, currentModel()).id
        setModel(selected)
        return GeminiConnectionResult(models, selected)
    }

    suspend fun generateConceptMap(
        context: LearningContext,
        existingConcepts: List<String>
    ): ConceptMapGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = currentApiKey()
        check(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        val conceptSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("label", JSONObject().put("type", "STRING"))
                .put("description", JSONObject().put("type", "STRING"))
                .put("type", JSONObject().put("type", "STRING"))
                .put("sourceQuote", JSONObject().put("type", "STRING"))
                .put("confidence", JSONObject().put("type", "NUMBER")))
            .put("required", JSONArray(listOf("label", "description", "type", "sourceQuote", "confidence")))
        val relationshipSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("sourceLabel", JSONObject().put("type", "STRING"))
                .put("targetLabel", JSONObject().put("type", "STRING"))
                .put("relationType", JSONObject().put("type", "STRING"))
                .put("explanation", JSONObject().put("type", "STRING"))
                .put("sourceQuote", JSONObject().put("type", "STRING"))
                .put("confidence", JSONObject().put("type", "NUMBER")))
            .put("required", JSONArray(listOf("sourceLabel", "targetLabel", "relationType", "explanation", "sourceQuote", "confidence")))
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("concepts", JSONObject().put("type", "ARRAY").put("items", conceptSchema))
                .put("relationships", JSONObject().put("type", "ARRAY").put("items", relationshipSchema)))
            .put("required", JSONArray(listOf("concepts", "relationships")))
        val prompt = """
            Build a true concept map from the supplied reading, not a hierarchical mind map.
            Return important concepts and meaningful directed relationships between them.
            Relationship labels must explain the meaning: causes, supports, contrasts with,
            depends on, example of, defines, leads to, or related to. Never invent facts.
            Reuse an existing concept label when the new passage refers to the same idea.
            Every sourceQuote must be copied exactly from SOURCE TEXT.
            Existing concepts: ${existingConcepts.take(80).joinToString(", ").ifBlank { "none" }}
            Return only the requested JSON.

            BOOK: ${context.bookTitle}
            CHAPTER: ${context.chapterTitle}
            SOURCE TEXT:
            ${context.recentText}
        """.trimIndent()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))
            .toString()
        val request = Request.Builder()
            .url("$endpointBase/models/${currentModel()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        parseConceptMapResponse(executeWithRetry(request), context)
    }

    suspend fun generate(context: LearningContext): AiGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = currentApiKey()
        check(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        val prompt = buildPrompt(context)
        val cardFields = JSONObject()
            .put("topic", JSONObject().put("type", "STRING"))
            .put("question", JSONObject().put("type", "STRING"))
            .put("answer", JSONObject().put("type", "STRING"))
            .put("explanation", JSONObject().put("type", "STRING"))
            .put("sourceQuote", JSONObject().put("type", "STRING"))
            .put("confidence", JSONObject().put("type", "NUMBER"))
        val cardItemSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", cardFields)
            .put("required", JSONArray(listOf("topic", "question", "answer", "explanation", "sourceQuote", "confidence")))
        val cardsSchema = JSONObject()
            .put("type", "ARRAY")
            .put("items", cardItemSchema)
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("cards", cardsSchema))
            .put("required", JSONArray(listOf("cards")))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))
            .toString()
        val request = Request.Builder()
            .url("$endpointBase/models/${currentModel()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        parseResponse(executeWithRetry(request), context)
    }

    private suspend fun executeWithRetry(request: Request): String {
        check(currentApiKey().isNotBlank()) { "Gemini API key is not configured" }
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val response = AppHttp.newClient(callTimeoutSeconds = 60L).newCall(request).execute()
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.isSuccessful) return body
                    if (it.code !in RETRYABLE_CODES) {
                        error("Gemini request failed: HTTP ${it.code}")
                    }
                    lastError = IllegalStateException("Gemini temporarily unavailable: HTTP ${it.code}")
                }
            } catch (error: Throwable) {
                lastError = error
            }
            if (attempt < 2) delay(700L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("Gemini request failed")
    }

    private fun parseConceptMapResponse(raw: String, context: LearningContext): ConceptMapGenerationResult {
        val text = JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        val root = JSONObject(text)
        val source = normalize(context.recentText)
        val concepts = mutableListOf<GeneratedConcept>()
        val labels = mutableSetOf<String>()
        val conceptArray = root.optJSONArray("concepts") ?: JSONArray()
        for (index in 0 until conceptArray.length().coerceAtMost(12)) {
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
                concept.sourceQuote.length in 12..500 && concept.confidence >= 0.55f &&
                source.contains(normalize(concept.sourceQuote)) && labels.add(key)
            ) concepts += concept
        }
        val validLabels = concepts.map { it.label.lowercase() }.toSet()
        val relationships = mutableListOf<GeneratedConceptRelationship>()
        val relationshipArray = root.optJSONArray("relationships") ?: JSONArray()
        for (index in 0 until relationshipArray.length().coerceAtMost(24)) {
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
                relationship.confidence >= 0.55f && source.contains(normalize(relationship.sourceQuote))
            ) relationships += relationship
        }
        return ConceptMapGenerationResult(concepts, relationships)
    }

    private fun parseModels(root: JSONObject): List<GeminiModel> {
        val models = root.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (i in 0 until models.length()) {
                val item = models.getJSONObject(i)
                val methods = buildList {
                    val values = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    for (j in 0 until values.length()) add(values.getString(j))
                }
                if ("generateContent" in methods) {
                    add(
                        GeminiModel(
                            name = item.optString("name"),
                            displayName = item.optString("displayName").ifBlank { item.optString("name") },
                            description = item.optString("description"),
                            supportedGenerationMethods = methods
                        )
                    )
                }
            }
        }.filter { it.id.isNotBlank() }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun chooseModel(models: List<GeminiModel>, requested: String): GeminiModel {
        return models.firstOrNull { it.id == requested.removePrefix("models/") }
            ?: models.firstOrNull { it.id == "gemini-2.5-flash" }
            ?: models.firstOrNull { it.id.contains("flash", ignoreCase = true) }
            ?: models.firstOrNull()
            ?: error("No Gemini model supporting generateContent was returned")
    }

    private fun currentApiKey(): String =
        settingsRepository.geminiApiKey()?.takeIf { it.isNotBlank() } ?: buildTimeApiKey

    private fun parseResponse(raw: String, context: LearningContext): AiGenerationResult {
        val root = JSONObject(raw)
        val text = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        val cardsJson = JSONObject(text).optJSONArray("cards") ?: JSONArray()
        val cards = mutableListOf<GeneratedLearningCard>()
        val seenTopics = mutableSetOf<String>()
        for (i in 0 until cardsJson.length().coerceAtMost(4)) {
            val item = cardsJson.getJSONObject(i)
            val card = GeneratedLearningCard(
                topic = item.optString("topic").trim(),
                question = item.optString("question").trim(),
                answer = item.optString("answer").trim(),
                explanation = item.optString("explanation").trim(),
                sourceQuote = item.optString("sourceQuote").trim(),
                confidence = item.optDouble("confidence", 0.0).toFloat()
            )
            if (isValid(card, context.recentText) && seenTopics.add(card.topic.lowercase())) {
                cards.add(card)
            }
        }
        return AiGenerationResult(cards, contextChapterCount = 3, usedCharacters = context.recentText.length)
    }

    private fun isValid(card: GeneratedLearningCard, source: String): Boolean {
        if (card.topic.length !in 3..100) return false
        if (card.question.length !in 12..300) return false
        if (card.answer.length !in 2..800) return false
        if (card.explanation.length !in 10..800) return false
        if (card.sourceQuote.length !in 20..500) return false
        if (card.confidence < 0.65f) return false
        return normalize(source).contains(normalize(card.sourceQuote))
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().lowercase()

    private fun buildPrompt(context: LearningContext): String = """
        You create comprehension review cards for a reader.
        Use only the supplied source text. Do not invent facts, plot events, names, or quotes.
        Identify 1 to 4 distinct important topics, prioritizing ideas, causes, decisions,
        conflicts, relationships, and concepts over trivial details.
        Each card must test active recall, not recognition. The sourceQuote must be copied
        exactly from the supplied text, 20 to 500 characters, and must support the answer.
        Return only JSON matching the schema.
        Book: ${context.bookTitle}
        Current chapter: ${context.chapterTitle}
        Recent context includes the current chapter and the two preceding chapters.

        SOURCE TEXT:
        ${context.recentText}
    """.trimIndent()

    companion object {
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_MODEL_PAGES = 20
    }
}
