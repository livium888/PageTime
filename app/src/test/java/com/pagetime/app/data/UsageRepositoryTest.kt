package com.pagetime.app.data

import app.cash.turbine.test
import com.pagetime.app.data.local.PackageTotal
import com.pagetime.app.data.local.UsageEventDao
import com.pagetime.app.data.local.UsageEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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