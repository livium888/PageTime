package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A site the reader has explicitly let through under
 * [com.pagetime.app.blocker.SiteMode.ALLOWLIST] — the mirror image of
 * [BlockedSiteEntity], stored in its own table so switching modes never
 * loses either list. See [BlockedSiteEntity] for why the shape is what it
 * is; nothing here differs beyond which direction the rule works in.
 */
@Entity(
    tableName = "allowed_sites",
    indices = [Index(value = ["enabled"])]
)
data class AllowedSiteEntity(
    @PrimaryKey val id: String,
    val host: String,
    /** `/news` for a section of a site, null for the whole thing. */
    val pathPrefix: String? = null,
    val createdAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true
)
