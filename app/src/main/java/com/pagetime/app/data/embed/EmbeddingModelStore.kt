package com.pagetime.app.data.embed

import com.pagetime.app.data.LumenModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Where the embedding model and its vocabulary are fetched from. */
data class EmbeddingModelSource(
    val modelUrl: String,
    val vocabUrl: String,
    val label: String,
)

sealed interface EmbeddingModelStatus {
    data object NotDownloaded : EmbeddingModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : EmbeddingModelStatus
    data object Ready : EmbeddingModelStatus
    data class Failed(val message: String) : EmbeddingModelStatus
}

/**
 * Owns the two files retrieval needs: the ONNX weights and the vocabulary the
 * tokeniser is built from.
 *
 * They are downloaded rather than bundled, and they are downloaded TOGETHER
 * because they are only meaningful as a pair. A vocabulary from one model with
 * the weights of another produces ids that index the wrong embedding rows —
 * valid vectors, quietly wrong, exactly the failure the tokeniser tests exist
 * to prevent upstream. So a partial install is treated as no install.
 */
class EmbeddingModelStore(
    private val directory: File,
    private val downloader: LumenModelDownloader,
    private val source: suspend () -> EmbeddingModelSource,
) {
    private val mutex = Mutex()

    private val _status = MutableStateFlow<EmbeddingModelStatus>(
        if (isInstalled()) EmbeddingModelStatus.Ready else EmbeddingModelStatus.NotDownloaded
    )
    val status: StateFlow<EmbeddingModelStatus> = _status.asStateFlow()

    val modelFile: File get() = File(directory, MODEL_FILE_NAME)
    val vocabFile: File get() = File(directory, VOCAB_FILE_NAME)

    /** Both files present and each big enough to be the thing it claims to be. */
    fun isInstalled(): Boolean =
        modelFile.length() >= MIN_MODEL_BYTES && vocabFile.length() >= MIN_VOCAB_BYTES

    /**
     * What identifies the vectors this model produces, or null when nothing is
     * installed.
     *
     * Lives here rather than in either indexer because cards and book text are
     * indexed by the SAME model and their vectors have to sit in the same
     * space. Two copies of this string that drifted apart would put a card and
     * a paragraph in different spaces while both looked correctly labelled —
     * comparable by the code, meaningless in fact.
     *
     * The file's length is part of it deliberately. The label alone stays the
     * same if the weights underneath are replaced, and the tables would then
     * hold two incompatible spaces under one name.
     */
    fun modelId(): String? {
        if (!isInstalled()) return null
        return "${DEFAULT_SOURCE.label}/${modelFile.length()}"
    }

    /**
     * A tokeniser built from the downloaded vocabulary, or null when the pair
     * is not installed or the vocabulary is not one.
     *
     * This IS the vocabulary's integrity check, and a strong one: the
     * tokeniser's constructor refuses a table without [CLS], [SEP], [UNK] and
     * [PAD], which no truncated download and no HTML error page can satisfy.
     */
    fun tokenizer(): WordPieceTokenizer? {
        if (!isInstalled()) return null
        return runCatching {
            WordPieceTokenizer(vocabFile.inputStream().use { WordPieceVocabulary.fromStream(it) })
        }.getOrNull()
    }

    /**
     * Fetches both files, newest first into temporary names, and only adopts
     * them once BOTH have arrived and the pair has been checked. A download
     * interrupted halfway leaves whatever was installed before untouched.
     */
    suspend fun download() = mutex.withLock {
        val urls = runCatching { source() }.getOrNull()
        if (urls == null) {
            _status.value = EmbeddingModelStatus.Failed("No embedding model is configured.")
            return@withLock
        }
        val modelPart = File(directory, "$MODEL_FILE_NAME.part")
        val vocabPart = File(directory, "$VOCAB_FILE_NAME.part")
        directory.mkdirs()
        modelPart.delete()
        vocabPart.delete()
        _status.value = EmbeddingModelStatus.Downloading(0, 0)
        try {
            val model = downloader.download(urls.modelUrl, modelPart) { done, total ->
                _status.value = EmbeddingModelStatus.Downloading(done, total)
            }
            if (model.isFailure) {
                fail(modelPart, vocabPart, model.exceptionOrNull()?.message ?: "Model download failed")
                return@withLock
            }
            val vocab = downloader.download(urls.vocabUrl, vocabPart) { _, _ -> }
            if (vocab.isFailure) {
                fail(modelPart, vocabPart, vocab.exceptionOrNull()?.message ?: "Vocabulary download failed")
                return@withLock
            }
            if (modelPart.length() < MIN_MODEL_BYTES) {
                fail(
                    modelPart, vocabPart,
                    "That URL returned ${modelPart.length()} bytes, too small to be a model. " +
                        "It is most likely a redirect or an error page rather than the file."
                )
                return@withLock
            }
            // The real check on the vocabulary: can a tokeniser be built from
            // it at all? Size alone would accept an HTML error page.
            val usable = runCatching {
                WordPieceTokenizer(vocabPart.inputStream().use { WordPieceVocabulary.fromStream(it) })
            }.isSuccess
            if (!usable) {
                fail(
                    modelPart, vocabPart,
                    "That file is not a WordPiece vocabulary — it has no [CLS]/[SEP]/[UNK]/[PAD]. " +
                        "Check the link points at the model's vocab.txt."
                )
                return@withLock
            }
            if (!modelPart.renameTo(modelFile) || !vocabPart.renameTo(vocabFile)) {
                fail(modelPart, vocabPart, "Could not move the downloaded files into place")
                return@withLock
            }
            _status.value = EmbeddingModelStatus.Ready
        } catch (cancelled: CancellationException) {
            modelPart.delete()
            vocabPart.delete()
            _status.value =
                if (isInstalled()) EmbeddingModelStatus.Ready else EmbeddingModelStatus.NotDownloaded
            throw cancelled
        }
    }

    private fun fail(modelPart: File, vocabPart: File, message: String) {
        modelPart.delete()
        vocabPart.delete()
        _status.value = EmbeddingModelStatus.Failed(message)
    }

    suspend fun delete() = mutex.withLock {
        modelFile.delete()
        vocabFile.delete()
        _status.value = EmbeddingModelStatus.NotDownloaded
    }

    companion object {
        const val MODEL_FILE_NAME = "embedding-model.onnx"
        const val VOCAB_FILE_NAME = "embedding-vocab.txt"

        /**
         * Floors, not expectations. The point is to reject a redirect page or a
         * truncated transfer, not to pin a particular model — the reader can
         * point this at any sentence-embedding export, and those range from
         * about 20 MB quantised to a few hundred.
         */
        const val MIN_MODEL_BYTES = 1_000_000L
        const val MIN_VOCAB_BYTES = 10_000L

        /**
         * The default pair, and the first URLs in this feature that were
         * actually followed before being written down.
         *
         * The agent's own container cannot reach huggingface.co, so these were
         * checked by a CI job that can (.github/workflows/probe-model-urls.yml).
         * What it found, and why these two addresses rather than the obvious
         * ones:
         *
         *   sentence-transformers/all-MiniLM-L6-v2 onnx/model.onnx
         *       200, 90,405,214 bytes — real, but fp32 and four times the size
         *   sentence-transformers/all-MiniLM-L6-v2 onnx/model_quantized.onnx
         *       404 — the address that follows the obvious pattern, sits beside
         *       a file that does exist, and is simply not there
         *   Xenova/all-MiniLM-L6-v2 onnx/model_quantized.onnx
         *       200, 22,972,370 bytes — int8, and what ships here
         *
         * Not the multi-qa variant, though it is byte-for-byte nearly the same
         * size and equally reachable. That one is trained for ASYMMETRIC
         * retrieval — a short query against a long passage. A slip box compares
         * a note against other notes, which is symmetric, and the general model
         * is the right tool for it. The wrong one would work just badly enough
         * not to notice.
         *
         * Both files come from the same repository. That is not tidiness: a
         * vocabulary paired with another model's weights indexes the wrong rows
         * of the embedding matrix and fails silently, which is the whole reason
         * the download above adopts the two files together or neither.
         */
        val DEFAULT_SOURCE = EmbeddingModelSource(
            modelUrl =
                "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/" +
                    "onnx/model_quantized.onnx",
            vocabUrl = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt",
            label = "all-MiniLM-L6-v2 (int8, 22 MB)",
        )

        /** Roughly what [DEFAULT_SOURCE] weighs, for the sentence before the download. */
        const val DEFAULT_MODEL_BYTES = 22_972_370L
    }
}
