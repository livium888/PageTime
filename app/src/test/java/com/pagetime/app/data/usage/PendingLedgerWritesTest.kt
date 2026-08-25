package com.pagetime.app.data.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The double-charge these tests guard against: a spend session flushes its SPENT
 * row by launching the write, and a reconcile sweep that reads the ledger inside
 * that window sees none of those seconds — so it charges them again on top of
 * what the live ticker already took.
 */
class PendingLedgerWritesTest {

    @Test
    fun `a reader waits for a write that was submitted but has not run yet`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val writes = PendingLedgerWrites()

        writes.track(backgroundScope) {
            gate.await()
            log += "write"
        }

        val reader = launch {
            writes.await()
            log += "read"
        }

        gate.complete(Unit)
        reader.join()

        assertEquals(listOf("write", "read"), log)
    }

    @Test
    fun `writes stay in submission order even when the first one suspends`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<Int>()
        val writes = PendingLedgerWrites()

        writes.track(backgroundScope) {
            gate.await()
            log += 1
        }
        writes.track(backgroundScope) { log += 2 }

        gate.complete(Unit)
        writes.await()

        assertEquals(listOf(1, 2), log)
    }

    @Test
    fun `awaiting with nothing in flight returns immediately`() = runTest {
        PendingLedgerWrites().await()
    }

    @Test
    fun `a write submitted after a read completes does not hold the next read`() = runTest {
        val log = mutableListOf<String>()
        val writes = PendingLedgerWrites()

        writes.track(backgroundScope) { log += "first" }
        writes.await()
        log += "read"

        writes.track(backgroundScope) { log += "second" }
        writes.await()
        log += "read"

        assertEquals(listOf("first", "read", "second", "read"), log)
    }
}
