package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An app the reader has chosen to trust for [com.pagetime.app.data.usage.ExternalReadingTracker]:
 * its foreground time counts toward reading credit, same shape as [BlockedAppEntity]
 * but the opposite direction — this grants rather than restricts.
 */
@Entity(tableName = "external_reading_apps")
data class ExternalReadingAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val enabled: Boolean = true
)
