package com.pagetime.app.data.embed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pooling and normalisation are the second silent-failure point in on-device
 * retrieval: a wrong mean still has the right shape, similarity still returns
 * a number, and the neighbours are merely worse. Pinned here for the same
 * reason the tokeniser is.
 */
class EmbeddingMathTest {

    @Test
    fun `pooling averages only the positions the mask keeps`() {
        // The bug this exists to prevent. Padding is NOT neutral — it has its
        // own learned embedding — so averaging it in drags every short text
        // toward the same point and makes short texts look alike.
        val tokenVectors = floatArrayOf(
            1f, 1f,      // real
            3f, 3f,      // real
            100f, 100f,  // padding, must not count
        )
        val pooled = EmbeddingMath.meanPool(
            tokenVectors = tokenVectors,
            attentionMask = listOf(1, 1, 0),
            hiddenSize = 2,
        )
        assertArrayEquals(floatArrayOf(2f, 2f), pooled, 1e-6f)
    }

    @Test
    fun `pooling with nothing kept gives zeros rather than dividing by zero`() {
        val pooled = EmbeddingMath.meanPool(
            tokenVectors = floatArrayOf(5f, 5f),
            attentionMask = listOf(0),
            hiddenSize = 2,
        )
        assertArrayEquals(floatArrayOf(0f, 0f), pooled, 1e-6f)
    }

    @Test
    fun `a mismatched tensor is refused, not quietly misread`() {
        val problem = runCatching {
            EmbeddingMath.meanPool(floatArrayOf(1f, 2f, 3f), listOf(1, 1), hiddenSize = 2)
        }.exceptionOrNull()
        assertTrue("expected a clear failure, got $problem", problem is IllegalArgumentException)
    }

    @Test
    fun `normalising gives unit length`() {
        val unit = EmbeddingMath.l2Normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), unit, 1e-6f)
        val length = sqrt(unit.fold(0.0) { acc, v -> acc + v * v })
        assertTrue("length was $length", abs(length - 1.0) < 1e-6)
    }

    @Test
    fun `normalising a zero vector does not produce NaN`() {
        // A NaN here would poison every comparison the vector ever takes part
        // in, and NaN compares false against everything, so it would look like
        // a card simply never matches.
        val zero = EmbeddingMath.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue("got ${zero.toList()}", zero.all { !it.isNaN() && it == 0f })
    }

    @Test
    fun `similarity runs from one to minus one`() {
        val a = floatArrayOf(1f, 0f)
        assertEquals(1f, EmbeddingMath.cosineSimilarity(a, floatArrayOf(1f, 0f)), 1e-6f)
        assertEquals(-1f, EmbeddingMath.cosineSimilarity(a, floatArrayOf(-1f, 0f)), 1e-6f)
        assertEquals(0f, EmbeddingMath.cosineSimilarity(a, floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `similarity does not assume its inputs were normalised`() {
        // Assuming it is how a vector that skipped normalisation outranks
        // everything else purely by being longer.
        val similarity = EmbeddingMath.cosineSimilarity(
            floatArrayOf(3f, 0f),
            floatArrayOf(5f, 0f),
        )
        assertEquals(1f, similarity, 1e-6f)
    }

    @Test
    fun `similarity against a zero vector is zero, not NaN`() {
        val similarity = EmbeddingMath.cosineSimilarity(
            floatArrayOf(0f, 0f),
            floatArrayOf(1f, 1f),
        )
        assertEquals(0f, similarity, 1e-6f)
    }

    @Test
    fun `a vector survives a round trip through the database column`() {
        val original = floatArrayOf(1.5f, -2.25f, 0f, 1e-7f)
        val restored = EmbeddingMath.fromBytes(EmbeddingMath.toBytes(original))
        assertArrayEquals(original, restored, 0f)
    }

    @Test
    fun `a blob that is not whole floats is refused`() {
        val problem = runCatching {
            EmbeddingMath.fromBytes(ByteArray(7))
        }.exceptionOrNull()
        assertTrue("expected a clear failure, got $problem", problem is IllegalArgumentException)
    }
}
