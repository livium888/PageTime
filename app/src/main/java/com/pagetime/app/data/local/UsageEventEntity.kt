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
    /** One of UsageRepository.TYPE_EARNED / TYPE_SPENT / TYPE_BLOCKED / TYPE_RECONCILED. */
    val type: String,
    /** Blocked app package for SPENT/BLOCKED/RECONCILED events; null for EARNED. */
    val packageName: String?,
    val seconds: Long,
    /**
     * Wall-clock window this entry covers (SPENT sessions and RECONCILED sweeps;
     * null for EARNED/BLOCKED). Kept so the UsageStats reconciler can subtract
     * already-charged wall time from real foreground time without double-charging.
     */
    val windowStart: Long? = null,
    val windowEnd: Long? = null
)
