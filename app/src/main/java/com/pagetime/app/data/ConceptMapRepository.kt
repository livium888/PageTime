package com.pagetime.app.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.pagetime.app.data.learning.ConceptMapGenerationResult
import com.pagetime.app.data.learning.ConceptRangeMatcher
import com.pagetime.app.data.learning.GeneratedConcept
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.GenerationPolicy
import com.pagetime.app.data.learning.LearningContext
import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.learning.LocalConceptMapGenerator
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipDao
import com.pagetime.app.data.local.ConceptRelationshipEntity
import com.pagetime.app.data.local.LearningCheckpoint
import com.pagetime.app.data.local.LearningGenerationDao
import com.pagetime.app.data.local.LearningGenerationEntity
import com.pagetime.app.data.local.MapMoment
import com.pagetime.app.data.local.SettingsRepository
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ConceptMapRepository(
    private val database: RoomDatabase,
    private val conceptDao: ConceptDao,
    private val relationshipDao: ConceptRelationshipDao,
    private val bookDao: BookDao,
    private val generationDao: LearningGenerationDao,
    private val contextExtractor: LearningContextExtractor,
    private val geminiClient: GeminiLearningClient,
    private val settingsRepository: SettingsRepository,
    private val aiUsageRepository: AiUsageRepository? = null
) {
    fun observeBookMap(bookId: String): Flow<ConceptMap> = combine(
        conceptDao.observeForBook(bookId),
        relationshipDao.observeForBook(bookId)
    ) { concepts, relationships -> ConceptMap(concepts, relationships) }

    /**
     * Chapter-level generation used by the concept map screen and the reader's
     * automatic pre-generation. The context is the full chapter and the cache
     * key is the chapter text, so each chapter is processed at most once.
     */
    suspend fun generateForReadingWindow(
        bookId: String,
        chapterIndex: Int
    ): ConceptMapGenerationResult {
        val book = bookDao.getById(bookId) ?: error("Book not found")
        return generateWithContext(
            bookId = bookId,
            chapterIndex = chapterIndex,
            context = contextExtractor.extract(book, chapterIndex),
            rangeScopedCache = false
        )
    }

    /**
     * Range-scoped generation for user-triggered Explain Back: the context is
     * exactly checkpoint → current position (chapter start when no checkpoint
     * exists), and the cache key is that bounded range's text. A new reading
     * range therefore triggers a fresh generation from that range's text
     * instead of returning concepts cached for a different part of the chapter.
     */
    suspend fun generateForLearningRange(
        bookId: String,
        chapterIndex: Int,
        checkpoint: LearningCheckpoint?,
        currentLocatorJson: String?,
        currentTextOffset: Int?
    ): ConceptMapGenerationResult {
        val book = bookDao.getById(bookId) ?: error("Book not found")
        val context = contextExtractor.extract(
            book = book,
            chapterIndex = chapterIndex,
            checkpoint = checkpoint,
            currentLocatorJson = currentLocatorJson,
            currentTextOffset = currentTextOffset
        )
        return generateWithContext(
            bookId = bookId,
            chapterIndex = chapterIndex,
            context = context,
            rangeScopedCache = true
        )
    }

    private suspend fun generateWithContext(
        bookId: String,
        chapterIndex: Int,
        context: LearningContext,
        rangeScopedCache: Boolean
    ): ConceptMapGenerationResult {
        val key = generationKey(context)
        val now = System.currentTimeMillis()
        val previous = generationDao.get(bookId, key)
        if (previous != null) {
            val isStale = previous.status == STATUS_GENERATING && now - previous.updatedAt >= GENERATION_STALE_AFTER_MS
            val canRetry = previous.status == STATUS_FAILED && now - previous.updatedAt >= GENERATION_RETRY_AFTER_MS
            if (!isStale && !canRetry) {
                // Chapter/range already processed (or a run is in flight): no new API call.
                return cachedResultFor(bookId, context, rangeScopedCache)
            }
            generationDao.release(bookId, key)
        }
        val claimed = generationDao.claim(
            LearningGenerationEntity(
                bookId = bookId,
                generationKey = key,
                chapterIndex = chapterIndex,
                status = STATUS_GENERATING,
                cardCount = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        if (claimed == -1L) {
            return cachedResultFor(bookId, context, rangeScopedCache)
        }
        return try {
            val existing = conceptDao.getForBook(bookId)
            val local = LocalConceptMapGenerator.generate(context)
            val useGemini = GenerationPolicy.shouldCallGemini(
                mode = settingsRepository.generationMode(),
                localItemCount = local.concepts.size,
                geminiConfigured = geminiClient.isConfigured
            )
            val generated = if (useGemini) {
                try {
                    val run = suspend {
                        geminiClient.generateConceptMap(context, existing.map { it.label })
                    }
                    val result = aiUsageRepository?.track(
                        bookId = context.bookId,
                        operation = AiUsageRepository.OPERATION_CONCEPTS,
                        model = geminiClient.currentModel(),
                        inputCharacters = context.recentText.length,
                        outputItems = { it.concepts.size },
                        secondaryItems = { it.relationships.size },
                        block = run
                    ) ?: run()
                    generationDao.complete(bookId, key, STATUS_COMPLETE, result.concepts.size, System.currentTimeMillis())
                    result
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    // Mark failed so a later checkpoint can retry; never leave the
                    // reader without a map when Gemini is unavailable.
                    generationDao.complete(bookId, key, STATUS_FAILED, 0, System.currentTimeMillis())
                    local
                }
            } else {
                // Local-only pass: merge it but do not claim the cache, so the
                // chapter can still be upgraded to a Gemini map later.
                generationDao.release(bookId, key)
                local
            }
            database.withTransaction {
                merge(bookId, chapterIndex, generated)
            }
            val featured = generated.relationships.firstOrNull()
            settingsRepository.saveMapMoment(
                MapMoment(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    conceptCount = generated.concepts.size,
                    relationshipCount = generated.relationships.size,
                    featuredConcept = featured?.sourceLabel ?: generated.concepts.firstOrNull()?.label,
                    featuredRelationship = featured?.let { "${it.relationType} ${it.targetLabel}" },
                    createdAt = System.currentTimeMillis()
                )
            )
            generated
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            generationDao.complete(bookId, key, STATUS_FAILED, 0, System.currentTimeMillis())
            throw error
        }
    }

    /**
     * Reflects what is already stored so cached runs still report a useful
     * moment. Range-scoped runs only report concepts grounded in the range
     * text; chapter-level runs keep the whole-book view.
     */
    private suspend fun cachedResultFor(
        bookId: String,
        context: LearningContext,
        rangeScopedCache: Boolean
    ): ConceptMapGenerationResult {
        val all = conceptDao.getForBook(bookId)
        val existing = if (rangeScopedCache) {
            all.filter { ConceptRangeMatcher.isRelevant(it.label, it.sourceQuote, context.recentText) }
        } else {
            all
        }
        return ConceptMapGenerationResult(
            concepts = existing.take(12).map {
                GeneratedConcept(
                    label = it.label,
                    description = it.description.orEmpty(),
                    type = it.type,
                    sourceQuote = it.sourceQuote.orEmpty(),
                    confidence = it.confidence
                )
            },
            relationships = emptyList()
        )
    }

    private fun generationKey(context: LearningContext): String {
        // Stable per chapter (same text -> same key), namespaced away from the
        // card-generation keys in the shared table.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("concepts:${context.bookId}:${context.chapterIndex}:${context.recentText}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun merge(bookId: String, chapterIndex: Int, generated: ConceptMapGenerationResult) {
        val now = System.currentTimeMillis()
        val conceptsByLabel = conceptDao.getForBook(bookId)
            .associateBy { it.normalizedLabel }
            .toMutableMap()
        val resolved = mutableMapOf<String, ConceptEntity>()
        generated.concepts.forEach { item ->
            val normalized = normalize(item.label)
            if (normalized.isBlank()) return@forEach
            val old = conceptsByLabel[normalized]
            val quote = item.sourceQuote.takeIf { it.isNotBlank() } ?: old?.sourceQuote
            val newLabel = preferredLabel(old?.label ?: item.label, item.label)
            val merged = old?.copy(
                label = newLabel,
                description = preferredDescription(old.description, item.description),
                type = old.type.ifBlank { item.type },
                lastChapterIndex = chapterIndex,
                sourceQuote = quote,
                confidence = maxOf(old.confidence, item.confidence),
                mentionCount = old.mentionCount + 1,
                keywords = extractKeywords(newLabel, quote),
                updatedAt = now
            ) ?: ConceptEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                label = item.label,
                normalizedLabel = normalized,
                description = item.description,
                type = item.type,
                firstChapterIndex = chapterIndex,
                lastChapterIndex = chapterIndex,
                sourceQuote = item.sourceQuote,
                confidence = item.confidence,
                mentionCount = 1,
                createdAt = now,
                updatedAt = now,
                keywords = extractKeywords(item.label, item.sourceQuote)
            )
            conceptDao.upsert(merged)
            conceptsByLabel[normalized] = merged
            resolved[normalized] = merged
        }
        val all = conceptDao.getForBook(bookId).associateBy { it.normalizedLabel }
        generated.relationships.forEach { item ->
            val source = all[normalize(item.sourceLabel)] ?: resolved[normalize(item.sourceLabel)]
            val target = all[normalize(item.targetLabel)] ?: resolved[normalize(item.targetLabel)]
            if (source == null || target == null || source.id == target.id) return@forEach
            relationshipDao.upsert(
                ConceptRelationshipEntity(
                    id = stableRelationshipId(bookId, source.id, target.id, item.relationType),
                    bookId = bookId,
                    sourceConceptId = source.id,
                    targetConceptId = target.id,
                    relationType = item.relationType,
                    explanation = item.explanation,
                    sourceQuote = item.sourceQuote,
                    confidence = item.confidence,
                    firstChapterIndex = chapterIndex,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun stableRelationshipId(bookId: String, source: String, target: String, relation: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$bookId:$source:$target:${normalize(relation)}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    private fun preferredLabel(old: String, fresh: String): String =
        if (old.length <= fresh.length) old else fresh

    private fun preferredDescription(old: String, fresh: String): String =
        if (fresh.length > old.length) fresh else old

    /**
     * Extracts searchable keywords from a concept's label and source quote.
     * These are persisted on the entity for fast local matching against
     * visible page text (no API call needed).
     */
    private fun extractKeywords(label: String, sourceQuote: String?): String {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "shall", "can", "this",
            "that", "these", "those", "it", "its", "not", "no", "so", "if",
            "than", "too", "very", "just", "about", "above", "after", "again",
            "all", "also", "any", "because", "before", "between", "both",
            "each", "few", "more", "most", "other", "some", "such", "into",
            "only", "own", "same", "then", "there", "when", "where", "which",
            "while", "who", "whom", "how", "what", "chapter", "concept", "idea"
        )
        val words = mutableListOf<String>()
        // Add label words first (highest priority).
        for (word in label.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (word.length >= 3 && word !in stopWords && word !in words) {
                words += word
            }
        }
        // Add significant words from the source quote.
        if (sourceQuote != null) {
            for (word in sourceQuote.lowercase().split(Regex("[^a-z0-9]+"))) {
                if (word.length >= 4 && word !in stopWords && word !in words) {
                    words += word
                }
            }
        }
        return words.take(MAX_KEYWORDS).joinToString(" ")
    }

    companion object {
        private const val STATUS_GENERATING = "generating"
        private const val STATUS_COMPLETE = "complete"
        private const val STATUS_FAILED = "failed"
        private const val GENERATION_STALE_AFTER_MS = 10 * 60 * 1000L
        private const val GENERATION_RETRY_AFTER_MS = 15 * 60 * 1000L
        private const val MAX_KEYWORDS = 20
    }
}

data class ConceptMap(
    val concepts: List<ConceptEntity>,
    val relationships: List<ConceptRelationshipEntity>
)
