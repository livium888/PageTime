package com.pagetime.app.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * On-device LLM inference via MediaPipe's tasks-genai runtime, powered by the
 * weights managed by [LumenModelStore].
 *
 * The model is loaded once per session and reused across requests, then
 * released when the session ends. The previous per-request load/close cycle
 * repeatedly allocated and freed ~800 MB of contiguous native memory, which on
 * many Android devices triggered a native OOM (SIGABRT/SIGSEGV) that Kotlin
 * try/catch cannot catch and that leaves no Java crash log.
 *
 * Session-scoped caching keeps one loaded instance alive, so a second card
 * capture in the same session skips the expensive native load entirely.
 *
 * Memory preflight: loading the model needs ~800 MB of contiguous native memory.
 * If the device cannot provide that, we skip the native call entirely and let
 * the caller fall back to the safe non-AI draft. This prevents OOM-driven
 * process kills that Kotlin cannot catch.
 */
class MediaPipeLlmProvider(
    private val context: Context,
    private val modelStore: LumenModelStore,
) : LlmProvider {
    override val kind: LlmProviderKind = LlmProviderKind.OFFLINE

    override val isAvailable: Boolean
        get() = modelStore.isInstalled() && modelStore.isModelFileIntact()

    private val inferenceMutex = Mutex()
    private val modelMutex = Mutex()

    /** Loaded model instance, kept alive for the session. Null when not loaded. */
    @Volatile
    private var cachedModel: LlmInference? = null

    /** How many in-flight requests are using the cached model. */
    @Volatile
    private var modelRefCount = 0

    /** Set after the first native crash/OOM — disables offline inference for the rest of the session. */
    @Volatile
    var nativeFailed = false
        private set

    /**
     * Tracks the model file's size at the last load, so a re-download that
     * replaces the file at the same path is detected and the stale native
     * handle is discarded before the next capture.
     */
    private var loadedModelSize: Long = -1

    private val modelFileSize: Long
        get() = modelStore.modelFile.length()

    init {
        // Observe the store's status so a model replacement (re-download) or
        // corruption is detected even when no capture is in flight. The store
        // emits on a background dispatcher; reset() is lightweight and safe to
        // call from any context.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            modelStore.status.collect { status ->
                when (status) {
                    is LumenModelStatus.Ready -> {
                        if (status.bytes != loadedModelSize) reset()
                    }
                    is LumenModelStatus.Downloading,
                        is LumenModelStatus.UpdateAvailable -> Unit
                    is LumenModelStatus.NotDownloaded,
                        is LumenModelStatus.Failed -> reset()
                }
            }
        }
    }

    override fun hasEnoughMemory(): Boolean = hasEnoughNativeMemory()

    override suspend fun generate(request: LlmRequest): Result<LlmResult> {
        if (nativeFailed) {
            return Result.failure(
                IllegalStateException("Offline model disabled after a previous failure")
            )
        }
        return try {
            Result.success(infer(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Offline model failed", error)
            nativeFailed = true
            releaseModel()
            Result.failure(error)
        }
    }

    /**
     * Loads the runtime once per session, runs the request against the cached
     * model, and releases the model when the last in-flight request returns.
     */
    private suspend fun infer(request: LlmRequest): LlmResult =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                require(isAvailable) {
                    "No offline model is installed. Download it in Settings first."
                }
                // Belt and braces: re-check right before native use. A corrupt
                // file aborts the process inside MediaPipe with no exception
                // Kotlin can catch — refuse the native call instead.
                require(modelStore.isModelFileIntact()) {
                    "The offline model file is damaged. Delete and re-download it in Settings."
                }
                require(hasEnoughNativeMemory()) {
                    "Not enough memory to load the offline model. Close other apps and try again."
                }

                val model = acquireModel()
                try {
                    val prompt = buildString {
                        request.systemInstruction?.let { append(it).append("\n\n") }
                        append(request.prompt)
                    }
                    Log.d(
                        TAG,
                        "Generating (model=${modelStore.modelFile.name}, " +
                            "remaining=${freeNativeMemoryMb()}MB)"
                    )
                    val text = model.generateResponse(prompt).trim()
                    check(text.isNotBlank()) { "The offline model returned an empty response" }
                    LlmResult(text, LlmProviderKind.OFFLINE)
                } finally {
                    releaseModel()
                }
            }
        }

    /**
     * Returns the cached model, loading it on first use. Subsequent callers
     * increment the reference count without reloading.
     */
    private suspend fun acquireModel(): LlmInference =
        modelMutex.withLock {
            // Re-check availability under the lock after the caller's preflight.
            if (cachedModel != null &&
                modelStore.isModelFileIntact() &&
                modelStore.modelFile.length() == loadedModelSize
            ) {
                modelRefCount++
                Log.d(TAG, "Reuse cached model (refCount=$modelRefCount)")
                return cachedModel!!
            }
            // Discard any stale handle whose backing file was replaced or
            // re-downloaded.
            cachedModel?.close()
            cachedModel = null
            modelRefCount = 0
            loadedModelSize = -1

            Log.d(TAG, "Loading model from ${modelStore.modelFile.absolutePath} " +
                "(available=${freeNativeMemoryMb()}MB)")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelStore.modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            val model = LlmInference.createFromOptions(context, options)
            cachedModel = model
            modelRefCount = 1
            loadedModelSize = modelStore.modelFile.length()
            Log.d(TAG, "Model loaded (refCount=$modelRefCount, size=${loadedModelSize}B)")
            model
        }

    /** Decrements the ref count and closes the cached model when the last user returns. */
    private suspend fun releaseModel() {
        modelMutex.withLock {
            if (modelRefCount <= 0) return
            modelRefCount--
            if (modelRefCount == 0) {
                cachedModel?.close()
                cachedModel = null
                Log.d(TAG, "Model released")
            }
        }
    }

    /**
     * Closes the cached model and clears the failure flag so the next capture
     * can retry. Called from the settings screen when the user re-downloads the
     * model, and from [generate] when a native failure is detected.
     */
    private suspend fun reset() {
        modelMutex.withLock {
            cachedModel?.close()
            cachedModel = null
            modelRefCount = 0
        }
        nativeFailed = false
        Log.d(TAG, "MediaPipeLlmProvider reset")
    }

    /**
     * Quick check: does the device have enough free memory for the ~521 MB model
     * plus runtime overhead? We need roughly 800 MB of headroom. If not, skip
     * the native call so the process survives.
     */
    private fun hasEnoughNativeMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeMb = memInfo.availMem / (1024 * 1024)
        Log.d(TAG, "Available native memory: ${freeMb}MB")
        return freeMb > MIN_MEMORY_MB
    }

    private fun freeNativeMemoryMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    companion object {
        private const val TAG = "MediaPipeLlm"
        private const val MIN_MEMORY_MB = 800L
    }
}
