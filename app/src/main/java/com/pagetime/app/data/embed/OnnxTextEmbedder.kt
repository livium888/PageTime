package com.pagetime.app.data.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * Turns a piece of text into one vector, using a sentence-embedding model run
 * by ONNX Runtime.
 *
 * Deliberately thin. Everything that can be wrong in a way that never throws —
 * tokenisation, masked pooling, normalisation — lives in [WordPieceTokenizer]
 * and [EmbeddingMath], where it is plain Kotlin and covered by tests. What is
 * left here is the part that genuinely needs the native runtime, and it fails
 * loudly when it fails at all.
 *
 * The model is a file on disk, downloaded like the LLM's weights rather than
 * bundled: an embedding model is tens of megabytes, which is nothing beside a
 * 529 MB language model, but it is still not something to put in an APK that
 * most readers will never ask to use.
 */
class OnnxTextEmbedder(
    private val modelFile: File,
    private val tokenizer: WordPieceTokenizer,
    /**
     * Tokens per text. Sentence-embedding models are trained at 128–256 and
     * degrade past their training length rather than improving, so this is a
     * property of the model and not a budget to spend.
     */
    private val maxLength: Int = 128,
) : AutoCloseable {

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession by lazy {
        require(modelFile.isFile) {
            "No embedding model at ${modelFile.absolutePath}. Download it first."
        }
        environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    /**
     * The unit-length vector for [text].
     *
     * Only the inputs this particular model declares are supplied. Exports
     * differ — some sentence-transformer models take token_type_ids and some
     * do not — and passing an input a model did not ask for is an error, while
     * omitting one it wants is a different error. Reading the names off the
     * session means one code path serves both rather than a guess that works
     * for whichever model was tried first.
     */
    fun embed(text: String): FloatArray {
        val encoded = tokenizer.encode(text, maxLength)
        val wanted = session.inputNames

        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            fun offer(name: String, values: List<Int>) {
                if (name in wanted) tensors[name] = longTensor(values)
            }
            offer("input_ids", encoded.ids)
            offer("attention_mask", encoded.attentionMask)
            offer("token_type_ids", encoded.tokenTypeIds)

            check(tensors.isNotEmpty()) {
                "This model declares inputs $wanted, none of which are the " +
                    "token inputs a sentence-embedding model is expected to take."
            }

            session.run(tensors).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: error("The model's first output is not a tensor.")
                val buffer = output.floatBuffer
                val total = buffer.remaining()
                // The shape is derived rather than read back: the sequence
                // length is one this code chose, so a total that is not a
                // whole number of positions means the output is not the
                // per-token hidden states this pooling assumes.
                check(total > 0 && total % maxLength == 0) {
                    "Expected a multiple of $maxLength values from the model, got $total. " +
                        "This export may already pool its output, in which case it needs " +
                        "no pooling here."
                }
                val hiddenSize = total / maxLength
                val flat = FloatArray(total)
                buffer.get(flat)
                return EmbeddingMath.l2Normalize(
                    EmbeddingMath.meanPool(flat, encoded.attentionMask, hiddenSize)
                )
            }
        } finally {
            // Native memory: every tensor is closed whether the run succeeded,
            // threw, or the model rejected the inputs.
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * A [1, maxLength] tensor of int64, which is what these models take.
     *
     * The buffer is direct and in native order because that is what the
     * runtime reads without copying; a heap buffer would work but would be
     * copied on every call, once per card being indexed.
     */
    private fun longTensor(values: List<Int>): OnnxTensor {
        val buffer: LongBuffer = ByteBuffer
            .allocateDirect(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        values.forEach { buffer.put(it.toLong()) }
        buffer.rewind()
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1L, values.size.toLong()),
        )
    }

    override fun close() {
        runCatching { session.close() }
    }
}
