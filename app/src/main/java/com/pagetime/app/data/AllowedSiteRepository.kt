package com.pagetime.app.data

import com.pagetime.app.blocker.SiteRules
import com.pagetime.app.data.local.AllowedSiteDao
import com.pagetime.app.data.local.AllowedSiteEntity
import kotlinx.coroutines.flow.Flow

/**
 * The reader's allowed sites, as rows — the [com.pagetime.app.blocker.SiteMode.ALLOWLIST]
 * counterpart to [BlockedSiteRepository]. See it for why parsing lives in
 * [SiteRules] rather than here.
 */
class AllowedSiteRepository(private val dao: AllowedSiteDao) {

    fun observeEnabled(): Flow<List<AllowedSiteEntity>> = dao.observeEnabled()

    fun observeAll(): Flow<List<AllowedSiteEntity>> = dao.observeAll()

    /** Adds what the reader typed, or refuses it. See [BlockedSiteRepository.add]. */
    suspend fun add(input: String, now: Long = System.currentTimeMillis()): SiteRules.Rule? {
        val rule = SiteRules.parse(input) ?: return null
        dao.upsert(
            AllowedSiteEntity(
                id = rule.id,
                host = rule.host,
                pathPrefix = rule.pathPrefix,
                createdAt = now,
            )
        )
        return rule
    }

    suspend fun remove(id: String) = dao.delete(id)
}
