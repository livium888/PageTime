package com.pagetime.app.data.usage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Orders fire-and-forget ledger writes against readers of the same ledger.
 *
 * A spend session flushes its SPENT row by *launching* the write — it cannot
 * suspend, because it is driven from the AccessibilityService's main-thread
 * callback. That leaves a window where the session is over but its row has not
 * landed, and a reconcile sweep starting inside that window reads a ledger
 * missing those seconds: [UsageReconciler.alreadyChargedSeconds] under-counts,
 * and the sweep charges time the live ticker already took.
 *
 * [track] records the write synchronously, before the coroutine body runs, so
 * anything that calls [await] afterwards is guaranteed to see it. Writes are
 * also chained to each other, which keeps them in submission order and makes
 * awaiting the most recent one sufficient.
 */
class PendingLedgerWrites {

    @Volatile
    private var last: Job? = null

    /** Launches [write] on [scope], after any write already in flight. */
    fun track(scope: CoroutineScope, write: suspend () -> Unit) {
        val previous = last
        last = scope.launch {
            previous?.join()
            write()
        }
    }

    /** Suspends until every write submitted before this call has completed. */
    suspend fun await() {
        last?.join()
    }
}
