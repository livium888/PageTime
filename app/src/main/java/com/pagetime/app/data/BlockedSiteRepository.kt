package com.pagetime.app.data

import com.pagetime.app.blocker.SiteRules
import com.pagetime.app.data.local.BlockedSiteDao
import com.pagetime.app.data.local.BlockedSiteEntity
import kotlinx.coroutines.flow.Flow

/**
 * The reader's site rules, as rows.
 *
 * The parsing lives in [SiteRules] and not here, so that a rule the reader
 * types is refused or accepted by the same code that later decides whether an
 * address bar matches it. A repository that normalised a host its own way
 * would be the second opinion that makes `www.` work in one place and not the
 * other.
 */
class BlockedSiteRepository(private val dao: BlockedSiteDao) {

    fun observeEnabled(): Flow<List<BlockedSiteEntity>> = dao.observeEnabled()

    fun observeAll(): Flow<List<BlockedSiteEntity>> = dao.observeAll()

    /**
     * Adds what the reader typed, or refuses it.
     *
     * Returns the rule that was stored, so the screen can clear its field only
     * when there is something to clear — and can say what it understood when
     * the reader typed `https://WWW.BBC.co.uk/News/` and the list says
     * `bbc.co.uk/news`.
     */
    suspend fun add(input: String, now: Long = System.currentTimeMillis()): SiteRules.Rule? {
        val rule = SiteRules.parse(input) ?: return null
        dao.upsert(
            BlockedSiteEntity(
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
