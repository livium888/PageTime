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
            // Progress events only fire when the position actually changes
            // (like a real scroll/locator stream would).
            val next = (p + 0.02f).coerceAtMost(1f) // 2 %/s → whole book in 50 s
            if (next != p) {
                guard.onProgress(next, t)
                p = next
            }
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

    /**
     * The symptom that reached the device: a counter stuck at exactly two
     * minutes however long the reader read.
     *
     * Movement keeps a session alive but mints nothing — budget comes only
     * from forward content. A caller that reports page turns with
     * [ReadingGuard.onMovement] and never [ReadingGuard.onProgress] therefore
     * spends the opening allowance and earns nothing again, which is exactly
     * what the plain-text reader did: only the EPUB path reported progress.
     *
     * This test does not exercise that wiring — it is a ViewModel calling a
     * Compose callback, and neither compiles here. It pins the behaviour that
     * makes the symptom diagnosable: 120 seconds, then flat.
     */
    @Test
    fun `movement without progress earns the allowance and never more`() {
        var credited = 0L
        var t = 0L
        // Read for an hour, turning a page every ten seconds, reporting only
        // movement — never progress.
        repeat(360) {
            repeat(10) {
                t += S
                if (guard.onTick(t)) credited++
            }
            guard.onMovement(t)
        }
        assertTrue("earned more than the allowance without progress: $credited", credited <= 125)
        assertTrue("session should still be live", guard.state.crediting)
        assertEquals(0, guard.state.budgetSeconds)
    }

    /**
     * The same hour, with page turns reported as progress, earns properly.
     */
    @Test
    fun `the same reading reported as progress earns throughout`() {
        var credited = 0L
        var t = 0L
        var p = 0f
        repeat(360) {
            repeat(10) {
                t += S
                if (guard.onTick(t)) credited++
            }
            p += PAGE
            guard.onProgress(p, t)
        }
        assertTrue("an hour of real reading should earn most of it: $credited", credited > 3_000)
    }

    // --- Backgrounding, which used to reset everything ---

    /**
     * The hole. start() was wired to ON_RESUME, so a flick to the home screen
     * and back granted the opening allowance again. Sixty flicks was two hours
     * of credit for no reading at all.
     */
    @Test
    fun `flicking to the home screen and back does not refill the allowance`() {
        // Never touch a page. The only credit available in the whole test is
        // the opening allowance, and it is granted exactly once.
        var credited = 0L
        for (i in 1..200) if (guard.onTick(i * S)) credited++

        // Away and back, ten times over. Resuming keeps the session alive, so
        // crediting starts again and drains whatever allowance is LEFT — but
        // nothing new is ever minted, so the total is bounded by the one
        // allowance however many times the reader flicks home.
        var t = 300 * S
        repeat(10) {
            guard.resume(t)
            for (i in 1..200) {
                t += S
                if (guard.onTick(t)) credited++
            }
            t += 60 * S
        }

        // 120s of allowance. With start() on resume this was ~120 PER FLICK.
        assertTrue(
            "ten resumes minted more than the single opening allowance: $credited",
            credited <= 125
        )
    }

    /**
     * The worse half of the same bug: start() reset the watermark to zero, so
     * every page already paid for became farmable again. Backgrounding was a
     * one-tap reset of the anti-oscillation defence.
     */
    @Test
    fun `resuming does not make already-paid pages farmable again`() {
        // Read forward, paying for everything up to the watermark.
        var t = 0L
        var p = 0f
        repeat(120) { i ->
            p += PAGE / 40f
            t = (i + 1) * S
            guard.onProgress(p, t)
            guard.onTick(t)
        }

        // Drain every second of banked budget, moving (so the session stays
        // live) but never advancing. Measuring the PEAK budget instead would
        // prove nothing: it is capped, so a refill hides under the ceiling.
        while (guard.state.budgetSeconds > 0) {
            t += S
            guard.onMovement(t)
            guard.onTick(t)
        }

        // Home screen, back, then re-read the same pages from near the start.
        guard.resume(t + 60 * S)
        t += 61 * S
        guard.onProgress(0.0005f, t)
        var q = 0.0005f
        var credited = 0
        repeat(90) {
            q += PAGE / 40f
            t += S
            guard.onProgress(q, t)
            if (guard.onTick(t)) credited++
        }

        // Every one of those pages was already paid for, and the resume added
        // nothing, so there is nothing to credit.
        assertEquals("re-read paid content after a resume", 0, credited)
    }

    /**
     * Closing the book entirely and reopening it is a real start() — but the
     * watermark is seeded from the saved position, so the pages behind the
     * reader stay paid for.
     */
    @Test
    fun `reopening a part-read book cannot re-farm the pages behind it`() {
        val reopened = ReadingGuard()
        reopened.start(0L, alreadyRead = 0.40f)

        // Jump back to the start of the book and read forward through content
        // that was paid for in an earlier sitting.
        reopened.onProgress(0.10f, 1 * S)
        var q = 0.10f
        var minted = 0
        repeat(120) { i ->
            q += PAGE / 40f
            val t = (2 + i) * S
            reopened.onProgress(q, t)
            reopened.onTick(t)
            minted = max(minted, reopened.state.budgetSeconds)
        }
        // Only the opening allowance, never budget minted from the old pages.
        assertTrue("re-farmed content behind the watermark: $minted", minted <= 120)
    }

    /**
     * A cooldown that could be cleared by pressing home would not be a
     * cooldown.
     */
    @Test
    fun `the too-fast cooldown survives backgrounding`() {
        var t = 0L
        repeat(70) { i ->
            t = (i + 1) * S
            guard.onProgress(0.002f * (i + 1), t)
            guard.onTick(t)
        }
        assertTrue("expected a pace cooldown", guard.state.tooFast)

        guard.resume(t + 1 * S)
        guard.onTick(t + 2 * S)
        assertTrue("backgrounding cleared the cooldown", guard.state.tooFast)
    }
}
