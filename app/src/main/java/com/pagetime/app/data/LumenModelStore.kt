package com.pagetime.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Lifecycle of the optional on-device LLM weights. The model is a single
 * MediaPipe `.task` file (Qwen 2.5 0.5B Instruct, q8) downloaded on demand —
 * it is never bundled into the APK, so the app stays small until the user
 * opts into offline AI.
 */
sealed interface LumenModelStatus {
    data object NotDownloaded : LumenModelStatus

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LumenModelStatus {
        val fraction: Float
            get() =
                if (totalBytes > 0) {
                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
    }

    data class Ready(val bytes: Long) : LumenModelStatus

    data class Failed(val message: String) : LumenModelStatus
}

/** Streams a model file; injected so unit tests can fake the network. */
interface LumenModelDownloader {
    /** Downloads [url] into [target], reporting (downloaded, total) bytes. */
    suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): Result<File>
}

/** Real downloader backed by the app's resilient OkHttp client. */
class OkHttpLumenModelDownloader : LumenModelDownloader {
    override suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                AppHttp.newClient(callTimeoutSeconds = 30L).newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Model download failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Model download returned no body")
                    val total =
                        body.contentLength()
                            .takeIf { it > 0 }
                            ?: LumenModelStore.EXPECTED_MODEL_BYTES
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val source = body.source()
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded % PROGRESS_STEP == 0L) onProgress(downloaded, total)
                        }
                        onProgress(downloaded, total)
                    }
                    target
                }
            }
        }

    private companion object {
        const val PROGRESS_STEP = 512 * 1024L
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    }
}

/**
 * Owns the model file on disk. [expectedBytes] is injectable so tests can run
 * against a small fake file; production uses the pinned q8 weight size.
 */
class LumenModelStore(
    private val directory: File,
    private val downloader: LumenModelDownloader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val expectedBytes: Long = EXPECTED_MODEL_BYTES,
) {
    private val _status = MutableStateFlow<LumenModelStatus>(currentStatus())
    val status: StateFlow<LumenModelStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    val modelFile: File
        get() = File(directory, MODEL_FILE_NAME)

    /** True when a complete, correctly-sized model file is on disk. */
    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() == expectedBytes

    private fun currentStatus(): LumenModelStatus =
        if (isInstalled()) {
            LumenModelStatus.Ready(modelFile.length())
        } else {
            LumenModelStatus.NotDownloaded
        }

    /**
     * Downloads the model weights. Serialized: concurrent callers wait on the
     * mutex, so a double-tap can't start two downloads. A stale partial file
     * from a cancelled run is discarded first.
     */
    suspend fun download() =
        mutex.withLock {
            if (isInstalled()) {
                _status.value = LumenModelStatus.Ready(modelFile.length())
                return@withLock
            }
            modelFile.delete()
            _status.value = LumenModelStatus.Downloading(0, expectedBytes)
            try {
                val result =
                    downloader.download(MODEL_URL, modelFile) { downloaded, total ->
                        _status.value =
                            LumenModelStatus.Downloading(
                                downloaded,
                                total.takeIf { it > 0 } ?: expectedBytes,
                            )
                    }
                result.fold(
                    onSuccess = { file ->
                        if (file.length() == expectedBytes) {
                            _status.value = LumenModelStatus.Ready(file.length())
                        } else {
                            file.delete()
                            _status.value =
                                LumenModelStatus.Failed(
                                    "Download incomplete (got ${file.length()} of " +
                                        "$expectedBytes bytes). Check your connection and try again.",
                                )
                        }
                    },
                    onFailure = { error ->
                        modelFile.delete()
                        _status.value =
                            LumenModelStatus.Failed(
                                error.message ?: "Model download failed",
                            )
                    },
                )
            } catch (cancelled: CancellationException) {
                // Leaving the settings screen mid-download: return to the clean state.
                _status.value = LumenModelStatus.NotDownloaded
                throw cancelled
            }
        }

    suspend fun deleteModel() =
        withContext(io) {
            modelFile.delete()
            _status.value = LumenModelStatus.NotDownloaded
        }

    companion object {
        const val MODEL_FILE_NAME = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "$MODEL_FILE_NAME?download=true"
        const val MODEL_LABEL = "Qwen 2.5 0.5B Instruct (q8)"
        const val EXPECTED_MODEL_BYTES = 546_660_344L

        /** ~521 MB for display strings. */
        val MODEL_SIZE_MB: Int = (EXPECTED_MODEL_BYTES / 1_048_576).toInt()
    }
}
