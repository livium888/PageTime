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
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Lifecycle of the optional on-device LLM weights. The model is a single
 * MediaPipe `.task` file (Qwen 2.5 0.5B Instruct, q8) downloaded on demand —
 * it is never bundled into the APK, so the app stays small until the user
 * opts into offline AI.
 *
 * Updates: a cheap HEAD request against the model URL returns the remote
 * file's size and ETag. Each completed download stores that fingerprint in a
 * small sidecar file, so the app can tell "the model on Hugging Face changed"
 * from "up to date" without downloading anything. Every network lookup is
 * best-effort: offline or unreachable means "keep whatever you have".
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

    /** The installed file works, but the remote model has changed since install. */
    data class UpdateAvailable(val installedBytes: Long, val remoteBytes: Long) : LumenModelStatus

    data class Failed(val message: String) : LumenModelStatus
}

/** What the remote server reports about the model file (HEAD metadata). */
data class LumenRemoteModelInfo(
    val sizeBytes: Long,
    val etag: String?,
)

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
                // No call-timeout cap: OkHttp's callTimeout bounds the WHOLE
                // exchange (headers + body) and a ~521 MB file legitimately
                // takes minutes on slower connections. The client's 30 s read
                // timeout still aborts a stalled connection between reads.
                AppHttp.newClient(callTimeoutSeconds = 0L).newCall(request).execute().use { response ->
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
                        var lastProgressAt = 0L
                        while (true) {
                            // Safety net for a server that never ends the body:
                            // stop once the declared size is reached; the store's
                            // size verification catches any mismatch afterwards.
                            if (total > 0 && downloaded >= total) break
                            val read = source.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            // Report at least every 250 ms so the UI does not look
                            // stuck at 0 MB on slow connections for the first chunk.
                            val elapsed = now - lastProgressAt
                            if (downloaded >= total * PROGRESS_MIN_FRACTION || elapsed >= PROGRESS_MIN_INTERVAL_MS) {
                                onProgress(downloaded, total)
                                lastProgressAt = now
                            }
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
        // Fire progress at least every 250 ms so a slow start (0 MB for several
        // seconds) does not look frozen on the UI.
        const val PROGRESS_MIN_INTERVAL_MS = 250L
        // Also fire on the first ~2% so the UI immediately shows movement.
        const val PROGRESS_MIN_FRACTION = 0.02
    }
}

/**
 * Pure-Java structural check for the model file. A MediaPipe `.task` model is
 * a ZIP archive (a TFLite payload inside), so a corrupt or truncated download
 * fails ZIP parsing. The check is deliberately cheap (a few seeks at the head
 * and tail of the file, never the whole body) and fails closed: any anomaly
 * returns false.
 *
 * Why this exists: MediaPipe's native loader aborts the process on a corrupt
 * file — a SIGABRT/SIGSEGV that no Kotlin try/catch can catch and that leaves
 * no Java crash log. Validating the ZIP structure before the native call
 * converts a guaranteed process kill into a recoverable "re-download" state.
 */
object LumenModelIntegrity {
    /**
     * True when [file] is structurally a sound ZIP: a local header at offset 0,
     * an end-of-central-directory record at the tail, and a central directory
     * that is present, in-bounds, and fully walkable.
     */
    fun isZipIntact(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_ZIP_BYTES) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()

                // 1. A local file header must exist in the leading region.
                //    Not necessarily at offset 0: the real MediaPipe model file
                //    starts with a small prefix (4 zero bytes) before the ZIP
                //    payload, and other task files may carry metadata headers.
                require(hasLocalHeaderInLead(raf, fileLen)) { "no local file header" }

                // 2. The EOCD sits at the very end (fixed 22 bytes + comment).
                val eocdPos = findEocd(raf, fileLen) ?: return false

