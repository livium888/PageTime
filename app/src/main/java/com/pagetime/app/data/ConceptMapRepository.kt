package com.pagetime.app.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.pagetime.app.data.learning.ConceptMapGenerationResult
import com.pagetime.app.data.learning.GeneratedConcept
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.GenerationPolicy
import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.learning.LocalConceptMapGenerator
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipDao
import com.pagetime.app.data.local.ConceptRelationshipEntity
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
     * Generates and merges the concept map for the current reading window.
     *
     * Gemini is contacted at most once per chapter: the generation key is stable
     * for a given chapter, so every later checkpoint (or re-opening the map)
     * reads the stored result instead of making another API call.
     */
    suspend fun generateForReadingWindow(
        bookId: String,
        chapterIndex: Int
    ): ConceptMapGenerationResult {
        val book = bookDao.getById(bookId) ?: error("Book not found")
        val context = contextExtractor.extract(book, chapterIndex)
        val key = generationKey(context)
        val now = System.currentTimeMillis()
        val previous = generationDao.get(bookId, key)
        if (previous != null) {
            val isStale = previous.status == STATUS_GENERATING && now - previous.updatedAt >= GENERATION_STALE_AFTER_MS
            val canRetry = previous.status == STATUS_FAILED && now - previous.updatedAt >= GENERATION_RETRY_AFTER_MS
            if (!isStale && !canRetry) {
                // Chapter already processed (or a run is in flight): no new API call.
                return cachedResult(bookId)
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
            return cachedResult(bookId)
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

    /** Reflects what is already stored for the book so cached runs still report a useful moment. */
    private suspend fun cachedResult(bookId: String): ConceptMapGenerationResult {
        val existing = conceptDao.getForBook(bookId)
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

    private fun generationKey(context: com.pagetime.app.data.learning.LearningContext): String {
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
            val merged = old?.copy(
                label = preferredLabel(old.label, item.label),
                description = preferredDescription(old.description, item.description),
                type = old.type.ifBlank { item.type },
                lastChapterIndex = chapterIndex,
                sourceQuote = item.sourceQuote.takeIf { it.isNotBlank() } ?: old.sourceQuote,
                confidence = maxOf(old.confidence, item.confidence),
                mentionCount = old.mentionCount + 1,
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
                updatedAt = now
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

    companion object {
        private const val STATUS_GENERATING = "generating"
        private const val STATUS_COMPLETE = "complete"
        private const val STATUS_FAILED = "failed"
        private const val GENERATION_STALE_AFTER_MS = 10 * 60 * 1000L
        private const val GENERATION_RETRY_AFTER_MS = 15 * 60 * 1000L
    }
}

data class ConceptMap(
    val concepts: List<ConceptEntity>,
    val relationships: List<ConceptRelationshipEntity>
)
