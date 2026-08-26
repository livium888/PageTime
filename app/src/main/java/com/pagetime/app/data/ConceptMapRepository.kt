package com.pagetime.app.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.pagetime.app.data.learning.ConceptMapGenerationResult
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.learning.LocalConceptMapGenerator
import com.pagetime.app.data.local.BookDao
import com.pagetime.app.data.local.ConceptDao
import com.pagetime.app.data.local.ConceptEntity
import com.pagetime.app.data.local.ConceptRelationshipDao
import com.pagetime.app.data.local.ConceptRelationshipEntity
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
    private val contextExtractor: LearningContextExtractor,
    private val geminiClient: GeminiLearningClient,
    private val settingsRepository: SettingsRepository,
    private val aiUsageRepository: AiUsageRepository? = null
) {
    fun observeBookMap(bookId: String): Flow<ConceptMap> = combine(
        conceptDao.observeForBook(bookId),
        relationshipDao.observeForBook(bookId)
    ) { concepts, relationships -> ConceptMap(concepts, relationships) }

    suspend fun generateForReadingWindow(
        bookId: String,
        chapterIndex: Int
    ): ConceptMapGenerationResult {
        val book = bookDao.getById(bookId) ?: error("Book not found")
        val context = contextExtractor.extract(book, chapterIndex)
        val existing = conceptDao.getForBook(bookId)
        val generated = if (geminiClient.isConfigured) {
            try {
                val run = suspend {
                    geminiClient.generateConceptMap(context, existing.map { it.label })
                }
                aiUsageRepository?.track(
                    bookId = context.bookId,
                    operation = AiUsageRepository.OPERATION_CONCEPTS,
                    model = geminiClient.currentModel(),
                    inputCharacters = context.recentText.length,
                    outputItems = { it.concepts.size },
                    secondaryItems = { it.relationships.size },
                    block = run
                ) ?: run()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                LocalConceptMapGenerator.generate(context)
            }
        } else {
            LocalConceptMapGenerator.generate(context)
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
        return generated
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
                sourceQuote = old.sourceQuote ?: item.sourceQuote,
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
}

data class ConceptMap(
    val concepts: List<ConceptEntity>,
    val relationships: List<ConceptRelationshipEntity>
)
