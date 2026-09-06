package com.pagetime.app.data.embed

import kotlin.math.sqrt

/**
 * Turning a model's per-token output into one comparable vector.
 *
 * This is the second place in on-device retrieval where a mistake never
 * throws. A sentence-transformer model does not emit a sentence vector: it
 * emits one vector PER TOKEN, and the sentence vector is the mean of them.
 * Get the mean wrong — average in the padding, forget to normalise — and every
 * vector still has the right shape, similarity still returns a number, and the
 * neighbours are simply worse than they should be. Nothing anywhere reports a
 * problem. So the arithmetic lives here, in pure functions, with tests.
 */
object EmbeddingMath {

    /**
     * The masked mean over token vectors.
     *
     * [tokenVectors] is the model's output flattened row-major: position 0's
     * hidden vector, then position 1's, and so on — which is how it arrives
     * from a [1, sequence, hidden] tensor.
     *
     * Positions whose mask is zero are PADDING and must not be averaged in.
     * They are not neutral: the padding token has its own learned embedding,
     * so including it drags every short text toward the same point and makes
     * short texts look more alike than they are. That is the bug this function
     * exists to make impossible.
     */
    fun meanPool(
        tokenVectors: FloatArray,
        attentionMask: List<Int>,
        hiddenSize: Int,
    ): FloatArray {
        require(hiddenSize > 0) { "hiddenSize must be positive." }
        require(attentionMask.isNotEmpty()) { "An empty mask has nothing to pool." }
        require(tokenVectors.size == attentionMask.size * hiddenSize) {
            "Expected ${attentionMask.size * hiddenSize} values for " +
                "${attentionMask.size} positions of $hiddenSize, got ${tokenVectors.size}."
        }

        val summed = FloatArray(hiddenSize)
        var counted = 0
        attentionMask.forEachIndexed { position, keep ->
            if (keep == 0) return@forEachIndexed
            counted++
            val offset = position * hiddenSize
            for (d in 0 until hiddenSize) summed[d] += tokenVectors[offset + d]
        }
        // Every position masked out. Better a zero vector than a division by
        // zero: the caller's normalise step leaves it zero, and a zero vector
        // scores zero against everything rather than matching at random.
        if (counted == 0) return FloatArray(hiddenSize)
        for (d in 0 until hiddenSize) summed[d] /= counted
        return summed
    }

    /**
     * Scales to unit length, which is what makes cosine similarity a dot
     * product and lets vectors from texts of different lengths be compared at
     * all. A zero vector is returned unchanged rather than producing NaNs that
     * would poison every later comparison.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumOfSquares = 0.0
        for (value in vector) sumOfSquares += value.toDouble() * value.toDouble()
        val length = sqrt(sumOfSquares)
        if (length == 0.0) return vector.copyOf()
        return FloatArray(vector.size) { (vector[it] / length).toFloat() }
    }

    /**
     * Cosine similarity, in [-1, 1] for unit vectors.
     *
     * Divides by the magnitudes rather than assuming the inputs were
     * normalised. Assuming it is how a stored vector that skipped
     * normalisation silently outranks everything else purely by being longer.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) {
            "Cannot compare a ${a.size}-dimensional vector with a ${b.size}-dimensional one."
        }
        var dot = 0.0
        var aSquared = 0.0
        var bSquared = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            aSquared += a[i].toDouble() * a[i].toDouble()
            bSquared += b[i].toDouble() * b[i].toDouble()
        }
        val denominator = sqrt(aSquared) * sqrt(bSquared)
        if (denominator == 0.0) return 0f
        return (dot / denominator).toFloat()
    }

    /**
     * A vector as bytes for a database column, and back.
     *
     * Big-endian and explicit, so a stored vector still reads correctly on a
     * device with different byte order from the one that wrote it — the kind
     * of thing that would otherwise only surface as inexplicably bad results
     * on somebody else's phone.
     */
    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) {
            "A vector blob must be a whole number of floats, got ${bytes.size} bytes."
        }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }
}
