package com.pagetime.app.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max

/**
 * Cheat-scenario tests for the anti-cheat engine. All time is virtual
 * (milliseconds since boot passed explicitly).
 *
 * Page size assumption: one page ≈ 0.0017 of the book (≈1/600th, typical novel),
 * so an honest reader turns ~1 page / 30–45 s.
 */
class ReadingGuardTest {

    private companion object {
        const val PAGE = 0.0017f
        const val S = 1000L // one second in ms
    }

    private lateinit var guard: ReadingGuard

    @Before
    fun setUp() {
        guard = ReadingGuard()
        guard.start(0L)
    }

    @Test
    fun `phone left open untouched earns almost nothing`() {
        var credited = 0L
        for (i in 1..200) {
            if (guard.onTick(i * S)) credited++
        }
        assertTrue("untouched session must earn < 95s, was $credited", credited < 95)
        assertFalse(guard.state.crediting)
        assertTrue(guard.state.showIdleGate)
    }

    @Test
    fun `oscillation farming dries up - next prev paging earns bounded time`() {
        // Open mid-book, then robotically flip between two pages forever.
        guard.onProgress(0.500f, 1 * S)
        guard.onProgress(0.502f, 2 * S) // one small genuine step mints once

        var creditedTotal = 0L
        var creditedTail = 0L
        var flip = false
        for (i in 3..1500) {
            val t = i * S
            flip = !flip
            guard.onProgress(if (flip) 0.500f else 0.502f, t) // movement → never idle
            if (guard.onTick(t)) {
                creditedTotal++
                if (t >= 800 * S) creditedTail++
            }
        }
        // Old engine: unbounded (1500 s here). New engine: hard-capped by budget…
        assertTrue("farm must stay under 310s, was $creditedTotal", creditedTotal <= 310)
        // …and finite: the tail earns nothing at all.
        assertEquals("tail of farm must earn nothing", 0L, creditedTail)
    }

    @Test
    fun `honest reader sustains full-rate earning for an hour`() {
        var credited = 0L
        var p = 0f
        for (i in 1..3600) {
            val t = i * S
            p += PAGE / 40f // 1 page / 40 s
            guard.onProgress(p, t)
            if (guard.onTick(t)) credited++
        }
        assertEquals("honest reading must never be throttled", 3600L, credited)
    }

    @Test
    fun `slow honest reader is not punished`() {
        var credited = 0L
        var p = 0f
        for (i in 1..3600) {
            val t = i * S
            p += PAGE / 120f // 1 page / 2 min — very slow, but human
            guard.onProgress(p, t)
            if (guard.onTick(t)) credited++
        }
        assertTrue("slow reader should still earn most seconds, was $credited", credited > 3000)
    }

    @Test
    fun `toc jump mints no budget and blocks re-farming the skipped region`() {
        guard.onProgress(0.05f, 1 * S) // genuine reading to 5%
        val budgetBefore = guard.state.budgetSeconds

        guard.onProgress(0.60f, 1800L) // instant jump: +55% in <2 s
        assertEquals(
            "navigation jumps must mint nothing",
            budgetBefore.toLong(), guard.state.budgetSeconds.toLong()
        )

        // Scrolling back into the skipped region and forward again must not
        // re-mint anything either (watermark already moved past it).
        val b2 = guard.state.budgetSeconds
        guard.onProgress(0.30f, 10 * S)
        guard.onProgress(0.59f, 11 * S)
        assertEquals(b2, guard.state.budgetSeconds)
    }

    @Test
    fun `auto-scroll fling through the book triggers pace cooldown`() {
        var credited = 0L
        var sawTooFast = false
        var p = 0f
        for (i in 1..300) {
            val t = i * S
            p = (p + 0.02f).coerceAtMost(1f) // 2 %/s → whole book in 50 s
            guard.onProgress(p, t)
            if (guard.onTick(t)) credited++
            sawTooFast = sawTooFast || guard.state.tooFast
        }
        assertTrue("pace limiter must engage", sawTooFast)
        assertTrue("fling must earn far less than wall clock, was $credited", credited < 150)
    }

    @Test
    fun `continue tap hides the gate but grants nothing`() {
        for (i in 1..100) guard.onTick(i * S) // go fully idle
        assertTrue(guard.state.showIdleGate)
        assertFalse(guard.state.crediting)

        val before = guard.state.budgetSeconds
        guard.onContinueTapped(101 * S)
        assertFalse("gate must hide after tap", guard.state.showIdleGate)
        assertFalse("tap alone must not resume crediting", guard.state.crediting)

        guard.onTick(110 * S) // still inside the grace window
        assertFalse(guard.state.showIdleGate)
        guard.onTick(117 * S) // grace over, still no movement
        assertTrue(guard.state.showIdleGate)
        assertEquals(before.toLong(), guard.state.budgetSeconds.toLong())
    }

    @Test
    fun `rereading already-paid content never mints new budget`() {
        // Read honestly to just past 0.5%.
        var p = 0f
        repeat(120) { i ->
            p += PAGE / 40f
            guard.onProgress(p, (i + 1) * S)
            guard.onTick((i + 1) * S)
        }
        val budgetAfterFirstRead = guard.state.budgetSeconds

        // Navigate back to 0.1% and re-read the SAME content slowly.
        guard.onProgress(0.001f, 130 * S)
        var q = 0.001f
        var maxBudgetSeen = 0
        repeat(90) { i ->
            q += PAGE / 40f // stays below the 0.51% watermark the whole time
            val t = 131 * S + i * S
            guard.onProgress(q, t)
            guard.onTick(t)
            maxBudgetSeen = max(maxBudgetSeen, guard.state.budgetSeconds)
        }
        assertTrue(
            "re-reading paid content must not extend budget (max $maxBudgetSeen vs $budgetAfterFirstRead)",
            maxBudgetSeen <= budgetAfterFirstRead
        )
    }
}
