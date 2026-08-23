package com.pagetime.app.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Thin Android-bound wrapper around UsageStatsManager. Keeps the interval math
 * pure (see [ForegroundParser]) while this class handles the OS specifics:
 * permission, event iteration, and the screen/keyguard event types that only
 * exist on newer API levels.
 */
class UsageStatsReader(private val context: Context) {

    /** The special-access "Usage access" switch, toggled from system settings. */
    @Suppress("DEPRECATION")
    fun isPermissionGranted(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = Process.myUid()
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * All usage events in [from]..[to]. Event-type access is gated at API 28;
     * on API 26–27 the reader emits no typed events, so reconciliation stays
     * conservative and does not retroactively charge from an unverifiable stream.
     */
    fun events(from: Long, to: Long): List<UsageEventSample> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val out = mutableListOf<UsageEventSample>()
        val event = UsageEvents.Event()
        try {
            val iterator = usm.queryEvents(from, to)
            while (iterator.hasNextEvent()) {
                iterator.getNextEvent(event)
                val pkg = event.packageName
                val time = event.timeStamp
                if (pkg == null || time <= 0L) continue
                // getEventType() (and the screen/keyguard event kinds) are API 28+.
                // Below that we can't distinguish event kinds reliably, so we emit
                // nothing — reconciliation simply doesn't run on 26–27, which is
                // the conservative direction (no retroactive charging on guesswork).
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    event.eventType
                } else {
                    0
                }
                out += UsageEventSample(pkg, type, time)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the query.
            return emptyList()
        } catch (_: RuntimeException) {
            // Some OEMs throw on malformed event streams; never crash the app.
            return emptyList()
        }
        return out
    }
}