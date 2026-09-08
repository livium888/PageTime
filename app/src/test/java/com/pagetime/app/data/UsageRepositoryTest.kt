package com.pagetime.app.data

import app.cash.turbine.test
import com.pagetime.app.data.local.PackageTotal
import com.pagetime.app.data.local.UsageEventDao
import com.pagetime.app.data.local.UsageEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The ledger must answer the same questions after a process death that it
 * answers before: what was earned, what was spent, and — critically — that
 * UsageStats-reconciled time is visible in daily totals and never counted twice
 * against the live ticker's sessions.
 */
class UsageRepositoryTest {

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private lateinit var dao: FakeUsageEventDao
    private lateinit var repo: UsageRepository

    @Before
    fun setUp() {
        dao = FakeUsageEventDao()
        repo = UsageRepository(dao)
    }

    @Test
    fun `recent returns newest first with cap`() = runTest {
        val t = System.currentTimeMillis()
        repo.log(UsageRepository.TYPE_EARNED, null, 60)
        repo.log(UsageRepository.TYPE_EARNED, null, 120)

        repo.recent(limit = 1).test {
            val first = awaitItem()
            assertEquals(1, first.size)
            assertEquals(120, first.first().seconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `earnedSince sums only earned rows within the window`() = runTest {
        val t = System.currentTimeMillis()
        repo.log(UsageRepository.TYPE_EARNED, null, 60)
        repo.log(UsageRepository.TYPE_SPENT, "com.instagram", 30)
        repo.log(UsageRepository.TYPE_RECONCILED, "com.twitter.android", 90)

        repo.earnedSince(t - 60_000).test {
            assertEquals(60L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `spentToday counts live spend and reconciled time together`() = runTest {
        val t = System.currentTimeMillis()
        repo.logSpent("com.instagram", 300, t - 5 * 60_000, t - 2 * 60_000)
        repo.log(UsageRepository.TYPE_RECONCILED, "com.twitter.android", 600)
        repo.log(UsageRepository.TYPE_RECONCILED, "com.twitter.android", 600)

        repo.spentToday().test {
            assertEquals(1500L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blockedToday counts blocked events without counting spend rows`() = runTest {
        repo.log(UsageRepository.TYPE_BLOCKED, "com.instagram", 0)
        repo.log(UsageRepository.TYPE_BLOCKED, "com.twitter.android", 0)
        repo.log(UsageRepository.TYPE_SPENT, "com.instagram", 60)

        repo.blockedToday().test {
            assertEquals(2L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `per-package stats split blocked counts and spent seconds by app`() = runTest {
        repo.log(UsageRepository.TYPE_BLOCKED, "com.instagram", 0)
        repo.log(UsageRepository.TYPE_BLOCKED, "com.instagram", 0)
        repo.log(UsageRepository.TYPE_BLOCKED, "com.twitter.android", 0)
        repo.log(UsageRepository.TYPE_SPENT, "com.instagram", 60)
        repo.log(UsageRepository.TYPE_RECONCILED, "com.twitter.android", 120)
        repo.log(UsageRepository.TYPE_SPENT, "com.other", 90)

        repo.blockedCountsByPackageToday().test {
            val counts = awaitItem()
            assertEquals(listOf("com.instagram", "com.twitter.android"), counts.map { it.packageName })
            assertEquals(2L, counts.first().total)
            cancelAndIgnoreRemainingEvents()
        }
        repo.spentSecondsByPackageToday().test {
            val spent = awaitItem()
            // com.other is in the top-3 launchable apps but never blocked: the
            // spend list is not filtered to blocked packages — it reports every
            // app that burned browse time today.
            assertEquals(setOf("com.instagram", "com.twitter.android", "com.other"), spent.map { it.packageName }.toSet())
            assertEquals(120L, spent.first { it.packageName == "com.twitter.android" }.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `spentWithWindows returns only live spend rows overlapping the cutoff`() = runTest {
        val t = System.currentTimeMillis()
        repo.logSpent("com.instagram", 120, t - 60_000, t) // windowEnd == now → included
        repo.logSpent("com.twitter.android", 300, t - 10 * 60_000, t - 5 * 60_000) // ended before cutoff → excluded
        repo.log(UsageRepository.TYPE_RECONCILED, "com.instagram", 60) // not a SPENT row

        val rows = repo.spentWithWindows(from = t - 2 * 60_000)
        assertEquals(1, rows.size)
        assertEquals("com.instagram", rows.first().packageName)
        assertEquals(120, rows.first().seconds)
    }

    /**
     * The bug the access gate would have shipped with.
     *
     * [UsageRepository.earnedToday] computes its window start ONCE, when the
     * flow is built, so a long-lived collector keeps measuring "since the
     * moment I subscribed" no matter how much later it is. A screen never
     * notices. A gate collected for the life of the blocker process would have
     * opened after two hours and then never closed again.
     */
    @Test
    fun `earnedToday keeps a frozen window, which is why the gate cannot use it`() = runTest {
        var clock = 1_000_000_000_000L
        val movable = UsageRepository(dao) { clock }
        movable.log(UsageRepository.TYPE_EARNED, null, 7200)

        val seen = mutableListOf<Long>()
        val job = launch { movable.earnedToday().toList(seen) }
        runCurrent()
        assertEquals(7200L, seen.last())

        // Two days later. The reading is long outside any honest "last day".
        clock += 2 * DAY_MS
        advanceTimeBy(2 * DAY_MS)
        runCurrent()
        // Still counted: the window never moved.
        assertEquals(7200L, seen.last())

        job.cancel()
    }

    @Test
    fun `the gate's reading window actually moves`() = runTest {
        var clock = 1_000_000_000_000L
        val movable = UsageRepository(dao) { clock }
        movable.log(UsageRepository.TYPE_EARNED, null, 7200)

        val seen = mutableListOf<Long>()
        val job = launch { movable.readingInLastDay(tickMillis = 60_000).toList(seen) }
        runCurrent()
        assertEquals(7200L, seen.last())

        // Still inside the day: unchanged.
        clock += DAY_MS - 60_000
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(7200L, seen.last())

        // Past the day: it ages out on the next tick, with nothing having
        // happened in the database to signal it.
        clock += 120_000
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(0L, seen.last())

        job.cancel()
    }

    @Test
    fun `planning time is recorded apart from reading`() = runTest {
        var clock = 1_000_000_000_000L
        val movable = UsageRepository(dao) { clock }
        movable.log(UsageRepository.TYPE_EARNED, null, 600)
        movable.logPlanning(300)
        movable.logPlanning(0) // ignored: nothing happened

        val reading = mutableListOf<Long>()
        val planning = mutableListOf<Long>()
        val a = launch { movable.readingInLastDay().toList(reading) }
        val b = launch { movable.planningInLastDay().toList(planning) }
        runCurrent()

        assertEquals(600L, reading.last())
        assertEquals(300L, planning.last())
        assertEquals(1, dao.events.count { it.type == UsageRepository.TYPE_PLANNED })

        a.cancel()
        b.cancel()
    }

    @Test
    fun `prune removes only rows older than the cutoff`() = runTest {
        val now = System.currentTimeMillis()
        // Direct insert with an old timestamp (repo.log always stamps "now";
        // pruning works on the persisted timestamp, so the old row must be old).
        dao.insert(
            UsageEventEntity(
                timestamp = now - 40 * 24 * 3_600_000L,
                type = UsageRepository.TYPE_SPENT,
                packageName = "com.instagram",
                seconds = 60
            )
        )
        repo.log(UsageRepository.TYPE_EARNED, null, 30)

        repo.prune(keepDays = 30)
        val remaining = dao.events
        assertEquals(1, remaining.size)
        assertEquals(UsageRepository.TYPE_EARNED, remaining.single().type)
    }

    /** In-memory DAO mirroring the Room queries on a single sorted list. */
    private class FakeUsageEventDao : UsageEventDao {
        private val flow = MutableStateFlow<List<UsageEventEntity>>(emptyList())

        val events: List<UsageEventEntity> get() = flow.value

        override suspend fun insert(event: UsageEventEntity) {
            // Newest first, like ORDER BY timestamp DESC in the real DAO; inserting
            // at the head also handles rows with identical timestamps deterministically.
            flow.value = listOf(event) + flow.value
        }

        override fun observeRecent(limit: Int): Flow<List<UsageEventEntity>> =
            flow.map { it.take(limit) }

        override fun sumSince(type: String, since: Long): Flow<Long> =
            flow.map { list ->
                list.filter { it.type == type && it.timestamp >= since }.sumOf { it.seconds }
            }

        override fun sumOfTypesSince(types: List<String>, since: Long): Flow<Long> =
            flow.map { list ->
                list.filter { it.type in types && it.timestamp >= since }.sumOf { it.seconds }
            }

        override fun countSince(type: String, since: Long): Flow<Long> =
            flow.map { list ->
                list.count { it.type == type && it.timestamp >= since }.toLong()
            }

        override fun blockedCountsByPackageSince(since: Long): Flow<List<PackageTotal>> =
            flow.map { list ->
                list.filter { it.type == UsageRepository.TYPE_BLOCKED && it.timestamp >= since && it.packageName != null }
                    .groupingBy { it.packageName!! }
                    .eachCount()
                    .map { (pkg, count) -> PackageTotal(pkg, count.toLong()) }
                    .sortedByDescending { it.total }
            }

        override fun spentSecondsByPackageSince(since: Long): Flow<List<PackageTotal>> =
            flow.map { list ->
                list.filter {
                    it.type in listOf(UsageRepository.TYPE_SPENT, UsageRepository.TYPE_RECONCILED) &&
                        it.timestamp >= since && it.packageName != null
                }
                    .groupingBy { it.packageName!! }
                    .fold(0L) { acc, e -> acc + e.seconds }
                    .map { (pkg, total) -> PackageTotal(pkg, total) }
                    .sortedByDescending { it.total }
            }

        override suspend fun spentWithWindows(type: String, from: Long): List<UsageEventEntity> =
            flow.value.filter {
                it.type == type && it.windowEnd != null && it.windowEnd!! >= from
            }

        override suspend fun pruneOlderThan(cutoff: Long) {
            flow.value = flow.value.filter { it.timestamp >= cutoff }
        }
    }
}