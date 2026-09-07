package com.pagetime.app.data.learning

import com.pagetime.app.BuildConfig
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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

    /** True when any usable key exists (user-entered or build-time fallback). */
    fun hasKey(): Boolean = runCatching { currentApiKey().isNotBlank() }.getOrDefault(false)

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

    /**
     * Writes one prompt per supplied passage.
     *
     * ONE CALL FOR THE WHOLE CHAPTER
     *
     * The passages arrive together rather than one request each. It is cheaper,
     * but the real reason is that a model shown all five at once can avoid
     * asking the same question twice, which it cannot do when each call is
     * blind to the others.
     *
     * WHAT IT IS ASKED FOR, AND WHY
     *
     * The instructions are Matuschak's attributes of a good prompt, stated as
     * rules rather than hoped for: one idea per prompt, an answer short enough
     * to actually retrieve, and a question about the WORLD rather than about
     * the text. That last one matters most and is the easiest to get wrong —
     * "what does this passage say about X" is worthless the moment the book is
     * closed, which is exactly when the card comes back.
     *
     * Every returned quote must be copied from its passage verbatim, and that
     * is checked locally afterwards. This asks for honesty; ChapterPromptRules
     * is what enforces it.
     */
    suspend fun generateChapterPrompts(
        bookTitle: String,
        chapterTitle: String,
        passages: List<String>,
    ): List<RawPrompt> = withContext(Dispatchers.IO) {
        val apiKey = currentApiKey()
        check(apiKey.isNotBlank()) { "Gemini API key is not configured" }
        if (passages.isEmpty()) return@withContext emptyList()

        val promptSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("passageIndex", JSONObject().put("type", "INTEGER"))
                .put("prompt", JSONObject().put("type", "STRING"))
                .put("answer", JSONObject().put("type", "STRING"))
                .put("sourceQuote", JSONObject().put("type", "STRING"))
                .put("type", JSONObject().put("type", "STRING")
                    .put("enum", JSONArray(listOf("qa", "cloze")))))
            .put("required", JSONArray(
                listOf("passageIndex", "prompt", "answer", "sourceQuote", "type")
            ))
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("prompts", JSONObject().put("type", "ARRAY").put("items", promptSchema)))
            .put("required", JSONArray(listOf("prompts")))

        val instructions = ChapterPromptText.build(bookTitle, chapterTitle, passages)

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", instructions)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))
            .toString()
        val request = Request.Builder()
            .url("$endpointBase/models/${currentModel()}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        parseChapterPrompts(executeWithRetry(request))
    }

    /**
     * Finds the prompts in a response, or says precisely why it could not.
     *
     * THIS THREW EVERYTHING AWAY
     *
     * The first version wrapped the whole unwrap in runCatching and returned an
     * empty list on any failure, so a blocked response, a truncated one, a
     * schema mismatch and a genuinely empty chapter were one outcome:
     * "the model returned nothing". That is the same silence this feature has
     * already been fixed for once, hidden one layer down.
     *
     * EVERY PART, NOT THE FIRST
     *
     * gemini-2.5-flash is a thinking model, and a thinking response can carry a
     * reasoning part BEFORE the answer. Reading parts[0] and parsing it as JSON
     * then fails on the thought and never reaches the answer sitting in the
     * next part. Every part is searched for the object that actually has the
     * prompts in it.
     *
     * Fences are stripped because a model asked for JSON will sometimes wrap it
     * in a markdown block anyway, and refusing that is pedantry paid for by the
     * reader.
     */
    private fun parseChapterPrompts(raw: String): List<RawPrompt> {
        val root = JSONObject(raw)

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blocked = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            error(
                "Gemini returned no candidates" +
                    if (blocked.isNotBlank()) " (blocked: $blocked)" else ""
            )
        }

        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optString("finishReason", "unknown")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            // MAX_TOKENS here usually means the thinking budget ate the whole
            // output allowance before any answer was written.
            error("Gemini returned no content (finishReason=$finishReason)")
        }

        val texts = (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }
        val payload = texts.firstNotNullOfOrNull { text ->
            runCatching { JSONObject(stripCodeFence(text)) }.getOrNull()
                ?.takeIf { it.has("prompts") }
        } ?: error(
            "Gemini returned no prompts array " +
                "(finishReason=$finishReason, parts=${parts.length()}, " +
                "first 120 chars: ${texts.firstOrNull()?.take(120).orEmpty()})"
        )

        val array = payload.optJSONArray("prompts") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            RawPrompt(
                passageIndex = item.optInt("passageIndex", -1),
                prompt = item.optString("prompt", ""),
                answer = item.optString("answer", ""),
                sourceQuote = item.optString("sourceQuote", ""),
                type = item.optString("type", RawPrompt.TYPE_QA),
            )
        }
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
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
                    if (it.isSuccessful) {
                        // Measured, not estimated. Reported into the caller's
                        // coroutine context rather than through a callback, so
                        // two requests in flight at once cannot have their
                        // tokens attributed to each other's log row. Never
                        // allowed to break the call it is measuring.
                        runCatching {
                            val sink = currentCoroutineContext()[GeminiUsageSink]
                            if (sink != null) {
                                GeminiUsageParser.parse(body, currentModel())?.let { usage ->
                                    sink.usage = usage
                                }
                            }
                        }
                        return body
                    }
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
        val accuracy = json.optInt("accuracy", 3)
        val completeness = json.optInt("completeness", 3)
        val clarity = json.optInt("clarity", 3)
        ExplanationEvaluation(
            accuracy = accuracy,
            completeness = completeness,
            clarity = clarity,
            overallScore = (accuracy + completeness + clarity) / 3f,
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
    suspend fun formatTranscriptWithAI(
        rawTranscript: String,
        videoTitle: String,
        onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        check(currentApiKey().isNotBlank()) { "Gemini API key is not configured" }
        require(rawTranscript.isNotBlank()) { "Transcript is empty" }

        val chunks = splitTranscriptForFormatting(rawTranscript)
        val formattedChunks = chunks.mapIndexed { index, chunk ->
            formatTranscriptChunk(
                chunk = chunk,
                videoTitle = videoTitle,
                chunkNumber = index + 1,
                totalChunks = chunks.size
            ).also {
                require(it.isNotBlank()) { "AI formatting returned an empty chunk ${index + 1}" }
                onProgress?.invoke(index + 1, chunks.size)
            }
        }
        formattedChunks.joinToString("\n\n")
            .trim()
            .also { formatted ->
                // Formatting may legitimately remove filler words and caption noise,
                // so length alone is not a reliable completeness test. Every bounded
                // input chunk must instead produce a non-empty response above.
                require(formatted.isNotBlank()) {
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


    /**
     * One small call: drafts a Lumen card (front + back) from a prompt the
     * caller has already rendered, and returns strict JSON {front, back}. The
     * caller owns the prompt because it owns the reader's tailored template and
     * the re-ask prompts, which carry their own passage and the idea to avoid;
     * a convenience overload that rendered the default template here is how the
     * tailored prompt got silently ignored on this path once already.
     */
    suspend fun draftLumenCardFromPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.4)
                .put("maxOutputTokens", 512))
            .toString()

        val request = Request.Builder()
            .url("$endpointBase/models/${currentModel()}:generateContent")
            .header("x-goog-api-key", currentApiKey())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = executeWithRetry(request)
        JSONObject(raw).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text")
            .trim()
    }

    companion object {
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
        private const val MAX_MODEL_PAGES = 20
        private const val FORMAT_CHUNK_CHARACTERS = 16_000

        internal fun splitTranscriptForFormatting(rawTranscript: String): List<String> {
            val normalized = rawTranscript.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) return emptyList()

            val units = normalized
                .split(Regex("(?<=[.!?])\\s+|\\n+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            units.forEach { unit ->
                var remaining = unit
                while (remaining.isNotBlank()) {
                    val room = FORMAT_CHUNK_CHARACTERS - current.length - if (current.isNotEmpty()) 1 else 0
                    if (room <= 0) {
                        chunks += current.toString().trim()
                        current = StringBuilder()
                        continue
                    }
                    if (remaining.length <= room) {
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(remaining)
                        remaining = ""
                    } else {
                        val cut = remaining.lastIndexOf(' ', room.coerceAtLeast(1))
                            .takeIf { it > 0 } ?: room
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(remaining.take(cut))
                        chunks += current.toString().trim()
                        current = StringBuilder()
                        remaining = remaining.drop(cut).trimStart()
                    }
                }
            }
            if (current.isNotEmpty()) chunks += current.toString().trim()
            return chunks
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().lowercase()
}
