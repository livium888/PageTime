package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One entry in the usage ledger: time earned by reading, time spent in a blocked
 * app, or a "blocked at zero" event. Session-level rows (seconds summed per
 * session), not per-second spam — this is what makes the counters auditable even
 * after PageTime has been swiped away and restarted.
 */
@Entity(tableName = "usage_events")
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** One of UsageRepository.TYPE_EARNED / TYPE_SPENT / TYPE_BLOCKED. */
    val type: String,
    /** Blocked app package for SPENT/BLOCKED events; null for EARNED. */
    val packageName: String?,
    val seconds: Long
)
