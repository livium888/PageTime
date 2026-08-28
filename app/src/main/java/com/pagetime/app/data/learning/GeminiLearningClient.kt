package com.pagetime.app.data.learning

import com.pagetime.app.BuildConfig
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            Use ONLY the supplied SOURCE TEXT: never introduce ideas, names, or events that
            do not appear in it, including material from elsewhere in the same book or later
            in the same chapter. If SOURCE TEXT contains no substantial teachable idea,
            return an empty concepts array instead of inventing one.
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
            .put("cardType", JSONObject().put("type", "STRING"))
        val cardItemSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", cardFields)
            .put("required", JSONArray(listOf("topic", "question", "answer", "explanation", "sourceQuote", "confidence", "cardType")))
        val cardsSchema = JSONObject()
            .put("type", "ARRAY")
            .put("minItems", 3)
            .put("maxItems", 5)
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
                val response = withTimeout(65_000L) {
                    AppHttp.newClient(callTimeoutSeconds = 60L).newCall(request).execute()
                }
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.isSuccessful) return body
                    if (it.code !in RETRYABLE_CODES) {
                        val detail = body.take(240).replace(Regex("\\s+"), " ").trim()
                        error("Gemini request failed: HTTP ${it.code}${detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                    }
                    lastError = IllegalStateException("Gemini temporarily unavailable: HTTP ${it.code}")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
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

    /**
     * Evaluates a reader's explanation of a concept using the Feynman Technique.
     * Returns structured feedback on accuracy, completeness, and clarity.
     */
    suspend fun evaluateExplanation(
        conceptLabel: String,
        keyPoints: List<String>,
        sourceExcerpt: String,
        userExplanation: String,
        bookTitle: String,
        chapterTitle: String
    ): ExplanationEvaluation = withContext(Dispatchers.IO) {
        val apiKey = currentApiKey()
        check(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        val prompt = """
            |You are evaluating a reader's understanding of a concept from a book.
            |Be encouraging but honest. The goal is to help the reader truly understand,
            |not just memorize.
            |
            |Book: $bookTitle
            |Chapter: $chapterTitle
            |Concept: $conceptLabel
            |Key aspects the reader should cover: ${keyPoints.joinToString(", ")}
            |
            |Compact source grounding (use only to resolve ambiguity):
            |${sourceExcerpt.take(6_000)}
            |
            |Reader's explanation:
            |${userExplanation.take(4_000)}
            |
            |Evaluate on three dimensions (1–5 scale each):
            |1. ACCURACY: Are the facts correct?
            |2. COMPLETENESS: Did they cover the key aspects?
            |3. CLARITY: Could a 12-year-old follow this explanation?
            |
            |Return JSON:
            |{
            |  "accuracy": <1-5>,
            |  "completeness": <1-5>,
            |  "clarity": <1-5>,
            |  "whatTheyGotRight": "<specific points they explained correctly>",
            |  "whatTheyMissed": "<what they missed or got wrong>",
            |  "suggestedImprovement": "<one specific suggestion>",
            |  "simplerVersion": "<a clear, simple 2-3 sentence version of the ideal explanation>"
            |}
            |
            |Return only the JSON.
        """.trimMargin()
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("accuracy", JSONObject().put("type", "INTEGER"))
                .put("completeness", JSONObject().put("type", "INTEGER"))
                .put("clarity", JSONObject().put("type", "INTEGER"))
                .put("whatTheyGotRight", JSONObject().put("type", "STRING"))
                .put("whatTheyMissed", JSONObject().put("type", "STRING"))
                .put("suggestedImprovement", JSONObject().put("type", "STRING"))
                .put("simplerVersion", JSONObject().put("type", "STRING")))
            .put("required", JSONArray(listOf(
                "accuracy", "completeness", "clarity",
                "whatTheyGotRight", "whatTheyMissed",
                "suggestedImprovement", "simplerVersion"
            )))
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
        val raw = executeWithRetry(request)
        val text = JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
        val json = JSONObject(text)
        ExplanationEvaluation(
            accuracy = json.optInt("accuracy", 3),
            completeness = json.optInt("completeness", 3),
            clarity = json.optInt("clarity", 3),
            whatTheyGotRight = json.optString("whatTheyGotRight", ""),
            whatTheyMissed = json.optString("whatTheyMissed", ""),
            suggestedImprovement = json.optString("suggestedImprovement", ""),
            simplerVersion = json.optString("simplerVersion", "")
        )
    }

    /**
     * Reformats a raw YouTube transcript into a clean, book-like document using Gemini.
     * Fixes ASR artifacts, identifies speakers, adds paragraph structure and topic headings.
     * Returns the formatted text, or throws on failure.
     */
    suspend fun formatTranscriptWithAI(rawTranscript: String, videoTitle: String): String = withContext(Dispatchers.IO) {
        check(currentApiKey().isNotBlank()) { "Gemini API key is not configured" }
        require(rawTranscript.isNotBlank()) { "Transcript is empty" }

        val chunks = splitTranscriptForFormatting(rawTranscript)
        val formattedChunks = chunks.mapIndexed { index, chunk ->
            formatTranscriptChunk(
                chunk = chunk,
                videoTitle = videoTitle,
                chunkNumber = index + 1,
                totalChunks = chunks.size
            )
        }
        formattedChunks.joinToString("\n\n")
            .trim()
            .also { formatted ->
                // A model response that is dramatically shorter is almost certainly
                // truncated or summarized; never present that as a complete book.
                require(formatted.length >= (rawTranscript.length * 0.35).toInt()) {
                    "AI formatting returned incomplete text; the original transcript was kept"
                }
            }
    }

    private suspend fun formatTranscriptChunk(
        chunk: String,
        videoTitle: String,
        chunkNumber: Int,
        totalChunks: Int
    ): String {
        val prompt = """
            |You are a professional editor formatting a raw YouTube transcript into a
            |clean, readable book-like document.
            |
            |Video title: $videoTitle
            |This is formatting chunk $chunkNumber of $totalChunks. It is part of one continuous transcript.
            |
            |RULES:
            |1. Fix all ASR (auto-generated caption) artifacts: repeated words, missing
            |   punctuation, broken sentences. Add proper sentence-ending periods.
            |2. Identify speakers: the video is a conversation/interview. Label speaker
            |   turns with "Host:" and "Guest:" (or infer names from context if mentioned).
            |   If you cannot determine who is who, use "Speaker A:" and "Speaker B:".
            |3. Organize into clear paragraphs at natural topic transitions.
            |4. Add topic headings (like chapter titles) when the conversation shifts to a
            |   new subject. Use a single # heading line.
            |5. Remove filler words (um, uh, like, you know) when they add no meaning.
            |6. Keep the conversational tone — do NOT make it formal or academic.
            |7. Preserve ALL content and meaning. Do not summarize or skip anything.
            |8. Remove [Music], [Applause], and other bracket tags.
            |9. Output ONLY the formatted text. No preamble, no commentary.
            |
            |EXAMPLE FORMAT:
            |
            |# The Science of Sleep
            |
            |**Host:** Welcome to the show. Today we're talking about sleep science.
            |
            |**Guest:** Thanks for having me. Sleep is one of the most important things
            |for health, and most people don't get enough of it.
            |
            |**Host:** Why do you think sleep is so undervalued?
            |
            |# Understanding Brain Plasticity
            |
            |**Guest:** The key insight is that your brain physically changes based on
            |what you do. It's not fixed — it adapts.
            |
            |---
            |
            |Here is the raw transcript to format:
            |
            |$chunk
            |
            |Return ONLY the formatted text. Do not summarize, omit, reorder, or invent content.
            |Do not add a title heading unless this is chunk 1.
        """.trimMargin()

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.3)
                .put("maxOutputTokens", 8192))
            .toString()

        val request = Request.Builder()
            .url("$endpointBase/models/${currentModel()}:generateContent")
            .header("x-goog-api-key", currentApiKey())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = executeWithRetry(request)
        return JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text")
            .trim()
    }

    companion object {
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_MODEL_PAGES = 20

        internal fun splitTranscriptForFormatting(rawTranscript: String): List<String> {
        val targetCharacters = 24_000
        val paragraphs = rawTranscript
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return listOf(rawTranscript)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        paragraphs.forEach { paragraph ->
            if (current.isNotEmpty() && current.length + paragraph.length + 2 > targetCharacters) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\\n\\n")
            current.append(paragraph)
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks
        }
    }

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
        for (i in 0 until cardsJson.length().coerceAtMost(5)) {
            val item = cardsJson.getJSONObject(i)
            // Cards are now concept-focused Q&A for the explain-back flow.
            val cardType = item.optString("cardType", "qa").trim().ifBlank { "qa" }

            val card = GeneratedLearningCard(
                topic = item.optString("topic").trim(),
                question = item.optString("question").trim(),
                answer = item.optString("answer").trim(),
                explanation = item.optString("explanation").trim(),
                sourceQuote = item.optString("sourceQuote").trim(),
                confidence = item.optDouble("confidence", 0.0).toFloat(),
                cardType = cardType,
                mcqOptions = null
            )
            if (isValid(card, context.recentText) && seenTopics.add(card.topic.lowercase())) {
                cards.add(card)
            }
        }
        return AiGenerationResult(cards, contextChapterCount = 1, usedCharacters = context.recentText.length)
    }

    private fun isValid(card: GeneratedLearningCard, source: String): Boolean {
        if (card.topic.length !in 3..100) return false
        if (card.question.length !in 12..500) return false
        if (card.answer.length !in 2..200) return false
        if (card.explanation.length !in 10..800) return false
        if (card.sourceQuote.length !in 20..500) return false
        if (card.confidence < 0.60f) return false
        return normalize(source).contains(normalize(card.sourceQuote))
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().lowercase()

    private fun buildPrompt(context: LearningContext): String {
        val dedupHint = if (context.existingCardTopics.isNotEmpty()) {
            val existing = context.existingCardTopics.take(20).joinToString(", ")
            """
            |EXISTING CARDS (do not duplicate these — test a different aspect of the
            |chapter or a deeper implication):
            |$existing
            """.trimMargin()
        } else ""
        return """
        |You create comprehension review concepts from the supplied reading.
        |Use ONLY the source text. Do not invent facts, plot events, names, or quotes.
        |
        |Return exactly 3 to 5 key concepts from this chapter. Each concept should
        |test whether the reader truly understands the core ideas — not surface recall.
        |
        |For each concept:
        |
        |  topic: The concept name (e.g. "Natural selection mechanism" not
        |  "Chapter details").
        |
        |  question: A clear prompt asking the reader to explain this concept in their
        |  own words (e.g. "Explain how natural selection works according to the author").
        |  Make it specific to what the author argues, not a generic textbook question.
        |
        |  answer: A brief 1-2 sentence model answer that covers the key points.
        |
        |  explanation: One sentence explaining why this concept matters for
        |  understanding the chapter's argument.
        |
        |  sourceQuote: A verbatim quote from the source text (20-500 chars) that
        |  grounds the concept.
        |
        |  confidence: 0.70-0.98. Only include concepts where not understanding them
        |  would genuinely damage the reader's comprehension of the chapter.
        |
        |QUALITY RULES (non-negotiable):
        |  - Focus on central arguments, definitions, mechanisms, cause/effect chains,
        |    principles, or meaningful contrasts.
        |  - NEVER test incidental names, dates, trivial details, or searchable trivia.
        |  - Each concept should target a DIFFERENT idea — no two about the same topic.
        |  - Questions should require the reader to synthesize, not just recall.
        |  - The model answer should be clear enough that a 12-year-old could follow it.
        |
        |$dedupHint
        |
        |Book: ${context.bookTitle}
        |Chapter: ${context.chapterTitle}
        |
        |SOURCE TEXT:
        |${context.recentText}
        |
        |Return only the requested JSON.
        """.trimMargin()
    }


}