                // 3. Parse the EOCD: total entries @10 (2), cd size @12 (4),
                //    cd offset @16 (4) — all little-endian.
                val totalEntries = readShortLe(raf, eocdPos + 10).toInt()
                val cdSize = readIntLe(raf, eocdPos + 12).toLong()
                val cdOffset = readIntLe(raf, eocdPos + 16).toLong()
                require(totalEntries > 0) { "no entries" }
                require(cdOffset >= 0L && cdSize >= 0L) { "zip64 not supported" }
                require(cdOffset + cdSize.toLong() <= eocdPos) { "central directory past EOCD" }

                // 4. The central directory must start with a header record.
                require(readIntLe(raf, cdOffset) == SIG_CENTRAL_DIR) { "no central directory" }

                // 5. Walk every record so a truncated directory is caught, not
                //    just one whose start happens to look right.
                val cdEnd = cdOffset + cdSize.toLong()
                var pos = cdOffset
                var seen = 0
                while (seen < totalEntries && pos + MIN_CD_RECORD <= cdEnd) {
                    require(readIntLe(raf, pos) == SIG_CENTRAL_DIR) { "corrupt cd record" }
                    val nameLen = readShortLe(raf, pos + 28).toInt()
                    val extraLen = readShortLe(raf, pos + 30).toInt()
                    val commentLen = readShortLe(raf, pos + 32).toInt()
                    val recordLen = MIN_CD_RECORD + nameLen + extraLen + commentLen
                    require(pos + recordLen <= cdEnd) { "cd record out of bounds" }
                    pos += recordLen
                    seen++
                }
                require(seen == totalEntries) { "central directory truncated" }
                true
            }
        }.getOrDefault(false)
    }

    /** Whether any local file header signature appears in the first [LEAD_BYTES]. */
    private fun hasLocalHeaderInLead(raf: RandomAccessFile, fileLen: Long): Boolean {
        val leadLen = minOf(LEAD_BYTES, fileLen).toInt()
        val lead = ByteArray(leadLen)
        raf.seek(0)
        raf.readFully(lead)
        // 4-byte little-endian signature anywhere in the lead (offset +3 is safe:
        // LEAD_BYTES is far larger than the 4-byte window).
        for (i in 0..lead.size - 4) {
            if (readIntLe(lead, i) == SIG_LOCAL_HEADER) return true
        }
        return false
    }

    /**
     * Locates the EOCD by scanning the tail backwards for its signature, then
     * confirming the comment-length field lands exactly at end-of-file (the
     * real EOCD, not a signature that happens to appear inside a comment).
     */
    private fun findEocd(raf: RandomAccessFile, fileLen: Long): Long? {
        val searchStart = (fileLen - EOCD_FIXED - MAX_COMMENT).coerceAtLeast(0L)
        val searchLen = (fileLen - searchStart).toInt()
        val tail = ByteArray(searchLen)
        raf.seek(searchStart)
        raf.readFully(tail)
        var i = tail.size - EOCD_FIXED
        while (i >= 0) {
            if (readIntLe(tail, i) == SIG_EOCD) {
                val commentLen = (tail[i + 20].toInt() and 0xff) or
                    ((tail[i + 21].toInt() and 0xff) shl 8)
                if (searchStart + i + EOCD_FIXED + commentLen == fileLen) return searchStart + i
            }
            i--
        }
        return null
    }

    private fun readIntLe(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        val b = ByteArray(4)
        raf.readFully(b)
        return readIntLe(b, 0)
    }

    private fun readIntLe(b: ByteArray, offset: Int): Int {
        val i = offset
        return (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            ((b[i + 3].toInt() and 0xff) shl 24)
    }

    private fun readShortLe(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    }

    private const val SIG_LOCAL_HEADER = 0x04034b50 // PK\x03\x04
    private const val SIG_CENTRAL_DIR = 0x02014b50 // PK\x01\x02
    private const val SIG_EOCD = 0x06054b50 // PK\x05\x06
    private const val EOCD_FIXED = 22
    private const val MAX_COMMENT = 65_535L
    private const val MIN_CD_RECORD = 46L
    private const val MIN_ZIP_BYTES = 22L
    private const val LEAD_BYTES = 64 * 1024L
}

