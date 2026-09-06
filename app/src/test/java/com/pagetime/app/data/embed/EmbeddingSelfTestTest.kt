package com.pagetime.app.data.embed

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-test is the thing that decides whether to trust every other piece,
 * so its own verdict has to be right. These drive it with embedders whose
 * behaviour is known exactly: a good one, and each way of being broken that
 * the real chain can be broken in without throwing.
 */
class EmbeddingSelfTestTest {

    /** Unit-length vector from a list of components. */
    private fun unit(vararg values: Float): FloatArray {
        val length = sqrt(values.fold(0.0) { acc, v -> acc + v * v })
        return FloatArray(values.size) { (values[it] / length).toFloat() }
    }

    /**
     * An embedder that behaves: paraphrases land near each other, unrelated
     * texts land far apart. Built by hand rather than by keyword, so it does
     * not accidentally reward the probes' shared words.
     */
    private val workingVectors: Map<String, FloatArray> = buildMap {
        val probes = EmbeddingSelfTest.PROBES
        // Two topics, roughly orthogonal; each related pair shares a topic.
        put(probes[0].first, unit(1f, 0f, 0.05f))
        put(probes[0].second, unit(0.95f, 0.05f, 0f))
        put(probes[1].first, unit(0f, 1f, 0.05f))
        put(probes[1].second, unit(0.05f, 0.95f, 0f))
        put(probes[2].second, unit(0f, 0.05f, 1f))
        put(probes[3].second, unit(0.05f, 0f, 1f))
    }

    @Test
    fun `a working embedder passes`() {
        val report = EmbeddingSelfTest.run { workingVectors.getValue(it) }
        assertTrue(report.failure == null)
        assertTrue("separation was ${report.separation}", report.passed)
        assertTrue(report.separation >= EmbeddingSelfTest.REQUIRED_SEPARATION)
    }

    @Test
    fun `every probe is measured`() {
        val report = EmbeddingSelfTest.run { workingVectors.getValue(it) }
        assertEquals(EmbeddingSelfTest.PROBES.size, report.measurements.size)
    }

    /**
     * The failure this whole exercise exists to catch. Averaging the padding
     * in, or a vocabulary offset, drags everything toward one point: vectors
     * are still valid, similarity still returns numbers, and everything looks
     * mildly similar to everything else.
     */
    @Test
    fun `an embedder that calls everything similar fails`() {
        val report = EmbeddingSelfTest.run { unit(1f, 0.02f, 0.02f) }
        assertFalse(report.passed)
    }

    /** Vectors that are all noise: no ordering, so no usable neighbours. */
    @Test
    fun `an embedder with no signal fails`() {
        var n = 0
        val report = EmbeddingSelfTest.run {
            n++
            unit((n % 7).toFloat() + 0.1f, (n % 3).toFloat() + 0.1f, (n % 5).toFloat() + 0.1f)
        }
        assertFalse(report.passed)
    }

    /**
     * A zero vector is similar to nothing, itself included. The ordering test
     * alone cannot see this — every pair scores 0, so nothing beats anything —
     * which is exactly why the identity check exists beside it.
     */
    @Test
    fun `a degenerate embedder fails on the identity check`() {
        val report = EmbeddingSelfTest.run { FloatArray(3) }
        assertFalse(report.passed)
        assertTrue(report.identity < EmbeddingSelfTest.MIN_IDENTITY)
    }

    @Test
    fun `a model that cannot load is reported rather than thrown`() {
        val report = EmbeddingSelfTest.run { error("No embedding model on disk") }
        assertFalse(report.passed)
        assertEquals("No embedding model on disk", report.failure)
        assertFalse(report.ran)
    }

    @Test
    fun `the summary leads with the verdict`() {
        val good = EmbeddingSelfTest.summarize(
            EmbeddingSelfTest.run { workingVectors.getValue(it) }
        )
        assertTrue(good.first().startsWith("Working"))

        val bad = EmbeddingSelfTest.summarize(EmbeddingSelfTest.run { unit(1f, 0.02f, 0.02f) })
        assertTrue(bad.first().startsWith("Broken"))

        val failed = EmbeddingSelfTest.summarize(EmbeddingSelfTest.run { error("boom") })
        assertEquals(1, failed.size)
        assertTrue(failed.first().contains("boom"))
    }

    /** Related and unrelated probes both have to exist or the gap is meaningless. */
    @Test
    fun `probes cover both cases`() {
        assertTrue(EmbeddingSelfTest.PROBES.any { it.related })
        assertTrue(EmbeddingSelfTest.PROBES.any { !it.related })
    }
}
