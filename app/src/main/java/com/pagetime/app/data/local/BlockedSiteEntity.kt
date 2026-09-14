package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A site the reader has asked to stay off.
 *
 * `id` is the rule written out — `bbc.co.uk` or `bbc.co.uk/news` — and is the
 * primary key because it is also the rule's meaning. Two rules that say the
 * same thing are the same rule, so re-adding one is an upsert rather than a
 * duplicate row the reader cannot tell apart in the list.
 *
 * `host` and `pathPrefix` are stored separately rather than re-parsed on every
 * address bar read. Matching runs on the main thread inside an accessibility
 * event, and it is the one place in this feature where per-event parsing would
 * be a real cost for no benefit.
 *
 * There is no `enabled` column to write: a site is either on the list or it is
 * not, and removing one is the delete. The column stays for symmetry with
 * [BlockedAppEntity] so both tables can answer `observeEnabled`.
 */
@Entity(
    tableName = "blocked_sites",
    indices = [Index(value = ["enabled"])]
)
data class BlockedSiteEntity(
    @PrimaryKey val id: String,
    val host: String,
    /** `/news` for a section of a site, null for the whole thing. */
    val pathPrefix: String? = null,
    val createdAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true
)