/** Reads the model's remote metadata (size + ETag) with a cheap HEAD request. */
object HfModelRemoteInfoFetcher {
    suspend fun fetch(url: String): LumenRemoteModelInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).head().build()
                AppHttp.newClient(callTimeoutSeconds = 30L).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val size = response.header("Content-Length")?.toLongOrNull() ?: return@use null
                    val etag = response.header("ETag") ?: response.header("X-Linked-ETag")
                    LumenRemoteModelInfo(size, etag)
                }
            }.getOrNull()
        }
}

/**
 * Owns the model file on disk. [expectedBytes] is injectable so tests can run
 * against a small fake file; production uses the pinned q8 weight size.
 * [remoteInfoFetcher] reports the server's current size/ETag; tests inject a
 * fake, production uses [HfModelRemoteInfoFetcher].
 */
class LumenModelStore(
    private val directory: File,
    private val downloader: LumenModelDownloader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val expectedBytes: Long = EXPECTED_MODEL_BYTES,
    private val remoteInfoFetcher: suspend (String) -> LumenRemoteModelInfo? = { null },
    private val integrityCheck: (File) -> Boolean = LumenModelIntegrity::isZipIntact,
) {
    private val _status = MutableStateFlow<LumenModelStatus>(currentStatus())
    val status: StateFlow<LumenModelStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    val modelFile: File
        get() = File(directory, MODEL_FILE_NAME)

    private val partFile: File
        get() = File(directory, "$MODEL_FILE_NAME.part")

    private val fingerprintFile: File
        get() = File(directory, "$MODEL_FILE_NAME.fp.json")

    /** True when a complete model file is on disk (size matches its fingerprint or the pinned size). */
    fun isInstalled(): Boolean {
        if (!modelFile.isFile) return false
        val length = modelFile.length()
        return readFingerprint()?.sizeBytes == length || length == expectedBytes
    }

    /**
     * True when the installed model file passes the structural ZIP check.
     * A corrupt file must never reach MediaPipe's native loader — that abort
     * cannot be caught in Kotlin and kills the process.
     */
    fun isModelFileIntact(): Boolean = integrityCheck(modelFile)

    private fun currentStatus(): LumenModelStatus =
        when {
            !isInstalled() -> LumenModelStatus.NotDownloaded
            !isModelFileIntact() -> LumenModelStatus.Failed(DAMAGED_MESSAGE)
            else -> LumenModelStatus.Ready(modelFile.length())
        }

    /**
     * Asks the server whether the model changed since it was installed.
     * Best-effort: any failure (offline, server error) leaves the current
     * status untouched. Legacy installs without a fingerprint are compared by
     * size alone, so they are never falsely flagged.
     */
    suspend fun checkForUpdate() {
        if (!isInstalled()) return
        val remote = fetchRemoteSafely() ?: return
        val installedBytes = modelFile.length()
        val fingerprint = readFingerprint()
        val changed =
            remote.sizeBytes != installedBytes ||
                (fingerprint?.etag != null && remote.etag != null && fingerprint.etag != remote.etag)
        _status.value =
            if (changed) {
                LumenModelStatus.UpdateAvailable(installedBytes, remote.sizeBytes)
            } else {
                LumenModelStatus.Ready(installedBytes)
            }
    }

    private suspend fun fetchRemoteSafely(): LumenRemoteModelInfo? =
        try {
            remoteInfoFetcher(MODEL_URL)
        } catch (_: Exception) {
            null
        }

    /**
     * Downloads (or updates) the model weights. Serialized: concurrent callers
     * wait on the mutex. New bytes land in a `.part` file, are verified
     * against the remote/pinned size, and only then replace the current
     * model — a failed or cancelled update leaves the installed model intact.
     */
    suspend fun download() =
        mutex.withLock {
            val remote = fetchRemoteSafely()
            val targetBytes = remote?.sizeBytes ?: expectedBytes
            if (isInstalled() && isModelFileIntact() && isCurrent(remote)) {
                _status.value = LumenModelStatus.Ready(modelFile.length())
                return@withLock
            }
            partFile.delete()
            _status.value = LumenModelStatus.Downloading(0, targetBytes)
            try {
                val result =
                    downloader.download(MODEL_URL, partFile) { downloaded, total ->
                        _status.value =
                            LumenModelStatus.Downloading(
                                downloaded,
                                total.takeIf { it > 0 } ?: targetBytes,
                            )
                    }
                result.fold(
                    onSuccess = { file ->
                        if (file.length() == targetBytes) {
                            if (!integrityCheck(file)) {
                                // A file with the right size but broken structure
                                // would abort MediaPipe's native loader. Reject it
                                // here instead of ever shipping it to the runtime.
                                partFile.delete()
                                _status.value = LumenModelStatus.Failed(DAMAGED_MESSAGE)
                                return@fold
                            }
                            writeFingerprint(ModelFingerprint(targetBytes, remote?.etag))
                            if (partFile.renameTo(modelFile)) {
                                _status.value = LumenModelStatus.Ready(modelFile.length())
                            } else {
                                partFile.delete()
                                _status.value =
                                    LumenModelStatus.Failed("Could not move the downloaded model into place")
                            }
                        } else {
                            partFile.delete()
                            _status.value =
                                LumenModelStatus.Failed(
                                    "Download incomplete (got ${file.length()} of " +
                                        "$targetBytes bytes). Check your connection and try again.",
                                )
                        }
                    },
                    onFailure = { error ->
                        partFile.delete()
                        _status.value = LumenModelStatus.Failed(error.message ?: "Model download failed")
                    },
                )
            } catch (cancelled: CancellationException) {
                // Leaving the settings screen mid-download: discard the partial
                // bytes and keep the previous model if there was one.
                partFile.delete()
                _status.value =
                    if (isInstalled()) LumenModelStatus.Ready(modelFile.length()) else LumenModelStatus.NotDownloaded
                throw cancelled
            }
        }

    /** Whether the installed file already matches what the server offers. */
    private fun isCurrent(remote: LumenRemoteModelInfo?): Boolean {
        if (remote == null) return true // nothing to compare against — keep what we have
        if (modelFile.length() != remote.sizeBytes) return false
        val fingerprint = readFingerprint()
        return fingerprint?.etag == null || remote.etag == null || fingerprint.etag == remote.etag
    }

    suspend fun deleteModel() =
        withContext(io) {
            modelFile.delete()
            partFile.delete()
            fingerprintFile.delete()
            _status.value = LumenModelStatus.NotDownloaded
        }

    private data class ModelFingerprint(val sizeBytes: Long, val etag: String?)

    private fun readFingerprint(): ModelFingerprint? =
        runCatching {
            if (!fingerprintFile.isFile) return null
            val root = JSONObject(fingerprintFile.readText())
            ModelFingerprint(
                sizeBytes = root.getLong("sizeBytes"),
                etag = root.optString("etag").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

    private fun writeFingerprint(fingerprint: ModelFingerprint) {
        runCatching {
            fingerprintFile.writeText(
                JSONObject()
                    .put("sizeBytes", fingerprint.sizeBytes)
                    .put("etag", fingerprint.etag ?: "")
                    .toString(),
            )
        }
    }

    companion object {
        const val MODEL_FILE_NAME = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "$MODEL_FILE_NAME?download=true"
        const val MODEL_LABEL = "Qwen 2.5 0.5B Instruct (q8)"
        const val EXPECTED_MODEL_BYTES = 546_660_344L

        /** Shown when the installed/downloaded file fails the structural check. */
        const val DAMAGED_MESSAGE =
            "The model file is damaged (incomplete or corrupted download). " +
                "Tap retry to download it again — it will be verified before installing."

        /** ~521 MB for display strings. */
        val MODEL_SIZE_MB: Int = (EXPECTED_MODEL_BYTES / 1_048_576).toInt()
    }
}
